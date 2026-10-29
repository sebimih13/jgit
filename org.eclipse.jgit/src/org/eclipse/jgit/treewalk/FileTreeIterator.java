/*
 * Copyright (C) 2008, Google Inc.
 * Copyright (C) 2007-2010, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org>
 * Copyright (C) 2009, Tor Arne Vestbø <torarnv@gmail.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.treewalk;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FsMonitor;
import org.eclipse.jgit.util.FS_POSIX;

/**
 * Working directory iterator for standard Java IO.
 * <p>
 * This iterator uses the standard <code>java.io</code> package to read the
 * specified working directory as part of a
 * {@link org.eclipse.jgit.treewalk.TreeWalk}.
 */
public class FileTreeIterator extends WorkingTreeIterator {

	/**
	 * the starting directory of this Iterator. All entries are located directly
	 * in this directory.
	 */
	protected final File directory;

	private IndexPreloader preloaded;

	private IndexPreloader monitorMetadata;

	private Set<DirCacheEntry> monitorEntries = Set.of();

	private UntrackedDirectoryListing untrackedListing;

	private UntrackedCacheScan untrackedScan;

	/**
	 * the file system abstraction which will be necessary to perform certain
	 * file system operations.
	 */
	protected final FS fs;

	/**
	 * the strategy used to compute the FileMode for a FileEntry. Can be used to
	 * control things such as whether to recurse into a directory or create a
	 * gitlink.
	 *
	 * @since 4.3
	 */
	protected final FileModeStrategy fileModeStrategy;

	/**
	 * Create a new iterator to traverse the work tree and its children.
	 *
	 * @param repo
	 *            the repository whose working tree will be scanned.
	 */
	public FileTreeIterator(Repository repo) {
		this(repo, defaultStrategy(repo));
	}

	private static FileModeStrategy defaultStrategy(Repository repo) {
		return repo.getConfig().get(WorkingTreeOptions.KEY).isDirNoGitLinks()
				? NoGitlinksStrategy.INSTANCE : DefaultFileModeStrategy.INSTANCE;
	}

	/**
	 * Create a walk optionally recording native cache-maintenance inputs.
	 * Recording alone does not write the index.
	 * @param repo repository to scan
	 * @param recordUntrackedCache whether to capture a complete traversal
	 * @since 7.8
	 */
	public FileTreeIterator(Repository repo, boolean recordUntrackedCache) {
		this(repo, defaultStrategy(repo), recordUntrackedCache);
	}

	/**
	 * Create a new iterator to traverse the work tree and its children.
	 *
	 * @param repo
	 *            the repository whose working tree will be scanned.
	 * @param fileModeStrategy
	 *            the strategy to use to determine the FileMode for a FileEntry;
	 *            controls gitlinks etc.
	 * @since 4.3
	 */
	public FileTreeIterator(Repository repo, FileModeStrategy fileModeStrategy) {
		this(repo, fileModeStrategy, false);
	}

	private FileTreeIterator(Repository repo, FileModeStrategy strategy, boolean record) {
		super(repo.getConfig().get(WorkingTreeOptions.KEY));
		directory = repo.getWorkTree();
		fs = repo.getFS();
		fileModeStrategy = strategy;
		untrackedScan = record && getClass() == FileTreeIterator.class
				? UntrackedCacheScan.create(repo) : null;
		init(entries());
		initRootIterator(repo);
	}

	/**
	 * Create a new iterator to traverse the given directory and its children.
	 *
	 * @param root
	 *            the starting directory. This directory should correspond to
	 *            the root of the repository.
	 * @param fs
	 *            the file system abstraction which will be necessary to perform
	 *            certain file system operations.
	 * @param options
	 *            working tree options to be used
	 */
	public FileTreeIterator(File root, FS fs, WorkingTreeOptions options) {
		this(root, fs, options, DefaultFileModeStrategy.INSTANCE);
	}

	/**
	 * Create a new iterator to traverse the given directory and its children.
	 *
	 * @param root
	 *            the starting directory. This directory should correspond to
	 *            the root of the repository.
	 * @param fs
	 *            the file system abstraction which will be necessary to perform
	 *            certain file system operations.
	 * @param options
	 *            working tree options to be used
	 * @param fileModeStrategy
	 *            the strategy to use to determine the FileMode for a FileEntry;
	 *            controls gitlinks etc.
	 * @since 4.3
	 */
	public FileTreeIterator(final File root, FS fs, WorkingTreeOptions options,
							FileModeStrategy fileModeStrategy) {
		super(options);
		directory = root;
		this.fs = fs;
		this.fileModeStrategy = fileModeStrategy;
		init(entries());
	}

