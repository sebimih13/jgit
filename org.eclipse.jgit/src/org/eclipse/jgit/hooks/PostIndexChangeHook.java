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
import org.eclipse.jgit.util.ProcessResult;

/**
 * Notification after an index write has been committed and unlocked.
 *
 * @since 7.8
 */
public class PostIndexChangeHook extends GitHook<Void> {
	/** Hook name. */
	public static final String NAME = "post-index-change"; //$NON-NLS-1$

	private final boolean updatedWorkTree;

	private final boolean updatedSkipWorkTree;

	/**
	 * Create the hook.
	 *
	 * @param repo
	 *            repository
	 * @param updatedWorkTree
	 *            whether unpacking trees updated the working tree
	 * @param updatedSkipWorkTree
	 *            whether index entries could have changed skip-worktree bits
	 * @param output
	 *            hook output, or null for the default stream
	 * @param error
	 *            hook errors, or null for System.err
	 */
	protected PostIndexChangeHook(Repository repo, boolean updatedWorkTree, boolean updatedSkipWorkTree,
			OutputStream output, OutputStream error) {
		super(repo, output == null ? System.err : output, error);
		if (updatedWorkTree && updatedSkipWorkTree) {
			throw new IllegalArgumentException();
		}
		this.updatedWorkTree = updatedWorkTree;
		this.updatedSkipWorkTree = updatedSkipWorkTree;
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
		return new String[] { updatedWorkTree ? "1" : "0", //$NON-NLS-1$ //$NON-NLS-2$
				updatedSkipWorkTree ? "1" : "0" }; //$NON-NLS-1$ //$NON-NLS-2$
	}

	@Override
	protected void handleError(String message, ProcessResult result) {
		// A notification's exit status cannot undo the completed operation.
	}
}
