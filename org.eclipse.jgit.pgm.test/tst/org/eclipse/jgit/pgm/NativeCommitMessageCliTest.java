/*
 * Copyright (C) 2026, and others
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.pgm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.eclipse.jgit.junit.NativeCommitMessageTestCase;
import org.eclipse.jgit.lib.CommitConfig.CleanupMode;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Shared native message cases through actual CLI option parsing/execution. */
@RunWith(Parameterized.class)
public class NativeCommitMessageCliTest extends NativeCommitMessageTestCase {
	public NativeCommitMessageCliTest(String scenario) { super(scenario); }
	@Parameterized.Parameters(name = "{0}")
	public static List<Object[]> cases() { return scenarios(); }
	@Override
	protected RevCommit commitMessage(String message, CleanupMode cleanup, boolean noVerify,
			boolean allowEmptyMessage) throws Exception {
		Commit command = new Commit();
		command.setCommandName("commit");
		command.initRaw(db, null, new ByteArrayInputStream(new byte[0]),
				new ByteArrayOutputStream(), new ByteArrayOutputStream());
		List<String> args = new ArrayList<>(List.of("-m", message));
		if (cleanup != CleanupMode.DEFAULT) args.add("--cleanup=" + cleanup.name().toLowerCase(Locale.ROOT));
		if (noVerify) args.add("--no-verify");
		if (allowEmptyMessage) args.add("--allow-empty-message");
		command.execute(args.toArray(new String[0]));
		try (RevWalk walk = new RevWalk(db)) { return walk.parseCommit(db.resolve("HEAD")); }
	}
}
