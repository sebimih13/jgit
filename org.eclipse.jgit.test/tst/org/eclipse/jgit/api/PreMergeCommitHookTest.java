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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.MergeResult.MergeStatus;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.api.errors.AbortedByHookException;

/** Automatic merge scenarios from C Git t7503. */
public class PreMergeCommitHookTest extends RepositoryTestCase {
	@Before
	public void prepareGraph() throws Exception {
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		graph();
	}

	private MergeResult merge(ObjectId target, boolean noVerify, boolean commit,
			boolean squash) throws Exception {
		return git.merge().include(target).setNoVerify(noVerify)
				.setCommit(commit).setSquash(squash).setMessage("merge main\n")
				.call();
	}

	private void resolve() throws Exception {
		git.commit().setMessage("resolve conflict\n").call();
	}

	private Git git;

	private RevCommit root;

	private RevCommit side;

	private ObjectId main;

	private Path record;

	private void graph() throws Exception {
		db.updateRef(Constants.HEAD).link("refs/heads/main");
		git = Git.wrap(db);
		write("file", "root\n");
		root = git.commit().setMessage("zeroth").call();
		git.checkout().setCreateBranch(true).setName("side").call();
		write("foo", "foo\n");
		side = git.commit().setMessage("make it non-ff").call();
		git.branchCreate().setName("side-orig").call();
		git.checkout().setName("main").call();
		write("file", "foo\n");
		main = git.commit().setMessage("first").call();
		record = db.getDirectory().toPath().resolve("actual_hooks");
	}

	private void write(String path, String content) throws Exception {
		Files.writeString(db.getWorkTree().toPath().resolve(path), content);
		git.add().addFilepattern(path).call();
	}

	private void hook(String name, String body, boolean executable)
			throws Exception {
		Path path = db.getDirectory().toPath().resolve("hooks/" + name);
		Files.createDirectories(path.getParent());
		Files.writeString(path, "#!/bin/sh\n" + body + "\n");
		assertTrue(FS.DETECTED.setExecute(path.toFile(), executable));
	}

	private void success() throws Exception {
		hook("pre-merge-commit",
				"echo pre-merge-commit >>\"$GIT_DIR/actual_hooks\"", true);
	}

	private void failure() throws Exception {
		hook("pre-merge-commit",
				"echo pre-merge-commit-failing-hook >>\"$GIT_DIR/actual_hooks\"; exit 1",
				true);
	}

	private void checkoutSide() throws Exception {
		git.checkout().setName("side").call();
	}

	private void assertMerged(MergeResult result) throws Exception {
		assertEquals(MergeStatus.MERGED, result.getMergeStatus());
		try (var walk = new org.eclipse.jgit.revwalk.RevWalk(db)) {
			assertEquals(2, walk.parseCommit(db.resolve(Constants.HEAD))
					.getParentCount());
		}
		assertEquals("foo\n",
				Files.readString(db.getWorkTree().toPath().resolve("file")));
	}

	@Test
	public void noHookMerge() throws Exception {
		checkoutSide();
		assertMerged(merge(main, false, true, false));
		assertFalse(Files.exists(record));
	}

	@Test
	public void noVerifyWithNoHook() throws Exception {
		checkoutSide();
		assertMerged(merge(main, true, true, false));
		assertFalse(Files.exists(record));
	}

	@Test
	public void succeedingMergeHookDoesNotInvokePreCommit() throws Exception {
		success();
		hook("pre-commit",
				"echo pre-commit >>\"$GIT_DIR/actual_hooks\"; exit 9", true);
		checkoutSide();
		assertMerged(merge(main, false, true, false));
		assertEquals("pre-merge-commit\n", Files.readString(record));
	}

	@Test
	public void noVerifyWithSucceedingHook() throws Exception {
		success();
		checkoutSide();
		assertMerged(merge(main, true, true, false));
		assertFalse(Files.exists(record));
	}

	@Test
	public void failingMergeHookLeavesHeadAndPreparedIndex() throws Exception {
		failure();
		checkoutSide();
		assertThrows(AbortedByHookException.class,
				() -> merge(main, false, true, false));
		assertEquals(side, db.resolve(Constants.HEAD));
		assertEquals("pre-merge-commit-failing-hook\n",
				Files.readString(record));
		assertFalse(new java.io.File(db.getDirectory(), "index.lock").exists());
		assertTrue(git.status().call().getChanged().contains("file"));
		assertEquals(org.eclipse.jgit.lib.RepositoryState.MERGING_RESOLVED,
				db.getRepositoryState());
	}

