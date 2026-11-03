/*
 * Copyright (C) 2026, and others
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.junit.NativeSparseUntrackedSupport;
import org.eclipse.jgit.junit.NativeSparseUntrackedSupport.Snapshot;
import org.eclipse.jgit.junit.NativeUntrackedNormalSupport;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Native t7063 sparse ignored-file, directory and membership transitions. */
@RunWith(Parameterized.class)
public class SparseUntrackedCacheStatusTest extends RepositoryTestCase {
	@Parameterized.Parameters(name = "cache={0}, preload={1}, sparseConfig={2}")
	public static List<Object[]> scenarios() {
		List<Object[]> result = new ArrayList<>();
		for (boolean cache : new boolean[] { false, true })
			for (boolean preload : new boolean[] { false, true })
				for (boolean sparse : new boolean[] { false, true })
					result.add(new Object[] { cache, preload, sparse });
		return result;
	}

	private final boolean cache;

	private final boolean preload;

	private final boolean sparse;

	public SparseUntrackedCacheStatusTest(boolean cache, boolean preload,
			boolean sparse) {
		this.cache = cache;
		this.preload = preload;
		this.sparse = sparse;
	}

	private void prepare() throws Exception {
		NativeSparseUntrackedSupport.prepare(db, cache, preload, sparse);
	}

	@Test
	public void sparseRulesNestedPathsAndMembershipMatchNative()
			throws Exception {
		prepare();
		try (Git git = Git.wrap(db)) {
			check(git, NativeSparseUntrackedSupport.expected(false, false));
			check(git, NativeSparseUntrackedSupport.expected(false, false));
			NativeUntrackedNormalSupport.write(db, "done/sub/sub/file",
					"sub\n");
			NativeUntrackedNormalSupport.ageDirectories(db);
			check(git, NativeSparseUntrackedSupport.expected(true, false));
			check(git, NativeSparseUntrackedSupport.expected(true, false));
			git.add().addFilepattern("dtwo/two").call();
			check(git, NativeSparseUntrackedSupport.expected(true, true));
			git.rm().setCached(true).addFilepattern("dtwo/two").call();
			check(git, NativeSparseUntrackedSupport.expected(true, false));
		}
	}

	@Test
	public void presentWorktreeIgnoreOverridesIndexedRules() throws Exception {
		prepare();
		try (Git git = Git.wrap(db)) {
			check(git, NativeSparseUntrackedSupport.expected(false, false));
			NativeUntrackedNormalSupport.write(db, "done/.gitignore", "five\n");
			NativeUntrackedNormalSupport.ageDirectories(db);
			check(git, NativeSparseUntrackedSupport.worktreeRules(sparse));
			check(git, NativeSparseUntrackedSupport.worktreeRules(sparse));
		}
	}

	@Test
	public void indexedRulesComposeWithParentAndGlobalRules() throws Exception {
		prepare();
		NativeUntrackedNormalSupport.write(db, ".gitignore",
				"four\nparent-only\n");
		NativeUntrackedNormalSupport.write(db, "done/parent-only", "parent\n");
		NativeUntrackedNormalSupport.write(db, "done/global-only", "global\n");
		java.nio.file.Path excludes = db.getDirectory().toPath()
				.resolve("sparse-global-excludes");
		java.nio.file.Files.writeString(excludes, "global-only\n!done/three\n");
		db.getConfig().setString("core", null, "excludesFile",
				excludes.toString());
		db.getConfig().save();
		NativeUntrackedNormalSupport.ageDirectories(db);
		try (Git git = Git.wrap(db)) {
			check(git, NativeSparseUntrackedSupport.parentAndGlobalRules());
			check(git, NativeSparseUntrackedSupport.parentAndGlobalRules());
		}
	}

	@Test
	public void expectedFilesOutsidePatternsRetainExplicitSkipHints()
			throws Exception {
		prepare();
		db.getConfig().setBoolean("sparse", null,
				"expectFilesOutsideOfPatterns", true);
		db.getConfig().save();
		NativeUntrackedNormalSupport.write(db, "done/.gitignore", "five\n");
		NativeUntrackedNormalSupport.ageDirectories(db);
		try (Git git = Git.wrap(db)) {
			check(git, NativeSparseUntrackedSupport.worktreeRules(false));
			check(git, NativeSparseUntrackedSupport.worktreeRules(false));
			assertTrue(db.readDirCache().getEntry("done/.gitignore")
					.isSkipWorkTree());
		}
	}

	@Test
	public void ordinaryDeletedIgnoreDoesNotReadIndexedRules()
			throws Exception {
		prepare();
		NativeSparseUntrackedSupport.clearIgnoreSkipFlag(db);
		try (Git git = Git.wrap(db)) {
			check(git, NativeSparseUntrackedSupport.ordinaryDeletion());
		}
	}

	@Test
	public void changedSkippedBlobInvalidatesPriorIgnoreRules()
			throws Exception {
		prepare();
		try (Git git = Git.wrap(db)) {
			check(git, NativeSparseUntrackedSupport.expected(false, false));
			NativeSparseUntrackedSupport.replaceIndexedRules(db);
			check(git, NativeSparseUntrackedSupport.changedIndexRules());
			check(git, NativeSparseUntrackedSupport.changedIndexRules());
		}
	}

	@Test
	public void monitorAndManyFilesKeepSparseIgnoreRulesAcrossQuietCalls()
			throws Exception {
		prepare();
		db.getConfig().setBoolean("feature", null, "manyFiles", true);
		db.getConfig().save();
		NativeUntrackedNormalSupport.enableMonitor(db);
		try (Git git = Git.wrap(db)) {
			check(git, NativeSparseUntrackedSupport.expected(false, false));
			check(git, NativeSparseUntrackedSupport.expected(false, false));
		}
	}

	private void check(Git git, Snapshot expected) throws Exception {
		NativeSparseUntrackedSupport.assertNative(db, expected);
		Status status = git.status().call();
		assertEquals(expected.modified(), status.getModified());
		assertEquals(expected.untracked(), status.getUntracked());
		assertEquals(expected.ignored(), status.getIgnoredNotInIndex());
		assertEquals(expected.added(), status.getAdded());
		assertEquals(expected.changed(), status.getChanged());
		assertEquals(expected.missing(), status.getMissing());
		assertTrue(status.getRemoved().isEmpty());
		assertTrue(status.getConflicting().isEmpty());
		NativeSparseUntrackedSupport.assertPublication(db, expected, cache);
	}
}
