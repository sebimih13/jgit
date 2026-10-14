/*
 * Copyright (C) 2015 Obeo. and others
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
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.util.LfsFactory;

/**
 * Factory class for instantiating supported hooks.
 *
 * @since 4.0
 */
public class Hooks {

	/**
	 * Create a post-rewrite notification.
	 *
	 * @param repo repository
	 * @param command rewrite command
	 * @param rewritten newline-terminated old/new object pairs
	 * @param output hook stdout, or null to use System.err
	 * @param error hook stderr, or null to use System.err
	 * @return the hook
	 * @since 7.8
	 */
	public static PostRewriteHook postRewrite(Repository repo, String command,
			String rewritten, PrintStream output, PrintStream error) {
		return new PostRewriteHook(repo, command, rewritten, output, error);
	}


	/**
	 * Create a pre-rebase verification hook.
	 *
	 * @param repo repository
	 * @param upstream original upstream argument
	 * @param branch explicit branch, or null for current HEAD
	 * @param output hook stdout, or null to use System.err
	 * @param error hook stderr, or null to use System.err
	 * @return the hook
	 * @since 7.8
	 */
	public static PreRebaseHook preRebase(Repository repo, String upstream,
			String branch, PrintStream output, PrintStream error) {
		return new PreRebaseHook(repo, upstream, branch, output, error);
	}


	/**
	 * Create a pre-merge-commit hook.
	 *
	 * @param repo
	 *            repository
	 * @param output
	 *            hook stdout, or null to use System.err
	 * @param error
	 *            hook stderr, or null to use System.err
	 * @return the hook
	 * @since 7.8
	 */
	public static PreMergeCommitHook preMergeCommit(Repository repo,
			PrintStream output, PrintStream error) {
		return new PreMergeCommitHook(repo, output, error);
	}


	/**
	 * Create a post-merge notification.
	 * @param repo repository
	 * @param squash whether the merge was squashed
	 * @param output hook output, or null to use System.err
	 * @param error hook error output, or null to use System.err
	 * @return the hook
	 * @since 7.8
	 */
	public static PostMergeHook postMerge(Repository repo, boolean squash,
			PrintStream output, PrintStream error) {
		return new PostMergeHook(repo, squash, output, error);
	}

	/**
	 * Create a post-checkout notification.
	 * @param repo repository
	 * @param oldHead previous HEAD, or null for an unborn HEAD
	 * @param newHead checked-out commit, or null for an unborn HEAD
	 * @param branch true for a branch/commit checkout, false for paths
	 * @param output hook stdout, or null for stderr
	 * @param error hook stderr, or null for stderr
	 * @return the hook
	 * @since 7.8
	 */
	public static PostCheckoutHook postCheckout(Repository repo, ObjectId oldHead,
			ObjectId newHead, boolean branch, PrintStream output, PrintStream error) {
		return new PostCheckoutHook(repo, oldHead, newHead, branch, output, error);
	}

	/**
	 * Create a prepare-commit-msg hook.
	 *
	 * @param repo repository
	 * @param outputStream hook standard output, or null for System.out
	 * @param errorStream hook standard error, or null for System.err
	 * @return the hook
	 * @since 7.8
	 */
	public static PrepareCommitMsgHook prepareCommitMsg(Repository repo,
			PrintStream outputStream, PrintStream errorStream) {
		return new PrepareCommitMsgHook(repo, outputStream, errorStream);
	}

	/**
	 * Create pre-commit hook for the given repository with the default error
	 * stream
	 *
	 * @param repo
	 *            a {@link org.eclipse.jgit.lib.Repository} object.
	 * @param outputStream
	 *            The output stream, or {@code null} to use {@code System.out}
	 * @return The pre-commit hook for the given repository.
	 */
	public static PreCommitHook preCommit(Repository repo,
			PrintStream outputStream) {
		return new PreCommitHook(repo, outputStream);
	}

	/**
	 * Create pre-commit hook for the given repository
	 *
	 * @param repo
	 *            a {@link org.eclipse.jgit.lib.Repository} object.
	 * @param outputStream
	 *            The output stream, or {@code null} to use {@code System.out}
	 * @param errorStream
	 *            The error stream, or {@code null} to use {@code System.err}
	 * @return The pre-commit hook for the given repository.
	 * @since 5.6
	 */
	public static PreCommitHook preCommit(Repository repo,
			PrintStream outputStream, PrintStream errorStream) {
		return new PreCommitHook(repo, outputStream, errorStream);
	}

