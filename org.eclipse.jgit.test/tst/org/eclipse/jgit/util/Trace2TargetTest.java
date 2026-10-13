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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import org.eclipse.jgit.internal.trace2.Trace2Destination;
import org.eclipse.jgit.internal.trace2.Trace2Target;
import org.eclipse.jgit.internal.trace2.Trace2Target.Format;
import org.junit.Test;

public class Trace2TargetTest {
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
