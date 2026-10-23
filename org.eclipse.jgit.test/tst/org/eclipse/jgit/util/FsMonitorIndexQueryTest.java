/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.internal.storage.file.FsMonitorCache;
import org.eclipse.jgit.junit.NativeFsMonitorIndexSupport;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.junit.Test;

public class FsMonitorIndexQueryTest extends RepositoryTestCase {
	private Path hook;

	private void prepare() throws Exception {
		NativeFsMonitorIndexSupport.prepare(db);
		writeTrashFile("sub/leaf", "base\n");
		NativeFsMonitorIndexSupport.report(db, "sub/leaf");
		var staged = NativeFsMonitorIndexSupport.mutate(db, "add", "sub/leaf");
		Files.copy(staged.index(), db.getIndexFile().toPath(),
				StandardCopyOption.REPLACE_EXISTING);
		NativeFsMonitorIndexSupport.report(db);
		try (Git git = Git.wrap(db)) {
			git.commit().setMessage("nested")
					.setAuthor("Test", "test@example.invalid")
					.setCommitter("Test", "test@example.invalid").call();
		}
		NativeFsMonitorIndexSupport.report(db);
		hook = Path.of(db.getConfig().getString("core", null, "fsmonitor"));
		Files.writeString(hook,
				"#!/bin/sh\nprintf '%s\\0%s\\0' \"$1\" \"$2\" >> .git/query-arguments\ncat .git/fsmonitor-index.response\n");
	}

	@Test
	public void persistedTokenValidatesExactIndexAndLeavesDiskUntouched()
			throws Exception {
		prepare();
		byte[] original = Files.readAllBytes(db.getIndexFile().toPath());
		var index = db.readDirCache();
		var query = FsMonitor.queryStoredIndex(db, index);
		assertNotNull(query.probe());
		assertTrue(query.probe().validatesIndex(index));
		assertFalse(query.forceContentCheck());
		assertEquals(Set.of("m"),
				new HashSet<>(query.probe().getIndexCheckPaths(index)));
		assertEquals(3, query.probe().getIndexValidEntries(index).size());
		assertEquals("2\0native-clock\0", Files.readString(
				db.getDirectory().toPath().resolve("query-arguments")));
		assertNativeStatus(true);
		assertArrayEquals(original,
				Files.readAllBytes(db.getIndexFile().toPath()));
	}

	@Test
	public void parallelPreloadAndPersistedMonitorPreserveNativeStatus()
			throws Exception {
		prepare();
		for (int i = 0; i < 996; i++) {
			writeTrashFile("sub/extra-" + i, "base\n");
		}
		NativeFsMonitorIndexSupport.report(db, "sub/");
		var staged = NativeFsMonitorIndexSupport.mutate(db, "add", "sub");
		Files.copy(staged.index(), db.getIndexFile().toPath(),
				StandardCopyOption.REPLACE_EXISTING);
		NativeFsMonitorIndexSupport.report(db);
		try (Git git = Git.wrap(db)) {
			git.commit().setMessage("preload threshold")
					.setAuthor("Test", "test@example.invalid")
					.setCommitter("Test", "test@example.invalid").call();
		}
		assertEquals(1000, db.readDirCache().getEntryCount());
		NativeFsMonitorIndexSupport.report(db);
		var valid = NativeFsMonitorIndexSupport.mutate(db, "update-index",
				"--fsmonitor-valid", "sub/leaf");
		Files.copy(valid.index(), db.getIndexFile().toPath(),
				StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.COPY_ATTRIBUTES);
		var clean = db.readDirCache();
		assertFalse(
				clean.getFsMonitorCache().isDirty(clean.findEntry("sub/leaf")));
		byte[] original = Files.readAllBytes(db.getIndexFile().toPath());
		Path file = db.getWorkTree().toPath().resolve("sub/leaf");
		var time = Files.getLastModifiedTime(file);
		Files.writeString(file, "next\n");
		Files.setLastModifiedTime(file, time);
		// t7519: a correlated provider deliberately omitting a tracked path
		// leaves it clean until the provider reports it, in both preload modes.
		NativeFsMonitorIndexSupport.report(db);
		for (boolean preload : new boolean[] { false, true }) {
			db.getConfig().setBoolean("core", null, "preloadIndex", preload);
			assertTrue(assertNativeStatus(true).getModified().isEmpty());
		}
		NativeFsMonitorIndexSupport.report(db, "sub/leaf");
		for (boolean preload : new boolean[] { false, true }) {
			db.getConfig().setBoolean("core", null, "preloadIndex", preload);
			assertEquals(Set.of("sub/leaf"),
					assertNativeStatus(true).getModified());
			assertEquals(Set.of("sub/leaf"),
					assertNativeStatus(true).getModified());
		}
		assertArrayEquals(original,
				Files.readAllBytes(db.getIndexFile().toPath()));
	}

