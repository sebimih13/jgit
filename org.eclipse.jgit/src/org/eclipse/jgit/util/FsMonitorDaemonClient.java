/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.util;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.lib.Repository;

/** Bounded C Git simple-IPC client for an already running local Unix daemon. */
final class FsMonitorDaemonClient {
	private static final int MAX_PACKET = 65520;

	private static final int MAX_RESPONSE = 16 * 1024 * 1024;

	private FsMonitorDaemonClient() {
	}

	static Path endpoint(Repository repository) {
		return repository.getDirectory().toPath()
				.resolve("fsmonitor--daemon.ipc"); //$NON-NLS-1$
	}

	static byte[] query(Repository repository, String token)
			throws IOException {
		return query(endpoint(repository), token, 5000, MAX_RESPONSE);
	}

	static byte[] query(Path socket, String token, long timeoutMillis,
			int maxResponse) throws IOException {
		if (timeoutMillis <= 0 || maxResponse <= 0) {
			throw new IllegalArgumentException("Invalid IPC limits"); //$NON-NLS-1$
		}
		if (token.indexOf('\0') >= 0 || token.equals("quit") //$NON-NLS-1$
				|| token.equals("flush")) { //$NON-NLS-1$
			throw new IOException("Invalid fsmonitor query token"); //$NON-NLS-1$
		}
		byte[] request = token.getBytes(StandardCharsets.UTF_8);
		if (request.length > MAX_RESPONSE) {
			throw new IOException("FSMonitor IPC request limit"); //$NON-NLS-1$
		}
		long deadline = System.nanoTime()
				+ TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
		checkDeadline(deadline);
		try (SocketPath target = socketPath(socket);
				SocketChannel channel = SocketChannel
						.open(StandardProtocolFamily.UNIX);
				Selector selector = Selector.open()) {
			channel.configureBlocking(false);
			try (Trace2.Scope trace = Trace2.getInstance().region(null,
					"ipc-client", "try-connect")) { //$NON-NLS-1$ //$NON-NLS-2$
				if (!channel
						.connect(UnixDomainSocketAddress.of(target.path()))) {
					while (!channel.finishConnect()) {
						await(channel, selector, SelectionKey.OP_CONNECT,
								deadline);
					}
				}
			}
			try (Trace2.Scope trace = Trace2.getInstance().region(null,
					"ipc-client", "send-command")) { //$NON-NLS-1$ //$NON-NLS-2$
				for (int offset = 0; offset < request.length;) {
					int length = Math.min(MAX_PACKET - 4,
							request.length - offset);
					byte[] header = String.format(java.util.Locale.ROOT, "%04x", //$NON-NLS-1$
							Integer.valueOf(length + 4))
							.getBytes(StandardCharsets.US_ASCII);
					write(channel, selector, ByteBuffer.wrap(header), deadline);
					write(channel, selector,
							ByteBuffer.wrap(request, offset, length), deadline);
					offset += length;
				}
				write(channel, selector,
						ByteBuffer.wrap(new byte[] { '0', '0', '0', '0' }),
						deadline);
				ByteArrayOutputStream response = new ByteArrayOutputStream();
				for (;;) {
					ByteBuffer header = ByteBuffer.allocate(4);
					read(channel, selector, header, deadline);
					int length = 0;
					for (byte digit : header.array()) {
						int value = Character.digit(digit & 0xff, 16);
						if (value < 0) {
							throw new IOException(
									"Invalid FSMonitor packet header"); //$NON-NLS-1$
						}
						length = (length << 4) | value;
					}
					if (length == 0) {
						return response.toByteArray();
					}
					if (length < 4 || length > MAX_PACKET) {
						throw new IOException(
								"Invalid FSMonitor packet length"); //$NON-NLS-1$
					}
					length -= 4;
					if (length > maxResponse - response.size()) {
						throw new IOException("FSMonitor IPC response limit"); //$NON-NLS-1$
					}
					ByteBuffer data = ByteBuffer.allocate(length);
					read(channel, selector, data, deadline);
					response.write(data.array());
				}
			}
		} catch (UnsupportedOperationException unsupported) {
			throw new IOException("Unix FSMonitor IPC unavailable", //$NON-NLS-1$
					unsupported);
		}
	}

	private record SocketPath(Path path, Path directory)
			implements AutoCloseable {
		@Override
		public void close() throws IOException {
			if (directory != null) {
				try {
					Files.deleteIfExists(path);
				} finally {
					Files.deleteIfExists(directory);
				}
			}
		}
	}

	private static SocketPath socketPath(Path socket) throws IOException {
		// Native Git temporarily chdirs for long Unix paths. Java cannot change
		// cwd safely in an IDE; a private temporary alias keeps the address
		// short.
		if (socket.toAbsolutePath().toString()
				.getBytes(StandardCharsets.UTF_8).length <= 100) {
			return new SocketPath(socket, null);
		}
		Path directory = Files.createTempDirectory("jgit-fsm-ipc-"); //$NON-NLS-1$
		Path alias = directory.resolve("s"); //$NON-NLS-1$
		try {
			Files.createSymbolicLink(alias, socket.toAbsolutePath());
			return new SocketPath(alias, directory);
		} catch (IOException | RuntimeException failure) {
			Files.deleteIfExists(directory);
			throw failure;
		}
	}

	private static void write(SocketChannel channel, Selector selector,
			ByteBuffer buffer, long deadline) throws IOException {
		while (buffer.hasRemaining()) {
			checkDeadline(deadline);
			if (channel.write(buffer) == 0) {
				await(channel, selector, SelectionKey.OP_WRITE, deadline);
			}
		}
	}

	private static void read(SocketChannel channel, Selector selector,
			ByteBuffer buffer, long deadline) throws IOException {
		while (buffer.hasRemaining()) {
			checkDeadline(deadline);
			int count = channel.read(buffer);
			if (count < 0) {
				throw new EOFException("Truncated FSMonitor IPC response"); //$NON-NLS-1$
			}
			if (count == 0) {
				await(channel, selector, SelectionKey.OP_READ, deadline);
			}
		}
	}

	private static void await(SocketChannel channel, Selector selector,
			int operation, long deadline) throws IOException {
		checkDeadline(deadline);
		channel.register(selector, operation);
		long remaining = deadline - System.nanoTime();
		selector.select(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
		selector.selectedKeys().clear();
		checkDeadline(deadline);
	}

	private static void checkDeadline(long deadline) throws IOException {
		if (Thread.currentThread().isInterrupted()) {
			throw new InterruptedIOException("FSMonitor IPC interrupted"); //$NON-NLS-1$
		}
		if (deadline - System.nanoTime() <= 0) {
			throw new IOException("FSMonitor IPC timeout"); //$NON-NLS-1$
		}
	}
}
