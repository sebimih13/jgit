/*
 * Copyright (C) 2007, Dave Watson <dwatson@mimvista.com>
 * Copyright (C) 2007-2008, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2010, Jens Baumgart <jens.baumgart@sap.com>
 * Copyright (C) 2013, Robin Stocker <robin@nibor.org>
 * Copyright (C) 2014, Axel Richard <axel.richard@obeo.fr> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.lib;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.StopWalkException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.util.IndexEntrySelection;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.submodule.SubmoduleWalk;
import org.eclipse.jgit.submodule.SubmoduleWalk.IgnoreSubmoduleMode;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.TreeWalk.OperationType;
import org.eclipse.jgit.treewalk.WorkingTreeIterator;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.IndexDiffFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.FsMonitor;
import org.eclipse.jgit.util.Trace2;

/**
 * Compares the index, a tree, and the working directory Ignored files are not
 * taken into account. The following information is retrieved:
 * <ul>
 * <li>added files</li>
 * <li>changed files</li>
 * <li>removed files</li>
 * <li>missing files</li>
 * <li>modified files</li>
 * <li>conflicting files</li>
 * <li>untracked files</li>
 * <li>files with assume-unchanged flag</li>
 * </ul>
 */
public class IndexDiff {

	/**
	 * Represents the state of the index for a certain path regarding the stages
	 * - which stages exist for a path and which not (base, ours, theirs).
	 * <p>
	 * This is used for figuring out what kind of conflict occurred.
	 *
	 * @see IndexDiff#getConflictingStageStates()
	 * @since 3.0
	 */
	public enum StageState {
		/**
		 * Exists in base, but neither in ours nor in theirs.
		 */
		BOTH_DELETED(1),

		/**
		 * Only exists in ours.
		 */
		ADDED_BY_US(2),

		/**
		 * Exists in base and ours, but no in theirs.
		 */
		DELETED_BY_THEM(3),

		/**
		 * Only exists in theirs.
		 */
		ADDED_BY_THEM(4),

		/**
		 * Exists in base and theirs, but not in ours.
		 */
		DELETED_BY_US(5),

		/**
		 * Exists in ours and theirs, but not in base.
		 */
		BOTH_ADDED(6),

		/**
		 * Exists in all stages, content conflict.
		 */
		BOTH_MODIFIED(7);

		private final int stageMask;

		private StageState(int stageMask) {
			this.stageMask = stageMask;
		}

		int getStageMask() {
			return stageMask;
		}

		/**
		 * Whether there is a "base" stage entry
		 *
		 * @return whether there is a "base" stage entry
		 */
		public boolean hasBase() {
			return (stageMask & 1) != 0;
		}

		/**
		 * Whether there is an "ours" stage entry
		 *
		 * @return whether there is an "ours" stage entry
		 */
		public boolean hasOurs() {
			return (stageMask & 2) != 0;
		}

		/**
		 * Whether there is a "theirs" stage entry
		 *
		 * @return whether there is a "theirs" stage entry
		 */
		public boolean hasTheirs() {
			return (stageMask & 4) != 0;
		}

		static StageState fromMask(int stageMask) {
			// bits represent: theirs, ours, base
			switch (stageMask) {
			case 1: // 0b001
				return BOTH_DELETED;
			case 2: // 0b010
				return ADDED_BY_US;
			case 3: // 0b011
				return DELETED_BY_THEM;
			case 4: // 0b100
				return ADDED_BY_THEM;
			case 5: // 0b101
				return DELETED_BY_US;
			case 6: // 0b110
				return BOTH_ADDED;
			case 7: // 0b111
				return BOTH_MODIFIED;
			default:
				return null;
			}
		}
	}

	private static final class ProgressReportingFilter extends TreeFilter {

		private final ProgressMonitor monitor;

		private int count = 0;

		private int stepSize;

		private final int total;

		private ProgressReportingFilter(ProgressMonitor monitor, int total) {
			this.monitor = monitor;
			this.total = total;
			stepSize = total / 100;
			if (stepSize == 0)
				stepSize = 1000;
		}

		@Override
		public boolean shouldBeRecursive() {
			return false;
		}

		@Override
		public boolean include(TreeWalk walker)
				throws MissingObjectException,
				IncorrectObjectTypeException, IOException {
			count++;
			if (count % stepSize == 0) {
				if (count <= total)
					monitor.update(stepSize);
				if (monitor.isCancelled())
					throw StopWalkException.INSTANCE;
			}
			return true;
		}

