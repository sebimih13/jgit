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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.Test;

public class DirectoryMetadataTest extends RepositoryTestCase {
	private File[] files(int size) {
		File[] files = new File[size];
		for (int i = 0; i < size; i++)
			files[i] = new File(trash, "f" + i);
		return files;
	}

	@Test
	public void fullStatusDoesNotEnumerateEntireIgnoredDirectories()
			throws Exception {
		writeTrashFile(".gitignore", "ignored-build/\n");
		writeTrashFile("visible", "tracked");
		try (org.eclipse.jgit.api.Git git = new org.eclipse.jgit.api.Git(db)) {
			git.add().addFilepattern(".").call();
			git.commit().setMessage("base").call();
		}
		writeTrashFile("ignored-build/deep/hidden", "ignored");
		FS fs = new FS_POSIX() {
			@Override
			public WorkingTreeIterator.Entry[] list(File directory,
					FileTreeIterator.FileModeStrategy strategy) {
				assertFalse("ignored contents must not be enumerated",
						directory.toPath().startsWith(
								trash.toPath().resolve("ignored-build")));
				return super.list(directory, strategy);
			}
		};
		FileTreeIterator iterator = new FileTreeIterator(trash, fs,
				db.getConfig().get(WorkingTreeOptions.KEY));
		org.eclipse.jgit.lib.IndexDiff diff = new org.eclipse.jgit.lib.IndexDiff(
				db, org.eclipse.jgit.lib.Constants.HEAD, iterator);
		diff.diff();
		assertEquals(java.util.Set.of("ignored-build"),
				diff.getIgnoredNotInIndex());
		assertTrue(diff.getUntracked().isEmpty());
	}

	@Test
	public void smallListingsKeepSerialPath() {
		FS fs = new FS_POSIX() {
			@Override
			public Attributes getAttributes(File f) {
				throw new AssertionError("unexpected stat");
			}
		};
		assertNull(DirectoryMetadata.read(fs, files(999), null));
	}

	@Test
	public void parallelReadsEachUncachedEntryOnce() throws Exception {
		File[] files = files(1000);
		AtomicInteger calls = new AtomicInteger();
		AtomicInteger active = new AtomicInteger();
		AtomicInteger peak = new AtomicInteger();
		CountDownLatch overlap = new CountDownLatch(2);
		FS fs = new FS_POSIX() {
			@Override
			public Attributes getAttributes(File file) {
				calls.incrementAndGet();
				int count = active.incrementAndGet();
				peak.accumulateAndGet(count, Math::max);
				overlap.countDown();
				try {
					assertTrue(overlap.await(5, TimeUnit.SECONDS));
					return new Attributes(file, this);
				} catch (InterruptedException e) {
					throw new AssertionError(e);
				} finally {
					active.decrementAndGet();
				}
			}
		};
		FS.Attributes[] read = DirectoryMetadata.read(fs, files, null);
		assertNotNull(read);
		assertEquals(1000, calls.get());
		assertTrue(peak.get() >= 2);
		for (int i = 0; i < files.length; i++)
			assertEquals(files[i], read[i].getFile());
	}

	@Test
	public void cachedEntriesAreNotStattedAgain() {
		File[] files = files(1001);
		FS fs = new FS_POSIX() {
			@Override
			public Attributes getAttributes(File file) {
				assertNotEquals(files[0], file);
				return new Attributes(file, this);
			}
		};
		FS.Attributes cached = new FS.Attributes(files[0], fs);
		FS.Attributes[] read = DirectoryMetadata.read(fs, files,
				Map.of(files[0].getName(), cached));
		assertNotNull(read);
		assertSame(cached, read[0]);
	}

	@Test
	public void mostlyCachedDirectoryDoesNotStartWorkers() {
		File[] files = files(1000);
		Map<String, FS.Attributes> cached = new java.util.HashMap<>();
		for (File file : files)
			cached.put(file.getName(), new FS.Attributes(file, FS.DETECTED));
		assertNull(DirectoryMetadata.read(FS.DETECTED, files, cached));
	}

	@Test
	public void workerFailureFallsBack() {
		FS fs = new FS_POSIX() {
			@Override
			public Attributes getAttributes(File file) {
				throw new IllegalStateException("unavailable");
			}
		};
		assertNull(DirectoryMetadata.read(fs, files(1000), null));
	}

	@Test
	public void interruptionIsPreserved() {
		try {
			Thread.currentThread().interrupt();
			assertNull(DirectoryMetadata.read(FS.DETECTED, files(1000), null));
			assertTrue(Thread.currentThread().isInterrupted());
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	public void realSymlinksKeepTheirOwnTypeAndLength() throws Exception {
		org.junit.Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		File[] files = files(1000);
		Files.createDirectories(trash.toPath().resolve("target"));
		for (int i = 0; i < files.length; i++)
			Files.createSymbolicLink(files[i].toPath(), java.nio.file.Path
					.of(i % 2 == 0 ? "missing-target" : "target"));
		FS.Attributes[] read = DirectoryMetadata.read(FS.DETECTED, files, null);
		assertNotNull(read);
		for (int i = 0; i < files.length; i++) {
			FS.Attributes serial = FS.DETECTED.getAttributes(files[i]);
			assertTrue(read[i].isSymbolicLink());
			assertFalse(read[i].isDirectory());
			assertEquals(serial.getLength(), read[i].getLength());
			assertEquals(serial.getLastModifiedInstant(),
					read[i].getLastModifiedInstant());
		}
		// Exercise the actual iterator, including mode calculation and sorting.
		Thread caller = Thread.currentThread();
		FileTreeIterator iterator = new FileTreeIterator(db,
				(file, attributes) -> {
					assertSame(caller, Thread.currentThread());
					return FileTreeIterator.DefaultFileModeStrategy.INSTANCE
							.getMode(file, attributes);
				});
		int links = 0;
		while (!iterator.eof()) {
			if (iterator.getEntryRawMode()
					== org.eclipse.jgit.lib.FileMode.SYMLINK.getBits()) {
				links++;
			} else {
				assertEquals("target", iterator.getEntryPathString());
			}
			iterator.next(1);
		}
		assertEquals(1000, links);
	}
}
