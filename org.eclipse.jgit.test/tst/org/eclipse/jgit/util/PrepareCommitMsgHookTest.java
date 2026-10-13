/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.PrintStream;
import java.io.StringWriter;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.AbortedByHookException;
import org.eclipse.jgit.hooks.PrepareCommitMsgHook;
import org.eclipse.jgit.junit.JGitTestUtil;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

public class PrepareCommitMsgHookTest extends RepositoryTestCase {
	private StringWriter events;

	private Trace2 trace;

	private Trace2 previous;

	private Field initialized;

	@Before
	public void enableTrace() throws Exception {
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		Trace2.getInstance();
		initialized = Trace2.class.getDeclaredField("initialized");
		initialized.setAccessible(true);
		previous = (Trace2) initialized.get(null);
		events = new StringWriter();
		trace = new Trace2(events, false, true, 10, false, null);
		initialized.set(null, trace);
	}

	@After
	public void restoreTraceAndValidateJSON() throws Exception {
		if (trace == null) {
			return;
		}
		try {
			trace.close();
			Map<Integer, String> children = new HashMap<>();
			List<String> regions = new ArrayList<>();
			for (String line : events.toString().split("\n")) {
				JsonObject event = JsonParser.parseString(line)
						.getAsJsonObject();
				switch (event.get("event").getAsString()) {
				case "child_start":
					assertEquals("hook",
							event.get("child_class").getAsString());
					int id = event.get("child_id").getAsInt();
					assertFalse(children.containsKey(id));
					children.put(id, event.get("hook_name").getAsString());
					assertFalse(regions.isEmpty());
					assertEquals(regions.get(regions.size() - 1),
							children.get(id));
					assertTrue(event.get("use_shell").getAsBoolean());
					assertTrue(event.getAsJsonArray("argv").size() > 0);
					break;
				case "child_exit":
					assertNotNull(
							children.remove(event.get("child_id").getAsInt()));
					assertTrue(event.get("t_rel").getAsDouble() >= 0);
					break;
				case "region_enter":
					assertEquals("hook", event.get("category").getAsString());
					regions.add(event.get("label").getAsString());
					break;
				case "region_leave":
					assertEquals(regions.remove(regions.size() - 1),
							event.get("label").getAsString());
					assertTrue(children.isEmpty());
					break;
				default:
					break;
				}
			}
			assertTrue(children.isEmpty());
			assertTrue(regions.isEmpty());
		} finally {
			initialized.set(null, previous);
		}
	}

	private File hook(String name, String body) throws Exception {
		File file = new File(db.getDirectory(), "hooks/" + name);
		JGitTestUtil.write(file, "#!/bin/sh\necho " + name
				+ " >> \"$GIT_DIR/hooks.log\"\n" + body + "\n");
		assertTrue(FS.DETECTED.setExecute(file, true));
		return file;
	}

	private String log() throws Exception {
		return Files
				.readString(db.getDirectory().toPath().resolve("hooks.log"));
	}

	private RevCommit commit(boolean noVerify) throws Exception {
		return Git.wrap(db).commit().setAllowEmpty(true).setMessage("original")
				.setNoVerify(noVerify).call();
	}

	@Test
	public void rejectedEmptyCommitDoesNotRunMessageHooks() throws Exception {
		commit(false);
		hook("prepare-commit-msg", "exit 7");
		try {
			Git.wrap(db).commit().setAllowEmpty(false).setMessage("empty")
					.call();
			fail("Expected empty commit rejection");
		} catch (org.eclipse.jgit.api.errors.EmptyCommitException expected) {
			assertFalse(new File(db.getDirectory(), "hooks.log").exists());
		}
	}

	@Test
	public void unmergedIndexDoesNotRunMessageHooks() throws Exception {
		org.eclipse.jgit.dircache.DirCache index = db.lockDirCache();
		try {
			var builder = index.builder();
			var entry = new org.eclipse.jgit.dircache.DirCacheEntry("conflict",
					1);
			entry.setFileMode(org.eclipse.jgit.lib.FileMode.REGULAR_FILE);
			entry.setObjectId(org.eclipse.jgit.lib.ObjectId.zeroId());
			builder.add(entry);
			assertTrue(builder.commit());
		} finally {
			index.unlock();
		}
		hook("prepare-commit-msg", "exit 7");
		try {
			commit(false);
			fail("Expected unmerged index rejection");
		} catch (org.eclipse.jgit.api.errors.UnmergedPathsException expected) {
			assertFalse(new File(db.getDirectory(), "hooks.log").exists());
		}
	}