		@Override
		public TreeFilter clone() {
			throw new IllegalStateException(
					"Do not clone this kind of filter: " //$NON-NLS-1$
							+ getClass().getName());
		}
	}

	private static final int TREE = 0;

	private static final int INDEX = 1;

	private static final int WORKDIR = 2;

	private final Repository repository;

	private final AnyObjectId tree;

	private TreeFilter filter = null;

	private boolean reportIgnored = true;

	private boolean completeScan;

	private ProgressMonitor scanMonitor;

	private FsMonitor.Probe scanProbe;

	private FsMonitor.Probe suppliedProbe;

	private FsMonitor.Publication publication;

	private Set<DirCacheEntry> contentCheckEntries = Collections.emptySet();

	/**
	 * Supply the whole-result cache query preceding this scan. Its index and
	 * repository context are validated again before any cached metadata is used.
	 * @param probe query preceding the scan, or null for ordinary index validation
	 * @since 7.8
	 */
	public void setFsMonitorProbe(FsMonitor.Probe probe) {
		suppliedProbe = probe;
	}

	/**
	 * Get the query associated with the completed scan, adjusted only for an
	 * owned optional publication. A null result forbids remembering correlation.
	 * @return completed scan boundary, or null
	 * @since 7.8
	 */
	public FsMonitor.Probe getFsMonitorProbe() {
		return scanProbe;
	}

	/**
	 * Get the outcome of the last committed optional index publication.
	 * @return publication outcome, or null if none was committed
	 * @since 7.8
	 */
	public FsMonitor.Publication getFsMonitorPublication() {
		return publication;
	}

	/**
	 * Publish recorded all-mode traversal data after a complete, uncancelled scan. A normal
	 * working-tree iterator does not record inputs and leaves the index intact.
	 * @return whether the optional index update was committed
	 * @since 7.8
	 */
	public boolean updateUntrackedCache() {
		return updateUntrackedCache(null);
	}

	/**
	 * Publish recorded traversal data, attaching an opaque identity to its index
	 * event so its own cache can distinguish this write from other writers.
	 * @param operationToken operation identity, or null
	 * @return whether the optional update was committed
	 * @since 7.8
	 */
	public boolean updateUntrackedCache(Object operationToken) {
		if (!completeScan || filter != null || reportIgnored
				|| initialWorkingTreeIterator.getClass() != FileTreeIterator.class) {
			return false;
		}
		Set<String> verified = new HashSet<>();
		if (FsMonitor.isConfigured(repository)) {
			for (int i = 0; i < dirCache.getEntryCount(); i++) {
				DirCacheEntry entry = dirCache.getEntry(i);
				String path = entry.getPathString();
				if (entry.getStage() == 0 && !entry.isAssumeValid()
						&& !entry.isSkipWorkTree() && entry.getFileMode() != FileMode.GITLINK
						&& (!dirCache.isFsMonitorValid(i) || contentCheckEntries.contains(entry))
						&& !modified.contains(path)
						&& !missing.contains(path) && !conflicts.containsKey(path)) {
					verified.add(path);
				}
			}
		}
		FileTreeIterator iterator = (FileTreeIterator) initialWorkingTreeIterator;
		if (!iterator.updateUntrackedCache(dirCache, untracked, verified, scanMonitor,
				scanProbe, operationToken)) {
			return false;
		}
		publication = iterator.getFsMonitorPublication();
		scanProbe = publication == null ? null : publication.getProbe();
		return true;
	}

	/**
	 * Select ignored-path reporting, which is enabled by default. Ignore matching is
	 * retained when reporting is disabled. Whole-tree scans may then consume
	 * native UNTR records without enumerating ignored names.
	 * @param report whether to include ignored paths in the result
	 * @since 7.8
	 */
	public void setReportIgnored(boolean report) {
		reportIgnored = report;
	}

	private final WorkingTreeIterator initialWorkingTreeIterator;

	private Set<String> added = new HashSet<>();

	private Set<String> changed = new HashSet<>();

	private Set<String> removed = new HashSet<>();

	private Set<String> missing = new HashSet<>();

	private Set<String> missingSubmodules = new HashSet<>();

	private Set<String> modified = new HashSet<>();

	private Set<String> untracked = new HashSet<>();

	private Map<String, StageState> conflicts = new HashMap<>();

	private Set<String> ignored;

	private Set<String> assumeUnchanged;

	private DirCache dirCache;

	private Collection<String> forceContentCheckPaths = java.util.List.of();

