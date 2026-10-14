/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;

import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.RebaseTodoLine;
import org.eclipse.jgit.lib.RebaseTodoLine.Action;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Test;

public class RebaseUnparsedUpstreamTest extends RepositoryTestCase {

	@Test
	public void interactiveRebaseWithUnparsedParent() throws Exception {
		try (Git git = new Git(db)) {
			git.commit().setAllowEmpty(true).setMessage("initial").call();
			RevCommit commit = git.commit().setAllowEmpty(true)
					.setMessage("edit me").call();
			RevCommit parent = commit.getParent(0);
			assertNull(parent.getRawBuffer());
			RebaseResult result = git.rebase().setUpstream(parent)
					.runInteractively(new RebaseCommand.InteractiveHandler() {
						@Override
						public void prepareSteps(List<RebaseTodoLine> steps) {
							try {
								steps.get(0).setAction(Action.EDIT);
							} catch (Exception e) {
								throw new AssertionError(e);
							}
						}

						@Override
						public String modifyCommitMessage(String message) {
							return message;
						}
					}).call();
			assertEquals(RebaseResult.Status.EDIT, result.getStatus());
			assertEquals(commit, db.resolve(Constants.HEAD));
		}
	}
}
