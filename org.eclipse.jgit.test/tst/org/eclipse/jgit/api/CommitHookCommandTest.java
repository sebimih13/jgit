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

import static org.junit.Assert.assertFalse;

import java.nio.file.Files;

import org.eclipse.jgit.junit.CommitHookTestCase;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Test;

/** Commit hook scenarios through the JGit commit command. */
public class CommitHookCommandTest extends CommitHookTestCase {
	@Override
	protected RevCommit commitStaged() throws Exception {
		try (Git git = Git.wrap(db)) {
			return git.commit().setMessage("first").call();
		}
	}

	@Test
	public void noVerifySkipsSuccessfulPreCommit() throws Exception {
		noVerifySkipsPreCommit(0);
	}

	@Test
	public void noVerifySkipsFailingPreCommit() throws Exception {
		noVerifySkipsPreCommit(1);
	}

	private void noVerifySkipsPreCommit(int exit) throws Exception {
		hook("pre-commit", "echo unexpected >\"$GIT_DIR/actual-hooks\"\nexit "
				+ exit + "\n");
		try (Git git = Git.wrap(db)) {
			git.commit().setMessage("bypass").setNoVerify(true).call();
		}
		assertFalse(Files
				.exists(db.getDirectory().toPath().resolve("actual-hooks")));
	}
}
