/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.treewalk;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.internal.storage.file.FileSnapshot;
import org.eclipse.jgit.internal.storage.file.UntrackedCache;
import org.eclipse.jgit.internal.storage.file.UntrackedCache.Directory;
import org.eclipse.jgit.internal.storage.file.UntrackedCacheConfig;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.util.FS_POSIX;
import org.eclipse.jgit.util.SystemReader;

/** Native UNTR names, with complete Java listings for ignored-path reporting. */
final class UntrackedDirectoryListing {
	private static final Map<Repository, Listings> REPOSITORIES = new WeakHashMap<>();

	private static final int MAX_DIRECTORIES = 16384;

	private static final int MAX_NAMES = 200_000;

	private static final int MAX_CHARACTERS = 8 * 1024 * 1024;

	private final UntrackedCache cache;

	private final Path root;

	private final Instant indexTime;

	private final Listings listings;

	private final File indexFile;

	private final FileSnapshot indexSnapshot;

	private boolean reusable;

	private Repository repository;

	private DirCache index;

	private boolean nativeNames;

	private String nativeKeyPrefix;

	private boolean lastNative;

	private Boolean globalExcludesValid;

	private final Map<String, Boolean> directoryExcludesValid = new java.util.HashMap<>();

	private record Listing(int[] stat, String[] names, int characters) {
		int size() {
			return names == null ? 0 : names.length;
		}
	}

	private static final class Listings {
		final Map<String, Listing> directories = new LinkedHashMap<>(16, 0.75f,
				true);

		int names;

		int characters;

		FileSnapshot rejectedIndex;

		synchronized Listing get(String path) {
			return directories.get(path);
		}

		synchronized void put(String path, Listing listing) {
			if (listing.size() > MAX_NAMES
					|| listing.characters > MAX_CHARACTERS) {
				return;
			}
			Listing previous = directories.put(path, listing);
			if (previous != null) {
				names -= previous.size();
				characters -= previous.characters + path.length();
			}
			names += listing.size();
			characters += listing.characters + path.length();
			var iterator = directories.entrySet().iterator();
			while (directories.size() > MAX_DIRECTORIES || names > MAX_NAMES
					|| characters > MAX_CHARACTERS) {
				var entry = iterator.next();
				Listing removed = entry.getValue();
				names -= removed.size();
				characters -= removed.characters + entry.getKey().length();
				iterator.remove();
			}
		}
	}

	static UntrackedDirectoryListing create(Repository repo, DirCache index) {
		return create(repo, index, true);
	}

	static UntrackedDirectoryListing create(Repository repo, DirCache index,
			boolean reportIgnored) {
		if (repo == null || repo.isBare()
				|| repo.getFS().getClass() != FS_POSIX.class
				|| SystemReader.getInstance()
						.getenv("GIT_DISABLE_UNTRACKED_CACHE") != null) { //$NON-NLS-1$
			return null;
		}
		try {
			UntrackedCache cache = index.getUntrackedCache();
			UntrackedCacheConfig policy = repo.getConfig()
					.get(UntrackedCacheConfig.KEY);
			if (cache == null || cache.root == null
					|| policy.getMode() == UntrackedCacheConfig.Mode.REMOVE
					|| !policy.matchesIdentity(repo, cache)) {
				return null;
			}
			Listings listings;
			synchronized (REPOSITORIES) {
				listings = REPOSITORIES.computeIfAbsent(repo,
						unused -> new Listings());
			}
			synchronized (listings) {
				if (listings.rejectedIndex != null && !listings.rejectedIndex
						.isModified(repo.getIndexFile())) {
					return null;
				}
			}
			UntrackedDirectoryListing result = new UntrackedDirectoryListing(cache,
					repo.getWorkTree().toPath().toAbsolutePath().normalize(),
					Files.getLastModifiedTime(repo.getIndexFile().toPath())
							.toInstant(),
					listings, repo.getIndexFile());
			result.repository = repo;
			result.index = index;
			result.nativeNames = !reportIgnored && cache.dirFlags == 0
					&& Arrays.equals(cache.excludePerDir,
							Constants.encode(Constants.DOT_GIT_IGNORE));
			if (result.nativeNames) {
				// NUL cannot occur in a filesystem path. Keep native subsets
				// separate from complete listings and bind them to this index.
				result.nativeKeyPrefix = "\0" + java.util.HexFormat.of() //$NON-NLS-1$
						.formatHex(index.getReadIndexChecksum());
			}
			return result;
		} catch (IOException | RuntimeException unsupported) {
			return null;
		}
	}

