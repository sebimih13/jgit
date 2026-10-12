/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.internal.trace2;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Destination ownership corresponding to C Git's tr2_dst. */
public final class Trace2Destination implements AutoCloseable {
	private final Writer writer;

	private final boolean owned;

	private final boolean debug;

	private volatile boolean enabled = true;

	private boolean discardSentinel;

	/**
	 * @param writer
	 *            output writer
	 * @param owned
	 *            whether close owns the writer
	 * @param debug
	 *            whether to diagnose failures
	 */
	public Trace2Destination(Writer writer, boolean owned, boolean debug) {
		this.writer = writer;
		this.owned = owned;
		this.debug = debug;
	}

	/**
	 * Open an append file, directory, stderr, descriptor or stream socket.
	 *
	 * @param target
	 *            destination setting
	 * @param sid
	 *            session ID, used for directory targets
	 * @param debug
	 *            whether to diagnose failures
	 * @return destination or null when disabled/unavailable
	 */
	public static Trace2Destination open(String target, String sid,
			boolean debug) {
		return open(target, sid, debug, 0);
	}

	/**
	 * Open with C Git's optional directory limit/sentinel policy.
	 *
	 * @param target
	 *            destination
	 * @param sid
	 *            session ID
	 * @param debug
	 *            diagnostics enabled
	 * @param maxFiles
	 *            directory entry limit (0 disables)
	 * @return destination, or null if disabled/unavailable
	 */
	public static Trace2Destination open(String target, String sid,
			boolean debug, int maxFiles) {
		if (target == null || target.isEmpty() || "0".equals(target) //$NON-NLS-1$
				|| "false".equalsIgnoreCase(target)) { //$NON-NLS-1$
			return null;
		}
		try {
			if ("1".equals(target) || "2".equals(target) //$NON-NLS-1$ //$NON-NLS-2$
					|| "true".equalsIgnoreCase(target) //$NON-NLS-1$
					|| "yes".equalsIgnoreCase(target) //$NON-NLS-1$
					|| "on".equalsIgnoreCase(target)) { //$NON-NLS-1$
				return new Trace2Destination(new OutputStreamWriter(System.err,
						StandardCharsets.UTF_8), false, debug);
			}
			if (target.startsWith("af_unix:")) { //$NON-NLS-1$
				String address = target.substring(8);
				if (address.startsWith("stream:")) { //$NON-NLS-1$
					address = address.substring(7);
				}
				SocketChannel channel = SocketChannel
						.open(StandardProtocolFamily.UNIX);
				try {
					channel.connect(UnixDomainSocketAddress.of(address));
					return new Trace2Destination(
							Channels.newWriter(channel, StandardCharsets.UTF_8),
							true, debug);
				} catch (IOException | RuntimeException failure) {
					channel.close();
					throw failure;
				}
			}
			Path path;
			if (target.matches("[3-9]")) { //$NON-NLS-1$
				// Linux descriptor duplication; other platforms fail closed.
				path = Path.of("/proc/self/fd", target); //$NON-NLS-1$
			} else {
				path = Path.of(target);
				if (!path.isAbsolute()) {
					throw new IOException("Trace2 target must be absolute"); //$NON-NLS-1$
				}
			}
			if (Files.isDirectory(path)) {
				if (maxFiles > 0) {
					Path sentinel = path.resolve("git-trace2-discard"); //$NON-NLS-1$
					if (Files.exists(sentinel)) {
						return null;
					}
					long entries;
					try (var files = Files.list(path)) {
						entries = files.limit(maxFiles).count() + 2;
					}
					// C Git readdir counts '.' and '..' in this limit.
					if (entries >= maxFiles) {
						Trace2Destination destination = new Trace2Destination(
								Files.newBufferedWriter(sentinel,
										StandardCharsets.UTF_8,
										StandardOpenOption.CREATE_NEW,
										StandardOpenOption.WRITE),
								true, debug);
						destination.discardSentinel = true;
						return destination;
					}
				}
				String leaf = sid.substring(sid.lastIndexOf('/') + 1);
				path = Files.createTempFile(path, leaf + '.', ""); //$NON-NLS-1$
			}
			return new Trace2Destination(Files.newBufferedWriter(path,
					StandardCharsets.UTF_8, StandardOpenOption.CREATE,
					StandardOpenOption.APPEND), true, debug);
		} catch (IOException | RuntimeException failure) {
			if (debug) {
				System.err.println("JGit: cannot open Trace2 destination"); //$NON-NLS-1$
			}
			return null;
		}
	}

	/** @return whether this is the first directory discard sentinel */
	public boolean isDiscardSentinel() {
		return discardSentinel;
	}

	/** @return whether output remains usable */
	public boolean isEnabled() {
		return enabled;
	}

	/**
	 * @param record
	 *            one complete record, including its newline
	 */
	public synchronized void write(String record) {
		if (!enabled) {
			return;
		}
		try {
			writer.write(record);
			writer.flush();
		} catch (IOException | RuntimeException failure) {
			enabled = false;
			if (debug) {
				System.err.println(
						"JGit: Trace2 destination disabled after write failure"); //$NON-NLS-1$
			}
		}
	}

	@Override
	public synchronized void close() {
		enabled = false;
		try {
			if (owned) {
				writer.close();
			} else {
				writer.flush();
			}
		} catch (IOException | RuntimeException failure) {
			if (debug) {
				System.err.println("JGit: cannot close Trace2 destination"); //$NON-NLS-1$
			}
		}
	}
}
