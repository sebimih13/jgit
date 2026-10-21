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
						"[master c65b2edc46e3be184967084e67d39021764d9dec] first comit", //
						"", // amend
						"[master bfffa75680b006c6b94bdfb1ba1ecce6c3630cbd] first commit", //
						"", // log
						"commit bfffa75680b006c6b94bdfb1ba1ecce6c3630cbd", //
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
