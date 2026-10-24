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
	@Test
	public void nativeAllRecordsServeColdWalkWithoutIgnoredResults()
			throws Exception {
		prepare("all");
		try (var fresh = new org.eclipse.jgit.storage.file.FileRepositoryBuilder()
				.setGitDir(db.getDirectory()).build()) {
			DirCache index = fresh.readDirCache();
			byte[] original = Files.readAllBytes(fresh.getIndexFile().toPath());
			var listing = UntrackedDirectoryListing.create(fresh, index, false);
			assertNotNull(listing);
			assertEquals(Set.of("tracked", "loose", ".gitignore"),
					names(listing.list(sub())));
			assertEquals("No prior Java enumeration is required", 0,
					index.getUntrackedCache().dirOpened);
			// An incomplete native-only list must never seed the complete-name
			// supplement used by the existing ignored-reporting API.
			listing = UntrackedDirectoryListing.create(fresh, index, true);
			assertTrue(names(listing.list(sub())).contains("hidden.tmp"));
			assertEquals(1, index.getUntrackedCache().dirOpened);
			try (Git git = Git.wrap(fresh)) {
				Status compact = git.status().setReportIgnored(false).call();
				Status complete = git.status().call();
				assertEquals(complete.getUntracked(), compact.getUntracked());
				assertTrue(compact.getIgnoredNotInIndex().isEmpty());
				assertEquals(Set.of("sub/hidden.tmp"), complete.getIgnoredNotInIndex());
			}
			assertArrayEquals(original, Files.readAllBytes(fresh.getIndexFile().toPath()));
		}
	}

	@Test
	public void ignoreContentChangesRejectColdNativeNames() throws Exception {
		prepare("all");
		writeTrashFile("sub/.gitignore", "\n");
		try (var fresh = new org.eclipse.jgit.storage.file.FileRepositoryBuilder()
				.setGitDir(db.getDirectory()).build()) {
			DirCache index = fresh.readDirCache();
			var listing = UntrackedDirectoryListing.create(fresh, index, false);
			assertTrue(names(listing.list(sub())).contains("hidden.tmp"));
			assertEquals("Ignore edits must reject native omissions", 1,
					index.getUntrackedCache().dirOpened);
			try (Git git = Git.wrap(fresh)) {
				assertTrue(git.status().setReportIgnored(false).call()
						.getUntracked().contains("sub/hidden.tmp"));
			}
		}
		assertNativeStatus();
	}

	@Test
	public void nativeCandidatesDoNotHideMissingTrackedFiles() throws Exception {
		prepare("all");
		Files.delete(sub().toPath().resolve("tracked"));
		Files.setLastModifiedTime(sub().toPath(),
				FileTime.fromMillis(System.currentTimeMillis() - 60000));
		org.eclipse.jgit.junit.NativeFsMonitorWorkloadSupport.warmNativeUntrackedCache(db);
		try (var fresh = new org.eclipse.jgit.storage.file.FileRepositoryBuilder()
				.setGitDir(db.getDirectory()).build(); Git git = Git.wrap(fresh)) {
			Status compact = git.status().setReportIgnored(false).call();
			Status complete = git.status().call();
			assertEquals(Set.of("sub/tracked"), compact.getMissing());
			assertEquals(complete.getMissing(), compact.getMissing());
			assertEquals(complete.getUntracked(), compact.getUntracked());
			assertEquals(complete.getModified(), compact.getModified());
		}
	}

	@Test
	public void changedGlobalAndAncestorIgnoresRequireEnumeration() throws Exception {
		for (String context : new String[] { "global", "info", "ancestor" }) {
			prepare("all");
			Path changed = switch (context) {
			case "global" -> Path.of(db.getConfig().getString("core", null, "excludesFile"));
			case "info" -> db.getDirectory().toPath().resolve("info/exclude");
			default -> db.getWorkTree().toPath().resolve(".gitignore");
			};
			Files.createDirectories(changed.getParent());
			Files.writeString(changed, "new-pattern\n");
			try (var fresh = new org.eclipse.jgit.storage.file.FileRepositoryBuilder()
					.setGitDir(db.getDirectory()).build()) {
				DirCache index = fresh.readDirCache();
				var listing = UntrackedDirectoryListing.create(fresh, index, false);
				assertTrue(names(listing.list(sub())).contains("hidden.tmp"));
				assertEquals(context, 1, index.getUntrackedCache().dirOpened);
			}
			Files.delete(changed);
		}
	}

	@Test
	public void normalModeRecordsCannotSupplyAllModeNames() throws Exception {
		prepare("normal");
		try (var fresh = new org.eclipse.jgit.storage.file.FileRepositoryBuilder()
				.setGitDir(db.getDirectory()).build()) {
			DirCache index = fresh.readDirCache();
			var listing = UntrackedDirectoryListing.create(fresh, index, false);
			assertTrue(names(listing.list(sub())).contains("hidden.tmp"));
			assertEquals(1, index.getUntrackedCache().dirOpened);
		}
	}

	@Test
	public void refreshedNativeIgnoresCannotReuseAnOlderSubset() throws Exception {
		prepare("all");
		try (var fresh = new org.eclipse.jgit.storage.file.FileRepositoryBuilder()
				.setGitDir(db.getDirectory()).build()) {
			DirCache before = fresh.readDirCache();
			var listing = UntrackedDirectoryListing.create(fresh, before, false);
			assertTrue(!names(listing.list(sub())).contains("hidden.tmp"));
			assertEquals(0, before.getUntrackedCache().dirOpened);
			Files.writeString(sub().toPath().resolve(".gitignore"), "\n");
			org.eclipse.jgit.junit.NativeFsMonitorWorkloadSupport.warmNativeUntrackedCache(db);
			DirCache after = fresh.readDirCache();
			assertTrue(!Arrays.equals(before.getReadIndexChecksum(), after.getReadIndexChecksum()));
			assertArrayEquals(before.getUntrackedCache().root.directories.stream()
					.filter(d -> Arrays.equals(d.name, Constants.encode("sub")))
					.findFirst().orElseThrow().stat.fields,
					after.getUntrackedCache().root.directories.stream()
							.filter(d -> Arrays.equals(d.name, Constants.encode("sub")))
							.findFirst().orElseThrow().stat.fields);
			listing = UntrackedDirectoryListing.create(fresh, after, false);
			assertTrue(names(listing.list(sub())).contains("hidden.tmp"));
			assertEquals("The new native payload can be consumed without listing", 0,
					after.getUntrackedCache().dirOpened);
		}
	}

	@Test
	public void indexedPrefixesAndTrackedIgnoresRemainComplete() throws Exception {
		prepare("all");
		for (String path : new String[] { "sub/dir/file", "sub/dir.other", "sub/dir0" }) {
			writeTrashFile(path, "base\n");
		}
		try (Git git = Git.wrap(db)) {
			git.add().addFilepattern("sub/.gitignore").addFilepattern("sub/dir")
					.addFilepattern("sub/dir.other").addFilepattern("sub/dir0").call();
		}
		for (Path dir : new Path[] { sub().toPath(), sub().toPath().resolve("dir") }) {
			Files.setLastModifiedTime(dir, FileTime.fromMillis(System.currentTimeMillis() - 60000));
		}
		org.eclipse.jgit.junit.NativeFsMonitorWorkloadSupport.warmNativeUntrackedCache(db);
		try (var fresh = new org.eclipse.jgit.storage.file.FileRepositoryBuilder()
				.setGitDir(db.getDirectory()).build()) {
			DirCache index = fresh.readDirCache();
			var listing = UntrackedDirectoryListing.create(fresh, index, false);
			assertEquals(Set.of("tracked", "loose", ".gitignore", "dir", "dir.other", "dir0"),
					names(listing.list(sub())));
			assertEquals(0, index.getUntrackedCache().dirOpened);
		}
	}
	private DirCache prepare(String mode) throws Exception {
		assumeTrue(db.getFS().getClass() == FS_POSIX.class);
		assumeTrue(db.getWorkTree().toPath().getFileSystem()
				.supportedFileAttributeViews().contains("unix"));
		writeTrashFile("sub/.gitignore", "*.tmp\n");
		writeTrashFile("sub/hidden.tmp", "ignored\n");
		Path excludes = db.getDirectory().toPath().resolve("native-test-excludes");
		Files.writeString(excludes, "");
		db.getConfig().setString("core", null, "excludesFile", excludes.toString());
		db.getConfig().save();
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
