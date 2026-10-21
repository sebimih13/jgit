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

import org.eclipse.jgit.junit.CommitHookTestCase;
import org.eclipse.jgit.revwalk.RevCommit;

/** Commit hook scenarios through the JGit commit command. */
public class CommitHookCommandTest extends CommitHookTestCase {
	@Override
	protected RevCommit commitStaged() throws Exception {
		try (Git git = Git.wrap(db)) {
			return git.commit().setHookPrefix(hookPrefix).setMessage("first").call();
		}
	}

	@Override
	protected RevCommit commitWithoutVerification() throws Exception {
		try (Git git = Git.wrap(db)) {
			return git.commit().setHookPrefix(hookPrefix).setMessage("bypass").setNoVerify(true).call();
		}
	}

	@Override
	protected RevCommit commitAs(org.eclipse.jgit.lib.PersonIdent author,
			boolean amend) throws Exception {
		try (Git git = Git.wrap(db)) {
			return git.commit().setHookPrefix(hookPrefix).setMessage("author test").setAuthor(author)
					.setAmend(amend).call();
		}
	}

}
