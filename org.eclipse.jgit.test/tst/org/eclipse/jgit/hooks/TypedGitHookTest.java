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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.eclipse.jgit.api.errors.AbortedByHookException;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/** Direct contracts for typed receive and index hooks. */
@RunWith(Parameterized.class)
public class TypedGitHookTest extends RepositoryTestCase {
	private static final String OLD = "1111111111111111111111111111111111111111";

	@Parameters(name = "{0}")
	public static Collection<Object[]> hooks() {
		return Arrays.asList(new Object[][] {
				{ "pre-auto-gc", "0\n", "", true },
				{ "pre-receive", "0\n", OLD + " " + ObjectId.zeroId().name() + " refs/heads/topic\n", true },
				{ "update", "3\nrefs/heads/topic\n" + OLD + "\n" + ObjectId.zeroId().name() + "\n", "", true },
				{ "post-receive", "0\n", OLD + " " + ObjectId.zeroId().name() + " refs/heads/topic\n", false },
				{ "post-update", "1\nrefs/heads/topic\n", "", false },
				{ "post-index-change", "2\n1\n0\n", "", false }
		});
	}

	private final String name;

	private final String arguments;

	private final String input;

	private final boolean verification;

	private final ByteArrayOutputStream output = new ByteArrayOutputStream();

	private final ByteArrayOutputStream errors = new ByteArrayOutputStream();

	public TypedGitHookTest(String name, String arguments, String input, boolean verification) {
		this.name = name;
		this.arguments = arguments;
		this.input = input;
		this.verification = verification;
	}

	@Before
	public void requireShellHooks() {
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
	}

	private GitHook<Void> hook() {
		ReceiveCommand command = new ReceiveCommand(ObjectId.fromString(OLD),
				ObjectId.zeroId(), "refs/heads/topic");
		switch (name) {
		case "pre-auto-gc":
			return Hooks.preAutoGc(db, output, errors);
		case "pre-receive":
			return Hooks.preReceive(db, List.of(command), output, errors);
		case "update":
			return Hooks.update(db, command, output, errors);
		case "post-receive":
			return Hooks.postReceive(db, List.of(command), output, errors);
		case "post-update":
			return Hooks.postUpdate(db, List.of(command), output, errors);
		case "post-index-change":
			return Hooks.postIndexChange(db, true, false, output, errors);
		default:
			throw new AssertionError(name);
		}
	}

	private void install(int code) throws Exception {
		Path script = db.getDirectory().toPath().resolve("hooks/" + name);
		Files.createDirectories(script.getParent());
		Files.writeString(script, "#!/bin/sh\n"
				+ "printf '%s\\n' \"$#\" \"$@\" >\"$GIT_DIR/typed.args\"\n"
				+ "cat >\"$GIT_DIR/typed.stdin\"\n"
				+ "printf hook-output\n"
				+ "printf hook-error >&2\n"
				+ "exit " + code + "\n");
		FS.DETECTED.setExecute(script.toFile(), true);
	}

	private void assertInvocation() throws Exception {
		assertEquals(arguments, Files.readString(db.getDirectory().toPath().resolve("typed.args")));
		assertEquals(input, Files.readString(db.getDirectory().toPath().resolve("typed.stdin")));
		assertEquals("hook-output", output.toString(UTF_8));
		assertEquals("hook-error", errors.toString(UTF_8));
	}

	@Test
	public void forwardsArgumentsInputAndSeparateStreams() throws Exception {
		install(0);
		assertEquals(null, hook().call());
		assertInvocation();
	}

	@Test
	public void verificationRejectsAndNotificationReturns() throws Exception {
		install(7);
		try {
			hook().call();
			if (verification) {
				fail("Verification hook must reject");
			}
		} catch (AbortedByHookException e) {
			if (!verification) {
				throw e;
			}
			assertEquals(name, e.getHookName());
			assertEquals(7, e.getReturnCode());
			assertEquals("hook-error", e.getHookStdErr());
		}
		assertInvocation();
	}
}
