/*
 * Copyright (C) 2026, and others
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.junit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.FS_POSIX;

/** Native t7063 sparse, indexed-ignore and membership scenarios. @since 7.8 */
public final class NativeSparseUntrackedSupport {
	private NativeSparseUntrackedSupport() {
	}

	/**
	 * Complete API categories plus native normal-mode summaries.
	 * 
	 * @param modified
	 *            modified paths
	 * @param untracked
	 *            individual untracked paths
	 * @param ignored
	 *            ignored paths
	 * @param added
	 *            staged additions
	 * @param changed
	 *            staged modifications
	 * @param missing
	 *            unstaged deletions
	 * @param normalUntracked
	 *            native directory summaries
	 * @since 7.8
	 */
	public record Snapshot(Set<String> modified, Set<String> untracked,
			Set<String> ignored, Set<String> added, Set<String> changed,
			Set<String> missing, Set<String> normalUntracked) {
		/**
		 * @param includeIgnored
		 *            include ignored records
		 * @param normal
		 *            use native directory summaries
		 * @return all expected native NUL record values, without delimiters
		 */
		public Set<String> records(boolean includeIgnored, boolean normal) {
			Set<String> records = new HashSet<>();
			add(records, " M ", modified);
			add(records, "?? ", normal ? normalUntracked : untracked);
			add(records, "A  ", added);
			add(records, "M  ", changed);
			add(records, " D ", missing);
			if (includeIgnored)
				add(records, "!! ", ignored);
			return records;
		}

		private static void add(Set<String> records, String prefix,
				Set<String> paths) {
			for (String path : paths)
				assertTrue(records.add(prefix + path));
		}
	}

	/**
	 * Prepare the post-commit sparse layout of t7063. Retain .project in EGit.
	 * 
	 * @param repository
	 *            disposable initialized repository
	 * @param cache
	 *            untracked cache policy
	 * @param preload
	 *            preload policy
	 * @param sparseConfig
	 *            retain sparse config after applying patterns
	 * @throws Exception
	 *             on fixture/native failure
	 * @since 7.8
	 */
	public static void prepare(Repository repository, boolean cache,
			boolean preload, boolean sparseConfig) throws Exception {
		assumeTrue(repository.getFS().getClass() == FS_POSIX.class);
		var config = repository.getConfig();
		config.setBoolean("core", null, "untrackedCache", cache);
		config.setBoolean("core", null, "preloadIndex", preload);
		config.setBoolean("core", null, "fsmonitor", false);
		config.setBoolean("feature", null, "manyFiles", false);
		config.setInt("index", null, "version", 2);
		config.setString("status", null, "showUntrackedFiles", "normal");
		Path excludes = repository.getDirectory().toPath()
				.resolve("sparse-excludes");
		Files.writeString(excludes, "");
		config.setString("core", null, "excludesFile", excludes.toString());
		config.save();
		for (String path : List.of("one", "two", "done/one", "done/two"))
			NativeUntrackedNormalSupport.write(repository, path, "base\n");
		try (Git git = Git.wrap(repository)) {
			git.add().addFilepattern(".").call();
			NativeUntrackedNormalSupport.write(repository, "done/.gitignore",
					"two\nthree\n");
			git.add().addFilepattern("done/.gitignore").call();
			git.commit().setAuthor("Sparse Test", "sparse@example.invalid")
					.setCommitter("Sparse Test", "sparse@example.invalid")
					.setMessage("sparse base").call();
		}
		Path info = repository.getDirectory().toPath().resolve("info");
		Files.createDirectories(info);
		Files.writeString(info.resolve("sparse-checkout"),
				".project\ndone/[a-z]*\n");
		config.setBoolean("core", null, "sparseCheckout", true);
		config.save();
		replaceIndex(repository, NativeFsMonitorIndexSupport
				.mutate(repository, "checkout", "--quiet", "HEAD").index());
		if (!sparseConfig) {
			// Native test_config restores this setting after sparse setup.
			config.unset("core", null, "sparseCheckout");
			config.save();
		}
		assertFalse(Files.exists(
				repository.getWorkTree().toPath().resolve("done/.gitignore")));
		assertTrue(repository.readDirCache().getEntry("done/.gitignore")
				.isSkipWorkTree());
		assertTrue(Files.isRegularFile(
				repository.getWorkTree().toPath().resolve("done/one")));
		for (var item : java.util.Map.of(".gitignore", "four\n", "done/two",
				"two bis\n", "done/three", "three\n", "done/four", "four\n",
				"done/five", "five\n", "dtwo/two", "two\n").entrySet())
			NativeUntrackedNormalSupport.write(repository, item.getKey(),
					item.getValue());
		NativeUntrackedNormalSupport.ageDirectories(repository);
		assertNative(repository, expected(false, false));
	}

	/**
	 * @param nested
	 *            include the nested untracked directory
	 * @param staged
	 *            dtwo/two is staged
	 * @return original sparse status, optionally nested and staged
	 */
	public static Snapshot expected(boolean nested, boolean staged) {
		Set<String> untracked = new HashSet<>(
				Set.of(".gitignore", "done/five"));
		Set<String> normal = new HashSet<>(untracked);
		if (!staged) {
			untracked.add("dtwo/two");
			normal.add("dtwo/");
		}
		if (nested) {
			untracked.add("done/sub/sub/file");
			normal.add("done/sub/");
		}
		return new Snapshot(Set.of("done/two"), Set.copyOf(untracked),
				Set.of("done/three", "done/four"),
				staged ? Set.of("dtwo/two") : Set.of(), Set.of(), Set.of(),
				Set.copyOf(normal));
	}