	@Test
	public void noVerifyBypassesBothVerificationHooksButStillPrepares()
			throws Exception {
		failure();
		hook("commit-msg",
				"echo commit-msg >>\"$GIT_DIR/actual_hooks\"; exit 8", true);
		hook("prepare-commit-msg", "echo prepared >>\"$GIT_DIR/actual_hooks\"",
				true);
		checkoutSide();
		assertMerged(merge(main, true, true, false));
		assertEquals("prepared\n", Files.readString(record));
	}

	@Test
	public void nonExecutableHookIsIgnored() throws Exception {
		hook("pre-merge-commit",
				"echo non-exec >>\"$GIT_DIR/actual_hooks\"; exit 1", false);
		checkoutSide();
		assertMerged(merge(main, false, true, false));
		assertFalse(Files.exists(record));
	}

	@Test
	public void noVerifyWithNonExecutableHook() throws Exception {
		hook("pre-merge-commit",
				"echo non-exec >>\"$GIT_DIR/actual_hooks\"; exit 1", false);
		checkoutSide();
		assertMerged(merge(main, true, true, false));
		assertFalse(Files.exists(record));
	}

	@Test
	public void conflictsSkipBothHooksAndResolutionRunsPreCommit()
			throws Exception {
		git.checkout().setCreateBranch(true).setName("conflicting-a")
				.setStartPoint(root).call();
		write("conflicting", "a\n");
		var a = git.commit().setMessage("conflicting-a").call();
		git.checkout().setCreateBranch(true).setName("conflicting-b")
				.setStartPoint(root).call();
		write("conflicting", "b\n");
		var b = git.commit().setMessage("conflicting-b").call();
		git.checkout().setName("conflicting-a").call();
		success();
		hook("pre-commit", "echo pre-commit >>\"$GIT_DIR/actual_hooks\"", true);
		assertEquals(MergeStatus.CONFLICTING,
				merge(b, false, true, false).getMergeStatus());
		assertFalse(Files.exists(record));
		assertEquals(a, db.resolve(Constants.HEAD));
		write("conflicting", "a+b\n");
		resolve();
		assertEquals("pre-commit\n", Files.readString(record));
	}

	@Test
	public void mergeHookIndexChangesRemainStagedOutsideCommit() throws Exception {
		hook("pre-merge-commit",
				"test ! -e \"$GIT_INDEX_FILE.lock\" || exit 4; test \"$GIT_EDITOR\" = : || exit 5; "
						+ "echo extra >extra; GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 git add extra",
				true);
		checkoutSide();
		assertMerged(merge(main, false, true, false));
		try (var walk = new org.eclipse.jgit.revwalk.RevWalk(db)) {
			var commit = walk.parseCommit(db.resolve(Constants.HEAD));
			try (var tree = org.eclipse.jgit.treewalk.TreeWalk.forPath(db,
					"extra", commit.getTree())) {
				assertNull(tree);
			}
		}
		assertTrue(git.status().call().getAdded().contains("extra"));
	}

	@Test
	public void noCommitDefersVerification() throws Exception {
		failure();
		checkoutSide();
		assertEquals(MergeStatus.MERGED_NOT_COMMITTED,
				merge(main, false, false, false).getMergeStatus());
		assertFalse(Files.exists(record));
	}

	@Test
	public void fastForwardSkipsMergeVerification() throws Exception {
		failure();
		git.checkout().setName("main").call();
		git.reset().setMode(ResetCommand.ResetType.HARD).setRef(root.name())
				.call();
		assertEquals(MergeStatus.FAST_FORWARD,
				merge(side, false, true, false).getMergeStatus());
		assertFalse(Files.exists(record));
	}

	@Test
	public void upToDateSkipsMergeVerification() throws Exception {
		failure();
		checkoutSide();
		assertEquals(MergeStatus.ALREADY_UP_TO_DATE,
				merge(side, false, true, false).getMergeStatus());
		assertFalse(Files.exists(record));
	}

	@Test
	public void squashSkipsMergeVerification() throws Exception {
		failure();
		checkoutSide();
		assertEquals(MergeStatus.MERGED_SQUASHED_NOT_COMMITTED,
				merge(main, false, false, true).getMergeStatus());
		assertFalse(Files.exists(record));
	}

	@Test
	public void commitMessageVerificationStillRunsAfterMergeVerification()
			throws Exception {
		success();
		hook("commit-msg",
				"echo commit-msg >>\"$GIT_DIR/actual_hooks\"; exit 3", true);
		checkoutSide();
		assertThrows(AbortedByHookException.class,
				() -> merge(main, false, true, false));
		assertEquals("pre-merge-commit\ncommit-msg\n",
				Files.readString(record));
		assertEquals(side, db.resolve(Constants.HEAD));
	}
}
