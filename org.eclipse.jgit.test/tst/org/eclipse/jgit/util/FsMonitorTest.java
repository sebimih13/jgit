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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.junit.Test;

public class FsMonitorTest extends RepositoryTestCase {
	private File response;

	private void configure(String script) throws Exception {
		assumeFalse(SystemReader.getInstance().isWindows());
		response = new File(db.getDirectory(), "monitor-response");
		File hook = new File(db.getDirectory(), "hooks/fsmonitor-test");
		Files.createDirectories(hook.toPath().getParent());
		String code = script == null
				? "cat '" + response.getPath().replace("'", "'\\''") + "'\n"
				: script;
		Files.writeString(hook.toPath(), "#!/bin/sh\n" + code);
		assertTrue(hook.setExecutable(true));
		db.getConfig().setString("core", null, "fsmonitor",
				hook.getAbsolutePath());
		db.getConfig().setBoolean("jgit", null, "fsmonitorCache", true);
		reply("initial\0/\0");
	}

	private void reply(String data) throws Exception {
		Files.write(response.toPath(), data.getBytes(StandardCharsets.UTF_8));
	}

	private static void equal(Status a, Status b) {
		assertEquals(a.getAdded(), b.getAdded());
		assertEquals(a.getChanged(), b.getChanged());
		assertEquals(a.getRemoved(), b.getRemoved());
		assertEquals(a.getMissing(), b.getMissing());
		assertEquals(a.getModified(), b.getModified());
		assertEquals(a.getUntracked(), b.getUntracked());
		assertEquals(a.getIgnoredNotInIndex(), b.getIgnoredNotInIndex());
		assertEquals(a.getConflictingStageState(),
				b.getConflictingStageState());
		assertEquals(a.getUntrackedFolders(), b.getUntrackedFolders());
	}

	private Status baseline(Git git) throws Exception {
		db.getConfig().setBoolean("jgit", null, "fsmonitorCache", false);
		try {
			return git.status().call();
		} finally {
			db.getConfig().setBoolean("jgit", null, "fsmonitorCache", true);
		}
	}

	@Test
	public void emptyReplyReusesSuccessfulResultAndChangesFallBack()
			throws Exception {
		configure(null);
		try (Git git = new Git(db)) {
			writeTrashFile("tracked", "one");
			git.add().addFilepattern("tracked").call();
			git.commit().setMessage("base").call();
			Status first = git.status().call();
			reply("second\0");
			assertSame(first, git.status().call());
			writeTrashFile("tracked", "two"); // Same-size edit must use the
												// original scan.
			reply("third\0tracked\0");
			Status changed = git.status().call();
			assertNotSame(first, changed);
			assertEquals(Set.of("tracked"), changed.getModified());
			equal(baseline(git), changed);
			reply("fourth\0");
			assertSame(changed, git.status().call());
		}
	}

	@Test
	public void indexAndHeadChangesInvalidateEvenWithoutFilesystemPaths()
			throws Exception {
		configure(null);
		try (Git git = new Git(db)) {
			writeTrashFile("tracked", "one");
			Status untracked = git.status().call();
			git.add().addFilepattern("tracked").call();
			reply("next\0");
			Status staged = git.status().call();
			assertNotSame(untracked, staged);
			assertEquals(Set.of("tracked"), staged.getAdded());
			git.commit().setMessage("base").call();
			reply("next2\0");
			Status committed = git.status().call();
			assertNotSame(staged, committed);
			assertTrue(committed.isClean());
			equal(baseline(git), committed);
		}
	}

	@Test
	public void ignoreFilesConfigAndRecrawlInvalidate() throws Exception {
		configure(null);
		try (Git git = new Git(db)) {
			writeTrashFile("other", "one");
			Status initial = git.status().call();
			Files.createDirectories(
					new File(db.getDirectory(), "info").toPath());
			Files.writeString(
					new File(db.getDirectory(), "info/exclude").toPath(),
					"other\n");
			reply("next\0");
			Status ignored = git.status().call();
			assertNotSame(initial, ignored);
			equal(baseline(git), ignored);
			db.getConfig().setBoolean("core", null, "fileMode", false);
			reply("next2\0");
			assertNotSame(ignored, git.status().call());
			reply("recrawl\0/\0");
			Status recrawl = git.status().call();
			equal(baseline(git), recrawl);
		}
	}

	@Test
	public void malformedAndFailedHooksNeverReuse() throws Exception {
		configure(null);
		try (Git git = new Git(db)) {
			Status first = git.status().call();
			for (String response : new String[] { "", "token", "\0",
					"token\0\0", "token\0../outside\0" }) {
				reply(response);
				assertNotSame(first, git.status().call());
			}
			configure("exit 1\n");
			assertNotSame(first, git.status().call());
			db.getConfig().setInt("core", null, "fsmonitorHookVersion", 1);
			assertNotSame(first, git.status().call());
		}
	}

	@Test
	public void timeoutFallsBackWithinDeadline() throws Exception {
		configure("sleep 60\n");
		try (Git git = new Git(db)) {
			long start = System.nanoTime();
			assertTrue(git.status().call().isClean());
			assertTrue("Hook deadline must bound the call",
					System.nanoTime() - start < 10_000_000_000L);
		}
	}

