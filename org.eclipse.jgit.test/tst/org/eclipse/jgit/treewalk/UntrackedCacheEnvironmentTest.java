/*
 * Copyright (C) 2026, and others
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.treewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.junit.NativeUntrackedEnvironmentSupport;
import org.eclipse.jgit.junit.NativeUntrackedNormalSupport;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class UntrackedCacheEnvironmentTest extends RepositoryTestCase {

	@Parameterized.Parameters(name = "disable={0}, enabled={1}, manyFiles={2}")
	public static List<Object[]> scenarios() {
		return NativeUntrackedEnvironmentSupport.scenarios();
	}

	private final String value;

	private final boolean enabled;

	private final boolean many;

	public UntrackedCacheEnvironmentTest(String value, boolean enabled,
			boolean many) {
		this.value = value;
		this.enabled = enabled;
		this.many = many;
	}

	@Test
	public void nativeBooleanActivationAndRetainedStatusTransitions()
			throws Exception {
		NativeUntrackedEnvironmentSupport.prepare(db, many);
		NativeUntrackedEnvironmentSupport.assertNative(db, value, enabled);
		String previous = mockSystemReader
				.getenv("GIT_DISABLE_UNTRACKED_CACHE");
		mockSystemReader.setProperty("GIT_DISABLE_UNTRACKED_CACHE", value);
		try (Git git = Git.wrap(db)) {
			Set<String> expected = new HashSet<>(
					Set.of("three", "dtwo/two", "dthree/three"));
			check(git.status().call(), expected);
			check(git.status().call(), expected);
			NativeUntrackedEnvironmentSupport.assertPublication(db, enabled,
					Set.of("three", "dtwo/", "dthree/"));
			assertEquals(enabled, UntrackedDirectoryListing.create(db,
					db.readDirCache()) != null);
			mockSystemReader.setProperty("GIT_DISABLE_UNTRACKED_CACHE",
					enabled ? "1" : "0");
			var directory = db.getWorkTree().toPath().resolve("dthree");
			var time = Files.getLastModifiedTime(directory);
			NativeUntrackedNormalSupport.write(db, "dthree/later", "later\n");
			Files.setLastModifiedTime(directory, time);
			expected.add("dthree/later");
			check(git.status().call(), expected);
			check(git.status().call(), expected);
			if (!enabled)
				NativeUntrackedEnvironmentSupport.assertPublication(db, true,
						Set.of("three", "dtwo/", "dthree/"));
		} finally {
			mockSystemReader.setProperty("GIT_DISABLE_UNTRACKED_CACHE",
					previous);
		}
	}

	private static void check(Status status, Set<String> expected) {
		assertEquals(expected, status.getUntracked());
		assertTrue(status.getAdded().isEmpty());
		assertTrue(status.getChanged().isEmpty());
		assertTrue(status.getRemoved().isEmpty());
		assertTrue(status.getModified().isEmpty());
		assertTrue(status.getMissing().isEmpty());
		assertTrue(status.getIgnoredNotInIndex().isEmpty());
		assertTrue(status.getConflicting().isEmpty());
	}

	@Test
	public void environmentPolicyAppliesToRetainedMonitorResults()
			throws Exception {
		NativeUntrackedEnvironmentSupport.prepare(db, many);
		NativeUntrackedEnvironmentSupport.enableQuietMonitor(db);
		String previous = mockSystemReader.getenv("GIT_DISABLE_UNTRACKED_CACHE");
		try (Git git = Git.wrap(db)) {
			mockSystemReader.setProperty("GIT_DISABLE_UNTRACKED_CACHE", "0");
			git.status().setUpdateUntrackedCache(false).call();
			Status warm = git.status().setUpdateUntrackedCache(false).call();
			assertSame(warm, git.status().setUpdateUntrackedCache(false).call());
			mockSystemReader.setProperty("GIT_DISABLE_UNTRACKED_CACHE", value);
			Status current = git.status().setUpdateUntrackedCache(false).call();
			if (enabled) {
				assertSame(warm, current);
			} else {
				assertNotSame(warm, current);
			}
			mockSystemReader.setProperty("GIT_DISABLE_UNTRACKED_CACHE", "1");
			NativeUntrackedNormalSupport.write(db, "dthree/later", "later\n");
			Set<String> expected = new HashSet<>(
					Set.of("three", "dtwo/two", "dthree/three", "dthree/later"));
			check(git.status().setUpdateUntrackedCache(false).call(), expected);
			assertEquals(expected, NativeUntrackedNormalSupport.nativeStatus(db, "all", false));
			Files.delete(db.getWorkTree().toPath().resolve("dthree/three"));
			expected.remove("dthree/three");
			check(git.status().setUpdateUntrackedCache(false).call(), expected);
			assertEquals(expected, NativeUntrackedNormalSupport.nativeStatus(db, "all", false));
		} finally {
			mockSystemReader.setProperty("GIT_DISABLE_UNTRACKED_CACHE", previous);
		}
	}
}