	/**
	 * Create post-commit hook for the given repository with the default error
	 * stream
	 *
	 * @param repo
	 *            a {@link org.eclipse.jgit.lib.Repository} object.
	 * @param outputStream
	 *            The output stream, or {@code null} to use {@code System.out}
	 * @return The post-commit hook for the given repository.
	 * @since 4.5
	 */
	public static PostCommitHook postCommit(Repository repo,
			PrintStream outputStream) {
		return new PostCommitHook(repo, outputStream);
	}

	/**
	 * Create post-commit hook for the given repository
	 *
	 * @param repo
	 *            a {@link org.eclipse.jgit.lib.Repository} object.
	 * @param outputStream
	 *            The output stream, or {@code null} to use {@code System.out}
	 * @param errorStream
	 *            The error stream, or {@code null} to use {@code System.err}
	 * @return The pre-commit hook for the given repository.
	 * @since 5.6
	 */
	public static PostCommitHook postCommit(Repository repo,
			PrintStream outputStream, PrintStream errorStream) {
		return new PostCommitHook(repo, outputStream, errorStream);
	}

	/**
	 * Create commit-msg hook for the given repository with the default error
	 * stream
	 *
	 * @param repo
	 *            a {@link org.eclipse.jgit.lib.Repository} object.
	 * @param outputStream
	 *            The output stream, or {@code null} to use {@code System.out}
	 * @return The commit-msg hook for the given repository.
	 */
	public static CommitMsgHook commitMsg(Repository repo,
			PrintStream outputStream) {
		return new CommitMsgHook(repo, outputStream);
	}

	/**
	 * Create commit-msg hook for the given repository
	 *
	 * @param repo
	 *            a {@link org.eclipse.jgit.lib.Repository} object.
	 * @param outputStream
	 *            The output stream, or {@code null} to use {@code System.out}
	 * @param errorStream
	 *            The error stream, or {@code null} to use {@code System.err}
	 * @return The pre-commit hook for the given repository.
	 * @since 5.6
	 */
	public static CommitMsgHook commitMsg(Repository repo,
			PrintStream outputStream, PrintStream errorStream) {
		return new CommitMsgHook(repo, outputStream, errorStream);
	}

	/**
	 * Create pre-push hook for the given repository with the default error
	 * stream
	 *
	 * @param repo
	 *            a {@link org.eclipse.jgit.lib.Repository} object.
	 * @param outputStream
	 *            The output stream, or {@code null} to use {@code System.out}
	 * @return The pre-push hook for the given repository.
	 * @since 4.2
	 */
	public static PrePushHook prePush(Repository repo, PrintStream outputStream) {
		if (LfsFactory.getInstance().isAvailable()) {
			PrePushHook hook = LfsFactory.getInstance().getPrePushHook(repo,
					outputStream);
			if (hook != null) {
				if (hook.isNativeHookPresent()) {
					return new PrePushHook(repo, outputStream);
				}
				return hook;
			}
		}
		return new PrePushHook(repo, outputStream);
	}

	/**
	 * Create pre-push hook for the given repository
	 *
	 * @param repo
	 *            a {@link org.eclipse.jgit.lib.Repository} object.
	 * @param outputStream
	 *            The output stream, or {@code null} to use {@code System.out}
	 * @param errorStream
	 *            The error stream, or {@code null} to use {@code System.err}
	 * @return The pre-push hook for the given repository.
	 * @since 5.6
	 */
	public static PrePushHook prePush(Repository repo, PrintStream outputStream,
			PrintStream errorStream) {
		if (LfsFactory.getInstance().isAvailable()) {
			PrePushHook hook = LfsFactory.getInstance().getPrePushHook(repo,
					outputStream, errorStream);
			if (hook != null) {
				if (hook.isNativeHookPresent()) {
					return new PrePushHook(repo, outputStream, errorStream);
				}
				return hook;
			}
		}
		return new PrePushHook(repo, outputStream, errorStream);
	}
}
