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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.Assume;
import org.junit.Test;

public class IndexPreloaderTest extends RepositoryTestCase {
	@Test
	public void nativeDefaultEnablesPreloadAndExplicitFalseDisablesIt() throws Exception {
		Assume.assumeTrue(db.getFS().getClass() == FS_POSIX.class);
		DirCache entries = index(1000, true);
		var field = FileTreeIterator.class.getDeclaredField("preloaded");
		field.setAccessible(true);
		FileTreeIterator enabled = new FileTreeIterator(db);
		enabled.preloadIndex(entries, null);
		assertNotNull("C Git enables core.preloadIndex by default", field.get(enabled));
		db.getConfig().setBoolean("core", null, "preloadIndex", false);
		FileTreeIterator disabled = new FileTreeIterator(db);
		disabled.preloadIndex(entries, null);
		assertNull(field.get(disabled));
	}

	private DirCache index(int size, boolean files) throws Exception {
		if (files)
			Files.createDirectories(trash.toPath().resolve("dir"));
		DirCache index = DirCache.newInCore();
		DirCacheBuilder builder = index.builder();
		for (int i = 0; i < size; i++) {
			String path = String.format(java.util.Locale.ROOT, "dir/f%04d",
					Integer.valueOf(i));
			DirCacheEntry entry = new DirCacheEntry(path);
			entry.setFileMode(FileMode.REGULAR_FILE);
			builder.add(entry);
			if (files)
				Files.writeString(trash.toPath().resolve(path), "data");
		}
		builder.finish();
		return index;
	}

	@Test
	public void smallIndexDoesNotStat() throws Exception {
		FS_POSIX failure = new FS_POSIX() {
			@Override
			public Attributes getAttributes(File f) {
				throw new AssertionError(
						"small indexes must not start workers");
			}
		};
		assertNull(IndexPreloader.load(db, failure, index(999, false), null));
	}

	@Test
	public void nativeTestOverrideForcesSmallIndexWorkers() throws Exception {
		mockSystemReader.setProperty("GIT_TEST_PRELOAD_INDEX", "true");
		try {
			IndexPreloader result = IndexPreloader.load(db, db.getFS(), index(2, true), null);
			assertNotNull(result);
			assertEquals(2, result.takeDirectory(new File(trash, "dir")).size());
			assertNull(IndexPreloader.load(db, db.getFS(), index(1, false), null));
		} finally {
			mockSystemReader.setProperty("GIT_TEST_PRELOAD_INDEX", null);
		}
	}

	@Test
	public void pathGroupsPreloadOnlySelectedFiles() throws Exception {
		DirCache entries = index(1000, true);
		for (Set<String> names : List.of(Set.of("f0001"), Set.of("f0000", "f0999"))) {
			AtomicInteger stats = new AtomicInteger();
			FS_POSIX fs = new FS_POSIX() {
				@Override
				public Attributes getAttributes(File file) {
					if (file.getName().startsWith("f")) {
						assertTrue("Unselected path " + file, names.contains(file.getName()));
						stats.incrementAndGet();
					}
					return super.getAttributes(file);
				}
			};
			var filter = org.eclipse.jgit.treewalk.filter.PathFilterGroup.createFromStrings(
					names.stream().map(name -> "dir/" + name).toList());
			IndexPreloader result = IndexPreloader.load(db, fs, entries, null,
					null, Set.of(), filter);
			assertNotNull(result);
			assertEquals(names.size(), stats.get());
			assertEquals(names, result.takeDirectory(new File(trash, "dir")).keySet());
		}
	}

	@Test
	public void directorySelectionRequiresAPathComponentBoundary() throws Exception {
		DirCache entries = index(1000, true);
		FS_POSIX fail = new FS_POSIX() {
			@Override
			public Attributes getAttributes(File file) {
				throw new AssertionError("Unselected subtree must not be statted");
			}
		};
		for (String path : List.of("di", "dir/f000", "missing")) {
			assertNull(IndexPreloader.load(db, fail, entries, null, null, Set.of(),
					org.eclipse.jgit.treewalk.filter.PathFilter.create(path)));
		}
		IndexPreloader selected = IndexPreloader.load(db, db.getFS(), entries, null,
				null, Set.of(), org.eclipse.jgit.treewalk.filter.PathFilter.create("dir"));
		assertNotNull(selected);
		assertEquals(1000, selected.takeDirectory(new File(trash, "dir")).size());
	}

