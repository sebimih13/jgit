/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.treewalk;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.events.IndexChangedEvent;
import org.eclipse.jgit.internal.storage.file.UntrackedCache;
import org.eclipse.jgit.internal.storage.file.UntrackedCache.Directory;
import org.eclipse.jgit.internal.storage.file.UntrackedCacheConfig;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.util.FS_POSIX;
import org.eclipse.jgit.util.FsMonitor;
import org.eclipse.jgit.util.SystemReader;

/**
 * Capture complete API traversal before optional, checksum-guarded publication
 * in the configured native untracked mode.
 */
final class UntrackedCacheScan {
	private record Ignore(Path path, int[] stat, ObjectId raw,
			ObjectId nativeId, boolean directoryBoundAbsence) {
		boolean unchanged() throws IOException {
			// Per-directory absence is protected by the surrounding
			// before/after
			// and pre-publication directory stat checks. Global inputs are not.
			if (directoryBoundAbsence)
				return true;
			Ignore now = ignore(path);
			return Arrays.equals(stat, now.stat) && raw.equals(now.raw);
		}
	}

	private record Observed(Path path, int[] stat, Ignore ignore) {
	}

	private final Repository repository;

	private final Path root;

	private final String rootPrefix;

	private final int directoryFlags;

	private final UntrackedCacheConfig.Mode policyMode;

	private final Ignore info;

	private final Ignore global;

	private final Map<String, Observed> directories = new LinkedHashMap<>();

	private final Set<String> directoryEntries = new HashSet<>();

	private boolean failed;

	private FsMonitor.Publication publication;

	FsMonitor.Publication getPublication() {
		return publication;
	}

	static UntrackedCacheScan create(Repository repository) {
		try {
			return eligible(repository) ? new UntrackedCacheScan(repository)
					: null;
		} catch (IOException | RuntimeException unsupported) {
			return null;
		}
	}

	private static boolean eligible(Repository repository) {
		if (repository.isBare()
				|| repository.getFS().getClass() != FS_POSIX.class
				|| FsMonitor.isConfigured(repository)
						&& !FsMonitor.isHookConfigured(repository)
						&& !Files.exists(repository.getDirectory().toPath()
								.resolve("fsmonitor--daemon.ipc")) //$NON-NLS-1$
				|| directoryFlags(repository) < 0
						&& repository.getConfig().get(UntrackedCacheConfig.KEY)
								.getMode() != UntrackedCacheConfig.Mode.REMOVE
				|| repository.getConfig().get(WorkingTreeOptions.KEY)
						.isDirNoGitLinks()
				|| SystemReader.getInstance()
						.getenv("GIT_DISABLE_UNTRACKED_CACHE") != null) { //$NON-NLS-1$
			return false;
		}
		String optional = SystemReader.getInstance()
				.getenv("GIT_OPTIONAL_LOCKS"); //$NON-NLS-1$
		return optional == null || Boolean.TRUE
				.equals(UntrackedCacheConfig.parseBoolean(optional));
	}

	private static int directoryFlags(Repository repository) {
		String mode = repository.getConfig().getString("status", null, //$NON-NLS-1$
				"showUntrackedFiles"); //$NON-NLS-1$
		if (mode == null || "normal".equals(mode)) { //$NON-NLS-1$
			return UntrackedCache.SHOW_OTHER_DIRECTORIES | (1 << 2);
		}
		return "all".equals(mode) ? 0 : -1; //$NON-NLS-1$
	}

	private UntrackedCacheScan(Repository repository) throws IOException {
		this.repository = repository;
		directoryFlags = directoryFlags(repository);
		policyMode = repository.getConfig().get(UntrackedCacheConfig.KEY)
				.getMode();
		root = repository.getWorkTree().toPath().toAbsolutePath().normalize();
		String path = root.toString();
		rootPrefix = path.endsWith(File.separator) ? path
				: path + File.separator;
		info = policyMode == UntrackedCacheConfig.Mode.REMOVE ? null
				: ignore(repository.getCommonDirectory().toPath()
						.resolve(Constants.INFO_EXCLUDE));
		global = policyMode == UntrackedCacheConfig.Mode.REMOVE ? null
				: ignore(globalPath(repository));
	}

	boolean acceptsIndex(DirCache index) {
		// A KEEP/no-cache scan cannot publish anything. Discard its root-only
		// capture before descending, without loading the index a second time.
		return policyMode == UntrackedCacheConfig.Mode.WRITE
				|| index.getUntrackedCache() != null;
	}

	private static Path globalPath(Repository repository) {
		Path path = repository.getConfig().getPath("core", null, "excludesFile", //$NON-NLS-1$ //$NON-NLS-2$
				repository.getFS(), null, null);
		if (path == null) {
			Path xdg = SystemReader.getInstance()
					.getXdgConfigDirectory(repository.getFS());
			path = xdg == null ? null : xdg.resolve("git/ignore"); //$NON-NLS-1$
		}
		return path;
	}

