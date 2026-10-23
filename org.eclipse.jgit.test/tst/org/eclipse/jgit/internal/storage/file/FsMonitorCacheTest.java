/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.internal.storage.file;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.junit.NativeFsMonitorIndexSupport;
import org.eclipse.jgit.junit.NativeFsMonitorWorkloadSupport;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.junit.Test;

public class FsMonitorCacheTest extends RepositoryTestCase {
	@Test
	public void nativeDirtyBitmapAndOpaqueTokenSurviveJavaRewrites()
			throws Exception {
		NativeFsMonitorIndexSupport.prepare(db);
		for (String version : List.of("2", "4")) {
			var expected = NativeFsMonitorIndexSupport.mutate(db,
					"update-index", "--index-version=" + version,
					"--force-write-index");
			Files.copy(expected.index(), db.getIndexFile().toPath(),
					StandardCopyOption.REPLACE_EXISTING);
			DirCache index = db.lockDirCache();
			try {
				assertNotNull(index.getFsMonitorCache());
				assertArrayEquals(
						"native-clock".getBytes(StandardCharsets.UTF_8),
						index.getFsMonitorCache().getToken());
				assertTrue(index.isFsMonitorValid(0));
				assertFalse(index.isFsMonitorValid(1));
				assertTrue(index.isFsMonitorValid(2));
				index.write();
				assertTrue(index.commit());
			} finally {
				index.unlock();
			}
			assertArrayEquals(expected.payload(), NativeFsMonitorIndexSupport
					.extension(Files.readAllBytes(db.getIndexFile().toPath())));
			NativeFsMonitorWorkloadSupport.assertStagedMatches(db,
					expected.index());
		}
	}

	@Test
	public void chmodRemovalAndInsertionMatchNativeDirtyPositions()
			throws Exception {
		for (String mutation : List.of("chmod", "remove", "add")) {
			NativeFsMonitorIndexSupport.prepare(db);
			String id = db.readDirCache().getEntry("a").getObjectId().name();
			String[] command = switch (mutation) {
			case "chmod" -> new String[] { "update-index", "--chmod=+x", "a" };
			case "remove" ->
				new String[] { "update-index", "--force-remove", "a" };
			default -> new String[] { "update-index", "--add", "--cacheinfo",
					"100644," + id + ",b" };
			};
			var expected = NativeFsMonitorIndexSupport.mutate(db, command);
			DirCache index = db.lockDirCache();
			try {
				if (mutation.equals("chmod")) {
					index.getEntry("a").setFileMode(FileMode.EXECUTABLE_FILE);
				} else {
					var editor = index.editor();
					if (mutation.equals("remove"))
						editor.add(new DirCacheEditor.DeletePath("a"));
					else
						editor.add(new DirCacheEditor.PathEdit("b") {
							@Override
							public void apply(DirCacheEntry entry) {
								entry.setFileMode(FileMode.REGULAR_FILE);
								entry.setObjectId(
										index.getEntry("a").getObjectId());
							}
						});
					editor.finish();
				}
				index.write();
				assertTrue(index.commit());
			} finally {
				index.unlock();
			}
			assertArrayEquals(mutation, expected.payload(),
					NativeFsMonitorIndexSupport.extension(
							Files.readAllBytes(db.getIndexFile().toPath())));
			NativeFsMonitorWorkloadSupport.assertStagedMatches(db,
					expected.index());
		}
	}

	@Test
	public void stagingNewFileMatchesNativeVerifiedValidity() throws Exception {
		checkVerifiedStaging("b");
	}

	@Test
	public void restagingChangedFileMatchesNativeVerifiedValidity()
			throws Exception {
		checkVerifiedStaging("a");
	}

	private void checkVerifiedStaging(String path) throws Exception {
		NativeFsMonitorIndexSupport.prepare(db);
		Files.writeString(db.getWorkTree().toPath().resolve(path),
				"verified new content\n");
		NativeFsMonitorIndexSupport.report(db, path);
		var expected = NativeFsMonitorIndexSupport.mutate(db, "add", "--",
				path);
		try (var git = org.eclipse.jgit.api.Git.wrap(db)) {
			git.add().addFilepattern(path).call();
		}
		assertArrayEquals(expected.payload(), NativeFsMonitorIndexSupport
				.extension(Files.readAllBytes(db.getIndexFile().toPath())));
		NativeFsMonitorWorkloadSupport.assertStagedMatches(db,
				expected.index());
	}