	@Test
	public void arbitraryFiltersRemainOnTheCallingWalk() throws Exception {
		var filter = new org.eclipse.jgit.treewalk.filter.TreeFilter() {
			@Override public boolean include(TreeWalk walk) {
				throw new AssertionError("A preload worker must not evaluate a filter");
			}
			@Override public boolean shouldBeRecursive() { return true; }
			@Override public org.eclipse.jgit.treewalk.filter.TreeFilter clone() { return this; }
		};
		assertNull(IndexPreloader.load(db, db.getFS(), index(1000, false), null,
				null, Set.of(), filter));
	}

	@Test
	public void parallelWorkersAndSingleUseDirectoryMetadata()
			throws Exception {
		Assume.assumeTrue(db.getFS() instanceof FS_POSIX);
		DirCache index = index(1000, true);
		CountDownLatch workers = new CountDownLatch(2);
		AtomicInteger active = new AtomicInteger();
		AtomicInteger peak = new AtomicInteger();
		AtomicInteger leaves = new AtomicInteger();
		FS_POSIX fs = new FS_POSIX() {
			@Override
			public Attributes getAttributes(File f) {
				if (f.getName().startsWith("f")) {
					int now = active.incrementAndGet();
					peak.accumulateAndGet(now, Math::max);
					try {
						if (leaves.getAndIncrement() < 2) {
							workers.countDown();
							if (!workers.await(5, TimeUnit.SECONDS))
								throw new AssertionError(
										"workers did not overlap");
						}
						return super.getAttributes(f);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException(e);
					} finally {
						active.decrementAndGet();
					}
				}
				return super.getAttributes(f);
			}
		};
		IndexPreloader result = IndexPreloader.load(db, fs, index, null);
		assertNotNull(result);
		assertEquals(1000, leaves.get());
		assertTrue(peak.get() >= 2);
		Map<String, FS.Attributes> entries = result
				.takeDirectory(new File(trash, "dir"));
		assertEquals(1000, entries.size());
		assertEquals(4, entries.get("f0000").getLength());
		assertNull(result.takeDirectory(new File(trash, "dir")));
	}

	@Test
	public void monitorValidatedEntriesAvoidStatsDuringParallelPreload()
			throws Exception {
		Assume.assumeTrue(db.getFS() instanceof FS_POSIX);
		DirCache index = index(1000, true);
		Set<DirCacheEntry> trusted = new HashSet<>();
		for (int i = 0; i < index.getEntryCount(); i++) {
			DirCacheEntry entry = index.getEntry(i);
			if (i != 0 && i != 999) {
				entry.setLength(42);
				trusted.add(entry);
			}
		}
		AtomicInteger leaves = new AtomicInteger();
		FS_POSIX fs = new FS_POSIX() {
			@Override
			public Attributes getAttributes(File file) {
				if (file.getName().startsWith("f")) {
					assertTrue("Trusted entries must not be statted",
							file.getName().equals("f0000")
									|| file.getName().equals("f0999"));
					leaves.incrementAndGet();
				}
				return super.getAttributes(file);
			}
		};
		IndexPreloader monitored = IndexPreloader.fromMonitor(db, fs, trusted);
		IndexPreloader result = IndexPreloader.load(db, fs, index, null,
				monitored, trusted);
		assertNotNull(result);
		assertEquals(2, leaves.get());
		Map<String, FS.Attributes> entries = result
				.takeDirectory(new File(trash, "dir"));
		assertEquals(1000, entries.size());
		assertEquals(4, entries.get("f0000").getLength());
		assertEquals(4, entries.get("f0999").getLength());
		assertEquals(42, entries.get("f0001").getLength());
		assertEquals(42, entries.get("f0998").getLength());
		assertNull(result.takeDirectory(new File(trash, "dir")));
	}

	@Test
	public void indexFlagsAndGitlinksAreSkipped() throws Exception {
		DirCache index = index(1000, false);
		for (int i = 0; i < 1000; i++) {
			DirCacheEntry entry = index.getEntry(i);
			if (i < 400)
				entry.setAssumeValid(true);
			else if (i < 800)
				entry.setStage(1);
			else
				entry.setFileMode(FileMode.GITLINK);
		}
		FS_POSIX fs = new FS_POSIX() {
			@Override
			public Attributes getAttributes(File f) {
				throw new AssertionError(
						"ineligible entries must not be statted");
			}
		};
		IndexPreloader result = IndexPreloader.load(db, fs, index, null);
		assertNotNull(result);
		assertNull(result.takeDirectory(new File(trash, "dir")));
	}

