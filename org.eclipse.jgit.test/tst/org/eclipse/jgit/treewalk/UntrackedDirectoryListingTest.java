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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.internal.storage.file.UntrackedCache;
import org.eclipse.jgit.junit.NativeUntrackedCheckoutSupport;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.Test;

public class UntrackedDirectoryListingTest extends RepositoryTestCase {
	private DirCache prepare(String mode) throws Exception {
		assumeTrue(db.getFS().getClass() == FS_POSIX.class);
		assumeTrue(db.getWorkTree().toPath().getFileSystem()
				.supportedFileAttributeViews().contains("unix"));
		writeTrashFile("sub/.gitignore", "*.tmp\n");
		writeTrashFile("sub/hidden.tmp", "ignored\n");
		NativeUntrackedCheckoutSupport.prepare(db, mode, "sub/tracked");
		return db.readDirCache();
	}

	private File sub() {
		return new File(db.getWorkTree(), "sub");
	}

	private static Set<String> names(File[] files) {
		Set<String> result = new HashSet<>();
		for (File file : files) {
			result.add(file.getName());
		}
		return result;
	}

	@Test
	public void completeListingsReuseAcrossWalksAndKeepIgnoredNames()
			throws Exception {
		for (String mode : new String[] { "normal", "all" }) {
			DirCache index = prepare(mode);
			UntrackedCache nativeCache = index.getUntrackedCache();
			byte[] original = nativeCache.write();
			UntrackedDirectoryListing first = UntrackedDirectoryListing
					.create(db, index);
			assertNotNull(first);
			Set<String> expected = Set.of("tracked", "loose", ".gitignore",
					"hidden.tmp");
			assertEquals(expected, names(first.list(sub())));
			assertEquals(1, nativeCache.dirOpened);
			assertEquals(expected, names(first.list(sub())));
			assertEquals("A repeated list must avoid enumeration", 1,
					nativeCache.dirOpened);
			assertArrayEquals("Runtime validation cannot mutate stored UNTR",
					original, nativeCache.write());
			DirCache reopened = db.readDirCache();
			UntrackedDirectoryListing next = UntrackedDirectoryListing
					.create(db, reopened);
			assertEquals(expected, names(next.list(sub())));
			assertEquals("New iterator shares only validated complete names", 0,
					reopened.getUntrackedCache().dirOpened);
			assertNativeStatus();
		}
	}

	@Test
	public void entryMembershipAndTypeChangesInvalidateListings()
			throws Exception {
		DirCache index = prepare("all");
		UntrackedDirectoryListing listing = UntrackedDirectoryListing.create(db,
				index);
		listing.list(sub());
		FileTime directoryTime = Files.getLastModifiedTime(sub().toPath());
		writeTrashFile("sub/new", "new\n");
		Files.setLastModifiedTime(sub().toPath(), directoryTime);
		assertTrue(names(listing.list(sub())).contains("new"));
		assertEquals(2, index.getUntrackedCache().dirOpened);
		Files.delete(sub().toPath().resolve("loose"));
		Files.createDirectory(sub().toPath().resolve("loose"));
		writeTrashFile("sub/loose/child", "nested\n");
		assertNativeStatus();
		Files.delete(sub().toPath().resolve("new"));
		assertTrue(!names(listing.list(sub())).contains("new"));
		assertNativeStatus();
	}

	@Test
	public void reusedNamesStillReadContentMetadataAndIgnoreRules()
			throws Exception {
		DirCache index = prepare("all");
		UntrackedDirectoryListing listing = UntrackedDirectoryListing.create(db,
				index);
		listing.list(sub());
		writeTrashFile("sub/tracked", "longer content\n");
		writeTrashFile("sub/.gitignore", "\n");
		listing.list(sub());
		assertEquals("Content edits leave directory membership reusable", 1,
				index.getUntrackedCache().dirOpened);
		try (Git git = Git.wrap(db)) {
			Status status = git.status().call();
			assertEquals(Set.of("sub/tracked"), status.getModified());
			assertTrue(status.getUntracked().contains("sub/hidden.tmp"));
			assertTrue(status.getIgnoredNotInIndex().isEmpty());
		}
		assertNativeStatus();
	}

	@Test
	public void invalidBitsForeignIdentityAndRacyTimesRequireEnumeration()
			throws Exception {
		DirCache index = prepare("all");
		UntrackedDirectoryListing listing = UntrackedDirectoryListing.create(db,
				index);
		listing.list(sub());
		var node = index.getUntrackedCache().root.directories.stream()
				.filter(d -> Arrays.equals(d.name, Constants.encode("sub")))
				.findFirst().orElseThrow();
		node.valid = false;
		listing.list(sub());
		assertEquals(2, index.getUntrackedCache().dirOpened);
		node.valid = true;
		index.getUntrackedCache().ident = Constants.encode("foreign\0");
		assertNull(UntrackedDirectoryListing.create(db, index));
		index = db.readDirCache();
		Files.setLastModifiedTime(db.getIndexFile().toPath(),
				FileTime.from(Instant.EPOCH));
		listing = UntrackedDirectoryListing.create(db, index);
		assertNotNull(listing);
		listing.list(sub());
		listing.list(sub());
		assertEquals("A racy native record cannot validate reuse", 2,
				index.getUntrackedCache().dirOpened);
	}

