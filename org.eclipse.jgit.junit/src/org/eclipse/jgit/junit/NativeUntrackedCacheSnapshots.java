/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.junit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.internal.storage.file.UntrackedCache;
import org.eclipse.jgit.internal.storage.file.UntrackedCache.Directory;
import org.eclipse.jgit.lib.Repository;

/**
 * Native t7063 index snapshots and original dump assertions. These test fixtures
 * validate storage interoperability, not a native directory traversal strategy.
 *
 * @since 7.8
 */
public final class NativeUntrackedCacheSnapshots {

	private NativeUntrackedCacheSnapshots() {
		// Test support only.
	}

	/** @return original t7063 checkpoint numbers */
	public static List<Integer> checkpoints() {
		return List.of(3, 5, 7, 9, 11, 16, 18, 20, 21, 23, 24, 26, 29, 33, 37,
				41, 43, 44, 45, 46, 47, 49, 50, 56);
	}

	/**
	 * Install the native index into an otherwise disposable repository and assert
	 * its complete stored cache dump and raw payload.
	 *
	 * @param repository disposable fixture repository
	 * @param checkpoint original t7063 checkpoint number
	 * @return the installed index bytes
	 * @throws Exception if a fixture cannot be loaded or decoded
	 */
	public static byte[] install(Repository repository, int checkpoint)
			throws Exception {
		byte[] index = Base64.getDecoder().decode(fixture(checkpoint)[0]);
		Files.write(repository.getIndexFile().toPath(), index);
		assertStored(repository, checkpoint);
		return index;
	}

	/**
	 * Assert both the original native dump and byte-for-byte payload preservation.
	 *
	 * @param repository fixture repository
	 * @param checkpoint original t7063 checkpoint number
	 * @throws Exception if reading the fixture or index fails
	 */
	public static void assertStored(Repository repository, int checkpoint)
			throws Exception {
		String[] expected = fixture(checkpoint);
		UntrackedCache cache = DirCache.read(repository).getUntrackedCache();
		assertEquals("t7063 checkpoint " + checkpoint, expected[2], dump(cache));
		if (!"-".equals(expected[1])) {
			assertNotNull(cache);
			assertArrayEquals("Native UNTR payload at checkpoint " + checkpoint,
					Base64.getDecoder().decode(expected[1]), cache.write());
		}
	}

	/**
	 * Compare a model mutation with an independently captured native dump.
	 * Stat and location bytes differ between captures, so this compares the
	 * complete native dump view rather than unrelated fixture identities.
	 *
	 * @param cache model after the operation under test
	 * @param checkpoint independently captured native expected state
	 * @throws IOException if the expected fixture is unavailable
	 */
	public static void assertNativeDump(UntrackedCache cache, int checkpoint)
			throws IOException {
		assertEquals("t7063 checkpoint " + checkpoint, fixture(checkpoint)[2],
				dump(cache));
	}

	private static String[] fixture(int checkpoint) throws IOException {
		String resource = "untracked-cache/t7063-" + checkpoint + ".txt";
		try (var stream = NativeUntrackedCacheSnapshots.class
				.getResourceAsStream(resource)) {
			assertNotNull("Missing native fixture " + resource, stream);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
					.split("\n", 3);
		}
	}

	private static String dump(UntrackedCache cache) {
		if (cache == null)
			return "no untracked cache\n";
		StringBuilder out = new StringBuilder("info/exclude ")
				.append(cache.infoExclude.oid.name())
				.append("\ncore.excludesfile ").append(cache.excludesFile.oid.name())
				.append("\nexclude_per_dir ")
				.append(new String(cache.excludePerDir, StandardCharsets.UTF_8))
				.append("\nflags ").append(String.format(java.util.Locale.ROOT,
						"%08x", Integer.valueOf(cache.dirFlags))).append('\n');
		if (cache.root != null)
			dump(cache.root, "", out);
		return out.toString();
	}

	// Match t/helper/test-dump-untracked-cache.c's unsigned-byte sorting and
	// public field view. Do not sort or otherwise mutate the parsed model.
	private static void dump(Directory dir, String base, StringBuilder out) {
		String path = base + new String(dir.name, StandardCharsets.UTF_8) + "/";
		out.append(path).append(' ').append(dir.excludeOid.name());
		if (dir.recurse)
			out.append(" recurse");
		if (dir.checkOnly)
			out.append(" check_only");
		if (dir.valid)
			out.append(" valid");
		out.append('\n');
		List<byte[]> names = new ArrayList<>(dir.untracked);
		names.sort(Arrays::compareUnsigned);
		for (byte[] name : names)
			out.append(new String(name, StandardCharsets.UTF_8)).append('\n');
		List<Directory> children = new ArrayList<>(dir.directories);
		children.sort((a, b) -> Arrays.compareUnsigned(a.name, b.name));
		for (Directory child : children)
			dump(child, path, out);
	}
}
