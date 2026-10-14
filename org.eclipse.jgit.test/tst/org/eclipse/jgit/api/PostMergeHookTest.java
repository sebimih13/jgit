/*
 * Copyright (C) 2006 Josh England, 2026 and others
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
import static org.junit.Assert.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.MergeResult.MergeStatus;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.Before;
import org.junit.Assume;
import org.junit.Test;

/** Scenarios from C Git t5402-post-merge-hook.sh and finish() edge cases. */
public class PostMergeHookTest extends RepositoryTestCase {
	@Before
	public void prepareMergeFixture() throws Exception {
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		prepareGraph();
	}

	private MergeResult merge(ObjectId target, boolean squash, boolean commit)
			throws Exception {
		return git.merge().include(target).setSquash(squash).setCommit(commit)
				.setMessage("merge\n").call();
	}

	private Git git;

	private RevCommit commit0;

	private RevCommit commit1;

	private Path record;

	private void prepareGraph() throws Exception {
		git = Git.wrap(db);
		Files.writeString(db.getWorkTree().toPath().resolve("a"),
				"Data for commit0.\n");
		git.add().addFilepattern("a").call();
		commit0 = git.commit().setMessage("setup").call();
		Files.writeString(db.getWorkTree().toPath().resolve("a"),
				"Changed data for commit1.\n");
		git.add().addFilepattern("a").call();
		commit1 = git.commit().setMessage("modify").call();
		// Native t5402 leaves HEAD at commit0 and the effective index/worktree
		// at commit1.
		git.reset().setMode(ResetCommand.ResetType.SOFT).setRef(commit0.name())
				.call();
		record = db.getDirectory().toPath().resolve("post-merge.args");
		installHook(0);
	}

	private void installHook(int exit) throws Exception {
		Path hook = db.getDirectory().toPath().resolve("hooks/post-merge");
		Files.createDirectories(hook.getParent());
		Files.writeString(hook,
				"#!/bin/sh\nprintf '%s\\n' \"$@\" >>\"$GIT_DIR/post-merge.args\"\nexit "
						+ exit + "\n");
		assertTrue(hook.toFile().setExecutable(true));
	}

	private void diverge(boolean conflict) throws Exception {
		git.reset().setMode(ResetCommand.ResetType.HARD).setRef(commit0.name())
				.call();
		Files.writeString(
				db.getWorkTree().toPath().resolve(conflict ? "a" : "b"),
				"local\n");
		git.add().addFilepattern(conflict ? "a" : "b").call();
		git.commit().setMessage("local").call();
	}

	@Test
	public void upToDateDoesNotRunHook() throws Exception {
		assertEquals(MergeStatus.ALREADY_UP_TO_DATE,
				merge(commit0, false, true).getMergeStatus());
		assertFalse(Files.exists(record));
	}

	@Test
	public void normalMergeReceivesZero() throws Exception {
		assertEquals(MergeStatus.FAST_FORWARD,
				merge(commit1, false, true).getMergeStatus());
		assertEquals("0\n", Files.readString(record));
		assertEquals(commit1, db.resolve(Constants.HEAD));
		assertEquals("Changed data for commit1.\n",
				Files.readString(db.getWorkTree().toPath().resolve("a")));
	}

	@Test
	public void squashReceivesOneAndKeepsHead() throws Exception {
		assertEquals(MergeStatus.FAST_FORWARD_SQUASHED,
				merge(commit1, true, false).getMergeStatus());
		assertEquals("1\n", Files.readString(record));
		assertEquals(commit0, db.resolve(Constants.HEAD));
		assertTrue(git.status().call().getChanged().contains("a"));
	}

	@Test
	public void notificationFailureDoesNotUndoMerge() throws Exception {
		installHook(7);
		assertEquals(MergeStatus.FAST_FORWARD,
				merge(commit1, false, true).getMergeStatus());
		assertEquals("0\n", Files.readString(record));
		assertEquals(commit1, db.resolve(Constants.HEAD));
	}

	@Test
	public void nonFastForwardMergeNotifiesOnce() throws Exception {
		diverge(false);
		assertEquals(MergeStatus.MERGED,
				merge(commit1, false, true).getMergeStatus());
		assertEquals("0\n", Files.readString(record));
	}

	@Test
	public void stoppedBeforeCommitDoesNotNotify() throws Exception {
		diverge(false);
		ObjectId before = db.resolve(Constants.HEAD);
		assertEquals(MergeStatus.MERGED_NOT_COMMITTED,
				merge(commit1, false, false).getMergeStatus());
		assertFalse(Files.exists(record));
		assertEquals(before, db.resolve(Constants.HEAD));
	}

	@Test
	public void normalConflictDoesNotNotify() throws Exception {
		diverge(true);
		assertEquals(MergeStatus.CONFLICTING,
				merge(commit1, false, true).getMergeStatus());
		assertFalse(Files.exists(record));
	}

	@Test
	public void squashConflictStillNotifies() throws Exception {
		diverge(true);
		ObjectId before = db.resolve(Constants.HEAD);
		assertEquals(MergeStatus.CONFLICTING,
				merge(commit1, true, false).getMergeStatus());
		assertEquals("1\n", Files.readString(record));
		assertEquals(before, db.resolve(Constants.HEAD));
	}

}
