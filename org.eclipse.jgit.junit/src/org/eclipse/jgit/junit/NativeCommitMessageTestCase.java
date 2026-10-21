/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.junit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.CommitConfig.CleanupMode;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Native t7504 hook conditions and t7500 message cleanup contracts.
 * Message-file inputs and resulting commit messages are compared as complete bytes.
 * @since 7.8
 */
public abstract class NativeCommitMessageTestCase extends RepositoryTestCase {
	/** Scenario identifier for all adapters and native captures. */
	protected final String scenario;
	/** Parent of the operation under test. */
	protected RevCommit initial;

	/**
	 * Create the scenario fixture.
	 * @param scenario native scenario identifier
	 */
	protected NativeCommitMessageTestCase(String scenario) {
		this.scenario = scenario;
	}

	/**
	 * Expanded native message scenarios.
	 * @return parameter rows
	 */
	public static List<Object[]> scenarios() {
		return Arrays.stream(new String[] { "no-hook", "success", "failure", "nonexec",
				"bypass-no-hook", "bypass-success", "bypass-failure", "bypass-nonexec",
				"edit-message", "edit-no-lf", "bypass-edit", "prepare-edit", "post-failure",
				"initial-whitespace", "default-comments", "strip-comments", "config-strip",
				"config-verbatim", "hook-whitespace", "verbatim-hook-no-lf", "custom-comment",
				"empty-reject", "empty-allow", "hook-empty-reject", "hook-empty-allow",
				"strip-empty-reject", "whitespace-empty-reject", "verbatim-whitespace",
				"signoff-empty-reject", "indented-comment", "unicode-space",
				"scissors-no-editor", "config-scissors", "auto-hash-free", "auto-frozen-semicolon",
				"auto-indented", "auto-carriage-return", "auto-candidate-order",
				"auto-trailing-comments", "auto-trailing-conflicts", "auto-nontrailing-comments",
				"auto-scissors-tail", "auto-exhausted", "auto-exhausted-whitespace",
				"auto-exhausted-verbatim", "bypass-auto-exhausted", "auto-hook-config-change" }).map(name -> new Object[] { name }).toList();
	}

	/**
	 * Commit the staged index through the adapter's actual operation.
	 * @param message message supplied by the caller
	 * @param cleanup cleanup policy, including DEFAULT/config-derived behavior
	 * @param noVerify whether to bypass verification
	 * @param allowEmptyMessage whether an empty final message is permitted
	 * @return resulting commit
	 * @throws Exception if the operation fails
	 */
	protected abstract RevCommit commitMessage(String message, CleanupMode cleanup,
			boolean noVerify, boolean allowEmptyMessage) throws Exception;

	/**
	 * Prepare the staged change before installing any hooks.
	 * @throws Exception if setup fails
	 */
	@Before
	public void prepareMessageFixture() throws Exception {
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		db.getConfig().setBoolean("maintenance", null, "auto", false);
		db.getConfig().setInt("gc", null, "auto", 0);
		db.getConfig().save();
		try (Git git = Git.wrap(db)) {
			writeTrashFile("file", "initial\n"); git.add().addFilepattern("file").call();
			initial = git.commit().setMessage("initial").call();
			writeTrashFile("file", "staged\n"); git.add().addFilepattern("file").call();
		}
	}

	private Path path(String name) {
		return db.getDirectory().toPath().resolve(name);
	}

	private Path hook(String name, String body) throws Exception {
		Path p = path("hooks/" + name); Files.createDirectories(p.getParent());
		Files.writeString(p, "#!/bin/sh\nGIT_DIR=${GIT_DIR:-.git}\n"
				+ "printf '%s\\n' '" + name + "' >>\"$GIT_DIR/phases\"\n" + body);
		assertTrue(FS.DETECTED.setExecute(p.toFile(), true));
		return p;
	}

