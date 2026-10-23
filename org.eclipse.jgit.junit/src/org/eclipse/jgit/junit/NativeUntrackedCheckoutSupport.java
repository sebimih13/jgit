/*
 * Copyright (C) 2026, and others
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.junit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;

/**
 * Native unpack-trees and checkout cache fixtures, shared with EGit operations.
 *
 * @since 7.8
 */
public final class NativeUntrackedCheckoutSupport {
	private NativeUntrackedCheckoutSupport() {
	}

	/**
	 * Fixture commits and the untracked paths present before checkout.
	 *
	 * @param base initial commit
	 * @param target commit changing one tracked path
	 * @param path changed path
	 * @param untracked original untracked paths
	 */
	public record Scenario(RevCommit base, RevCommit target, String path,
			Set<String> untracked) {
	}

	/**
	 * Create two commits and return to the first, then populate real native UNTR.
	 *
	 * @param repository disposable fixture
	 * @param mode native normal/all untracked mode
	 * @param changedPath tracked path changed by the target
	 * @return commits and baseline status
	 * @throws Exception on preparation failure
	 */
	public static Scenario prepare(Repository repository, String mode, String changedPath)
			throws Exception {
		var config = repository.getConfig();
		config.setBoolean("feature", null, "manyFiles", false);
		config.setString("core", null, "untrackedCache", "keep");
		config.setString("status", null, "showUntrackedFiles", mode);
		config.setInt("index", null, "version", 2);
		config.setBoolean("jgit", null, "fsmonitorCache", false);
		config.save();
		Path root = repository.getWorkTree().toPath();
		Files.createDirectories(root.resolve("sub"));
		Files.createDirectories(root.resolve("other"));
		Files.writeString(root.resolve("two"), "base\n");
		Files.writeString(root.resolve("sub/tracked"), "base\n");
		Files.writeString(root.resolve("sub/loose"), "loose\n");
		Files.writeString(root.resolve("other/loose"), "loose\n");
		RevCommit base;
		RevCommit target;
		Set<String> untracked;
		try (Git git = Git.wrap(repository)) {
			var add = git.add().addFilepattern("two").addFilepattern("sub/tracked");
			// Keep connected Eclipse projects present in both commits.
			if (Files.isRegularFile(root.resolve(".project")))
				add.addFilepattern(".project");
			add.call();
			base = git.commit().setMessage("base").setAllowEmpty(true)
					.setAuthor("Cache Test", "cache@example.invalid")
					.setCommitter("Cache Test", "cache@example.invalid").call();
			Files.writeString(root.resolve(changedPath), "next\n");
			git.add().addFilepattern(changedPath).call();
			target = git.commit().setMessage("target")
					.setAuthor("Cache Test", "cache@example.invalid")
					.setCommitter("Cache Test", "cache@example.invalid").call();
			git.checkout().setName(base.name()).call();
			untracked = Set.copyOf(git.status().call().getUntracked());
		}
		for (String directory : List.of("", "sub", "other"))
			Files.setLastModifiedTime(root.resolve(directory),
					FileTime.fromMillis(System.currentTimeMillis() - 60000));
		NativeFsMonitorWorkloadSupport.warmNativeUntrackedCache(repository);
		var cache = repository.readDirCache().getUntrackedCache();
		assertTrue("Real native cache must be warm", cache.root.valid);
		assertEquals("Native traversal mode", "all".equals(mode) ? 0 : 6, cache.dirFlags);
		return new Scenario(base, target, changedPath, untracked);
	}

	/**
	 * Check raw cache bytes, complete index entries and actual checkout results.
	 *
	 * @param repository fixture after the Java operation
	 * @param scenario prepared fixture
	 * @param branch whether HEAD moves to the target, or only a path is checked out
	 * @throws Exception on a comparison failure
	 */
	public static void assertResult(Repository repository, Scenario scenario, boolean branch)
			throws Exception {
		NativeFsMonitorWorkloadSupport.assertNativeUntrackedCachePolicy(repository);
		NativeFsMonitorWorkloadSupport.assertStagedMatches(repository,
				repository.getDirectory().toPath().resolve("native-untracked-policy.index"));
		assertEquals("next\n", Files.readString(repository.getWorkTree().toPath().resolve(scenario.path())));
		assertEquals(branch ? scenario.target() : scenario.base(), repository.resolve("HEAD"));
		try (Git git = Git.wrap(repository)) {
			var status = git.status().call();
			assertEquals(scenario.untracked(), status.getUntracked());
			assertTrue(status.getModified().isEmpty());
			assertTrue(status.getMissing().isEmpty());
			assertTrue(status.getAdded().isEmpty());
			assertTrue(status.getRemoved().isEmpty());
			assertTrue(status.getConflicting().isEmpty());
			assertEquals(branch ? Set.of() : Set.of(scenario.path()), status.getChanged());
		}
	}
}
