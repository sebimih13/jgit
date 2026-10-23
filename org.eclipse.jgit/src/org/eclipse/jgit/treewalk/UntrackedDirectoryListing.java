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
import java.util.WeakHashMap;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.internal.storage.file.FileSnapshot;
import org.eclipse.jgit.internal.storage.file.UntrackedCache;
import org.eclipse.jgit.internal.storage.file.UntrackedCache.Directory;
import org.eclipse.jgit.internal.storage.file.UntrackedCacheConfig;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.FS_POSIX;
import org.eclipse.jgit.util.SystemReader;

/** Complete Java listings supplement native UNTR's untracked-only names. */
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
			return new UntrackedDirectoryListing(cache,
					repo.getWorkTree().toPath().toAbsolutePath().normalize(),
					Files.getLastModifiedTime(repo.getIndexFile().toPath())
							.toInstant(),
					listings, repo.getIndexFile());
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