	@Test
	public void disabledPolicyAndConcurrentWalksKeepCompleteListings()
			throws Exception {
		DirCache index = prepare("all");
		UntrackedDirectoryListing listing = UntrackedDirectoryListing.create(db,
				index);
		Set<String> expected = names(listing.list(sub()));
		db.getConfig().setBoolean("core", null, "untrackedCache", false);
		assertNull(UntrackedDirectoryListing.create(db, index));
		db.getConfig().setString("core", null, "untrackedCache", "keep");
		var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
		try {
			var first = pool.submit(() -> {
				var walk = UntrackedDirectoryListing.create(db, index);
				for (int i = 0; i < 100; i++)
					assertEquals(expected, names(walk.list(sub())));
			});
			var second = pool.submit(() -> {
				var walk = UntrackedDirectoryListing.create(db, index);
				for (int i = 0; i < 100; i++)
					assertEquals(expected, names(walk.list(sub())));
			});
			first.get(10, TimeUnit.SECONDS);
			second.get(10, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}
		assertEquals("Concurrent validated reads need no new enumeration", 1,
				index.getUntrackedCache().dirOpened);
	}

	@Test
	public void staleNativeRecordsUseFreshListingsAndRecoverAfterNativeRefresh()
			throws Exception {
		DirCache index = prepare("all");
		UntrackedDirectoryListing listing = UntrackedDirectoryListing.create(db,
				index);
		listing.list(sub());
		writeTrashFile("sub/first-new", "new\n");
		assertTrue(names(listing.list(sub())).contains("first-new"));
		writeTrashFile("sub/second-new", "new\n");
		Files.delete(sub().toPath().resolve("first-new"));
		Set<String> current = names(listing.list(sub()));
		assertTrue(current.contains("second-new"));
		assertTrue(!current.contains("first-new"));
		assertNativeStatus();
		DirCache refreshed = prepare("all");
		listing = UntrackedDirectoryListing.create(db, refreshed);
		assertNotNull(listing);
		assertTrue(names(listing.list(sub())).contains("second-new"));
		assertEquals(1, refreshed.getUntrackedCache().dirOpened);
		listing.list(sub());
		assertEquals("A refreshed native record can seed reuse again", 1,
				refreshed.getUntrackedCache().dirOpened);
	}

	@Test
	public void completeMissBypassesUnchangedIndexUntilNativeRefresh()
			throws Exception {
		DirCache index = prepare("all");
		UntrackedDirectoryListing listing = UntrackedDirectoryListing.create(db,
				index);
		writeTrashFile("sub/changed", "new\n");
		listing.list(sub());
		listing.finish();
		assertNull(UntrackedDirectoryListing.create(db, db.readDirCache()));
		writeTrashFile("sub/after-miss", "new\n");
		assertNativeStatus();
		index = prepare("all");
		assertNotNull(UntrackedDirectoryListing.create(db, index));
	}

	private void assertNativeStatus() throws Exception {
		Path output = db.getDirectory().toPath().resolve("listing-status.out");
		Path error = db.getDirectory().toPath().resolve("listing-status.err");
		ProcessBuilder builder = new ProcessBuilder(
				System.getProperty("jgit.test.gitExecutable", "git"),
				"--no-optional-locks", "-c", "core.fsmonitor=false", "status",
				"--porcelain=v1", "-z", "--untracked-files=all",
				"--ignored=matching", "--no-renames");
		builder.directory(db.getWorkTree());
		builder.environment().keySet()
				.removeIf(name -> name.startsWith("GIT_"));
		builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
		builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
		builder.redirectOutput(output.toFile()).redirectError(error.toFile());
		Process child = builder.start();
		try {
			assertTrue(child.waitFor(10, TimeUnit.SECONDS));
			assertEquals(Files.readString(error), 0, child.exitValue());
		} finally {
			child.destroyForcibly();
		}
		Set<String> nativeUntracked = new HashSet<>();
		Set<String> nativeIgnored = new HashSet<>();
		Set<String> nativeModified = new HashSet<>();
		for (String record : new String(Files.readAllBytes(output),
				StandardCharsets.UTF_8).split("\0")) {
			if (record.startsWith("?? "))
				nativeUntracked.add(record.substring(3));
			else if (record.startsWith("!! "))
				nativeIgnored.add(record.substring(3));
			else if (record.startsWith(" M "))
				nativeModified.add(record.substring(3));
			else if (!record.isEmpty())
				throw new AssertionError(record);
		}
		try (Git git = Git.wrap(db)) {
			Status actual = git.status().call();
			assertEquals(nativeUntracked, actual.getUntracked());
			assertEquals(nativeIgnored, actual.getIgnoredNotInIndex());
			assertEquals(nativeModified, actual.getModified());
			assertTrue(actual.getAdded().isEmpty());
			assertTrue(actual.getChanged().isEmpty());
			assertTrue(actual.getRemoved().isEmpty());
			assertTrue(actual.getMissing().isEmpty());
			assertTrue(actual.getConflicting().isEmpty());
		}
	}
}
