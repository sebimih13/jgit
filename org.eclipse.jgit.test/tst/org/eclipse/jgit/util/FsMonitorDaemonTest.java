/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.eclipse.jgit.junit.NativeFsMonitorDaemonSupport;
import org.eclipse.jgit.junit.NativeFsMonitorIndexSupport;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.junit.Test;

/** Native t7527 initial query, history reuse and flush scenarios. */
public class FsMonitorDaemonTest extends RepositoryTestCase {
	@Test
	public void persistedDaemonBitsValidateAndSameStatEditsRemainVisible()
			throws Exception {
		NativeFsMonitorIndexSupport.prepareWithoutExtension(db);
		try (var daemon = NativeFsMonitorDaemonSupport.start(db)) {
			installNativeDaemonIndex();
			byte[] original = java.nio.file.Files
					.readAllBytes(db.getIndexFile().toPath());
			var index = db.readDirCache();
			var query = FsMonitor.queryStoredIndex(db, index);
			assertNotNull(query.probe());
			org.junit.Assert.assertEquals(java.util.Set.of("a", "m", "z"), query
					.getCheckoutValidEntries(db, index).stream()
					.map(org.eclipse.jgit.dircache.DirCacheEntry::getPathString)
					.collect(java.util.stream.Collectors.toSet()));
			var file = db.getWorkTree().toPath().resolve("a");
			var time = java.nio.file.Files.getLastModifiedTime(file);
			java.util.concurrent.TimeUnit.MILLISECONDS
					.sleep(1010 - System.currentTimeMillis() % 1000);
			java.nio.file.Files.writeString(file, "edit\n");
			java.nio.file.Files.setLastModifiedTime(file, time);
			org.eclipse.jgit.junit.NativeFsMonitorWorkloadSupport
					.assertNativeDiff(db, false, null, java.util.Set.of("a"));
			try (var git = org.eclipse.jgit.api.Git.wrap(db)) {
				org.junit.Assert.assertEquals(java.util.Set.of("a"),
						git.status().call().getModified());
			}
			org.junit.Assert.assertArrayEquals(original, java.nio.file.Files
					.readAllBytes(db.getIndexFile().toPath()));
			daemon.stop();
			var failed = FsMonitor.queryStoredIndex(db, db.readDirCache());
			org.junit.Assert.assertNull(failed.probe());
			assertTrue(failed.forceContentCheck());
		}
	}

	@Test
	public void daemonWriterInitializesDirtyStateAndRetainsOlderDirtyBits()
			throws Exception {
		NativeFsMonitorIndexSupport.prepareWithoutExtension(db);
		try (var daemon = NativeFsMonitorDaemonSupport.start(db)) {
			var expected = NativeFsMonitorIndexSupport.mutate(db,
					"update-index", "--fsmonitor", "--force-write-index");
			var nativeIndex = org.eclipse.jgit.dircache.DirCache
					.read(expected.index().toFile(), db.getFS());
			var index = db.lockDirCache();
			try {
				assertNotNull(index.getFsMonitorCache());
				org.junit.Assert.assertEquals(
						nativeIndex.getFsMonitorCache().getDirtyEntries(),
						index.getFsMonitorCache().getDirtyEntries());
				String actualToken = new String(
						index.getFsMonitorCache().getToken(),
						java.nio.charset.StandardCharsets.UTF_8);
				assertTrue(actualToken.startsWith("builtin:"));
				index.write();
				assertTrue(index.commit());
			} finally {
				index.unlock();
			}
			var reopened = db.lockDirCache();
			try {
				org.junit.Assert.assertEquals(
						nativeIndex.getFsMonitorCache().getDirtyEntries(),
						reopened.getFsMonitorCache().getDirtyEntries());
				assertNotNull(FsMonitor.refreshIndexWithChanges(db, reopened)
						.paths());
			} finally {
				reopened.unlock();
			}
		}
	}

	private void installNativeDaemonIndex() throws Exception {
		var expected = NativeFsMonitorIndexSupport.mutate(db, "update-index",
				"--fsmonitor", "--fsmonitor-valid", "a", "m", "z",
				"--force-write-index");
		java.nio.file.Files.copy(expected.index(), db.getIndexFile().toPath(),
				java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	@Test
	public void booleanProviderQueriesRunningNativeDaemonAndFlushInvalidates()
			throws Exception {
		NativeFsMonitorIndexSupport.prepareWithoutExtension(db);
		db.getConfig().setBoolean("jgit", null, "fsmonitorCache", true);
		try (var daemon = NativeFsMonitorDaemonSupport.start(db)) {
			db.getConfig().setString("core", null, "fsmonitorHookVersion",
					"ignored-by-daemon");
			assertNotNull(FsMonitor.parse(daemon.query("0")));
			assertNotNull(
					FsMonitor.parse(FsMonitorDaemonClient.query(db, "0")));
			var state = new FsMonitor.State();
			var first = state.query(db);
			assertNotNull("core.fsmonitor=true must query the native daemon",
					first);
			assertFalse(first.isUnchanged());
			state.remember(first);
			var repeated = state.query(db);
			assertNotNull(repeated);
			assertTrue(repeated.isUnchanged());
			daemon.flush();
			var changed = state.query(db);
			assertNotNull(changed);
			assertFalse(changed.isUnchanged());
			assertTrue(changed.getChangedPaths().contains("/"));
		}
	}
}
