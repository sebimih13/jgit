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

import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.lib.Repository;
import org.junit.Before;
import org.junit.Test;

/** Operation-specific hook environment isolation and filesystem dispatch. */
public class HookEnvironmentTest extends RepositoryTestCase {

	@Before
	public void requirePosix() {
		org.junit.Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
	}

	private void hook(String name, String body) throws Exception {
		var path = db.getDirectory().toPath().resolve("hooks/" + name);
		Files.createDirectories(path.getParent());
		Files.writeString(path, "#!/bin/sh\n" + body);
		assertTrue(FS.DETECTED.setExecute(path.toFile(), true));
	}

	@Test
	public void rejectionDoesNotLeakEnvironmentToFollowingHook()
			throws Exception {
		hook("pre-commit", "printf '%s' \"${JGIT_TEST_HOOK_SCOPE-unset}\""
				+ " >\"$GIT_DIR/scope\"\nexit 7\n");
		assertEquals(7, FS.DETECTED
				.runHookIfPresent(db, "pre-commit", new String[0], null, null,
						null, Map.of("JGIT_TEST_HOOK_SCOPE", "selected"))
				.getExitCode());
		assertEquals("selected",
				Files.readString(db.getDirectory().toPath().resolve("scope")));
		FS.DETECTED.runHookIfPresent(db, "pre-commit", new String[0]);
		assertEquals(
				System.getenv().getOrDefault("JGIT_TEST_HOOK_SCOPE", "unset"),
				Files.readString(db.getDirectory().toPath().resolve("scope")));
	}

	@Test
	public void launchFailureDoesNotLeakEnvironment() throws Exception {
		hook("pre-commit", "printf '%s' \"${JGIT_TEST_HOOK_SCOPE-unset}\""
				+ " >\"$GIT_DIR/scope\"\n");
		var failLaunch = new java.util.concurrent.atomic.AtomicBoolean(true);
		FS fs = new FS_POSIX() {
			@Override
			public ProcessBuilder runInShell(String command, String[] args) {
				return failLaunch.get()
						? new ProcessBuilder(db.getDirectory().toPath()
								.resolve("missing-shell").toString())
						: super.runInShell(command, args);
			}
		};
		try {
			fs.runHookIfPresent(db, "pre-commit", new String[0], null, null,
					null, Map.of("JGIT_TEST_HOOK_SCOPE", "selected"));
			org.junit.Assert.fail("Expected a process launch failure");
		} catch (org.eclipse.jgit.api.errors.JGitInternalException expected) {
			failLaunch.set(false);
		}
		assertEquals(0, fs.runHookIfPresent(db, "pre-commit", new String[0])
				.getExitCode());
		assertEquals(
				System.getenv().getOrDefault("JGIT_TEST_HOOK_SCOPE", "unset"),
				Files.readString(db.getDirectory().toPath().resolve("scope")));
	}

	@Test
	public void nestedInvocationRestoresOuterEnvironmentAndUsesFilesystemOverride()
			throws Exception {
		hook("outer",
				"printf '%s' \"$JGIT_TEST_HOOK_SCOPE\" >\"$GIT_DIR/outer\"\n");
		hook("inner",
				"printf '%s' \"$JGIT_TEST_HOOK_SCOPE\" >\"$GIT_DIR/inner\"\n");
		FS fs = new FS_POSIX() {
			@Override
			public ProcessResult runHookIfPresent(Repository repository,
					String name, String[] args, OutputStream output,
					OutputStream error, String input) {
				if (name.equals("outer")) {
					assertEquals(0,
							runHookIfPresent(repository, "inner", args, output,
									error, input,
									Map.of("JGIT_TEST_HOOK_SCOPE", "inner"))
									.getExitCode());
				}
				return super.runHookIfPresent(repository, name, args, output,
						error, input);
			}
		};
		assertEquals(0,
				fs.runHookIfPresent(db, "outer", new String[0], null, null,
						null, Map.of("JGIT_TEST_HOOK_SCOPE", "outer"))
						.getExitCode());
		assertEquals("outer",
				Files.readString(db.getDirectory().toPath().resolve("outer")));
		assertEquals("inner",
				Files.readString(db.getDirectory().toPath().resolve("inner")));
	}

	@Test
	public void concurrentInvocationsKeepTheirOwnEnvironment()
			throws Exception {
		hook("pre-commit", "printf '%s' \"$JGIT_TEST_HOOK_SCOPE\""
				+ " >\"$GIT_DIR/$JGIT_TEST_HOOK_SCOPE\"\n");
		CountDownLatch entered = new CountDownLatch(2);
		FS fs = new FS_POSIX() {
			@Override
			public ProcessResult runHookIfPresent(Repository repository,
					String name, String[] args, OutputStream output,
					OutputStream error, String input) {
				entered.countDown();
				try {
					assertTrue(entered.await(10, TimeUnit.SECONDS));
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new AssertionError(interrupted);
				}
				return super.runHookIfPresent(repository, name, args, output,
						error, input);
			}
		};
		var pool = Executors.newFixedThreadPool(2);
		try {
			var first = pool.submit(() -> fs
					.runHookIfPresent(db, "pre-commit", new String[0], null,
							null, null, Map.of("JGIT_TEST_HOOK_SCOPE", "first"))
					.getExitCode());
			var second = pool.submit(() -> fs
					.runHookIfPresent(db, "pre-commit", new String[0], null,
							null, null,
							Map.of("JGIT_TEST_HOOK_SCOPE", "second"))
					.getExitCode());
			assertEquals(Integer.valueOf(0), first.get(15, TimeUnit.SECONDS));
			assertEquals(Integer.valueOf(0), second.get(15, TimeUnit.SECONDS));
		} finally {
			pool.shutdownNow();
		}
		assertEquals("first",
				Files.readString(db.getDirectory().toPath().resolve("first")));
		assertEquals("second",
				Files.readString(db.getDirectory().toPath().resolve("second")));
	}
}
