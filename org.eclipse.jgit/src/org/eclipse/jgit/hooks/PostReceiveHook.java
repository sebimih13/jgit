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
import java.util.Collection;

import org.eclipse.jgit.api.errors.AbortedByHookException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.util.ProcessResult;

/**
 * Notification of successfully received reference updates.
 *
 * @since 7.8
 */
public class PostReceiveHook extends GitHook<Void> {
	/** Hook name. */
	public static final String NAME = "post-receive"; //$NON-NLS-1$

	private final String input;

	/**
	 * Create the hook.
	 *
	 * @param repo
	 *            repository
	 * @param commands
	 *            reference updates in their input order
	 * @param output
	 *            hook output, or null for the default stream
	 * @param error
	 *            hook errors, or null for System.err
	 */
	protected PostReceiveHook(Repository repo,
			Collection<ReceiveCommand> commands, OutputStream output,
			OutputStream error) {
		super(repo, output == null ? System.err : output, error);
		input = Hooks.receiveHookInput(commands);
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
	protected String getStdinArgs() {
		return input;
	}

	@Override
	protected void handleError(String message, ProcessResult result) {
		// A notification's exit status cannot undo the completed operation.
	}
}