	/**
	 * Create a new iterator to traverse a subdirectory.
	 *
	 * @param p
	 *            the parent iterator we were created from.
	 * @param root
	 *            the subdirectory. This should be a directory contained within
	 *            the parent directory.
	 * @param fs
	 *            the file system abstraction which will be necessary to perform
	 *            certain file system operations.
	 * @since 4.3
	 */
	protected FileTreeIterator(final FileTreeIterator p, final File root,
			FS fs) {
		this(p, root, fs, p.fileModeStrategy);
	}

	/**
	 * Create a new iterator to traverse a subdirectory, given the specified
	 * FileModeStrategy.
	 *
	 * @param p
	 *            the parent iterator we were created from.
	 * @param root
	 *            the subdirectory. This should be a directory contained within
	 *            the parent directory
	 * @param fs
	 *            the file system abstraction which will be necessary to perform
	 *            certain file system operations.
	 * @param fileModeStrategy
	 *            the strategy to use to determine the FileMode for a given
	 *            FileEntry.
	 * @since 4.3
	 */
	protected FileTreeIterator(final WorkingTreeIterator p, final File root,
			FS fs, FileModeStrategy fileModeStrategy) {
		super(p);
		directory = root;
		this.fs = fs;
		this.fileModeStrategy = fileModeStrategy;
		if (p instanceof FileTreeIterator) {
			preloaded = ((FileTreeIterator) p).preloaded;
			untrackedListing = ((FileTreeIterator) p).untrackedListing;
			untrackedScan = ((FileTreeIterator) p).untrackedScan;
		}
		init(entries());
	}

	@Override
	public AbstractTreeIterator createSubtreeIterator(ObjectReader reader)
			throws IncorrectObjectTypeException, IOException {
		// An associated index iterator requires traversal regardless of ignore
		// rules. Resolve that before loading or matching the ignore rules.
		if (!walksIgnoredDirectories() && getDirCacheIterator() == null
				&& isEntryIgnored()) {
			return new EmptyTreeIterator(this);
		}
		return enterSubtree();
	}


	/**
	 * Create a new iterator for the current entry's subtree.
	 * <p>
	 * The parent reference of the iterator must be <code>this</code>, otherwise
	 * the caller would not be able to exit out of the subtree iterator
	 * correctly and return to continue walking <code>this</code>.
	 *
	 * @return a new iterator that walks over the current subtree.
	 * @since 5.0
	 */
	protected AbstractTreeIterator enterSubtree() {
		return new FileTreeIterator(this, ((FileEntry) current()).getFile(), fs,
				fileModeStrategy);
	}

	/**
	 * Validate monitor state before checkout metadata/content comparisons, then
	 * optionally preload entries that still require filesystem metadata.
	 * @param index index owned by this checkout
	 * @param monitor cancellation monitor, or null
	 * @throws IOException on invalid provider paths
	 * @since 7.8
	 */
	public void prepareCheckout(DirCache index, ProgressMonitor monitor) throws IOException {
		Repository repository = getRepository();
		if (getClass() == FileTreeIterator.class && repository != null) {
			var query = org.eclipse.jgit.util.FsMonitor.queryIndexForCheckout(repository, index);
			query.invalidateUntrackedCache(repository, index);
			Set<DirCacheEntry> trusted = query.getCheckoutValidEntries(repository, index);
			Set<DirCacheEntry> dirty = new java.util.HashSet<>();
			if (org.eclipse.jgit.util.FsMonitor.isConfigured(repository)) {
				for (int i = 0; i < index.getEntryCount(); i++) {
					DirCacheEntry entry = index.getEntry(i);
					if (entry.getStage() == 0 && !entry.isAssumeValid()
							&& !entry.isSkipWorkTree() && entry.getFileMode() != FileMode.GITLINK
							&& !trusted.contains(entry)) dirty.add(entry);
				}
			}
			configureCheckoutMonitor(trusted, dirty);
		}
		preloadIndex(index, monitor);
	}

	/**
	 * Preload tracked metadata for this walk when core.preloadIndex is
	 * enabled (the default). The index is not modified. Metadata is discarded with
	 * this walk; existing content and racy-timestamp checks still apply.
	 *
	 * @param index
	 *            already loaded index
	 * @param monitor
	 *            cancellation monitor, or null
	 * @since 7.8
	 */
	public void preloadIndex(DirCache index, ProgressMonitor monitor) {
		preloadIndex(index, monitor, TreeFilter.ALL);
	}