	private IndexDiffFilter indexDiffFilter;

	private Map<String, IndexDiff> submoduleIndexDiffs = new HashMap<>();

	private IgnoreSubmoduleMode ignoreSubmoduleMode = null;

	private Map<FileMode, Set<String>> fileModes = new HashMap<>();

	/**
	 * Construct an IndexDiff
	 *
	 * @param repository
	 *            a {@link org.eclipse.jgit.lib.Repository} object.
	 * @param revstr
	 *            symbolic name e.g. HEAD An EmptyTreeIterator is used if
	 *            <code>revstr</code> cannot be resolved.
	 * @param workingTreeIterator
	 *            iterator for working directory
	 * @throws java.io.IOException
	 *             if an IO error occurred
	 */
	public IndexDiff(Repository repository, String revstr,
			WorkingTreeIterator workingTreeIterator) throws IOException {
		this(repository, repository.resolve(revstr), workingTreeIterator);
	}

	/**
	 * Construct an Indexdiff
	 *
	 * @param repository
	 *            a {@link org.eclipse.jgit.lib.Repository} object.
	 * @param objectId
	 *            tree id. If null, an EmptyTreeIterator is used.
	 * @param workingTreeIterator
	 *            iterator for working directory
	 * @throws java.io.IOException
	 *             if an IO error occurred
	 */
	public IndexDiff(Repository repository, ObjectId objectId,
			WorkingTreeIterator workingTreeIterator) throws IOException {
		this.repository = repository;
		if (objectId != null) {
			try (RevWalk rw = new RevWalk(repository)) {
				tree = rw.parseTree(objectId);
			}
		} else {
			tree = null;
		}
		this.initialWorkingTreeIterator = workingTreeIterator;
	}

	/**
	 * Defines how modifications in submodules are treated
	 *
	 * @param mode
	 *            defines how modifications in submodules are treated
	 * @since 3.6
	 */
	public void setIgnoreSubmoduleMode(IgnoreSubmoduleMode mode) {
		this.ignoreSubmoduleMode = mode;
	}

	/**
	 * A factory to producing WorkingTreeIterators
	 * @since 3.6
	 */
	public interface WorkingTreeIteratorFactory {
		/**
		 * Get working tree iterator
		 *
		 * @param repo
		 *            the repository
		 * @return working tree iterator
		 */
		public WorkingTreeIterator getWorkingTreeIterator(Repository repo);
	}

	private WorkingTreeIteratorFactory wTreeIt = FileTreeIterator::new;

	/**
	 * Allows higher layers to set the factory for WorkingTreeIterators.
	 *
	 * @param wTreeIt
	 *            working tree iterator factory
	 * @since 3.6
	 */
	public void setWorkingTreeItFactory(WorkingTreeIteratorFactory wTreeIt) {
		this.wTreeIt = wTreeIt;
	}

	/**
	 * Sets a filter. Can be used e.g. for restricting the tree walk to a set of
	 * files.
	 *
	 * @param filter
	 *            a {@link org.eclipse.jgit.treewalk.filter.TreeFilter} object.
	 */
	public void setFilter(TreeFilter filter) {
		this.filter = filter;
	}

	/**
	 * Run the diff operation. Until this is called, all lists will be empty.
	 * Use {@link #diff(ProgressMonitor, int, int, String)} if a progress
	 * monitor is required.
	 *
	 * @return if anything is different between index, tree, and workdir
	 * @throws java.io.IOException
	 *             if an IO error occurred
	 */
	public boolean diff() throws IOException {
		return diff(null);
	}

	/**
	 * Run the diff operation. Until this is called, all lists will be empty.
	 * Use
	 * {@link #diff(ProgressMonitor, int, int, String, RepositoryBuilderFactory)}
	 * if a progress monitor is required.
	 * <p>
	 * The operation may create repositories for submodules using builders
	 * provided by the given {@code factory}, if any, and will also close these
	 * submodule repositories again.
	 * </p>
	 *
	 * @param factory
	 *            the {@link RepositoryBuilderFactory} to use to create builders
	 *            to create submodule repositories, if needed; if {@code null},
	 *            submodule repositories will be built using a plain
	 *            {@link RepositoryBuilder}.
	 * @return if anything is different between index, tree, and workdir
	 * @throws java.io.IOException
	 *             if an IO error occurred
	 * @since 5.6
	 */
	public boolean diff(RepositoryBuilderFactory factory)
			throws IOException {
		return diff(null, 0, 0, "", factory); //$NON-NLS-1$
	}

