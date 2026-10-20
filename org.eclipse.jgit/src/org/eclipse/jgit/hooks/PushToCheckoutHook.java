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
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Repository;

/**
 * Override deployment of a pushed commit to a checked-out branch.
 *
 * @since 7.8
 */
public class PushToCheckoutHook extends GitHook<Void> {
	/** Hook name. */
	public static final String NAME = "push-to-checkout"; //$NON-NLS-1$

	private final String commit;

	/**
	 * Create a deployment hook.
	 *
	 * @param repo
	 *            repository owning the worktree being updated
	 * @param commit
	 *            proposed commit
	 * @param output
	 *            hook stdout, or null for System.err
	 * @param error
	 *            hook stderr, or null for System.err
	 */
	protected PushToCheckoutHook(Repository repo, AnyObjectId commit,
			OutputStream output, OutputStream error) {
		super(repo, output == null ? System.err : output, error);
		this.commit = commit.name();
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
		return new String[] { commit };
	}
}
