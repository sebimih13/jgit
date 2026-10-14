/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Native t6500 pre-auto-gc rejection and automatic/manual invocation
 * boundaries.
 */
public class PreAutoGcHookTest extends RepositoryTestCase {
	private Git git;

	private Path record;

	@Override
	@Before
	public void setUp() throws Exception {
		super.setUp();
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		git = Git.wrap(db);
		db.getConfig().setInt("gc", null, "auto", 0);
		db.getConfig().setBoolean("gc", null, "autoDetach", false);
		for (String text : new String[] { "263", "410" }) {
			Files.writeString(db.getWorkTree().toPath().resolve(text),
					text + "\n");
			git.add().addFilepattern(text).call();
			git.commit().setMessage(text).call();
		}
		assertTrue(Files
				.isDirectory(db.getDirectory().toPath().resolve("objects/17")));
		db.getConfig().setInt("gc", null, "auto", 3);
		record = db.getDirectory().toPath().resolve("pre-auto-gc.args");
	}

	private void hook(int exit, boolean executable) throws Exception {
		Path hook = db.getDirectory().toPath().resolve("hooks/pre-auto-gc");
		Files.createDirectories(hook.getParent());
		Files.writeString(hook,
				"#!/bin/sh\nprintf '%s\\n' \"$#\" >>\"$GIT_DIR/pre-auto-gc.args\"\nexit "
						+ exit + "\n");
		assertTrue(FS.DETECTED.setExecute(hook.toFile(), executable));
	}

	private long loose() throws Exception {
		if (!Files.exists(db.getDirectory().toPath().resolve("objects/17"))) {
			return 0;
		}
		try (var files = Files
				.list(db.getDirectory().toPath().resolve("objects/17"))) {
			return files.filter(Files::isRegularFile).count();
		}
	}

	@Test
	public void rejectingHookStopsAutoGcWithoutFailureOrPidFile()
			throws Exception {
		hook(1, true);
		long before = loose();
		db.autoGC(null);
		assertEquals("0\n", Files.readString(record));
		assertEquals(before, loose());
		assertFalse(Files.exists(db.getDirectory().toPath().resolve("gc.pid")));
		db.autoGC(null);
		assertEquals("0\n0\n", Files.readString(record));
	}

	@Test
	public void backgroundRejectionRunsBeforeSchedulingGc() throws Exception {
		hook(1, true);
		db.getConfig().setBoolean("gc", null, "autoDetach", true);
		long before = loose();
		db.autoGC(null);
		assertEquals("0\n", Files.readString(record));
		assertEquals(before, loose());
		assertFalse(Files.exists(db.getDirectory().toPath().resolve("gc.log")));
	}

	@Test
	public void acceptingHookAllowsPacking() throws Exception {
		hook(0, true);
		db.autoGC(null);
		assertEquals("0\n", Files.readString(record));
		assertEquals(0, loose());
	}

	@Test
	public void belowThresholdDoesNotInvokeHook() throws Exception {
		hook(1, true);
		db.getConfig().setInt("gc", null, "auto", 6700);
		db.autoGC(null);
		assertFalse(Files.exists(record));
	}

	@Test
	public void disabledAutoDoesNotInvokeHook() throws Exception {
		hook(1, true);
		db.getConfig().setInt("gc", null, "auto", 0);
		db.autoGC(null);
		assertFalse(Files.exists(record));
	}

	@Test
	public void explicitGcDoesNotInvokeAutomaticHook() throws Exception {
		hook(1, true);
		git.gc().call();
		assertFalse(Files.exists(record));
		assertEquals(0, loose());
	}

	@Test
	public void nonExecutableHookDoesNotBlockAutoGc() throws Exception {
		hook(1, false);
		db.autoGC(null);
		assertFalse(Files.exists(record));
		assertEquals(0, loose());
	}

	@Test
	public void absentHookAllowsAutoGc() throws Exception {
		db.autoGC(null);
		assertEquals(0, loose());
	}

	@Test
	public void configuredRejectionStopsAutoGc() throws Exception {
		db.getConfig().setString("hook", "prevent", "event", "pre-auto-gc");
		db.getConfig().setString("hook", "prevent", "command",
				"echo configured >>\"$GIT_DIR/pre-auto-gc.args\"; exit 7");
		long before = loose();
		db.autoGC(null);
		assertEquals("configured\n", Files.readString(record));
		assertEquals(before, loose());
	}

	@Test
	public void configuredAndTraditionalHooksBothRunBeforeRejection()
			throws Exception {
		hook(0, true);
		db.getConfig().setString("hook", "prevent", "event", "pre-auto-gc");
		db.getConfig().setString("hook", "prevent", "command",
				"echo configured >>\"$GIT_DIR/pre-auto-gc.args\"; exit 7");
		long before = loose();
		db.autoGC(null);
		assertEquals("configured\n0\n", Files.readString(record));
		assertEquals(before, loose());
	}
}