	@Test
	public void customStagingDoesNotInventFilesystemValidity()
			throws Exception {
		NativeFsMonitorIndexSupport.prepare(db);
		Files.writeString(db.getWorkTree().toPath().resolve("b"),
				"custom content\n");
		try (var git = org.eclipse.jgit.api.Git.wrap(db)) {
			git.add()
					.setWorkingTreeIterator(
							new org.eclipse.jgit.treewalk.FileTreeIterator(db))
					.addFilepattern("b").call();
		}
		DirCache index = db.readDirCache();
		assertFalse(index.isFsMonitorValid(index.findEntry("b")));
		assertTrue(index.getFsMonitorCache().isDirty(index.findEntry("b")));
	}

	@Test
	public void gitlinksNeverAcquireVerifiedMonitorValidity() throws Exception {
		NativeFsMonitorIndexSupport.prepare(db);
		var id = db.resolve(Constants.HEAD);
		var expected = NativeFsMonitorIndexSupport.mutate(db, "update-index",
				"--add", "--cacheinfo", "160000," + id.name() + ",g");
		DirCache index = db.lockDirCache();
		try {
			var editor = index.editor();
			editor.add(new DirCacheEditor.PathEdit("g") {
				@Override
				public void apply(DirCacheEntry entry) {
					entry.setFileMode(FileMode.GITLINK);
					entry.setObjectId(id);
					index.markFsMonitorValid(entry);
				}
			});
			editor.finish();
			assertFalse(index.isFsMonitorValid(index.findEntry("g")));
			index.write();
			assertTrue(index.commit());
		} finally {
			index.unlock();
		}
		assertArrayEquals(expected.payload(), NativeFsMonitorIndexSupport
				.extension(Files.readAllBytes(db.getIndexFile().toPath())));
	}

	@Test
	public void nativeVersionOneAndBinaryTokensSurviveNormalization()
			throws Exception {
		for (boolean versionOne : new boolean[] { true, false }) {
			NativeFsMonitorIndexSupport.prepare(db);
			byte[] original = Files.readAllBytes(db.getIndexFile().toPath());
			byte[] payload = NativeFsMonitorIndexSupport.extension(original);
			int mapOffset = 4;
			while (payload[mapOffset++] != 0) {
				/* opaque token */ }
			byte[] map = Arrays.copyOfRange(payload, mapOffset + 4,
					payload.length);
			byte[] replacement;
			if (versionOne) {
				replacement = ByteBuffer.allocate(16 + map.length).putInt(1)
						.putLong(-1L).putInt(map.length).put(map).array();
			} else {
				byte[] token = { (byte) 0xff, 'x', '\n' };
				replacement = ByteBuffer.allocate(9 + token.length + map.length)
						.putInt(2).put(token).put((byte) 0).putInt(map.length)
						.put(map).array();
			}
			installPayload(original, payload, replacement);
			Files.writeString(
					db.getDirectory().toPath().resolve("hooks/fsmonitor-index"),
					"#!/bin/sh\nprintf '%s\\0' \"$2\"\n");
			var expected = NativeFsMonitorIndexSupport.mutate(db,
					"update-index", "--force-write-index");
			DirCache index = db.lockDirCache();
			try {
				index.write();
				assertTrue(index.commit());
			} finally {
				index.unlock();
			}
			assertArrayEquals(expected.payload(), NativeFsMonitorIndexSupport
					.extension(Files.readAllBytes(db.getIndexFile().toPath())));
		}
	}

	private void installPayload(byte[] index, byte[] previous,
			byte[] replacement) throws Exception {
		int offset = findPayload(index, previous);
		var output = new java.io.ByteArrayOutputStream();
		try (var data = new java.io.DataOutputStream(output)) {
			data.write(index, 0, offset - 4);
			data.writeInt(replacement.length);
			data.write(replacement);
			data.write(index, offset + previous.length,
					index.length - 20 - offset - previous.length);
			data.flush();
			data.write(
					Constants.newMessageDigest().digest(output.toByteArray()));
		}
		Files.write(db.getIndexFile().toPath(), output.toByteArray());
	}

	@Test
	public void newlyStagedSymlinkMatchesNativeDirtyBitmap() throws Exception {
		org.junit.Assume.assumeTrue(db.getFS().supportsSymlinks());
		NativeFsMonitorIndexSupport.prepare(db);
		Files.createSymbolicLink(db.getWorkTree().toPath().resolve("link"),
				java.nio.file.Path.of("a"));
		NativeFsMonitorIndexSupport.report(db, "link");
		var expected = NativeFsMonitorIndexSupport.mutate(db, "add", "--",
				"link");
		try (var git = org.eclipse.jgit.api.Git.wrap(db)) {
			git.add().addFilepattern("link").call();
		}
		assertArrayEquals(expected.payload(), NativeFsMonitorIndexSupport
				.extension(Files.readAllBytes(db.getIndexFile().toPath())));
		NativeFsMonitorWorkloadSupport.assertStagedMatches(db,
				expected.index());
	}

