/*
 * Copyright (C) 2026, and others
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/documents/edl-v10.php.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.treewalk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.junit.NativeFsMonitorIndexSupport;
import org.eclipse.jgit.junit.NativeUntrackedNormalSupport;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.junit.Test;

/** Native status optional maintenance with complete Java ignored reporting. */
public class UntrackedCacheAutomaticPublicationTest extends RepositoryTestCase {
	private void prepare() throws Exception {
		NativeUntrackedNormalSupport.prepare(db, null);
		NativeUntrackedNormalSupport.write(db, "done/.gitignore", "*.tmp\n");
		NativeUntrackedNormalSupport.write(db, "done/hidden.tmp", "ignored\n");
		NativeUntrackedNormalSupport.ageDirectories(db);
	}

	private void assertStatus(Status status) throws Exception {
		assertEquals(
				Set.of("three", "dtwo/two", "dthree/three", "done/.gitignore"),
				status.getUntracked());
		assertEquals(Set.of("done/hidden.tmp"), status.getIgnoredNotInIndex());
		assertEquals(Set.of(), status.getAdded());
		assertEquals(Set.of(), status.getChanged());
		assertEquals(Set.of(), status.getRemoved());
		assertEquals(Set.of(), status.getModified());
		assertEquals(Set.of(), status.getMissing());
		assertEquals(Set.of(), status.getConflicting());
	}

	private void assertPublished() throws Exception {
		assertNotNull(db.readDirCache().getUntrackedCache());
		NativeUntrackedNormalSupport.assertReusable(db,
				Set.of("three", "dtwo/", "dthree/", "done/.gitignore"));
	}

	@Test
	public void ordinaryStatusMaintainsCacheWithoutLosingIgnoredResults()
			throws Exception {
		prepare();
		try (Git git = Git.wrap(db)) {
			assertStatus(git.status().call());
		}
		assertPublished();
	}

	@Test
	public void readOnlyOptOutDoesNotPreventLaterAutomaticPublication()
			throws Exception {
		prepare();
		NativeUntrackedNormalSupport.enableMonitor(db);
		byte[] original = Files.readAllBytes(db.getIndexFile().toPath());
		try (Git git = Git.wrap(db)) {
			assertStatus(git.status().setUpdateUntrackedCache(false).call());
			assertArrayEquals(original,
					Files.readAllBytes(db.getIndexFile().toPath()));
			assertStatus(git.status().call());
			assertPublished();
			byte[] published = Files.readAllBytes(db.getIndexFile().toPath());
			assertStatus(git.status().setUpdateUntrackedCache(false).call());
			assertArrayEquals(published,
					Files.readAllBytes(db.getIndexFile().toPath()));
		}
	}

	@Test
	public void optionalLocksDisabledThenEnabledRefreshesMaintenancePolicy()
			throws Exception {
		prepare();
		NativeUntrackedNormalSupport.enableMonitor(db);
		byte[] original = Files.readAllBytes(db.getIndexFile().toPath());
		try (Git git = Git.wrap(db)) {
			mockSystemReader.setProperty("GIT_OPTIONAL_LOCKS", "0");
			try {
				assertStatus(git.status().call());
				assertArrayEquals(original,
						Files.readAllBytes(db.getIndexFile().toPath()));
			} finally {
				mockSystemReader.setProperty("GIT_OPTIONAL_LOCKS", null);
			}
			assertStatus(git.status().call());
			assertPublished();
		}
	}

	@Test
	public void manyFilesAloneCreatesAndPopulatesAnAbsentExtension()
			throws Exception {
		prepare();
		removeCache();
		db.getConfig().unset("core", null, "untrackedCache");
		db.getConfig().setBoolean("feature", null, "manyFiles", true);
		db.getConfig().save();
		NativeUntrackedNormalSupport.enableMonitor(db);
		try (Git git = Git.wrap(db)) {
			var first = git.status().call();
			assertStatus(first);
			byte[] published = Files.readAllBytes(db.getIndexFile().toPath());
			assertArrayEquals(new byte[20], java.util.Arrays.copyOfRange(
					published, published.length - 20, published.length));
			org.junit.Assert.assertSame(first, git.status().call());
			assertArrayEquals(published,
					Files.readAllBytes(db.getIndexFile().toPath()));
		}
		assertPublished();
	}

	@Test
	public void keepModeMaintainsAnExistingExtension() throws Exception {
		prepare();
		db.getConfig().unset("core", null, "untrackedCache");
		db.getConfig().save();
		try (Git git = Git.wrap(db)) {
			assertStatus(git.status().call());
		}
		assertPublished();
	}

	@Test
	public void unconfiguredStatusDoesNotCreateAnExtension() throws Exception {
		prepare();
		removeCache();
		db.getConfig().unset("core", null, "untrackedCache");
		db.getConfig().save();
		byte[] original = Files.readAllBytes(db.getIndexFile().toPath());
		try (Git git = Git.wrap(db)) {
			assertStatus(git.status().call());
		}
		assertArrayEquals(original,
				Files.readAllBytes(db.getIndexFile().toPath()));
		assertNull(db.readDirCache().getUntrackedCache());
	}

	@Test
	public void disabledCacheIsRemovedLikeNativeStatus() throws Exception {
		prepare();
		assertNotNull(db.readDirCache().getUntrackedCache());
		db.getConfig().setBoolean("core", null, "untrackedCache", false);
		db.getConfig().save();
		var nativeIndex = NativeFsMonitorIndexSupport.mutate(db, "status",
				"--porcelain=v1", "-z");
		assertNull(NativeFsMonitorIndexSupport
				.untrackedExtension(Files.readAllBytes(nativeIndex.index())));
		try (Git git = Git.wrap(db)) {
			assertStatus(git.status().call());
		}
		assertNull(db.readDirCache().getUntrackedCache());
	}

	@Test
	public void lockedOrFilteredStatusDoesNotPublish() throws Exception {
		prepare();
		byte[] original = Files.readAllBytes(db.getIndexFile().toPath());
		var lock = db.getIndexFile().toPath().resolveSibling("index.lock");
		Files.writeString(lock, "busy");
		try (Git git = Git.wrap(db)) {
			assertStatus(git.status().call());
			assertEquals("busy", Files.readString(lock));
		} finally {
			Files.delete(lock);
		}
		assertArrayEquals(original,
				Files.readAllBytes(db.getIndexFile().toPath()));
		try (Git git = Git.wrap(db)) {
			git.status().addPath("done").call();
		}
		assertArrayEquals(original,
				Files.readAllBytes(db.getIndexFile().toPath()));
	}

	private void removeCache() throws Exception {
		var nativeIndex = NativeFsMonitorIndexSupport.mutate(db, "-c",
				"core.untrackedCache=false", "update-index",
				"--no-untracked-cache", "--force-write-index");
		Files.copy(nativeIndex.index(), db.getIndexFile().toPath(),
				StandardCopyOption.REPLACE_EXISTING);
		assertNull(db.readDirCache().getUntrackedCache());
	}
}
