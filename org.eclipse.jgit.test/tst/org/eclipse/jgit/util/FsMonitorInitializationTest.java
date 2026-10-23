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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.junit.NativeFsMonitorIndexSupport;
import org.eclipse.jgit.junit.NativeFsMonitorWorkloadSupport;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.junit.Test;

/** Native t7519 initialization and newly-added validity scenarios. */
public class FsMonitorInitializationTest extends RepositoryTestCase {

	@Test
	public void configuredWriterInitializesTokenAndInvalidatesPriorEntries()
			throws Exception {
		for (String response : List.of("paths", "root", "failure")) {
			NativeFsMonitorIndexSupport.prepareWithoutExtension(db);
			if (response.equals("root")) {
				NativeFsMonitorIndexSupport.report(db, "/");
			} else if (response.equals("failure")) {
				Files.writeString(Path.of(
						db.getConfig().getString("core", null, "fsmonitor")),
						"#!/bin/sh\nexit 1\n");
			}
			var expected = NativeFsMonitorIndexSupport.mutate(db,
					"update-index", "--force-write-index");
			var index = db.lockDirCache();
			try {
				assertNotNull("Initialization must precede index mutation",
						index.getFsMonitorCache());
				for (int i = 0; i < index.getEntryCount(); i++) {
					assertTrue(index.getFsMonitorCache().isDirty(i));
				}
				index.write();
				assertTrue(index.commit());
			} finally {
				index.unlock();
			}
			assertArrayEquals(response, expected.payload(),
					NativeFsMonitorIndexSupport.extension(
							Files.readAllBytes(db.getIndexFile().toPath())));
			NativeFsMonitorWorkloadSupport.assertStagedMatches(db,
					expected.index());
			assertNotNull(db.readDirCache().getFsMonitorCache());
		}
	}

	@Test
	public void firstStagingInitializesMonitorAndMarksNewRegularFileValid()
			throws Exception {
		checkFirstAdd(false);
	}

	@Test
	public void firstStagingWithMissingIndexMatchesNative() throws Exception {
		checkFirstAdd(true);
	}

	private void checkFirstAdd(boolean missingIndex) throws Exception {
		NativeFsMonitorIndexSupport.prepareWithoutExtension(db);
		if (missingIndex) {
			Files.delete(db.getIndexFile().toPath());
		}
		writeTrashFile("new", "base\n");
		var expected = NativeFsMonitorIndexSupport.mutate(db, "add", "new");
		try (Git git = Git.wrap(db)) {
			git.add().addFilepattern("new").call();
		}
		var actual = db.readDirCache();
		assertNotNull(actual.getFsMonitorCache());
		assertTrue(actual.isFsMonitorValid(actual.findEntry("new")));
		assertEquals(missingIndex ? 1 : 4, actual.getEntryCount());
		assertArrayEquals(expected.payload(), NativeFsMonitorIndexSupport
				.extension(Files.readAllBytes(db.getIndexFile().toPath())));
		NativeFsMonitorWorkloadSupport.assertStagedMatches(db,
				expected.index());
	}

	@Test
	public void versionOneAndAutoInitializationUseNumericBoundaries()
			throws Exception {
		for (boolean explicit : new boolean[] { true, false }) {
			NativeFsMonitorIndexSupport.prepareWithoutExtension(db);
			if (explicit) {
				db.getConfig().setInt("core", null, "fsmonitorHookVersion", 1);
			} else {
				db.getConfig().unset("core", null, "fsmonitorHookVersion");
			}
			db.getConfig().save();
			Path arguments = db.getDirectory().toPath()
					.resolve("init-arguments");
			Files.deleteIfExists(arguments);
			Files.writeString(
					Path.of(db.getConfig().getString("core", null,
							"fsmonitor")),
					"#!/bin/sh\nprintf '%s:%s\\n' \"$1\" \"$2\" >> .git/init-arguments\n[ \"$1\" = 1 ] || exit 1\n");
			var expected = NativeFsMonitorIndexSupport.mutate(db,
					"update-index", "--force-write-index");
			List<String> nativeCalls = Files.readAllLines(arguments);
			Files.delete(arguments);
			long before = System.currentTimeMillis() * 1_000_000L;
			var index = db.lockDirCache();
			try {
				assertNotNull(index.getFsMonitorCache());
				String token = new String(index.getFsMonitorCache().getToken(),
						StandardCharsets.US_ASCII);
				assertTrue(token.matches("[0-9]+"));
				long value = Long.parseUnsignedLong(token);
				assertTrue(Long.compareUnsigned(value, before) >= 0);
				assertTrue(Long.compareUnsigned(value,
						(System.currentTimeMillis() + 1) * 1_000_000L) < 0);
				index.write();
				assertTrue(index.commit());
			} finally {
				index.unlock();
			}
			List<String> calls = Files.readAllLines(arguments);
			assertEquals(explicit ? 1 : 2, calls.size());
			assertEquals(nativeCalls.size(), calls.size());
			for (int i = 0; i < calls.size(); i++) {
				assertEquals(nativeCalls.get(i).split(":")[0],
						calls.get(i).split(":")[0]);
				assertTrue(calls.get(i).matches("[12]:[0-9]+"));
			}
			byte[] actual = NativeFsMonitorIndexSupport
					.extension(Files.readAllBytes(db.getIndexFile().toPath()));
			assertArrayEquals(bitmap(expected.payload()), bitmap(actual));
		}
	}

	private static byte[] bitmap(byte[] payload) {
		int end = 4;
		while (payload[end++] != 0) {
			// Independent v1 clocks differ; compare raw native bitmap bytes.
		}
		return Arrays.copyOfRange(payload, end, payload.length);
	}

	@Test
	public void rawReadsDoNotQueryProviderOrCreateIndexStorage()
			throws Exception {
		NativeFsMonitorIndexSupport.prepareWithoutExtension(db);
		byte[] original = Files.readAllBytes(db.getIndexFile().toPath());
		Path marker = db.getDirectory().toPath()
				.resolve("initialization-query");
		Files.writeString(
				Path.of(db.getConfig().getString("core", null, "fsmonitor")),
				"#!/bin/sh\n: > .git/initialization-query\nprintf 'native-clock\\0'\n");
		assertNull(db.readDirCache().getFsMonitorCache());
		assertTrue(Files.notExists(marker));
		assertArrayEquals(original,
				Files.readAllBytes(db.getIndexFile().toPath()));
	}
}
