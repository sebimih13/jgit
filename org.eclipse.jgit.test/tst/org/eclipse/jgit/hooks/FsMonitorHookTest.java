/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.hooks;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/** The configured FSMonitor protocol bypasses ordinary event hook discovery. */
public class FsMonitorHookTest extends RepositoryTestCase {
	@Before
	public void requireExecutableScripts() {
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
	}

	private Path executable(String body) throws Exception {
		Path path = db.getDirectory().toPath().resolve("monitor with spaces");
		Files.writeString(path, "#!/bin/sh\n" + body);
		assertTrue(FS.DETECTED.setExecute(path.toFile(), true));
		return path;
	}

	@Test
	public void configuredExecutableReceivesLiteralTokenAndReturnsBinaryReply()
			throws Exception {
		Path script = executable("test \"$1\" = 2 || exit 7\n"
				+ "printf '%s\\0path with space\\0' \"$2\"\n");
		String token = "$literal; `opaque`";
		FsMonitorHook hook = Hooks.fsMonitor(db, script.toFile(), token);
		assertTrue(hook.isNativeHookPresent());
		assertFalse(Files.exists(db.getDirectory().toPath()
				.resolve("hooks/fsmonitor-watchman")));
		assertArrayEquals((token + "\0path with space\0").getBytes(UTF_8),
				hook.call());
	}

	/** Child process owns and closes its tracing session. */
	public static class TraceProbe {
		public static void main(String[] args) throws Exception {
			try (org.eclipse.jgit.lib.Repository repo = new org.eclipse.jgit.storage.file.FileRepositoryBuilder()
					.setGitDir(new java.io.File(args[0])).build()) {
				repo.getConfig().setString("core", null, "fsmonitor", args[1]);
				repo.getConfig().setBoolean("jgit", null, "fsmonitorCache", true);
				org.eclipse.jgit.util.FsMonitor.State state = new org.eclipse.jgit.util.FsMonitor.State();
				org.eclipse.jgit.util.FsMonitor.Probe first = state.query(repo);
				if (first == null)
					throw new AssertionError("v1 fallback must succeed");
				state.remember(first);
				if (!state.query(repo).isUnchanged())
					throw new AssertionError("empty v1 reply must validate reuse");
			} finally {
				org.eclipse.jgit.util.Trace2.getInstance().close();
			}
		}
	}

	@Test
	public void fsmonitorNegotiationBalancesChildrenAndHidesClock()
			throws Exception {
		Path script = executable("[ \"$1\" = 1 ] || exit 1\n");
		Path events = db.getDirectory().toPath().resolve("trace.jsonl");
		Path output = db.getDirectory().toPath().resolve("trace-probe.log");
		String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		ProcessBuilder builder = new ProcessBuilder(javaExecutable, "-cp",
				System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
				TraceProbe.class.getName(), db.getDirectory().getPath(), script.toString());
		builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
		builder.environment().put("GIT_TRACE2_EVENT", events.toString());
		builder.redirectErrorStream(true).redirectOutput(output.toFile());
		Process child = builder.start();
		try {
			assertTrue(child.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
			assertEquals(Files.readString(output), 0, child.exitValue());
		} finally {
			child.destroyForcibly();
		}
		String text = Files.readString(events);
		assertEquals(4, text.split("\"event\":\"child_start\"", -1).length - 1);
		assertEquals(4, text.split("\"event\":\"child_exit\"", -1).length - 1);
		assertEquals(2, text.split("\"event\":\"region_enter\"", -1).length - 1);
		assertEquals(2, text.split("\"event\":\"region_leave\"", -1).length - 1);
		for (String line : text.split("\n")) {
			if (line.contains("\"event\":\"child_start\"")) {
				assertTrue(line, line.contains("\"2\",\"<redacted>\"")
						|| line.contains("\"1\",\"<redacted>\""));
				assertFalse(line.contains("c:0:0:0:0"));
			}
		}
	}

	@Test
	public void failedProviderReportsIoFailure() throws Exception {
		Path script = executable("exit 7\n");
		FsMonitorHook hook = Hooks.fsMonitor(db, script.toFile(), "clock");
		assertThrows(IOException.class, hook::call);
	}
}
