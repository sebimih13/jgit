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

	@Test
	public void failedProviderReportsIoFailure() throws Exception {
		Path script = executable("exit 7\n");
		FsMonitorHook hook = Hooks.fsMonitor(db, script.toFile(), "clock");
		assertThrows(IOException.class, hook::call);
	}
}
