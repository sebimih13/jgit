/*
 * Copyright (C) 2011, Christian Halstrick <christian.halstrick@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.errors.NoWorkTreeException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.IndexDiff;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.submodule.SubmoduleWalk.IgnoreSubmoduleMode;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.WorkingTreeIterator;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.util.FsMonitor;
import org.eclipse.jgit.util.Trace2;

/**
 * A class used to execute a {@code Status} command. It has setters for all
 * supported options and arguments of this command and a {@link #call()} method
 * to finally execute the command. Each instance of this class should only be
 * used for one invocation of the command (means: one call to {@link #call()})
 *
 * @see <a href=
 *      "http://www.kernel.org/pub/software/scm/git/docs/git-status.html" >Git
 *      documentation about Status</a>
 */
public class StatusCommand extends GitCommand<Status> {
	private static final Map<Repository, CachedStatus> MONITORED = new WeakHashMap<>();

	private static class CachedStatus {
		final FsMonitor.State monitor = new FsMonitor.State();

		WeakReference<Status> result = new WeakReference<>(null);

		// Reporting modes must retain independent results and provider clocks.
		CachedStatus withoutIgnored;
	}

	private WorkingTreeIterator workingTreeIt;

	private List<String> paths = null;

	private ProgressMonitor progressMonitor = null;

	private IgnoreSubmoduleMode ignoreSubmoduleMode = null;

	private boolean reportIgnored = true;

	private boolean updateUntrackedCache;

	/**
	 * Enable best-effort native UNTR publication after a complete scan.
	 * The default is read-only. Publication requires ignored reporting disabled,
	 * status.showUntrackedFiles=all, an enabled/existing cache, optional locks
	 * permitted, and a supported filesystem. Hook notifications consumed by the
	 * writer invalidate affected scan records. Partial or cancelled scans never write.
	 * @param update whether to attempt an optional cache update
	 * @return this command
	 * @since 7.8
	 */
	public StatusCommand setUpdateUntrackedCache(boolean update) {
		updateUntrackedCache = update;
		return this;
	}

	/**
	 * Select whether ignored paths are included in this status result. Omitting them
	 * permits native untracked-directory cache reuse; ignore rules still apply
	 * to untracked paths. The default preserves ignored-path reporting.
	 * @param report whether to report ignored paths
	 * @return this command
	 * @since 7.8
	 */
	public StatusCommand setReportIgnored(boolean report) {
		reportIgnored = report;
		return this;
	}

	/**
	 * Constructor for StatusCommand.
	 *
	 * @param repo
	 *            a {@link org.eclipse.jgit.lib.Repository} object.
	 */
	protected StatusCommand(Repository repo) {
		super(repo);
	}

	/**
	 * Whether to ignore submodules
	 *
	 * @param mode
	 *            the
	 *            {@link org.eclipse.jgit.submodule.SubmoduleWalk.IgnoreSubmoduleMode}
	 * @return {@code this}
	 * @since 3.6
	 */
	public StatusCommand setIgnoreSubmodules(IgnoreSubmoduleMode mode) {
		ignoreSubmoduleMode = mode;
		return this;
	}

	/**
	 * Show only the status of files which match the given paths. The path must
	 * either name a file or a directory exactly. All paths are always relative
	 * to the repository root. If a directory is specified all files recursively
	 * underneath that directory are matched. If this method is called multiple
	 * times then the status of those files is reported which match at least one
	 * of the given paths. Note that regex expressions or wildcards are not
	 * supported.
	 *
	 * @param path
	 *            repository-relative path of file/directory to show status for
	 *            (with <code>/</code> as separator)
	 * @return {@code this}
	 * @since 3.1
	 */
	public StatusCommand addPath(String path) {
		if (paths == null)
			paths = new ArrayList<>();
		paths.add(path);
		return this;
	}

