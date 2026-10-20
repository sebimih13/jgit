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

/**
 * Verification before automatic garbage collection is started.
 *
 * @since 7.8
 */
public class PreAutoGcHook extends GitHook<Void> {
	/** Hook name. */
	public static final String NAME = "pre-auto-gc"; //$NON-NLS-1$

	/**
	 * Create the hook.
	 *
	 * @param repo
	 *            repository
	 * @param output
	 *            hook output, or null for the default stream
	 * @param error
	 *            hook errors, or null for System.err
	 */
	protected PreAutoGcHook(Repository repo,
			OutputStream output, OutputStream error) {
		super(repo, output, error);
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

}