	@Test
	public void resolvingUnmergedStagesMatchesNativeBitmapOrdering()
			throws Exception {
		NativeFsMonitorIndexSupport.prepare(db);
		DirCache index = db.lockDirCache();
		try {
			var builder = index.builder();
			for (int stage = 1; stage <= 3; stage++) {
				DirCacheEntry entry = new DirCacheEntry("a", stage);
				entry.setFileMode(FileMode.REGULAR_FILE);
				entry.setObjectId(index.getEntry("a").getObjectId());
				builder.add(entry);
			}
			builder.add(index.getEntry("m"));
			builder.add(index.getEntry("z"));
			builder.finish();
			index.write();
			assertTrue(index.commit());
		} finally {
			index.unlock();
		}
		Files.writeString(db.getWorkTree().toPath().resolve("a"), "resolved\n");
		NativeFsMonitorIndexSupport.report(db, "a");
		var expected = NativeFsMonitorIndexSupport.mutate(db, "add", "--", "a");
		try (var git = org.eclipse.jgit.api.Git.wrap(db)) {
			git.add().addFilepattern("a").call();
		}
		assertArrayEquals(expected.payload(), NativeFsMonitorIndexSupport.extension(
				Files.readAllBytes(db.getIndexFile().toPath())));
		NativeFsMonitorWorkloadSupport.assertStagedMatches(db, expected.index());
		assertEquals(0, db.readDirCache().getEntry("a").getStage());
	}

	@Test
	public void relevantSettersInvalidateEntryCorrelation() throws Exception {
		NativeFsMonitorIndexSupport.prepare(db);
		for (String change : List.of("mode", "id", "raw-id", "mtime", "ctime",
				"length", "stage", "assume", "copy", "update")) {
			DirCache index = db.readDirCache();
			DirCacheEntry entry = index.getEntry(0);
			assertTrue(change, index.isFsMonitorValid(0));
			switch (change) {
			case "mode" -> entry.setFileMode(FileMode.EXECUTABLE_FILE);
			case "id" -> entry.setObjectId(entry.getObjectId());
			case "raw-id" -> {
				byte[] id = new byte[20];
				entry.getObjectId().copyRawTo(id, 0);
				entry.setObjectIdFromRaw(id, 0);
			}
			case "mtime" ->
				entry.setLastModified(entry.getLastModifiedInstant());
			case "ctime" -> entry.setCreationTime(0);
			case "length" -> entry.setLength(entry.getLength());
			case "stage" -> entry.setStage(1);
			case "assume" -> entry.setAssumeValid(true);
			case "copy" -> entry.copyMetaData(new DirCacheEntry(entry));
			case "update" -> entry.setUpdateNeeded(true);
			default -> throw new AssertionError(change);
			}
			assertFalse(change, index.isFsMonitorValid(0));
			assertTrue("Unrelated entries retain their context",
					index.isFsMonitorValid(2));
		}
	}

	@Test
	public void copiesRetainValidityOnlyWithinTheOriginatingIndexContext()
			throws Exception {
		NativeFsMonitorIndexSupport.prepare(db);
		DirCache first = db.readDirCache();
		DirCacheEntry copy = new DirCacheEntry(first.getEntry(0));
		var builder = first.builder();
		builder.add(copy);
		builder.add(first.getEntry(1));
		builder.add(first.getEntry(2));
		builder.finish();
		assertTrue(first.isFsMonitorValid(0));
		DirCache second = db.readDirCache();
		builder = second.builder();
		builder.add(copy);
		builder.add(second.getEntry(1));
		builder.add(second.getEntry(2));
		builder.finish();
		assertFalse(
				"A token from another index read cannot validate this entry",
				second.isFsMonitorValid(0));
		assertTrue(second.isFsMonitorValid(2));
	}

	@Test
	public void disabledWritePolicyAndExplicitOverrideMatchNative()
			throws Exception {
		for (String value : Arrays.asList(null, "false", "0", "0g", "",
				"true")) {
			NativeFsMonitorIndexSupport.prepare(db);
			if (value == null)
				db.getConfig().unset("core", null, "fsmonitor");
			else
				db.getConfig().setString("core", null, "fsmonitor", value);
			db.getConfig().save();
			// Native true would start a daemon; test retained storage without
			// starting it.
			boolean enabled = "true".equals(value);
			if (!enabled) {
				var expected = NativeFsMonitorIndexSupport.mutate(db,
						"update-index", "--force-write-index");
				assertNull(expected.payload());
			}
			assertNotNull("Raw storage reads do not apply disabled writer policy",
					db.readDirCache().getFsMonitorCache());
			DirCache index = db.lockDirCache();
			try {
				assertEquals("Writer policy applies before mutation", enabled,
						index.getFsMonitorCache() != null);
				index.write();
				assertTrue(index.commit());
			} finally {
				index.unlock();
			}
			assertEquals(enabled, NativeFsMonitorIndexSupport.extension(
					Files.readAllBytes(db.getIndexFile().toPath())) != null);
		}
		NativeFsMonitorIndexSupport.prepare(db);
		db.getConfig().setBoolean("core", null, "fsmonitor", false);
		var selected = db.readDirCache().getFsMonitorCache();
		DirCache index = db.lockDirCache();
		try {
			index.setFsMonitorCache(selected);
			index.write();
			assertTrue(index.commit());
		} finally {
			index.unlock();
		}
		assertNotNull(db.readDirCache().getFsMonitorCache());
	}

