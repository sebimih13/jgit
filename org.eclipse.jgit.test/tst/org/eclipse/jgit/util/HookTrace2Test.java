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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.io.StringWriter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.junit.JGitTestUtil;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

public class HookTrace2Test extends RepositoryTestCase {
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
	public void restoreTrace() throws Exception {
		if (trace != null) {
			trace.close();
			initialized.set(null, previous);
		}
	}

	private File hook(String name, String body) throws Exception {
		File file = new File(db.getDirectory(), "hooks/" + name);
		JGitTestUtil.write(file, "#!/bin/sh\n" + body + "\n");
		assertTrue(FS.DETECTED.setExecute(file, true));
		return file;
	}

	private int count(String event) {
		return events.toString().split("\"event\":\"" + event + "\"", -1).length
				- 1;
	}

	private String event(String name) {
		for (String line : events.toString().split("\n")) {
			if (line.contains("\"event\":\"" + name + "\"")) {
				return line;
			}
		}
		fail("Missing event " + name + ": " + events);
		return null;
	}

	private void assertHookPair(String name, int code) {
		assertEquals(1, count("child_start"));
		assertEquals(1, count("child_exit"));
		assertEquals(1, count("region_enter"));
		assertEquals(1, count("region_leave"));
		assertTrue(event("child_start").contains("\"child_class\":\"hook\""));
		assertTrue(event("child_start")
				.contains("\"hook_name\":\"" + name + "\""));
		assertTrue(event("child_start").contains("\"use_shell\":true"));
		assertEquals(
				event("child_start").split("\"child_id\":")[1].split(",")[0],
				event("child_exit").split("\"child_id\":")[1].split(",")[0]);
		assertTrue(event("child_exit").contains("\"code\":" + code));
		assertTrue(event("region_enter").contains("\"category\":\"hook\""));
		assertTrue(
				event("region_leave").contains("\"label\":\"" + name + "\""));
		String text = events.toString();
		assertTrue(text.indexOf("\"event\":\"region_enter\"") < text
				.indexOf("\"event\":\"child_start\""));
		assertTrue(text.indexOf("\"event\":\"child_exit\"") < text
				.indexOf("\"event\":\"region_leave\""));
	}

	@Test
	public void successfulHookPreservesStreamsAndParentSession()
			throws Exception {
		hook("pre-push",
				"printf '%s|%s|' \"$1\" \"$2\"; cat; printf '%s' \"$GIT_TRACE2_PARENT_SID\"; echo error >&2");
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		assertEquals(0,
				FS.DETECTED.runHookIfPresent(db, "pre-push",
						new String[] { "one", "two" }, out, err, "input|")
						.getExitCode());
		assertTrue(out.toString("UTF-8").startsWith("one|two|input|"));
		assertTrue(out.toString("UTF-8").contains("-P"));
		assertEquals("error\n", err.toString("UTF-8"));
		assertHookPair("pre-push", 0);
	}

	@Test
	public void nonzeroExitIsRecordedOnce() throws Exception {
		hook("pre-commit", "exit 7");
		assertEquals(7,
				FS.DETECTED.runHookIfPresent(db, "pre-commit", new String[0])
						.getExitCode());
		assertHookPair("pre-commit", 7);
	}

	@Test
	public void absentAndNonExecutableHooksHaveNoEvents() throws Exception {
		assertEquals(ProcessResult.Status.NOT_PRESENT, FS.DETECTED
				.runHookIfPresent(db, "pre-commit", new String[0]).getStatus());
		FS.DETECTED.setExecute(hook("pre-commit", "exit 0"), false);
		assertEquals(ProcessResult.Status.NOT_PRESENT, FS.DETECTED
				.runHookIfPresent(db, "pre-commit", new String[0]).getStatus());
		assertEquals(0, count("child_start"));
		assertEquals(0, count("region_enter"));
	}

