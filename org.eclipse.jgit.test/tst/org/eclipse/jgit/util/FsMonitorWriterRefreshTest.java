/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.junit.NativeFsMonitorIndexSupport;
import org.eclipse.jgit.junit.NativeFsMonitorWorkloadSupport;
import org.eclipse.jgit.junit.NativeUntrackedCheckoutSupport;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.junit.Test;

/** Native t7519 refresh-before-write and dirty-entry preservation. */
public class FsMonitorWriterRefreshTest extends RepositoryTestCase {

	@Test
	public void ignoredReportingModesKeepSeparateMonitorSnapshots() throws Exception {
		NativeFsMonitorIndexSupport.prepare(db);
		Files.createDirectories(db.getDirectory().toPath().resolve("info"));
		Files.writeString(db.getDirectory().toPath().resolve("info/exclude"), "hidden\n");
		writeTrashFile("hidden", "ignored\n");
		db.getConfig().setBoolean("jgit", null, "fsmonitorCache", true);
		db.getConfig().save();
		try (Git git = Git.wrap(db)) {
			var compact = git.status().setReportIgnored(false).call();
			var complete = git.status().call();
			assertTrue(compact.getIgnoredNotInIndex().isEmpty());
			assertEquals(Set.of("hidden"), complete.getIgnoredNotInIndex());
			Path file = db.getWorkTree().toPath().resolve("a");
			var time = Files.getLastModifiedTime(file);
			Files.writeString(file, "next\n");
			Files.setLastModifiedTime(file, time);
			NativeFsMonitorIndexSupport.reportToken(db, "mode-clock", "a");
			assertEquals(Set.of("a"), git.status().setReportIgnored(false).call().getModified());
			assertEquals(Set.of("a"), git.status().call().getModified());
		}
	}

	@Test
	public void notificationsSurviveIdenticalIndexWrites() throws Exception {
		NativeFsMonitorIndexSupport.prepare(db);
		NativeFsMonitorIndexSupport.report(db, "outside");
		byte[] before = Files.readAllBytes(db.getIndexFile().toPath());
		var events = new java.util.ArrayList<org.eclipse.jgit.events.IndexChangedEvent>();
		var listener = db.getListenerList().addIndexChangedListener(events::add);
		var index = db.lockDirCache();
		try {
			index.write();
			assertTrue(index.commit());
		} finally {
			index.unlock();
			listener.remove();
		}
		assertArrayEquals(before, Files.readAllBytes(db.getIndexFile().toPath()));
		assertEquals(1, events.size());
		assertEquals(Set.of("outside"), events.get(0).getFsMonitorPaths());
		assertArrayEquals(db.readDirCache().getReadIndexChecksum(),
				events.get(0).getIndexChecksum());
	}

	@Test
	public void eventScopeIsImmutableAndOldConstructorIsUnknown() {
		var paths = new java.util.HashSet<>(Set.of("outside"));
		byte[] checksum = new byte[] { 1, 2, 3 };
		var event = new org.eclipse.jgit.events.IndexChangedEvent(true, paths, checksum);
		checksum[0] = 9;
		event.getIndexChecksum()[1] = 9;
		assertArrayEquals(new byte[] { 1, 2, 3 }, event.getIndexChecksum());
		paths.clear();
		assertEquals(Set.of("outside"), event.getFsMonitorPaths());
		org.junit.Assert.assertThrows(UnsupportedOperationException.class,
				() -> event.getFsMonitorPaths().clear());
		assertEquals(null,
				new org.eclipse.jgit.events.IndexChangedEvent(false).getFsMonitorPaths());
	}

	@Test
	public void writerEventsRetainKnownAndUnknownScope() throws Exception {
		for (String path : List.of("none", "a", "dir/", ".git/index", "/",
				".git/info/exclude", ".gitignore", "../outside")) {
			NativeFsMonitorIndexSupport.prepare(db);
			NativeFsMonitorIndexSupport.reportToken(db, "scope-clock",
					path.equals("none") ? new String[0] : new String[] { path });
			var events = new java.util.ArrayList<org.eclipse.jgit.events.IndexChangedEvent>();
			var index = org.eclipse.jgit.dircache.DirCache.lock(db, events::add);
			try {
				index.write();
				assertTrue(index.commit());
			} finally {
				index.unlock();
			}
			assertEquals(path, 1, events.size());
			Set<String> expected = switch (path) {
			case "none", ".git/index" -> Set.of();
			case "a" -> Set.of("a");
			case "dir/" -> Set.of("dir");
			default -> null;
			};
			assertEquals(path, expected, events.get(0).getFsMonitorPaths());
		}
	}

