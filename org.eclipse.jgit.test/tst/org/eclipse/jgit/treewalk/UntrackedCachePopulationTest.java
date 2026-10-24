/*
 * Copyright (C) 2026, and others
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.treewalk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.IndexDiff;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.Test;

import com.google.gson.JsonParser;

/** Native t7063 all-mode population and optional-write boundaries. */
public class UntrackedCachePopulationTest extends RepositoryTestCase {
	private void prepare() throws Exception {
		assumeTrue(db.getFS().getClass() == FS_POSIX.class);
		var config = db.getConfig();
		config.setBoolean("core", null, "untrackedCache", true);
		config.setBoolean("core", null, "fsmonitor", false);
		config.setString("status", null, "showUntrackedFiles", "all");
		config.setInt("index", null, "version", 2);
		Path global = db.getDirectory().toPath().resolve("test-excludes");
		Files.writeString(global, "");
		config.setString("core", null, "excludesFile", global.toString());
		config.save();
		writeTrashFile("tracked", "base\n");
		writeTrashFile("sub/tracked", "base\n");
		try (Git git = Git.wrap(db)) {
			git.add().addFilepattern("tracked").addFilepattern("sub/tracked").call();
			git.commit().setMessage("base").call();
		}
		writeTrashFile("loose", "new\n");
		writeTrashFile("sub/loose", "new\n");
		writeTrashFile("sub/.gitignore", "*.tmp\n");
		writeTrashFile("sub/hidden.tmp", "ignored\n");
		for (String directory : new String[] { "", "sub" }) {
			Files.setLastModifiedTime(db.getWorkTree().toPath().resolve(directory),
					FileTime.fromMillis(System.currentTimeMillis() - 60000));
		}
		assertNull(db.readDirCache().getUntrackedCache().root);
	}

	@Test
	public void javaPopulationIsReusableByNativeGitAndFreshJavaWalk() throws Exception {
		prepare();
		byte[] original = Files.readAllBytes(db.getIndexFile().toPath());
		var before = db.readDirCache();
		try (Git git = Git.wrap(db)) {
			git.status().setReportIgnored(false).call();
			assertArrayEquals("Ordinary status stays read-only", original,
					Files.readAllBytes(db.getIndexFile().toPath()));
			var status = git.status().setReportIgnored(false)
					.setUpdateUntrackedCache(true).call();
			assertEquals(Set.of("loose", "sub/loose", "sub/.gitignore"), status.getUntracked());
		}
		var after = db.readDirCache();
		assertNotNull(after.getUntrackedCache().root);
		assertTrue(after.getUntrackedCache().root.valid);
		assertEquals(before.getEntryCount(), after.getEntryCount());
		for (int i = 0; i < before.getEntryCount(); i++) {
			assertEquals(before.getEntry(i).getObjectId(), after.getEntry(i).getObjectId());
			assertEquals(before.getEntry(i).getFileMode(), after.getEntry(i).getFileMode());
			assertEquals(before.getEntry(i).getStage(), after.getEntry(i).getStage());
		}
		byte[] published = Files.readAllBytes(db.getIndexFile().toPath());
		assertEquals(Set.of("loose", "sub/loose", "sub/.gitignore"), nativeStatus());
		assertArrayEquals(published, Files.readAllBytes(db.getIndexFile().toPath()));
		try (var fresh = new FileRepositoryBuilder().setGitDir(db.getDirectory()).build()) {
			var index = fresh.readDirCache();
			var listing = UntrackedDirectoryListing.create(fresh, index, false);
			assertNotNull(listing);
			Set<String> names = new HashSet<>();
			for (var file : listing.list(new java.io.File(fresh.getWorkTree(), "sub"))) {
				names.add(file.getName());
			}
			assertEquals(Set.of("tracked", "loose", ".gitignore"), names);
			assertEquals(0, index.getUntrackedCache().dirOpened);
		}
	}