	@Test
	public void failedLaunchBalancesChildAndRegion() throws Exception {
		hook("pre-commit", "exit 0");
		FS fs = new FS_POSIX() {
			@Override
			public ProcessBuilder runInShell(String cmd, String[] args) {
				return new ProcessBuilder(
						new File(db.getDirectory(), "missing-shell")
								.getAbsolutePath());
			}
		};
		try {
			fs.runHookIfPresent(db, "pre-commit", new String[0]);
			fail("Expected launch failure");
		} catch (JGitInternalException expected) {
			assertHookPair("pre-commit", -1);
			assertTrue(event("child_exit").contains("\"pid\":-1"));
		}
	}

	@Test
	public void interruptionUnwindsHookRegion() throws Exception {
		hook("pre-commit", "sleep 0.1");
		try {
			Thread.currentThread().interrupt();
			FS.DETECTED.runHookIfPresent(db, "pre-commit", new String[0]);
			fail("Expected interruption");
		} catch (JGitInternalException expected) {
			assertHookPair("pre-commit", 0);
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	public void disabledTraceDoesNotPropagateSessionOrCreateHookScope()
			throws Exception {
		trace.close();
		events.getBuffer().setLength(0);
		trace = new Trace2(null, false, false, 10, false, null);
		initialized.set(null, trace);
		hook("pre-commit", "exit 0");
		assertEquals(0,
				FS.DETECTED.runHookIfPresent(db, "pre-commit", new String[0])
						.getExitCode());
		assertEquals("", events.toString());
	}

	@Test
	public void commitHooksAndNoVerifyFollowActualExecution() throws Exception {
		hook("pre-commit", "exit 0");
		hook("commit-msg", "exit 0");
		hook("post-commit", "exit 0");
		try (Git git = Git.wrap(db)) {
			git.commit().setAllowEmpty(true).setMessage("first").call();
			assertEquals(3, count("child_start"));
			assertTrue(
					events.toString().contains("\"hook_name\":\"commit-msg\""));
			events.getBuffer().setLength(0);
			git.commit().setAllowEmpty(true).setMessage("second")
					.setNoVerify(true).call();
			assertHookPair("post-commit", 0);
		}
	}

	@Test
	public void customHooksPathRetainsLogicalHookName() throws Exception {
		File file = new File(db.getWorkTree(), "custom hooks/pre-commit");
		JGitTestUtil.write(file, "#!/bin/sh\nexit 0\n");
		FS.DETECTED.setExecute(file, true);
		db.getConfig().setString("core", null, "hooksPath", "custom hooks");
		assertEquals(0,
				FS.DETECTED.runHookIfPresent(db, "pre-commit", new String[0])
						.getExitCode());
		assertHookPair("pre-commit", 0);
	}

	@Test
	public void hookIdentityDoesNotLeakToOtherBuildersOrFollowingProcesses() {
		ProcessBuilder hook = new ProcessBuilder("hook");
		ProcessBuilder other = new ProcessBuilder("other");
		try (Trace2.Scope scope = trace.hook(null, "pre-commit", hook)) {
			trace.childStart(other).exit(1, 0);
			trace.childStart(hook).exit(2, 0);
		}
		trace.childStart(hook).exit(3, 0);
		assertEquals(3, count("child_start"));
		assertEquals(1,
				events.toString().split("\"hook_name\":", -1).length - 1);
	}

	@Test
	public void concurrentAndNestedHookScopesKeepTheirIdentity()
			throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<?>[] tasks = new Future<?>[2];
			for (int i = 0; i < tasks.length; i++) {
				final String name = "hook-" + i;
				tasks[i] = pool.submit(() -> {
					ProcessBuilder outer = new ProcessBuilder("outer");
					ProcessBuilder inner = new ProcessBuilder("inner");
					try (Trace2.Scope first = trace.hook(null, name, outer);
							Trace2.Scope second = trace.hook(null,
									name + "-nested", inner)) {
						trace.childStart(inner).exit(1, 0);
						trace.childStart(outer).exit(2, 0);
					}
					trace.childStart(outer).exit(3, 0);
				});
			}
			for (Future<?> task : tasks) {
				task.get();
			}
		} finally {
			pool.shutdownNow();
		}
		assertEquals(6, count("child_start"));
		assertEquals(6, count("child_exit"));
		assertEquals(4, count("region_leave"));
		assertEquals(4,
				events.toString().split("\"hook_name\":", -1).length - 1);
	}
}
