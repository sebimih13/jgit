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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.IndexDiff;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.junit.Test;

public class PreloadIndexCommandTest extends RepositoryTestCase {
	@Test
	public void nativePreloadDefaultsAndPathBoundsHaveExpectedStatCounters() throws Exception {
		org.junit.Assume.assumeTrue(db.getFS().getClass() == org.eclipse.jgit.util.FS_POSIX.class);
		try (Git git = Git.wrap(db)) {
			populate(git);
			byte[] original = Files.readAllBytes(db.getIndexFile().toPath());
			assertEquals(Integer.valueOf(1000), nativePreloadCounter("default-all", null, null));
			assertEquals(null, nativePreloadCounter("false-all", Boolean.FALSE, null));
			assertEquals(Integer.valueOf(1), nativePreloadCounter("default-file", null, "dir/f0"));
			assertEquals(Integer.valueOf(0), nativePreloadCounter("default-prefix", null, "di"));
			assertArrayEquals(original, Files.readAllBytes(db.getIndexFile().toPath()));
		}
	}

	private Integer nativePreloadCounter(String label, Boolean enabled, String path)
			throws Exception {
		Path trace = db.getDirectory().toPath().resolve(label + ".jsonl");
		Path output = db.getDirectory().toPath().resolve(label + ".out");
		Path error = db.getDirectory().toPath().resolve(label + ".err");
		var arguments = new java.util.ArrayList<>(List.of(
				System.getProperty("jgit.test.gitExecutable", "git"), "--no-optional-locks",
				"-c", "core.fsmonitor=false", "-c", "core.untrackedCache=false"));
		if (enabled != null) arguments.addAll(List.of("-c", "core.preloadIndex=" + enabled));
		arguments.addAll(List.of("status", "--porcelain=v1", "-z", "--untracked-files=all"));
		if (path != null) arguments.addAll(List.of("--", path));
		ProcessBuilder builder = new ProcessBuilder(arguments).directory(db.getWorkTree())
				.redirectOutput(output.toFile()).redirectError(error.toFile());
		builder.environment().keySet().removeIf(name -> name.startsWith("GIT_"));
		builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
		builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
		builder.environment().put("GIT_TRACE2_EVENT", trace.toString());
		builder.environment().put("GIT_TRACE2_EVENT_NESTING", "20");
		Process process = builder.start();
		try {
			assertTrue(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS));
			assertEquals(Files.readString(error), 0, process.exitValue());
		} finally {
			process.destroyForcibly();
		}
		assertEquals(0, Files.size(output));
		Integer counter = null;
		for (String line : Files.readAllLines(trace)) {
			var event = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
			if (event.has("key") && "preload/sum_lstat".equals(event.get("key").getAsString())) {
				assertEquals(null, counter);
				counter = Integer.valueOf(event.get("value").getAsInt());
			}
		}
		return counter;
	}

	@Test
	public void defaultAndExplicitPreloadPreserveFilteredNativeDiffs() throws Exception {
		try (Git git = Git.wrap(db)) {
			populate(git);
			writeTrashFile("dir/white\nspace", "base\n");
			writeTrashFile("dir-other/file", "base\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("path boundaries").call();
			for (String path : List.of("dir/f0", "dir/f999", "dir/white\nspace", "dir-other/file"))
				writeTrashFile(path, "changed content\n");
			byte[] original = Files.readAllBytes(db.getIndexFile().toPath());
			for (Boolean preload : new Boolean[] { null, Boolean.FALSE, Boolean.TRUE }) {
				if (preload == null) db.getConfig().unset("core", null, "preloadIndex");
				else enabled(preload.booleanValue());
				for (String path : List.of("dir", "dir/white\nspace", "dir/f0")) {
					java.util.Set<String> expected = path.equals("dir")
							? java.util.Set.of("dir/f0", "dir/f999", "dir/white\nspace")
							: java.util.Set.of(path);
					var status = git.status().addPath(path).call();
					assertEquals(expected, status.getModified());
					assertTrue(status.getAdded().isEmpty());
					assertTrue(status.getChanged().isEmpty());
					assertTrue(status.getRemoved().isEmpty());
					assertTrue(status.getMissing().isEmpty());
					assertTrue(status.getUntracked().isEmpty());
					assertTrue(status.getConflicting().isEmpty());
					java.util.Set<String> actual = new java.util.HashSet<>();
					for (var diff : git.diff().setPathFilter(
							org.eclipse.jgit.treewalk.filter.PathFilterGroup.createFromStrings(path)).call())
						actual.add(diff.getNewPath());
					assertEquals(expected, actual);
					org.eclipse.jgit.junit.NativeFsMonitorWorkloadSupport.assertNativeDiff(db,
							false, path, expected);
				}
			}
			assertArrayEquals(original, Files.readAllBytes(db.getIndexFile().toPath()));
		}
	}

	private void populate(Git git) throws Exception {
		for (int i = 0; i < 1000; i++)
			writeTrashFile("dir/f" + i, "base\n");
		git.add().addFilepattern(".").call();
		git.commit().setMessage("base").call();
	}

	private void enabled(boolean value) {
		db.getConfig().setBoolean("core", null, "preloadIndex", value);
	}

	private void sameStatus(Status a, Status b) {
		assertEquals(a.getAdded(), b.getAdded());
		assertEquals(a.getChanged(), b.getChanged());
		assertEquals(a.getRemoved(), b.getRemoved());
		assertEquals(a.getMissing(), b.getMissing());
		assertEquals(a.getModified(), b.getModified());
		assertEquals(a.getUntracked(), b.getUntracked());
		assertEquals(a.getIgnoredNotInIndex(), b.getIgnoredNotInIndex());
		assertEquals(a.getConflictingStageState(),
				b.getConflictingStageState());
	}

	@Test
	public void statusAndDiffRemainEquivalentAcrossMutations()
			throws Exception {
		try (Git git = new Git(db)) {
			populate(git);
			enabled(true);
			assertTrue(git.status().call().isClean());
			writeTrashFile("dir/f0", "staged\n");
			git.add().addFilepattern("dir/f0").call();
			writeTrashFile("dir/f0", "unstaged after staged\n");
			deleteTrashFile("dir/f1");
			writeTrashFile("dir/new", "new");
			writeTrashFile(".gitignore", "*.ignored\n");
			writeTrashFile("dir/a.ignored", "ignored");
			enabled(false);
			Status serial = git.status().call();
			ByteArrayOutputStream off = new ByteArrayOutputStream();
			List<org.eclipse.jgit.diff.DiffEntry> entries = git.diff()
					.setOutputStream(off).call();
			enabled(true);
			sameStatus(serial, git.status().call());
			ByteArrayOutputStream on = new ByteArrayOutputStream();
			List<org.eclipse.jgit.diff.DiffEntry> parallel = git.diff()
					.setOutputStream(on).call();
			assertEquals(entries.size(), parallel.size());
			assertArrayEquals(off.toByteArray(), on.toByteArray());
			assertTrue(git.status().call().getModified().contains("dir/f0"));
			assertTrue(git.status().call().getMissing().contains("dir/f1"));
			// Filtered operations must retain their normal narrow traversal.
			assertEquals(java.util.Set.of("dir/f0"),
					git.status().addPath("dir/f0").call().getModified());
			assertEquals(1, git.diff()
					.setPathFilter(PathFilter.create("dir/f0")).call().size());
			writeTrashFile("dir/f1", "restored");
			enabled(false);
			Status restored = git.status().call();
			enabled(true);
			sameStatus(restored, git.status().call());
		}
	}

	@Test
	public void forcedContentCheckSurvivesPreloadedMetadata() throws Exception {
		try (Git git = new Git(db)) {
			populate(git);
			enabled(true);
			Path file = db.getWorkTree().toPath().resolve("dir/f0");
			java.nio.file.attribute.FileTime stamp = Files
					.getLastModifiedTime(file);
			Files.writeString(file, "BASE\n");
			Files.setLastModifiedTime(file, stamp);
			IndexDiff diff = new IndexDiff(db, Constants.HEAD,
					new FileTreeIterator(db));
			diff.setForceContentCheckPaths(java.util.Set.of("dir/f0"));
			diff.diff();
			assertTrue(diff.getModified().contains("dir/f0"));
		}
	}

	@Test
	public void checkoutProtectsDirtyFilesAndRefreshesMetadata()
			throws Exception {
		try (Git git = new Git(db)) {
			populate(git);
			git.branchCreate().setName("other").call();
			writeTrashFile("dir/f0", "other version\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("second").call();
			enabled(true);
			git.checkout().setName("other").call();
			assertEquals("base\n", Files.readString(
					Path.of(db.getWorkTree().toString(), "dir/f0")));
			writeTrashFile("dir/f0", "local dirty\n");
			assertThrows(CheckoutConflictException.class,
					() -> git.checkout().setName("master").call());
			assertEquals("local dirty\n", Files.readString(
					Path.of(db.getWorkTree().toString(), "dir/f0")));
			writeTrashFile("dir/f0", "base\n");
			git.checkout().setName("master").call();
			assertEquals("other version\n", Files.readString(
					Path.of(db.getWorkTree().toString(), "dir/f0")));
			assertTrue(git.status().call().isClean());
		}
	}
}