	@Test
	public void gitMetadataOnlyNotificationsDoNotHideNestedRepositories()
			throws Exception {
		configure(null);
		try (Git git = new Git(db)) {
			Status first = git.status().call();
			reply("next\0.git\0.git/monitor-response\0");
			assertSame(first, git.status().call());
			writeTrashFile("nested/file", "new");
			reply("changed\0nested/.git\0nested/file\0");
			Status changed = git.status().call();
			assertNotSame(first, changed);
			equal(baseline(git), changed);
		}
	}

	@Test
	public void filteredAndCustomIteratorCallsAlwaysScan() throws Exception {
		configure(null);
		try (Git git = new Git(db)) {
			Status first = git.status().call();
			reply("next\0");
			assertNotSame(first, git.status().addPath("tracked").call());
			assertNotSame(first,
					git.status().setWorkingTreeIt(
							new org.eclipse.jgit.treewalk.FileTreeIterator(db))
							.call());
			assertNotSame(first,
					git.status().setProgressMonitor(
							org.eclipse.jgit.lib.NullProgressMonitor.INSTANCE)
							.call());
		}
	}

	@Test
	public void oversizedAndInvalidUtf8RepliesFallBack() throws Exception {
		configure(null);
		try (Git git = new Git(db)) {
			Status first = git.status().call();
			Files.write(response.toPath(), new byte[] { (byte) 0xff, 0 });
			assertNotSame(first, git.status().call());
			configure("head -c 16777217 /dev/zero\n");
			assertNotSame(first, git.status().call());
		}
	}

	@Test
	public void restartedAndEphemeralWatchmanClocksForceScan()
			throws Exception {
		configure(null);
		try (Git git = new Git(db)) {
			reply("c:10:20:1:1\0/\0");
			Status first = git.status().call();
			reply("c:10:20:1:2\0.git\0");
			assertSame(first, git.status().call());
			reply("c:10:20:1:1\0.git\0");
			assertNotSame(first, git.status().call());
			writeTrashFile("new", "new");
			reply("c:11:21:1:1\0.git\0");
			Status restarted = git.status().call();
			assertNotSame(first, restarted);
			equal(baseline(git), restarted);
			Files.delete(new File(db.getWorkTree(), "new").toPath());
			reply("c:0:0:1:2\0.git\0");
			Status unavailable = git.status().call();
			assertNotSame(restarted, unavailable);
			assertTrue(unavailable.isClean());
			assertNotSame(unavailable, git.status().call());
		}
	}

	@Test
	public void gitlinksDisableMonitorReuse() throws Exception {
		configure(null);
		org.eclipse.jgit.dircache.DirCache cache = db.lockDirCache();
		org.eclipse.jgit.dircache.DirCacheBuilder builder = cache.builder();
		org.eclipse.jgit.dircache.DirCacheEntry entry = new org.eclipse.jgit.dircache.DirCacheEntry(
				"submodule");
		entry.setFileMode(org.eclipse.jgit.lib.FileMode.GITLINK);
		entry.setObjectId(org.eclipse.jgit.lib.ObjectId
				.fromString("1111111111111111111111111111111111111111"));
		builder.add(entry);
		assertTrue(builder.commit());
		assertNull(new FsMonitor.State().query(db));
	}

	@Test
	public void interruptionClearsStateAndRestoresInterruptFlag()
			throws Exception {
		configure("touch '" + db.getDirectory().getPath().replace("'", "'\\''")
				+ "/hook-ready'\nsleep 60\n");
		java.util.concurrent.atomic.AtomicBoolean interrupted = new java.util.concurrent.atomic.AtomicBoolean();
		java.util.concurrent.atomic.AtomicReference<FsMonitor.Probe> probe = new java.util.concurrent.atomic.AtomicReference<>();
		Thread thread = new Thread(() -> {
			probe.set(new FsMonitor.State().query(db));
			interrupted.set(Thread.currentThread().isInterrupted());
		});
		thread.start();
		try {
			File ready = new File(db.getDirectory(), "hook-ready");
			long deadline = System.nanoTime() + 3_000_000_000L;
			while (!ready.isFile() && System.nanoTime() < deadline) {
				Thread.sleep(10);
			}
			assertTrue("Hook should start", ready.isFile());
			thread.interrupt();
			thread.join(3000);
			assertTrue("Interrupted hook query should stop", !thread.isAlive());
			assertNull(probe.get());
			assertTrue(interrupted.get());
		} finally {
			thread.interrupt();
			thread.join(6000);
		}
	}

	@Test
	public void parserRequiresTokenAndCompleteNulRecords() throws Exception {
		assertNull(FsMonitor.parse(new byte[0]));
		assertNull(FsMonitor.parse("token".getBytes(StandardCharsets.UTF_8)));
		assertEquals(1, FsMonitor
				.parse("token\0".getBytes(StandardCharsets.UTF_8)).length);
		assertEquals(2, FsMonitor
				.parse("token\0/\0".getBytes(StandardCharsets.UTF_8)).length);
		assertEquals(2, FsMonitor
				.parse("token\0path".getBytes(StandardCharsets.UTF_8)).length);
	}
}
