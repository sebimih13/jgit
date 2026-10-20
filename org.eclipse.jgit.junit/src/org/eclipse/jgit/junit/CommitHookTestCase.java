/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.junit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Standalone commit scenarios from C Git t7503 and the commit hook contracts.
 * Both adapters execute their actual commit command against the staged index.
 *
 * @since 7.8
 */
public abstract class CommitHookTestCase extends RepositoryTestCase {
	/** Commit preceding the tested operation. */
	protected RevCommit initial;

	/**
	 * Commit the staged index using the adapter's operation.
	 *
	 * @return resulting commit
	 * @throws Exception
	 *             if the operation fails
	 */
	protected abstract RevCommit commitStaged() throws Exception;

	/**
	 * Commit while bypassing pre-commit and commit-msg verification.
	 *
	 * @return resulting commit
	 * @throws Exception
	 *             if the adapter cannot execute the operation
	 */
	protected RevCommit commitWithoutVerification() throws Exception {
		throw new UnsupportedOperationException();
	}

	/**
	 * Prepare the parent commit and staged changes before installing hooks.
	 *
	 * @throws Exception
	 *             if setup fails
	 */
	@Before
	public void prepareCommitHooks() throws Exception {
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		try (Git git = Git.wrap(db)) {
			writeTrashFile("file", "root\n");
			git.add().addFilepattern("file").call();
			initial = git.commit().setMessage("zeroth").call();
			writeTrashFile("file", "foo\n");
			git.add().addFilepattern("file").call();
		}
	}

	/**
	 * Install an executable hook.
	 *
	 * @param name
	 *            hook name
	 * @param body
	 *            shell script body
	 * @return script path
	 * @throws Exception
	 *             if installation fails
	 */
	protected Path hook(String name, String body) throws Exception {
		Path path = db.getDirectory().toPath().resolve("hooks").resolve(name);
		Files.createDirectories(path.getParent());
		Files.writeString(path, "#!/bin/sh\n" + body);
		assertTrue(FS.DETECTED.setExecute(path.toFile(), true));
		return path;
	}

	private String records() throws Exception {
		return Files
				.readString(db.getDirectory().toPath().resolve("actual-hooks"));
	}

	private String record(String name) {
		return "echo " + name + " >>\"$GIT_DIR/actual-hooks\"\n";
	}

	private void rejected() throws Exception {
		try {
			commitStaged();
			fail("Hook must reject the commit");
		} catch (Exception expected) {
			assertEquals(initial, db.resolve(Constants.HEAD));
		}
		assertEquals("foo\n",
				Files.readString(db.getWorkTree().toPath().resolve("file")));
		try (Git git = Git.wrap(db)) {
			assertEquals(java.util.Set.of("file"),
					git.status().call().getChanged());
		}
	}

	/**
	 * Verify commit with no installed hooks.
	 *
	 * @throws Exception
	 *             if setup or command fails
	 */
	@Test
	public void noHookCommitsStagedChanges() throws Exception {
		assertEquals(initial, commitStaged().getParent(0));
		assertFalse(Files
				.exists(db.getDirectory().toPath().resolve("actual-hooks")));
	}

	/**
	 * Verify a successful pre-commit hook is run exactly once.
	 *
	 * @throws Exception
	 *             if setup or command fails
	 */
	@Test
	public void successfulPreCommitRunsOnce() throws Exception {
		hook("pre-commit", record("pre-commit"));
		assertEquals(initial, commitStaged().getParent(0));
		assertEquals("pre-commit\n", records());
	}

	/**
	 * Verify rejection preserves the parent and staged contents.
	 *
	 * @throws Exception
	 *             if setup or command fails
	 */
	@Test
	public void failingPreCommitStopsLaterHooks() throws Exception {
		hook("pre-commit", record("pre-commit-failing-hook") + "exit 1\n");
		hook("commit-msg", record("commit-msg"));
		hook("post-commit", record("post-commit"));
		rejected();
		assertEquals("pre-commit-failing-hook\n", records());
	}

	/**
	 * Verify non-executable scripts do not reject the operation.
	 *
	 * @throws Exception
	 *             if setup or command fails
	 */
	@Test
	public void nonExecutablePreCommitIsSkipped() throws Exception {
		Path script = hook("pre-commit", record("non-exec") + "exit 1\n");
		assertTrue(FS.DETECTED.setExecute(script.toFile(), false));
		assertEquals(initial, commitStaged().getParent(0));
		assertFalse(Files
				.exists(db.getDirectory().toPath().resolve("actual-hooks")));
	}

	/**
	 * Verify the complete hook order around a successful commit.
	 *
	 * @throws Exception
	 *             if setup or command fails
	 */
	@Test
	public void commitHooksRunInOrder() throws Exception {
		for (String name : java.util.List.of("pre-commit", "prepare-commit-msg",
				"commit-msg", "post-commit")) {
			hook(name, record(name));
		}
		commitStaged();
		assertEquals(
				"pre-commit\nprepare-commit-msg\ncommit-msg\npost-commit\n",
				records());
	}