	/**
	 * Returns the paths filtering this status.
	 *
	 * @return the paths for which the status is shown or <code>null</code> if
	 *         the complete status for the whole repo is shown.
	 * @since 3.1
	 */
	public List<String> getPaths() {
		return paths;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Executes the {@code Status} command with all the options and parameters
	 * collected by the setter methods of this class. Each instance of this
	 * class should only be used for one invocation of the command. Don't call
	 * this method twice on an instance.
	 */
	@Override
	public Status call() throws GitAPIException, NoWorkTreeException {
		try (Trace2.Scope trace = Trace2.getInstance().command(repo, "status", //$NON-NLS-1$
				"jgit.api.StatusCommand")) { //$NON-NLS-1$
			try {
				Status result = callWithTrace();
				if (trace.isEnabled()) {
					trace.data("outcome", progressMonitor != null //$NON-NLS-1$
							&& progressMonitor.isCancelled() ? "cancelled" //$NON-NLS-1$
									: "ok"); //$NON-NLS-1$
				}
				return result;
			} catch (GitAPIException | RuntimeException e) {
				trace.failure(e);
				trace.data("outcome", "error"); //$NON-NLS-1$ //$NON-NLS-2$
				throw e;
			}
		}
	}

	private Status callWithTrace() throws GitAPIException, NoWorkTreeException {
		if (workingTreeIt == null && paths == null
				&& ignoreSubmoduleMode == null && progressMonitor == null
				&& FsMonitor.isEnabled(repo)) {
			CachedStatus cache;
			synchronized (MONITORED) {
				cache = MONITORED.computeIfAbsent(repo,
						r -> new CachedStatus());
				if (!reportIgnored) {
					if (cache.withoutIgnored == null) {
						cache.withoutIgnored = new CachedStatus();
					}
					cache = cache.withoutIgnored;
				}
			}
			synchronized (cache) {
				FsMonitor.Probe probe = cache.monitor.query(repo);
				Status previous = cache.result.get();
				if (probe != null && probe.isUnchanged() && previous != null) {
					if (!FsMonitor.isUntrackedCacheEnabled(repo, probe)) {
						try {
							Status untracked = scanStatus(probe, false, true);
							previous = new Status(previous, untracked);
							cache.result = new WeakReference<>(previous);
						} catch (GitAPIException | RuntimeException | Error failure) {
							cache.result.clear();
							cache.monitor.clear();
							throw failure;
						}
					}
					cache.monitor.remember(probe);
					return previous;
				}
				cache.result.clear();
				try {
					Status result = scanStatus(probe, cache.monitor.requiresFullContentCheck()
							|| probe != null && previous == null);
					cache.monitor.remember(probe);
					if (probe != null) {
						cache.result = new WeakReference<>(result);
					}
					return result;
				} catch (GitAPIException | RuntimeException | Error failure) {
					cache.monitor.clear();
					throw failure;
				}
			}
		}
		return scanStatus();
	}

	private Status scanStatus() throws GitAPIException, NoWorkTreeException {
		return scanStatus(null, false);
	}

	private Status scanStatus(FsMonitor.Probe probe, boolean forceContentCheck) throws GitAPIException, NoWorkTreeException {
		return scanStatus(probe, forceContentCheck, false);
	}

	private Status scanStatus(FsMonitor.Probe probe, boolean forceContentCheck,
			boolean untrackedOnly) throws GitAPIException, NoWorkTreeException {
		if (workingTreeIt == null) {
			workingTreeIt = updateUntrackedCache && !reportIgnored && paths == null
					&& ignoreSubmoduleMode == null && !untrackedOnly
						? new FileTreeIterator(repo, true) : new FileTreeIterator(repo);
		}

		try {
			IndexDiff diff = new IndexDiff(repo, Constants.HEAD, workingTreeIt);
			diff.setReportIgnored(reportIgnored);
			if (untrackedOnly) {
				diff.setFilter(new org.eclipse.jgit.treewalk.filter.TreeFilter() {
					@Override
					public boolean include(org.eclipse.jgit.treewalk.TreeWalk walk) {
						return walk.getTreeCount() < 2 || walk.isSubtree()
								|| walk.getRawMode(1) == 0;
					}
					@Override
					public boolean shouldBeRecursive() { return true; }
					@Override
					public org.eclipse.jgit.treewalk.filter.TreeFilter clone() { return this; }
				});
			}

			if (forceContentCheck) {
				diff.setForceContentCheckPaths(java.util.List.of("/")); //$NON-NLS-1$
			} else if (probe != null) {
				diff.setForceContentCheckPaths(probe.getChangedPaths());
			}
			if (ignoreSubmoduleMode != null)
				diff.setIgnoreSubmoduleMode(ignoreSubmoduleMode);
			if (paths != null)
				diff.setFilter(PathFilterGroup.createFromStrings(paths));
			if (progressMonitor == null)
				diff.diff();
			else
				diff.diff(progressMonitor, ProgressMonitor.UNKNOWN,
						ProgressMonitor.UNKNOWN, ""); //$NON-NLS-1$
			if (updateUntrackedCache) {
				diff.updateUntrackedCache();
			}
			return new Status(diff);
		} catch (IOException e) {
			throw new JGitInternalException(e.getMessage(), e);
		}
	}

	/**
	 * To set the {@link org.eclipse.jgit.treewalk.WorkingTreeIterator} which
	 * should be used. If this method is not called a standard
	 * {@link org.eclipse.jgit.treewalk.FileTreeIterator} is used.
	 *
	 * @param workingTreeIt
	 *            a working tree iterator
	 * @return {@code this}
	 */
	public StatusCommand setWorkingTreeIt(WorkingTreeIterator workingTreeIt) {
		this.workingTreeIt = workingTreeIt;
		return this;
	}

	/**
	 * To set the {@link org.eclipse.jgit.lib.ProgressMonitor} which contains
	 * callback methods to inform you about the progress of this command.
	 *
	 * @param progressMonitor
	 *            a {@link org.eclipse.jgit.lib.ProgressMonitor} object.
	 * @return {@code this}
	 * @since 3.1
	 */
	public StatusCommand setProgressMonitor(ProgressMonitor progressMonitor) {
		this.progressMonitor = progressMonitor;
		return this;
	}
}
