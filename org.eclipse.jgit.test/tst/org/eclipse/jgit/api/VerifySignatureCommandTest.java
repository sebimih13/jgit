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

import static org.junit.Assert.assertTrue;

import org.eclipse.jgit.junit.RepositoryTestCase;
import org.junit.Test;

public class VerifySignatureCommandTest extends RepositoryTestCase {
	@Test
	public void unsignedCommitIsSkipped() throws Exception {
		try (Git git = new Git(db)) {
			git.commit().setAllowEmpty(true).setMessage("unsigned").call();
			assertTrue(new VerifySignatureCommand(db).addName("HEAD").call()
					.isEmpty());
		}
	}
}