	private UntrackedDirectoryListing(UntrackedCache cache, Path root,
			Instant indexTime, Listings listings, File indexFile) {
		this.cache = cache;
		this.root = root;
		this.indexTime = indexTime;
		this.listings = listings;
		this.indexFile = indexFile;
		this.indexSnapshot = FileSnapshot.save(indexFile);
	}

	File[] list(File directory) {
		lastNative = false;
		Path path = directory.toPath().toAbsolutePath().normalize();
		String relative = root.relativize(path).toString()
				.replace(File.separatorChar, '/');
		Directory node = node(relative);
		Listing previous = listings.get(relative);
		// A rejected native record cannot validate names. Until it changes,
		// a normal listing is authoritative and avoids repeated stat probes.
		if (node != null && previous != null && previous.names == null
				&& Arrays.equals(previous.stat, node.stat.fields)) {
			cache.dirOpened++;
			return directory.listFiles();
		}
		int[] stat = validatedStat(path, node);
		if (stat != null) {
			reusable = true;
			if (previous != null && previous.names != null
					&& Arrays.equals(previous.stat, stat)) {
				File[] result = new File[previous.names.length];
				for (int i = 0; i < result.length; i++) {
					result[i] = new File(directory, previous.names[i]);
				}
				return result;
			}
			if (nativeNames) {
				File[] result = nativeListing(directory, relative, node, stat);
				if (result != null) {
					lastNative = true;
					return result;
				}
			}
		}
		cache.dirOpened++;
		File[] files = directory.listFiles();
		if (stat != null && files != null
				&& Arrays.equals(stat, validatedStat(path, node))) {
			String[] names = new String[files.length];
			int characters = 0;
			for (int i = 0; i < files.length; i++) {
				names[i] = files[i].getName();
				characters += names[i].length();
			}
			listings.put(relative, new Listing(stat, names, characters));
		} else if (node != null && node.valid) {
			listings.put(relative,
					new Listing(node.stat.fields.clone(), null, 0));
		}
		return files;
	}

	boolean isNativeListing() {
		return lastNative;
	}

	private File[] nativeListing(File directory, String relative, Directory node,
			int[] stat) {
		if (node.checkOnly) {
			return null;
		}
		try {
			if (!validExcludes(relative)) {
				return null;
			}
			String key = nativeKeyPrefix + relative;
			Listing prior = listings.get(key);
			if (prior != null && prior.names != null && Arrays.equals(prior.stat, stat)) {
				return files(directory, prior.names);
			}
			Set<String> names = new HashSet<>();
			for (byte[] name : node.untracked) {
				names.add(component(name, true));
			}
			for (Directory child : node.directories) {
				if (child.recurse) {
					names.add(component(child.name, false));
				}
			}
			String prefix = relative.isEmpty() ? "" : relative + "/"; //$NON-NLS-1$ //$NON-NLS-2$
			int pos = index.findEntry(prefix);
			if (pos < 0) {
				pos = -pos - 1;
			}
			while (pos < index.getEntryCount()) {
				String path = index.getEntry(pos).getPathString();
				if (!path.startsWith(prefix)) {
					break;
				}
				String rest = path.substring(prefix.length());
				int slash = rest.indexOf('/');
				String name = slash < 0 ? rest : rest.substring(0, slash);
				names.add(name);
				if (slash < 0) {
					pos++;
				} else {
					// '0' is the byte successor of '/': skip this child's
					// entire index prefix without decoding every descendant.
					pos = index.findEntry(prefix + name + "0"); //$NON-NLS-1$
					if (pos < 0) {
						pos = -pos - 1;
					}
				}
			}
			// Even an ignored .gitignore is needed to evaluate the other entries.
			if (!ObjectId.zeroId().equals(node.excludeOid)) {
				names.add(Constants.DOT_GIT_IGNORE);
			}
			String[] array = names.toArray(String[]::new);
			// Retain ordered input for WorkingTreeIterator's adaptive sort on
			// subsequent walks, rather than repeating a hash-table iteration order.
			Arrays.sort(array);
			int characters = 0;
			for (String name : array) {
				characters += name.length();
			}
			listings.put(key, new Listing(stat, array, characters));
			return files(directory, array);
		} catch (IOException | RuntimeException unsafe) {
			return null;
		}
	}

