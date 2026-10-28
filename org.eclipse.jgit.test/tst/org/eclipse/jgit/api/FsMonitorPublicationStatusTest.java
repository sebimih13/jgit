/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/documents/edl-v10.php.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.eclipse.jgit.junit.NativeFsMonitorIndexSupport;
import org.eclipse.jgit.junit.NativeFsMonitorWorkloadSupport;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.junit.Test;

/** A result's scan clock must survive its own optional cache publication. */
public class FsMonitorPublicationStatusTest extends RepositoryTestCase {
	@Test
	public void firstResultWithMissingHookStillChecksContents() throws Exception {
		checkInitiallyUnavailableHook(true);
	}

	@Test
	public void firstResultWithNonExecutableHookStillChecksContents() throws Exception {
		checkInitiallyUnavailableHook(false);
	}

	private void checkInitiallyUnavailableHook(boolean missing) throws Exception {
		prepare(false);
		Path executable = Path.of(db.getConfig().getString("core", null, "fsmonitor"));
		if (missing) Files.delete(executable);
		else assertTrue(executable.toFile().setExecutable(false, false));
		Path a = db.getWorkTree().toPath().resolve("a");
		var time = Files.getLastModifiedTime(a);
		Files.writeString(a, "next\n");
		Files.setLastModifiedTime(a, time);
		byte[] original = Files.readAllBytes(db.getIndexFile().toPath());
		try (Git git = Git.wrap(db)) {
			assertEquals(Set.of("a"), git.status().call().getModified());
			assertEquals(Set.of("a"), git.status().call().getModified());
		}
		NativeFsMonitorWorkloadSupport.assertNativeDiff(db, false, null, Set.of("a"));
		assertArrayEquals(original, Files.readAllBytes(db.getIndexFile().toPath()));
	}

	@Test
	public void ownPublicationCanCreateAnInitiallyAbsentIndex() throws Exception {
		Path executable = db.getDirectory().toPath().resolve("hooks/empty-monitor");
		Files.createDirectories(executable.getParent());
		Files.writeString(executable, "#!/bin/sh\nprintf 'initial-clock\\000'\n");
		assertTrue(executable.toFile().setExecutable(true));
		db.getConfig().setString("core", null, "fsmonitor", executable.toString());
		db.getConfig().setInt("core", null, "fsmonitorHookVersion", 2);
		db.getConfig().setBoolean("core", null, "untrackedCache", true);
		db.getConfig().setBoolean("jgit", null, "fsmonitorCache", true);
		db.getConfig().setString("status", null, "showUntrackedFiles", "all");
		assertFalse(db.getIndexFile().exists());
		writeTrashFile("loose", "new\n");
		try (Git git = Git.wrap(db)) {
			Status first = status(git);
			assertEquals(Set.of("loose"), first.getUntracked());
			assertEquals(0, db.readDirCache().getEntryCount());
			assertTrue(db.readDirCache().getUntrackedCache().root.valid);
			assertTrue(db.readDirCache().getFsMonitorCache().getDirtyEntries().isEmpty());
			assertSame(first, status(git));
		}
	}


	private void prepare(boolean withoutSavedMonitor) throws Exception {
		if (withoutSavedMonitor) {
			NativeFsMonitorIndexSupport.prepareWithoutExtension(db);
		} else {
			NativeFsMonitorIndexSupport.prepare(db);
		}
		db.getConfig().setBoolean("core", null, "untrackedCache", true);
		db.getConfig().setBoolean("jgit", null, "fsmonitorCache", true);
		db.getConfig().setString("status", null, "showUntrackedFiles", "all");
		db.getConfig().save();
	}

	private void hook(String script) throws Exception {
		Files.writeString(Path.of(db.getConfig().getString("core", null, "fsmonitor")),
				"#!/bin/sh\nprintf '%s\\n' \"$2\" >> .git/publication-queries\n" + script);
	}

	private Status status(Git git) throws Exception {
		return git.status().setReportIgnored(false).setUpdateUntrackedCache(true).call();
	}

	@Test
	public void ownPublicationRetainsScanClockAndAllowsQuietResultReuse() throws Exception {
		checkQuietReuse(false);
	}

	@Test
	public void initialPublicationWithoutSavedFSMNAllowsQuietResultReuse() throws Exception {
		checkQuietReuse(true);
	}

	private void checkQuietReuse(boolean withoutSavedMonitor) throws Exception {
		prepare(withoutSavedMonitor);
		hook("case \"$2\" in scan-clock|writer-clock) printf 'writer-clock\\000';;\n"
				+ "*) printf 'scan-clock\\000a\\000';; esac\n");
		try (Git git = Git.wrap(db)) {
			Status first = status(git);
			assertTrue(first.isClean());
			assertTrue(db.readDirCache().getUntrackedCache().root.valid);
			assertTrue(db.readDirCache().getFsMonitorCache().getDirtyEntries().isEmpty());
			byte[] published = Files.readAllBytes(db.getIndexFile().toPath());
			assertSame(first, status(git));
			assertArrayEquals(published, Files.readAllBytes(db.getIndexFile().toPath()));
			var queries = Files.readAllLines(db.getDirectory().toPath().resolve("publication-queries"));
			assertEquals(3, queries.size());
			assertEquals("scan-clock", queries.get(1));
			assertEquals("The result must not advance to the later writer clock", "scan-clock", queries.get(2));
		}
	}

