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

import java.io.PrintStream;
import org.eclipse.jgit.lib.Repository;

/**
 * Verification before an automatic merge commit.
 * 
 * @since 7.8
 */
public class PreMergeCommitHook extends PreCommitHook {
	/** Hook name. */
	public static final String NAME = "pre-merge-commit"; //$NON-NLS-1$

	/**
	 * Construct a merge verification hook.
	 * 
	 * @param repo
	 *            repository
	 * @param output
	 *            hook stdout, or null to use System.err
	 * @param error
	 *            hook stderr, or null to use System.err
	 */
	protected PreMergeCommitHook(Repository repo, PrintStream output,
			PrintStream error) {
		super(repo, output == null ? System.err : output, error);
	}

	@Override
	public String getHookName() {
		return NAME;
	}
}
