/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.junit.NativeFsMonitorWorkloadSupport;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class NativeCacheStatusTest extends RepositoryTestCase {
	@Parameterized.Parameters(name = "manyFiles={0}, untrackedCache={1}, preloadIndex={2}")
	public static List<Object[]> options() {
		return List.of(new Object[] { null, false, false },
				new Object[] { null, false, true },
				new Object[] { null, true, false },
				new Object[] { null, true, true },
				new Object[] { true, null, false },
				new Object[] { true, null, true },
				new Object[] { true, false, false },
				new Object[] { true, false, true },
				new Object[] { true, null, null },
				new Object[] { false, null, null });
	}

	private final Boolean manyFiles;

	private final Boolean configuredUntrackedCache;

	private final boolean untrackedCache;

	private final Boolean preloadIndex;

	public NativeCacheStatusTest(Boolean manyFiles,
			Boolean untrackedCache, Boolean preloadIndex) {
		this.manyFiles = manyFiles;
		this.configuredUntrackedCache = untrackedCache;
		this.untrackedCache = untrackedCache != null ? untrackedCache.booleanValue()
				: Boolean.TRUE.equals(manyFiles);
		this.preloadIndex = preloadIndex;
	}

	private Git git;

	private Path response;

	private int token;

	@Override
	@Before
	public void setUp() throws Exception {
		super.setUp();
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		git = Git.wrap(db);
		setOption("feature", "manyFiles", manyFiles);
		setOption("core", "untrackedCache", configuredUntrackedCache);
		setOption("core", "preloadIndex", preloadIndex);
		db.getConfig().setBoolean("jgit", null, "fsmonitorCache", true);
		response = db.getDirectory().toPath().resolve("native-cache-response");
		Path hook = db.getDirectory().toPath().resolve("hooks/fsmonitor-test");
		Files.createDirectories(hook.getParent());
		Files.writeString(hook,
				"#!/bin/sh\n[ \"$#\" = 2 ] && [ \"$1\" = 2 ] || exit 2\nprintf '%s\\n' \"$2\" >>.git/native-cache-calls\ncat .git/native-cache-response\n");
		assertTrue(FS.DETECTED.setExecute(hook.toFile(), true));
		db.getConfig().setString("core", null, "fsmonitor", hook.toString());
		db.getConfig().save();
		reply("/");
	}

	private void setOption(String section, String name, Boolean value) {
		if (value == null)
			db.getConfig().unset(section, null, name);
		else
			db.getConfig().setBoolean(section, null, name, value.booleanValue());
	}

	private void put(String path, String text) throws Exception {
		Path file = db.getWorkTree().toPath().resolve(path);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
	}

	private void reply(String... paths) throws Exception {
		StringBuilder text = new StringBuilder("token" + (++token))
				.append('\0');
		for (String path : paths)
			text.append(path).append('\0');
		Files.writeString(response, text.toString());
	}

	private int monitorCalls() throws Exception {
		Path calls = db.getDirectory().toPath().resolve("native-cache-calls");
		return Files.exists(calls) ? Files.readAllLines(calls).size() : 0;
	}

	private void fsmonitorFixture() throws Exception {
		for (String dir : List.of("", "dir1/", "dir2/")) {
			put(dir + "tracked", "");
			put(dir + "modified", "");
		}
		git.add().addFilepattern(".").call();
		git.commit().setMessage("initial").call();
	}

	private void dirty() throws Exception {
		int n = 1;
		for (String dir : List.of("", "dir1/", "dir2/")) {
			put(dir + "untracked", "");
			put(dir + "modified", (n++) + "\n");
			put(dir + "new", (n++) + "\n");
		}
	}

	private void untrackedFixture() throws Exception {
		for (String path : List.of("one", "two", "three", "done/one",
				"dtwo/two", "dthree/three"))
			put(path, "");
		git.add().addFilepattern("one").addFilepattern("two")
				.addFilepattern("done/one").call();
		Files.createDirectories(db.getDirectory().toPath().resolve("info"));
		Files.writeString(db.getDirectory().toPath().resolve("info/exclude"),
				"");
	}

	private Status fresh() throws Exception {
		String hook = db.getConfig().getString("core", null, "fsmonitor");
		db.getConfig().setBoolean("jgit", null, "fsmonitorCache", false);
		db.getConfig().setBoolean("core", null, "fsmonitor", false);
		try {
			return git.status().call();
		} finally {
			if (hook == null) {
				db.getConfig().unset("core", null, "fsmonitor");
			} else {
				db.getConfig().setString("core", null, "fsmonitor", hook);
			}
			db.getConfig().setBoolean("jgit", null, "fsmonitorCache", true);
		}
	}

	private void equal(Status expected, Status actual) {
		assertEquals(expected.getAdded(), actual.getAdded());
		assertEquals(expected.getChanged(), actual.getChanged());
		assertEquals(expected.getRemoved(), actual.getRemoved());
		assertEquals(expected.getMissing(), actual.getMissing());
		assertEquals(expected.getModified(), actual.getModified());
		assertEquals(expected.getUntracked(), actual.getUntracked());
		assertEquals(expected.getIgnoredNotInIndex(),
				actual.getIgnoredNotInIndex());
		assertEquals(expected.getConflictingStageState(),
				actual.getConflictingStageState());
		assertEquals(expected.getUntrackedFolders(),
				actual.getUntrackedFolders());
	}

	private Status check() throws Exception {
		Status actual = git.status().call();
		nativeOracle(actual.getAdded(), actual.getChanged(),
				actual.getRemoved(), actual.getModified(), actual.getMissing(),
				actual.getUntracked());
		equal(fresh(), actual);
		return actual;
	}

	// t7519: compare status with and without fsmonitor, for both cache/preload
	// values.
	@Test
	public void reportedDirtyPathsMatchFullScan() throws Exception {
		fsmonitorFixture();
		git.status().call();
		dirty();
		for (String path : List.of("new", "dir1/new", "dir2/new"))
			git.add().addFilepattern(path).call();
		reply("untracked", "dir1/untracked", "dir2/untracked", "modified",
				"dir1/modified", "dir2/modified", "new", "dir1/new",
				"dir2/new");
		Status status = check();
		assertEquals(Set.of("new", "dir1/new", "dir2/new"), status.getAdded());
		assertEquals(Set.of("modified", "dir1/modified", "dir2/modified"),
				status.getModified());
		assertEquals(Set.of("untracked", "dir1/untracked", "dir2/untracked"),
				status.getUntracked());
	}

	// t7519: omitted notifications expose the process-local whole-snapshot
	// contract.
	// Native rescans untracked paths when UNTR is disabled; this difference is
	// recorded.
	@Test
	public void unreportedPathsRescanUntrackedWhenCacheDisabled()
			throws Exception {
		fsmonitorFixture();
		Status clean = git.status().call();
		int initialCalls = monitorCalls();
		assertTrue("Initial status must invoke the monitor", initialCalls > 0);
		reply();
		Status repeated = git.status().call();
		if (untrackedCache) {
			assertSame(clean, repeated);
		} else {
			assertTrue(repeated.getUntracked().isEmpty());
		}
		int repeatedCalls = monitorCalls();
		assertTrue("Cached status must query the hook again",
				repeatedCalls > initialCalls);
		dirty();
		reply();
		Status hidden = git.status().call();
		assertTrue("Unreported edits must still query the hook",
				monitorCalls() > repeatedCalls);
		if (untrackedCache) {
			assertSame(clean, hidden);
		} else {
			assertNotSame(clean, hidden);
		}
		assertTrue(hidden.getModified().isEmpty());
		// Disabled UNTR replaces the weak cached result after each scan.
		// This omitted-notification test needs that result alive; collection
		// deliberately permits the production fallback to find all edits.
		java.lang.ref.Reference.reachabilityFence(repeated);
		Status full = fresh();
		assertEquals(untrackedCache ? Set.of() : full.getUntracked(),
				hidden.getUntracked());
		assertEquals(Set.of("modified", "dir1/modified", "dir2/modified"),
				full.getModified());
		assertEquals(Set.of("untracked", "dir1/untracked", "dir2/untracked",
				"new", "dir1/new", "dir2/new"), full.getUntracked());
	}

	// t7519: ignore .git changes when invalidating UNTR. Assert the
	// implemented snapshot reuse contract, rather than native UNTR counters.
	@Test
	public void metadataOnlyNotificationsPreserveCachedWorktreeResults()
			throws Exception {
		fsmonitorFixture();
		put("loose", "untracked");
		put("dir1/loose", "nested untracked");
		var before = git.status().call();
		reply(".git", ".git/index");
		var after = git.status().call();
		if (untrackedCache)
			assertSame(before, after);
		equal(before, after);
		assertEquals(Set.of("loose", "dir1/loose"), after.getUntracked());
		check();
	}

	// t7519: the original six untracked files and all four metadata paths.
	// Native UNTR bytes must survive Java monitoring, including nested paths.
	@Test
	public void metadataNotificationsPreserveImportedNativeUntrackedPayload()
			throws Exception {
		for (String dir : List.of("", "dir1/", "dir2/")) {
			put(dir + "tracked", "");
			put(dir + "modified", "");
		}
		var age = java.nio.file.attribute.FileTime.fromMillis(
				System.currentTimeMillis() - 60000);
		for (String dir : List.of("dir1/", "dir2/", ""))
			Files.setLastModifiedTime(db.getWorkTree().toPath().resolve(dir), age);
		NativeFsMonitorWorkloadSupport.warmNativeUntrackedCache(db);
		byte[] index = Files.readAllBytes(db.getIndexFile().toPath());
		var imported = db.readDirCache().getUntrackedCache();
		assertNotNull(imported);
		assertNotNull(imported.root);
		assertTrue(imported.root.valid);
		byte[] payload = imported.write();
		var before = git.status().call();
		assertEquals(Set.of("tracked", "modified", "dir1/tracked",
				"dir1/modified", "dir2/tracked", "dir2/modified"),
				before.getUntracked());
		reply(".git", ".git/index", "dir1/.git", "dir1/.git/index");
		var after = check();
		equal(before, after);
		assertArrayEquals(index, Files.readAllBytes(db.getIndexFile().toPath()));
		assertArrayEquals(payload, db.readDirCache().getUntrackedCache().write());
	}

	// The .git/index notification itself can be ignored only if the actual
	// index is unchanged. Keep the retained cache warm across a real stage.
	@Test
	public void metadataOnlyNotificationsDoNotHideIndexChanges()
			throws Exception {
		fsmonitorFixture();
		var before = git.status().call();
		put("tracked", "new indexed content\n");
		git.add().addFilepattern("tracked").call();
		reply(".git", ".git/index");
		var after = check();
		assertNotSame(before, after);
		assertEquals(Set.of("tracked"), after.getChanged());
		assertTrue(after.getModified().isEmpty());
		assertTrue(after.getMissing().isEmpty());
	}

	// t7519 discard_index(): a new monitoring lifetime cannot inherit an old
	// result/token, even when the hook omits an edit made between lifetimes.
	@Test
	public void closingAndReopeningMonitorDoesNotHideUnreportedEdits()
			throws Exception {
		fsmonitorFixture();
		Status before;
		try (Git first = Git.open(db.getWorkTree())) {
			before = first.status().call();
			reply();
			equal(before, first.status().call());
		}
		put("tracked", "changed after monitoring lifetime ended\n");
		reply();
		try (Git reopened = Git.open(db.getWorkTree())) {
			var after = reopened.status().call();
			assertNotSame(before, after);
			assertEquals(Set.of("tracked"), after.getModified());
			var calls = Files.readAllLines(
					db.getDirectory().toPath().resolve("native-cache-calls"));
			assertEquals("c:0:0:0:0", calls.get(calls.size() - 1));
			nativeOracle(after.getAdded(), after.getChanged(),
					after.getRemoved(), after.getModified(), after.getMissing(),
					after.getUntracked());
			equal(fresh(), after);
		}
	}

	// t7519: stage/unstage 100 entries after the original six index entries.
	@Test
	public void stagingAndUnstagingHundredEntriesDoesNotLeaveStalePositions()
			throws Exception {
		fsmonitorFixture();
		git.status().call();
		Set<String> names = new TreeSet<>();
		for (int n = 1; n <= 100; n++) {
			String name = "z" + n;
			names.add(name);
			put(name, "");
			git.add().addFilepattern(name).call();
		}
		reply();
		assertEquals(names, check().getAdded());
		ResetCommand reset = git.reset();
		for (String name : names)
			reset.addPath(name);
		reset.call();
		reply();
		Status status = check();
		assertTrue(status.getAdded().isEmpty());
		assertEquals(names, status.getUntracked());
	}

	// t7063: initial status and subsequent stable status preserve all paths.
	@Test
	public void initialUntrackedFixtureAndRepeatHaveSameResults()
			throws Exception {
		untrackedFixture();
		Status initial = check();
		assertEquals(Set.of("one", "two", "done/one"), initial.getAdded());
		assertEquals(Set.of("three", "dtwo/two", "dthree/three"),
				initial.getUntracked());
		reply();
		equal(initial, git.status().call());
	}

	// t7063 cases 4, 15, 17, 19, 21-28, cumulatively in the original order.
	@Test
	public void cumulativeIgnoreAndIndexTransitionsSurviveCommit()
			throws Exception {
		untrackedFixture();
		Set<String> added = Set.of("one", "two", "done/one");
		cumulativeCheck(added, Set.of("three", "dtwo/two", "dthree/three"));
		put("four", "");
		reply("four");
		cumulativeCheck(added,
				Set.of("four", "three", "dtwo/two", "dthree/three"));
		put(".gitignore", "four\n");
		reply(".gitignore");
		var ignored = cumulativeCheck(added,
				Set.of(".gitignore", "three", "dtwo/two", "dthree/three"));
		assertTrue(ignored.getIgnoredNotInIndex().contains("four"));
		Files.writeString(db.getDirectory().toPath().resolve("info/exclude"),
				"three\n");
		reply();
		var excluded = cumulativeCheck(added, Set.of(".gitignore", "dtwo/two"));
		assertTrue(excluded.getIgnoredNotInIndex().containsAll(Set.of("four", "three")));
		git.rm().setCached(true).addFilepattern("two").call();
		reply();
		cumulativeCheck(Set.of("one", "done/one"),
				Set.of(".gitignore", "dtwo/two", "two"));
		git.add().addFilepattern("two").call();
		reply();
		cumulativeCheck(added, Set.of(".gitignore", "dtwo/two"));
		put("done/.gitignore", "two\nthree\n");
		put("done/two", "two\n");
		// Fixture equivalent of native add -f; AddCommand has no force flag.
		forceStage("done/two");
		git.add().addFilepattern("done/.gitignore").call();
		reply("done/.gitignore", "done/two");
		var staged = cumulativeCheck(
				Set.of("one", "two", "done/one", "done/.gitignore", "done/two"),
				Set.of(".gitignore", "dtwo/two"));
		assertTrue(!staged.getIgnoredNotInIndex().contains("done/two"));
		git.commit().setMessage("first commit").call();
		reply();
		var committed = cumulativeCheck(Set.of(), Set.of(".gitignore", "dtwo/two"));
		assertEquals(db.readDirCache().getEntry("done/two").getObjectId(),
				db.resolve("HEAD:done/two"));
		assertTrue(!committed.getIgnoredNotInIndex().contains("done/two"));
		reply();
		var repeated = git.status().call();
		if (untrackedCache)
			assertSame(committed, repeated);
		equal(committed, repeated);
	}

	private Status cumulativeCheck(Set<String> added,
			Set<String> untracked) throws Exception {
		var status = check();
		assertEquals(added, status.getAdded());
		assertEquals(untracked, status.getUntracked());
		assertTrue(status.getChanged().isEmpty());
		assertTrue(status.getRemoved().isEmpty());
		assertTrue(status.getModified().isEmpty());
		assertTrue(status.getMissing().isEmpty());
		// Core's independent full scan drops its cache. Rewarm before the next
		// mutation; EGit retains the same entry and runs its refresh job.
		reply();
		var warm = git.status().call();
		equal(status, warm);
		return warm;
	}

	private void forceStage(String path) throws Exception {
		byte[] content = Files.readAllBytes(db.getWorkTree().toPath().resolve(path));
		var cache = db.lockDirCache();
		try (var inserter = db.newObjectInserter()) {
			var blob = inserter.insert(Constants.OBJ_BLOB, content);
			inserter.flush();
			var editor = cache.editor();
			editor.add(new DirCacheEditor.PathEdit(path) {
				@Override
				public void apply(DirCacheEntry entry) {
					entry.setFileMode(FileMode.REGULAR_FILE);
					entry.setObjectId(blob);
					entry.setLength(content.length);
				}
			});
			assertTrue(editor.commit());
		} finally {
			cache.unlock();
		}
	}

	// t7063: modify in root directory.
	@Test
	public void rootCreationInvalidatesCachedResult() throws Exception {
		untrackedFixture();
		git.status().call();
		put("four", "");
		reply("four");
		assertEquals(Set.of("three", "four", "dtwo/two", "dthree/three"),
				check().getUntracked());
	}

	// t7063: new .gitignore invalidates recursively, including an empty hook
	// reply.
	@Test
	public void newRootIgnoreInvalidatesRecursively() throws Exception {
		untrackedFixture();
		put("four", "");
		put("dtwo/four", "");
		git.status().call();
		put(".gitignore", "four\n");
		reply(".gitignore");
		Status status = check();
		assertEquals(Set.of(".gitignore", "three", "dtwo/two", "dthree/three"),
				status.getUntracked());
		assertTrue(status.getIgnoredNotInIndex()
				.containsAll(Set.of("four", "dtwo/four")));
	}

	// t7063: info/exclude invalidates every directory.
	@Test
	public void newInfoExcludeInvalidatesAllDirectories() throws Exception {
		untrackedFixture();
		git.status().call();
		Files.writeString(db.getDirectory().toPath().resolve("info/exclude"),
				"three\n");
		reply();
		assertEquals(Set.of("dtwo/two"), check().getUntracked());
	}

	// t7063: cached -> untracked -> cached, with the original staged/unborn
	// fixture.
	@Test
	public void indexMembershipTransitionsInvalidateCachedResults()
			throws Exception {
		untrackedFixture();
		git.status().call();
		git.rm().setCached(true).addFilepattern("two").call();
		reply();
		Status removed = check();
		assertEquals(Set.of("one", "done/one"), removed.getAdded());
		assertEquals(Set.of("two", "three", "dtwo/two", "dthree/three"),
				removed.getUntracked());
		git.add().addFilepattern("two").call();
		reply();
		assertEquals(Set.of("one", "two", "done/one"), check().getAdded());
	}

	@Test
	public void unreportedDeletionAndIgnoredCreationRescanWhenCacheDisabled()
			throws Exception {
		fsmonitorFixture();
		put(".gitignore", "*.tmp\n");
		git.add().addFilepattern(".gitignore").call();
		git.commit().setMessage("ignore").call();
		put("old", "untracked");
		put("old.tmp", "ignored");
		Status before = git.status().call();
		Files.delete(db.getWorkTree().toPath().resolve("old"));
		Files.delete(db.getWorkTree().toPath().resolve("old.tmp"));
		put("new-file", "untracked");
		put("new.tmp", "ignored");
		reply();
		Status after = git.status().call();
		if (untrackedCache) {
			assertSame(before, after);
		} else {
			assertEquals(Set.of("new-file"), after.getUntracked());
			assertEquals(Set.of("new.tmp"), after.getIgnoredNotInIndex());
			equal(fresh(), after);
			nativeOracle(after.getAdded(), after.getChanged(),
					after.getRemoved(), after.getModified(), after.getMissing(),
					after.getUntracked());
		}
	}

	@Test
	public void nestedRepositoryKeepsApiPathAndMatchesTypedNativeDirectory()
			throws Exception {
		fsmonitorFixture();
		put("nested/file", "untracked");
		git.status().call();
		try (Git nested = Git.init()
				.setDirectory(
						db.getWorkTree().toPath().resolve("nested").toFile())
				.call()) {
			assertNotNull(nested.getRepository());
		}
		reply("nested/.git");
		assertEquals(Set.of("nested"), check().getUntracked());
	}

	// t7063: a tracked directory replaced by a symlink stays clean through
	// repeated checkouts with both untracked-cache settings.
	@Test
	public void directoryToSymlinkCheckoutDoesNotLeaveCachedChildren()
			throws Exception {
		Assume.assumeTrue(FS.DETECTED.supportsSymlinks());
		assertCleanReplacementCheckouts(true);
	}

	// t7063: the same checkout transition with a regular file at the old
	// directory path must not leak the old directory listing.
	@Test
	public void directoryToFileCheckoutDoesNotLeaveCachedChildren()
			throws Exception {
		assertCleanReplacementCheckouts(false);
	}

	private void assertCleanReplacementCheckouts(boolean symlink)
			throws Exception {
		put("one/file", "");
		put("two/file", "");
		git.add().addFilepattern(".").call();
		var first = git.commit().setMessage("first commit").call();
		git.rm().addFilepattern("one/file").call();
		Path one = db.getWorkTree().toPath().resolve("one");
		Files.deleteIfExists(one);
		if (symlink) {
			Files.createSymbolicLink(one, Path.of("two"));
		} else {
			Files.writeString(one, "");
		}
		git.add().addFilepattern("one").call();
		var second = git.commit().setMessage("second commit").call();
		git.status().call();
		for (var target : List.of(first, second, first, second)) {
			git.checkout().setName(target.name()).call();
			reply();
			Status actual = check();
			assertTrue(actual.getAdded().isEmpty());
			assertTrue(actual.getChanged().isEmpty());
			assertTrue(actual.getRemoved().isEmpty());
			assertTrue(actual.getModified().isEmpty());
			assertTrue(actual.getMissing().isEmpty());
			assertTrue(actual.getUntracked().isEmpty());
			git.status().call();
			reply();
			equal(actual, git.status().call());
		}
	}

	// t7063: directory summaries must disappear and reappear when the last
	// untracked file in a subdirectory moves into and out of the index.
	@Test
	public void subdirectoryMembershipTransitionsInvalidateCachedSummaries()
			throws Exception {
		untrackedFixture();
		git.status().call();
		git.add().addFilepattern("dtwo/two").call();
		reply();
		Status staged = check();
		assertEquals(Set.of("one", "two", "done/one", "dtwo/two"),
				staged.getAdded());
		assertEquals(Set.of("three", "dthree/three"), staged.getUntracked());
		git.status().call();
		git.rm().setCached(true).addFilepattern("dtwo/two").call();
		reply();
		Status unstaged = check();
		assertEquals(Set.of("one", "two", "done/one"), unstaged.getAdded());
		assertEquals(Set.of("three", "dtwo/two", "dthree/three"),
				unstaged.getUntracked());
	}

	// t7063: checkout and commit retain correct untracked results. This tests
	// observable results; native UNTR payload/counter preservation is separate.
	@Test
	public void checkoutAndCommitPreserveUntrackedResults() throws Exception {
		fsmonitorFixture();
		put("loose", "root untracked");
		put("dir1/loose", "nested untracked");
		Set<String> expected = Set.of("loose", "dir1/loose");
		var initial = db.resolve("HEAD");
		git.status().call();
		git.checkout().setCreateBranch(true).setName("cache-transition").call();
		reply();
		assertEquals(expected, check().getUntracked());
		git.status().call();
		put("tracked", "changed tracked content");
		git.add().addFilepattern("tracked").call();
		git.commit().setMessage("commit with warm cache").call();
		reply();
		assertEquals(expected, check().getUntracked());
		git.status().call();
		git.checkout().setName(initial.name()).call();
		reply();
		assertEquals(expected, check().getUntracked());
	}

	// t7063: nested untracked directory creation after an already cached scan.
	@Test
	public void newlyCreatedNestedDirectoriesInvalidateCachedSummaries()
			throws Exception {
		untrackedFixture();
		git.status().call();
		put("done/sub/sub/file", "sub");
		reply("done/sub/");
		Set<String> expected = Set.of("three", "dtwo/two", "dthree/three",
				"done/sub/sub/file");
		assertEquals(expected, check().getUntracked());
		git.status().call();
		reply();
		assertEquals(expected, check().getUntracked());
	}

	// t7519: refresh_index() invalidates fsmonitor cache after moving HEAD back.
	@Test
	public void mixedResetInvalidatesWarmMonitorAndIndexState() throws Exception {
		fsmonitorFixture();
		var original = db.resolve("HEAD");
		dirty();
		git.add().addFilepattern(".").call();
		git.commit().setMessage("to reset").call();
		reply("/");
		git.status().call();
		reply();
		git.status().call();
		git.reset().setRef(original.name()).setMode(ResetCommand.ResetType.MIXED).call();
		reply();
		var status = check();
		assertTrue(status.getAdded().isEmpty());
		assertTrue(status.getChanged().isEmpty());
		assertEquals(Set.of("modified", "dir1/modified", "dir2/modified"), status.getModified());
		assertEquals(Set.of("new", "dir1/new", "dir2/new", "untracked",
				"dir1/untracked", "dir2/untracked"), status.getUntracked());
		assertEquals(original, db.resolve("HEAD"));
	}

	// t7063: empty repo (no index) and core.untrackedCache.
	@Test
	public void emptyRepositoryWithoutIndexRemainsUsableWithCaches() throws Exception {
		assertTrue(!Files.exists(db.getIndexFile().toPath()));
		// t7063 invokes write-tree, not just status, in the no-index case.
		try (var inserter = db.newObjectInserter()) {
			var tree = db.readDirCache().writeTree(inserter);
			inserter.flush();
			assertEquals(Constants.EMPTY_TREE_ID, tree);
			assertTrue(db.getObjectDatabase().has(tree));
		}
		assertTrue(!Files.exists(db.getIndexFile().toPath()));
		var empty = check();
		assertTrue(empty.getAdded().isEmpty());
		assertTrue(empty.getUntracked().isEmpty());
		put("loose", "untracked");
		reply("loose");
		assertEquals(Set.of("loose"), check().getUntracked());
		assertTrue(!Files.exists(db.getIndexFile().toPath()));
	}

	// t7063 config transitions, extended through a warm monitor/cache instance.
	@Test
	public void explicitCacheDisableOverridesManyFilesAndRescansUntracked() throws Exception {
		fsmonitorFixture();
		git.status().call();
		db.getConfig().setBoolean("feature", null, "manyFiles", true);
		db.getConfig().setBoolean("core", null, "untrackedCache", false);
		db.getConfig().save();
		reply();
		git.status().call();
		put("loose", "untracked");
		reply();
		assertEquals(Set.of("loose"), check().getUntracked());
		db.getConfig().unset("core", null, "untrackedCache");
		db.getConfig().save();
		reply();
		assertEquals(Set.of("loose"), check().getUntracked());
		Files.delete(db.getWorkTree().toPath().resolve("loose"));
		reply("loose");
		assertTrue(check().getUntracked().isEmpty());
	}

	// p7519: retain the original directory sizes and command order. This ports
	// command acceptance/results; it makes no timing or cache-drop assertions.
	@Test
	public void nativePerformanceWorkloadsRetainCorrectResults()
			throws Exception {
		NativeFsMonitorWorkloadSupport.populate(db);
		git.add().setAll(true).call();
		git.commit().setAuthor("Cache Workload", "cache@example.invalid")
				.setCommitter("Cache Workload", "cache@example.invalid")
				.setMessage("p7519 files").call();
		assertTrue(db.readDirCache().getEntryCount() >= 11110);
		String hook = db.getConfig().getString("core", null, "fsmonitor");
		for (boolean monitored : new boolean[] { true, false }) {
			if (monitored)
				db.getConfig().setString("core", null, "fsmonitor", hook);
			else
				db.getConfig().unset("core", null, "fsmonitor");
			db.getConfig().save();
			git.reset().setMode(ResetCommand.ResetType.HARD).call();
			reply("/");
			for (String mode : List.of("normal", "no", "all")) {
				NativeFsMonitorWorkloadSupport.assertNativeEmpty(db, "status",
						"--porcelain=v1", "-z", "--untracked-files=" + mode);
				check();
			}
			reply();
			var warm = git.status().call();
			reply();
			var repeated = git.status().call();
			if (monitored && untrackedCache)
				assertSame(warm, repeated);
			equal(warm, repeated);
			NativeFsMonitorWorkloadSupport.backdateTrackedFiles(db);
			reply();
			NativeFsMonitorWorkloadSupport.assertNativeEmpty(db, "status",
					"--porcelain=v1", "-z");
			check();
			workloadDiff(false, null, Set.of());
			workloadDiff(true, null, Set.of());
			for (String path : NativeFsMonitorWorkloadSupport.PATHS)
				workloadDiff(false, path, Set.of());
			var nativeIndex = NativeFsMonitorWorkloadSupport.nativeStageAll(db);
			git.add().setAll(true).call();
			NativeFsMonitorWorkloadSupport.assertStagedMatches(db, nativeIndex);
			reply();
			check();
			// Positive controls: unchanged workload output must not hide a
			// broken/no-op diff or staging operation on this larger index.
			Set<String> edited = new TreeSet<>();
			for (String path : NativeFsMonitorWorkloadSupport.PATHS) {
				if (path.equals("1_file"))
					continue;
				String file = path + "/1";
				put(file, "edited " + file + "\n");
				edited.add(file);
			}
			reply(edited.toArray(String[]::new));
			assertEquals(edited, check().getModified());
			workloadDiff(false, null, edited);
			workloadDiff(true, null, edited);
			for (String path : NativeFsMonitorWorkloadSupport.PATHS)
				workloadDiff(false, path, path.equals("1_file")
						? Set.of() : Set.of(path + "/1"));
			nativeIndex = NativeFsMonitorWorkloadSupport.nativeStageAll(db);
			git.add().setAll(true).call();
			NativeFsMonitorWorkloadSupport.assertStagedMatches(db, nativeIndex);
			reply();
			var staged = check();
			assertEquals(edited, staged.getChanged());
			assertTrue(staged.getModified().isEmpty());
		}
	}

	private void workloadDiff(boolean head, String path, Set<String> expected)
			throws Exception {
		NativeFsMonitorWorkloadSupport.assertNativeDiff(db, head, path, expected);
		try (var reader = db.newObjectReader()) {
			var output = new java.io.ByteArrayOutputStream();
			var command = git.diff().setOutputStream(output);
			if (head) {
				var tree = new org.eclipse.jgit.treewalk.CanonicalTreeParser();
				tree.reset(reader, db.resolve("HEAD^{tree}"));
				command.setOldTree(tree);
			}
			if (path != null)
				command.setPathFilter(org.eclipse.jgit.treewalk.filter.PathFilter.create(path));
			Set<String> actual = new TreeSet<>();
			for (var difference : command.call()) {
				assertEquals(org.eclipse.jgit.diff.DiffEntry.ChangeType.MODIFY,
						difference.getChangeType());
				actual.add(difference.getNewPath());
			}
			assertEquals(expected, actual);
			if (expected.isEmpty())
				assertEquals(0, output.size());
			else
				for (String file : expected)
					assertTrue(output.toString(java.nio.charset.StandardCharsets.UTF_8)
							.contains("+edited " + file));
		}
	}

	// t7519's preload/cache matrix with enough entries to run JGit's workers.
	@Test
	public void parallelSizedIndexRetainsStagedAndUnstagedCategories() throws Exception {
		for (int n = 0; n < 1000; n++)
			put("large/f" + n, "base\n");
		git.add().addFilepattern("large").call();
		git.commit().setMessage("large index").call();
		put("large/f0", "staged\n");
		git.add().addFilepattern("large/f0").call();
		reply("/");
		git.status().call();
		put("large/f0", "unstaged after staging\n");
		Files.delete(db.getWorkTree().toPath().resolve("large/f1"));
		put("large/loose\nname", "untracked");
		reply("large/f0", "large/f1", "large/loose\nname");
		var status = check();
		assertEquals(Set.of("large/f0"), status.getChanged());
		assertEquals(Set.of("large/f0"), status.getModified());
		assertEquals(Set.of("large/f1"), status.getMissing());
		assertEquals(Set.of("large/loose\nname"), status.getUntracked());
		put("large/f0", "staged\n");
		put("large/f1", "base\n");
		reply("large/f0", "large/f1");
		var restored = check();
		assertEquals(Set.of("large/f0"), restored.getChanged());
		assertTrue(restored.getModified().isEmpty());
		assertTrue(restored.getMissing().isEmpty());
	}

	private void largeIndexFixture() throws Exception {
		for (int n = 0; n < 1000; n++)
			put("flags/f" + n, "base\n");
		git.add().addFilepattern("flags").call();
		git.commit().setMessage("index flag fixture").call();
	}

	// t4039: keep the indexed version visible while ignoring worktree content.
	// The large fixture also exercises preload-index.c's entry eligibility.
	@Test
	public void assumedEntryRetainsStagedChangeAndFlagRemovalInvalidatesCache()
			throws Exception {
		largeIndexFixture();
		put("flags/f0", "staged\n");
		git.add().addFilepattern("flags/f0").call();
		nativeIndexFlag("--assume-unchanged", "flags/f0");
		assertTrue(db.readDirCache().getEntry("flags/f0").isAssumeValid());
		put("flags/f0", "unreported worktree contents\n");
		put("flags/f1", "ordinary tracked edit\n");
		reply("flags/f0", "flags/f1");
		var dirty = check();
		assertEquals(Set.of("flags/f0"), dirty.getChanged());
		assertEquals(Set.of("flags/f1"), dirty.getModified());
		assertTrue(dirty.getMissing().isEmpty());
		Files.delete(db.getWorkTree().toPath().resolve("flags/f0"));
		reply("flags/f0");
		var absent = check();
		assertEquals(Set.of("flags/f0"), absent.getChanged());
		assertEquals(Set.of("flags/f1"), absent.getModified());
		assertTrue(absent.getMissing().isEmpty());
		git.status().call();
		nativeIndexFlag("--no-assume-unchanged", "flags/f0");
		reply();
		var restored = check();
		assertEquals(Set.of("flags/f0"), restored.getChanged());
		assertEquals(Set.of("flags/f1"), restored.getModified());
		assertEquals(Set.of("flags/f0"), restored.getMissing());
	}

	// t7011: skip-worktree excludes both dirty and absent worktree entries.
	@Test
	public void skippedDirtyAndAbsentEntriesReappearAfterFlagRemoval()
			throws Exception {
		largeIndexFixture();
		put("flags/new", "indexed addition\n");
		git.add().addFilepattern("flags/new").call();
		nativeIndexFlag("--skip-worktree", "flags/f0", "flags/f1", "flags/new");
		put("flags/new", "skipped addition edit\n");
		assertTrue(db.readDirCache().getEntry("flags/f0").isSkipWorkTree());
		assertTrue(db.readDirCache().getEntry("flags/f1").isSkipWorkTree());
		put("flags/f0", "skipped worktree edit\n");
		Files.delete(db.getWorkTree().toPath().resolve("flags/f1"));
		put("flags/f2", "ordinary tracked edit\n");
		reply("flags/f0", "flags/f1", "flags/f2");
		var skipped = check();
		assertEquals(Set.of("flags/new"), skipped.getAdded());
		assertTrue(skipped.getChanged().isEmpty());
		assertEquals(Set.of("flags/f2"), skipped.getModified());
		assertTrue(skipped.getMissing().isEmpty());
		Files.delete(db.getWorkTree().toPath().resolve("flags/new"));
		reply("flags/new");
		var absentAddition = check();
		assertEquals(Set.of("flags/new"), absentAddition.getAdded());
		assertTrue(absentAddition.getMissing().isEmpty());
		git.status().call();
		nativeIndexFlag("--no-skip-worktree", "flags/f0", "flags/f1", "flags/new");
		reply();
		var restored = check();
		assertEquals(Set.of("flags/f0", "flags/f2"), restored.getModified());
		assertEquals(Set.of("flags/new"), restored.getAdded());
		assertEquals(Set.of("flags/f1", "flags/new"), restored.getMissing());
	}

	private void nativeIndexFlag(String flag, String... paths) throws Exception {
		var command = new java.util.ArrayList<>(List.of(
				System.getProperty("jgit.test.gitExecutable", "git"),
				"-c", "core.fsmonitor=false", "update-index", flag));
		command.addAll(List.of(paths));
		Path output = db.getDirectory().toPath().resolve("native-index.out");
		ProcessBuilder builder = new ProcessBuilder(command).directory(db.getWorkTree())
				.redirectErrorStream(true).redirectOutput(output.toFile());
		builder.environment().keySet().removeIf(n -> n.startsWith("GIT_"));
		builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
		builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
		Process process = builder.start();
		try {
			assertTrue("Native flag update timeout",
					process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
			assertEquals(Files.readString(output), 0, process.exitValue());
		} finally {
			if (process.isAlive()) {
				process.destroyForcibly();
				process.waitFor();
			}
		}
	}

	private void nativeOracle(java.util.Set<String> added,
			java.util.Set<String> changed, java.util.Set<String> removed,
			java.util.Set<String> modified, java.util.Set<String> missing,
			java.util.Set<String> untracked) throws Exception {
		String executable = System.getProperty("jgit.test.gitExecutable",
				"git");
		Path output = db.getDirectory().toPath().resolve("native-status.out");
		Path errors = db.getDirectory().toPath().resolve("native-status.err");
		ProcessBuilder builder = new ProcessBuilder(executable,
				"--no-optional-locks", "-c", "core.fsmonitor=false", "-c",
				"core.untrackedCache=false", "status", "--porcelain=v1", "-z",
				"--untracked-files=all", "--no-renames")
				.directory(db.getWorkTree()).redirectOutput(output.toFile())
				.redirectError(errors.toFile());
		builder.environment().keySet()
				.removeIf(name -> name.startsWith("GIT_"));
		builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
		builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
		Process process = builder.start();
		try {
			assertTrue("Native status timeout",
					process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
			assertEquals(Files.readString(errors), 0, process.exitValue());
		} finally {
			if (process.isAlive()) {
				process.destroyForcibly();
				process.waitFor();
			}
		}
		Set<String> expected = new TreeSet<>();
		Set<String> paths = new TreeSet<>();
		for (Set<String> group : List.of(added, changed, removed, modified,
				missing))
			paths.addAll(group);
		for (String path : paths) {
			char index = added.contains(path) ? 'A'
					: changed.contains(path) ? 'M'
							: removed.contains(path) ? 'D' : ' ';
			char worktree = modified.contains(path) ? 'M'
					: missing.contains(path) ? 'D' : ' ';
			expected.add("" + index + worktree + " " + path);
		}
		for (String path : untracked) {
			boolean directory = Files.isDirectory(
					db.getWorkTree().toPath().resolve(path),
					java.nio.file.LinkOption.NOFOLLOW_LINKS);
			expected.add("?? " + path + (directory ? "/" : ""));
		}
		String raw = Files.readString(output);
		assertTrue("Native porcelain must be NUL terminated",
				raw.isEmpty() || raw.charAt(raw.length() - 1) == 0);
		Set<String> actual = new TreeSet<>();
		for (String record : raw.split("\\x00"))
			if (!record.isEmpty()) {
				assertTrue("Duplicate native record: " + record,
						actual.add(record));
			}
		assertEquals(expected, actual);
	}

}