	@Test
	public void dirtyBitmapAndReportedPathsFindRestoredMtimeEdits()
			throws Exception {
		prepare();
		for (String path : List.of("m", "sub/leaf")) {
			Path file = db.getWorkTree().toPath().resolve(path);
			var time = Files.getLastModifiedTime(file);
			Files.writeString(file, "next\n");
			Files.setLastModifiedTime(file, time);
		}
		NativeFsMonitorIndexSupport.report(db, "sub/leaf");
		Status status = assertNativeStatus(true);
		assertEquals(Set.of("m", "sub/leaf"), status.getModified());
		assertEquals(Set.of("m", "sub/leaf"),
				assertNativeStatus(true).getModified());
	}

	@Test
	public void repeatedScansQueryThePersistedClockUntilItIsPublished()
			throws Exception {
		prepare();
		for (String path : List.of("a", "sub/leaf"))
			writeTrashFile(path, "changed content\n");
		NativeFsMonitorIndexSupport.report(db, "a", "sub/leaf");
		for (int i = 0; i < 3; i++) {
			assertEquals(Set.of("a", "sub/leaf"),
					assertNativeStatus(true).getModified());
		}
		String[] arguments = Files
				.readString(
						db.getDirectory().toPath().resolve("query-arguments"))
				.split("\0");
		for (int i = 0; i < arguments.length; i += 2) {
			assertEquals("2", arguments[i]);
			assertEquals("native-clock", arguments[i + 1]);
		}
	}

	@Test
	public void ignoredAndUntrackedResultsRemainCompleteWithTrustedLeaves()
			throws Exception {
		prepare();
		writeTrashFile("sub/.gitignore", "*.tmp\n");
		writeTrashFile("sub/hidden.tmp", "ignored\n");
		writeTrashFile("sub/loose", "untracked\n");
		NativeFsMonitorIndexSupport.report(db, "sub/.gitignore",
				"sub/hidden.tmp", "sub/loose");
		Status status = assertNativeStatus(true);
		assertEquals(Set.of("sub/.gitignore", "sub/loose"),
				status.getUntracked());
		assertEquals(Set.of("sub/hidden.tmp"), status.getIgnoredNotInIndex());
		writeTrashFile("sub/.gitignore", "\n");
		NativeFsMonitorIndexSupport.report(db, "sub/.gitignore");
		assertTrue(assertNativeStatus(true).getUntracked()
				.contains("sub/hidden.tmp"));
	}

	@Test
	public void failedMalformedAndRootQueriesRequireRealContentChecks()
			throws Exception {
		prepare();
		Path file = db.getWorkTree().toPath().resolve("sub/leaf");
		var time = Files.getLastModifiedTime(file);
		Files.writeString(file, "next\n");
		Files.setLastModifiedTime(file, time);
		for (String script : List.of("exit 1\n", "printf 'invalid'\n",
				"printf 'native-clock\\0/\\0'\n")) {
			Files.writeString(hook, "#!/bin/sh\n" + script);
			assertEquals(Set.of("sub/leaf"),
					assertNativeStatus(false).getModified());
		}
	}

	@Test
	public void snapshotCannotAuthorizeAnotherIndexOrIndexChangedByHook()
			throws Exception {
		prepare();
		var old = db.readDirCache();
		var probe = FsMonitor.queryStoredIndex(db, old).probe();
		assertNotNull(probe);
		try (Git git = Git.wrap(db)) {
			writeTrashFile("new", "new\n");
			git.add().addFilepattern("new").call();
		}
		assertFalse(probe.validatesIndex(db.readDirCache()));
		Files.writeString(hook,
				"#!/bin/sh\nprintf '\\0' >> .git/index\nprintf 'native-clock\\0'\n");
		var result = FsMonitor.queryStoredIndex(db, db.readDirCache());
		assertTrue(result.probe() == null);
		assertTrue(result.forceContentCheck());
	}

	@Test
	public void suppliedIndexDoesNotNeedAnotherParse() throws Exception {
		prepare();
		var index = db.readDirCache();
		AtomicInteger reads = new AtomicInteger();
		try (var repository = new FileRepository(db.getDirectory()) {
			@Override
			public DirCache readDirCache() throws IOException {
				reads.incrementAndGet();
				return super.readDirCache();
			}
		}) {
			var query = FsMonitor.queryStoredIndex(repository, index);
			assertNotNull(query.probe());
			assertTrue(query.probe().validatesIndex(index));
			assertEquals(3, query.probe().getIndexValidEntries(index).size());
			assertEquals("The caller already supplied the parsed index", 0,
					reads.get());
		}
	}

	@Test
	public void changedZeroFooterIndexCannotValidateAnEarlierRead()
			throws Exception {
		prepare();
		db.getConfig().setBoolean("index", null, "skipHash", true);
		db.getConfig().save();
		var locked = db.lockDirCache();
		try {
			locked.write();
			assertTrue(locked.commit());
		} finally {
			locked.unlock();
		}
		var original = db.readDirCache();
		var file = db.getIndexFile().toPath();
		byte[] bytes = Files.readAllBytes(file);
		var time = Files.getLastModifiedTime(file);
		var changed = NativeFsMonitorIndexSupport.mutate(db, "update-index",
				"--chmod=+x", "a");
		Files.copy(changed.index(), file, StandardCopyOption.REPLACE_EXISTING);
		Files.setLastModifiedTime(file, time);
		byte[] after = Files.readAllBytes(file);
		assertEquals(bytes.length, after.length);
		assertArrayEquals(new byte[20],
				Arrays.copyOfRange(after, after.length - 20, after.length));
		var query = FsMonitor.queryStoredIndex(db, original);
		assertTrue(query.probe() == null
				|| !query.probe().validatesIndex(original));
		assertTrue(query.forceContentCheck() || query.probe()
				.getIndexCheckPaths(original).equals(List.of("/")));
	}

