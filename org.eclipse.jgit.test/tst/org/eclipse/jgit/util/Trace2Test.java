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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.junit.Test;

public class Trace2Test {
	@Test
	public void hookChildrenInheritOwningCommandHierarchy() throws Exception {
		StringWriter out = new StringWriter();
		try (Trace2 trace = new Trace2(out, false, true, 10, false, null)) {
			java.lang.reflect.Field parent = Trace2.class
					.getDeclaredField("parentCommand");
			parent.setAccessible(true);
			parent.set(trace, "parent");
			try (Trace2.Scope command = trace.command(null, "commit", "test");
					Trace2.Scope nested = trace.command(null, "add", "test")) {
				ProcessBuilder builder = new ProcessBuilder("git", "version");
				trace.childStart(builder).exit(1, 0);
				assertEquals("parent/commit",
						builder.environment().get("GIT_TRACE2_PARENT_NAME"));
			}
		}
		com.google.gson.JsonObject command = out.toString().lines()
				.map(line -> com.google.gson.JsonParser.parseString(line)
						.getAsJsonObject())
				.filter(event -> event.get("event").getAsString()
						.equals("cmd_name"))
				.findFirst().orElseThrow();
		assertEquals("parent/commit", command.get("hierarchy").getAsString());
	}

	@Test
	public void applicationRegionDoesNotHideCommandName() {
		StringWriter out = new StringWriter();
		try (Trace2 trace = new Trace2(out, false, false, 10, false, null);
				Trace2.Scope operation = trace.region(null, "egit",
						"operation")) {
			try (Trace2.Scope outer = trace.command(null, "commit", "egit");
					Trace2.Scope inner = trace.command(null, "add", "jgit")) {
				assertTrue(inner.isEnabled());
			}
		}
		assertEquals(1, count(out.toString(), "cmd_name"));
		assertTrue(out.toString().contains("\"name\":\"commit\""));
		assertEquals(3, count(out.toString(), "region_leave"));
	}

	@Test
	public void inMemoryRepositoryHasNoFilesystemIdentity() {
		StringWriter out = new StringWriter();
		try (InMemoryRepository repo = new InMemoryRepository(
				new DfsRepositoryDescription());
				Trace2 trace = new Trace2(out, false, false, 10, false, null)) {
			try (Trace2.Scope scope = trace.command(repo, "log", "test")) {
				assertTrue(scope.isEnabled());
			}
		}
		assertEquals(1, count(out.toString(), "cmd_name"));
		assertEquals(1, count(out.toString(), "region_leave"));
		assertEquals(0, count(out.toString(), "def_repo"));
	}

	@Test
	public void nestedCommandsShareSession() {
		StringWriter out = new StringWriter();
		try (Trace2 trace = new Trace2(out, false, false, 10, false,
				"parent")) {
			try (Trace2.Scope outer = trace.command(null, "status", "egit")) {
				try (Trace2.Scope inner = trace.command(null, "status",
						"jgit")) {
					inner.data("outcome", "ok");
				}
			}
			assertFalse(out.toString().contains("\"event\":\"atexit\""));
		}
		String events = out.toString();
		assertEquals(1, count(events, "cmd_name"));
		assertEquals(2, count(events, "region_enter"));
		assertEquals(2, count(events, "region_leave"));
		assertEquals(1, count(events, "start"));
		assertEquals(1, count(events, "atexit"));
		assertTrue(events.contains("\"sid\":\"parent/"));
	}

	@Test
	public void exceptionUnwindsAndWorkerReuseStartsFreshCommand()
			throws Exception {
		StringWriter out = new StringWriter();
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try (Trace2 trace = new Trace2(out, false, false, 10, false, null)) {
			Future<?> first = pool.submit(() -> {
				try (Trace2.Scope scope = trace.command(null, "status",
						"first")) {
					throw new IllegalStateException("test");
				} catch (IllegalStateException expected) {
					// The next job must have no inherited command context.
				}
			});
			first.get();
			pool.submit(() -> {
				try (Trace2.Scope scope = trace.command(null, "status",
						"second")) {
					scope.data("outcome", "cancelled");
				}
			}).get();
		} finally {
			pool.shutdownNow();
		}
		assertEquals(2, count(out.toString(), "cmd_name"));
		assertEquals(2, count(out.toString(), "region_leave"));
		assertFalse(out.toString().contains("\"nesting\":2"));
	}