	/**
	 * Run the diff operation. Until this is called, all lists will be empty.
	 * <p>
	 * The operation may be aborted by the progress monitor. In that event it
	 * will report what was found before the cancel operation was detected.
	 * Callers should ignore the result if monitor.isCancelled() is true. If a
	 * progress monitor is not needed, callers should use {@link #diff()}
	 * instead. Progress reporting is crude and approximate and only intended
	 * for informing the user.
	 *
	 * @param monitor
	 *            for reporting progress, may be null
	 * @param estWorkTreeSize
	 *            number or estimated files in the working tree
	 * @param estIndexSize
	 *            number of estimated entries in the cache
	 * @param title
	 *            a {@link java.lang.String} object.
	 * @return if anything is different between index, tree, and workdir
	 * @throws java.io.IOException
	 *             if an IO error occurred
	 */
	public boolean diff(final ProgressMonitor monitor, int estWorkTreeSize,
			int estIndexSize, final String title)
			throws IOException {
		return diff(monitor, estWorkTreeSize, estIndexSize, title, null);
	}

	/**
	 * Run the diff operation. Until this is called, all lists will be empty.
	 * <p>
	 * The operation may be aborted by the progress monitor. In that event it
	 * will report what was found before the cancel operation was detected.
	 * Callers should ignore the result if monitor.isCancelled() is true. If a
	 * progress monitor is not needed, callers should use {@link #diff()}
	 * instead. Progress reporting is crude and approximate and only intended
	 * for informing the user.
	 * </p>
	 * <p>
	 * The operation may create repositories for submodules using builders
	 * provided by the given {@code factory}, if any, and will also close these
	 * submodule repositories again.
	 * </p>
	 *
	 * @param monitor
	 *            for reporting progress, may be null
	 * @param estWorkTreeSize
	 *            number or estimated files in the working tree
	 * @param estIndexSize
	 *            number of estimated entries in the cache
	 * @param title
	 *            a {@link java.lang.String} object.
	 * @param factory
	 *            the {@link RepositoryBuilderFactory} to use to create builders
	 *            to create submodule repositories, if needed; if {@code null},
	 *            submodule repositories will be built using a plain
	 *            {@link RepositoryBuilder}.
	 * @return if anything is different between index, tree, and workdir
	 * @throws java.io.IOException
	 *             if an IO error occurred
	 * @since 5.6
	 */
	public boolean diff(ProgressMonitor monitor, int estWorkTreeSize,
			int estIndexSize, String title, RepositoryBuilderFactory factory)
			throws IOException {
		try (Trace2.Scope trace = Trace2.getInstance().region(repository,
				"index", "diff")) { //$NON-NLS-1$ //$NON-NLS-2$
			try {
				boolean result = diffWithTrace(monitor, estWorkTreeSize,
						estIndexSize, title, factory);
				if (trace.isEnabled()) {
					trace.data("outcome", monitor != null && monitor.isCancelled() //$NON-NLS-1$
							? "cancelled" : "ok"); //$NON-NLS-1$ //$NON-NLS-2$
				}
				return result;
			} catch (IOException | RuntimeException e) {
				trace.data("outcome", "error"); //$NON-NLS-1$ //$NON-NLS-2$
				throw e;
			}
		}
	}

	/**
	 * Require content checks for paths whose stat metadata is known to be
	 * stale. Paths include their descendants; "/" requests checks throughout
	 * the index. The on-disk index is not modified. Assume-unchanged and
	 * skip-worktree flags retain their usual semantics.
	 *
	 * @param paths
	 *            repository-relative paths, copied on entry
	 * @since 7.8
	 */
	public void setForceContentCheckPaths(Collection<String> paths) {
		forceContentCheckPaths = java.util.List.copyOf(paths);
	}

