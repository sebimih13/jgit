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
 * Verification before starting a rebase.
 * 
 * @since 7.8
 */
public class PreRebaseHook extends PreCommitHook {
	/** Hook name. */
	public static final String NAME = "pre-rebase"; //$NON-NLS-1$

	private final String upstream;

	private final String branch;

	/**
	 * Construct a pre-rebase hook.
	 * 
	 * @param repo
	 *            repository
	 * @param upstream
	 *            original upstream argument
	 * @param branch
	 *            explicit target branch, or null for current HEAD
	 * @param output
	 *            hook stdout, or null to use System.err
	 * @param error
	 *            hook stderr, or null to use System.err
	 */
	protected PreRebaseHook(Repository repo, String upstream, String branch,
			PrintStream output, PrintStream error) {
		super(repo, output == null ? System.err : output, error);
		this.upstream = upstream;
		this.branch = branch;
	}

	@Override
	public String getHookName() {
		return NAME;
	}

	@Override
	protected String[] getParameters() {
		return branch == null ? new String[] { upstream }
				: new String[] { upstream, branch };
	}
}
