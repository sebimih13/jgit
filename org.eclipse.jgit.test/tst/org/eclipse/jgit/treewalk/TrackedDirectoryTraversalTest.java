/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.treewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.IndexDiff;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.junit.Test;

public class TrackedDirectoryTraversalTest extends RepositoryTestCase {
	private void commitFiles(String... paths) throws Exception {
		for (String path : paths) {
			writeTrashFile(path, "base");
		}
		try (Git git = new Git(db)) {
			git.add().addFilepattern(".").call();
			git.commit().setMessage("base").call();
		}
	}

	private final class CountingIterator extends FileTreeIterator {
		final List<String> ignoreChecks;

		CountingIterator(Repository repository) {
			super(repository);
			ignoreChecks = new ArrayList<>();
		}

		CountingIterator(CountingIterator parent, File directory) {
			super(parent, directory, db.getFS());
			ignoreChecks = parent.ignoreChecks;
		}

		@Override
		public boolean isEntryIgnored() throws IOException {
			ignoreChecks.add(getEntryPathString());
			return super.isEntryIgnored();
		}

		@Override
		protected AbstractTreeIterator enterSubtree() {
			return new CountingIterator(this, getEntryFile());
		}
	}

	@Test
	public void trackedDirectoriesAvoidIgnoreChecksButKeepUntrackedChildren()
			throws Exception {
		commitFiles("tracked/deep/file");
		writeTrashFile(".gitignore", "*.ignored\n");
		writeTrashFile("tracked/new", "new");
		writeTrashFile("tracked/new-directory/file", "new");
		writeTrashFile("tracked/hidden.ignored", "hidden");
		CountingIterator iterator = new CountingIterator(db);
		IndexDiff diff = new IndexDiff(db, Constants.HEAD, iterator);
		assertTrue(diff.diff());
		assertEquals(Set.of(".gitignore", "tracked/new",
				"tracked/new-directory/file"), diff.getUntracked());
		assertEquals(Set.of("tracked/new-directory"),
				diff.getUntrackedFolders());
		assertEquals(Set.of("tracked/hidden.ignored"),
				diff.getIgnoredNotInIndex());
		assertFalse(iterator.ignoreChecks.contains("tracked"));
		assertFalse(iterator.ignoreChecks.contains("tracked/deep"));
		assertTrue(iterator.ignoreChecks.contains("tracked/new"));
	}

	@Test
	public void ignoredIndexedDirectoriesStillReportTrackedModifications()
			throws Exception {
		commitFiles("tracked/deep/file");
		writeTrashFile(".gitignore", "tracked/\n");
		writeTrashFile("tracked/deep/file", "modified content");
		writeTrashFile("tracked/hidden", "hidden");
		CountingIterator iterator = new CountingIterator(db);
		IndexDiff diff = new IndexDiff(db, Constants.HEAD, iterator);
		assertTrue(diff.diff());
		assertEquals(Set.of("tracked/deep/file"), diff.getModified());
		assertEquals(Set.of(".gitignore"), diff.getUntracked());
		assertEquals(Set.of("tracked/hidden"), diff.getIgnoredNotInIndex());
		assertTrue(diff.getUntrackedFolders().isEmpty());
		assertFalse(iterator.ignoreChecks.contains("tracked"));
		assertFalse(iterator.ignoreChecks.contains("tracked/deep"));
	}

	@Test
	public void unindexedIgnoredDirectoriesAreStillPruned() throws Exception {
		commitFiles("tracked/file");
		writeTrashFile(".gitignore", "hidden/\n");
		writeTrashFile("hidden/child", "hidden");
		writeTrashFile("visible/child", "new");
		CountingIterator iterator = new CountingIterator(db);
		IndexDiff diff = new IndexDiff(db, Constants.HEAD, iterator);
		assertTrue(diff.diff());
		assertEquals(Set.of(".gitignore", "visible/child"),
				diff.getUntracked());
		assertEquals(Set.of("visible"), diff.getUntrackedFolders());
		assertEquals(Set.of("hidden"), diff.getIgnoredNotInIndex());
		assertTrue(iterator.ignoreChecks.contains("hidden"));
		assertFalse(iterator.ignoreChecks.contains("hidden/child"));
	}