	@Test
	public void writesAdvanceTokenAndPreserveDirtyEntries() throws Exception {
		for (String notification : List.of("none", "a", "/", "failure")) {
			NativeFsMonitorIndexSupport.prepare(db);
			NativeFsMonitorIndexSupport.reportToken(db, "advanced-clock",
					notification.equals("a") || notification.equals("/")
							? new String[] { notification }
							: new String[0]);
			if (notification.equals("failure")) {
				Files.writeString(Path.of(
						db.getConfig().getString("core", null, "fsmonitor")),
						"#!/bin/sh\nexit 1\n");
			}
			compareNativeWriter();
		}
	}

	@Test
	public void directoryNotificationsDoNotInvalidateLexicalNeighbors()
			throws Exception {
		for (String notification : List.of("dir", "dir/", "DIR", "DIR/")) {
			NativeFsMonitorIndexSupport.prepare(db);
			for (String path : List.of("dir/file", "dir/other", "dir-other",
					"dir2/unaffected")) {
				writeTrashFile(path, "base\n");
			}
			var added = NativeFsMonitorIndexSupport.mutate(db, "add", ".");
			Files.copy(added.index(), db.getIndexFile().toPath(),
					StandardCopyOption.REPLACE_EXISTING);
			db.getConfig().setBoolean("core", null, "ignoreCase", true);
			db.getConfig().save();
			NativeFsMonitorIndexSupport.reportToken(db, "directory-clock",
					notification);
			compareNativeWriter();
			var index = db.readDirCache();
			assertTrue(index.isFsMonitorValid(index.findEntry("dir-other")));
			assertTrue(
					index.isFsMonitorValid(index.findEntry("dir2/unaffected")));
		}
	}

	@Test
	public void advancingTokenDoesNotLoseEarlierRestoredMtimeChanges()
			throws Exception {
		NativeFsMonitorIndexSupport.prepare(db);
		Path file = db.getWorkTree().toPath().resolve("a");
		var time = Files.getLastModifiedTime(file);
		Files.writeString(file, "next\n");
		Files.setLastModifiedTime(file, time);
		NativeFsMonitorIndexSupport.reportToken(db, "first-clock", "a");
		compareNativeWriter();
		NativeFsMonitorIndexSupport.reportToken(db, "second-clock");
		compareNativeWriter();
		byte[] original = Files.readAllBytes(db.getIndexFile().toPath());
		try (Git git = Git.wrap(db)) {
			assertEquals(Set.of("a"), git.status().call().getModified());
			assertEquals(Set.of("a"), git.status().call().getModified());
		}
		assertArrayEquals(original,
				Files.readAllBytes(db.getIndexFile().toPath()));
	}

	@Test
	public void notificationsInvalidateNativeUntrackedRecords()
			throws Exception {
		for (String mode : List.of("normal", "all")) {
			for (String path : List.of("sub/tracked", "sub/", "/", ".git/index",
					"sub/.git/index")) {
				db.getConfig().setBoolean("core", null, "fsmonitor", false);
				db.getConfig().save();
				NativeUntrackedCheckoutSupport.prepare(db, mode, "two");
				Path hook = db.getDirectory().toPath()
						.resolve("hooks/fsmonitor-index");
				Files.createDirectories(hook.getParent());
				Files.writeString(hook,
						"#!/bin/sh\ncat .git/fsmonitor-index.response\n");
				assertTrue(hook.toFile().setExecutable(true));
				db.getConfig().setString("core", null, "fsmonitor",
						hook.toString());
				db.getConfig().setInt("core", null, "fsmonitorHookVersion", 2);
				db.getConfig().save();
				NativeFsMonitorIndexSupport.reportToken(db, "before-clock");
				var initialized = NativeFsMonitorIndexSupport.mutate(db,
						"update-index", "--fsmonitor", "--force-write-index");
				Files.copy(initialized.index(), db.getIndexFile().toPath(),
						StandardCopyOption.REPLACE_EXISTING);
				NativeFsMonitorIndexSupport.reportToken(db, "after-clock",
						path);
				compareNativeWriter();
			}
		}
	}

	private void compareNativeWriter() throws Exception {
		var expected = NativeFsMonitorIndexSupport.mutate(db, "update-index",
				"--force-write-index");
		var index = db.lockDirCache();
		try {
			assertNotNull(index.getFsMonitorCache());
			index.write();
			assertTrue(index.commit());
		} finally {
			index.unlock();
		}
		assertArrayEquals(expected.payload(), NativeFsMonitorIndexSupport
				.extension(Files.readAllBytes(db.getIndexFile().toPath())));
		assertArrayEquals(
				NativeFsMonitorIndexSupport.untrackedExtension(
						Files.readAllBytes(expected.index())),
				NativeFsMonitorIndexSupport.untrackedExtension(
						Files.readAllBytes(db.getIndexFile().toPath())));
		NativeFsMonitorWorkloadSupport.assertStagedMatches(db,
				expected.index());
	}
}