	File[] list(File directory, UntrackedDirectoryListing listing) {
		if (policyMode == UntrackedCacheConfig.Mode.REMOVE) {
			return listing == null ? directory.listFiles()
					: listing.list(directory);
		}
		File[] files = null;
		if (!failed) {
			try {
				Path path = directory.toPath().toAbsolutePath().normalize();
				var before = UntrackedDirectoryListing.stat(path);
				String relative = relative(path);
				files = listing == null ? directory.listFiles()
						: listing.list(directory, path, relative, before);
				Ignore rules = directoryIgnore(path, files,
						listing == null || !listing.isNativeListing());
				// build() compares this pre-list stat with a fresh stat after
				// the entire scan, covering both listing and ignore capture.
				if (files == null || !before.directory()
						|| directories.size() >= 16384 || !rules.unchanged()) {
					failed = true;
				} else {
					directories.put(relative,
							new Observed(path, before.fields(), rules));
				}
			} catch (IOException | RuntimeException unsupported) {
				failed = true;
			}
		}
		return files != null ? files : directory.listFiles();
	}

	void entry(File file, boolean directory, boolean exists) {
		if (policyMode == UntrackedCacheConfig.Mode.REMOVE || failed) {
			return;
		}
		if (!exists) {
			failed = true;
		}
		if (directory) {
			if (directoryEntries.size() >= 16384) {
				failed = true;
			} else {
				directoryEntries.add(
						relative(file.toPath().toAbsolutePath().normalize()));
			}
		}
	}

	private String relative(Path path) {
		if (path.equals(root))
			return ""; //$NON-NLS-1$
		if (path.startsWith(root)) {
			return path.toString().substring(rootPrefix.length())
					.replace(File.separatorChar, '/');
		}
		return root.relativize(path).toString().replace(File.separatorChar,
				'/');
	}

	private static int[] statIfPresent(Path path) throws IOException {
		if (path == null) {
			return null;
		}
		try {
			return UntrackedDirectoryListing.stat(path).fields();
		} catch (NoSuchFileException absent) {
			return null;
		}
	}

	private static Ignore directoryIgnore(Path directory, File[] files,
			boolean complete) throws IOException {
		if (complete && files != null) {
			boolean present = false;
			String suffix = File.separator + Constants.DOT_GIT_IGNORE;
			for (File file : files) {
				String name = file.getPath();
				if (name.regionMatches(true, name.length() - suffix.length(),
						suffix, 0, suffix.length())) {
					present = true;
					break;
				}
			}
			if (!present) {
				// The surrounding directory stat checks bind this complete
				// listing's absence; native subsets cannot prove absence.
				return new Ignore(null, null, ObjectId.zeroId(),
						ObjectId.zeroId(), true);
			}
		}
		Path path = directory.resolve(Constants.DOT_GIT_IGNORE);
		int[] before = statIfPresent(path);
		return before == null
				? new Ignore(path, null, ObjectId.zeroId(), ObjectId.zeroId(),
						true)
				: ignore(path, before);
	}

	private static Ignore ignore(Path path) throws IOException {
		return ignore(path, statIfPresent(path));
	}

	private static Ignore ignore(Path path, int[] before) throws IOException {
		byte[] bytes = UntrackedDirectoryListing.readExclude(path);
		if (!Arrays.equals(before, statIfPresent(path))) {
			throw new IOException("Ignore input changed during capture"); //$NON-NLS-1$
		}
		return new Ignore(path, before,
				UntrackedDirectoryListing.excludeId(bytes, false),
				UntrackedDirectoryListing.excludeId(bytes, true), false);
	}

