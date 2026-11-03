/*
 * Copyright (C) 2026, and others
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.junit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.Repository;

/**
 * Native dir.c git_env_bool controls for the t7063 cache fixture. @since 7.8
 */
public final class NativeUntrackedEnvironmentSupport {
	private NativeUntrackedEnvironmentSupport() {
	}

	/**
	 * @return environment value, cache-enabled expectation and manyFiles policy
	 * @since 7.8
	 */
	public static List<Object[]> scenarios() {
		List<Object[]> rows = new ArrayList<>();
		Object[][] values = { { null, true }, { "", true }, { "0", true },
				{ "false", true }, { "FALSE", true }, { "no", true },
				{ "off", true }, { "00", true }, { "0x0", true },
				{ "-0", true }, { "0k", true }, { " 0", true }, { "1", false },
				{ "true", false }, { "TRUE", false }, { "yes", false },
				{ "on", false }, { "-1", false }, { "2", false },
				{ "0x10", false }, { "1k", false }, { "01", false } };
		for (Object[] value : values)
			for (boolean many : new boolean[] { false, true })
				rows.add(new Object[] { value[0], value[1], many });
		return rows;
	}

	/**
	 * @param repository
	 *            disposable fixture
	 * @param many
	 *            use feature.manyFiles instead of explicit core.untrackedCache
	 * @throws Exception
	 *             on setup failure
	 * @since 7.8
	 */
	public static void prepare(Repository repository, boolean many)
			throws Exception {
		NativeUntrackedNormalSupport.prepare(repository, "normal");
		repository.getConfig().setBoolean("feature", null, "manyFiles", many);
		repository.getConfig().setBoolean("core", null, "preloadIndex", true);
		if (many)
			repository.getConfig().unset("core", null, "untrackedCache");
		repository.getConfig().save();
		var nativeIndex = NativeFsMonitorIndexSupport.mutate(repository, "-c",
				"core.untrackedCache=false", "update-index",
				"--no-untracked-cache");
		Files.copy(nativeIndex.index(), repository.getIndexFile().toPath(),
				StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.COPY_ATTRIBUTES);
		assertNull(repository.readDirCache().getUntrackedCache());
	}

	/**
	 * Install a quiet v2 provider to exercise whole-result cache eligibility.
	 * Mutations made while untracked caching is disabled must still be found.
	 *
	 * @param repository disposable fixture
	 * @throws Exception on hook setup failure
	 * @since 7.8
	 */
	public static void enableQuietMonitor(Repository repository) throws Exception {
		org.junit.Assume.assumeFalse(
				org.eclipse.jgit.util.SystemReader.getInstance().isWindows());
		Path hook = repository.getDirectory().toPath().resolve("quiet-monitor");
		Files.writeString(hook,
				"#!/bin/sh\n[ \"$1\" = 2 ] || exit 2\nprintf 'environment-quiet\\000'\n");
		assertTrue(hook.toFile().setExecutable(true));
		repository.getConfig().setString("core", null, "fsmonitor", hook.toString());
		repository.getConfig().setInt("core", null, "fsmonitorHookVersion", 2);
		repository.getConfig().setBoolean("jgit", null, "fsmonitorCache", true);
		repository.getConfig().save();
	}

	/**
	 * Verify native cache activation and complete normal-mode NUL records using
	 * an independent writable index; the Java fixture index stays untouched.
	 * 
	 * @param repository
	 *            fixture repository
	 * @param value
	 *            environment value, or null when unset
	 * @param enabled
	 *            expected native populated cache
	 * @throws Exception
	 *             on native failure or behavioral mismatch
	 * @since 7.8
	 */
	public static void assertNative(Repository repository, String value,
			boolean enabled) throws Exception {
		Path directory = repository.getDirectory().toPath();
		Path index = directory.resolve("native-environment.index");
		Path output = directory.resolve("native-environment.z");
		Path error = directory.resolve("native-environment.err");
		byte[] original = Files
				.readAllBytes(repository.getIndexFile().toPath());
		Files.copy(repository.getIndexFile().toPath(), index,
				StandardCopyOption.REPLACE_EXISTING);
		var builder = new ProcessBuilder(
				System.getProperty("jgit.test.gitExecutable", "git"), "-c",
				"core.fsmonitor=false", "status", "--porcelain=v1", "-z",
				"--untracked-files=normal", "--no-renames")
				.directory(repository.getWorkTree())
				.redirectOutput(output.toFile()).redirectError(error.toFile());
		builder.environment().keySet()
				.removeIf(name -> name.startsWith("GIT_"));
		builder.environment().put("GIT_INDEX_FILE", index.toString());
		builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
		builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
		if (value != null)
			builder.environment().put("GIT_DISABLE_UNTRACKED_CACHE", value);
		Process child = builder.start();
		try {
			assertTrue(child.waitFor(20, TimeUnit.SECONDS));
			assertEquals(Files.readString(error), 0, child.exitValue());
		} finally {
			child.destroyForcibly();
		}
		Set<String> actual = new HashSet<>();
		for (String record : Files.readString(output, StandardCharsets.UTF_8)
				.split("\0"))
			if (!record.isEmpty())
				assertTrue(actual.add(record));
		assertEquals(Set.of("?? three", "?? dtwo/", "?? dthree/"), actual);
		var cache = DirCache.read(index.toFile(), repository.getFS())
				.getUntrackedCache();
		assertNotNull(cache);
		assertEquals(enabled, cache.root != null && cache.root.valid);
		assertArrayEquals(original,
				Files.readAllBytes(repository.getIndexFile().toPath()));
	}

	/**
	 * @param repository
	 *            fixture repository
	 * @param enabled
	 *            whether publication and reuse are allowed
	 * @param normal
	 *            expected native normal directory summaries
	 * @throws Exception
	 *             on cache mismatch
	 * @since 7.8
	 */
	public static void assertPublication(Repository repository, boolean enabled,
			Set<String> normal) throws Exception {
		if (enabled)
			NativeUntrackedNormalSupport.assertReusable(repository, normal);
		else
			assertNull(repository.readDirCache().getUntrackedCache());
	}
}
