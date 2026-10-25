/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.junit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.SystemReader;

/**
 * Disposable native daemon lifecycle and packet-protocol oracle.
 * 
 * @since 7.8
 */
public final class NativeFsMonitorDaemonSupport implements AutoCloseable {
	private final Repository repository;

	private NativeFsMonitorDaemonSupport(Repository repository) {
		this.repository = repository;
	}

	/**
	 * Start the pinned native daemon on a disposable repository.
	 * 
	 * @param repository
	 *            disposable repository
	 * @return daemon owner; close it before deleting the repository
	 * @throws Exception
	 *             on native command failure
	 * @since 7.8
	 */
	public static NativeFsMonitorDaemonSupport start(Repository repository)
			throws Exception {
		assumeFalse(SystemReader.getInstance().isWindows());
		repository.getConfig().setBoolean("core", null, "fsmonitor", true);
		repository.getConfig().save();
		var daemon = new NativeFsMonitorDaemonSupport(repository);
		try {
			daemon.run(false, "fsmonitor--daemon", "start",
					"--start-timeout=5");
			return daemon;
		} catch (Exception | AssertionError failure) {
			daemon.close();
			throw failure;
		}
	}

	/**
	 * Query through C Git's independent fsmonitor-client helper.
	 * 
	 * @param token
	 *            previous daemon token
	 * @return raw token/NUL/path response
	 * @throws Exception
	 *             on command failure
	 * @since 7.8
	 */
	public byte[] query(String token) throws Exception {
		return run(true, "fsmonitor-client", "query", "--token", token);
	}

	/**
	 * Discard native history and advance the daemon instance token.
	 * 
	 * @throws Exception
	 *             on command failure
	 * @since 7.8
	 */
	public void flush() throws Exception {
		run(true, "fsmonitor-client", "flush");
	}

	/**
	 * Stop the owned daemon.
	 * 
	 * @throws Exception
	 *             on command failure
	 * @since 7.8
	 */
	public void stop() throws Exception {
		run(false, "fsmonitor--daemon", "stop");
	}

	private byte[] run(boolean helper, String... arguments) throws Exception {
		return execute(helper, true, arguments);
	}

	private byte[] execute(boolean helper, boolean requireSuccess,
			String... arguments) throws Exception {
		String git = System.getProperty("jgit.test.gitExecutable", "git");
		String executable = helper
				? Path.of(git).toAbsolutePath().getParent()
						.resolve("t/helper/test-tool").toString()
				: git;
		List<String> command = new ArrayList<>(List.of(executable, "-C",
				repository.getWorkTree().getAbsolutePath()));
		command.addAll(List.of(arguments));
		Path output = Files.createTempFile("jgit-daemon-command-", ".out");
		Path error = Files.createTempFile("jgit-daemon-command-", ".err");
		Process process = null;
		try {
			var builder = new ProcessBuilder(command)
					.redirectOutput(output.toFile())
					.redirectError(error.toFile());
			builder.environment().keySet()
					.removeIf(name -> name.startsWith("GIT_"));
			builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
			builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
			process = builder.start();
			assertTrue("Native daemon command timed out",
					process.waitFor(20, TimeUnit.SECONDS));
			if (requireSuccess) {
				assertEquals(Files.readString(error), 0, process.exitValue());
			}
			return Files.readAllBytes(output);
		} finally {
			if (process != null && process.isAlive()) {
				process.destroyForcibly();
				process.waitFor(5, TimeUnit.SECONDS);
			}
			Files.deleteIfExists(output);
			Files.deleteIfExists(error);
		}
	}

	@Override
	public void close() throws Exception {
		execute(false, false, "fsmonitor--daemon", "stop");
	}
}
