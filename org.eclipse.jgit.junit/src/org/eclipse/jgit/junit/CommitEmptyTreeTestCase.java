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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertNotNull;

import java.util.List;

import org.eclipse.jgit.api.errors.EmptyCommitException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.lib.Constants;
import org.junit.Test;

/**
 * Native commit emptiness is relative to the proposed first parent.
 * @since 7.8
 */
public abstract class CommitEmptyTreeTestCase extends RepositoryTestCase {
	/**
	 * Execute the adapter's commit with an explicit emptiness policy.
	 * @param allowEmpty whether an empty tree change is allowed
	 * @param amend whether to amend HEAD
	 * @param message commit message
	 * @return resulting commit
	 * @throws Exception if the operation fails
	 */
	protected abstract RevCommit commit(boolean allowEmpty, boolean amend, String message) throws Exception;

	private void rejectEmpty(boolean amend) throws Exception {
		try {
			commit(false, amend, "rejected empty");
			fail("The operation must reject an empty commit");
		} catch (Exception failure) {
			Throwable cause = failure;
			while (cause != null && !(cause instanceof EmptyCommitException)) {
				cause = cause.getCause();
			}
			assertNotNull("The operation must reject an empty commit", cause);
		}
	}

	/**
	 * An unborn branch rejects an empty tree unless explicitly allowed.
	 * @throws Exception if setup or operation fails
	 */
	@Test
	public void rootWithEmptyTreeRequiresAllowance() throws Exception {
		try (Git git = Git.wrap(db)) {
			rejectEmpty(false);
			assertNull(db.resolve("HEAD"));
			assertEquals(Constants.EMPTY_TREE_ID,
					commit(true, false, "allowed root").getTree());
		}
	}

	/**
	 * A nonempty root can be amended without changing its tree.
	 * @throws Exception if setup or operation fails
	 */
	@Test
	public void amendRootWithFilesIsNotEmpty() throws Exception {
		try (Git git = Git.wrap(db)) {
			writeTrashFile("file", "root\n");
			git.add().addFilepattern("file").call();
			var original = git.commit().setMessage("root").call();
			var amended = commit(false, true, "amended");
			assertEquals(original.getTree(), amended.getTree());
			assertEquals(0, amended.getParentCount());
		}
	}

	/**
	 * Amending to the first parent tree requires explicit allowance.
	 * @throws Exception if setup or operation fails
	 */
	@Test
	public void amendToParentTreeRequiresAllowance() throws Exception {
		try (Git git = Git.wrap(db)) {
			writeTrashFile("file", "root\n"); git.add().addFilepattern("file").call();
			var parent = git.commit().setMessage("parent").call();
			writeTrashFile("file", "second\n"); git.add().addFilepattern("file").call();
			var original = git.commit().setMessage("original").call();
			writeTrashFile("file", "root\n"); git.add().addFilepattern("file").call();
			rejectEmpty(true);
			assertEquals(original, db.resolve("HEAD"));
			var allowed = commit(true, true, "allowed amend");
			assertEquals(parent, allowed.getParent(0));
			assertEquals(parent.getTree(), allowed.getTree());
		}
	}

	/**
	 * Merge and amended merge commits may retain the first parent tree.
	 * @throws Exception if setup or operation fails
	 */
	@Test
	public void mergeCanRetainFirstParentTreeWithoutAllowance() throws Exception {
		try (Git git = Git.wrap(db)) {
			writeTrashFile("file", "root\n"); git.add().addFilepattern("file").call();
			git.commit().setMessage("root").call();
			String main = db.getBranch();
			git.checkout().setCreateBranch(true).setName("side").call();
			var side = git.commit().setMessage("side parent").call();
			git.checkout().setName(main).call();
			var first = git.commit().setMessage("first parent").call();
			db.writeMergeHeads(List.of(side));
			db.writeMergeCommitMsg("merge");
			var merge = commit(false, false, "merge");
			assertEquals(2, merge.getParentCount());
			assertEquals(first, merge.getParent(0));
			assertEquals(side, merge.getParent(1));
			assertEquals(first.getTree(), merge.getTree());
			var amended = commit(false, true, "amended merge");
			assertEquals(2, amended.getParentCount());
			assertEquals(merge.getTree(), amended.getTree());
			assertEquals(first, amended.getParent(0));
			assertEquals(side, amended.getParent(1));
		}
	}
}