	/**
	 * Preload tracked metadata within the supplied filter's available path bounds.
	 * Filters without path bounds retain the ordinary walk. The filter itself
	 * is never evaluated by worker threads.
	 * @param index already loaded index
	 * @param monitor cancellation monitor, or null
	 * @param filter path filter, or null for the whole index
	 * @since 7.8
	 */
	public void preloadIndex(DirCache index, ProgressMonitor monitor, TreeFilter filter) {
		preloaded = monitorMetadata;
		Repository repo = getRepository();
		// Preserve custom FS.list implementations and Win32 enumeration,
		// which already supplies file attributes.
		if (repo != null && fs.getClass() == FS_POSIX.class && repo.getConfig()
				.getBoolean("core", null, "preloadIndex", true)) { //$NON-NLS-1$ //$NON-NLS-2$
			preloaded = IndexPreloader.load(repo, fs, index, monitor, monitorMetadata, monitorEntries, filter);
		}
	}

	@Override
	public void setFsMonitorValidEntries(Collection<DirCacheEntry> entries) {
		monitorEntries = snapshotMonitorEntries(entries);
		super.setFsMonitorValidEntries(monitorEntries);
		monitorMetadata = !entries.isEmpty() && getClass() == FileTreeIterator.class
				&& fs.getClass() == FS_POSIX.class && getRepository() != null
				? IndexPreloader.fromMonitor(getRepository(), fs, entries) : null;
		preloaded = monitorMetadata;
	}

	private static Set<DirCacheEntry> snapshotMonitorEntries(
			Collection<DirCacheEntry> entries) {
		if (entries instanceof Set<?>) {
			DirCacheEntry[] snapshot = entries.toArray(new DirCacheEntry[0]);
			for (DirCacheEntry entry : snapshot) {
				if (entry.getClass() != DirCacheEntry.class) {
					// Subclasses may define value equality, including in an
					// identity-based Set. Preserve general copy/dedup semantics.
					return Set.copyOf(entries);
				}
			}
			// Canonical entries use identity equality and are already unique.
			// Take an immutable copy without a temporary deduplicating HashSet.
			return Set.of(snapshot);
		}
		return Set.copyOf(entries);
	}

	/**
	 * Reuse complete Java directory listings when native UNTR stat data and a
	 * prior listing validate them. Entry metadata and ignore rules stay fresh.
	 * The already constructed root listing is not replaced. No index is written.
	 *
	 * @param index validated index for this walk
	 * @since 7.8
	 */
	public void setUntrackedCache(DirCache index) {
		setUntrackedCache(index, true);
	}

	/**
	 * Configure complete-name or native untracked-name reuse for this walk.
	 * @param index validated index
	 * @param reportIgnored whether complete ignored-path reporting is required
	 * @since 7.8
	 */
	public void setUntrackedCache(DirCache index, boolean reportIgnored) {
		if (untrackedScan != null && !untrackedScan.acceptsIndex(index)) {
			untrackedScan = null;
		}
		untrackedListing = getClass() == FileTreeIterator.class
				? UntrackedDirectoryListing.create(getRepository(), index, reportIgnored) : null;
	}

	/**
	 * Attempt to publish a completed recorded traversal without replacing a
	 * newer index. Ordinary iterators do not record or publish cache state.
	 * @param index index used by the scan
	 * @param untracked complete untracked path set
	 * @param monitor cancellation monitor, or null
	 * @return whether an updated index was committed
	 * @since 7.8
	 */
	public boolean updateUntrackedCache(DirCache index, Set<String> untracked,
			ProgressMonitor monitor) {
		return updateUntrackedCache(index, untracked, Set.of(), monitor);
	}

	/**
	 * Publish a complete scan and its verified clean tracked paths. Later
	 * writer notifications keep affected entries dirty despite this verification.
	 * @param index index used by the scan
	 * @param untracked complete untracked path set
	 * @param verified clean tracked paths verified against this index
	 * @param monitor cancellation monitor, or null
	 * @return whether an updated index was committed
	 * @since 7.8
	 */
	public boolean updateUntrackedCache(DirCache index, Set<String> untracked,
			Set<String> verified, ProgressMonitor monitor) {
		return updateUntrackedCache(index, untracked, verified, monitor, null);
	}

	/**
	 * Publish a complete scan using its operation-local monitor boundary.
	 * @param index index used by the scan
	 * @param untracked complete untracked path set
	 * @param verified clean tracked paths verified by the scan
	 * @param monitor cancellation monitor, or null
	 * @param scan monitor query preceding the scan, or null
	 * @return whether an updated index was committed
	 * @since 7.8
	 */
	public boolean updateUntrackedCache(DirCache index, Set<String> untracked,
			Set<String> verified, ProgressMonitor monitor, FsMonitor.Probe scan) {
		return updateUntrackedCache(index, untracked, verified, monitor, scan, null);
	}

