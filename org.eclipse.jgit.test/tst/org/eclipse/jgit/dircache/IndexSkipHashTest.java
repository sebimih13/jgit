/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.dircache;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.NB;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Ports the index.skipHash cases from C Git t/t1600-index.sh. */
@RunWith(Parameterized.class)
public class IndexSkipHashTest extends RepositoryTestCase {
	@Parameterized.Parameters(name = "index version {0}")
	public static List<Object[]> versions() {
		return List.of(new Object[] { 2 }, new Object[] { 3 },
				new Object[] { 4 });
	}

	private final int version;

	public IndexSkipHashTest(int version) {
		this.version = version;
	}

	private void configure(Boolean skip, boolean many) {
		db.getConfig().setInt("index", null, "version", version);
		db.getConfig().setBoolean("feature", null, "manyFiles", many);
		if (skip == null)
			db.getConfig().unset("index", null, "skipHash");
		else
			db.getConfig().setBoolean("index", null, "skipHash",
					skip.booleanValue());
	}

	private void add(String content) throws Exception {
		writeTrashFile("a", content);
		try (Git git = new Git(db)) {
			git.add().addFilepattern("a").call();
		}
	}

	private void footer(Repository repository, boolean zero) throws Exception {
		byte[] bytes = Files.readAllBytes(repository.getIndexFile().toPath());
		byte[] actual = Arrays.copyOfRange(bytes, bytes.length - 20,
				bytes.length);
		if (zero)
			assertArrayEquals(new byte[20], actual);
		else {
			assertFalse(Arrays.equals(new byte[20], actual));
			assertArrayEquals(Constants.newMessageDigest()
					.digest(Arrays.copyOf(bytes, bytes.length - 20)), actual);
		}
	}

	@Test
	public void explicitSkipHashOnNewIndex() throws Exception {
		configure(true, false);
		add("1");
		footer(db, true);
		assertEquals(1, db.readDirCache().getEntryCount());
	}

	@Test
	public void manyFilesEnablesSkipHash() throws Exception {
		configure(null, true);
		add("1");
		footer(db, true);
	}

	@Test
	public void explicitFalseOverridesManyFiles() throws Exception {
		configure(false, true);
		add("1");
		footer(db, false);
	}

	@Test
	public void manyFilesDefaultsToVersionFourAndZeroFooter() throws Exception {
		configure(null, true);
		db.getConfig().unset("index", null, "version");
		add("1");
		footer(db, true);
		assertEquals(4, NB.decodeInt32(
				Files.readAllBytes(db.getIndexFile().toPath()), 4));
	}

	@Test
	public void defaultKeepsChecksum() throws Exception {
		configure(null, false);
		add("1");
		footer(db, false);
	}

	@Test
	public void existingIndexHonorsConfigChangesAndPreservesVersion()
			throws Exception {
		configure(false, false);
		add("1");
		footer(db, false);
		configure(true, false);
		add("2");
		footer(db, true);
		configure(false, false);
		add("3");
		footer(db, false);
		assertEquals(version, NB.decodeInt32(
				Files.readAllBytes(db.getIndexFile().toPath()), 4));
	}

	@Test
	public void existingIndexIgnoresUnneededDefaultVersion() throws Exception {
		configure(false, false);
		add("1");
		db.getConfig().setString("index", null, "version", "invalid");
		db.getConfig().setBoolean("index", null, "skipHash", true);
		add("2");
		footer(db, true);
		assertEquals(version, NB.decodeInt32(
				Files.readAllBytes(db.getIndexFile().toPath()), 4));
	}

	@Test
	public void zeroChecksumIsAcceptedWithSkipHashDisabled() throws Exception {
		configure(true, false);
		add("1");
		configure(false, false);
		assertEquals(1, db.readDirCache().getEntryCount());
	}

	@Test
	public void submoduleUsesItsOwnConfiguration() throws Exception {
		configure(false, false);
		add("1");
		try (Git git = new Git(db)) {
			git.commit().setMessage("base").call();
			try (Repository child = git.submoduleAdd().setPath("sub")
					.setURI(db.getDirectory().toURI().toString()).call()) {
				child.getConfig().setBoolean("index", null, "skipHash", true);
				child.getConfig().save();
				Files.delete(child.getIndexFile().toPath());
				try (Git sub = Git.wrap(child)) {
					sub.add().addFilepattern("a").call();
				}
				footer(child, true);
				footer(db, false);
				assertEquals(1, child.readDirCache().getEntryCount());
			}
		}
	}

	private DirCache newSkippedCache() throws Exception {
		configure(true, false);
		add("1");
		DirCacheEntry original = db.readDirCache().getEntry(0);
		Files.delete(db.getIndexFile().toPath());
		DirCache cache = db.lockDirCache();
		DirCacheBuilder builder = cache.builder();
		builder.add(original);
		builder.finish();
		cache.write();
		assertTrue(cache.commit());
		footer(db, true);
		return cache;
	}

	private void invalidateSnapshot() throws Exception {
		var path = db.getIndexFile().toPath();
		Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime
				.fromMillis(Files.getLastModifiedTime(path).toMillis() - 5000));
	}

	@Test
	public void reusedCacheStillVerifiesNonzeroChecksums() throws Exception {
		DirCache cache = newSkippedCache();
		byte[] bytes = Files.readAllBytes(db.getIndexFile().toPath());
		bytes[bytes.length - 1] = 1;
		Files.write(db.getIndexFile().toPath(), bytes);
		invalidateSnapshot();
		assertThrows(CorruptObjectException.class, cache::read);
	}

	@Test
	public void reusedCachePublishesChangesAfterSkippedChecksum()
			throws Exception {
		DirCache cache = newSkippedCache();
		invalidateSnapshot();
		cache.read();
		AtomicInteger events = new AtomicInteger();
		var listener = db.getListenerList()
				.addIndexChangedListener(e -> events.incrementAndGet());
		try {
			assertTrue(cache.lock());
			cache.getEntry(0).setAssumeValid(true);
			cache.write();
			assertTrue(cache.commit());
			assertEquals(1, events.get());
			assertTrue(db.readDirCache().getEntry(0).isAssumeValid());
		} finally {
			listener.remove();
		}
	}
}