	@Test
	public void nativeSkipWorktreeEntryIsNotPreloaded() throws Exception {
		Assume.assumeTrue(db.getFS() instanceof FS_POSIX);
		index(1000, true);
		try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.wrap(db)) {
			git.add().addFilepattern("dir").call();
		}
		var output = db.getDirectory().toPath().resolve("native-flag.out");
		ProcessBuilder builder = new ProcessBuilder(List.of(
				System.getProperty("jgit.test.gitExecutable", "git"),
				"-c", "core.fsmonitor=false", "update-index", "--skip-worktree", "dir/f0000"))
				.directory(db.getWorkTree()).redirectErrorStream(true).redirectOutput(output.toFile());
		builder.environment().keySet().removeIf(n -> n.startsWith("GIT_"));
		builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
		builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
		Process process = builder.start();
		try {
			assertTrue(process.waitFor(10, TimeUnit.SECONDS));
			assertEquals(Files.readString(output), 0, process.exitValue());
		} finally {
			if (process.isAlive()) {
				process.destroyForcibly();
				process.waitFor();
			}
		}
		DirCache entries = db.readDirCache();
		assertTrue(entries.getEntry("dir/f0000").isSkipWorkTree());
		AtomicInteger leaves = new AtomicInteger();
		FS_POSIX fs = new FS_POSIX() {
			@Override
			public Attributes getAttributes(File file) {
				if (file.getName().equals("f0000"))
					throw new AssertionError("Skipped entry must not be statted");
				if (file.getName().startsWith("f"))
					leaves.incrementAndGet();
				return super.getAttributes(file);
			}
		};
		IndexPreloader result = IndexPreloader.load(db, fs, entries, null);
		assertNotNull(result);
		assertEquals(999, leaves.get());
		Map<String, FS.Attributes> directory = result.takeDirectory(new File(trash, "dir"));
		assertEquals(999, directory.size());
		assertNull(directory.get("f0000"));
	}

	@Test
	public void leadingSymlinkIsNotFollowed() throws Exception {
		Assume.assumeTrue(db.getFS() instanceof FS_POSIX);
		DirCache index = index(1000, false);
		Files.createDirectory(trash.toPath().resolve("target"));
		Files.createSymbolicLink(trash.toPath().resolve("dir"),
				trash.toPath().resolve("target"));
		AtomicInteger leaves = new AtomicInteger();
		FS_POSIX fs = new FS_POSIX() {
			@Override
			public Attributes getAttributes(File f) {
				if (f.getName().startsWith("f"))
					leaves.incrementAndGet();
				return super.getAttributes(f);
			}
		};
		IndexPreloader result = IndexPreloader.load(db, fs, index, null);
		assertNotNull(result);
		assertEquals(0, leaves.get());
		assertNull(result.takeDirectory(new File(trash, "dir")));
	}

	@Test
	public void workerFailureFallsBack() throws Exception {
		FS_POSIX fs = new FS_POSIX() {
			@Override
			public Attributes getAttributes(File f) {
				throw new IllegalStateException("unavailable");
			}
		};
		assertNull(IndexPreloader.load(db, fs, index(1000, false), null));
	}

	@Test
	public void interruptionIsPreserved() throws Exception {
		DirCache index = index(1000, false);
		Thread.currentThread().interrupt();
		try {
			assertNull(IndexPreloader.load(db, db.getFS(), index, null));
			assertTrue(Thread.currentThread().isInterrupted());
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	public void cancelledMonitorRunsOnlyOnCallingThread() throws Exception {
		Thread caller = Thread.currentThread();
		AtomicInteger polls = new AtomicInteger();
		ProgressMonitor monitor = new ProgressMonitor() {
			public void start(int n) {
			}

			public void beginTask(String title, int n) {
			}

			public void update(int n) {
			}

			public void endTask() {
			}

			public void showDuration(boolean enabled) {
			}

			public boolean isCancelled() {
				assertSame(caller, Thread.currentThread());
				return polls.incrementAndGet() >= 2;
			}
		};
		assertNull(IndexPreloader.load(db, db.getFS(), index(1000, false),
				monitor));
		assertTrue(polls.get() >= 2);
	}
}
