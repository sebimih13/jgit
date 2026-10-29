/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.junit.NativeFsMonitorIndexSupport;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.junit.Test;

/** Repeated status must retain dirty entries as native refresh_index() does. */
public class FsMonitorRetainedStatusTest extends RepositoryTestCase {

	@Test
	public void earlierSameStatEditSurvivesLaterNotifications() throws Exception {
		checkRepeatedStatus(true);
	}

	@Test
	public void earlierSameStatEditSurvivesWithoutUntrackedCache() throws Exception {
		checkRepeatedStatus(false);
	}

	private void checkRepeatedStatus(boolean untrackedCache) throws Exception {
		NativeFsMonitorIndexSupport.prepare(db);
		db.getConfig().setBoolean("jgit", null, "fsmonitorCache", true);
		db.getConfig().setBoolean("core", null, "untrackedCache", untrackedCache);
		db.getConfig().save();
		// This scenario isolates read-only result retention from writer refresh.
		byte[] original = Files.readAllBytes(db.getIndexFile().toPath());
		// Native Git records ctime; JGit's same-stat edit uses the saved mtime.
		TimeUnit.MILLISECONDS.sleep(1010 - System.currentTimeMillis() % 1000);
		edit("a", "next\n");
		NativeFsMonitorIndexSupport.reportToken(db, "first-clock", "a");
		try (Git git = Git.wrap(db)) {
			assertModified(git.status().setUpdateUntrackedCache(false).call(), Set.of("a"));
			edit("z", "next\n");
			NativeFsMonitorIndexSupport.reportToken(db, "second-clock", "z");
			Status second = git.status().setUpdateUntrackedCache(false).call();
			assertModified(second, Set.of("a", "z"));
			NativeFsMonitorIndexSupport.reportToken(db, "quiet-clock");
			Status quiet = git.status().setUpdateUntrackedCache(false).call();
			assertModified(quiet, Set.of("a", "z"));
			if (untrackedCache) {
				assertSame(second, quiet);
			}
			// Recheck content rather than indefinitely unioning old dirty results.
			edit("a", "base\n");
			NativeFsMonitorIndexSupport.reportToken(db, "restored-clock", "a");
			assertModified(git.status().setUpdateUntrackedCache(false).call(), Set.of("z"));
		}
		assertArrayEquals(original, Files.readAllBytes(db.getIndexFile().toPath()));
	}

	private void edit(String name, String contents) throws Exception {
		Path path = db.getWorkTree().toPath().resolve(name);
		var time = Files.getLastModifiedTime(path);
		Files.writeString(path, contents);
		Files.setLastModifiedTime(path, time);
	}

	private void assertModified(Status actual, Set<String> expected) throws Exception {
		Set<String> nativeRecords = Arrays.stream(new String(
				NativeFsMonitorIndexSupport.status(db, false), StandardCharsets.UTF_8)
				.split("\0")).filter(record -> !record.isEmpty())
				.collect(Collectors.toSet());
		assertEquals(expected.stream().map(path -> " M " + path)
				.collect(Collectors.toSet()), nativeRecords);
		assertEquals(expected, actual.getModified());
		assertEquals(Set.of(), actual.getAdded());
		assertEquals(Set.of(), actual.getChanged());
		assertEquals(Set.of(), actual.getRemoved());
		assertEquals(Set.of(), actual.getMissing());
		assertEquals(Set.of(), actual.getUntracked());
		assertEquals(Set.of(), actual.getIgnoredNotInIndex());
		assertEquals(Set.of(), actual.getConflicting());
	}
}
