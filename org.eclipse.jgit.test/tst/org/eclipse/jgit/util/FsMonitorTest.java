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
import org.eclipse.jgit.lib.Repository;
import org.junit.Test;

public class FsMonitorTest extends RepositoryTestCase {
	@Test
	public void versionOneAndAutoNegotiationUseTimestampAndPathOnlyReplies()
			throws Exception {
		for (String version : new String[] { "1", null, "0", "3", "-1" }) {
			configure("printf '%s\\n' \"$1\" >> .git/protocol-calls\n"
					+ "[ \"$1\" = 1 ] || exit 1\n"
					+ "case \"$2\" in *[!0-9]*|'') exit 2;; esac\n"
					+ "cat .git/monitor-response\n");
			if (version == null)
				db.getConfig().unset("core", null, "fsmonitorHookVersion");
			else
				db.getConfig().setString("core", null, "fsmonitorHookVersion", version);
			Files.deleteIfExists(db.getDirectory().toPath().resolve("protocol-calls"));
			reply(""); // t7519/fsmonitor-none: empty success is valid v1.
			FsMonitor.State state = new FsMonitor.State();
			FsMonitor.Probe first = state.query(db);
			org.junit.Assert.assertNotNull(version, first);
			org.junit.Assert.assertFalse(first.isUnchanged());
			state.remember(first);
			assertTrue(version, state.query(db).isUnchanged());
			String calls = Files.readString(db.getDirectory().toPath().resolve("protocol-calls"));
			assertEquals(version, "1".equals(version) ? "1\n1\n" : "2\n1\n2\n1\n", calls);
			reply("nested/file\0loose"); // Final NUL is optional in native Git.
			assertEquals(java.util.List.of("nested/file", "loose"), state.query(db).getChangedPaths());
			reply("/\n"); // t7519/fsmonitor-all uses echo, including its newline.
			assertEquals(java.util.List.of("/"), state.query(db).getChangedPaths());
		}
	}

	@Test
	public void versionOneStatusChecksTrackedAndUntrackedChanges() throws Exception {
		configure("[ \"$1\" = 1 ] || exit 1\ncat .git/monitor-response\n");
		db.getConfig().setInt("core", null, "fsmonitorHookVersion", 1);
		try (Git git = Git.wrap(db)) {
			File tracked = writeTrashFile("tracked", "base");
			git.add().addFilepattern("tracked").call();
			git.commit().setMessage("base").call();
			reply("");
			Status initial = git.status().call();
			assertSame(initial, git.status().call());
			java.nio.file.attribute.FileTime time = Files.getLastModifiedTime(tracked.toPath());
			writeTrashFile("tracked", "next");
			Files.setLastModifiedTime(tracked.toPath(), time);
			writeTrashFile("loose", "new");
			reply("tracked\0loose");
			Status changed = git.status().call();
			assertEquals(Set.of("tracked"), changed.getModified());
			assertEquals(Set.of("loose"), changed.getUntracked());
			reply("");
			assertSame(changed, git.status().call());
			reply("/\n");
			assertNotSame(changed, git.status().call());
		}
	}

	@Test
	public void versionSwitchesRebuildCorrelation() throws Exception {
		configure("if [ \"$1\" = 2 ]; then printf 'opaque\\0'; fi\n");
		FsMonitor.State state = new FsMonitor.State();
		FsMonitor.Probe initial = state.query(db);
		org.junit.Assert.assertNotNull(initial);
		state.remember(initial);
		assertTrue(state.query(db).isUnchanged());
		for (int version : new int[] { 1, 2, 1 }) {
			db.getConfig().setInt("core", null, "fsmonitorHookVersion", version);
			FsMonitor.Probe changed = state.query(db);
			org.junit.Assert.assertNotNull(changed);
			org.junit.Assert.assertFalse(changed.isUnchanged());
			assertEquals(java.util.List.of("/"), changed.getChangedPaths());
			state.remember(changed);
			assertTrue(state.query(db).isUnchanged());
		}
	}

	@Test
	public void versionOneRejectsUnsafeAndMalformedPathReplies() throws Exception {
		configure("cat .git/monitor-response\n");
		db.getConfig().setInt("core", null, "fsmonitorHookVersion", 1);
		for (String invalid : new String[] { "../outside\0", "a/../outside", "a\0\0" }) {
			reply(invalid);
			assertNull(invalid, new FsMonitor.State().query(db));
		}
		Files.write(response.toPath(), new byte[] { (byte) 0xc3, 0x28 });
		assertNull(new FsMonitor.State().query(db));
	}

	@Test
	public void successfulMalformedVersionTwoDoesNotRetryVersionOne()
			throws Exception {
		configure("printf '%s\\n' \"$1\" >> .git/protocol-calls\nexit 0\n");
		assertNull(new FsMonitor.State().query(db));
		assertEquals("2\n", Files.readString(db.getDirectory().toPath().resolve("protocol-calls")));
	}

	@Test
	public void untrackedCachePolicyMatchesNativeConfigurationAndReuse()
			throws Exception {
		configure(null);
		reply("unchanged\0");
		writeTrashFile("loose", "untracked");
		try (Git git = Git.wrap(db)) {
			for (boolean manyFiles : new boolean[] { false, true }) {
				for (boolean existing : new boolean[] { false, true }) {
					for (String value : org.eclipse.jgit.junit.NativeFsMonitorWorkloadSupport.UNTRACKED_SETTINGS) {
						boolean expected = org.eclipse.jgit.junit.NativeFsMonitorWorkloadSupport
								.nativeUntrackedCachePolicy(db, manyFiles,
										value, existing);
						db.getConfig().setBoolean("feature", null, "manyFiles",
								manyFiles);
						if (value == null)
							db.getConfig().unset("core", null,
									"untrackedCache");
						else
							db.getConfig().setString("core", null,
									"untrackedCache", value);
						String row = "manyFiles=" + manyFiles + ", existing="
								+ existing + ", value=" + value;
						assertEquals(row, expected,
								FsMonitor.isUntrackedCacheEnabled(db, null));
						Status first = git.status().call();
						Status second = git.status().call();
						assertEquals(row, Set.of("loose"),
								second.getUntracked());
						if (expected)
							assertSame(row, first, second);
						else
							assertNotSame(row, first, second);
					}
				}
			}
		}
	}

	@Test
	public void removedHookStillChecksContentsAfterSuccessfulMonitoring()
			throws Exception {
		unavailableHookChecksContents("missing");
	}

	@Test
	public void nonexecutableHookStillChecksContentsAfterSuccessfulMonitoring()
			throws Exception {
		unavailableHookChecksContents("nonexecutable");
	}

	@Test
	public void unsupportedHookVersionStillChecksContentsAfterSuccessfulMonitoring()
			throws Exception {
		unavailableHookChecksContents("version");
	}

	@Test
	public void booleanHookSettingStillChecksContentsAfterSuccessfulMonitoring()
			throws Exception {
		unavailableHookChecksContents("boolean");
	}

