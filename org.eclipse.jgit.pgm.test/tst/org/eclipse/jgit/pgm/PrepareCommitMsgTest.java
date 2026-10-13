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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.eclipse.jgit.junit.JGitTestUtil;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.Assume;
import org.junit.Test;

public class PrepareCommitMsgTest extends CommitTest {
	private void install(String body) throws Exception {
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		File hook = new File(db.getDirectory(), "hooks/prepare-commit-msg");
		JGitTestUtil.write(hook, "#!/bin/sh\n" + body + "\n");
		assertTrue(FS.DETECTED.setExecute(hook, true));
		writeTrashFile("file", "content");
		execute("git add file");
	}

	@Test
	public void preparationChangesCliCommitMessage() throws Exception {
		install("test \"$2\" = message || exit 9; printf 'prepared\\n' > \"$1\"");
		execute("git commit -m original");
		try (RevWalk walk = new RevWalk(db)) {
			assertEquals("prepared\n", walk
					.parseCommit(db.resolve(Constants.HEAD)).getFullMessage());
		}
	}

	@Test
	public void preparationFailureAbortsCliCommit() throws Exception {
		install("exit 7");
		assertThrows(Die.class, () -> execute("git commit -m original"));
		assertNull(db.resolve(Constants.HEAD));
	}
}
