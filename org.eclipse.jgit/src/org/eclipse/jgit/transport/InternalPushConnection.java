/*
 * Copyright (C) 2015, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.resolver.ReceivePackFactory;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;

class InternalPushConnection<C> extends BasePackPushConnection {
	private Thread worker;

	/**
	 * Constructor for InternalPushConnection.
	 *
	 * @param transport
	 *            a {@link org.eclipse.jgit.transport.PackTransport}
	 * @param receivePackFactory
	 *            a
	 *            {@link org.eclipse.jgit.transport.resolver.ReceivePackFactory}
	 * @param req
	 *            a request
	 * @param remote
	 *            the {@link org.eclipse.jgit.lib.Repository}
	 * @throws org.eclipse.jgit.errors.TransportException
	 *             if any.
	 */
	public InternalPushConnection(PackTransport transport,
			final ReceivePackFactory<C> receivePackFactory,
			final C req, final Repository remote) throws TransportException {
		super(transport);

		final InputStream in_r;
		final OutputStream in_w;

		final InputStream out_r;
		final OutputStream out_w;
		List<Closeable> opened = new ArrayList<>(4);
		try {
			// Hook output is copied by short-lived threads. Java's piped
			// streams mistake their exit for the end of the server writer.
			// Channel pipes remain readable until the endpoint is closed.
			Pipe incoming = Pipe.open();
			opened.add(incoming.source());
			opened.add(incoming.sink());
			Pipe outgoing = Pipe.open();
			opened.add(outgoing.source());
			opened.add(outgoing.sink());
			in_r = Channels.newInputStream(incoming.source());
			in_w = Channels.newOutputStream(incoming.sink());
			out_r = Channels.newInputStream(outgoing.source());
			out_w = Channels.newOutputStream(outgoing.sink());
		} catch (IOException err) {
			for (Closeable channel : opened) {
				try {
					channel.close();
				} catch (IOException closeError) {
					err.addSuppressed(closeError);
				}
			}
			remote.close();
			throw new TransportException(uri, JGitText.get().cannotConnectPipes, err);
		}

		worker = new Thread("JGit-Receive-Pack") { //$NON-NLS-1$
			@Override
			public void run() {
				try {
					final ReceivePack rp = receivePackFactory.create(req, remote);
					rp.receive(out_r, in_w, System.err);
				} catch (ServiceNotEnabledException
						| ServiceNotAuthorizedException e) {
					// Ignored. Client cannot use this repository.
				} catch (IOException e) {
					// Since the InternalPushConnection is used in tests, we
					// want to avoid hiding exceptions because they can point to
					// programming errors on the server side. By rethrowing, the
					// default handler will dump it to stderr.
					throw new UncheckedIOException(e);
				} finally {
					try {
						out_r.close();
					} catch (IOException e2) {
						// Ignore close failure, we probably crashed above.
					}

					try {
						in_w.close();
					} catch (IOException e2) {
						// Ignore close failure, we probably crashed above.
					}

					remote.close();
				}
			}
		};
		worker.start();

		init(in_r, out_w);
		readAdvertisedRefs();
	}

	@Override
	public void close() {
		super.close();

		if (worker != null) {
			try {
				worker.join();
			} catch (InterruptedException ie) {
				// Stop waiting and return anyway.
			} finally {
				worker = null;
			}
		}
	}
}