	/**
	 * Publish a scan, tagging only this operation's index event.
	 * @param index index used by the scan
	 * @param untracked complete untracked path set
	 * @param verified clean tracked paths verified by the scan
	 * @param monitor cancellation monitor, or null
	 * @param scan query preceding the scan, or null
	 * @param operationToken opaque event identity, or null
	 * @return whether the optional index update was committed
	 * @since 7.8
	 */
	public boolean updateUntrackedCache(DirCache index, Set<String> untracked,
			Set<String> verified, ProgressMonitor monitor, FsMonitor.Probe scan,
			Object operationToken) {
		return untrackedScan != null && untrackedScan.publish(index, untracked, verified,
				monitor, scan, operationToken);
	}

	/**
	 * Get correlation and late notifications from the last committed publication.
	 * @return outcome, or null if no optional update was committed
	 * @since 7.8
	 */
	public FsMonitor.Publication getFsMonitorPublication() {
		return untrackedScan == null ? null : untrackedScan.getPublication();
	}

	/**
	 * Finish a successful full walk, retaining only a negative optimization
	 * hint when none of the native directory records could be reused.
	 * No index or filesystem data is written.
	 *
	 * @since 7.8
	 */
	public void finishUntrackedCache() {
		if (untrackedListing != null) {
			untrackedListing.finish();
		}
	}

	private Entry[] entries() {
		// Keep custom enumeration and Win32's attribute-aware listing intact.
		if (fs.getClass() != FS_POSIX.class) {
			return fs.list(directory, fileModeStrategy);
		}
		File[] files = untrackedScan != null ? untrackedScan.list(directory, untrackedListing)
				: untrackedListing == null ? directory.listFiles()
				: untrackedListing.list(directory);
		if (files == null) {
			return new Entry[0];
		}
		Map<String, FS.Attributes> attributes = preloaded == null ? null
				: preloaded.takeDirectory(directory, files);
		FS.Attributes[] parallel = fs.getClass() == FS_POSIX.class
				? DirectoryMetadata.read(fs, files, attributes) : null;
		Entry[] result = new Entry[files.length];
		int count = 0;
		boolean nativeListing = untrackedListing != null && untrackedListing.isNativeListing();
		for (int i = 0; i < files.length; i++) {
			File file = files[i];
			FS.Attributes cached = parallel != null ? parallel[i]
					: attributes == null ? null
							: attributes.get(fs.normalize(file).getName());
			if (nativeListing) {
				if (cached == null) {
					cached = fs.getAttributes(fs.normalize(file));
				}
				// The native cache describes untracked names; index-derived
				// candidates can already have been missing at capture time.
				if (!cached.exists()) {
					continue;
				}
			}
			FileEntry entry = cached == null
					? new FileEntry(file, fs, fileModeStrategy)
					: new FileEntry(file, fs, cached, fileModeStrategy);
			result[count++] = entry;
			if (untrackedScan != null) {
				untrackedScan.entry(file, entry.getMode() == FileMode.TREE
						|| entry.getMode() == FileMode.GITLINK, entry.attributes.exists());
			}
		}
		return count == result.length ? result : java.util.Arrays.copyOf(result, count);
	}

	/**
	 * An interface representing the methods used to determine the FileMode for
	 * a FileEntry.
	 *
	 * @since 4.3
	 */
	public interface FileModeStrategy {
		/**
		 * Compute the FileMode for a given File, based on its attributes.
		 *
		 * @param f
		 *            the file to return a FileMode for
		 * @param attributes
		 *            the attributes of a file
		 * @return a FileMode indicating whether the file is a regular file, a
		 *         directory, a gitlink, etc.
		 */
		FileMode getMode(File f, FS.Attributes attributes);
	}

	/**
	 * A default implementation of a FileModeStrategy; defaults to treating
	 * nested .git directories as gitlinks, etc.
	 *
	 * @since 4.3
	 */
	public static class DefaultFileModeStrategy implements FileModeStrategy {
		/**
		 * a singleton instance of the default FileModeStrategy
		 */
		public static final DefaultFileModeStrategy INSTANCE =
				new DefaultFileModeStrategy();

