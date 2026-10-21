/*
 * Copyright (C) 2022, Matthias Sohn <matthias.sohn@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.pgm;

import static org.junit.Assert.assertEquals;

import org.eclipse.jgit.lib.CLIRepositoryTestCase;
import org.junit.Before;
import org.junit.Test;

public class ShowTest extends CLIRepositoryTestCase {

	private static final String NO_NEWLINE = "\\ No newline at end of file";

	@Before
	public void setup() throws Exception {
		writeTrashFile("a", "a");
		writeTrashFile("b", "b");
		execute("git add a b");
		execute("git commit -m added");
		writeTrashFile("a", "a1");
		execute("git add a");
		execute("git commit -m modified");
	}

	@Test
	public void testShow() throws Exception {
		String result = toString(execute("git show"));
		assertEquals(
				toString("commit 9b653ee786fa13e50cf5ab4dad3b7c6f31668269",
						"Author: GIT_AUTHOR_NAME <GIT_AUTHOR_EMAIL>",
						"Date:   Sat Aug 15 20:12:58 2009 -0330", "",
						"    modified", "", "diff --git a/a b/a",
						"index 2e65efe..59ef8d1 100644", "--- a/a", "+++ b/a",
						"@@ -1 +1 @@", "-a", NO_NEWLINE, "+a1", NO_NEWLINE),
				result);
	}

	@Test
	public void testShowNameOnly() throws Exception {
		String result = toString(execute("git show --name-only"));
		assertEquals(toString("commit 9b653ee786fa13e50cf5ab4dad3b7c6f31668269",
				"Author: GIT_AUTHOR_NAME <GIT_AUTHOR_EMAIL>",
				"Date:   Sat Aug 15 20:12:58 2009 -0330", "", "    modified",
				"a"), result);
	}

	@Test
	public void testShowNameStatus() throws Exception {
		String result = toString(execute("git show --name-status"));
		assertEquals(toString("commit 9b653ee786fa13e50cf5ab4dad3b7c6f31668269",
				"Author: GIT_AUTHOR_NAME <GIT_AUTHOR_EMAIL>",
				"Date:   Sat Aug 15 20:12:58 2009 -0330", "", "    modified",
				"M\ta"), result);
	}
}