	/**
	 * Verify native hook ordering, exact message-file inputs, final bytes and state.
	 * @throws Exception if setup or verification fails
	 */
	@Test
	public void nativeMessageScenario() throws Exception {
		String input = "subject";
		String beforePrepare = "subject\n";
		String beforeMessage = beforePrepare;
		String expected = beforePrepare;
		String replacement = null;
		CleanupMode cleanup = CleanupMode.DEFAULT;
		boolean bypass = scenario.startsWith("bypass-");
		boolean hasMessageHook = !scenario.endsWith("no-hook");
		boolean executable = !scenario.endsWith("nonexec");
		boolean reject = scenario.equals("failure");
		boolean allowEmpty = scenario.endsWith("-allow");
		boolean prepareEdit = scenario.equals("prepare-edit");
		boolean earlyReject = scenario.contains("auto-exhausted");
		int hookCode = scenario.endsWith("failure") && !scenario.equals("post-failure") ? 7 : 0;
		switch (scenario) {
		case "edit-message": case "bypass-edit": replacement = "new message\n"; expected = bypass ? beforePrepare : replacement; break;
		case "edit-no-lf": replacement = "new message"; expected = "new message\n"; break;
		case "prepare-edit": beforeMessage = "prepared"; expected = "prepared\n"; break;
		case "initial-whitespace": input = "\n\nsubject  \n\nbody\t \n\n"; beforePrepare = "subject\n\nbody\n"; beforeMessage = beforePrepare; expected = beforePrepare; break;
		case "default-comments": input = "# retained\nsubject"; beforePrepare = "# retained\nsubject\n"; beforeMessage = beforePrepare; expected = beforePrepare; break;
		case "strip-comments": case "config-strip":
			input = "# removed\nsubject"; beforePrepare = "# removed\nsubject\n"; beforeMessage = beforePrepare; expected = "subject\n";
			if (scenario.equals("config-strip")) db.getConfig().setString("commit", null, "cleanup", "strip");
			else cleanup = CleanupMode.STRIP;
			break;
		case "config-verbatim": input = "\nsubject  \n\n"; beforePrepare = input; beforeMessage = input; expected = input; db.getConfig().setString("commit", null, "cleanup", "verbatim"); break;
		case "hook-whitespace": replacement = "\nnew message  \n\nbody\t\n\n"; expected = "new message\n\nbody\n"; break;
		case "verbatim-hook-no-lf": cleanup = CleanupMode.VERBATIM; replacement = "new message"; expected = replacement; break;
		case "custom-comment": cleanup = CleanupMode.STRIP; db.getConfig().setString("core", null, "commentChar", ";"); input = "; removed\nsubject"; beforePrepare = input + "\n"; beforeMessage = beforePrepare; expected = "subject\n"; break;
		case "empty-reject": case "empty-allow": input = ""; beforePrepare = ""; beforeMessage = ""; expected = ""; reject = !allowEmpty; break;
		case "hook-empty-reject": case "hook-empty-allow": replacement = ""; expected = ""; reject = !allowEmpty; break;
		case "strip-empty-reject": input = "# comment only"; beforePrepare = input + "\n"; beforeMessage = beforePrepare; cleanup = CleanupMode.STRIP; expected = ""; reject = true; break;
		case "whitespace-empty-reject": input = " \t\n\n"; beforePrepare = ""; beforeMessage = ""; expected = ""; reject = true; break;
		case "verbatim-whitespace": cleanup = CleanupMode.VERBATIM; input = " \t"; beforePrepare = input + "\n"; beforeMessage = beforePrepare; expected = beforePrepare; break;
		case "signoff-empty-reject": input = "Signed-off-by: Oracle <oracle@example.invalid>"; beforePrepare = input + "\n"; beforeMessage = beforePrepare; expected = beforePrepare; reject = true; break;
		case "indented-comment": cleanup = CleanupMode.STRIP; input = "  # retained\n# removed\nsubject"; beforePrepare = input + "\n"; beforeMessage = beforePrepare; expected = "  # retained\nsubject\n"; break;
		case "unicode-space": input = "subject\u2003"; beforePrepare = input + "\n"; beforeMessage = beforePrepare; expected = beforePrepare; break;

		case "scissors-no-editor": case "config-scissors":
			input = "subject\n# ------------------------ >8 ------------------------\nretained";
			beforePrepare = input + "\n"; beforeMessage = beforePrepare; expected = beforePrepare;
			if (scenario.equals("config-scissors")) db.getConfig().setString("commit", null, "cleanup", "scissors");
			else cleanup = CleanupMode.SCISSORS;
			break;

		case "auto-hash-free": replacement = "# removed\nsubject"; expected = "subject\n"; break;
		case "auto-frozen-semicolon": input = "# kept\nsubject"; replacement = "# kept\n; removed\nsubject"; expected = "# kept\nsubject\n"; break;
		case "auto-indented": input = "  # kept\nsubject"; replacement = "  # kept\n# removed\nsubject"; expected = "  # kept\nsubject\n"; break;
		case "auto-carriage-return": input = "subject\r# occupied"; replacement = "subject\n; removed\n# retained"; expected = "subject\n# retained\n"; break;
		case "auto-candidate-order": input = "# a\n; b\n@ c\n! d\n$ e\n% f\n^ g\n& h\n| i\nsubject"; replacement = input + "\n: removed"; expected = input + "\n"; break;
		case "auto-trailing-comments": input = "subject\n\n# trailing"; replacement = "# removed\nsubject"; expected = "subject\n"; break;
		case "auto-trailing-conflicts": input = "subject\n\nConflicts:\n\t#file\n# trailing"; replacement = "# removed\nsubject"; expected = "subject\n"; break;
		case "auto-nontrailing-comments": input = "subject\n# occupied\nbody"; replacement = "# retained\n; removed\nbody"; expected = "# retained\nbody\n"; break;
		case "auto-scissors-tail": input = "subject\n# ------------------------ >8 ------------------------\n; ignored\n@ ignored"; replacement = "# removed\nsubject"; expected = "subject\n"; break;
		case "auto-exhausted": case "auto-exhausted-whitespace": case "auto-exhausted-verbatim": case "bypass-auto-exhausted":
			input = "# 1\n; 2\n@ 3\n! 4\n$ 5\n% 6\n^ 7\n& 8\n| 9\n: 10\nsubject";
			reject = true;
			break;
		case "auto-hook-config-change": input = "# occupied\nsubject"; replacement = "# retained\n; removed\n! retained\nsubject"; expected = "# retained\n! retained\nsubject\n"; break;

		default: break;
		}
		if (scenario.contains("auto-")) {
			db.getConfig().setString("core", null, "commentChar", "auto");
			cleanup = scenario.equals("auto-exhausted-whitespace") ? CleanupMode.WHITESPACE
					: scenario.equals("auto-exhausted-verbatim") ? CleanupMode.VERBATIM : CleanupMode.STRIP;
			beforePrepare = input + "\n";
			beforeMessage = beforePrepare;
		}

		db.getConfig().save();
		hook("pre-commit", "test \"$#\" = 0\n");
		hook("prepare-commit-msg", "test \"$#\" = 2 && test \"$2\" = message || exit 21\n"
				+ "cp \"$1\" \"$GIT_DIR/prepare.input\"\n"
				+ (prepareEdit ? "printf prepared >\"$1\"\n" : "")
				+ (scenario.equals("auto-hook-config-change") ? "git config core.commentChar '!'\n" : ""));
		if (hasMessageHook) {
			if (replacement != null) Files.writeString(path("replacement"), replacement);
			Path p = hook("commit-msg", "test \"$#\" = 1 || exit 22\n"
					+ "cp \"$1\" \"$GIT_DIR/message.input\"\n"
					+ (replacement != null ? "cat \"$GIT_DIR/replacement\" >\"$1\"\n" : "")
					+ "exit " + hookCode + "\n");
			assertTrue(FS.DETECTED.setExecute(p.toFile(), executable));
		}
		hook("post-commit", "test \"$#\" = 0 || exit 23\nexit " + (scenario.equals("post-failure") ? 7 : 0) + "\n");
		RevCommit result = null; Exception failure = null;
		try { result = commitMessage(input, cleanup, bypass, allowEmpty); }
		catch (Exception caught) { failure = caught; }
		assertEquals("Unexpected outcome in " + scenario + ": " + failure, reject, failure != null);
		if (earlyReject) {
			assertFalse(Files.exists(path("prepare.input")));
			assertFalse(Files.exists(path("message.input")));
			assertArrayEquals(beforePrepare.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(path("COMMIT_EDITMSG")));
			assertEquals(bypass ? "" : "pre-commit\n", Files.exists(path("phases")) ? Files.readString(path("phases")) : "");
			assertEquals(initial, db.resolve("HEAD"));
			try (Git git = Git.wrap(db)) { assertEquals(java.util.Set.of("file"), git.status().call().getChanged()); }
			return;
		}
		assertArrayEquals(beforePrepare.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(path("prepare.input")));
		boolean messageRuns = !bypass && hasMessageHook && executable;
		if (messageRuns) assertArrayEquals(beforeMessage.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(path("message.input")));
		else assertFalse(Files.exists(path("message.input")));
		String phases = (bypass ? "" : "pre-commit\n") + "prepare-commit-msg\n"
				+ (messageRuns ? "commit-msg\n" : "") + (reject ? "" : "post-commit\n");
		assertEquals(phases, Files.readString(path("phases")));
		if (reject) {
			assertEquals(initial, db.resolve("HEAD"));
			try (Git git = Git.wrap(db)) { assertEquals(java.util.Set.of("file"), git.status().call().getChanged()); }
		} else {
			assertNotNull(result); assertEquals(result, db.resolve("HEAD")); assertEquals(initial, result.getParent(0));
			assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), result.getFullMessage().getBytes(StandardCharsets.UTF_8));
			try (TreeWalk tree = TreeWalk.forPath(db, "file", result.getTree())) {
				assertNotNull(tree); assertArrayEquals("staged\n".getBytes(StandardCharsets.UTF_8), db.open(tree.getObjectId(0)).getBytes());
			}
		}
	}
}
