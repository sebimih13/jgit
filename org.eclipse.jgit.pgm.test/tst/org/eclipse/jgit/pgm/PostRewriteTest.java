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

/** t5407 amendment cases through actual CLI parsing and execution. */
public class PostRewriteTest extends CLIRepositoryTestCase {
	private ObjectId oldHead;

	@Before
	public void prepareGraph() throws Exception {
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		db.updateRef(Constants.HEAD).link("refs/heads/main");
		Git git = Git.wrap(db);
		for (String name : java.util.List.of("A", "B", "C", "D")) {
			writeTrashFile("foo", name + "\n");
			git.add().addFilepattern("foo").call();
			oldHead = git.commit().setMessage(name + "\n").call();
		}
		install(0);
	}

	private void install(int exit) throws Exception {
		File hook = new File(db.getDirectory(), "hooks/post-rewrite");
		JGitTestUtil.write(hook,
				"#!/bin/sh\necho \"$@\" >\"$GIT_DIR/rewrite.args\"\ncat >\"$GIT_DIR/rewrite.data\"\nexit "
						+ exit + "\n");
		assertTrue(FS.DETECTED.setExecute(hook, true));
	}

	@Test
	public void cliAmendReportsOldAndNewHeads() throws Exception {
		execute("git commit --amend -m amended");
		assertEquals("amend\n", Files.readString(
				new File(db.getDirectory(), "rewrite.args").toPath()));
		assertEquals(
				oldHead.name() + " " + db.resolve(Constants.HEAD).name() + "\n",
				Files.readString(
						new File(db.getDirectory(), "rewrite.data").toPath()));
	}

	@Test
	public void cliNoPostRewriteSuppressesNotification() throws Exception {
		execute("git commit --amend --no-post-rewrite -m amended");
		assertFalse(new File(db.getDirectory(), "rewrite.args").exists());
		assertFalse(new File(db.getDirectory(), "rewrite.data").exists());
	}

	@Test
	public void failingNotificationDoesNotFailCliAmend() throws Exception {
		install(7);
		execute("git commit --amend -m amended");
		assertFalse(oldHead.equals(db.resolve(Constants.HEAD)));
		assertEquals(
				oldHead.name() + " " + db.resolve(Constants.HEAD).name() + "\n",
				Files.readString(
						new File(db.getDirectory(), "rewrite.data").toPath()));
	}
}