	private Set<DirCacheEntry> configureContentChecks(BitSet selected) {
		if (forceContentCheckPaths.isEmpty()) {
			initialWorkingTreeIterator.setForceContentCheckEntries(Collections.emptySet());
			return Collections.emptySet();
		}
		Set<DirCacheEntry> entries = new HashSet<>();
		boolean all = repository.getConfig().getBoolean("core", null, //$NON-NLS-1$
				"ignoreCase", false); //$NON-NLS-1$
		for (String path : forceContentCheckPaths) {
			if (path.equals("/") || path.matches("(^|.*/)\\.(/.*|$)") //$NON-NLS-1$ //$NON-NLS-2$
					|| path.contains("//")) { //$NON-NLS-1$
				all = true;
				break;
			}
		}
		if (all) {
			for (int i = selected == null ? 0 : selected.nextSetBit(0);
					i >= 0 && i < dirCache.getEntryCount();
					i = selected == null ? i + 1 : selected.nextSetBit(i + 1)) {
				addContentCheckEntry(entries, dirCache.getEntry(i));
			}
			initialWorkingTreeIterator.setForceContentCheckEntries(entries);
			return entries;
		}
		for (String path : forceContentCheckPaths) {
			String directory = path.endsWith("/") ? path : path + "/"; //$NON-NLS-1$ //$NON-NLS-2$
			int file = dirCache.findEntry(path);
			if (file >= 0 && (selected == null || selected.get(file))) {
				addContentCheckEntry(entries, dirCache.getEntry(file));
			}
			int start = dirCache.findEntry(directory);
			if (start < 0) {
				start = -(start + 1);
			}
			for (int i = start; i < dirCache.getEntryCount(); i++) {
				DirCacheEntry entry = dirCache.getEntry(i);
				if (!entry.getPathString().startsWith(directory)) {
					break;
				}
				if (selected == null || selected.get(i)) {
					addContentCheckEntry(entries, entry);
				}
			}
		}
		initialWorkingTreeIterator.setForceContentCheckEntries(entries);
		return entries;
	}

	private static void addContentCheckEntry(Set<DirCacheEntry> entries,
			DirCacheEntry entry) {
		if (entry.getStage() == 0 && !entry.isAssumeValid()
				&& !entry.isSkipWorkTree()
				&& entry.getFileMode() != FileMode.GITLINK) {
			entries.add(entry);
		}
	}

