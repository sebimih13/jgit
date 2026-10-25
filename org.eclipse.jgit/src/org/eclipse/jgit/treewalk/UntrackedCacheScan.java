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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.jgit.dircache.DirCache;
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
 * Capture complete all-mode traversal before optional, checksum-guarded
 * publication.
 */
final class UntrackedCacheScan {
	private record Ignore(Path path, int[] stat, ObjectId raw,
			ObjectId nativeId) {
		boolean unchanged() throws IOException {
			Ignore now = ignore(path);
			return Arrays.equals(stat, now.stat) && raw.equals(now.raw);
		}
	}

	private record Observed(Path path, int[] stat, Ignore ignore) {
	}

	private final Repository repository;

	private final Path root;

	private final Ignore info;

	private final Ignore global;

	private final Map<String, Observed> directories = new LinkedHashMap<>();

	private final Set<String> directoryEntries = new HashSet<>();

	private boolean failed;

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
				|| repository.getConfig().get(UntrackedCacheConfig.KEY)
						.getMode() == UntrackedCacheConfig.Mode.REMOVE
				|| !"all" //$NON-NLS-1$
						.equals(repository.getConfig().getString("status", null, //$NON-NLS-1$
								"showUntrackedFiles")) //$NON-NLS-1$
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

	private UntrackedCacheScan(Repository repository) throws IOException {
		this.repository = repository;
		root = repository.getWorkTree().toPath().toAbsolutePath().normalize();
		info = ignore(repository.getCommonDirectory().toPath()
				.resolve(Constants.INFO_EXCLUDE));
		global = ignore(globalPath(repository));
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
		File[] files = null;
		if (!failed) {
			try {
				Path path = directory.toPath().toAbsolutePath().normalize();
				var before = UntrackedDirectoryListing.stat(path);
				Ignore rules = ignore(path.resolve(Constants.DOT_GIT_IGNORE));
				files = listing == null ? directory.listFiles()
						: listing.list(directory);
				if (files == null || !before.directory()
						|| !Arrays.equals(before.fields(),
								UntrackedDirectoryListing.stat(path).fields())
						|| directories.size() >= 16384 || !rules.unchanged()) {
					failed = true;
				} else {
					directories.put(relative(path),
							new Observed(path, before.fields(), rules));
				}
			} catch (IOException | RuntimeException unsupported) {
				failed = true;
			}
		}
		return files != null ? files : directory.listFiles();
	}

	void entry(File file, boolean directory, boolean exists) {
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

	private static Ignore ignore(Path path) throws IOException {
		int[] before = statIfPresent(path);
		byte[] bytes = UntrackedDirectoryListing.readExclude(path);
		if (!Arrays.equals(before, statIfPresent(path))) {
			throw new IOException("Ignore input changed during capture"); //$NON-NLS-1$
		}
		return new Ignore(path, before,
				UntrackedDirectoryListing.excludeId(bytes, false),
				UntrackedDirectoryListing.excludeId(bytes, true));
	}

	private UntrackedCache build(DirCache index, Set<String> untracked)
			throws IOException {
		if (failed || !eligible(repository) || untracked.size() > 200000
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
		cache.dirFlags = 0;
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
		for (Directory node : nodes.values()) {
			node.untracked.sort(Arrays::compareUnsigned);
		}
		return cache;
	}

	boolean publish(DirCache scanned, Set<String> untracked,
			Set<String> verified, ProgressMonitor monitor) {
		try {
			if (monitor != null && monitor.isCancelled())
				return false;
			UntrackedCache cache = build(scanned, untracked);
			if (cache == null)
				return false;
			byte[] payload = cache.write();
			if (!FsMonitor.isConfigured(repository)
					&& scanned.getUntrackedCache() != null && Arrays.equals(
							payload, scanned.getUntrackedCache().write()))
				return false;
			DirCache locked = repository.lockDirCache();
			try {
				if (!eligible(repository)
						|| monitor != null && monitor.isCancelled()
						|| !Arrays.equals(scanned.getReadIndexChecksum(),
								locked.getReadIndexChecksum())
						|| !Arrays.equals(scanned.getReadIndexChecksum(),
								repository.readDirCache()
										.getReadIndexChecksum()))
					return false;
				locked.setUntrackedCacheAfterScan(cache, verified);
				locked.write();
				return locked.commit();
			} finally {
				locked.unlock();
			}
		} catch (IOException optionalFailure) {
			return false;
		}
	}
}
