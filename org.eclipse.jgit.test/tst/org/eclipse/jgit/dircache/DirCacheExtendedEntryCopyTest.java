/*
 * Copyright (C) 2026, and others
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.dircache;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.junit.NativeFsMonitorIndexSupport;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.junit.Test;

/**
 * Preserve native extended flags when checkout/index operations copy entries.
 */
public class DirCacheExtendedEntryCopyTest extends RepositoryTestCase {
	private DirCache nativeEntries(boolean intent) throws Exception {
		writeTrashFile("a", "first\n");
		writeTrashFile("b", "second\n");
		try (Git git = Git.wrap(db)) {
			git.add().addFilepattern(".").call();
		}
		if (intent) {
			writeTrashFile("intent", "new\n");
		}
		var result = intent
				? NativeFsMonitorIndexSupport.mutate(db, "add",
						"--intent-to-add", "intent")
				: NativeFsMonitorIndexSupport.mutate(db, "update-index",
						"--skip-worktree", "b");
		assertTrue(Files.isRegularFile(result.index()));
		return DirCache.read(result.index().toFile(), db.getFS());
	}

	@Test
	public void constructorPreservesNativeSkipWorktree() throws Exception {
		DirCacheEntry source = nativeEntries(false).getEntry("b");
		DirCacheEntry copy = new DirCacheEntry(source);
		assertTrue(copy.isSkipWorkTree());
		assertEquals(source.getObjectId(), copy.getObjectId());
		assertEquals("b", copy.getPathString());
	}

	@Test
	public void metadataGrowthDoesNotOverwriteNeighborInPackedIndex()
			throws Exception {
		DirCache index = nativeEntries(false);
		DirCacheEntry source = index.getEntry("b");
		var id = source.getObjectId();
		var length = source.getLength();
		var modified = source.getLastModifiedInstant();
		var before = new java.io.ByteArrayOutputStream();
		source.write(before, DirCache.DirCacheVersion.DIRC_VERSION_EXTENDED, null);
		DirCacheEntry target = index.getEntry("a");
		target.copyMetaData(source);
		assertTrue(target.isSkipWorkTree());
		assertEquals("a", target.getPathString());
		assertEquals(id, target.getObjectId());
		assertEquals(id, source.getObjectId());
		assertEquals(length, source.getLength());
		assertEquals(modified, source.getLastModifiedInstant());
		assertTrue(source.isSkipWorkTree());
		var after = new java.io.ByteArrayOutputStream();
		source.write(after, DirCache.DirCacheVersion.DIRC_VERSION_EXTENDED, null);
		assertArrayEquals(before.toByteArray(), after.toByteArray());
	}

	@Test
	public void metadataPreservesDestinationPathAndRequestedStage()
			throws Exception {
		DirCacheEntry source = nativeEntries(false).getEntry("b");
		DirCacheEntry target = new DirCacheEntry("renamed",
				DirCacheEntry.STAGE_2);
		target.copyMetaData(source, true);
		assertTrue(target.isSkipWorkTree());
		assertEquals(DirCacheEntry.STAGE_2, target.getStage());
		assertEquals("renamed", target.getPathString());
		target.copyMetaData(source);
		assertEquals(DirCacheEntry.STAGE_0, target.getStage());
		assertTrue(target.isSkipWorkTree());
	}

	@Test
	public void constructorAndMetadataPreserveNativeIntentToAdd()
			throws Exception {
		DirCacheEntry source = nativeEntries(true).getEntry("intent");
		assertTrue(new DirCacheEntry(source).isIntentToAdd());
		DirCacheEntry target = new DirCacheEntry("renamed");
		target.copyMetaData(source);
		assertTrue(target.isIntentToAdd());
		assertEquals(source.getObjectId(), target.getObjectId());
		assertEquals("renamed", target.getPathString());
	}

	@Test
	public void copyingOrdinaryMetadataClearsExtendedFlags() throws Exception {
		DirCache index = nativeEntries(false);
		DirCacheEntry target = new DirCacheEntry(index.getEntry("b"));
		target.copyMetaData(index.getEntry("a"));
		assertFalse(target.isSkipWorkTree());
		assertFalse(target.isIntentToAdd());
		assertEquals("b", target.getPathString());
		assertEquals(index.getEntry("a").getObjectId(), target.getObjectId());
	}
}