	private boolean diffWithTrace(ProgressMonitor monitor, int estWorkTreeSize,
			int estIndexSize, String title, RepositoryBuilderFactory factory)
			throws IOException {
		completeScan = false;
		publication = null;
		scanMonitor = monitor;
		try (Trace2.Scope trace = Trace2.getInstance().region(repository,
				"index", "read")) { //$NON-NLS-1$ //$NON-NLS-2$
			dirCache = repository.readDirCache();
			FsMonitor.IndexQuery query = initialWorkingTreeIterator.getClass() == FileTreeIterator.class
					? suppliedProbe == null ? FsMonitor.queryStoredIndex(repository, dirCache)
							: FsMonitor.queryForScan(repository, dirCache, suppliedProbe)
					: new FsMonitor.IndexQuery(null, false);
			query.invalidateUntrackedCache(repository, dirCache);
			FsMonitor.Probe probe = query.probe();
			scanProbe = probe;
			if (query.forceContentCheck()) {
				forceContentCheckPaths = List.of("/"); //$NON-NLS-1$
			} else if (probe != null) {
				List<String> checks = new ArrayList<>(forceContentCheckPaths);
				checks.addAll(probe.getIndexCheckPaths(dirCache));
				forceContentCheckPaths = checks;
			}
			BitSet selected = IndexEntrySelection.select(dirCache, filter, monitor);
			Set<DirCacheEntry> forced = configureContentChecks(selected);
			contentCheckEntries = forced;
			Set<DirCacheEntry> trusted = probe != null
					? probe.getIndexValidEntries(dirCache, selected) : new HashSet<>();
			trusted.removeAll(forced);
			initialWorkingTreeIterator.setFsMonitorValidEntries(trusted);
		}
		if (initialWorkingTreeIterator instanceof FileTreeIterator) {
			((FileTreeIterator) initialWorkingTreeIterator).setUntrackedCache(dirCache,
					reportIgnored || filter != null);
		}
		if (initialWorkingTreeIterator instanceof FileTreeIterator) {
			((FileTreeIterator) initialWorkingTreeIterator)
					.preloadIndex(dirCache, monitor, filter);
		}

		try (TreeWalk treeWalk = new TreeWalk(repository)) {
			treeWalk.setOperationType(OperationType.CHECKIN_OP);
			treeWalk.setRecursive(true);
			// add the trees (tree, dirchache, workdir)
			if (tree != null)
				treeWalk.addTree(tree);
			else
				treeWalk.addTree(new EmptyTreeIterator());
			treeWalk.addTree(new DirCacheIterator(dirCache));
			treeWalk.addTree(initialWorkingTreeIterator);
			initialWorkingTreeIterator.setDirCacheIterator(treeWalk, 1);
			Collection<TreeFilter> filters = new ArrayList<>(4);

			if (monitor != null) {
				// Get the maximum size of the work tree and index
				// and add some (quite arbitrary)
				if (estIndexSize == 0)
					estIndexSize = dirCache.getEntryCount();
				int total = Math.max(estIndexSize * 10 / 9,
						estWorkTreeSize * 10 / 9);
				monitor.beginTask(title, total);
				filters.add(new ProgressReportingFilter(monitor, total));
			}

			if (filter != null)
				filters.add(filter);
			indexDiffFilter = new IndexDiffFilter(INDEX, WORKDIR);
			filters.add(indexDiffFilter);
			treeWalk.setFilter(filters.size() == 1 ? indexDiffFilter
					: AndTreeFilter.create(filters));
			fileModes.clear();
			while (treeWalk.next()) {
				AbstractTreeIterator treeIterator = treeWalk.getTree(TREE,
						AbstractTreeIterator.class);
				DirCacheIterator dirCacheIterator = treeWalk.getTree(INDEX,
						DirCacheIterator.class);
				WorkingTreeIterator workingTreeIterator = treeWalk
						.getTree(WORKDIR, WorkingTreeIterator.class);

				if (dirCacheIterator != null) {
					final DirCacheEntry dirCacheEntry = dirCacheIterator
							.getDirCacheEntry();
					if (dirCacheEntry != null) {
						int stage = dirCacheEntry.getStage();
						if (stage > 0) {
							String path = treeWalk.getPathString();
							addConflict(path, stage);
							continue;
						}
					}
				}

				if (treeIterator != null) {
					if (dirCacheIterator != null) {
						if (!treeIterator.idEqual(dirCacheIterator)
								|| treeIterator
										.getEntryRawMode() != dirCacheIterator
												.getEntryRawMode()) {
							// in repo, in index, content diff => changed
							if (!isEntryGitLink(treeIterator)
									|| !isEntryGitLink(dirCacheIterator)
									|| ignoreSubmoduleMode != IgnoreSubmoduleMode.ALL)
								changed.add(treeWalk.getPathString());
						}
					} else {
						// in repo, not in index => removed
						if (!isEntryGitLink(treeIterator)
								|| ignoreSubmoduleMode != IgnoreSubmoduleMode.ALL)
							removed.add(treeWalk.getPathString());
						if (workingTreeIterator != null
								&& !workingTreeIterator.isEntryIgnored())
							untracked.add(treeWalk.getPathString());
					}
				} else {
					if (dirCacheIterator != null) {
						// not in repo, in index => added
						if (!isEntryGitLink(dirCacheIterator)
								|| ignoreSubmoduleMode != IgnoreSubmoduleMode.ALL)
							added.add(treeWalk.getPathString());
					} else {
						// not in repo, not in index => untracked
						if (workingTreeIterator != null
								&& !workingTreeIterator.isEntryIgnored()) {
							untracked.add(treeWalk.getPathString());
						}
					}
				}

				DirCacheEntry entry = dirCacheIterator == null ? null
						: dirCacheIterator.getDirCacheEntry();
				// Flags suppress only worktree comparisons. Staged changes and
				// the file-mode bookkeeping below still apply to these entries.
				if (dirCacheIterator != null && (entry == null
						|| !entry.isAssumeValid() && !entry.isSkipWorkTree())) {
					if (workingTreeIterator == null) {
						// in index, not in workdir => missing
						boolean isGitLink = isEntryGitLink(dirCacheIterator);
						if (!isGitLink
								|| ignoreSubmoduleMode != IgnoreSubmoduleMode.ALL) {
							String path = treeWalk.getPathString();
							missing.add(path);
							if (isGitLink) {
								missingSubmodules.add(path);
							}
						}
					} else {
						if (workingTreeIterator.isModified(
								dirCacheIterator.getDirCacheEntry(), true,
								treeWalk.getObjectReader())) {
							// in index, in workdir, content differs => modified
							if (!isEntryGitLink(dirCacheIterator)
									|| !isEntryGitLink(workingTreeIterator)
									|| (ignoreSubmoduleMode != IgnoreSubmoduleMode.ALL
											&& ignoreSubmoduleMode != IgnoreSubmoduleMode.DIRTY))
								modified.add(treeWalk.getPathString());
						}
					}
				}

				String path = treeWalk.getPathString();
				if (path != null) {
					for (int i = 0; i < treeWalk.getTreeCount(); i++) {
						recordFileMode(path, treeWalk.getFileMode(i));
					}
				}
			}
		}

		if (ignoreSubmoduleMode != IgnoreSubmoduleMode.ALL) {
			try (SubmoduleWalk smw = new SubmoduleWalk(repository)) {
				smw.setTree(new DirCacheIterator(dirCache));
				if (filter != null) {
					smw.setFilter(filter);
				}
				smw.setBuilderFactory(factory);
				while (smw.next()) {
					IgnoreSubmoduleMode localIgnoreSubmoduleMode = ignoreSubmoduleMode;
					try {
						if (localIgnoreSubmoduleMode == null)
							localIgnoreSubmoduleMode = smw.getModulesIgnore();
						if (IgnoreSubmoduleMode.ALL
								.equals(localIgnoreSubmoduleMode))
							continue;
					} catch (ConfigInvalidException e) {
						throw new IOException(MessageFormat.format(
								JGitText.get().invalidIgnoreParamSubmodule,
								smw.getPath()), e);
					}
					try (Repository subRepo = smw.getRepository()) {
						String subRepoPath = smw.getPath();
						if (subRepo != null) {
							ObjectId subHead = subRepo.resolve("HEAD"); //$NON-NLS-1$
							if (subHead != null
									&& !subHead.equals(smw.getObjectId())) {
								modified.add(subRepoPath);
								recordFileMode(subRepoPath, FileMode.GITLINK);
							} else if (localIgnoreSubmoduleMode != IgnoreSubmoduleMode.DIRTY) {
								IndexDiff smid = submoduleIndexDiffs
										.get(smw.getPath());
								if (smid == null) {
									smid = new IndexDiff(subRepo,
											smw.getObjectId(),
											wTreeIt.getWorkingTreeIterator(
													subRepo));
									submoduleIndexDiffs.put(subRepoPath, smid);
								}
								if (smid.diff(factory)) {
									if (localIgnoreSubmoduleMode == IgnoreSubmoduleMode.UNTRACKED
											&& smid.getAdded().isEmpty()
											&& smid.getChanged().isEmpty()
											&& smid.getConflicting().isEmpty()
											&& smid.getMissing().isEmpty()
											&& smid.getModified().isEmpty()
											&& smid.getRemoved().isEmpty()) {
										continue;
									}
									modified.add(subRepoPath);
									recordFileMode(subRepoPath,
											FileMode.GITLINK);
								}
							}
						} else if (missingSubmodules.remove(subRepoPath)) {
							// If the directory is there and empty but the
							// submodule repository in .git/modules doesn't
							// exist yet it isn't "missing".
							File gitDir = new File(
									new File(repository.getCommonDirectory(),
											Constants.MODULES),
									subRepoPath);
							if (!gitDir.isDirectory()) {
								File dir = SubmoduleWalk.getSubmoduleDirectory(
										repository, subRepoPath);
								if (dir.isDirectory() && !hasFiles(dir)) {
									missing.remove(subRepoPath);
								}
							}
						}
					}
				}
			}

		}

		// consume the remaining work
		if (monitor != null) {
			monitor.endTask();
		}

		if (filter == null && initialWorkingTreeIterator instanceof FileTreeIterator) {
			((FileTreeIterator) initialWorkingTreeIterator).finishUntrackedCache();
		}
		ignored = reportIgnored ? indexDiffFilter.getIgnoredPaths() : java.util.Set.of();
		completeScan = monitor == null || !monitor.isCancelled();
		if (added.isEmpty() && changed.isEmpty() && removed.isEmpty()
				&& missing.isEmpty() && modified.isEmpty()
				&& untracked.isEmpty()) {
			return false;
		}
		return true;
	}