	@Test
	public void newerIndexAndChangedIgnoreInputsAreNeverOverwritten() throws Exception {
		prepare();
		var diff = new IndexDiff(db, Constants.HEAD, new FileTreeIterator(db, true));
		diff.setReportIgnored(false);
		diff.diff();
		try (Git git = Git.wrap(db)) {
			git.add().addFilepattern("loose").call();
		}
		byte[] staged = Files.readAllBytes(db.getIndexFile().toPath());
		assertFalse(diff.updateUntrackedCache());
		assertArrayEquals(staged, Files.readAllBytes(db.getIndexFile().toPath()));
		var iterator = new FileTreeIterator(db, true);
		diff = new IndexDiff(db, Constants.HEAD, iterator);
		diff.setReportIgnored(false);
		Files.writeString(db.getWorkTree().toPath().resolve("sub/.gitignore"), "\n");
		// Change root rules after their constructor-time capture.
		Files.writeString(db.getWorkTree().toPath().resolve(".gitignore"), "loose\n");
		diff.diff();
		assertFalse(diff.updateUntrackedCache());
		assertArrayEquals(staged, Files.readAllBytes(db.getIndexFile().toPath()));
	}

	@Test
	public void lockedIndexCancelledScanAndDaemonSettingRemainReadOnly() throws Exception {
		prepare();
		byte[] original = Files.readAllBytes(db.getIndexFile().toPath());
		Path lock = db.getIndexFile().toPath().resolveSibling("index.lock");
		Files.writeString(lock, "busy");
		try (Git git = Git.wrap(db)) {
			git.status().setReportIgnored(false).setUpdateUntrackedCache(true).call();
			assertEquals("busy", Files.readString(lock));
		} finally {
			Files.delete(lock);
		}
		ProgressMonitor cancelled = new ProgressMonitor() {
			private boolean stopped;
			@Override public void start(int totalTasks) { }
			@Override public void beginTask(String title, int totalWork) { }
			@Override public void update(int completed) { }
			@Override public void endTask() { stopped = true; }
			@Override public boolean isCancelled() { return stopped; }
			@Override public void showDuration(boolean enabled) { }
		};
		try (Git git = Git.wrap(db)) {
			git.status().setReportIgnored(false).setUpdateUntrackedCache(true)
					.setProgressMonitor(cancelled).call();
			assertArrayEquals(original, Files.readAllBytes(db.getIndexFile().toPath()));
			db.getConfig().setString("core", null, "fsmonitor", "true");
			db.getConfig().save();
			git.status().setReportIgnored(false).setUpdateUntrackedCache(true).call();
			assertArrayEquals(original, Files.readAllBytes(db.getIndexFile().toPath()));
		}
	}

	@Test
	public void partialIgnoredAndOptionalLockDisabledScansRemainReadOnly() throws Exception {
		prepare();
		byte[] original = Files.readAllBytes(db.getIndexFile().toPath());
		try (Git git = Git.wrap(db)) {
			git.status().setUpdateUntrackedCache(true).call();
			assertArrayEquals(original, Files.readAllBytes(db.getIndexFile().toPath()));
			git.status().setReportIgnored(false).setUpdateUntrackedCache(true)
					.addPath("sub").call();
			assertArrayEquals(original, Files.readAllBytes(db.getIndexFile().toPath()));
			mockSystemReader.setProperty("GIT_OPTIONAL_LOCKS", "0");
			try {
				git.status().setReportIgnored(false).setUpdateUntrackedCache(true).call();
				assertArrayEquals(original, Files.readAllBytes(db.getIndexFile().toPath()));
			} finally {
				mockSystemReader.setProperty("GIT_OPTIONAL_LOCKS", null);
			}
		}
	}

	@Test
	public void ignoreContentEditAfterScanRejectsPublicationWithStableDirectoryStat() throws Exception {
		prepare();
		var diff = new IndexDiff(db, Constants.HEAD, new FileTreeIterator(db, true));
		diff.setReportIgnored(false);
		diff.diff();
		Path directory = db.getWorkTree().toPath().resolve("sub");
		var before = UntrackedDirectoryListing.stat(directory);
		Path ignore = directory.resolve(".gitignore");
		var modified = Files.getLastModifiedTime(ignore);
		Files.writeString(ignore, "*.log\n");
		Files.setLastModifiedTime(ignore, modified);
		assertArrayEquals(before.fields(), UntrackedDirectoryListing.stat(directory).fields());
		byte[] original = Files.readAllBytes(db.getIndexFile().toPath());
		assertFalse(diff.updateUntrackedCache());
		assertArrayEquals(original, Files.readAllBytes(db.getIndexFile().toPath()));
	}

