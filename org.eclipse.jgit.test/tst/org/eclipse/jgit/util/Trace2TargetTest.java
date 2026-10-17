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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.internal.trace2.Trace2Destination;
import org.eclipse.jgit.internal.trace2.Trace2Target;
import org.eclipse.jgit.internal.trace2.Trace2Target.Format;
import org.junit.Test;

public class Trace2TargetTest {
	@Test(timeout = 10000)
	public void socketSessionSurvivesCommandsAndThreadsUntilClose()
			throws Exception {
		assumeFalse(SystemReader.getInstance().isWindows());
		var directory = Files.createTempDirectory("jgit-trace2-");
		var socket = directory.resolve("trace.sock");
		var pool = Executors.newFixedThreadPool(2);
		try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
			server.bind(UnixDomainSocketAddress.of(socket));
			Trace2Destination destination = Trace2Destination.open(
					"af_unix:stream:" + socket, "socket-session", false);
			assertNotNull(destination);
			try (var connection = server.accept();
					Trace2 trace = new Trace2(List.of(new Trace2Target(destination,
							Format.EVENT, false, 10)), "socket-session",
							List.of("jgit", "embedded"))) {
				var received = pool.submit(() -> {
					try (var reader = Channels.newReader(connection,
							StandardCharsets.UTF_8)) {
						StringBuilder records = new StringBuilder();
						char[] buffer = new char[1024];
						for (int n; (n = reader.read(buffer)) != -1;) {
							records.append(buffer, 0, n);
						}
						return records.toString();
					}
				});
				for (int i = 0; i < 2; i++) {
					pool.submit(() -> {
						try (Trace2.Scope scope = trace.command(null, "status", "test")) {
							scope.data("entries", "1");
						}
					}).get(5, TimeUnit.SECONDS);
				}
				assertFalse("Commands must keep the receiver connection open",
						received.isDone());
				trace.close();
				String events = received.get(5, TimeUnit.SECONDS);
				assertEquals(1, count(events, "version"));
				assertEquals(1, count(events, "start"));
				assertEquals(2, count(events, "region_enter"));
				assertEquals(2, count(events, "region_leave"));
				assertEquals(2, count(events, "thread_start"));
				assertEquals(2, count(events, "thread_exit"));
				assertEquals(1, count(events, "atexit"));
				assertTrue(events.stripTrailing().endsWith("}"));
				server.configureBlocking(false);
				assertNull("Session must use one connection", server.accept());
			}
		} finally {
			pool.shutdownNow();
			Files.deleteIfExists(socket);
			Files.deleteIfExists(directory);
		}
	}

	@Test
	public void failedOwnedDestinationClosesImmediately() {
		int[] closes = { 0 };
		Writer broken = new Writer() {
			@Override
			public void write(char[] b, int off, int len) {
				// Simulate a buffered socket failing at flush.
			}

			@Override
			public void flush() throws IOException {
				throw new IOException("disconnected");
			}

			@Override
			public void close() throws IOException {
				closes[0]++;
				throw new IOException("close failed too");
			}
		};
		Trace2Destination destination = new Trace2Destination(broken, true,
				false);
		destination.write("record\n");
		assertFalse(destination.isEnabled());
		assertEquals("A failed socket must deliver EOF without JVM shutdown",
				1, closes[0]);
		destination.write("continuation must be discarded\n");
		destination.close();
		assertEquals("Failed close must not be retried", 1, closes[0]);
	}

	@Test
	public void hookIdentityIsIncludedInEventAndPerfTargets() {
		StringWriter event = new StringWriter();
		StringWriter normal = new StringWriter();
		StringWriter perf = new StringWriter();
		try (Trace2 trace = new Trace2(List.of(target(event, Format.EVENT, 10),
				target(normal, Format.NORMAL, 10), target(perf, Format.PERF, 10)),
				"parent", List.of("jgit", "commit"))) {
			ProcessBuilder builder = new ProcessBuilder("sh", "hook");
			try (Trace2.Scope scope = trace.hook(null, "pre-commit", builder)) {
				trace.childStart(builder).exit(42, 7);
			}
		}
		assertTrue(event.toString().contains("\"hook_name\":\"pre-commit\""));
		assertTrue(perf.toString().contains("[ch0] class:hook hook:pre-commit argv:[sh hook]"));
		assertTrue(perf.toString().contains("label:pre-commit"));
		assertTrue(normal.toString().contains("child_start[0] sh hook"));
		assertFalse(normal.toString().contains("hook:pre-commit"));
	}

	private static Trace2Target target(StringWriter writer, Format format,
			int nesting) {
		return new Trace2Target(new Trace2Destination(writer, false, false),
				format, true, nesting);
	}

	@Test
	public void allTargetsAndIndependentNesting() {
		StringWriter event = new StringWriter();
		StringWriter normal = new StringWriter();
		StringWriter perf = new StringWriter();
		try (Trace2 trace = new Trace2(List.of(target(event, Format.EVENT, 1),
				target(normal, Format.NORMAL, 1), target(perf, Format.PERF, 1)),
				"D20261012T000000.000000Z-P00000001",
				List.of("jgit", "status"))) {
			try (Trace2.Scope command = trace.command(null, "status", "test")) {
				try (Trace2.Scope inner = trace.region(null, "index", "read")) {
					inner.data("entries", "2");
				}
			}
			trace.exit(3);
			trace.exit(0);
		}
		assertTrue(normal.toString().startsWith(
				"version jgit\nstart jgit status\ncmd_name status (status)\n"));
		assertFalse(normal.toString().contains("region_"));
		assertFalse(normal.toString().contains("data "));
		assertTrue(normal.toString().contains("code:3"));
		assertFalse(event.toString().contains("\"nesting\":2"));
		assertTrue(perf.toString().contains("label:read"));
		assertTrue(perf.toString().contains("entries:2"));
		assertEquals(1, count(event.toString(), "exit"));
		assertTrue(event.toString().contains("\"code\":3"));
	}

	@Test
	public void structuredValuesPreserveTypes() {
		StringWriter event = new StringWriter();
		try (Trace2 trace = new Trace2(event, false, true, 2, false, null);
				Trace2.Scope scope = trace.command(null, "status", "test")) {
			scope.dataJson("metrics",
					Map.of("n", Integer.valueOf(4), "ok", Boolean.TRUE));
			assertThrows(IllegalArgumentException.class,
					() -> scope.dataJson("bad", Double.valueOf(Double.NaN)));
		}
		assertTrue(event.toString().contains("\"n\":4"));
		assertTrue(event.toString().contains("\"ok\":true"));
		assertEquals(1, count(event.toString(), "data_json"));
		assertFalse(event.toString().contains("NaN"));
	}

	@Test
	public void failedTargetDoesNotDisableHealthyTarget() {
		Writer broken = new Writer() {
			@Override
			public void write(char[] b, int off, int len) throws IOException {
				throw new IOException();
			}

			@Override
			public void flush() {
				/* no output */ }

			@Override
			public void close() {
				/* no output */ }
		};
		StringWriter good = new StringWriter();
		try (Trace2 trace = new Trace2(
				List.of(new Trace2Target(
						new Trace2Destination(broken, true, false), Format.PERF,
						true, 2), target(good, Format.EVENT, 2)),
				"sid", List.of("jgit"))) {
			try (Trace2.Scope command = trace.command(null, "status", "test")) {
				assertTrue(command.isEnabled());
			}
		}
		assertEquals(1, count(good.toString(), "atexit"));
		assertEquals(1, count(good.toString(), "region_leave"));
	}

	@Test
	public void workerAndChildLifetimeAndPrivacy() throws Exception {
		StringWriter event = new StringWriter();
		var pool = Executors.newSingleThreadExecutor();
		try (Trace2 trace = new Trace2(event, false, true, 2, false, null)) {
			pool.submit(() -> {
				try (Trace2.Worker thread = trace.thread("worker");
						Trace2.Scope region = trace.region(null, "test",
								"work")) {
					Trace2.Child child = trace.childStart("hook", null, false,
							"https://user:secret@example.invalid/repo",
							"--token", "sensitive");
					child.exit(42, 7);
					child.exit(42, 0);
				}
			}).get();
		} finally {
			pool.shutdownNow();
		}
		assertEquals(1, count(event.toString(), "thread_start"));
		assertEquals(1, count(event.toString(), "thread_exit"));
		assertEquals(1, count(event.toString(), "child_start"));
		assertEquals(1, count(event.toString(), "child_exit"));
		assertFalse(event.toString().contains("secret"));
		assertFalse(event.toString().contains("sensitive"));
		assertTrue(event.toString().contains("<redacted>"));
	}

	@Test
	public void eventSchemas() {
		StringWriter event = new StringWriter();
		try (Trace2 trace = new Trace2(event, false, true, 2, false, null)) {
			trace.commandMode("porcelain");
			trace.commandPath("jgit");
			trace.commandAncestry("parent", "grandparent");
			trace.alias("st", "status");
			trace.parameter("global", "core.bare", "false");
			trace.error("test");
			trace.printf("test");
			trace.exec(1, "git", "git", "version");
			trace.execResult(1, 0);
			trace.childStart("daemon", null, false, "git").ready(8, "ready");
		}
		for (String name : List.of("cmd_mode", "cmd_path", "cmd_ancestry",
				"alias", "def_param", "error", "printf", "exec", "exec_result",
				"child_ready")) {
			assertEquals(name, 1, count(event.toString(), name));
		}
	}

	@Test
	public void countersAndTimersAggregateAtShutdown() {
		StringWriter out = new StringWriter();
		try (Trace2 trace = new Trace2(out, false, true, 2, false, null)) {
			trace.counter("index", "entries", 2);
			trace.counter("index", "entries", 3);
			try (Trace2.Interval timer = trace.timer("index", "read")) {
				assertTrue(trace.isEnabled());
			}
			try (Trace2.Worker worker = trace.thread("metrics")) {
				trace.counter("index", "entries", 4);
				try (Trace2.Interval timer = trace.timer("index", "read")) {
					assertTrue(trace.isEnabled());
				}
			}
			assertEquals(1, count(out.toString(), "th_counter"));
			assertEquals(1, count(out.toString(), "th_timer"));
			assertEquals(0, count(out.toString(), "counter"));
		}
		assertTrue(out.toString().contains("\"count\":9"));
		assertEquals(1, count(out.toString(), "timer"));
		assertTrue(out.toString().contains("\"intervals\":2"));
	}

	@Test
	public void childBuilderPropagatesSession() {
		StringWriter out = new StringWriter();
		try (Trace2 trace = new Trace2(out, false, true, 2, false, "parent")) {
			ProcessBuilder builder = new ProcessBuilder("git", "version");
			trace.childStart(builder).exit(42, 0);
			assertTrue(builder.environment().get("GIT_TRACE2_PARENT_SID")
					.startsWith("parent/"));
		}
		assertEquals(1, count(out.toString(), "child_exit"));
	}

	private static int count(String events, String name) {
		return events.split("\"event\":\"" + name + "\"", -1).length - 1;
	}
}