	private boolean hasFiles(File directory) {
		try (DirectoryStream<java.nio.file.Path> dir = Files
				.newDirectoryStream(directory.toPath())) {
			return dir.iterator().hasNext();
		} catch (DirectoryIteratorException | IOException e) {
			return false;
		}
	}

	private void recordFileMode(String path, FileMode mode) {
		Set<String> values = fileModes.get(mode);
		if (path != null) {
			if (values == null) {
				values = new HashSet<>();
				fileModes.put(mode, values);
			}
			values.add(path);
		}
	}

	private boolean isEntryGitLink(AbstractTreeIterator ti) {
		return ((ti != null) && (ti.getEntryRawMode() == FileMode.GITLINK
				.getBits()));
	}

	private void addConflict(String path, int stage) {
		StageState existingStageStates = conflicts.get(path);
		byte stageMask = 0;
		if (existingStageStates != null) {
			stageMask |= (byte) existingStageStates.getStageMask();
		}
		// stage 1 (base) should be shifted 0 times
		int shifts = stage - 1;
		stageMask |= (byte) (1 << shifts);
		StageState stageState = StageState.fromMask(stageMask);
		conflicts.put(path, stageState);
	}

	/**
	 * Get list of files added to the index, not in the tree
	 *
	 * @return list of files added to the index, not in the tree
	 */
	public Set<String> getAdded() {
		return added;
	}

