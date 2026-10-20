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
		try (Git git = Git.wrap(db)) {
			return git.push().setRemote(remote).setRefSpecs(specifications)
					.call().iterator().next();
		}
	}
}