	private void unavailableHookChecksContents(String reason) throws Exception {
		configure(null);
		try (Git git = new Git(db)) {
			File tracked = writeTrashFile("tracked", "one");
			java.nio.file.attribute.FileTime time = java.nio.file.attribute.FileTime
					.fromMillis(System.currentTimeMillis() - 60_000);
			Files.setLastModifiedTime(tracked.toPath(), time);
			git.add().addFilepattern("tracked").call();
			git.commit().setMessage("base").call();
			Status initial = git.status().call();
			assertTrue(initial.isClean());
			writeTrashFile("tracked", "two");
			Files.setLastModifiedTime(tracked.toPath(), time);
			File hook = new File(db.getConfig().getString("core", null,
					"fsmonitor"));
			switch (reason) {
			case "missing":
				Files.delete(hook.toPath());
				break;
			case "nonexecutable":
				assertTrue(hook.setExecutable(false, false));
				break;
			case "version":
				db.getConfig().setString("core", null, "fsmonitorHookVersion", "not-a-number");
				break;
			case "boolean":
				db.getConfig().setBoolean("core", null, "fsmonitor", false);
				break;
			default:
				throw new AssertionError(reason);
			}
			assertEquals(" M tracked\0", new String(nativeGit(null, "status",
					"--porcelain=v1", "-z", "--untracked-files=all", "--no-renames"),
					StandardCharsets.UTF_8));
			assertEquals(reason, Set.of("tracked"),
					git.status().call().getModified());
			assertEquals("Repeated fallback must not lose the detected edit",
					Set.of("tracked"), git.status().call().getModified());
		}
	}

	private File response;

	private void configure(String script) throws Exception {
		assumeFalse(SystemReader.getInstance().isWindows());
		response = new File(db.getDirectory(), "monitor-response");
		File hook = new File(db.getDirectory(), "hooks/fsmonitor-test");
		Files.createDirectories(hook.toPath().getParent());
		String code = script == null
				? "cat '" + response.getPath().replace("'", "'\\''") + "'\n"
				: script;
		// Static fixtures report mutations once; a writer querying the scan's
		// returned clock must not replay those pre-scan notifications.
		String clock = new File(db.getDirectory(), "monitor-response-clock")
				.getPath().replace("'", "'\\''");
		String writer = "if test -e .git/index.lock; then\n"
				+ "  if test \"$1\" = 1; then exit 0; fi\n"
				+ "  if printf '%s' \"$2\" | cmp -s - '" + clock + "'; then\n"
				+ "    printf '%s\\0' \"$2\"; exit 0\n  fi\nfi\n";
		Files.writeString(hook.toPath(), "#!/bin/sh\n" + writer + code);
		assertTrue(hook.setExecutable(true));
		db.getConfig().setString("core", null, "fsmonitor",
				hook.getAbsolutePath());
		db.getConfig().setBoolean("jgit", null, "fsmonitorCache", true);
		db.getConfig().setBoolean("core", null, "untrackedCache", true);
		reply("initial\0/\0");
	}