	/**
	 * @param sparse whether native sparse checkout clears a present file's skip flag
	 * @return native result when an existing worktree ignore overrides the
	 *         skipped indexed ignore
	 * @since 7.8
	 */
	public static Snapshot worktreeRules(boolean sparse) {
		return new Snapshot(sparse ? Set.of("done/two", "done/.gitignore")
				: Set.of("done/two"),
				Set.of(".gitignore", "done/three", "dtwo/two"),
				Set.of("done/four", "done/five"), Set.of(), Set.of(), Set.of(),
				Set.of(".gitignore", "done/three", "dtwo/"));
	}

	/**
	 * @return native result with parent and global rules composed with the
	 *         skipped indexed ignore
	 * @since 7.8
	 */
	public static Snapshot parentAndGlobalRules() {
		return new Snapshot(Set.of("done/two"),
				Set.of(".gitignore", "done/five", "dtwo/two"),
				Set.of("done/three", "done/four", "done/parent-only",
						"done/global-only"),
				Set.of(), Set.of(), Set.of(),
				Set.of(".gitignore", "done/five", "dtwo/"));
	}

	/** @return native result after an ordinary tracked ignore deletion */
	public static Snapshot ordinaryDeletion() {
		return new Snapshot(Set.of("done/two"),
				Set.of(".gitignore", "done/three", "done/five", "dtwo/two"),
				Set.of("done/four"), Set.of(), Set.of(),
				Set.of("done/.gitignore"),
				Set.of(".gitignore", "done/three", "done/five", "dtwo/"));
	}

	/** @return native result after changing only the skipped index blob */
	public static Snapshot changedIndexRules() {
		return new Snapshot(Set.of("done/two"),
				Set.of(".gitignore", "done/three", "dtwo/two"),
				Set.of("done/four", "done/five"), Set.of(),
				Set.of("done/.gitignore"), Set.of(),
				Set.of(".gitignore", "done/three", "dtwo/"));
	}

	/**
	 * Verify every independent native porcelain record, including ignored
	 * paths.
	 * 
	 * @param repository
	 *            fixture
	 * @param expected
	 *            complete expected snapshot
	 * @throws Exception
	 *             on native or semantic failure
	 * @since 7.8
	 */
	public static void assertNative(Repository repository, Snapshot expected)
			throws Exception {
		assertEquals(expected.records(true, false),
				records(NativeFsMonitorIndexSupport.status(repository, false)));
	}

	/**
	 * Require normal-format Java publication to be reusable by C Git.
	 * 
	 * @param repository
	 *            fixture
	 * @param expected
	 *            complete expected snapshot
	 * @param enabled
	 *            cache policy
	 * @throws Exception
	 *             on native or semantic failure
	 * @since 7.8
	 */
	public static void assertPublication(Repository repository,
			Snapshot expected, boolean enabled) throws Exception {
		if (!enabled) {
			assertEquals(null, repository.readDirCache().getUntrackedCache());
			return;
		}
		var cache = repository.readDirCache().getUntrackedCache();
		assertTrue(cache != null && cache.root != null && cache.root.valid);
		assertEquals(6, cache.dirFlags);
		assertEquals(expected.records(false, true),
				records(NativeUntrackedNormalSupport
						.nativeStatusOutput(repository, "normal", true)));
	}

	private static Set<String> records(byte[] raw) {
		Set<String> result = new HashSet<>();
		for (String record : new String(raw, StandardCharsets.UTF_8)
				.split("\0"))
			if (!record.isEmpty())
				assertTrue("Duplicate native record: " + record,
						result.add(record));
		return result;
	}

	/**
	 * Disable only the ignore entry's skip flag; its file remains absent.
	 * 
	 * @param repository
	 *            disposable fixture
	 * @throws Exception
	 *             on native failure
	 */
	public static void clearIgnoreSkipFlag(Repository repository)
			throws Exception {
		replaceIndex(repository,
				NativeFsMonitorIndexSupport
						.mutate(repository, "update-index",
								"--no-skip-worktree", "done/.gitignore")
						.index());
	}

	/**
	 * Change skipped ignore contents without materializing the worktree file.
	 * 
	 * @param repository
	 *            disposable fixture
	 * @throws Exception
	 *             on object/index failure
	 */
	public static void replaceIndexedRules(Repository repository)
			throws Exception {
		try (var inserter = repository.newObjectInserter()) {
			var id = inserter.insert(Constants.OBJ_BLOB,
					Constants.encode("five\n"));
			inserter.flush();
			DirCache index = repository.lockDirCache();
			try {
				var builder = index.builder();
				for (int i = 0; i < index.getEntryCount(); i++) {
					var entry = new org.eclipse.jgit.dircache.DirCacheEntry(
							index.getEntry(i));
					if ("done/.gitignore".equals(entry.getPathString())) {
						assertTrue(entry.isSkipWorkTree());
						entry.setObjectId(id);
					}
					builder.add(entry);
				}
				// Rebuild the index tree as a real staged edit does.
				builder.finish();
				index.write();
				assertTrue(index.commit());
			} finally {
				index.unlock();
			}
		}
		assertFalse(Files.exists(
				repository.getWorkTree().toPath().resolve("done/.gitignore")));
	}

	private static void replaceIndex(Repository repository, Path index)
			throws Exception {
		Files.copy(index, repository.getIndexFile().toPath(),
				StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.COPY_ATTRIBUTES);
		assertArrayEquals(Files.readAllBytes(index),
				Files.readAllBytes(repository.getIndexFile().toPath()));
	}
}
