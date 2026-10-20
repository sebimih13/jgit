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

import java.io.IOException;
import java.io.OutputStream;

import org.eclipse.jgit.api.errors.AbortedByHookException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceiveCommand;

/**
 * Verification before a received reference is updated.
 *
 * @since 7.8
 */
public class UpdateHook extends GitHook<Void> {
	/** Hook name. */
	public static final String NAME = "update"; //$NON-NLS-1$

	private final String[] parameters;

	/**
	 * Create the hook.
	 *
	 * @param repo
	 *            repository
	 * @param command
	 *            the proposed reference update
	 * @param output
	 *            hook output, or null for the default stream
	 * @param error
	 *            hook errors, or null for System.err
	 */
	protected UpdateHook(Repository repo, ReceiveCommand command,
			OutputStream output, OutputStream error) {
		super(repo, output, error);
		parameters = new String[] { command.getRefName(),
				command.getOldId().name(), command.getNewId().name() };
	}

	@Override
	public Void call() throws IOException, AbortedByHookException {
		doRun();
		return null;
	}

	@Override
	public String getHookName() {
		return NAME;
	}

	@Override
	protected String[] getParameters() {
		return parameters.clone();
	}
}
