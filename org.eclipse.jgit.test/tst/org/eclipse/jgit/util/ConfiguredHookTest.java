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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.io.ByteArrayOutputStream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.AbortedByHookException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.junit.JGitTestUtil;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

public class ConfiguredHookTest extends RepositoryTestCase {
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
	public void closeAndCheckChildPairs() throws Exception {
		if (trace == null)
			return;
		try {
			trace.close();
			Map<Integer, JsonObject> children = new HashMap<>();
			int depth = 0;
			for (JsonObject event : records()) {
				switch (event.get("event").getAsString()) {
				case "region_enter":
					assertEquals("hook", event.get("category").getAsString());
					depth++;
					break;
				case "child_start":
					assertEquals(1, depth);
					assertEquals("hook",
							event.get("child_class").getAsString());
					assertTrue(event.has("hook_name"));
					assertNull(children.put(event.get("child_id").getAsInt(),
							event));
					break;
				case "child_exit":
					assertNotNull(
							children.remove(event.get("child_id").getAsInt()));
					assertTrue(event.get("t_rel").getAsDouble() >= 0);
					break;
				case "region_leave":
					assertTrue(children.isEmpty());
					depth--;
					break;
				default:
					break;
				}
			}
			assertEquals(0, depth);
			assertTrue(children.isEmpty());
		} finally {
			initialized.set(null, previous);
		}
	}

	private List<JsonObject> records() {
		return events.toString().lines()
				.map(line -> JsonParser.parseString(line).getAsJsonObject())
				.toList();
	}

	private void named(String name, String event, String body) {
		db.getConfig().setString("hook", name, "command", body);
		db.getConfig().setStringList("hook", name, "event", List.of(event));
	}

	private String append(String text) {
		return "printf '" + text + "\\n' >> .git/log";
	}

	private String log() throws Exception {
		File file = new File(db.getDirectory(), "log");
		return file.exists() ? Files.readString(file.toPath()) : "";
	}

	private void script(String name, String body) throws Exception {
		File file = new File(db.getDirectory(), "hooks/" + name);
		JGitTestUtil.write(file, "#!/bin/sh\n" + body + "\n");
		assertTrue(FS.DETECTED.setExecute(file, true));
	}

	private RevCommit commit() throws Exception {
		return Git.wrap(db).commit().setAllowEmpty(true).setMessage("message")
				.call();
	}

	private void parallelPair(String event) {
		named("one", event,
				"touch .git/one; n=0; while ! test -f .git/two; do n=$((n+1)); test $n -lt 200 || exit 9; sleep .01; done; echo one");
		named("two", event,
				"touch .git/two; n=0; while ! test -f .git/one; do n=$((n+1)); test $n -lt 200 || exit 9; sleep .01; done; echo two");
		db.getConfig().setBoolean("hook", "one", "parallel", true);
		db.getConfig().setBoolean("hook", "two", "parallel", true);
		db.getConfig().setInt("hook", null, "jobs", 2);
	}

	@Test
	public void parallelHooksOverlapAndMergeOutputWithoutInterleaving()
			throws Exception {
		parallelPair("pre-push");
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		assertEquals(0, FS.DETECTED
				.runHookIfPresent(db, "pre-push", new String[0], out, err, null)
				.getExitCode());
		assertEquals("", out.toString("UTF-8"));
		assertEquals(List.of("one", "two"),
				err.toString("UTF-8").lines().sorted().toList());
		List<JsonObject> starts = records().stream()
				.filter(r -> r.get("event").getAsString().equals("child_start"))
				.toList();
		assertEquals(2, starts.size());
		assertEquals(starts.get(0).get("thread"), starts.get(1).get("thread"));
	}

	@Test
	public void commitHooksStaySequentialEvenWhenParallelIsRequested()
			throws Exception {
		named("one", "pre-commit", append("one"));
		named("two", "pre-commit",
				"test -f .git/log || exit 9; " + append("two"));
		db.getConfig().setBoolean("hook", "one", "parallel", true);
		db.getConfig().setBoolean("hook", "two", "parallel", true);
		db.getConfig().setInt("hook", null, "jobs", 2);
		commit();
		assertEquals("one\ntwo\n", log());
	}

	@Test
	public void eventJobOverrideCanForceSequentialExecution() throws Exception {
		parallelPair("pre-push");
		db.getConfig().setInt("hook", "pre-push", "jobs", 1);
		assertEquals(9, FS.DETECTED
				.runHookIfPresent(db, "pre-push", new String[0]).getExitCode());
	}

