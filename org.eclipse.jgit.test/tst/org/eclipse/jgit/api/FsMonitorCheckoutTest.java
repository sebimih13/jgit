/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import java.nio.file.Files;
import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.junit.NativeFsMonitorCheckoutSupport;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.lib.Constants;
import org.junit.Test;

/** Native t7519 checkout protection with persisted monitor state. */
public class FsMonitorCheckoutTest extends RepositoryTestCase {
	@Test
	public void checkoutQueryRetainsDirtyAndReportedEntriesWithStatusCacheEnabled()
			throws Exception {
		org.eclipse.jgit.junit.NativeFsMonitorIndexSupport.prepare(db);
		db.getConfig().setBoolean("jgit", null, "fsmonitorCache", true);
		var index = db.readDirCache();
		var first = org.eclipse.jgit.util.FsMonitor.queryIndexForCheckout(db,
				index);
		assertEquals(java.util.Set.of("a", "z"), checkoutPaths(first, index));
		org.eclipse.jgit.junit.NativeFsMonitorIndexSupport.reportToken(db,
				"query-clock", "a");
		var changed = org.eclipse.jgit.util.FsMonitor.queryIndexForCheckout(db,
				index);
		assertEquals(java.util.Set.of("z"), checkoutPaths(changed, index));
		org.eclipse.jgit.junit.NativeFsMonitorIndexSupport.reportToken(db,
				"metadata-clock", ".git/info/attributes");
		assertEquals(java.util.Set.of(),
				checkoutPaths(org.eclipse.jgit.util.FsMonitor
						.queryIndexForCheckout(db, index), index));
	}

