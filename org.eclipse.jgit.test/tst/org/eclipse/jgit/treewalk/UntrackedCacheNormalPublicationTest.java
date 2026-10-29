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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.junit.NativeUntrackedNormalSupport;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.IndexDiff;
import org.junit.Test;

/**
 * Native t7063 normal summaries and mode-bypass behavior from a Java full scan.
 */
public class UntrackedCacheNormalPublicationTest extends RepositoryTestCase {
	@Test
	public void defaultModePublishesNativeDirectorySummaries()
			throws Exception {
		checkInitial(null);
	}

	@Test
	public void explicitNormalModePublishesNativeDirectorySummaries()
			throws Exception {
		checkInitial("normal");
	}

	private void checkInitial(String mode) throws Exception {
		NativeUntrackedNormalSupport.prepare(db, mode);
		byte[] original = Files.readAllBytes(db.getIndexFile().toPath());
		try (Git git = Git.wrap(db)) {
			git.status().setReportIgnored(false).setUpdateUntrackedCache(false).call();
			assertArrayEquals(original,
					Files.readAllBytes(db.getIndexFile().toPath()));
			assertEquals(Set.of("three", "dtwo/two", "dthree/three"),
					git.status().setReportIgnored(false)
							.setUpdateUntrackedCache(true).call()
							.getUntracked());
			NativeUntrackedNormalSupport.assertReusable(db,
					Set.of("three", "dtwo/", "dthree/"));
			var root = db.readDirCache().getUntrackedCache().root;
			for (var child : root.directories) {
				String name = new String(child.name,
						java.nio.charset.StandardCharsets.UTF_8);
				assertTrue(child.recurse && child.valid);
				assertEquals(!name.equals("done"), child.checkOnly);
			}
			// t7063: an all-mode command bypasses, and preserves, normal
			// records.
			assertEquals(Set.of("three", "dtwo/two", "dthree/three"),
					NativeUntrackedNormalSupport.nativeStatus(db, "all",
							false));
			assertEquals(Set.of("three", "dtwo/two", "dthree/three"),
					git.status().call().getUntracked());
			NativeUntrackedNormalSupport.assertReusable(db,
					Set.of("three", "dtwo/", "dthree/"));
		}
	}

	@Test
	public void nestedSummariesSurviveFirstWitnessRemovalAndIgnoreEdits()
			throws Exception {
		NativeUntrackedNormalSupport.prepare(db, "normal");
		NativeUntrackedNormalSupport.write(db, "dtwo/nested/next", "new\n");
		NativeUntrackedNormalSupport.write(db, "dtwo/.gitignore", "*.tmp\n");
		NativeUntrackedNormalSupport.write(db, "dtwo/hidden.tmp", "ignored\n");
		Files.createDirectories(db.getWorkTree().toPath().resolve("empty"));
		NativeUntrackedNormalSupport.ageDirectories(db);
		try (Git git = Git.wrap(db)) {
			git.status().setReportIgnored(false).setUpdateUntrackedCache(true)
					.call();
			NativeUntrackedNormalSupport.assertReusable(db,
					Set.of("three", "dtwo/", "dthree/"));
			Files.delete(db.getWorkTree().toPath().resolve("dtwo/two"));
			Files.delete(db.getWorkTree().toPath().resolve("dtwo/.gitignore"));
			NativeUntrackedNormalSupport.ageDirectories(db);
			var status = git.status().setReportIgnored(false)
					.setUpdateUntrackedCache(true).call();
			assertEquals(Set.of("three", "dthree/three", "dtwo/nested/next",
					"dtwo/hidden.tmp"), status.getUntracked());
			NativeUntrackedNormalSupport.assertReusable(db,
					Set.of("three", "dtwo/", "dthree/"));
			assertEquals(status.getUntracked(), NativeUntrackedNormalSupport
					.nativeStatus(db, "all", false));
		}
	}

	@Test
	public void normalMonitorPublicationPreservesQuietResultReuse()
			throws Exception {
		NativeUntrackedNormalSupport.prepare(db, null);
		NativeUntrackedNormalSupport.enableMonitor(db);
		try (Git git = Git.wrap(db)) {
			var first = git.status().setReportIgnored(false)
					.setUpdateUntrackedCache(true).call();
			NativeUntrackedNormalSupport.assertReusable(db,
					Set.of("three", "dtwo/", "dthree/"));
			assertTrue(db.readDirCache().getFsMonitorCache() != null);
			byte[] published = Files.readAllBytes(db.getIndexFile().toPath());
			org.junit.Assert.assertSame(first,
					git.status().setReportIgnored(false)
							.setUpdateUntrackedCache(true).call());
			assertArrayEquals(published,
					Files.readAllBytes(db.getIndexFile().toPath()));
		}
	}

	@Test
	public void changingModeAfterScanRejectsTheOldPublication()
			throws Exception {
		NativeUntrackedNormalSupport.prepare(db, "normal");
		var diff = new IndexDiff(db, Constants.HEAD,
				new FileTreeIterator(db, true));
		diff.setReportIgnored(false);
		diff.diff();
		db.getConfig().setString("status", null, "showUntrackedFiles", "all");
		db.getConfig().save();
		byte[] original = Files.readAllBytes(db.getIndexFile().toPath());
		assertFalse(diff.updateUntrackedCache());
		assertArrayEquals(original,
				Files.readAllBytes(db.getIndexFile().toPath()));
		try (Git git = Git.wrap(db)) {
			git.status().setReportIgnored(false).setUpdateUntrackedCache(true)
					.call();
		}
		assertEquals(0, db.readDirCache().getUntrackedCache().dirFlags);
		assertEquals(Set.of("three", "dtwo/two", "dthree/three"),
				NativeUntrackedNormalSupport.nativeStatus(db, "all", true));
	}
}
