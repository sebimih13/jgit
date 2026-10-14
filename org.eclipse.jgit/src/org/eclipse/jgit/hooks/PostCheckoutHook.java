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
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

/**
 * Post-checkout notification with old/new HEAD and branch/file flag.
 * 
 * @since 7.8
 */
public class PostCheckoutHook extends GitHook<Void> {
	/** Hook event name. */
	public static final String NAME = "post-checkout"; //$NON-NLS-1$

	private final ObjectId oldHead;

	private final ObjectId newHead;

	private final boolean branch;

	/**
	 * @param repo repository
	 * @param oldHead previous commit
	 * @param newHead checked-out commit
	 * @param branch branch or file checkout
	 * @param out stdout, or null for stderr
	 * @param err stderr, or null for stderr
	 */
	protected PostCheckoutHook(Repository repo, ObjectId oldHead,
			ObjectId newHead, boolean branch, PrintStream out,
			PrintStream err) {
		super(repo, out == null ? System.err : out, err);
		this.oldHead = oldHead == null ? ObjectId.zeroId() : oldHead;
		this.newHead = newHead == null ? ObjectId.zeroId() : newHead;
		this.branch = branch;
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
		return new String[] { oldHead.name(), newHead.name(),
				branch ? "1" : "0" }; //$NON-NLS-1$ //$NON-NLS-2$
	}
}
