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
import java.io.PrintStream;
import org.eclipse.jgit.api.errors.AbortedByHookException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.ProcessResult;

/**
 * Notification after commits have been rewritten.
 * 
 * @since 7.8
 */
public class PostRewriteHook extends GitHook<Void> {
	/** Hook name. */
	public static final String NAME = "post-rewrite"; //$NON-NLS-1$

	private final String command;

	private final String rewritten;

	/**
	 * Construct a rewrite notification.
	 * 
	 * @param repo
	 *            repository
	 * @param command
	 *            rewrite command, such as amend or rebase
	 * @param rewritten
	 *            old/new object pairs, one per newline-terminated line
	 * @param output
	 *            hook stdout, or null to use System.err
	 * @param error
	 *            hook stderr, or null to use System.err
	 */
	protected PostRewriteHook(Repository repo, String command, String rewritten,
			PrintStream output, PrintStream error) {
		super(repo, output == null ? System.err : output, error);
		this.command = command;
		this.rewritten = rewritten;
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
		return new String[] { command };
	}

	@Override
	protected String getStdinArgs() {
		return rewritten;
	}

	@Override
	protected void handleError(String message, ProcessResult result) {
		// Notification failure cannot undo the rewritten commits.
	}
}