	@Test
	public void binaryTokensAndVersionOneUnsignedTimestampsRoundTrip()
			throws Exception {
		BitSet dirty = new BitSet();
		dirty.set(1);
		dirty.set(65);
		dirty.set(129);
		byte[] token = { (byte) 0xff, 'x', '\n' };
		FsMonitorCache source = new FsMonitorCache(token, dirty);
		FsMonitorCache parsed = FsMonitorCache.read(source.write(), 130);
		assertArrayEquals(token, parsed.getToken());
		assertEquals(dirty, parsed.getDirtyEntries());
		byte[] v2 = new FsMonitorCache(new byte[] { 'x' }, dirty).write();
		byte[] bitmap = Arrays.copyOfRange(v2, 10, v2.length);
		ByteBuffer v1 = ByteBuffer.allocate(16 + bitmap.length);
		v1.putInt(1).putLong(-1L).putInt(bitmap.length).put(bitmap);
		parsed = FsMonitorCache.read(v1.array(), 130);
		assertEquals(1, parsed.getVersion());
		assertArrayEquals(
				"18446744073709551615".getBytes(StandardCharsets.US_ASCII),
				parsed.getToken());
		assertEquals(2, ByteBuffer.wrap(parsed.write()).getInt());
		assertEquals(dirty,
				FsMonitorCache.read(parsed.write(), 130).getDirtyEntries());
	}

	@Test
	public void malformedOptionalStorageFallsBackWithoutWeakeningChecksum()
			throws Exception {
		NativeFsMonitorIndexSupport.prepare(db);
		byte[] index = Files.readAllBytes(db.getIndexFile().toPath());
		byte[] payload = NativeFsMonitorIndexSupport.extension(index);
		int offset = findPayload(index, payload);
		ByteBuffer.wrap(index).putInt(offset, 99);
		System.arraycopy(
				Constants.newMessageDigest()
						.digest(Arrays.copyOf(index, index.length - 20)),
				0, index, index.length - 20, 20);
		Files.write(db.getIndexFile().toPath(), index);
		DirCache parsed = db.readDirCache();
		assertEquals(3, parsed.getEntryCount());
		assertNull(parsed.getFsMonitorCache());
		index[index.length - 1] ^= 1;
		Files.write(db.getIndexFile().toPath(), index);
		assertThrows(CorruptObjectException.class, () -> db.readDirCache());
	}

	@Test
	public void malformedAndOutOfRangeBitmapsAreRejected() throws Exception {
		byte[] valid = new FsMonitorCache(new byte[] { 'x' }, new BitSet())
				.write();
		for (int length = 0; length < valid.length; length++) {
			byte[] truncated = Arrays.copyOf(valid, length);
			assertThrows(IOException.class,
					() -> FsMonitorCache.read(truncated, 3));
		}
		BitSet dirty = new BitSet();
		dirty.set(3);
		byte[] outOfRange = new FsMonitorCache(new byte[] { 'x' }, dirty)
				.write();
		assertThrows(IOException.class,
				() -> FsMonitorCache.read(outOfRange, 3));
		byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
		assertThrows(IOException.class, () -> FsMonitorCache.read(trailing, 3));
		byte[] invalidRlw = valid.clone();
		ByteBuffer.wrap(invalidRlw).putInt(invalidRlw.length - 4, 1);
		assertThrows(IOException.class,
				() -> FsMonitorCache.read(invalidRlw, 3));
		assertThrows(IllegalArgumentException.class,
				() -> new FsMonitorCache(new byte[] { 0 }, new BitSet()));
	}

	private static int findPayload(byte[] index, byte[] payload) {
		for (int offset = 20; offset + payload.length <= index.length
				- 20; offset++) {
			if (ByteBuffer.wrap(index).getInt(offset - 8) == 0x46534d4e
					&& Arrays.equals(index, offset, offset + payload.length,
							payload, 0, payload.length))
				return offset;
		}
		throw new AssertionError("FSMN payload not found");
	}
}