	private static File[] files(File directory, String[] names) {
		File[] files = new File[names.length];
		for (int i = 0; i < files.length; i++) {
			files[i] = new File(directory, names[i]);
		}
		return files;
	}

	private boolean validExcludes(String relative) throws IOException {
		if (globalExcludesValid == null) {
			Path excludes = repository.getConfig().getPath("core", null, //$NON-NLS-1$
					"excludesFile", repository.getFS(), null, null); //$NON-NLS-1$
			if (excludes == null) {
				Path xdg = SystemReader.getInstance()
						.getXdgConfigDirectory(repository.getFS());
				excludes = xdg == null ? null : xdg.resolve("git/ignore"); //$NON-NLS-1$
			}
			globalExcludesValid = Boolean.valueOf(matchesExclude(excludes,
					cache.excludesFile.oid) && matchesExclude(repository.getCommonDirectory()
						.toPath().resolve(Constants.INFO_EXCLUDE), cache.infoExclude.oid));
		}
		if (!globalExcludesValid.booleanValue()) {
			return false;
		}
		// Like native prep_exclude and WorkingTreeIterator's ignore stack,
		// resolve each ancestor once per walk; subsequent walks validate anew.
		Directory current = cache.root;
		Path directory = root;
		String path = ""; //$NON-NLS-1$
		if (!validDirectoryExclude(path, directory, current)) {
			return false;
		}
		for (String part : relative.isEmpty() ? new String[0] : relative.split("/")) { //$NON-NLS-1$
			path = path.isEmpty() ? part : path + "/" + part; //$NON-NLS-1$
			current = node(path);
			directory = directory.resolve(part);
			if (current == null || !validDirectoryExclude(path, directory, current)) {
				return false;
			}
		}
		return true;
	}

	private boolean validDirectoryExclude(String path, Path directory,
			Directory node) throws IOException {
		Boolean valid = directoryExcludesValid.get(path);
		if (valid == null) {
			valid = Boolean.valueOf(matchesExclude(
					directory.resolve(Constants.DOT_GIT_IGNORE), node.excludeOid));
			directoryExcludesValid.put(path, valid);
		}
		return valid.booleanValue();
	}

