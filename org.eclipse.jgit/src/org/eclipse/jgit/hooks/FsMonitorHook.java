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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.Trace2;

/**
 * Query a configured FSMonitor executable using the binary hook v2 protocol.
 * <p>
 * Unlike event hooks, this executable is selected by core.fsmonitor. Its
 * response is limited to 16 MiB and execution to five seconds. The caller
 * validates the reply before reusing cached status data.
 * </p>
 *
 * @since 7.8
 */
public class FsMonitorHook extends GitHook<byte[]> {
	/** Conventional name of the Watchman sample hook. */
	public static final String NAME = "fsmonitor-watchman"; //$NON-NLS-1$

	private static final int MAX_RESPONSE = 16 * 1024 * 1024;

	private static final int TIMEOUT_SECONDS = 5;

	private final File hook;

	private final String token;

	/**
	 * Create a binary FSMonitor query.
	 *
	 * @param repo
	 *            repository
	 * @param executable
	 *            configured executable
	 * @param token
	 *            previous provider token
	 */
	protected FsMonitorHook(Repository repo, File executable, String token) {
		super(repo, null, null);
		this.hook = executable;
		this.token = token;
	}

	@Override
	public String getHookName() {
		return NAME;
	}

	@Override
	protected String[] getParameters() {
		return new String[] { "2", token }; //$NON-NLS-1$
	}

	@Override
	public boolean isNativeHookPresent() {
		FS fs = getRepository().getFS();
		return hook.isFile()
				&& (fs == null ? FS.DETECTED : fs).canExecute(hook);
	}

	@Override
	public byte[] call() throws IOException {
		try {
			return runQuery();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IOException("fsmonitor interrupted", interrupted); //$NON-NLS-1$
		}
	}

	private byte[] runQuery() throws IOException, InterruptedException {
		Repository repo = getRepository();
		Path output = Files.createTempFile("jgit-fsmonitor-", ".response"); //$NON-NLS-1$ //$NON-NLS-2$
		Process process = null;
		Trace2.Child child = null;
		try {
			String[] parameters = getParameters();
			ProcessBuilder builder = new ProcessBuilder(hook.getAbsolutePath(),
					parameters[0], parameters[1]);
			builder.directory(repo.getWorkTree());
			builder.redirectError(ProcessBuilder.Redirect.DISCARD);
			builder.redirectOutput(output.toFile());
			Trace2 trace = Trace2.getInstance();
			if (trace.isEnabled()) {
				builder.command().set(2, "<redacted>"); //$NON-NLS-1$
				try {
					child = trace.childStart(builder);
				} finally {
					builder.command().set(2, token);
				}
			}
			process = builder.start();
			long deadline = System.nanoTime()
					+ TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
			while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
				if (Files.size(output) > MAX_RESPONSE
						|| System.nanoTime() >= deadline) {
					throw new IOException("fsmonitor timeout/output limit"); //$NON-NLS-1$
				}
			}
			if (child != null) {
				child.exit(process.pid(), process.exitValue());
			}
			if (process.exitValue() != 0) {
				throw new IOException("fsmonitor failed"); //$NON-NLS-1$
			}
			try (InputStream in = Files.newInputStream(output)) {
				byte[] bytes = in.readNBytes(MAX_RESPONSE + 1);
				if (bytes.length > MAX_RESPONSE) {
					throw new IOException("fsmonitor output limit"); //$NON-NLS-1$
				}
				return bytes;
			}
		} finally {
			try {
				if (process != null && process.isAlive()) {
					process.descendants()
							.forEach(ProcessHandle::destroyForcibly);
					process.destroyForcibly();
					if (process.waitFor(1, TimeUnit.SECONDS) && child != null) {
						child.exit(process.pid(), process.exitValue());
					}
				} else if (process == null && child != null) {
					child.exit(-1, -1);
				}
			} finally {
				Files.deleteIfExists(output);
			}
		}
	}

}