	/**
	 * Get list of files changed from tree to index
	 *
	 * @return list of files changed from tree to index
	 */
	public Set<String> getChanged() {
		return changed;
	}

	/**
	 * Get list of files removed from index, but in tree
	 *
	 * @return list of files removed from index, but in tree
	 */
	public Set<String> getRemoved() {
		return removed;
	}

	/**
	 * Get list of files in index, but not filesystem
	 *
	 * @return list of files in index, but not filesystem
	 */
	public Set<String> getMissing() {
		return missing;
	}

	/**
	 * Get list of files modified on disk relative to the index
	 *
	 * @return list of files modified on disk relative to the index
	 */
	public Set<String> getModified() {
		return modified;
	}

	/**
	 * Get list of files that are not ignored, and not in the index.
	 *
	 * @return list of files that are not ignored, and not in the index.
	 */
	public Set<String> getUntracked() {
		return untracked;
	}

	/**
	 * Get list of files that are in conflict, corresponds to the keys of
	 * {@link #getConflictingStageStates()}
	 *
	 * @return list of files that are in conflict, corresponds to the keys of
	 *         {@link #getConflictingStageStates()}
	 */
	public Set<String> getConflicting() {
		return conflicts.keySet();
	}

	/**
	 * Get the map from each path of {@link #getConflicting()} to its
	 * corresponding {@link org.eclipse.jgit.lib.IndexDiff.StageState}
	 *
	 * @return the map from each path of {@link #getConflicting()} to its
	 *         corresponding {@link org.eclipse.jgit.lib.IndexDiff.StageState}
	 * @since 3.0
	 */
	public Map<String, StageState> getConflictingStageStates() {
		return conflicts;
	}

	/**
	 * The method returns the list of ignored files and folders. Only the root
	 * folder of an ignored folder hierarchy is reported. If a/b/c is listed in
	 * the .gitignore then you should not expect a/b/c/d/e/f to be reported
	 * here. Only a/b/c will be reported. Furthermore only ignored files /
	 * folders are returned that are NOT in the index.
	 *
	 * @return list of files / folders that are ignored
	 */
	public Set<String> getIgnoredNotInIndex() {
		return ignored;
	}

	/**
	 * Get list of files with the flag assume-unchanged
	 *
	 * @return list of files with the flag assume-unchanged
	 */
	public Set<String> getAssumeUnchanged() {
		if (assumeUnchanged == null) {
			HashSet<String> unchanged = new HashSet<>();
			for (int i = 0; i < dirCache.getEntryCount(); i++)
				if (dirCache.getEntry(i).isAssumeValid())
					unchanged.add(dirCache.getEntry(i).getPathString());
			assumeUnchanged = unchanged;
		}
		return assumeUnchanged;
	}

	/**
	 * Get list of folders containing only untracked files/folders
	 *
	 * @return list of folders containing only untracked files/folders
	 */
	public Set<String> getUntrackedFolders() {
		return ((indexDiffFilter == null) ? Collections.<String> emptySet()
				: new HashSet<>(indexDiffFilter.getUntrackedFolders()));
	}

	/**
	 * Get the file mode of the given path in the index
	 *
	 * @param path a {@link java.lang.String} object.
	 * @return file mode
	 */
	public FileMode getIndexMode(String path) {
		final DirCacheEntry entry = dirCache.getEntry(path);
		return entry != null ? entry.getFileMode() : FileMode.MISSING;
	}

	/**
	 * Get the list of paths that IndexDiff has detected to differ and have the
	 * given file mode
	 *
	 * @param mode a {@link org.eclipse.jgit.lib.FileMode} object.
	 * @return the list of paths that IndexDiff has detected to differ and have
	 *         the given file mode
	 * @since 3.6
	 */
	public Set<String> getPathsWithIndexMode(FileMode mode) {
		Set<String> paths = fileModes.get(mode);
		if (paths == null)
			paths = new HashSet<>();
		return paths;
	}
}