		@Override
		public FileMode getMode(File f, FS.Attributes attributes) {
			if (attributes.isSymbolicLink()) {
				return FileMode.SYMLINK;
			} else if (attributes.isDirectory()) {
				if (new File(f, Constants.DOT_GIT).exists()) {
					return FileMode.GITLINK;
				}
				return FileMode.TREE;
			} else if (attributes.isExecutable()) {
				return FileMode.EXECUTABLE_FILE;
			} else {
				return FileMode.REGULAR_FILE;
			}
		}
	}

	/**
	 * A FileModeStrategy that implements native git's DIR_NO_GITLINKS
	 * behavior. This is the same as the default FileModeStrategy, except
	 * all directories will be treated as directories regardless of whether
	 * or not they contain a .git directory or file.
	 *
	 * @since 4.3
	 */
	public static class NoGitlinksStrategy implements FileModeStrategy {

		/**
		 * a singleton instance of the default FileModeStrategy
		 */
		public static final NoGitlinksStrategy INSTANCE = new NoGitlinksStrategy();

		@Override
		public FileMode getMode(File f, FS.Attributes attributes) {
			if (attributes.isSymbolicLink()) {
				return FileMode.SYMLINK;
			} else if (attributes.isDirectory()) {
				return FileMode.TREE;
			} else if (attributes.isExecutable()) {
				return FileMode.EXECUTABLE_FILE;
			} else {
				return FileMode.REGULAR_FILE;
			}
		}
	}


	/**
	 * Wrapper for a standard Java IO file
	 */
	public static class FileEntry extends Entry {
		private final FileMode mode;

		private FS.Attributes attributes;

		private FS fs;

		/**
		 * Create a new file entry.
		 *
		 * @param f
		 *            file
		 * @param fs
		 *            file system
		 */
		public FileEntry(File f, FS fs) {
			this(f, fs, DefaultFileModeStrategy.INSTANCE);
		}

		/**
		 * Create a new file entry given the specified FileModeStrategy
		 *
		 * @param f
		 *            file
		 * @param fs
		 *            file system
		 * @param fileModeStrategy
		 *            the strategy to use when determining the FileMode of a
		 *            file; controls gitlinks etc.
		 *
		 * @since 4.3
		 */
		public FileEntry(File f, FS fs, FileModeStrategy fileModeStrategy) {
			this.fs = fs;
			f = fs.normalize(f);
			attributes = fs.getAttributes(f);
			mode = fileModeStrategy.getMode(f, attributes);
		}

		/**
		 * Create a new file entry given the specified FileModeStrategy
		 *
		 * @param f
		 *            file
		 * @param fs
		 *            file system
		 * @param attributes
		 *            of the file
		 * @param fileModeStrategy
		 *            the strategy to use when determining the FileMode of a
		 *            file; controls gitlinks etc.
		 *
		 * @since 5.0
		 */
		public FileEntry(File f, FS fs, FS.Attributes attributes,
				FileModeStrategy fileModeStrategy) {
			this.fs = fs;
			this.attributes = attributes;
			f = fs.normalize(f);
			mode = fileModeStrategy.getMode(f, attributes);
		}

		@Override
		public FileMode getMode() {
			return mode;
		}

		@Override
		public String getName() {
			return attributes.getName();
		}

		@Override
		public long getLength() {
			return attributes.getLength();
		}

		/**
		 * @since 5.1.9
		 */
		@Override
		public Instant getLastModifiedInstant() {
			return attributes.getLastModifiedInstant();
		}

		@Override
		public InputStream openInputStream() throws IOException {
			if (attributes.isSymbolicLink()) {
				return new ByteArrayInputStream(fs.readSymLink(getFile())
						.getBytes(UTF_8));
			}
			return new FileInputStream(getFile());
		}

		/**
		 * Get the underlying file of this entry.
		 *
		 * @return the underlying file of this entry
		 */
		public File getFile() {
			return attributes.getFile();
		}
	}

	/**
	 * <p>Getter for the field <code>directory</code>.</p>
	 *
	 * @return The root directory of this iterator
	 */
	public File getDirectory() {
		return directory;
	}

	/**
	 * Get the location of the working file.
	 *
	 * @return The location of the working file. This is the same as {@code new
	 *         File(getDirectory(), getEntryPath())} but may be faster by
	 *         reusing an internal File instance.
	 */
	public File getEntryFile() {
		return ((FileEntry) current()).getFile();
	}

	@Override
	protected byte[] idSubmodule(Entry e) {
		return idSubmodule(getDirectory(), e);
	}

	@Override
	protected String readSymlinkTarget(Entry entry) throws IOException {
		return fs.readSymLink(getEntryFile());
	}
}
