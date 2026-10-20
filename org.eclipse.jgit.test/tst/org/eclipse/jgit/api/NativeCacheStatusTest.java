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
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class NativeCacheStatusTest extends RepositoryTestCase {
	@Parameterized.Parameters(name = "untrackedCache={0}, preloadIndex={1}")
	public static List<Object[]> options() {
		return List.of(new Object[] { false, false },
				new Object[] { false, true }, new Object[] { true, false },
				new Object[] { true, true });
	}

	private final boolean untrackedCache;

	private final boolean preloadIndex;

	public NativeCacheStatusTest(boolean untrackedCache, boolean preloadIndex) {
		this.untrackedCache = untrackedCache;
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
		db.getConfig().setBoolean("core", null, "untrackedCache",
				untrackedCache);
		db.getConfig().setBoolean("core", null, "preloadIndex", preloadIndex);
		db.getConfig().setBoolean("jgit", null, "fsmonitorCache", true);
		response = db.getDirectory().toPath().resolve("native-cache-response");
		Path hook = db.getDirectory().toPath().resolve("hooks/fsmonitor-test");
		Files.createDirectories(hook.getParent());
		Files.writeString(hook,
				"#!/bin/sh\n[ \"$#\" = 2 ] && [ \"$1\" = 2 ] || exit 2\ncat .git/native-cache-response\n");
		assertTrue(FS.DETECTED.setExecute(hook.toFile(), true));
		db.getConfig().setString("core", null, "fsmonitor", hook.toString());
		reply("/");
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
		db.getConfig().setBoolean("jgit", null, "fsmonitorCache", false);
		try {
			return git.status().call();
		} finally {
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
		reply();
		if (untrackedCache) {
			assertSame(clean, git.status().call());
		} else {
			assertTrue(git.status().call().getUntracked().isEmpty());
		}
		dirty();
		reply();
		Status hidden = git.status().call();
		if (untrackedCache) {
			assertSame(clean, hidden);
		} else {
			assertNotSame(clean, hidden);
		}
		assertTrue(hidden.getModified().isEmpty());
		Status full = fresh();
		assertEquals(untrackedCache ? Set.of() : full.getUntracked(),
				hidden.getUntracked());
		assertEquals(Set.of("modified", "dir1/modified", "dir2/modified"),
				full.getModified());
		assertEquals(Set.of("untracked", "dir1/untracked", "dir2/untracked",
				"new", "dir1/new", "dir2/new"), full.getUntracked());
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
