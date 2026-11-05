/*
 * Copyright (C) 2012, IBM Corporation and others. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.pgm;

import static org.junit.Assert.assertArrayEquals;

import org.eclipse.jgit.lib.CLIRepositoryTestCase;
import org.junit.Test;

public class CommitAndLogTest extends CLIRepositoryTestCase {
	@Test
	public void testCommitAmend() throws Exception {
		assertArrayEquals(new String[] { // commit
						"[master f3302078f263ac8b13d4c8915c573bcba8a1d079] first comit", //
						"", // amend
						"[master cfa4bb45b176389bd90a2aea0128c67dae7852a5] first commit", //
						"", // log
						"commit cfa4bb45b176389bd90a2aea0128c67dae7852a5", //
						"Author: GIT_AUTHOR_NAME <GIT_AUTHOR_EMAIL>", //
						"Date:   Sat Aug 15 20:12:58 2009 -0330", //
						"", //
						"    first commit", //
						"", //
						"" //
				}, execute("git commit --allow-empty -m 'first comit'", //
						"git commit --allow-empty --amend -m 'first commit'", //
						"git log"));
	}
}