	private Path enableMonitor() throws Exception {
		return enableMonitor(true);
	}

	private Path enableMonitor(boolean nativeInitialization) throws Exception {
		Path hook = db.getDirectory().toPath().resolve("hooks/fsmonitor-index");
		Files.createDirectories(hook.getParent());
		Files.writeString(hook, "#!/bin/sh\ncat .git/fsmonitor-index.response\n");
		assertTrue(hook.toFile().setExecutable(true));
		db.getConfig().setString("core", null, "fsmonitor", hook.toString());
		db.getConfig().setInt("core", null, "fsmonitorHookVersion", 2);
		db.getConfig().setBoolean("jgit", null, "fsmonitorCache", false);
		db.getConfig().save();
		org.eclipse.jgit.junit.NativeFsMonitorIndexSupport.reportToken(db, "initial-clock");
		if (nativeInitialization) {
			var nativeIndex = org.eclipse.jgit.junit.NativeFsMonitorIndexSupport.mutate(db,
					"update-index", "--fsmonitor", "--force-write-index");
			Files.copy(nativeIndex.index(), db.getIndexFile().toPath(),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		return hook;
	}

	private IndexDiff recordedDiff() throws Exception {
		var diff = new IndexDiff(db, Constants.HEAD, new FileTreeIterator(db, true));
		diff.setReportIgnored(false);
		diff.diff();
		return diff;
	}

	@Test
	public void monitoredPopulationWithEmptyReplyIsReusableByNativeGit() throws Exception {
		prepare();
		enableMonitor();
		var diff = recordedDiff();
		org.eclipse.jgit.junit.NativeFsMonitorIndexSupport.reportToken(db, "writer-clock");
		assertTrue(diff.updateUntrackedCache());
		var index = db.readDirCache();
		assertTrue(index.getUntrackedCache().root.valid);
		assertEquals("writer-clock", new String(index.getFsMonitorCache().getToken(),
				java.nio.charset.StandardCharsets.UTF_8));
		assertEquals(Set.of("loose", "sub/loose", "sub/.gitignore"), nativeStatus());
	}

	@Test
	public void writerQueryAfterScanInvalidatesOnlyAffectedCacheDirectories() throws Exception {
		prepare();
		Path hook = enableMonitor();
		var diff = recordedDiff();
		Path stamp = db.getDirectory().toPath().resolve("tracked-stamp");
		Files.writeString(stamp, "");
		Files.setLastModifiedTime(stamp,
				Files.getLastModifiedTime(db.getWorkTree().toPath().resolve("sub/tracked")));
		// The writer query mutates the worktree after build() validated its inputs.
		Files.writeString(hook, "#!/bin/sh\nprintf 'next\\n' > sub/tracked\n"
				+ "touch -r .git/tracked-stamp sub/tracked\n"
				+ "printf 'late\\n' > sub/late\ncat .git/fsmonitor-index.response\n");
		org.eclipse.jgit.junit.NativeFsMonitorIndexSupport.reportToken(db, "late-clock",
				"sub/tracked", "sub/late");
		var events = new java.util.ArrayList<org.eclipse.jgit.events.IndexChangedEvent>();
		var listener = db.getListenerList().addIndexChangedListener(events::add);
		try {
			assertTrue(diff.updateUntrackedCache());
		} finally {
			listener.remove();
		}
		var index = db.readDirCache();
		assertTrue(index.getUntrackedCache().root.valid);
		assertFalse(index.getUntrackedCache().root.directories.get(0).valid);
		assertTrue(index.getFsMonitorCache().getDirtyEntries().get(index.findEntry("sub/tracked")));
		assertEquals(1, events.size());
		assertEquals(Set.of("sub/tracked", "sub/late"), events.get(0).getFsMonitorPaths());
		Files.writeString(hook, "#!/bin/sh\ncat .git/fsmonitor-index.response\n");
		org.eclipse.jgit.junit.NativeFsMonitorIndexSupport.reportToken(db, "next-clock");
		try (Git git = Git.wrap(db)) {
			var status = git.status().setReportIgnored(false).call();
			assertEquals(Set.of("sub/tracked"), status.getModified());
			assertEquals(Set.of("loose", "sub/loose", "sub/.gitignore", "sub/late"), status.getUntracked());
			assertNativeStatus(status);
		}
	}

	@Test
	public void overflowDuringPublicationInvalidatesEveryDirectory() throws Exception {
		prepare();
		enableMonitor();
		var diff = recordedDiff();
		org.eclipse.jgit.junit.NativeFsMonitorIndexSupport.reportToken(db, "overflow-clock", "/");
		assertTrue(diff.updateUntrackedCache());
		var index = db.readDirCache();
		assertFalse(index.getUntrackedCache().root.valid);
		assertFalse(index.getUntrackedCache().root.directories.get(0).valid);
		assertEquals(index.getEntryCount(), index.getFsMonitorCache().getDirtyEntries().cardinality());
		try (Git git = Git.wrap(db)) {
			assertNativeStatus(git.status().setReportIgnored(false).call());
		}
	}

	@Test
	public void failedWriterQueryPublishesOnlyInvalidDirectoryRecords() throws Exception {
		prepare();
		Path hook = enableMonitor();
		var diff = recordedDiff();
		Files.writeString(hook, "#!/bin/sh\nexit 1\n");
		assertTrue(diff.updateUntrackedCache());
		var index = db.readDirCache();
		assertFalse(index.getUntrackedCache().root.valid);
		assertFalse(index.getUntrackedCache().root.directories.get(0).valid);
		assertEquals(index.getEntryCount(), index.getFsMonitorCache().getDirtyEntries().cardinality());
		assertEquals(0, index.getFsMonitorCache().getToken().length);
		try (Git git = Git.wrap(db)) {
			assertNativeStatus(git.status().setReportIgnored(false).call());
		}
	}

	@Test
	public void identicalDirectoryPayloadStillAdvancesMonitorClock() throws Exception {
		prepare();
		enableMonitor();
		assertTrue(recordedDiff().updateUntrackedCache());
		byte[] payload = db.readDirCache().getUntrackedCache().write();
		var diff = recordedDiff();
		org.eclipse.jgit.junit.NativeFsMonitorIndexSupport.reportToken(db, "advanced-clock");
		assertTrue(diff.updateUntrackedCache());
		var index = db.readDirCache();
		assertArrayEquals(payload, index.getUntrackedCache().write());
		assertEquals("advanced-clock", new String(index.getFsMonitorCache().getToken(),
				java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test
	public void directoryNotificationInvalidatesConeAndPreservesLexicalNeighbor() throws Exception {
		prepare();
		writeTrashFile("sub/nested/tracked", "base\n");
		writeTrashFile("sub-other/tracked", "base\n");
		try (Git git = Git.wrap(db)) {
			git.add().addFilepattern("sub/nested/tracked").addFilepattern("sub-other/tracked").call();
			git.commit().setMessage("nested").call();
		}
		enableMonitor();
		var diff = recordedDiff();
		org.eclipse.jgit.junit.NativeFsMonitorIndexSupport.reportToken(db, "directory-clock", "sub/");
		assertTrue(diff.updateUntrackedCache());
		var root = db.readDirCache().getUntrackedCache().root;
		assertFalse(root.valid);
		assertFalse(root.directories.get(0).valid);
		assertFalse(root.directories.get(0).directories.get(0).valid);
		assertTrue(root.directories.get(1).valid);
		try (Git git = Git.wrap(db)) {
			assertNativeStatus(git.status().setReportIgnored(false).call());
		}
	}

	private void markAllDirty() throws Exception {
		var index = db.lockDirCache();
		try {
			java.util.BitSet dirty = new java.util.BitSet();
			dirty.set(0, index.getEntryCount());
			index.setFsMonitorCache(new org.eclipse.jgit.internal.storage.file.FsMonitorCache(
					Constants.encode("initial-clock"), dirty));
			index.write();
			assertTrue(index.commit());
		} finally {
			index.unlock();
		}
	}

	@Test
	public void verifiedCleanEntriesArePublishedWithoutKeepingOldDirtyBits() throws Exception {
		prepare();
		enableMonitor();
		// Align native stat fields before comparing refresh validity: the first
		// C refresh of a JGit index fills inode/device fields and leaves bits dirty.
		var initialized = org.eclipse.jgit.junit.NativeFsMonitorIndexSupport.mutate(db,
				"update-index", "--refresh", "--force-write-index");
		Files.copy(initialized.index(), db.getIndexFile().toPath(),
				java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		markAllDirty();
		var expected = org.eclipse.jgit.junit.NativeFsMonitorIndexSupport.mutate(db,
				"update-index", "--refresh", "--force-write-index");
		assertTrue(recordedDiff().updateUntrackedCache());
		assertTrue(db.readDirCache().getFsMonitorCache().getDirtyEntries().isEmpty());
		assertArrayEquals("native=" + java.util.HexFormat.of().formatHex(expected.payload())
				+ "; dirty=" + org.eclipse.jgit.internal.storage.file.FsMonitorCache.read(
						expected.payload(), db.readDirCache().getEntryCount()).getDirtyEntries(),
				expected.payload(), org.eclipse.jgit.junit.NativeFsMonitorIndexSupport
				.extension(Files.readAllBytes(db.getIndexFile().toPath())));
	}

	@Test
	public void modifiedEntriesRemainDirtyWhenCleanNeighborsArePublished() throws Exception {
		prepare();
		enableMonitor();
		markAllDirty();
		Path path = db.getWorkTree().toPath().resolve("sub/tracked");
		var time = Files.getLastModifiedTime(path);
		Files.writeString(path, "next\n");
		Files.setLastModifiedTime(path, time);
		var diff = recordedDiff();
		assertEquals(Set.of("sub/tracked"), diff.getModified());
		assertTrue(diff.updateUntrackedCache());
		var index = db.readDirCache();
		assertEquals(1, index.getFsMonitorCache().getDirtyEntries().cardinality());
		assertTrue(index.getFsMonitorCache().getDirtyEntries().get(index.findEntry("sub/tracked")));
		try (Git git = Git.wrap(db)) {
			assertNativeStatus(git.status().setReportIgnored(false).call());
		}
	}

	@Test
	public void firstMonitorPublicationInitializesBeforeLaterCleanReuse() throws Exception {
		prepare();
		enableMonitor(false);
		assertNull(db.readDirCache().getFsMonitorCache());
		assertTrue(recordedDiff().updateUntrackedCache());
		var first = db.readDirCache();
		assertNotNull(first.getFsMonitorCache());
		assertFalse(first.getUntrackedCache().root.valid);
		assertEquals(first.getEntryCount(), first.getFsMonitorCache().getDirtyEntries().cardinality());
		org.eclipse.jgit.junit.NativeFsMonitorIndexSupport.reportToken(db, "reused-clock");
		assertTrue(recordedDiff().updateUntrackedCache());
		var second = db.readDirCache();
		assertTrue(second.getUntrackedCache().root.valid);
		assertTrue(second.getFsMonitorCache().getDirtyEntries().isEmpty());
		assertEquals(Set.of("loose", "sub/loose", "sub/.gitignore"), nativeStatus());
	}

	@Test
	public void recordingWalkReusesValidNativeDirectoryListings() throws Exception {
		prepare();
		assertTrue(recordedDiff().updateUntrackedCache());
		var index = db.readDirCache();
		var iterator = new FileTreeIterator(db, true);
		iterator.setUntrackedCache(index, false);
		Set<String> paths = new HashSet<>();
		try (var walk = new TreeWalk(db)) {
			walk.addTree(iterator);
			walk.setRecursive(true);
			while (walk.next()) paths.add(walk.getPathString());
		}
		assertTrue(paths.containsAll(Set.of("tracked", "sub/tracked", "sub/loose", "sub/.gitignore")));
		assertFalse(paths.contains("sub/hidden.tmp"));
		assertEquals(0, index.getUntrackedCache().dirOpened);
	}

	@Test
	public void scanQueryInvalidatesDirectoryRecordsBeforeReuse() throws Exception {
		prepare();
		enableMonitor();
		assertTrue(recordedDiff().updateUntrackedCache());
		var index = db.readDirCache();
		assertTrue(index.getUntrackedCache().root.valid);
		assertTrue(index.getUntrackedCache().root.directories.get(0).valid);
		org.eclipse.jgit.junit.NativeFsMonitorIndexSupport.reportToken(db, "directory-clock", "sub/");
		var query = org.eclipse.jgit.util.FsMonitor.queryStoredIndex(db, index);
		assertNotNull(query.probe());
		query.invalidateUntrackedCache(db, index);
		assertFalse(index.getUntrackedCache().root.valid);
		assertFalse(index.getUntrackedCache().root.directories.get(0).valid);
	}

	@Test
	public void assumedAndSkippedEntriesAreNeverPublishedAsVerified() throws Exception {
		prepare();
		enableMonitor();
		for (String[] arguments : new String[][] {
				{ "update-index", "--assume-unchanged", "tracked" },
				{ "update-index", "--skip-worktree", "sub/tracked" } }) {
			var mutation = org.eclipse.jgit.junit.NativeFsMonitorIndexSupport.mutate(db, arguments);
			Files.copy(mutation.index(), db.getIndexFile().toPath(),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		markAllDirty();
		assertTrue(recordedDiff().updateUntrackedCache());
		var index = db.readDirCache();
		assertTrue(index.getEntry("tracked").isAssumeValid());
		assertTrue(index.getEntry("sub/tracked").isSkipWorkTree());
		assertEquals(index.getEntryCount(), index.getFsMonitorCache().getDirtyEntries().cardinality());
	}

	private void assertNativeStatus(org.eclipse.jgit.api.Status status) throws Exception {
		byte[] raw = org.eclipse.jgit.junit.NativeFsMonitorIndexSupport.status(db, false);
		Set<String> untracked = new HashSet<>();
		Set<String> modified = new HashSet<>();
		for (String record : new String(raw, java.nio.charset.StandardCharsets.UTF_8).split("\0")) {
			if (record.startsWith("?? ")) untracked.add(record.substring(3));
			else if (record.startsWith(" M ")) modified.add(record.substring(3));
			else if (!record.isEmpty() && !record.startsWith("!! ")) throw new AssertionError(record);
		}
		assertEquals(untracked, status.getUntracked());
		assertEquals(modified, status.getModified());
		assertTrue(status.getAdded().isEmpty());
		assertTrue(status.getChanged().isEmpty());
		assertTrue(status.getRemoved().isEmpty());
		assertTrue(status.getMissing().isEmpty());
		assertTrue(status.getConflicting().isEmpty());
	}

	private Set<String> nativeStatus() throws Exception {
		Path trace = db.getDirectory().toPath().resolve("native-population-trace.jsonl");
		Path output = db.getDirectory().toPath().resolve("native-population-status.z");
		Path error = db.getDirectory().toPath().resolve("native-population-status.err");
		ProcessBuilder builder = new ProcessBuilder(System.getProperty("jgit.test.gitExecutable", "git"),
				"--no-optional-locks", "-c", "core.fsmonitor=false", "status",
				"--porcelain=v1", "-z", "--untracked-files=all", "--no-renames");
		builder.directory(db.getWorkTree()).redirectOutput(output.toFile()).redirectError(error.toFile());
		builder.environment().keySet().removeIf(name -> name.startsWith("GIT_"));
		builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
		builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
		builder.environment().put("GIT_TRACE2_EVENT", trace.toString());
		builder.environment().put("GIT_TRACE2_EVENT_NESTING", "20");
		Process child = builder.start();
		try {
			assertTrue(child.waitFor(20, TimeUnit.SECONDS));
			assertEquals(Files.readString(error), 0, child.exitValue());
		} finally {
			child.destroyForcibly();
		}
		boolean sawOpens = false;
		for (String line : Files.readAllLines(trace)) {
			var event = JsonParser.parseString(line).getAsJsonObject();
			if (event.has("key") && "opendir".equals(event.get("key").getAsString())) {
				assertEquals("Native Git must reuse Java-generated directory records", 0,
						event.get("value").getAsInt());
				sawOpens = true;
			}
		}
		assertTrue("Native UNTR counters must be present: " + Files.readString(error)
				+ Files.readString(trace), sawOpens);
		Set<String> paths = new HashSet<>();
		for (String record : new String(Files.readAllBytes(output), java.nio.charset.StandardCharsets.UTF_8).split("\0")) {
			if (!record.isEmpty()) {
				assertTrue(record, record.startsWith("?? "));
				paths.add(record.substring(3));
			}
		}
		return paths;
	}
}
