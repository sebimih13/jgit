/*
 * Copyright (C) 2026, and others
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import java.util.List;
import org.eclipse.jgit.junit.NativeCommitMessageTestCase;
import org.eclipse.jgit.lib.CommitConfig.CleanupMode;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Native message scenarios through the actual JGit commit command. */
@RunWith(Parameterized.class)
public class NativeCommitMessageTest extends NativeCommitMessageTestCase {
	public NativeCommitMessageTest(String scenario) { super(scenario); }
	@Parameterized.Parameters(name = "{0}")
	public static List<Object[]> cases() { return scenarios(); }
	@Override
	protected RevCommit commitMessage(String message, CleanupMode cleanup, boolean noVerify,
			boolean allowEmptyMessage) throws Exception {
		try (Git git = Git.wrap(db)) {
			return git.commit().setMessage(message).setCleanupMode(cleanup).setDefaultClean(false)
					.setAmend(scenario.contains("amend-reuse"))
					.setAll(scenario.equals("cherry-all-repository-message"))
					.setUseNativeMessageCleanup(true).setAllowEmptyMessage(allowEmptyMessage)
					.setNoVerify(noVerify).call();
		}
	}
}
