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
import java.util.Arrays;

import org.eclipse.jgit.lib.Repository;

/**
 * Prepare the commit message before verification. Unlike pre-commit and
 * commit-msg, this hook is not bypassed by the no-verify option.
 *
 * @since 7.8
 */
public class PrepareCommitMsgHook extends CommitMsgHook {
	/** Name of the prepare-commit-msg hook. */
	public static final String NAME = "prepare-commit-msg"; //$NON-NLS-1$

	private String source;

	private String commit;

	/**
	 * @param repo
	 *            repository
	 * @param outputStream
	 *            hook standard output, or null for System.out
	 * @param errorStream
	 *            hook standard error, or null for System.err
	 */
	protected PrepareCommitMsgHook(Repository repo, PrintStream outputStream,
			PrintStream errorStream) {
		super(repo, outputStream, errorStream);
	}

	@Override
	public String getHookName() {
		return NAME;
	}

	@Override
	protected String[] getParameters() {
		String[] parameters = super.getParameters();
		if (source == null) {
			return parameters;
		}
		parameters = Arrays.copyOf(parameters, commit == null ? 2 : 3);
		parameters[1] = source;
		if (commit != null) {
			parameters[2] = commit;
		}
		return parameters;
	}

	/**
	 * Set the source of the proposed message.
	 *
	 * @param source
	 *            message, merge, squash, template, commit, or null when no
	 *            source argument applies
	 * @param commit
	 *            commit name when the source is commit, otherwise null
	 * @return this hook
	 */
	public PrepareCommitMsgHook setMessageSource(String source, String commit) {
		this.source = source;
		this.commit = commit;
		return this;
	}

	@Override
	public PrepareCommitMsgHook setCommitMessage(String message) {
		super.setCommitMessage(message);
		return this;
	}
}
