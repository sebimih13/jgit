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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.SystemReader;

/**
 * Real native FSMN fixtures and independent alternate-index mutation oracles.
 */
public final class NativeFsMonitorIndexSupport {
	private NativeFsMonitorIndexSupport() {
	}

	/**
	 * Prepare a native v2 token and three entries, with m explicitly dirty.
	 * 
	 * @param repo
	 *            disposable repository
	 * @throws Exception
	 *             on fixture failure
	 */
	public static void prepare(Repository repo) throws Exception {
		assumeFalse(SystemReader.getInstance().isWindows());
		Path root = repo.getWorkTree().toPath();
		for (String name : List.of("a", "m", "z")) {
			Files.writeString(root.resolve(name), "base\n");
			Files.setLastModifiedTime(root.resolve(name),
					FileTime.fromMillis(System.currentTimeMillis() - 60_000));
		}
		try (Git git = Git.wrap(repo)) {
			git.add().addFilepattern("a").addFilepattern("m")
					.addFilepattern("z").call();
			git.commit().setMessage("base")
					.setAuthor("Cache Test", "cache@example.invalid")
					.setCommitter("Cache Test", "cache@example.invalid").call();
		}
		Path hook = repo.getDirectory().toPath()
				.resolve("hooks/fsmonitor-index");
		Files.createDirectories(hook.getParent());
		Files.writeString(hook,
				"#!/bin/sh\ncat .git/fsmonitor-index.response\n");
		report(repo);
		assertTrue(hook.toFile().setExecutable(true));
		var config = repo.getConfig();
		config.setString("core", null, "fsmonitor", hook.toString());
		config.setInt("core", null, "fsmonitorHookVersion", 2);
		config.setBoolean("core", null, "untrackedCache", false);
		config.setBoolean("feature", null, "manyFiles", false);
		config.setInt("index", null, "version", 2);
		config.setBoolean("jgit", null, "fsmonitorCache", false);
		config.save();
		run(repo, null, "update-index", "--index-version=2", "--fsmonitor",
				"--force-write-index");
		run(repo, null, "update-index", "--fsmonitor-valid", "a", "m", "z");
		run(repo, null, "update-index", "--no-fsmonitor-valid", "m");
		assertNotNull("Native FSMN must exist",
				extension(Files.readAllBytes(repo.getIndexFile().toPath())));
	}

	/**
	 * Report real fixture mutations to the native provider before its next
	 * query.
	 * 
	 * @param repo
	 *            fixture repository
	 * @param paths
	 *            changed repository-relative paths
	 * @throws Exception
	 *             on fixture I/O failure
	 */
	public static void report(Repository repo, String... paths)
			throws Exception {
		StringBuilder response = new StringBuilder("native-clock\0");
		for (String path : paths)
			response.append(path).append('\0');
		Files.write(
				repo.getDirectory().toPath()
						.resolve("fsmonitor-index.response"),
				response.toString()
						.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	/**
	 * Alternate-index native result.
	 * 
	 * @param index
	 *            native alternate index path
	 * @param payload
	 *            raw FSMN payload, or null
	 */
	public record Expected(Path index, byte[] payload) {
	}

	/**
	 * Run a native mutation against a separate index; source index is
	 * read-only.
	 * 
	 * @param repo
	 *            fixture
	 * @param args
	 *            native arguments
	 * @return independent native index and raw FSMN, possibly null
	 * @throws Exception
	 *             on command failure
	 */
	public static Expected mutate(Repository repo, String... args)
			throws Exception {
		Path index = repo.getDirectory().toPath()
				.resolve("native-fsmn-oracle.index");
		Files.copy(repo.getIndexFile().toPath(), index,
				StandardCopyOption.REPLACE_EXISTING);
		run(repo, index, args);
		return new Expected(index, extension(Files.readAllBytes(index)));
	}

	/**
	 * Extract raw FSMN without using the model parser/serializer under test.
	 * 
	 * @param index
	 *            complete SHA-1 index
	 * @return raw payload or null
	 */
	public static byte[] extension(byte[] index) {
		ByteBuffer data = ByteBuffer.wrap(index);
		assertEquals(0x44495243, data.getInt(0));
		int version = data.getInt(4);
		assertTrue(version >= 2 && version <= 4);
		int entries = data.getInt(8);
		int offset = 12;
		for (int i = 0; i < entries; i++) {
			int start = offset;
			int flags = Short.toUnsignedInt(data.getShort(offset + 60));
			offset += (flags & 0x4000) != 0 ? 64 : 62;
			if (version == 4) {
				while ((index[offset++] & 0x80) != 0) {
					// Consume the bounded fixture's path-prefix varint.
				}
			}
			while (index[offset++] != 0) {
				// Skip raw pathname bytes, preserving whitespace/newlines.
			}
			if (version != 4) {
				offset = start + ((offset - start + 7) & ~7);
			}
		}
		while (offset + 8 <= index.length - 20) {
			int signature = data.getInt(offset);
			int length = data.getInt(offset + 4);
			assertTrue(length >= 0
					&& (long) offset + 8 + length <= index.length - 20);
			if (signature == 0x46534d4e) {
				return Arrays.copyOfRange(index, offset + 8,
						offset + 8 + length);
			}
			offset += 8 + length;
		}
		return null;
	}

	private static void run(Repository repo, Path index, String... args)
			throws Exception {
		List<String> command = new ArrayList<>();
		command.add(System.getProperty("jgit.test.gitExecutable", "git"));
		command.addAll(Arrays.asList(args));
		ProcessBuilder builder = new ProcessBuilder(command)
				.directory(repo.getWorkTree());
		builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
		builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
		builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
		if (index != null) {
			builder.environment().put("GIT_INDEX_FILE", index.toString());
			Path objects = repo.getDirectory().toPath()
					.resolve("native-fsmn-oracle.objects");
			Files.createDirectories(objects);
			builder.environment().put("GIT_OBJECT_DIRECTORY",
					objects.toString());
			builder.environment().put("GIT_ALTERNATE_OBJECT_DIRECTORIES",
					repo.getCommonDirectory().toPath().resolve("objects")
							.toString());
		}
		Path output = repo.getDirectory().toPath().resolve("native-fsmn.out");
		Path error = repo.getDirectory().toPath().resolve("native-fsmn.err");
		builder.redirectOutput(output.toFile()).redirectError(error.toFile());
		Process child = builder.start();
		try {
			assertTrue("Native FSMN command must finish",
					child.waitFor(10, TimeUnit.SECONDS));
			assertEquals(Files.readString(error), 0, child.exitValue());
		} finally {
			child.destroyForcibly();
		}
	}
}