	/**
	 * Verify pre-commit has zero arguments and empty stdin.
	 *
	 * @throws Exception
	 *             if setup or command fails
	 */
	@Test
	public void preCommitReceivesNoArgumentsAndEmptyInput() throws Exception {
		hook("pre-commit",
				"test \"$#\" = 0 || exit 11\n"
						+ "cat >\"$GIT_DIR/hook.stdin\"\n"
						+ "test ! -s \"$GIT_DIR/hook.stdin\" || exit 12\n"
						+ "test \"$GIT_EDITOR\" = : || exit 13\n");
		commitStaged();
		assertEquals(0,
				Files.size(db.getDirectory().toPath().resolve("hook.stdin")));
	}

	/**
	 * Verify pre-commit sees the staged version of the tracked file.
	 *
	 * @throws Exception
	 *             if setup or command fails
	 */
	@Test
	public void preCommitSeesStagedSnapshot() throws Exception {
		hook("pre-commit", "git show :file >\"$GIT_DIR/hook.index\"\n");
		commitStaged();
		assertEquals("foo\n", Files
				.readString(db.getDirectory().toPath().resolve("hook.index")));
	}

	/**
	 * Verify index changes made by pre-commit enter the committed tree.
	 *
	 * @throws Exception
	 *             if setup or command fails
	 */
	@Test
	public void preCommitCanStageAdditionalChanges() throws Exception {
		hook("pre-commit", "echo injected >injected\ngit add injected\n");
		RevCommit commit = commitStaged();
		try (TreeWalk tree = TreeWalk.forPath(db, "injected",
				commit.getTree())) {
			assertNotNull(tree);
			assertEquals("injected\n",
					new String(db.open(tree.getObjectId(0)).getBytes(),
							java.nio.charset.StandardCharsets.UTF_8));
		}
	}

	/**
	 * Verify a message-hook rejection prevents post-commit.
	 *
	 * @throws Exception
	 *             if setup or command fails
	 */
	@Test
	public void failingCommitMessageStopsPostCommit() throws Exception {
		hook("pre-commit", record("pre-commit"));
		hook("commit-msg", record("commit-msg") + "exit 7\n");
		hook("post-commit", record("post-commit"));
		rejected();
		assertEquals("pre-commit\ncommit-msg\n", records());
	}

	/**
	 * Verify post-commit failure cannot undo the commit.
	 *
	 * @throws Exception
	 *             if setup or command fails
	 */
	@Test
	public void failingPostCommitDoesNotRejectCommit() throws Exception {
		hook("post-commit", record("post-commit") + "exit 7\n");
		RevCommit commit = commitStaged();
		assertEquals(commit, db.resolve(Constants.HEAD));
		assertEquals(initial, commit.getParent(0));
		assertEquals("post-commit\n", records());
	}

	/**
	 * Verify bypass succeeds without installed hooks.
	 *
	 * @throws Exception
	 *             if setup or operation fails
	 */
	@Test
	public void noVerifyWithNoHookCommits() throws Exception {
		assertEquals(initial, commitWithoutVerification().getParent(0));
		assertFalse(Files
				.exists(db.getDirectory().toPath().resolve("actual-hooks")));
	}

	/**
	 * Verify successful verification hooks are bypassed.
	 *
	 * @throws Exception
	 *             if setup or operation fails
	 */
	@Test
	public void noVerifySkipsSuccessfulPreCommit() throws Exception {
		bypassPreCommit(0, true);
	}

	/**
	 * Verify failed verification hooks are bypassed.
	 *
	 * @throws Exception
	 *             if setup or operation fails
	 */
	@Test
	public void noVerifySkipsFailingPreCommit() throws Exception {
		bypassPreCommit(1, true);
	}

	/**
	 * Verify bypass also succeeds with a non-executable script.
	 *
	 * @throws Exception
	 *             if setup or operation fails
	 */
	@Test
	public void noVerifyWithNonExecutablePreCommit() throws Exception {
		bypassPreCommit(1, false);
	}

	private void bypassPreCommit(int code, boolean executable)
			throws Exception {
		Path script = hook("pre-commit",
				record("unexpected") + "exit " + code + "\n");
		assertTrue(FS.DETECTED.setExecute(script.toFile(), executable));
		assertEquals(initial, commitWithoutVerification().getParent(0));
		assertFalse(Files
				.exists(db.getDirectory().toPath().resolve("actual-hooks")));
	}

	/**
	 * Verify preparation and notification remain enabled under bypass.
	 *
	 * @throws Exception
	 *             if setup or operation fails
	 */
	@Test
	public void noVerifyStillPreparesAndNotifies() throws Exception {
		hook("pre-commit", record("unexpected-pre-commit") + "exit 7\n");
		hook("commit-msg", record("unexpected-commit-msg") + "exit 7\n");
		hook("prepare-commit-msg", record("prepare-commit-msg")
				+ "printf 'prepared message\\n' >\"$1\"\n");
		hook("post-commit", record("post-commit"));
		RevCommit commit = commitWithoutVerification();
		assertEquals(initial, commit.getParent(0));
		assertEquals("prepared message\n", commit.getFullMessage());
		assertEquals("prepare-commit-msg\npost-commit\n", records());
	}

}