	@Test
	public void changedChecksumFooterCannotAuthorizeStoredValidity()
			throws Exception {
		prepare();
		var index = db.readDirCache();
		Path file = db.getIndexFile().toPath();
		byte[] bytes = Files.readAllBytes(file);
		bytes[bytes.length - 1] ^= 1;
		var time = Files.getLastModifiedTime(file);
		Files.write(file, bytes);
		Files.setLastModifiedTime(file, time);
		var query = FsMonitor.queryStoredIndex(db, index);
		assertNull(query.probe());
		assertTrue(query.forceContentCheck());
	}

	@Test
	public void largeOptionalExtensionsKeepPayloadCorrelation()
			throws Exception {
		prepare();
		byte[] original = Files.readAllBytes(db.getIndexFile().toPath());
		for (int tail : new int[] { 0, 1, 19, 20, 21 }) {
			byte[] padded = Arrays.copyOf(original, 65536 + tail);
			int at = original.length - 20;
			var header = java.nio.ByteBuffer.wrap(padded);
			header.putInt(at, 0x54455354); // Optional TEST extension.
			header.putInt(at + 4, padded.length - 20 - at - 8);
			var digest = Constants.newMessageDigest();
			digest.update(padded, 0, padded.length - 20);
			System.arraycopy(digest.digest(), 0, padded, padded.length - 20,
					20);
			Files.write(db.getIndexFile().toPath(), padded);
			var index = db.readDirCache();
			var query = FsMonitor.queryStoredIndex(db, index);
			assertNotNull(query.probe());
			assertTrue(query.probe().validatesIndex(index));
			assertNativeStatus(true);
		}
	}

	@Test
	public void explicitAndAutoVersionOneUseTheStoredNanosecondBoundary()
			throws Exception {
		prepare();
		var index = db.lockDirCache();
		try {
			index.setFsMonitorCache(new FsMonitorCache(Constants.encode("1"),
					index.getFsMonitorCache().getDirtyEntries()));
			index.write();
			assertTrue(index.commit());
		} finally {
			index.unlock();
		}
		Files.writeString(hook,
				"#!/bin/sh\nprintf '%s\\0%s\\0' \"$1\" \"$2\" >> .git/v1-arguments\n[ \"$1\" = 1 ] || exit 1\nexit 0\n");
		for (String version : new String[] { "1", null, "3" }) {
			if (version == null)
				db.getConfig().unset("core", null, "fsmonitorHookVersion");
			else
				db.getConfig().setString("core", null, "fsmonitorHookVersion",
						version);
			var query = FsMonitor.queryStoredIndex(db, db.readDirCache());
			assertNotNull(query.probe());
			assertTrue("version=" + version,
					query.probe().validatesIndex(db.readDirCache()));
		}
		String[] calls = Files
				.readString(db.getDirectory().toPath().resolve("v1-arguments"))
				.split("\0");
		for (int i = 0; i < calls.length; i += 2)
			assertEquals("1", calls[i + 1]);
	}

	private Status assertNativeStatus(boolean monitored) throws Exception {
		byte[] bytes = NativeFsMonitorIndexSupport.status(db, monitored);
		Set<String> modified = new HashSet<>(), missing = new HashSet<>(),
				untracked = new HashSet<>(), ignored = new HashSet<>();
		for (String record : new String(bytes, StandardCharsets.UTF_8)
				.split("\0")) {
			if (record.startsWith(" M "))
				modified.add(record.substring(3));
			else if (record.startsWith(" D "))
				missing.add(record.substring(3));
			else if (record.startsWith("?? "))
				untracked.add(record.substring(3));
			else if (record.startsWith("!! ")) {
				String path = record.substring(3);
				ignored.add(path.endsWith("/")
						? path.substring(0, path.length() - 1)
						: path);
			} else if (!record.isEmpty())
				throw new AssertionError(record);
		}
		try (Git git = Git.wrap(db)) {
			Status actual = git.status().call();
			assertEquals(modified, actual.getModified());
			assertEquals(missing, actual.getMissing());
			assertEquals(untracked, actual.getUntracked());
			assertEquals(ignored, actual.getIgnoredNotInIndex());
			assertTrue(actual.getAdded().isEmpty());
			assertTrue(actual.getChanged().isEmpty());
			assertTrue(actual.getRemoved().isEmpty());
			assertTrue(actual.getConflicting().isEmpty());
			return actual;
		}
	}
}
