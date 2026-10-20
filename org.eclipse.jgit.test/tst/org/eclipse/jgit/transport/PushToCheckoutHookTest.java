/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.transport;

import java.util.List;
import org.eclipse.jgit.junit.PushToCheckoutTestCase;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.Repository;

/** C Git t5516 scenarios through JGit's local transport and ReceivePack. */
public class PushToCheckoutHookTest extends PushToCheckoutTestCase {
	@Override
	protected PushResult doPush(Repository source, URIish uri,
			List<RemoteRefUpdate> updates) throws Exception {
		try (Transport transport = Transport.open(source, uri)) {
			return transport.push(NullProgressMonitor.INSTANCE, updates);
		}
	}
}