	@Test
	public void concurrentCommandsHaveIndependentNesting() throws Exception {
		StringWriter out = new StringWriter();
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try (Trace2 trace = new Trace2(out, false, false, 10, false, null)) {
			Future<?>[] tasks = new Future<?>[40];
			for (int i = 0; i < tasks.length; i++) {
				tasks[i] = pool.submit(() -> {
					try (Trace2.Scope scope = trace.command(null, "status",
							"worker")) {
						scope.data("outcome", "ok");
					}
				});
			}
			for (Future<?> task : tasks) {
				task.get();
			}
		} finally {
			pool.shutdownNow();
		}
		assertEquals(40, count(out.toString(), "cmd_name"));
		assertEquals(40, count(out.toString(), "region_leave"));
		assertFalse(out.toString().contains("\"nesting\":2"));
		for (String line : out.toString().split("\n")) {
			assertTrue(line.startsWith("{\"event\":"));
			assertTrue(line.endsWith("}"));
		}
	}

	@Test
	public void filtersKeepBalancedRegions() {
		StringWriter out = new StringWriter();
		try (Trace2 trace = new Trace2(out, false, true, 1, false, null)) {
			try (Trace2.Scope outer = trace.command(null, "status", "test")) {
				try (Trace2.Scope inner = trace.region(null, "index", "read")) {
					inner.data("hidden", "yes");
				}
			}
		}
		assertEquals(1, count(out.toString(), "region_enter"));
		assertEquals(1, count(out.toString(), "region_leave"));
		assertFalse(out.toString().contains("hidden"));
		assertEquals(2, out.toString().split("\"time\":", -1).length - 1);
	}

	@Test
	public void disabledScopeIsSharedAndDoesNotEvaluateData() {
		try (Trace2 trace = new Trace2(null, false, false, 2, false, null)) {
			Trace2.Scope a = trace.command(null, null, null);
			assertSame(a, trace.region(null, null, null));
			a.data(null, null);
			a.close();
			a.close();
		}
	}

	@Test
	public void writeFlushAndCloseFailuresDoNotEscape() {
		for (int phase = 0; phase < 3; phase++) {
			final int failure = phase;
			Writer out = new Writer() {
				@Override
				public void write(char[] text, int off, int len)
						throws IOException {
					if (failure == 0) {
						throw new IOException("write");
					}
				}

				@Override
				public void flush() throws IOException {
					if (failure == 1) {
						throw new IOException("flush");
					}
				}

				@Override
				public void close() throws IOException {
					throw new IOException("close");
				}
			};
			try (Trace2 trace = new Trace2(out, true, false, 2, false, null)) {
				try (Trace2.Scope scope = trace.command(null, "status",
						"test")) {
					scope.data("outcome", "ok");
				}
			}
		}
	}

	@Test
	public void escapesControlsUnicodeAndUnpairedSurrogates() {
		assertEquals("\"a\\\"\\\\\\u000a\\u0000é\\ud800\"",
				Trace2.quote("a\"\\\n\0é\ud800"));
	}

	@Test
	public void closeIsIdempotentAndDoesNotOwnStderr() {
		StringWriter out = new StringWriter() {
			@Override
			public void close() {
				throw new AssertionError("Must not close a borrowed writer");
			}
		};
		Trace2 trace = new Trace2(out, false, false, 2, false, null);
		trace.close();
		trace.close();
		assertEquals(1, count(out.toString(), "atexit"));
	}

	private static int count(String events, String name) {
		return events.split("\"event\":\"" + name + "\"", -1).length - 1;
	}
}