	private UntrackedCache build(DirCache index, Set<String> untracked)
			throws IOException {
		if (failed || !eligible(repository)
				|| policyMode != repository.getConfig()
						.get(UntrackedCacheConfig.KEY).getMode()
				|| directoryFlags != directoryFlags(repository)
				|| untracked.size() > 200000
				|| !java.util.Objects.equals(global.path,
						globalPath(repository))
				|| !info.unchanged() || !global.unchanged()
				|| !directories.containsKey("")) { //$NON-NLS-1$
			return null;
		}
		UntrackedCacheConfig policy = repository.getConfig()
				.get(UntrackedCacheConfig.KEY);
		UntrackedCache base = policy.applyTo(repository,
				index.getUntrackedCache());
		if (base == null || !policy.matchesIdentity(repository, base)) {
			return null;
		}
		UntrackedCache cache = new UntrackedCache();
		cache.ident = base.ident.clone();
		cache.dirFlags = directoryFlags;
		cache.infoExclude.oid = info.nativeId;
		cache.excludesFile.oid = global.nativeId;
		if (info.stat != null)
			System.arraycopy(info.stat, 0, cache.infoExclude.stat.fields, 0, 9);
		if (global.stat != null)
			System.arraycopy(global.stat, 0, cache.excludesFile.stat.fields, 0,
					9);
		cache.root = new Directory(new byte[0]);
		Map<String, Directory> nodes = new HashMap<>();
		nodes.put("", cache.root); //$NON-NLS-1$
		for (var entry : directories.entrySet()) {
			String path = entry.getKey();
			Observed observed = entry.getValue();
			if (!Arrays.equals(observed.stat,
					UntrackedDirectoryListing.stat(observed.path).fields())
					|| !observed.ignore.unchanged()) {
				return null;
			}
			Directory node = cache.root;
			if (!path.isEmpty()) {
				int slash = path.lastIndexOf('/');
				String parent = slash < 0 ? "" : path.substring(0, slash); //$NON-NLS-1$
				node = cache.lookupDirectory(nodes.get(parent),
						Constants.encode(path.substring(slash + 1)));
				if (node == null)
					return null;
				nodes.put(path, node);
			}
			System.arraycopy(observed.stat, 0, node.stat.fields, 0, 9);
			node.excludeOid = observed.ignore.nativeId;
			var indexed = index
					.getEntry(path.isEmpty() ? Constants.DOT_GIT_IGNORE
							: path + "/" + Constants.DOT_GIT_IGNORE); //$NON-NLS-1$
			if (indexed != null && indexed.getStage() == 0
					&& indexed.getObjectId().equals(observed.ignore.raw)) {
				node.excludeOid = observed.ignore.raw;
			}
			node.valid = node.recurse = true;
		}
		for (String path : untracked) {
			int slash = path.lastIndexOf('/');
			Directory parent = nodes
					.get(slash < 0 ? "" : path.substring(0, slash)); //$NON-NLS-1$
			if (parent == null)
				return null;
			parent.untracked.add(Constants.encode(path.substring(slash + 1)
					+ (directoryEntries.contains(path) ? "/" : ""))); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (directoryFlags != 0) {
			// The API still reports every untracked file. Only the persisted
			// normal-mode view collapses wholly untracked directories.
			var paths = new ArrayList<>(directories.keySet());
			for (int i = paths.size() - 1; i >= 0; i--) {
				String path = paths.get(i);
				Directory node = nodes.get(path);
				if (path.isEmpty()) {
					continue;
				}
				String prefix = path + "/"; //$NON-NLS-1$
				int position = index.findEntry(prefix);
				if (position < 0) {
					position = -position - 1;
				}
				node.checkOnly = position == index.getEntryCount() || !index
						.getEntry(position).getPathString().startsWith(prefix);
				if (node.checkOnly && !node.untracked.isEmpty()) {
					// Native check_only records need one nonignored witness.
					node.untracked.sort(Arrays::compareUnsigned);
					node.untracked.subList(1, node.untracked.size()).clear();
					int slash = path.lastIndexOf('/');
					Directory parent = nodes
							.get(slash < 0 ? "" : path.substring(0, slash)); //$NON-NLS-1$
					parent.untracked.add(
							Constants.encode(path.substring(slash + 1) + "/")); //$NON-NLS-1$
				}
			}
		}
		for (Directory node : nodes.values()) {
			node.untracked.sort(Arrays::compareUnsigned);
		}
		return cache;
	}

	boolean publish(DirCache scanned, Set<String> untracked,
			Set<String> verified, ProgressMonitor monitor, FsMonitor.Probe scan,
			Object operationToken) {
		publication = null;
		try {
			if (monitor != null && monitor.isCancelled())
				return false;
			// A failed scan query owns its deadline. Optional maintenance must
			// not start another provider process or manufacture a new boundary.
			if (scan == null && FsMonitor.isConfigured(repository))
				return false;
			boolean remove = policyMode == UntrackedCacheConfig.Mode.REMOVE;
			if (remove && scanned.getUntrackedCache() == null)
				return false;
			UntrackedCache cache = remove ? null : build(scanned, untracked);
			if (!remove && cache == null)
				return false;
			if (cache != null && !FsMonitor.isConfigured(repository)
					&& scanned.getUntrackedCache() != null && Arrays.equals(
							cache.write(), scanned.getUntrackedCache().write()))
				return false;
			byte[][] written = { scanned.getReadIndexChecksum() };
			DirCache locked = DirCache.lock(repository, event -> {
				// Capture the owned checksum before any listener can write
				// again.
				written[0] = event.getIndexChecksum();
				repository.notifyIndexChanged(operationToken == null ? event
						: new IndexChangedEvent(event.isInternal(),
								event.getFsMonitorPaths(),
								event.getIndexChecksum(), operationToken));
			}, scan);
			try {
				if (!eligible(repository)
						|| policyMode != repository.getConfig()
								.get(UntrackedCacheConfig.KEY).getMode()
						|| directoryFlags != directoryFlags(repository)
						|| monitor != null && monitor.isCancelled()
						|| !Arrays.equals(scanned.getReadIndexChecksum(),
								locked.getReadIndexChecksum())
						|| !Arrays.equals(scanned.getReadIndexChecksum(),
								repository.readDirCache()
										.getReadIndexChecksum()))
					return false;
				locked.setUntrackedCacheAfterScan(cache, verified);
				locked.write();
				Set<String> late = locked.getFsMonitorPublicationPaths();
				if (!locked.commit()) {
					return false;
				}
				publication = FsMonitor.finishPublication(repository, scan,
						written[0], late);
				return true;
			} finally {
				locked.unlock();
			}
		} catch (IOException optionalFailure) {
			return false;
		}
	}
}
