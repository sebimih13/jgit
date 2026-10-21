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

import org.eclipse.jgit.junit.PushDefaultTestCase;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** C Git t5528 scenarios through the real JGit push command. */
@RunWith(Parameterized.class)
public class NativePushDefaultTest extends PushDefaultTestCase {
	@Parameterized.Parameters(name = "{0}")
	public static List<Object[]> cases() {
		return scenarios();
	}

	public NativePushDefaultTest(String scenario) {
		super(scenario);
	}

	@Override
	protected boolean executePush(String remote, List<RefSpec> specs,
			boolean dryRun) throws Exception {
		try (Git git = Git.wrap(db)) {
			PushResult result = git.push().setRemote(remote).setPushDefault(null)
					.setRefSpecs(specs).setDryRun(dryRun).call().iterator()
					.next();
			return result.getRemoteUpdates().stream().allMatch(update ->
					update.getStatus() == org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK
					|| update.getStatus() == org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE);
		}
	}
}