	@Test
	public void indexFileReplacedByIgnoredDirectoryRetainsMissingAndIgnoreResults()
			throws Exception {
		commitFiles("shape");
		assertTrue(new File(db.getWorkTree(), "shape").delete());
		writeTrashFile("shape/child", "new");
		writeTrashFile(".gitignore", "shape/\n");
		CountingIterator iterator = new CountingIterator(db);
		IndexDiff diff = new IndexDiff(db, Constants.HEAD, iterator);
		assertTrue(diff.diff());
		assertEquals(Set.of("shape"), diff.getMissing());
		assertFalse(diff.getUntrackedFolders().contains("shape"));
		assertTrue(iterator.ignoreChecks.contains("shape"));
		assertEquals(Set.of(".gitignore"), diff.getUntracked());
	}

	@Test
	public void directoryOnlyInHeadDoesNotBypassIgnoreRules() throws Exception {
		commitFiles("tracked/file");
		try (Git git = new Git(db)) {
			git.rm().setCached(true).addFilepattern("tracked/file").call();
		}
		writeTrashFile(".gitignore", "tracked/\n");
		CountingIterator iterator = new CountingIterator(db);
		IndexDiff diff = new IndexDiff(db, Constants.HEAD, iterator);
		assertTrue(diff.diff());
		assertEquals(Set.of("tracked/file"), diff.getRemoved());
		assertEquals(Set.of("tracked"), diff.getIgnoredNotInIndex());
		assertFalse(diff.getUntrackedFolders().contains("tracked"));
		assertTrue(iterator.ignoreChecks.contains("tracked"));
	}

	@Test
	public void pathFilteredTrackedDescendantsStillTraverseIgnoredParents()
			throws Exception {
		commitFiles("tracked/deep/file", "tracked/sibling");
		writeTrashFile(".gitignore", "tracked/\n");
		writeTrashFile("tracked/deep/file", "modified content");
		CountingIterator iterator = new CountingIterator(db);
		IndexDiff diff = new IndexDiff(db, Constants.HEAD, iterator);
		diff.setFilter(PathFilter.create("tracked/deep/file"));
		assertTrue(diff.diff());
		assertEquals(Set.of("tracked/deep/file"), diff.getModified());
		assertTrue(diff.getUntracked().isEmpty());
		assertFalse(iterator.ignoreChecks.contains("tracked"));
	}

	@Test
	public void conflictsUnderIgnoredIndexedDirectoryAreStillReported()
			throws Exception {
		commitFiles("tracked/file");
		ObjectId blob = db.readDirCache().getEntry("tracked/file")
				.getObjectId();
		DirCache cache = db.lockDirCache();
		try {
			DirCacheBuilder builder = cache.builder();
			for (int stage = 1; stage <= 3; stage++) {
				DirCacheEntry entry = new DirCacheEntry("tracked/file", stage);
				entry.setFileMode(FileMode.REGULAR_FILE);
				entry.setObjectId(blob);
				builder.add(entry);
			}
			builder.finish();
			cache.write();
			assertTrue(cache.commit());
		} finally {
			cache.unlock();
		}
		writeTrashFile(".gitignore", "tracked/\n");
		CountingIterator iterator = new CountingIterator(db);
		IndexDiff diff = new IndexDiff(db, Constants.HEAD, iterator);
		assertTrue(diff.diff());
		assertEquals(Set.of("tracked/file"), diff.getConflicting());
		assertEquals(IndexDiff.StageState.BOTH_MODIFIED,
				diff.getConflictingStageStates().get("tracked/file"));
		assertFalse(iterator.ignoreChecks.contains("tracked"));
	}
}
