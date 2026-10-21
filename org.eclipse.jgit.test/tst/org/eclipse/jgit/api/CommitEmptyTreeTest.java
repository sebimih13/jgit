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

import org.eclipse.jgit.junit.CommitEmptyTreeTestCase;
import org.eclipse.jgit.revwalk.RevCommit;

/** Native empty-tree scenarios through JGit CommitCommand. */
public class CommitEmptyTreeTest extends CommitEmptyTreeTestCase {
	@Override
	protected RevCommit commit(boolean allowEmpty, boolean amend, String message) throws Exception {
		try (Git git = Git.wrap(db)) {
			return git.commit().setAllowEmpty(allowEmpty).setAmend(amend).setMessage(message).call();
		}
	}
}
