/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.pgm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import java.io.File;
import java.nio.file.Files;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.junit.JGitTestUtil;
import org.eclipse.jgit.lib.CLIRepositoryTestCase;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/** Native t7503 merge verification cases through the JGit CLI. */
public class PreMergeCommitTest extends CLIRepositoryTestCase {
	private ObjectId side;

	@Before
	public void graph() throws Exception {
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		db.updateRef(Constants.HEAD).link("refs/heads/main");
		Git git = Git.wrap(db);
		writeTrashFile("file", "root\n");
		git.add().addFilepattern("file").call();
		git.commit().setMessage("zeroth").call();
		git.checkout().setCreateBranch(true).setName("side").call();
		writeTrashFile("foo", "foo\n");
		git.add().addFilepattern("foo").call();
		side = git.commit().setMessage("make it non-ff").call();
		git.checkout().setName("main").call();
		writeTrashFile("file", "foo\n");
		git.add().addFilepattern("file").call();
		git.commit().setMessage("first").call();
		git.checkout().setName("side").call();
	}

	private void hook(String name, String body) throws Exception {
		File file = new File(db.getDirectory(), "hooks/" + name);
		JGitTestUtil.write(file, "#!/bin/sh\n" + body + "\n");
		assertTrue(FS.DETECTED.setExecute(file, true));
	}

	@Test
	public void rejectingMergeHookStopsCliMerge() throws Exception {
		hook("pre-merge-commit", "exit 1");
		assertThrows(Die.class, () -> execute("git merge main -m merge"));
		assertEquals(side, db.resolve(Constants.HEAD));
	}

	@Test
	public void noVerifyBypassesMergeAndMessageVerification() throws Exception {
		hook("pre-merge-commit", "exit 1");
		hook("commit-msg", "exit 2");
		execute("git merge main --no-verify -m merge");
		assertFalse(side.equals(db.resolve(Constants.HEAD)));
	}

	@Test
	public void succeedingHookRunsOnceWithoutPreCommit() throws Exception {
		hook("pre-merge-commit",
				"echo pre-merge-commit >>\"$GIT_DIR/actual_hooks\"");
		hook("pre-commit", "exit 3");
		execute("git merge main -m merge");
		assertEquals("pre-merge-commit\n", Files.readString(
				new File(db.getDirectory(), "actual_hooks").toPath()));
	}

	@Test
	public void noVerifyKeepsMessagePreparation() throws Exception {
		hook("pre-merge-commit", "exit 1");
		hook("prepare-commit-msg", "echo prepared >\"$1\"");
		execute("git merge main --no-verify -m merge");
		try (var walk = new org.eclipse.jgit.revwalk.RevWalk(db)) {
			assertEquals("prepared\n", walk
					.parseCommit(db.resolve(Constants.HEAD)).getFullMessage());
		}
	}
}