	@Test
	public void parallelCancellationClosesEveryStartedChild() throws Exception {
		named("one", "pre-push", "sleep 30");
		named("two", "pre-push", "sleep 30");
		db.getConfig().setBoolean("hook", "one", "parallel", true);
		db.getConfig().setBoolean("hook", "two", "parallel", true);
		db.getConfig().setInt("hook", null, "jobs", 2);
		try {
			Thread.currentThread().interrupt();
			assertThrows(JGitInternalException.class, () -> FS.DETECTED
					.runHookIfPresent(db, "pre-push", new String[0]));
			assertTrue(Thread.currentThread().isInterrupted());
		} finally {
			Thread.interrupted();
		}
		assertEquals(2,
				records().stream().filter(
						r -> r.get("event").getAsString().equals("child_start"))
						.count());
		assertEquals(2,
				records().stream().filter(
						r -> r.get("event").getAsString().equals("child_exit"))
						.count());
	}

	@Test
	public void actualPushRunsConfiguredHookWithBuiltinLfsEnabled()
			throws Exception {
		org.eclipse.jgit.util.LfsFactory previous = org.eclipse.jgit.util.LfsFactory
				.getInstance();
		try (var remote = createBareRepository()) {
			org.eclipse.jgit.lfs.BuiltinLFS.register();
			db.getConfig().setBoolean("filter", "lfs", "useJGitBuiltin", true);
			var head = commit();
			named("check", "pre-push", "cat > .git/push-input; :");
			Git.wrap(db).push()
					.setRemote(remote.getDirectory().getAbsolutePath())
					.setRefSpecs(new org.eclipse.jgit.transport.RefSpec(
							"refs/heads/master:refs/heads/master"))
					.call();
			assertEquals(head, remote.resolve("refs/heads/master"));
			assertTrue(Files
					.readString(
							db.getDirectory().toPath().resolve("push-input"))
					.contains(head.name()));
		} finally {
			org.eclipse.jgit.util.LfsFactory.setInstance(previous);
		}
	}

	@Test
	public void nativePrePushRunsAfterConfiguredHookWithBuiltinLfsEnabled()
			throws Exception {
		org.eclipse.jgit.util.LfsFactory previous = org.eclipse.jgit.util.LfsFactory
				.getInstance();
		try (var remote = createBareRepository()) {
			org.eclipse.jgit.lfs.BuiltinLFS.register();
			db.getConfig().setBoolean("filter", "lfs", "useJGitBuiltin", true);
			var head = commit();
			named("check", "pre-push", append("configured"));
			script("pre-push", append("native"));
			Git.wrap(db).push()
					.setRemote(remote.getDirectory().getAbsolutePath())
					.setRefSpecs(new org.eclipse.jgit.transport.RefSpec(
							"refs/heads/master:refs/heads/master"))
					.call();
			assertEquals(head, remote.resolve("refs/heads/master"));
			assertEquals("configured\nnative\n", log());
		} finally {
			org.eclipse.jgit.util.LfsFactory.setInstance(previous);
		}
	}

	@Test
	public void multilineConfiguredCommandsDoNotLeakTokensToTrace()
			throws Exception {
		named("check", "pre-commit", "true;\nexport TOKEN=fixture_secret; :");
		commit();
		assertTrue(!events.toString().contains("fixture_secret"));
	}

	@Test
	public void configuredHooksRunWithoutTraditionalFileAndEditMessage()
			throws Exception {
		named("prepare", "prepare-commit-msg",
				"test \"$2\" = message || exit 9; printf 'prepared\\n' > \"$1\"");
		named("verify", "commit-msg",
				"test \"$(cat \"$1\")\" = prepared || exit 8; printf 'verified\\n' > \"$1\"");
		assertEquals("verified\n", commit().getFullMessage());
		assertEquals(2,
				records().stream().filter(
						r -> r.get("event").getAsString().equals("child_start"))
						.count());
	}

	@Test
	public void hooksRunInLastRegistrationOrderThenTraditionalHook()
			throws Exception {
		db.getConfig()
				.fromText(db.getConfig().toText() + "[hook \"a\"]\ncommand = "
						+ append("a") + "\nevent = pre-commit\n"
						+ "[hook \"b\"]\ncommand = " + append("b")
						+ "\nevent = pre-commit\n"
						+ "[hook \"a\"]\nevent = pre-commit\n");
		script("pre-commit", append("traditional"));
		commit();
		assertEquals("b\na\ntraditional\n", log());
		assertEquals(1, records().stream().filter(
				r -> r.get("event").getAsString().equals("region_enter"))
				.count());
		assertEquals(3,
				records().stream().filter(
						r -> r.get("event").getAsString().equals("child_start"))
						.count());
	}