	@Test
	public void lateWriterChangesRemainVisibleOnTheNextResultQuery() throws Exception {
		checkLateWriterChanges(true);
	}

	@Test
	public void knownLateNotificationsSurviveAnEmptyFollowingProviderReply() throws Exception {
		checkLateWriterChanges(false);
	}

	private void checkLateWriterChanges(boolean redeliver) throws Exception {
		prepare(false);
		Path a = db.getWorkTree().toPath().resolve("a");
		Files.writeString(db.getDirectory().toPath().resolve("publication-stamp"), "");
		Files.setLastModifiedTime(db.getDirectory().toPath().resolve("publication-stamp"),
				Files.getLastModifiedTime(a));
		hook("if test \"$2\" = scan-clock && test -e .git/index.lock && ! test -e .git/publication-done; then\n"
				+ "printf 'next\\n' > a\ntouch -r .git/publication-stamp a\n"
				+ "printf 'late\\n' > late\ntouch .git/publication-done\n"
				+ "printf 'writer-clock\\000a\\000late\\000'\n"
				+ "elif test -e .git/publication-done && test \"$2\" = scan-clock; then\n"
				+ (redeliver ? "printf 'writer-clock\\000a\\000late\\000'\n"
						: "printf 'writer-clock\\000'\n")
				+ "elif test \"$2\" = writer-clock; then printf 'writer-clock\\000'\n"
				+ "else printf 'scan-clock\\000'; fi\n");
		try (Git git = Git.wrap(db)) {
			Status first = status(git);
			assertTrue("The first snapshot precedes the late mutation", first.isClean());
			assertTrue(Files.exists(db.getDirectory().toPath().resolve("publication-done")));
			var index = db.readDirCache();
			assertFalse(index.isFsMonitorValid(index.findEntry("a")));
			assertFalse(index.getUntrackedCache().root.valid);
			assertArrayEquals(org.eclipse.jgit.lib.Constants.encode(" M a\0?? late\0"),
					NativeFsMonitorIndexSupport.status(db, false));
			Status next = status(git);
			assertEquals(Set.of("a"), next.getModified());
			assertEquals(Set.of("late"), next.getUntracked());
			NativeFsMonitorWorkloadSupport.assertNativeDiff(db, false, null, Set.of("a"));
			assertSame(next, status(git));
		}
	}

	@Test
	public void externalIndexWriteDuringPublicationCannotAnchorAnOldResult() throws Exception {
		prepare(false);
		hook("case \"$2\" in scan-clock|writer-clock) printf 'writer-clock\\000';;\n"
				+ "*) printf 'scan-clock\\000';; esac\n");
		String blob = db.readDirCache().getEntry("a").getObjectId().name();
		Path external = NativeFsMonitorIndexSupport.mutate(db, "-c", "core.fsmonitor=false",
				"update-index", "--add", "--cacheinfo", "100644," + blob + ",ghost").index();
		var replaced = new java.util.concurrent.atomic.AtomicBoolean();
		var listener = db.getListenerList().addIndexChangedListener(event -> {
			if (replaced.compareAndSet(false, true)) {
				try {
					Files.copy(external, db.getIndexFile().toPath(),
							java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				} catch (java.io.IOException e) {
					throw new java.io.UncheckedIOException(e);
				}
			}
		});
		try (Git git = Git.wrap(db)) {
			Status first = status(git);
			assertEquals(Set.of(), first.getAdded());
			assertTrue(replaced.get());
			Status next = status(git);
			assertEquals(Set.of("ghost"), next.getAdded());
			assertEquals(Set.of("ghost"), next.getMissing());
			assertArrayEquals(org.eclipse.jgit.lib.Constants.encode("AD ghost\0"),
					NativeFsMonitorIndexSupport.status(db, false));
		} finally {
			listener.remove();
		}
	}

	@Test
	public void contextChangeDuringPublicationCannotAnchorAnOldResult() throws Exception {
		prepare(false);
		org.junit.Assume.assumeTrue(db.getFS().supportsExecute());
		db.getConfig().setBoolean("core", null, "filemode", false);
		db.getConfig().save();
		assertTrue(db.getFS().setExecute(db.getWorkTree().toPath().resolve("a").toFile(), true));
		hook("printf 'scan-clock\\000'\n");
		var changed = new java.util.concurrent.atomic.AtomicBoolean();
		var listener = db.getListenerList().addIndexChangedListener(event -> {
			if (changed.compareAndSet(false, true)) {
				db.getConfig().setBoolean("core", null, "filemode", true);
			}
		});
		try (Git git = Git.wrap(db)) {
			assertEquals(Set.of(), status(git).getModified());
			assertTrue(changed.get());
			assertEquals(Set.of("a"), status(git).getModified());
		} finally {
			listener.remove();
		}
	}
}
