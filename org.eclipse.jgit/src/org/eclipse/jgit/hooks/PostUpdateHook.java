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
 * Notification with the names of successfully updated references.
 *
 * @since 7.8
 */
public class PostUpdateHook extends GitHook<Void> {
	/** Hook name. */
	public static final String NAME = "post-update"; //$NON-NLS-1$

	private final String[] parameters;

	/**
	 * Create the hook.
	 *
	 * @param repo
	 *            repository
	 * @param commands
	 *            successfully applied reference updates
	 * @param output
	 *            hook output, or null for the default stream
	 * @param error
	 *            hook errors, or null for System.err
	 */
	protected PostUpdateHook(Repository repo,
			Collection<ReceiveCommand> commands, OutputStream output,
			OutputStream error) {
		super(repo, output == null ? System.err : output, error);
		parameters = commands.stream().map(ReceiveCommand::getRefName)
				.toArray(String[]::new);
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

	@Override
	protected void handleError(String message, ProcessResult result) {
		// A notification's exit status cannot undo the completed operation.
	}
}
