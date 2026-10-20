/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/** Scenarios from C Git t7113-post-index-change-hook.sh. */
public class PostIndexChangeHookTest extends RepositoryTestCase {
	private Git git;

	private Path record;

	private RevCommit initial;

	@Before
	public void prepareIndex() throws Exception {
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		git = Git.wrap(db);
		writeTrashFile("a", "initial");
		git.add().addFilepattern("a").call();
		initial = git.commit().setMessage("initial").call();
		record = db.getDirectory().toPath().resolve("index-hook.args");
		installHook(0);
	}

	private Path installHook(int code) throws Exception {
		Path hook = db.getDirectory().toPath()
				.resolve("hooks/post-index-change");
		Files.createDirectories(hook.getParent());
		Files.writeString(hook, "#!/bin/sh\n"
				+ "test ! -e \"$GIT_DIR/index.lock\" || exit 91\n"
				+ "test -f \"$GIT_DIR/index\" || exit 92\n"
				+ "printf '%s %s\\n' \"$1\" \"$2\" >>\"$GIT_DIR/index-hook.args\"\n"
				+ "exit " + code + "\n");
		assertTrue(hook.toFile().setExecutable(true));
		return hook;
	}

	private void checkFlags(String flags) throws Exception {
		assertEquals(flags + "\n", Files.readString(record));
		assertFalse(Files.exists(
				db.getIndexFile().toPath().resolveSibling("index.lock")));
	}

	@Test
	public void addNotifiesAfterUnlock() throws Exception {
		writeTrashFile("a", "changed");
		git.add().addFilepattern("a").call();
		checkFlags("0 0");
		assertTrue(git.status().call().getChanged().contains("a"));
	}

	@Test
	public void checkoutBranchNotifiesWorkTreeUpdate() throws Exception {
		git.checkout().setName(db.getBranch()).call();
		checkFlags("1 0");
	}

	@Test
	public void checkoutDetachedNotifiesWorkTreeUpdate() throws Exception {
		git.checkout().setName(initial.name()).call();
		checkFlags("1 0");
	}

	@Test
	public void createBranchNotifiesWorkTreeUpdate() throws Exception {
		git.checkout().setCreateBranch(true).setName("test").call();
		checkFlags("1 0");
	}

	@Test
	public void checkoutPathNotifiesWithoutFlags() throws Exception {
		writeTrashFile("a", "changed");
		git.checkout().addPath("a").call();
		checkFlags("0 0");
		assertEquals("initial", read("a"));
	}

	@Test
	public void hardResetNotifiesWorkTreeUpdate() throws Exception {
		writeTrashFile("a", "changed");
		git.reset().setMode(ResetType.HARD).call();
		checkFlags("1 0");
		assertEquals("initial", read("a"));
	}

	@Test
	public void mixedResetNotifiesPossibleSkipWorkTreeChange()
			throws Exception {
		writeTrashFile("a", "changed");
		git.add().addFilepattern("a").call();
		Files.delete(record);
		git.reset().setMode(ResetType.MIXED).call();
		checkFlags("0 1");
		assertTrue(git.status().call().getModified().contains("a"));
	}

	@Test
	public void pathResetNotifiesPossibleSkipWorkTreeChange() throws Exception {
		git.reset().addPath("a").call();
		checkFlags("0 1");
	}

	@Test
	public void softResetDoesNotNotify() throws Exception {
		git.reset().setMode(ResetType.SOFT).call();
		assertFalse(Files.exists(record));
	}

	@Test
	public void readOnlyStatusDoesNotNotify() throws Exception {
		git.status().call();
		assertFalse(Files.exists(record));
	}

	@Test
	public void failedNotificationDoesNotUndoWrite() throws Exception {
		installHook(7);
		writeTrashFile("a", "changed");
		git.add().addFilepattern("a").call();
		checkFlags("0 0");
		assertTrue(git.status().call().getChanged().contains("a"));
	}

	@Test
	public void rolledBackWriteDoesNotNotify() throws Exception {
		DirCache cache = db.lockDirCache();
		try {
			cache.write();
		} finally {
			cache.unlock();
		}
		assertFalse(Files.exists(record));
	}

	@Test
	public void repeatedIdenticalWritesNotifyEachTime() throws Exception {
		DirCache cache = db.lockDirCache();
		try {
			cache.write();
			assertTrue(cache.commit());
			assertTrue(cache.lock());
			cache.write();
			assertTrue(cache.commit());
		} finally {
			cache.unlock();
		}
		assertEquals("0 0\n0 0\n", Files.readString(record));
	}

	@Test
	public void configuredHookRunsWithoutTraditionalHook() throws Exception {
		Path hook = installHook(0);
		Path command = hook.resolveSibling("index-notification");
		Files.move(hook, command);
		db.getConfig().setString("hook", "index notification", "event",
				"post-index-change");
		db.getConfig().setString("hook", "index notification", "command",
				"'" + command + "'");
		git.add().addFilepattern("a").call();
		checkFlags("0 0");
	}

	@Test
	public void nonExecutableHookDoesNotRun() throws Exception {
		assertTrue(installHook(0).toFile().setExecutable(false, false));
		git.add().addFilepattern("a").call();
		assertFalse(Files.exists(record));
	}
}