	private static boolean matchesExclude(Path file, ObjectId expected) throws IOException {
		if (file == null || !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
			return ObjectId.zeroId().equals(expected);
		}
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
				|| Files.size(file) > 1024 * 1024) {
			throw new IOException("Unsupported exclusion file"); //$NON-NLS-1$
		}
		try (ObjectInserter.Formatter formatter = new ObjectInserter.Formatter()) {
			byte[] bytes;
			try (var input = Files.newInputStream(file)) {
				bytes = input.readNBytes(1024 * 1024 + 1);
			}
			if (bytes.length > 1024 * 1024) {
				throw new IOException("Exclusion file exceeds cache limit"); //$NON-NLS-1$
			}
			if (formatter.idFor(Constants.OBJ_BLOB, bytes).equals(expected)) {
				return true;
			}
			// Native add_patterns appends LF before hashing an untracked
			// exclusion file. It can instead reuse a tracked file's index OID.
			// The extra empty final line does not change ignore semantics.
			byte[] terminated = Arrays.copyOf(bytes, bytes.length + 1);
			terminated[bytes.length] = '\n';
			return bytes.length != 0 && formatter.idFor(Constants.OBJ_BLOB,
					terminated).equals(expected);
		}
	}

	private static String component(byte[] raw, boolean slash) throws IOException {
		String name = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
				.decode(java.nio.ByteBuffer.wrap(raw)).toString();
		if (slash && name.endsWith("/")) { //$NON-NLS-1$
			name = name.substring(0, name.length() - 1);
		}
		if (name.isEmpty() || name.equals(".") || name.equals("..") //$NON-NLS-1$ //$NON-NLS-2$
				|| name.indexOf('/') >= 0 || name.indexOf('\0') >= 0) {
			throw new IOException("Unsafe cached name"); //$NON-NLS-1$
		}
		return name;
	}

	void finish() {
		// A complete scan found no reusable directory record. The unchanged
		// native index cannot supply a useful listing on the next scan either.
		// This only bypasses an optimization: normal traversal stays complete.
		if (!reusable && !indexSnapshot.isModified(indexFile)) {
			synchronized (listings) {
				listings.rejectedIndex = indexSnapshot;
			}
		}
	}

	private Directory node(String relative) {
		Directory node = cache.root;
		if (!relative.isEmpty()) {
			for (String component : relative.split("/")) { //$NON-NLS-1$
				byte[] name = Constants.encode(component);
				Directory found = null;
				for (Directory child : node.directories) {
					if (Arrays.equals(child.name, name)) {
						found = child;
						break;
					}
				}
				if (found == null) {
					return null;
				}
				node = found;
			}
		}
		return node;
	}

	private int[] validatedStat(Path path, Directory node) {
		if (node == null || !node.valid || !path.startsWith(root)) {
			return null;
		}
		try {
			Map<String, Object> values = Files.readAttributes(path,
					"unix:ctime,lastModifiedTime,dev,ino,uid,gid,size,isDirectory", //$NON-NLS-1$
					LinkOption.NOFOLLOW_LINKS);
			if (!Boolean.TRUE.equals(values.get("isDirectory"))) { //$NON-NLS-1$
				return null;
			}
			Instant ctime = ((FileTime) values.get("ctime")).toInstant(); //$NON-NLS-1$
			Instant mtime = ((FileTime) values.get("lastModifiedTime")) //$NON-NLS-1$
					.toInstant();
			// Conservatively reject the whole timestamp second, including
			// native builds that do not use nanosecond racy checks.
			if (mtime.getEpochSecond() >= indexTime.getEpochSecond()) {
				return null;
			}
			long size = ((Number) values.get("size")).longValue(); //$NON-NLS-1$
			int encodedSize = (int) size;
			if (encodedSize == 0 && size != 0) {
				encodedSize = 0x80000000;
			}
			int[] stat = { (int) ctime.getEpochSecond(), ctime.getNano(),
					(int) mtime.getEpochSecond(), mtime.getNano(),
					((Number) values.get("dev")).intValue(), //$NON-NLS-1$
					((Number) values.get("ino")).intValue(), //$NON-NLS-1$
					((Number) values.get("uid")).intValue(), //$NON-NLS-1$
					((Number) values.get("gid")).intValue(), encodedSize }; //$NON-NLS-1$
			return Arrays.equals(stat, node.stat.fields) ? stat : null;
		} catch (IOException | RuntimeException unsupported) {
			return null;
		}
	}
}