	@Test
	public void directoryRepliesPreserveLexicalNeighborValidity()
			throws Exception {
		org.eclipse.jgit.junit.NativeFsMonitorIndexSupport.prepare(db);
		for (String directory : java.util.List.of("dir", "dir-other")) {
			var path = db.getWorkTree().toPath().resolve(directory);
			Files.createDirectories(path);
			Files.writeString(path.resolve("file"), "base\n");
		}
		for (String[] arguments : new String[][] {
				{ "add", "dir", "dir-other" }, { "update-index",
						"--fsmonitor-valid", "dir/file", "dir-other/file" } }) {
			var nativeIndex = org.eclipse.jgit.junit.NativeFsMonitorIndexSupport
					.mutate(db, arguments);
			Files.copy(nativeIndex.index(), db.getIndexFile().toPath(),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		var index = db.readDirCache();
		org.eclipse.jgit.junit.NativeFsMonitorIndexSupport.reportToken(db,
				"directory-clock", "dir/");
		assertEquals(java.util.Set.of("a", "z", "dir-other/file"),
				checkoutPaths(org.eclipse.jgit.util.FsMonitor
						.queryIndexForCheckout(db, index), index));
	}

	@Test
	public void explicitCallerContentCheckSurvivesCheckoutPreparation()
			throws Exception {
		org.eclipse.jgit.junit.NativeFsMonitorIndexSupport.prepare(db);
		var index = db.readDirCache();
		var path = db.getWorkTree().toPath().resolve("a");
		var time = Files.getLastModifiedTime(path);
		Files.writeString(path, "edit\n");
		Files.setLastModifiedTime(path, time);
		var iterator = new org.eclipse.jgit.treewalk.FileTreeIterator(db);
		iterator.setForceContentCheckEntries(
				java.util.Set.of(index.getEntry("a")));
		iterator.prepareCheckout(index, null);
		boolean checked = false;
		try (var walk = new org.eclipse.jgit.treewalk.TreeWalk(db)) {
			int cached = walk.addTree(
					new org.eclipse.jgit.dircache.DirCacheIterator(index));
			walk.addTree(iterator);
			iterator.setDirCacheIterator(walk, cached);
			walk.setRecursive(true);
			while (walk.next()) {
				if (walk.getPathString().equals("a")) {
					org.junit.Assert.assertTrue(iterator.isModifiedForCheckout(
							index.getEntry("a"), walk.getObjectReader()));
					checked = true;
				}
			}
		}
		org.junit.Assert.assertTrue(checked);
	}

	private java.util.Set<String> checkoutPaths(
			org.eclipse.jgit.util.FsMonitor.IndexQuery query,
			org.eclipse.jgit.dircache.DirCache index) {
		var paths = new java.util.HashSet<String>();
		for (var entry : query.getCheckoutValidEntries(db, index))
			paths.add(entry.getPathString());
		return paths;
	}

	@Test
	public void reportedSameStatChangeCannotBeOverwritten() throws Exception {
		checkConflict("reported", false);
	}

	@Test
	public void earlierDirtyEntrySurvivesWriterClockAdvance() throws Exception {
		checkConflict("dirty", false);
	}

	@Test
	public void failedProviderCannotHideSameStatChange() throws Exception {
		checkConflict("failure", false);
	}

	@Test
	public void rootReplyCannotHideSameStatChange() throws Exception {
		checkConflict("root", false);
	}

	@Test
	public void statusResultCacheCannotBypassCheckoutValidation()
			throws Exception {
		checkConflict("reported", true);
	}

	@Test
	public void assumedEntryCannotHideCheckoutConflictWithReportedMonitor()
			throws Exception {
		checkFlag("--assume-unchanged", "reported", false);
	}

	@Test
	public void assumedEntryCannotHideCheckoutConflictWithDisabledMonitor()
			throws Exception {
		checkFlag("--assume-unchanged", "disabled", false);
	}

	@Test
	public void assumedEntryCannotHideCheckoutConflictWithFailureMonitor()
			throws Exception {
		checkFlag("--assume-unchanged", "failure", false);
	}

	@Test
	public void cleanAssumedEntryCanBeCheckedOut() throws Exception {
		checkFlag("--assume-unchanged", "reported", true);
	}

	@Test
	public void skippedEntryCannotHideCheckoutConflictWithReportedMonitor()
			throws Exception {
		checkFlag("--skip-worktree", "reported", false);
	}

	@Test
	public void skippedEntryCannotHideCheckoutConflictWithDisabledMonitor()
			throws Exception {
		checkFlag("--skip-worktree", "disabled", false);
	}

	@Test
	public void skippedEntryCannotHideCheckoutConflictWithFailureMonitor()
			throws Exception {
		checkFlag("--skip-worktree", "failure", false);
	}

	@Test
	public void cleanSkippedEntryCanBeCheckedOut() throws Exception {
		checkFlag("--skip-worktree", "reported", true);
	}

	private void checkFlag(String flag, String mode, boolean clean)
			throws Exception {
		var scenario = NativeFsMonitorCheckoutSupport.prepare(db, mode,
				false, flag, clean);
		try (Git git = Git.wrap(db)) {
			// Status honors these flags; checkout must independently verify
			// paths it will overwrite, as native t2106 requires.
			assertEquals(java.util.Set.of(), git.status()
					.setReportIgnored(false).call().getModified());
			if (clean) {
				git.checkout().setName(scenario.target().name()).call();
				assertEquals(scenario.target(), db.resolve(Constants.HEAD));
				assertEquals("dest\n", Files.readString(scenario.file()));
			} else {
				assertThrows(CheckoutConflictException.class, () -> git.checkout()
						.setName(scenario.target().name()).call());
				assertEquals("edit\n", Files.readString(scenario.file()));
				assertEquals(scenario.base(), db.resolve(Constants.HEAD));
				assertArrayEquals(scenario.originalIndex(),
						Files.readAllBytes(db.getIndexFile().toPath()));
			}
		}
	}

	private void checkConflict(String mode, boolean resultCache)
			throws Exception {
		var scenario = NativeFsMonitorCheckoutSupport.prepare(db, mode,
				resultCache);
		try (Git git = Git.wrap(db)) {
			assertThrows(CheckoutConflictException.class, () -> git.checkout()
					.setName(scenario.target().name()).call());
			assertEquals("edit\n", Files.readString(scenario.file()));
			assertEquals(scenario.base(), db.resolve(Constants.HEAD));
			assertArrayEquals(scenario.originalIndex(),
					Files.readAllBytes(db.getIndexFile().toPath()));
		}
	}
}