	private void reply(String data) throws Exception {
		Files.write(response.toPath(), data.getBytes(StandardCharsets.UTF_8));
		int nul = data.indexOf('\0');
		Files.writeString(new File(db.getDirectory(), "monitor-response-clock").toPath(),
				nul < 0 ? data : data.substring(0, nul));
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
		String hook = db.getConfig().getString("core", null, "fsmonitor");
		db.getConfig().setBoolean("jgit", null, "fsmonitorCache", false);
		db.getConfig().setBoolean("core", null, "fsmonitor", false);
		try {
			return git.status().setUpdateUntrackedCache(false).call();
		} finally {
			if (hook == null) {
				db.getConfig().unset("core", null, "fsmonitor");
			} else {
				db.getConfig().setString("core", null, "fsmonitor", hook);
			}
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
		configure("echo $$ > .git/hook-pid\nexec sleep 60\n");
		try (Git git = new Git(db)) {
			long start = System.nanoTime();
			assertTrue(git.status().call().isClean());
			assertTrue("Hook deadline must bound the call",
					System.nanoTime() - start < 10_000_000_000L);
			long pid = Long.parseLong(Files
					.readString(
							new File(db.getDirectory(), "hook-pid").toPath())
					.trim());
			assertTrue("Timed-out hook must be dead",
					ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true));
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
	public void booleanMonitorValuesNeverExecuteFilesNamedLikeBooleans()
			throws Exception {
		configure(null);
		File marker = new File(db.getDirectory(), "boolean-hook-called");
		for (String value : new String[] { "false", "FALSE", "0", "no", "off",
				"true", "TRUE", "1", "yes", "on", "2", "-1", "1k", "0x1", "+1",
				"010", "0X7f", "0g", "1M", "1G", "-2G", "2147483647",
				"-2147483648", " 1" }) {
			File executable = new File(db.getWorkTree(), value);
			Files.writeString(executable.toPath(),
					"#!/bin/sh\ntouch '"
							+ marker.getPath().replace("'", "'\\''")
							+ "'\nprintf 'token\\0'\n");
			assertTrue(executable.setExecutable(true));
			db.getConfig().setString("core", null, "fsmonitor", value);
			assertNull("Boolean/daemon values cannot name a hook: " + value,
					new FsMonitor.State().query(db));
			assertTrue("A boolean value must not execute a file",
					!marker.exists());
		}
	}

	@Test
	public void includedConfigChangesInvalidateEffectiveStatusInputs()
			throws Exception {
		configure(null);
		File included = new File(db.getDirectory(), "included-config");
		Files.writeString(included.toPath(), "[core]\nfileMode = true\n");
		String text = db.getConfig().toText() + "[include]\npath = "
				+ included.getAbsolutePath() + "\n";
		db.getConfig().fromText(text);
		try (Git git = new Git(db)) {
			File tracked = writeTrashFile("tracked", "body");
			git.add().addFilepattern("tracked").call();
			git.commit().setMessage("base").call();
			org.junit.Assume.assumeTrue(db.getFS().supportsExecute());
			assertTrue(db.getFS().setExecute(tracked, true));
			reply("mode-changed\0tracked\0");
			Status modeChanged = git.status().call();
			assertEquals(Set.of("tracked"), modeChanged.getModified());
			Files.writeString(included.toPath(), "[core]\nfileMode = false\n");
			db.getConfig().fromText(text);
			reply("unchanged\0");
			Status ignoredMode = git.status().call();
			assertNotSame(modeChanged, ignoredMode);
			assertTrue(ignoredMode.isClean());
			equal(baseline(git), ignoredMode);
		}
	}

	@Test
	public void nativeBooleanParsingAndExplicitHookPathsAgree()
			throws Exception {
		configure(null);
		for (String value : new String[] { "false", "true", "0", "-1", "010",
				"0x1", "1k", "1M", "-2G", "2147483647", "-2147483648", " 1",
				"./false", "08", "2G", "2147483648", "-2147483649", "#1", "1kk",
				"1 " }) {
			Process nativeConfig = new ProcessBuilder("git", "-c",
					"core.fsmonitor=" + value, "config", "--type=bool", "--get",
					"core.fsmonitor")
					.redirectOutput(ProcessBuilder.Redirect.DISCARD)
					.redirectError(ProcessBuilder.Redirect.DISCARD).start();
			assertTrue(nativeConfig.waitFor(5,
					java.util.concurrent.TimeUnit.SECONDS));
			boolean nativeBoolean = nativeConfig.exitValue() == 0;
			File executable = new File(db.getWorkTree(), value);
			Files.writeString(executable.toPath(),
					"#!/bin/sh\nprintf 'token\\0'\n");
			assertTrue(executable.setExecutable(true));
			db.getConfig().setString("core", null, "fsmonitor", value);
			assertEquals(value, nativeBoolean,
					new FsMonitor.State().query(db) == null);
		}
	}

	@Test
	public void hookReceivesLiteralOpaqueTokensAndRepositoryWorkingDirectory()
			throws Exception {
		configure(
				"printf '%s\\0%s\\0%s\\0' \"$PWD\" \"$1\" \"$2\" > .git/hook-argv\n"
						+ "cat .git/monitor-response\n");
		File original = new File(
				db.getConfig().getString("core", null, "fsmonitor"));
		File renamed = new File(original.getParentFile(),
				"monitor ' with spaces");
		Files.move(original.toPath(), renamed.toPath());
		db.getConfig().setString("core", null, "fsmonitor",
				".git/hooks/" + renamed.getName());
		String token = "opaque ü; $(touch injected)\n'quote";
		reply(token + "\0/\0");
		FsMonitor.State state = new FsMonitor.State();
		FsMonitor.Probe first = state.query(db);
		org.junit.Assert.assertNotNull(first);
		String[] argv = Files
				.readString(new File(db.getDirectory(), "hook-argv").toPath())
				.split("\u0000");
		assertEquals(db.getWorkTree().getCanonicalPath(), argv[0]);
		assertEquals("2", argv[1]);
		assertEquals("c:0:0:0:0", argv[2]);
		state.remember(first);
		reply("next\0");
		assertTrue(state.query(db).isUnchanged());
		argv = Files
				.readString(new File(db.getDirectory(), "hook-argv").toPath())
				.split("\u0000");
		assertEquals(token, argv[2]);
		assertTrue(!new File(db.getWorkTree(), "injected").exists());
	}

	@Test
	public void globalIgnoreContentChangesInvalidateEvenWithRestoredTimestamp()
			throws Exception {
		configure(null);
		File ignore = new File(db.getDirectory(), "global-ignore");
		Files.writeString(ignore.toPath(), "foo\n");
		java.nio.file.attribute.FileTime oldTime = Files
				.getLastModifiedTime(ignore.toPath());
		db.getConfig().setString("core", null, "excludesFile",
				ignore.getAbsolutePath());
		writeTrashFile("foo", "foo");
		writeTrashFile("bar", "bar");
		try (Git git = new Git(db)) {
			Status first = git.status().call();
			assertEquals(Set.of("foo"), first.getIgnoredNotInIndex());
			Files.writeString(ignore.toPath(), "bar\n");
			Files.setLastModifiedTime(ignore.toPath(), oldTime);
			reply("next\0");
			Status second = git.status().call();
			assertNotSame(first, second);
			assertEquals(Set.of("bar"), second.getIgnoredNotInIndex());
			assertEquals(Set.of("foo"), second.getUntracked());
			equal(baseline(git), second);
		}
	}

	@Test
	public void malformedReplyDropsTokenAndRecoveryMustScanBeforeReuse()
			throws Exception {
		configure(null);
		try (Git git = new Git(db)) {
			Status first = git.status().call();
			reply("invalid\0../outside\0");
			assertNotSame(first, git.status().call());
			writeTrashFile("new", "new");
			reply("recovered\0");
			Status recovered = git.status().call();
			assertEquals(Set.of("new"), recovered.getUntracked());
			equal(baseline(git), recovered);
			reply("stable\0");
			assertSame(recovered, git.status().call());
		}
	}

	@Test
	public void implicitBooleanAndMultivalueConfigRemainCacheableAndDistinct()
			throws Exception {
		configure(null);
		db.getConfig().fromText(db.getConfig().toText()
				+ "[benchmark]\nflag\nvalue = first\nvalue\n");
		FsMonitor.State state = new FsMonitor.State();
		FsMonitor.Probe initial = state.query(db);
		org.junit.Assert.assertNotNull(initial);
		state.remember(initial);
		reply("next\0");
		assertTrue(state.query(db).isUnchanged());
		db.getConfig().setString("benchmark", null, "flag", "");
		FsMonitor.Probe changed = state.query(db);
		org.junit.Assert.assertNotNull(changed);
		org.junit.Assert.assertFalse(changed.isUnchanged());
		state.remember(changed);
		assertTrue(state.query(db).isUnchanged());
	}

	private byte[] nativeGit(File alternateIndex, String... arguments)
			throws Exception {
		java.util.List<String> command = new java.util.ArrayList<>(
				java.util.List.of(System.getProperty("jgit.test.gitExecutable", "git"),
						"--no-optional-locks", "-C",
						db.getWorkTree().getAbsolutePath(), "-c",
						"core.fsmonitor=false"));
		command.addAll(java.util.List.of(arguments));
		ProcessBuilder builder = new ProcessBuilder(command)
				.redirectErrorStream(true);
		builder.environment().keySet()
				.removeIf(name -> name.startsWith("GIT_"));
		builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
		builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
		if (alternateIndex != null) {
			builder.environment().put("GIT_INDEX_FILE",
					alternateIndex.getAbsolutePath());
		}
		Process process = builder.start();
		try {
			assertTrue("Native Git must finish",
					process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
			byte[] output = process.getInputStream().readAllBytes();
			assertEquals(new String(output, StandardCharsets.UTF_8), 0,
					process.exitValue());
			return output;
		} finally {
			process.destroyForcibly();
		}
	}

	private void configureGate() throws Exception {
		configure("if [ -f .git/gate-enabled ]; then\n"
				+ "if ! mkdir .git/hook-running; then touch .git/hook-overlap; fi\n"
				+ "touch .git/hook-ready\n"
				+ "while [ ! -f .git/hook-release ]; do sleep 0.01; done\n"
				+ "rmdir .git/hook-running\nfi\ncat .git/monitor-response\n");
	}

	private void awaitHook() throws Exception {
		long deadline = System.nanoTime() + 3_000_000_000L;
		File ready = new File(db.getDirectory(), "hook-ready");
		while (!ready.isFile() && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertTrue("Hook must reach the gate", ready.isFile());
	}

	@Test
	public void concurrentStatusCallsSerializeHookAndReuseOneResult()
			throws Exception {
		configureGate();
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors
				.newFixedThreadPool(2);
		try (Git git = new Git(db)) {
			Status initial = git.status().call();
			reply("next\0");
			Files.writeString(
					new File(db.getDirectory(), "gate-enabled").toPath(),
					"gate");
			java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(
					2);
			java.util.concurrent.Callable<Status> request = () -> {
				start.await(3, java.util.concurrent.TimeUnit.SECONDS);
				return git.status().call();
			};
			java.util.concurrent.Future<Status> first = pool.submit(request);
			java.util.concurrent.Future<Status> second = pool.submit(request);
			awaitHook();
			Files.writeString(
					new File(db.getDirectory(), "hook-release").toPath(),
					"release");
			assertSame(initial,
					first.get(10, java.util.concurrent.TimeUnit.SECONDS));
			assertSame(initial,
					second.get(10, java.util.concurrent.TimeUnit.SECONDS));
			assertTrue(!new File(db.getDirectory(), "hook-overlap").exists());
			equal(baseline(git), initial);
		} finally {
			Files.writeString(
					new File(db.getDirectory(), "hook-release").toPath(),
					"release");
			pool.shutdownNow();
			assertTrue(pool.awaitTermination(10,
					java.util.concurrent.TimeUnit.SECONDS));
		}
	}

	@Test
	public void atomicExternalIndexReplacementDuringHookForcesFreshScan()
			throws Exception {
		configureGate();
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors
				.newSingleThreadExecutor();
		try (Git git = new Git(db)) {
			writeTrashFile("tracked", "base");
			git.add().addFilepattern("tracked").call();
			git.commit().setMessage("base").call();
			Status initial = git.status().call();
			writeTrashFile("new", "new");
			File alternate = new File(db.getDirectory(), "replacement-index");
			Files.copy(db.getIndexFile().toPath(), alternate.toPath());
			nativeGit(alternate, "add", "new");
			reply("next\0");
			Files.writeString(
					new File(db.getDirectory(), "gate-enabled").toPath(),
					"gate");
			java.util.concurrent.Future<Status> result = pool
					.submit(() -> git.status().call());
			awaitHook();
			Files.move(alternate.toPath(), db.getIndexFile().toPath(),
					java.nio.file.StandardCopyOption.ATOMIC_MOVE,
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			Files.writeString(
					new File(db.getDirectory(), "hook-release").toPath(),
					"release");
			Status changed = result.get(10,
					java.util.concurrent.TimeUnit.SECONDS);
			assertNotSame(initial, changed);
			assertEquals(Set.of("new"), changed.getAdded());
			equal(baseline(git), changed);
			assertEquals("A  new\0",
					new String(
							nativeGit(null, "status", "--porcelain=v1", "-z",
									"--untracked-files=all", "--no-renames"),
							StandardCharsets.UTF_8));
			Status seeded = git.status().call();
			assertNotSame(changed, seeded);
			assertSame(seeded, git.status().call());
		} finally {
			Files.writeString(
					new File(db.getDirectory(), "hook-release").toPath(),
					"release");
			pool.shutdownNow();
			assertTrue(pool.awaitTermination(10,
					java.util.concurrent.TimeUnit.SECONDS));
		}
	}

	@Test
	public void allSevenConflictStatesInvalidateAndAgreeWithNativeGit()
			throws Exception {
		configure(null);
		try (Git git = new Git(db)) {
			writeTrashFile("tracked", "base");
			git.add().addFilepattern("tracked").call();
			git.commit().setMessage("base").call();
			org.eclipse.jgit.lib.ObjectId blob;
			try (org.eclipse.jgit.lib.ObjectInserter inserter = db
					.newObjectInserter()) {
				blob = inserter.insert(org.eclipse.jgit.lib.Constants.OBJ_BLOB,
						"base".getBytes(StandardCharsets.UTF_8));
				inserter.flush();
			}
			int[][] stages = { { 1 }, { 2 }, { 1, 2 }, { 3 }, { 1, 3 },
					{ 2, 3 }, { 1, 2, 3 } };
			String[] codes = { "DD", "AU", "UD", "UA", "DU", "AA", "UU" };
			org.eclipse.jgit.lib.IndexDiff.StageState[] states = {
					org.eclipse.jgit.lib.IndexDiff.StageState.BOTH_DELETED,
					org.eclipse.jgit.lib.IndexDiff.StageState.ADDED_BY_US,
					org.eclipse.jgit.lib.IndexDiff.StageState.DELETED_BY_THEM,
					org.eclipse.jgit.lib.IndexDiff.StageState.ADDED_BY_THEM,
					org.eclipse.jgit.lib.IndexDiff.StageState.DELETED_BY_US,
					org.eclipse.jgit.lib.IndexDiff.StageState.BOTH_ADDED,
					org.eclipse.jgit.lib.IndexDiff.StageState.BOTH_MODIFIED };
			Status previous = git.status().call();
			for (int i = 0; i < stages.length; i++) {
				org.eclipse.jgit.dircache.DirCache cache = db.lockDirCache();
				org.eclipse.jgit.dircache.DirCacheBuilder builder = cache
						.builder();
				for (int stage : stages[i]) {
					org.eclipse.jgit.dircache.DirCacheEntry entry = new org.eclipse.jgit.dircache.DirCacheEntry(
							"tracked", stage);
					entry.setFileMode(
							org.eclipse.jgit.lib.FileMode.REGULAR_FILE);
					entry.setObjectId(blob);
					builder.add(entry);
				}
				assertTrue(builder.commit());
				reply("conflict-" + i + "\0");
				Status conflict = git.status().call();
				assertNotSame(previous, conflict);
				assertEquals(java.util.Map.of("tracked", states[i]),
						conflict.getConflictingStageState());
				equal(baseline(git), conflict);
				assertEquals(codes[i] + " tracked\0",
						new String(nativeGit(null, "status", "--porcelain=v1",
								"-z", "--untracked-files=all", "--no-renames"),
								StandardCharsets.UTF_8));
				assertSame(conflict, git.status().call());
				previous = conflict;
			}
		}
	}

	@Test
	public void indexVersionsAndAssumeUnchangedSkipWorktreeFlagsRemainCorrect()
			throws Exception {
		configure(null);
		try (Git git = new Git(db)) {
			File tracked = writeTrashFile("tracked", "base");
			git.add().addFilepattern("tracked").call();
			git.commit().setMessage("base").call();
			Status previous = git.status().call();
			for (String version : new String[] { "2", "3", "4" }) {
				nativeGit(null, "update-index", "--index-version", version);
				reply("version-" + version + "\0");
				Status status = git.status().call();
				equal(baseline(git), status);
				assertTrue(status.isClean());
				assertSame(status, git.status().call());
				previous = status;
			}
			nativeGit(null, "update-index", "--assume-unchanged", "tracked");
			writeTrashFile("tracked", "edit");
			reply("assumed\0tracked\0");
			Status assumed = git.status().call();
			assertNotSame(previous, assumed);
			assertTrue(assumed.isClean());
			equal(baseline(git), assumed);
			nativeGit(null, "update-index", "--no-assume-unchanged", "tracked");
			reply("unassumed\0");
			Status modified = git.status().call();
			assertEquals(Set.of("tracked"), modified.getModified());
			equal(baseline(git), modified);
			nativeGit(null, "update-index", "--skip-worktree", "tracked");
			Files.delete(tracked.toPath());
			reply("skipped\0");
			Status skipped = git.status().call();
			assertNotSame(modified, skipped);
			assertTrue(skipped.getMissing().isEmpty());
			equal(baseline(git), skipped);
			nativeGit(null, "update-index", "--no-skip-worktree", "tracked");
			reply("unskipped\0");
			Status missing = git.status().call();
			assertEquals(Set.of("tracked"), missing.getMissing());
			equal(baseline(git), missing);
		}
	}

	@Test
	public void independentRepositoryObjectsAndReopenNeverShareCachedResults()
			throws Exception {
		configure(null);
		try (Git original = new Git(db)) {
			Status first = original.status().call();
			reply("next\0");
			Status revalidated;
			try (Git reopened = Git.open(db.getWorkTree())) {
				assertNotSame(db, reopened.getRepository());
				reopened.getRepository().getConfig().setBoolean("jgit", null,
						"fsmonitorCache", true);
				reopened.getRepository().getConfig().setBoolean("core", null,
						"untrackedCache", true);
				reopened.getRepository().getConfig().setString("core", null,
						"fsmonitor",
						db.getConfig().getString("core", null, "fsmonitor"));
				Status second = reopened.status().call();
				assertNotSame(first, second);
				equal(first, second);
				assertSame(second, reopened.status().call());
				// The other repository object published a new persisted clock.
				revalidated = original.status().call();
				assertNotSame(first, revalidated);
				equal(first, revalidated);
			}
			org.eclipse.jgit.lib.Repository other = createWorkRepository();
			try (Git secondRepo = new Git(other)) {
				other.getConfig().setBoolean("jgit", null, "fsmonitorCache",
						true);
				other.getConfig().setBoolean("core", null, "untrackedCache", true);
				other.getConfig().setString("core", null, "fsmonitor",
						db.getConfig().getString("core", null, "fsmonitor"));
				File onlyOther = new File(other.getWorkTree(), "only-other");
				Files.writeString(onlyOther.toPath(), "other");
				Status isolated = secondRepo.status().call();
				assertEquals(Set.of("only-other"), isolated.getUntracked());
				assertSame(isolated, secondRepo.status().call());
				assertSame(revalidated, original.status().call());
			}
		}
	}

	@Test
	public void globalAndInfoAttributesChangesInvalidateWithoutPathNotifications()
			throws Exception {
		configure(null);
		File global = new File(db.getDirectory(), "global-attributes");
		Files.writeString(global.toPath(), "tracked -text\n");
		db.getConfig().setString("core", null, "attributesFile",
				global.getAbsolutePath());
		try (Git git = new Git(db)) {
			writeTrashFile("tracked", "line\n");
			java.nio.file.attribute.FileTime trackedTime = java.nio.file.attribute.FileTime
					.fromMillis(System.currentTimeMillis() - 60_000);
			Files.setLastModifiedTime(new File(db.getWorkTree(), "tracked").toPath(),
					trackedTime);
			git.add().addFilepattern("tracked").call();
			git.commit().setMessage("base").call();
			writeTrashFile("tracked", "line\r\n");
			Files.setLastModifiedTime(new File(db.getWorkTree(), "tracked").toPath(),
					trackedTime);
			Status raw = git.status().call();
			assertEquals(Set.of("tracked"), raw.getModified());
			java.nio.file.attribute.FileTime originalTime = Files
					.getLastModifiedTime(global.toPath());
			Files.writeString(global.toPath(), "tracked text eol=lf\n");
			Files.setLastModifiedTime(global.toPath(), originalTime);
			reply("global-changed\0");
			Status normalized = git.status().call();
			assertNotSame(raw, normalized);
			assertEquals(Set.of("tracked"), normalized.getModified());
			equal(baseline(git), normalized);
			// Native status trusts the changed raw size here, whereas a full
			// content comparison normalizes CRLF. Forced checks must preserve
			// this raw-size decision, matching native and ordinary JGit status.
			assertEquals(" M tracked\0", new String(nativeGit(null, "-c",
					"core.attributesFile=" + global.getAbsolutePath(), "status",
					"--porcelain=v1", "-z", "--untracked-files=all", "--no-renames"),
					StandardCharsets.UTF_8));
			assertEquals("", new String(nativeGit(null, "-c",
					"core.attributesFile=" + global.getAbsolutePath(), "-c",
					"core.safecrlf=false", "diff", "--exit-code", "--", "tracked"),
					StandardCharsets.UTF_8));
			File info = new File(db.getDirectory(), "info/attributes");
			Files.createDirectories(info.toPath().getParent());
			Files.writeString(info.toPath(), "tracked -text\n");
			reply("info-changed\0");
			Status overridden = git.status().call();
			assertNotSame(normalized, overridden);
			assertEquals(Set.of("tracked"), overridden.getModified());
			equal(baseline(git), overridden);
			Files.delete(info.toPath());
			reply("info-removed\0");
			Status restored = git.status().call();
			assertNotSame(overridden, restored);
			assertEquals(Set.of("tracked"), restored.getModified());
			equal(baseline(git), restored);
			assertEquals(" M tracked\0", new String(nativeGit(null, "-c",
					"core.attributesFile=" + global.getAbsolutePath(), "status",
					"--porcelain=v1", "-z", "--untracked-files=all", "--no-renames"),
					StandardCharsets.UTF_8));
		}
	}

	@Test
	public void inheritedGlobalConfigChangeInvalidatesEffectiveIgnoreRules()
			throws Exception {
		configure(null);
		org.eclipse.jgit.lib.Config base = db.getConfig().getBaseConfig();
		org.junit.Assert.assertNotNull(base);
		String[] old = base.getStringList("core", null, "excludesFile");
		try (Git git = new Git(db)) {
			writeTrashFile("other", "other");
			Status first = git.status().call();
			assertEquals(Set.of("other"), first.getUntracked());
			File exclude = new File(db.getDirectory(), "inherited-ignore");
			Files.writeString(exclude.toPath(), "other\n");
			base.setString("core", null, "excludesFile",
					exclude.getAbsolutePath());
			reply("global-changed\0");
			Status ignored = git.status().call();
			assertNotSame(first, ignored);
			assertEquals(Set.of("other"), ignored.getIgnoredNotInIndex());
			equal(baseline(git), ignored);
		} finally {
			base.setStringList("core", null, "excludesFile",
					java.util.List.of(old));
		}
	}

	@Test
	public void fileDirectoryAndSymlinkTransitionsNeverReuseObsoleteResults()
			throws Exception {
		configure(null);
		org.junit.Assume.assumeTrue(db.getFS().supportsSymlinks());
		try (Git git = new Git(db)) {
			File shape = writeTrashFile("shape", "base");
			git.add().addFilepattern("shape").call();
			git.commit().setMessage("base").call();
			Status regular = git.status().call();
			Files.delete(shape.toPath());
			writeTrashFile("shape/child", "new");
			reply("directory\0shape\0");
			Status directory = git.status().call();
			assertNotSame(regular, directory);
			assertEquals(Set.of("shape"), directory.getMissing());
			assertEquals(Set.of("shape/child"), directory.getUntracked());
			equal(baseline(git), directory);
			Files.delete(shape.toPath().resolve("child"));
			Files.delete(shape.toPath());
			Files.createSymbolicLink(shape.toPath(),
					java.nio.file.Path.of("base"));
			reply("symlink\0shape\0");
			Status symlink = git.status().call();
			assertNotSame(directory, symlink);
			assertEquals(Set.of("shape"), symlink.getModified());
			equal(baseline(git), symlink);
			assertEquals(" T shape\0",
					new String(
							nativeGit(null, "status", "--porcelain=v1", "-z",
									"--untracked-files=all", "--no-renames"),
							StandardCharsets.UTF_8));
			Files.delete(shape.toPath());
			writeTrashFile("shape", "base");
			reply("regular\0shape\0");
			Status restored = git.status().call();
			assertNotSame(symlink, restored);
			assertTrue(restored.isClean());
			equal(baseline(git), restored);
			reply("stable\0");
			assertSame(restored, git.status().call());
		}
	}

	@Test
	public void notifiedSameSizeEditWithRestoredTimestampMustScan()
			throws Exception {
		configure(null);
		try (Git git = new Git(db)) {
			File tracked = writeTrashFile("tracked", "one");
			java.nio.file.attribute.FileTime time = java.nio.file.attribute.FileTime
					.fromMillis(System.currentTimeMillis() - 60_000);
			Files.setLastModifiedTime(tracked.toPath(), time);
			git.add().addFilepattern("tracked").call();
			git.commit().setMessage("base").call();
			Status initial = git.status().call();
			writeTrashFile("tracked", "two");
			Files.setLastModifiedTime(tracked.toPath(), time);
			reply("changed\0tracked\0");
			Status changed = git.status().call();
			assertNotSame(initial, changed);
			// Native Git is the oracle here: the unmonitored metadata-only scan
			// misses a restored timestamp. The notification must force hashing.
			assertEquals(" M tracked\0",
					new String(
							nativeGit(null, "status", "--porcelain=v1", "-z",
									"--untracked-files=all", "--no-renames"),
							StandardCharsets.UTF_8));
			assertEquals(Set.of("tracked"), changed.getModified());
		}
	}

	@Test
	public void gitlinkAdditionRemovalRevalidatesNegativeCache()
			throws Exception {
		configure(null);
		FsMonitor.State state = new FsMonitor.State();
		FsMonitor.Probe initial = state.query(db);
		org.junit.Assert.assertNotNull(initial);
		state.remember(initial);
		org.eclipse.jgit.dircache.DirCache cache = db.lockDirCache();
		org.eclipse.jgit.dircache.DirCacheBuilder builder = cache.builder();
		org.eclipse.jgit.dircache.DirCacheEntry entry = new org.eclipse.jgit.dircache.DirCacheEntry(
				"submodule");
		entry.setFileMode(org.eclipse.jgit.lib.FileMode.GITLINK);
		entry.setObjectId(org.eclipse.jgit.lib.ObjectId
				.fromString("1111111111111111111111111111111111111111"));
		builder.add(entry);
		assertTrue(builder.commit());
		reply("with-gitlink\0");
		assertNull(state.query(db));
		assertNull(state.query(db));
		cache = db.lockDirCache();
		assertTrue(cache.builder().commit());
		reply("without-gitlink\0");
		FsMonitor.Probe removed = state.query(db);
		org.junit.Assert.assertNotNull(removed);
		org.junit.Assert.assertFalse(removed.isUnchanged());
		state.remember(removed);
		assertTrue(state.query(db).isUnchanged());
	}

	@Test
	public void invalidVersionsMissingNonexecutableDisabledAndBareRepositoriesNeverRunHooks()
			throws Exception {
		File called = new File(db.getDirectory(), "hook-called");
		configure("touch '" + called.getPath().replace("'", "'\\''")
				+ "'\nprintf 'token\\0'\n");
		String hook = db.getConfig().getString("core", null, "fsmonitor");
		for (String version : new String[] { "not-a-number" }) {
			db.getConfig().setString("core", null, "fsmonitorHookVersion",
					version);
			assertNull(new FsMonitor.State().query(db));
			assertTrue(!called.exists());
		}
		db.getConfig().setInt("core", null, "fsmonitorHookVersion", 2);
		assertTrue(new File(hook).setExecutable(false, false));
		assertNull(new FsMonitor.State().query(db));
		assertTrue(!called.exists());
		db.getConfig().setString("core", null, "fsmonitor", hook + "-missing");
		assertNull(new FsMonitor.State().query(db));
		db.getConfig().setString("core", null, "fsmonitor",
				new File(hook).getParent());
		assertNull(new FsMonitor.State().query(db));
		db.getConfig().setString("core", null, "fsmonitor", "");
		assertNull(new FsMonitor.State().query(db));
		db.getConfig().setString("core", null, "fsmonitor", hook);
		assertTrue(new File(hook).setExecutable(true));
		db.getConfig().unset("jgit", null, "fsmonitorCache");
		org.junit.Assert.assertFalse(FsMonitor.isEnabled(db));
		assertNull(new FsMonitor.State().query(db));
		org.eclipse.jgit.lib.Repository bare = createBareRepository();
		bare.getConfig().setBoolean("jgit", null, "fsmonitorCache", true);
		bare.getConfig().setString("core", null, "fsmonitor", hook);
		org.junit.Assert.assertFalse(FsMonitor.isEnabled(bare));
		assertNull(new FsMonitor.State().query(bare));
		assertTrue(!called.exists());
	}

	@Test
	public void directoryRootAndNoncanonicalNotificationsForceContentChecks()
			throws Exception {
		configure(null);
		try (Git git = new Git(db)) {
			File tracked = writeTrashFile("directory/tracked", "one");
			java.nio.file.attribute.FileTime time = java.nio.file.attribute.FileTime
					.fromMillis(System.currentTimeMillis() - 60_000);
			Files.setLastModifiedTime(tracked.toPath(), time);
			git.add().addFilepattern("directory/tracked").call();
			git.commit().setMessage("base").call();
			git.status().call();
			int version = 0;
			for (String path : new String[] { "directory", "directory/", "/",
					"directory/./tracked", "directory//tracked",
					"DIRECTORY/TRACKED" }) {
				if (path.equals("DIRECTORY/TRACKED")) {
					db.getConfig().setBoolean("core", null, "ignoreCase", true);
					git.status().call();
				}
				String content = ++version % 2 == 0 ? "one" : "two";
				Files.writeString(tracked.toPath(), content);
				Files.setLastModifiedTime(tracked.toPath(), time);
				reply("change-" + version + "\0" + path + "\0");
				Status changed = git.status().call();
				assertEquals(path,
						content.equals("one") ? Set.of()
								: Set.of("directory/tracked"),
						changed.getModified());
				String nativeStatus = new String(
						nativeGit(null, "status", "--porcelain=v1", "-z",
								"--untracked-files=all", "--no-renames"),
						StandardCharsets.UTF_8);
				assertEquals(
						content.equals("one") ? "" : " M directory/tracked\0",
						nativeStatus);
				reply("stable-" + version + "\0");
				assertSame(path, changed, git.status().call());
			}
		}
	}

	@Test
	public void clockBoundariesAndImmutableDeltasKeepUntrustedEpochsUncacheable()
			throws Exception {
		configure(null);
		FsMonitor.State state = new FsMonitor.State();
		reply("c:1:2:3:18446744073709551614\0");
		FsMonitor.Probe first = state.query(db);
		org.junit.Assert.assertNotNull(first);
		assertEquals(java.util.List.of("/"), first.getChangedPaths());
		org.junit.Assert.assertThrows(UnsupportedOperationException.class,
				() -> first.getChangedPaths().add("injected"));
		state.remember(first);
		reply("c:1:2:3:18446744073709551615\0.git/index\0");
		FsMonitor.Probe maximum = state.query(db);
		assertTrue(maximum.isUnchanged());
		assertTrue(maximum.getChangedPaths().isEmpty());
		state.remember(maximum);
		for (String token : new String[] { "c:1:2:3:0", "c:1:2:4:11",
				"c:0:2:3:11", "c:1:2:3:-1", "c:1:2:3:18446744073709551616",
				"c:01:2:3:11", "c:1:2:3:11:extra", "opaque" }) {
			reply(token + "\0");
			FsMonitor.Probe invalidEpoch = state.query(db);
			org.junit.Assert.assertNotNull(invalidEpoch);
			org.junit.Assert.assertFalse(token, invalidEpoch.isUnchanged());
			assertEquals(java.util.List.of("/"),
					invalidEpoch.getChangedPaths());
		}
	}

	@Test
	public void failedScanCannotSeedCacheAndRecoveryRevalidatesEverything()
			throws Exception {
		configure(null);
		try (Git git = new Git(db)) {
			writeTrashFile("tracked", "base");
			git.add().addFilepattern("tracked").call();
			git.commit().setMessage("base").call();
			Status initial = git.status().call();
			byte[] savedIndex = Files.readAllBytes(db.getIndexFile().toPath());
			try {
				Files.write(db.getIndexFile().toPath(), new byte[] { 1, 2, 3 });
				reply("failed\0");
				org.junit.Assert.assertThrows(
						org.eclipse.jgit.api.errors.JGitInternalException.class,
						() -> git.status().call());
			} finally {
				Files.write(db.getIndexFile().toPath(), savedIndex);
			}
			writeTrashFile("tracked", "edit");
			reply("recovered\0");
			Status recovered = git.status().call();
			assertNotSame(initial, recovered);
			assertEquals(Set.of("tracked"), recovered.getModified());
			equal(baseline(git), recovered);
			assertSame(recovered, git.status().call());
		}
	}

	@Test
	public void failedOrMalformedSupportedHookForcesContentChecksOnFallback()
			throws Exception {
		configure(null);
		try (Git git = new Git(db)) {
			File tracked = writeTrashFile("tracked", "one");
			java.nio.file.attribute.FileTime time = java.nio.file.attribute.FileTime
					.fromMillis(System.currentTimeMillis() - 60_000);
			Files.setLastModifiedTime(tracked.toPath(), time);
			git.add().addFilepattern("tracked").call();
			git.commit().setMessage("base").call();
			git.status().call();
			File hook = new File(
					db.getConfig().getString("core", null, "fsmonitor"));
			for (String script : new String[] {
					"printf 'bad\\0../outside\\0'\n", "exit 1\n",
					"printf '\\377\\0'\n" }) {
				Files.writeString(hook.toPath(), "#!/bin/sh\n" + script);
				Files.writeString(tracked.toPath(), "two");
				Files.setLastModifiedTime(tracked.toPath(), time);
				Status modified = git.status().call();
				assertEquals(Set.of("tracked"), modified.getModified());
				assertNotSame(modified, git.status().call());
				assertEquals(" M tracked\0",
						new String(nativeGit(null, "status", "--porcelain=v1",
								"-z", "--untracked-files=all", "--no-renames"),
								StandardCharsets.UTF_8));
				Files.writeString(tracked.toPath(), "one");
				Files.setLastModifiedTime(tracked.toPath(), time);
				assertTrue(git.status().call().isClean());
			}
		}
	}

	@Test
	public void attributesChangedDuringHookRequireContentChecksAndCannotSeedCache()
			throws Exception {
		configureGate();
		File attributes = new File(db.getDirectory(), "global-attributes");
		Files.writeString(attributes.toPath(), "tracked text eol=lf\n");
		db.getConfig().setString("core", null, "attributesFile",
				attributes.getAbsolutePath());
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors
				.newSingleThreadExecutor();
		try (Git git = new Git(db)) {
			File tracked = writeTrashFile("tracked", "line\r\n");
			java.nio.file.Files.setLastModifiedTime(tracked.toPath(),
					java.nio.file.attribute.FileTime
							.fromMillis(System.currentTimeMillis() - 60_000));
			git.add().addFilepattern("tracked").call();
			git.commit().setMessage("base").call();
			Status initial = git.status().call();
			assertTrue(initial.isClean());
			reply("next\0");
			Files.writeString(
					new File(db.getDirectory(), "gate-enabled").toPath(),
					"gate");
			java.util.concurrent.Future<Status> pending = pool
					.submit(() -> git.status().call());
			awaitHook();
			Files.writeString(attributes.toPath(), "tracked -text\n");
			Files.writeString(
					new File(db.getDirectory(), "hook-release").toPath(),
					"release");
			Status changed = pending.get(10,
					java.util.concurrent.TimeUnit.SECONDS);
			assertNotSame(initial, changed);
			assertEquals(Set.of("tracked"), changed.getModified());
			assertEquals(" M tracked\0",
					new String(
							nativeGit(null, "-c",
									"core.attributesFile="
											+ attributes.getAbsolutePath(),
									"status", "--porcelain=v1", "-z",
									"--untracked-files=all", "--no-renames"),
							StandardCharsets.UTF_8));
			Status seeded = git.status().call();
			assertNotSame(changed, seeded);
			assertEquals(changed.getModified(), seeded.getModified());
			assertSame(seeded, git.status().call());
		} finally {
			Files.writeString(
					new File(db.getDirectory(), "hook-release").toPath(),
					"release");
			pool.shutdownNow();
			assertTrue(pool.awaitTermination(10,
					java.util.concurrent.TimeUnit.SECONDS));
		}
	}

	@Test
	public void collectedResultCannotTurnUnchangedDirtyWorkingTreeIntoCleanSnapshot()
			throws Exception {
		configure(null);
		try (Git git = new Git(db)) {
			File tracked = writeTrashFile("tracked", "one");
			java.nio.file.attribute.FileTime time = java.nio.file.attribute.FileTime
					.fromMillis(System.currentTimeMillis() - 60_000);
			Files.setLastModifiedTime(tracked.toPath(), time);
			git.add().addFilepattern("tracked").call();
			git.commit().setMessage("base").call();
			git.status().call();
			Files.writeString(tracked.toPath(), "two");
			Files.setLastModifiedTime(tracked.toPath(), time);
			reply("changed\0tracked\0");
			Status dirty = git.status().call();
			assertEquals(Set.of("tracked"), dirty.getModified());
			reply("unchanged\0");
			assertSame(dirty, git.status().call());
			java.lang.reflect.Field monitored = org.eclipse.jgit.api.StatusCommand.class
					.getDeclaredField("MONITORED");
			monitored.setAccessible(true);
			java.util.Map<?, ?> map = (java.util.Map<?, ?>) monitored.get(null);
			Object cache;
			synchronized (map) {
				cache = map.get(db);
			}
			org.junit.Assert.assertNotNull(cache);
			java.lang.reflect.Field result = cache.getClass()
					.getDeclaredField("result");
			result.setAccessible(true);
			// Deterministically model collection instead of relying on
			// System.gc().
			((java.lang.ref.WeakReference<?>) result.get(cache)).clear();
			Status rebuilt = git.status().call();
			assertNotSame(dirty, rebuilt);
			assertEquals(Set.of("tracked"), rebuilt.getModified());
			assertSame(rebuilt, git.status().call());
			assertEquals(" M tracked\0",
					new String(
							nativeGit(null, "status", "--porcelain=v1", "-z",
									"--untracked-files=all", "--no-renames"),
							StandardCharsets.UTF_8));
		}
	}

	@Test
	public void stateCorrelationIncludesRepositoryAndWorkingTreeIdentity()
			throws Exception {
		configure(null);
		FsMonitor.State state = new FsMonitor.State();
		FsMonitor.Probe initial = state.query(db);
		org.junit.Assert.assertNotNull(initial);
		state.remember(initial);
		reply("next\0");
		assertTrue(state.query(db).isUnchanged());
		org.eclipse.jgit.lib.Repository other = createWorkRepository();
		other.getConfig().fromText(db.getConfig().toText());
		FsMonitor.Probe differentRepository = state.query(other);
		org.junit.Assert.assertNotNull(differentRepository);
		org.junit.Assert.assertFalse(
				"Identical index/HEAD/config must not identify another worktree",
				differentRepository.isUnchanged());
		assertEquals(java.util.List.of("/"),
				differentRepository.getChangedPaths());
	}

	@Test
	public void parserPathBoundaryMatrix() throws Exception {
		for (String path : new String[] { "/absolute", "..", "../x", "x/..",
				"x/../y", "x\\y", "" }) {
			assertNull(path, FsMonitor.parse(("token\0" + path + "\0")
					.getBytes(StandardCharsets.UTF_8)));
		}
		for (String path : new String[] { "/", ".git", ".git/index", "a/./b",
				"a..b", "space name", "line\nname", "tab\tname", "ü/ß",
				"nested/.git", "-option" }) {
			assertEquals(path, FsMonitor.parse(("token\0" + path + "\0")
					.getBytes(StandardCharsets.UTF_8))[1]);
		}
	}

	@Test
	public void parentComponentsAreRejectedAcrossLineTerminators()
			throws Exception {
		for (String separator : new String[] { "\n", "\r", "\u0085", "\u2028", "\u2029" }) {
			assertNull(FsMonitor.parse(("token\0directory" + separator
					+ "/../outside\0").getBytes(StandardCharsets.UTF_8)));
			String valid = "directory" + separator + "/reference..name";
			assertEquals(valid, FsMonitor.parse(("token\0" + valid + "\0")
					.getBytes(StandardCharsets.UTF_8))[1]);
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
	@Test
	public void keepCacheHitDoesNotReparseValidatedIndex() throws Exception {
		configure(null);
		db.getConfig().setString("core", null, "untrackedCache", "keep");
		try (Git git = new Git(db)) {
			writeTrashFile("tracked", "base");
			git.add().addFilepattern("tracked").call();
			git.commit().setMessage("base").call();
			setNativeUntrackedCache(true);
		}
		db.getConfig().save();
		java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
		try (Repository repository = new org.eclipse.jgit.internal.storage.file.FileRepository(
				db.getDirectory()) {
			@Override
			public org.eclipse.jgit.dircache.DirCache readDirCache()
					throws java.io.IOException {
				reads.incrementAndGet();
				return super.readDirCache();
			}
		}; Git git = new Git(repository)) {
			Status first = git.status().call();
			reply("stable\0");
			int before = reads.get();
			for (int i = 0; i < 4; i++) {
				assertSame(first, git.status().call());
			}
			assertEquals("A validated cache hit must not parse the index again",
					before, reads.get());
		}
	}

	private void setNativeUntrackedCache(boolean present) throws Exception {
		org.eclipse.jgit.dircache.DirCache index = db.lockDirCache();
		try {
			index.setUntrackedCache(present
					? new org.eclipse.jgit.internal.storage.file.UntrackedCache()
					: null);
			index.write();
			assertTrue(index.commit());
		} finally {
			index.unlock();
		}
	}

	@Test
	public void keepSettingRevalidatesExtensionAdditionAndRemoval()
			throws Exception {
		configure(null);
		db.getConfig().setString("core", null, "untrackedCache", "keep");
		db.getConfig().setBoolean("index", null, "skipHash", true);
		try (Git git = new Git(db)) {
			writeTrashFile("tracked", "base");
			git.add().addFilepattern("tracked").call();
			git.commit().setMessage("base").call();
			int sequence = 0;
			for (boolean present : new boolean[] { true, false, true, false }) {
				setNativeUntrackedCache(present);
				reply("seed-" + sequence + "\0");
				Status first = git.status().call();
				String name = "new-" + sequence++;
				writeTrashFile(name, "untracked");
				reply("next-" + sequence + "\0");
				Status next = git.status().call();
				if (present) {
					assertSame(first, next);
				} else {
					assertTrue(next.getUntracked().contains(name));
				}
				reply("rescan-" + sequence + "\0/\0");
				equal(baseline(git), git.status().call());
			}
		}
	}

	@Test
	public void effectiveUntrackedSettingsControlUnreportedPathRescans() throws Exception {
		configure(null);
		try (Git git = new Git(db)) {
			writeTrashFile("tracked", "base");
			git.add().addFilepattern("tracked").call();
			git.commit().setMessage("base").call();
			// Staging with core.untrackedCache=true now creates UNTR. This
			// test exercises keep/unset with no stored extension.
			setNativeUntrackedCache(false);
			for (String setting : new String[] { "false", "keep", "unset" }) {
				if ("unset".equals(setting)) {
					db.getConfig().unset("core", null, "untrackedCache");
				} else {
					db.getConfig().setString("core", null, "untrackedCache", setting);
				}
				reply("before-" + setting + "\0/\0");
				git.status().call();
				writeTrashFile("new-" + setting, "untracked");
				reply("after-" + setting + "\0");
				assertTrue(git.status().call().getUntracked().contains("new-" + setting));
			}
			db.getConfig().setBoolean("feature", null, "manyFiles", true);
			reply("many-before\0/\0");
			Status before = git.status().call();
			writeTrashFile("many-new", "unreported");
			reply("many-after\0");
			assertSame(before, git.status().call());
			db.getConfig().setBoolean("core", null, "untrackedCache", false);
			reply("override-before\0/\0");
			git.status().call();
			writeTrashFile("override-new", "unreported");
			reply("override-after\0");
			assertTrue(git.status().call().getUntracked().contains("override-new"));
		}
	}

}
