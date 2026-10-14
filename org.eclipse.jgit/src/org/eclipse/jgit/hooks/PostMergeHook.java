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
 * Notification after a merge finishes, including squash merges.
 * 
 * @since 7.8
 */
public class PostMergeHook extends GitHook<Void> {
	/** Hook name. */
	public static final String NAME = "post-merge"; //$NON-NLS-1$

	private final boolean squash;

	/**
	 * Construct a merge notification.
	 * 
	 * @param repo
	 *            repository
	 * @param squash
	 *            whether the merge was squashed
	 * @param output
	 *            hook output, or null to use System.err
	 * @param error
	 *            hook error output, or null to use System.err
	 */
	protected PostMergeHook(Repository repo, boolean squash, PrintStream output,
			PrintStream error) {
		super(repo, output == null ? System.err : output, error);
		this.squash = squash;
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
		return new String[] { squash ? "1" : "0" }; //$NON-NLS-1$ //$NON-NLS-2$
	}

	@Override
	protected void handleError(String message, ProcessResult result) {
		// Notification status cannot undo the completed merge.
	}
}