	@Test
	public void preparationCanRunGitIndexCommandsWithoutLockConflict()
			throws Exception {
		writeTrashFile("file", "content");
		Git.wrap(db).add().addFilepattern("file").call();
		hook("prepare-commit-msg", "git update-index --refresh");
		commit(false);
		assertEquals("prepare-commit-msg\n", log());
	}

	@Test
	public void modifiesMessageBeforeVerificationInCorrectOrder()
			throws Exception {
		hook("pre-commit", "exit 0");
		hook("prepare-commit-msg",
				"test \"$#\" = 2 && test \"$2\" = message && test \"$GIT_EDITOR\" = : && test -f \"$GIT_INDEX_FILE\" || exit 9\nprintf 'prepared\\n' > \"$1\"");
		hook("commit-msg",
				"test \"$(cat \"$1\")\" = prepared || exit 8\nprintf 'verified\\n' > \"$1\"");
		hook("post-commit", "exit 0");
		// Create an index so the environment assertion can read it.
		writeTrashFile("file", "content");
		Git.wrap(db).add().addFilepattern("file").call();
		assertEquals("verified\n", commit(false).getFullMessage());
		assertEquals(
				"pre-commit\nprepare-commit-msg\ncommit-msg\npost-commit\n",
				log());
	}

	@Test
	public void noVerifyStillRunsPreparationAndPreservesEdits()
			throws Exception {
		hook("pre-commit", "exit 9");
		hook("prepare-commit-msg", "printf 'prepared\\n' > \"$1\"");
		hook("commit-msg", "exit 9");
		hook("post-commit", "exit 0");
		assertEquals("prepared\n", commit(true).getFullMessage());
		assertEquals("prepare-commit-msg\npost-commit\n", log());
	}

	@Test
	public void rejectionEvenWithNoVerifyLeavesHeadUnchanged()
			throws Exception {
		hook("prepare-commit-msg", "echo rejected >&2; exit 7");
		hook("commit-msg", "exit 0");
		hook("post-commit", "exit 0");
		try {
			commit(true);
			fail("Expected preparation to abort");
		} catch (AbortedByHookException failure) {
			assertEquals(PrepareCommitMsgHook.NAME, failure.getHookName());
			assertEquals(7, failure.getReturnCode());
			assertNull(db.resolve(Constants.HEAD));
			assertEquals("prepare-commit-msg\n", log());
		}
	}

	@Test
	public void amendWithExplicitMessageHasMessageSource() throws Exception {
		commit(false);
		hook("prepare-commit-msg",
				"test \"$#\" = 2 && test \"$2\" = message || exit 9");
		Git.wrap(db).commit().setAmend(true).setMessage("amended").call();
		assertEquals("prepare-commit-msg\n", log());
	}

	@Test
	public void squashMessageSourceIsPassedWhenMessageIsImplicit()
			throws Exception {
		db.writeSquashCommitMsg("squashed\n");
		hook("prepare-commit-msg",
				"test \"$2\" = squash || exit 9\nprintf 'prepared squash\\n' > \"$1\"");
		assertEquals("prepared squash\n", Git.wrap(db).commit()
				.setAllowEmpty(true).call().getFullMessage());
	}

	@Test
	public void mergingMessageSourceIsPassedWhenMessageIsImplicit()
			throws Exception {
		RevCommit head = commit(false);
		db.writeMergeHeads(List.of(head));
		db.writeMergeCommitMsg("merged\n");
		hook("prepare-commit-msg",
				"test \"$2\" = merge || exit 9\nprintf 'prepared merge\\n' > \"$1\"");
		assertEquals("prepared merge\n", Git.wrap(db).commit()
				.setAllowEmpty(true).call().getFullMessage());
	}

	@Test
	public void missingAndNonExecutablePreparationDoNotEmitChildren()
			throws Exception {
		assertEquals("original", commit(true).getFullMessage());
		FS.DETECTED.setExecute(hook("prepare-commit-msg", "exit 7"), false);
		assertEquals("original", commit(true).getFullMessage());
		assertFalse(new File(db.getDirectory(), "hooks.log").exists());
		assertEquals(2, events.toString().split("\n").length);
	}

	@Test
	public void hookSpecificOutputAndErrorStreamsAreSupported()
			throws Exception {
		hook("prepare-commit-msg", "echo output; echo error >&2");
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		Git.wrap(db).commit().setAllowEmpty(true).setMessage("message")
				.setHookOutputStream(PrepareCommitMsgHook.NAME,
						new PrintStream(out))
				.setHookErrorStream(PrepareCommitMsgHook.NAME,
						new PrintStream(err))
				.call();
		assertEquals("output\n", out.toString("UTF-8"));
		assertEquals("error\n", err.toString("UTF-8"));
	}
}
