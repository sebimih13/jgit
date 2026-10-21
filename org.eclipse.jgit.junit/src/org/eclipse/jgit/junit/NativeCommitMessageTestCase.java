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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.CommitConfig.CleanupMode;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
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
				"auto-exhausted-verbatim", "bypass-auto-exhausted", "auto-hook-config-change",
				"auto-prior-marker", "auto-prior-prefix", "auto-prior-marker-order",
				"prefix-char", "prefix-string", "prefix-unicode", "prefix-space",
				"prefix-char-after-string", "prefix-string-after-char", "prefix-interleaved",
				"prefix-include-last", "prefix-after-include", "prefix-string-auto",
				"prefix-string-frozen", "prefix-no-editor-scissors", "prefix-whitespace",
				"prefix-verbatim", "prefix-empty", "prefix-space-hook", "prefix-precommit-change",
				"prefix-auto-overridden", "prefix-invalid-char-empty", "prefix-invalid-char-newline",
				"prefix-invalid-char-bare", "prefix-invalid-empty",
				"prefix-invalid-newline", "prefix-invalid-bare", "prefix-invalid-overridden",
				"cherry-explicit", "bypass-cherry-explicit", "cherry-repository-message",
				"cherry-prepare-edit", "cherry-reject", "cherry-empty-message", "cherry-all-repository-message",
				"revert-explicit", "revert-repository-message", "revert-prepare-edit", "revert-reject",
				"revert-empty-message", "bypass-revert-repository-message", "amend-reuse",
				"amend-reuse-edit", "amend-reuse-reject", "bypass-amend-reuse",
				"state-squash", "state-squash-reject", "state-squash-explicit",
				"state-squash-merge", "state-squash-merge-no-lf", "state-merge-only",
				"state-empty-fill", "state-empty-reject", "amend-reuse-squash",
				"cherry-missing-message-fill", "cherry-missing-message-reject",
				"revert-missing-message-fill", "revert-missing-message-reject",
				"cleanup-squash-directory", "cleanup-merge-directory", "cleanup-squash-empty-directory",
				"cleanup-merge-empty-directory", "cleanup-merge-directory-and-squash",
				"cleanup-squash-symlink", "cleanup-squash-dangling", "cleanup-squash-file"
				}).map(name -> new Object[] { name }).toList();
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
		boolean prepareEdit = scenario.equals("prepare-edit") || scenario.equals("cherry-prepare-edit")
				|| scenario.equals("revert-prepare-edit") || scenario.equals("amend-reuse-edit")
				|| scenario.endsWith("-fill");
		boolean cherry = scenario.contains("cherry-");
		boolean reverting = scenario.contains("revert-");
		boolean amending = scenario.contains("amend-reuse");
		PersonIdent cherryAuthor = new PersonIdent("Cherry Author", "cherry@example.invalid",
				Instant.ofEpochSecond(946684800L), ZoneOffset.ofHoursMinutes(5, 45));
		String messageSource = "message";
		String messageCommit = null;
		String committedContents = "staged\n";
		boolean earlyReject = scenario.contains("auto-exhausted");
		boolean invalidPrefix = scenario.startsWith("prefix-invalid-");
		String prefixConfig = null;
		boolean cleanupCase = scenario.startsWith("cleanup-");
		boolean cleanupDirectory = cleanupCase && scenario.contains("directory");
		String cleanupPath = scenario.startsWith("cleanup-merge") ? "MERGE_MSG" : "SQUASH_MSG";
		String cleanupHook = "";
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

		case "auto-prior-marker": case "auto-prior-prefix":
			input = "# occupied\nsubject\n" + (scenario.equals("auto-prior-prefix") ? ";;" : ";") + " trailing";
			replacement = "# retained\n; retained\n@ removed\nsubject"; expected = "# retained\n; retained\nsubject\n"; break;
		case "auto-prior-marker-order": input = "# occupied\n; occupied\nsubject\n@ trailing"; replacement = "# retained\n; retained\n@ retained\n! removed\nsubject"; expected = "# retained\n; retained\n@ retained\nsubject\n"; break;

		default: break;
		}
		if (scenario.startsWith("prefix-")) {
			cleanup = CleanupMode.STRIP;
			input = "# retained\n/ retained\n// removed\n; retained\nsubject";
			expected = "# retained\n/ retained\n; retained\nsubject\n";
			prefixConfig = "[core]\ncommentString = //\n";
			switch (scenario) {
			case "prefix-char": prefixConfig = "[core]\ncommentChar = //\n"; break;
			case "prefix-unicode":
				prefixConfig = "[core]\ncommentString = ⁑⁕⁑\n";
				input = "⁑ retained\n⁑⁕ retained\n⁑⁕⁑ removed\nsubject";
				expected = "⁑ retained\n⁑⁕ retained\nsubject\n"; break;
			case "prefix-space":
				prefixConfig = "[core]\ncommentString = \"// \"\n";
				input = "//retained\n// removed\n  // retained\nsubject";
				expected = "//retained\n  // retained\nsubject\n"; break;
			case "prefix-char-after-string": case "prefix-interleaved":
				prefixConfig = "[core]\n" + (scenario.equals("prefix-interleaved") ? "commentChar = !\n" : "")
						+ "commentString = //\ncommentChar = \";\"\n";
				expected = "# retained\n/ retained\n// removed\nsubject\n"; break;
			case "prefix-string-after-char":
				prefixConfig = "[core]\ncommentChar = \";\"\ncommentString = //\n"; break;
			case "prefix-auto-overridden":
				prefixConfig = "[core]\ncommentChar = auto\ncommentString = //\n"; break;
			case "prefix-space-hook":
				prefixConfig = "[core]\ncommentString = \"// \"\n";
				replacement = "// \nsubject"; expected = "subject\n"; break;
			case "prefix-include-last": case "prefix-after-include":
				Files.writeString(path("prefix.config"), "[core]\ncommentString = //\n");
				String include = "[include]\npath = prefix.config\n";
				String local = "[core]\ncommentChar = \";\"\n";
				prefixConfig = scenario.equals("prefix-include-last") ? local + include : include + local;
				if (scenario.equals("prefix-after-include")) expected = "# retained\n/ retained\n// removed\nsubject\n";
				break;
			case "prefix-string-auto":
				prefixConfig = "[core]\ncommentString = auto\n";
				input = "# occupied\nsubject";
				replacement = "# retained\n; removed\nsubject";
				expected = "# retained\nsubject\n"; break;
			case "prefix-no-editor-scissors":
				cleanup = CleanupMode.SCISSORS;
				input = "subject\n// ------------------------ >8 ------------------------\nretained";
				expected = input + "\n"; break;
			case "prefix-whitespace": cleanup = CleanupMode.WHITESPACE; expected = input + "\n"; break;
			case "prefix-verbatim": cleanup = CleanupMode.VERBATIM; replacement = "// retained\nsubject"; expected = replacement; break;
			case "prefix-empty": input = "// comment"; expected = ""; reject = true; break;
			case "prefix-invalid-empty": prefixConfig = "[core]\ncommentString = \"\"\n"; break;
			case "prefix-invalid-newline": prefixConfig = "[core]\ncommentString = \"/\\n/\"\n"; break;
			case "prefix-invalid-bare": prefixConfig = "[core]\ncommentString\n"; break;
			case "prefix-invalid-char-empty": prefixConfig = "[core]\ncommentChar = \"\"\n"; break;
			case "prefix-invalid-char-newline": prefixConfig = "[core]\ncommentChar = \"/\\n/\"\n"; break;
			case "prefix-invalid-char-bare": prefixConfig = "[core]\ncommentChar\n"; break;
			case "prefix-invalid-overridden": prefixConfig = "[core]\ncommentString = \"\"\ncommentChar = \"#\"\n"; break;
			default: break;
			}
			beforePrepare = input + "\n";
			beforeMessage = beforePrepare;
			reject |= invalidPrefix;
		}
		if (scenario.contains("auto-")) {
			if (scenario.equals("auto-prior-marker")) {
				db.getConfig().setStringList("core", null, "commentChar", List.of(";", "auto"));
			} else if (scenario.equals("auto-prior-prefix")) {
				db.getConfig().setStringList("core", null, "commentChar", List.of(";;", "auto"));
			} else if (scenario.equals("auto-prior-marker-order")) {
				db.getConfig().setStringList("core", null, "commentChar", List.of(";", "@", "auto"));
			} else {
				db.getConfig().setString("core", null, "commentChar", "auto");
			}
			cleanup = scenario.equals("auto-exhausted-whitespace") ? CleanupMode.WHITESPACE
					: scenario.equals("auto-exhausted-verbatim") ? CleanupMode.VERBATIM : CleanupMode.STRIP;
			beforePrepare = input + "\n";
			beforeMessage = beforePrepare;
		}

		if (cherry || reverting) {
			CommitBuilder source = new CommitBuilder();
			source.setTreeId(initial.getTree());
			source.setParentId(initial);
			source.setAuthor(cherryAuthor);
			source.setCommitter(initial.getCommitterIdent());
			source.setMessage("picked message\n");
			try (ObjectInserter inserter = db.newObjectInserter()) {
				if (cherry) db.writeCherryPickHead(inserter.insert(source));
				else db.writeRevertHead(inserter.insert(source));
				inserter.flush();
			}
			String repositoryMessage = scenario.endsWith("empty-message") ? "" : "repository message\n";
			db.writeMergeCommitMsg(repositoryMessage);
			if (scenario.equals("cherry-repository-message") || scenario.equals("cherry-all-repository-message") || prepareEdit
					|| scenario.equals("cherry-empty-message") || reverting && !scenario.equals("revert-explicit")) {
				input = null;
				messageSource = "merge";
				beforePrepare = repositoryMessage;
				beforeMessage = prepareEdit ? "prepared" : beforePrepare;
				expected = prepareEdit ? "prepared\n" : beforePrepare;
			}
			if (scenario.equals("cherry-reject")) { reject = true; hookCode = 7; }
			if (scenario.equals("cherry-empty-message")) reject = true;
			if (scenario.equals("revert-reject")) { reject = true; hookCode = 7; }
			if (scenario.equals("revert-empty-message")) reject = true;
			if (scenario.equals("cherry-all-repository-message")) {
				committedContents = "from worktree\n";
				writeTrashFile("file", committedContents);
			}
		}
		if (amending) {
			input = null;
			messageSource = "commit";
			messageCommit = "HEAD";
			beforePrepare = initial.getFullMessage();
			if (!beforePrepare.endsWith("\n")) beforePrepare += "\n";
			beforeMessage = prepareEdit ? "prepared" : beforePrepare;
			expected = prepareEdit ? "prepared\n" : beforePrepare;
			if (scenario.equals("amend-reuse-reject")) { reject = true; hookCode = 7; }
		}
		if (scenario.startsWith("state-") && !scenario.equals("state-squash-explicit")) {
			input = null;
			messageSource = scenario.startsWith("state-empty-") ? null
					: scenario.equals("state-merge-only") ? "merge" : "squash";
			beforePrepare = scenario.startsWith("state-empty-") ? "" : "squash message\n";
			if (scenario.equals("state-merge-only")) beforePrepare = "merge message\n";
			if (scenario.equals("state-squash-merge")) beforePrepare += "merge message\n";
			if (scenario.equals("state-squash-merge-no-lf")) beforePrepare = "squashmerge message\n";
			beforeMessage = prepareEdit ? "prepared" : beforePrepare;
			expected = prepareEdit ? "prepared\n" : beforePrepare;
		}
		if (scenario.startsWith("state-squash")) {
			db.writeSquashCommitMsg(scenario.endsWith("no-lf") ? "squash" : "squash message\n");
		}
		if (scenario.contains("state-squash-merge") || scenario.equals("state-merge-only")) {
			db.writeMergeCommitMsg("merge message\n");
		}
		if (scenario.equals("amend-reuse-squash")) db.writeSquashCommitMsg("unused squash message\n");
		if (scenario.contains("missing-message")) {
			db.writeMergeCommitMsg(null);
			input = null;
			messageSource = cherry ? "commit" : null;
			messageCommit = cherry ? "CHERRY_PICK_HEAD" : null;
			beforePrepare = "";
			beforeMessage = prepareEdit ? "prepared" : "";
			expected = prepareEdit ? "prepared\n" : "";
		}
		if (scenario.equals("state-squash-reject")) { reject = true; hookCode = 7; }
		if (scenario.equals("state-empty-reject") || scenario.endsWith("missing-message-reject")) reject = true;
		if (cleanupCase) {
			String target = "\"$GIT_DIR/" + cleanupPath + "\"";
			if (scenario.equals("cleanup-merge-directory-and-squash")) db.writeSquashCommitMsg("unused squash\n");
			if (cleanupDirectory) {
				cleanupHook = "mkdir " + target + "\n";
				if (!scenario.contains("empty-directory")) cleanupHook += "printf keep >" + target + "/kept\n";
			} else if (scenario.equals("cleanup-squash-symlink")) {
				cleanupHook = "mkdir \"$GIT_DIR/cleanup-target\"\nprintf target >\"$GIT_DIR/cleanup-target/kept\"\n"
						+ "ln -s cleanup-target " + target + "\n";
			} else if (scenario.equals("cleanup-squash-dangling")) {
				cleanupHook = "ln -s missing-target " + target + "\n";
			} else {
				cleanupHook = "printf unused >" + target + "\n";
			}
		}
		if (prefixConfig != null) {
			db.getConfig().fromText(db.getConfig().toText() + prefixConfig);
		}
		db.getConfig().save();
		db.getConfig().load();
		boolean hadSquash = Files.exists(path("SQUASH_MSG"));
		boolean hadMerge = Files.exists(path("MERGE_MSG"));
		byte[] squashBefore = hadSquash ? Files.readAllBytes(path("SQUASH_MSG")) : new byte[0];
		byte[] mergeBefore = hadMerge ? Files.readAllBytes(path("MERGE_MSG")) : new byte[0];
		hook("pre-commit", (cherry ? authorRecord("pre-commit") : "") + "test \"$#\" = 0\n"
				+ (reverting ? "test \"$GIT_AUTHOR_NAME\" != 'Cherry Author' || exit 24\n" : "")
				+ (scenario.equals("prefix-precommit-change") ? "git config core.commentString '!'\n" : ""));
		hook("prepare-commit-msg", (cherry ? authorRecord("prepare-commit-msg") : "")
				+ "test \"$#\" = " + (messageSource == null ? "1" : messageCommit == null ? "2" : "3") + " || exit 21\n"
				+ (messageSource == null ? "" : "test \"$2\" = " + messageSource + " || exit 21\n")
				+ (messageCommit == null ? "" : "test \"$3\" = " + messageCommit + " || exit 25\n")
				+ (hadSquash ? "test -f \"$GIT_DIR/SQUASH_MSG\" || exit 26\n" : "")
				+ "cp \"$1\" \"$GIT_DIR/prepare.input\"\n"
				+ (prepareEdit ? "printf prepared >\"$1\"\n" : "")
				+ (scenario.equals("auto-hook-config-change") ? "git config core.commentChar '!'\n" : "")
				+ (scenario.equals("prefix-string-frozen") ? "git config core.commentString '!'\n" : ""));
		if (hasMessageHook) {
			if (replacement != null) Files.writeString(path("replacement"), replacement);
			Path p = hook("commit-msg", (cherry ? authorRecord("commit-msg") : "") + "test \"$#\" = 1 || exit 22\n"
					+ "cp \"$1\" \"$GIT_DIR/message.input\"\n"
					+ (replacement != null ? "cat \"$GIT_DIR/replacement\" >\"$1\"\n" : "")
					+ cleanupHook
					+ "exit " + hookCode + "\n");
			assertTrue(FS.DETECTED.setExecute(p.toFile(), executable));
		}
		hook("post-commit", (cherry ? authorRecord("post-commit") : "")
				+ "test \"$#\" = 0 || exit 23\nexit " + (scenario.equals("post-failure") ? 7 : 0) + "\n");
		RevCommit result = null; Exception failure = null;
		byte[] initialMessageFile = Files.readAllBytes(path("COMMIT_EDITMSG"));
		try { result = commitMessage(input, cleanup, bypass, allowEmpty); }
		catch (Exception caught) { failure = caught; }
		assertEquals("Unexpected outcome in " + scenario + ": " + failure, reject, failure != null);
		if (invalidPrefix) {
			assertFalse(Files.exists(path("phases")));
			assertFalse(Files.exists(path("prepare.input")));
			assertFalse(Files.exists(path("message.input")));
			assertArrayEquals(initialMessageFile, Files.readAllBytes(path("COMMIT_EDITMSG")));
			assertEquals(initial, db.resolve("HEAD"));
			try (Git git = Git.wrap(db)) { assertEquals(java.util.Set.of("file"), git.status().call().getChanged()); }
			return;
		}
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
		if (cherry) {
			StringBuilder zone = new StringBuilder();
			PersonIdent.appendTimezone(zone, cherryAuthor.getTimeZoneOffset());
			String identity = "|" + cherryAuthor.getName() + "|" + cherryAuthor.getEmailAddress()
					+ "|@" + cherryAuthor.getWhenAsInstant().getEpochSecond() + " " + zone + "\n";
			StringBuilder records = new StringBuilder();
			for (String phase : phases.split("\n")) records.append(phase).append(identity);
			assertEquals(records.toString(), Files.readString(path("cherry-author")));
			assertEquals(reject, Files.exists(path("CHERRY_PICK_HEAD")));
			assertEquals(reject && hadMerge, Files.exists(path("MERGE_MSG")));
			if (!reject) {
				assertNotNull(result);
				assertEquals(cherryAuthor.toExternalString(), result.getAuthorIdent().toExternalString());
			}
		}
		if (reject) {
			assertEquals(initial, db.resolve("HEAD"));
			try (Git git = Git.wrap(db)) { assertEquals(java.util.Set.of("file"), git.status().call().getChanged()); }
		} else {
			assertNotNull(result); assertEquals(result, db.resolve("HEAD"));
			if (amending) {
				assertArrayEquals(initial.getParents(), result.getParents());
				assertEquals(initial.getAuthorIdent().toExternalString(), result.getAuthorIdent().toExternalString());
			} else assertEquals(initial, result.getParent(0));
			assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), result.getFullMessage().getBytes(StandardCharsets.UTF_8));
			try (TreeWalk tree = TreeWalk.forPath(db, "file", result.getTree())) {
				assertNotNull(tree); assertArrayEquals(committedContents.getBytes(StandardCharsets.UTF_8), db.open(tree.getObjectId(0)).getBytes());
			}
		}
		if (reverting) {
			assertEquals(reject, Files.exists(path("REVERT_HEAD")));
			assertEquals(reject && hadMerge, Files.exists(path("MERGE_MSG")));
		}
		if (cleanupCase) {
			assertEquals(cleanupDirectory, Files.exists(path(cleanupPath), java.nio.file.LinkOption.NOFOLLOW_LINKS));
			if (cleanupDirectory) {
				assertTrue(Files.isDirectory(path(cleanupPath)));
				if (!scenario.contains("empty-directory")) assertEquals("keep", Files.readString(path(cleanupPath + "/kept")));
			}
			if (scenario.equals("cleanup-squash-symlink")) assertEquals("target", Files.readString(path("cleanup-target/kept")));
			assertFalse(Files.exists(path(cleanupPath.equals("MERGE_MSG") ? "SQUASH_MSG" : "MERGE_MSG")));
		} else {
			assertEquals(reject && hadSquash, Files.exists(path("SQUASH_MSG")));
			assertEquals(reject && hadMerge, Files.exists(path("MERGE_MSG")));
		}
		if (reject && hadSquash) assertArrayEquals(squashBefore, Files.readAllBytes(path("SQUASH_MSG")));
		if (reject && hadMerge) assertArrayEquals(mergeBefore, Files.readAllBytes(path("MERGE_MSG")));
	}

	private String authorRecord(String phase) {
		return "printf '%s|%s|%s|%s\\n' '" + phase
				+ "' \"$GIT_AUTHOR_NAME\" \"$GIT_AUTHOR_EMAIL\" \"$GIT_AUTHOR_DATE\" >>\"$GIT_DIR/cherry-author\"\n";
	}
}