	@Test
	public void emptyEventResetsInheritedRegistrations() throws Exception {
		Config base = db.getConfig().getBaseConfig();
		base.setString("hook", "a", "command", append("a"));
		base.setStringList("hook", "a", "event",
				List.of("pre-commit", "post-commit"));
		db.getConfig().setStringList("hook", "a", "event",
				List.of("", "commit-msg"));
		commit();
		assertEquals("a\n", log());
		assertEquals("commit-msg",
				records().stream().filter(r -> r.has("hook_name")).findFirst()
						.orElseThrow().get("hook_name").getAsString());
	}

	@Test
	public void includedConfigurationPreservesOrderingAndReset()
			throws Exception {
		File include = new File(db.getDirectory(), "included");
		JGitTestUtil.write(include, "[hook \"a\"]\ncommand = " + append("a")
				+ "\nevent = pre-commit\n");
		String text = db.getConfig().toText() + "[include]\npath = "
				+ include.getAbsolutePath() + "\n[hook \"b\"]\ncommand = "
				+ append("b") + "\nevent = pre-commit\n"
				+ "[hook \"a\"]\nevent =\nevent = pre-commit\n";
		JGitTestUtil.write(db.getConfig().getFile(), text);
		db.getConfig().load();
		commit();
		assertEquals("b\na\n", log());
	}

	@Test
	public void disabledNamedAndEventHooksDoNotStartChildren()
			throws Exception {
		named("one", "pre-commit", "exit 7");
		db.getConfig().setBoolean("hook", "one", "enabled", false);
		commit();
		db.getConfig().setBoolean("hook", "one", "enabled", true);
		db.getConfig().setBoolean("hook", "pre-commit", "enabled", false);
		commit();
		assertEquals(0,
				records().stream().filter(r -> r.has("child_id")).count());
		assertEquals(2, records().stream().filter(
				r -> r.get("event").getAsString().equals("region_enter"))
				.count());
	}

	@Test
	public void failureStillRunsLaterHooksAndAggregatesExitCodes()
			throws Exception {
		named("one", "pre-commit", append("one") + "; exit 1");
		named("two", "pre-commit", append("two") + "; exit 2");
		try {
			commit();
			fail("Expected rejection");
		} catch (AbortedByHookException failure) {
			assertEquals(3, failure.getReturnCode());
			assertNull(db.resolve("HEAD"));
			assertEquals("one\ntwo\n", log());
		}
	}

	@Test
	public void disabledMissingCommandIsAllowedButEnabledMissingCommandIsFatal()
			throws Exception {
		db.getConfig().setString("hook", "one", "event", "pre-commit");
		db.getConfig().setBoolean("hook", "one", "enabled", false);
		commit();
		db.getConfig().setBoolean("hook", "one", "enabled", true);
		assertThrows(JGitInternalException.class, this::commit);
	}

	@Test
	public void knownEventNameCannotBeFriendlyName() throws Exception {
		named("pre-commit", "post-commit", "exit 0");
		assertThrows(JGitInternalException.class, this::commit);
	}

	@Test
	public void lastCommandValueWinsAndConfigChangesInvalidateCache()
			throws Exception {
		named("one", "pre-commit", "exit 9");
		db.getConfig().setStringList("hook", "one", "command",
				List.of("exit 9", append("one")));
		commit();
		db.getConfig().setString("hook", "one", "command", append("changed"));
		commit();
		assertEquals("one\nchanged\n", log());
	}

	@Test
	public void stdinAndArgumentsAreReplayedToEachHook() throws Exception {
		named("one", "pre-push", "printf '%s|%s|' \"$1\" \"$2\"; cat; :");
		named("two", "pre-push", "printf '%s|%s|' \"$1\" \"$2\"; cat; :");
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		assertEquals(0,
				FS.DETECTED.runHookIfPresent(db, "pre-push",
						new String[] { "a b", "x' y" }, output, output,
						"input\n").getExitCode());
		assertEquals("a b|x' y|input\na b|x' y|input\n",
				output.toString("UTF-8"));
	}

	@Test
	public void numericAndInvalidEnabledValuesFollowGitParsing()
			throws Exception {
		named("one", "pre-commit", append("one"));
		db.getConfig().setStringList("hook", "one", "enabled",
				List.of("0", "invalid"));
		commit();
		assertEquals("", log());
		db.getConfig().setStringList("hook", "one", "enabled",
				List.of("0", "2"));
		commit();
		assertEquals("one\n", log());
	}

	@Test
	public void noVerifySkipsConfiguredVerificationButNotPreparation()
			throws Exception {
		named("reject", "pre-commit", "exit 7");
		named("prepare", "prepare-commit-msg", append("prepare"));
		named("verify", "commit-msg", "exit 7");
		Git.wrap(db).commit().setAllowEmpty(true).setNoVerify(true)
				.setMessage("message").call();
		assertEquals("prepare\n", log());
	}
}
