/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.junit.NativeFsMonitorIndexSupport;
import org.eclipse.jgit.junit.NativeFsMonitorWorkloadSupport;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.IndexDiff;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.junit.Test;

/** Native pathspec selection applied before monitor metadata preparation. */
public class FsMonitorFilteredStatusTest extends RepositoryTestCase {
	private static final Set<String> ALL = Set.of("a", "m", "z", "sub/file",
			"sub/new\nline", "sub-neighbor/file");

	private void prepare() throws Exception {
		NativeFsMonitorIndexSupport.prepare(db);
		for (String path : List.of("sub/file", "sub/new\nline",
				"sub-neighbor/file")) {
			Files.createDirectories(
					db.getWorkTree().toPath().resolve(path).getParent());
			Files.writeString(db.getWorkTree().toPath().resolve(path),
					"base\n");
		}
		NativeFsMonitorIndexSupport.report(db, "/");
		try (var git = Git.wrap(db)) {
			git.add().addFilepattern(".").call();
			git.commit().setAuthor("Filter Test", "filter@example.invalid")
					.setCommitter("Filter Test", "filter@example.invalid")
					.setMessage("directories").call();
		}
		NativeFsMonitorIndexSupport.report(db);
		var expected = NativeFsMonitorIndexSupport.mutate(db, "update-index",
				"--fsmonitor-valid", "a", "m", "z", "sub/file", "sub/new\nline",
				"sub-neighbor/file");
		Files.copy(expected.index(), db.getIndexFile().toPath(),
				StandardCopyOption.REPLACE_EXISTING);
	}

	@Test
	public void literalBoundsRestrictTrustedMetadataBeforeConstruction()
			throws Exception {
		prepare();
		byte[] original = Files.readAllBytes(db.getIndexFile().toPath());
		for (Set<String> paths : List.of(Set.of("a"), Set.of("a", "z"),
				Set.of("sub"), Set.of("sub/new\nline"), Set.of("absent"))) {
			var iterator = new FileTreeIterator(db);
			var diff = new IndexDiff(db, Constants.HEAD, iterator);
			diff.setFilter(PathFilterGroup.createFromStrings(paths));
			diff.diff();
			Set<String> expected = ALL.stream()
					.filter(path -> paths.stream()
							.anyMatch(bound -> path.equals(bound)
									|| path.startsWith(bound + "/")))
					.collect(Collectors.toSet());
			assertEquals(paths.toString(), expected, trustedPaths(iterator));
			assertEquals(Set.of(), diff.getModified());
		}
		assertArrayEquals(original,
				Files.readAllBytes(db.getIndexFile().toPath()));
	}

	@Test
	public void genericFilterWithoutBoundsKeepsConservativeFullMetadata()
			throws Exception {
		prepare();
		var iterator = new FileTreeIterator(db);
		var diff = new IndexDiff(db, Constants.HEAD, iterator);
		diff.setFilter(new TreeFilter() {
			@Override
			public boolean include(TreeWalk walker) {
				return true;
			}

			@Override
			public boolean shouldBeRecursive() {
				return true;
			}

			@Override
			public TreeFilter clone() {
				return this;
			}
		});
		diff.diff();
		assertEquals(ALL, trustedPaths(iterator));
	}

	@Test
	public void rootInvalidationStillFindsSelectedRestoredMtimeEdits()
			throws Exception {
		prepare();
		java.util.concurrent.TimeUnit.MILLISECONDS
				.sleep(1010 - System.currentTimeMillis() % 1000);
		for (String path : List.of("sub/file", "sub-neighbor/file")) {
			var file = db.getWorkTree().toPath().resolve(path);
			var time = Files.getLastModifiedTime(file);
			Files.writeString(file, "edit\n");
			Files.setLastModifiedTime(file, time);
		}
		NativeFsMonitorIndexSupport.report(db, "/");
		NativeFsMonitorWorkloadSupport.assertNativeDiff(db, false, "sub",
				Set.of("sub/file"));
		var iterator = new FileTreeIterator(db);
		var diff = new IndexDiff(db, Constants.HEAD, iterator);
		diff.setFilter(PathFilterGroup.createFromStrings(List.of("sub")));
		diff.diff();
		assertEquals(Set.of("sub/file"), diff.getModified());
		assertEquals(Set.of(), trustedPaths(iterator));
	}

	@Test
	@SuppressWarnings("deprecation")
	public void arbitraryChangedPathHintsCannotSuppressRequiredContentChecks()
			throws Exception {
		prepare();
		var file = db.getWorkTree().toPath().resolve("z");
		var time = Files.getLastModifiedTime(file);
		Files.writeString(file, "edit\n");
		Files.setLastModifiedTime(file, time);
		NativeFsMonitorIndexSupport.report(db, "/");
		var diff = new IndexDiff(db, Constants.HEAD, new FileTreeIterator(db));
		diff.setFilter(new TreeFilter() {
			@Override
			public boolean include(TreeWalk walker) {
				return true;
			}

			@Override
			public boolean shouldBeRecursive() {
				return true;
			}

			@Override
			public TreeFilter clone() {
				return this;
			}

			@Override
			public java.util.Optional<Set<byte[]>> getPathsBestEffort() {
				return java.util.Optional.of(Set.of(Constants.encode("a")));
			}
		});
		diff.diff();
		assertEquals(Set.of("z"), diff.getModified());
	}

	@Test
	public void cancelledBoundsFallBackToAllRequiredChecks() throws Exception {
		prepare();
		var calls = new java.util.concurrent.atomic.AtomicInteger();
		var monitor = new org.eclipse.jgit.lib.ProgressMonitor() {
			@Override
			public void start(int totalTasks) {
			}

			@Override
			public void beginTask(String title, int totalWork) {
			}

			@Override
			public void update(int completed) {
			}

			@Override
			public void endTask() {
			}

			@Override
			public void showDuration(boolean enabled) {
			}

			@Override
			public boolean isCancelled() {
				return calls.incrementAndGet() == 2;
			}
		};
		org.junit.Assert.assertNull(
				org.eclipse.jgit.internal.util.IndexEntrySelection.select(
						db.readDirCache(),
						List.of(Constants.encode("a"), Constants.encode("z")),
						monitor));
	}

	@SuppressWarnings("unchecked")
	private static Set<String> trustedPaths(FileTreeIterator iterator)
			throws Exception {
		var field = FileTreeIterator.class.getDeclaredField("monitorEntries");
		field.setAccessible(true);
		return ((Set<DirCacheEntry>) field.get(iterator)).stream()
				.map(DirCacheEntry::getPathString).collect(Collectors.toSet());
	}
}
