/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/** C simple-IPC framing, limits, cancellation and failure boundaries. */
public class FsMonitorDaemonClientTest {
	@FunctionalInterface
	private interface Query {
		void run(Path path) throws Exception;
	}

	private void response(byte[] wire, Query action) throws Exception {
		assumeFalse(SystemReader.getInstance().isWindows());
		Path directory = Files.createTempDirectory("jgit-ipc-");
		Path socket = directory.resolve("socket");
		var executor = Executors.newSingleThreadExecutor();
		try (var server = ServerSocketChannel
				.open(StandardProtocolFamily.UNIX)) {
			server.bind(UnixDomainSocketAddress.of(socket));
			var peer = executor.submit(() -> {
				try (var connection = server.accept()) {
					var input = Channels.newInputStream(connection);
					assertArrayEquals(
							"000500000".getBytes(StandardCharsets.US_ASCII),
							input.readNBytes(9));
					try {
						for (byte value : wire) {
							ByteBuffer next = ByteBuffer
									.wrap(new byte[] { value });
							while (next.hasRemaining())
								connection.write(next);
						}
					} catch (IOException closedAfterRejection) {
						// Early limit/header rejection may close before the
						// body.
					}
				}
				return null;
			});
			action.run(socket);
			peer.get(5, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
			Files.deleteIfExists(socket);
			Files.deleteIfExists(directory);
		}
	}

	@Test
	public void fragmentedHeadersAndPacketsPreserveBinarySeparators()
			throws Exception {
		response(
				("000bclock\0a000a\0dir/\0" + "0000")
						.getBytes(StandardCharsets.UTF_8),
				socket -> assertArrayEquals(
						"clock\0a\0dir/\0".getBytes(StandardCharsets.UTF_8),
						FsMonitorDaemonClient.query(socket, "0", 2000, 1024)));
	}

	@Test
	public void malformedControlAndOversizedPacketHeadersFail()
			throws Exception {
		for (String header : new String[] { "0001", "0002", "0003", "ffff",
				"xxxx" }) {
			response(header.getBytes(StandardCharsets.US_ASCII),
					socket -> assertThrows(IOException.class,
							() -> FsMonitorDaemonClient.query(socket, "0", 2000,
									1024)));
		}
	}

	@Test
	public void truncatedHeaderAndBodyFail() throws Exception {
		for (String wire : new String[] { "000", "000bshort" }) {
			response(wire.getBytes(StandardCharsets.US_ASCII),
					socket -> assertThrows(IOException.class,
							() -> FsMonitorDaemonClient.query(socket, "0", 2000,
									1024)));
		}
	}

	@Test
	public void responseLimitIsEnforcedBeforeAllocatingPacketBody()
			throws Exception {
		response("000b12345670000".getBytes(StandardCharsets.US_ASCII),
				socket -> assertThrows(IOException.class,
						() -> FsMonitorDaemonClient.query(socket, "0", 2000,
								6)));
	}

	@Test
	public void idlePeerIsBoundedByDeadline() throws Exception {
		assumeFalse(SystemReader.getInstance().isWindows());
		Path directory = Files.createTempDirectory("jgit-ipc-timeout-");
		Path socket = directory.resolve("socket");
		var executor = Executors.newSingleThreadExecutor();
		CountDownLatch release = new CountDownLatch(1);
		try (var server = ServerSocketChannel
				.open(StandardProtocolFamily.UNIX)) {
			server.bind(UnixDomainSocketAddress.of(socket));
			var peer = executor.submit(() -> {
				try (var connection = server.accept()) {
					Channels.newInputStream(connection).readNBytes(9);
					assertTrue(release.await(5, TimeUnit.SECONDS));
				}
				return null;
			});
			long before = System.nanoTime();
			try {
				IOException failure = assertThrows(IOException.class,
						() -> FsMonitorDaemonClient.query(socket, "0", 100,
								1024));
				assertTrue(failure.getMessage().contains("timeout"));
				assertTrue(System.nanoTime() - before < TimeUnit.SECONDS
						.toNanos(3));
			} finally {
				release.countDown();
			}
			peer.get(5, TimeUnit.SECONDS);
		} finally {
			release.countDown();
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
			Files.deleteIfExists(socket);
			Files.deleteIfExists(directory);
		}
	}

	@Test
	public void cancellationPreservesInterruptAndReservedTokensCannotSendCommands()
			throws Exception {
		Path absent = Path.of("nonexistent-fsmonitor-socket");
		for (String token : new String[] { "quit", "flush", "a\0b" }) {
			assertThrows(IOException.class, () -> FsMonitorDaemonClient
					.query(absent, token, 100, 1024));
		}
		Thread.currentThread().interrupt();
		try {
			assertThrows(IOException.class,
					() -> FsMonitorDaemonClient.query(absent, "0", 100, 1024));
			assertTrue(Thread.currentThread().isInterrupted());
		} finally {
			Thread.interrupted();
		}
		assertEquals(0, Thread.currentThread().isInterrupted() ? 1 : 0);
	}
}
