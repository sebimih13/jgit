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

import java.util.List;

import org.eclipse.jgit.junit.PrePushHookTestCase;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;

/** C Git pre-push scenarios through the JGit push command. */
public class PrePushHookCommandTest extends PrePushHookTestCase {
	@Override
	protected PushResult push(String remote, List<RefSpec> specifications)
			throws Exception {
		return executePush(remote, specifications, false);
	}

	@Override
	protected PushResult pushWithoutVerification(String remote,
			List<RefSpec> specifications) throws Exception {
		return executePush(remote, specifications, true);
	}

	private PushResult executePush(String remote, List<RefSpec> specifications,
			boolean noVerify) throws Exception {
		try (Git git = Git.wrap(db)) {
			return git.push().setRemote(remote).setPushDefault(null)
					.setNoVerify(noVerify).setRefSpecs(specifications).call()
					.iterator().next();
		}
	}

	@org.junit.Test
	public void legacyApiDefaultRemainsCurrent() throws Exception {
		db.getConfig().setString("branch", "main", "remote", "parent1");
		db.getConfig().setString("branch", "main", "merge",
				"refs/heads/foreign");
		db.getConfig().setString("push", null, "default", "upstream");
		db.getConfig().save();
		installPrePush(0);
		try (Git git = Git.wrap(db)) {
			PushCommand command = git.push().setRemote("parent1");
			org.junit.Assert.assertEquals(
					org.eclipse.jgit.transport.PushConfig.PushDefault.CURRENT,
					command.getPushDefault());
			command.call();
		}
		org.junit.Assert.assertEquals(next, server.resolve("refs/heads/main"));
		org.junit.Assert.assertNull(server.exactRef("refs/heads/foreign"));
	}

}
