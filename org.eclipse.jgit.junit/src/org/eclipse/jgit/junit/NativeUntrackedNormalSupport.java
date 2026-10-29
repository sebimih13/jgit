/*
 * Copyright (C) 2026, and others
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.junit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.FS_POSIX;

/** Shared native t7063 normal-mode publication fixtures. @since 7.8 */
public final class NativeUntrackedNormalSupport {
	private NativeUntrackedNormalSupport() {
	}

	/**
	 * Prepare t7063's tracked/untracked directory layout, with committed
	 * tracked files.
	 * 
	 * @param repository
	 *            disposable repository
	 * @param mode
	 *            configured mode, or null for the native default
	 * @throws Exception
	 *             on fixture failure
	 * @since 7.8
	 */
	public static void prepare(Repository repository, String mode)
			throws Exception {
		assumeTrue(repository.getFS().getClass() == FS_POSIX.class);
		var config = repository.getConfig();
		config.setBoolean("core", null, "untrackedCache", true);
		config.setBoolean("core", null, "fsmonitor", false);
		if (mode == null)
			config.unset("status", null, "showUntrackedFiles");
		else
			config.setString("status", null, "showUntrackedFiles", mode);
		Path global = repository.getDirectory().toPath()
				.resolve("normal-excludes");
		Files.writeString(global, "");
		config.setString("core", null, "excludesFile", global.toString());
		config.save();
		try (Git git = Git.wrap(repository)) {
			for (String name : Set.of("one", "two", "done/one")) {
				write(repository, name, "base\n");
				git.add().addFilepattern(name).call();
			}
			git.commit().setAuthor("Cache Test", "cache@example.invalid")
					.setCommitter("Cache Test", "cache@example.invalid")
					.setMessage("base").call();
		}
		for (String name : Set.of("three", "dtwo/two", "dthree/three"))
			write(repository, name, "new\n");
		ageDirectories(repository);
	}

	/**
	 * Enable an initially unpersisted, quiet monitor and the result cache.
	 * 
	 * @param repository
	 *            fixture repository
	 * @throws Exception
	 *             on fixture failure
	 * @since 7.8
	 */
	public static void enableMonitor(Repository repository) throws Exception {
		Path hook = repository.getDirectory().toPath()
				.resolve("hooks/normal-monitor");
		Files.createDirectories(hook.getParent());
		Files.writeString(hook, "#!/bin/sh\nprintf 'normal-clock\\000'\n");
		assertTrue(hook.toFile().setExecutable(true));
		repository.getConfig().setString("core", null, "fsmonitor",
				hook.toString());
		repository.getConfig().setInt("core", null, "fsmonitorHookVersion", 2);
		repository.getConfig().setBoolean("jgit", null, "fsmonitorCache", true);
		repository.getConfig().save();
	}

	/**
	 * @param repository
	 *            fixture repository
	 * @param path
	 *            relative path
	 * @param text
	 *            file contents
	 * @throws Exception
	 *             on filesystem failure
	 * @since 7.8
	 */
	public static void write(Repository repository, String path, String text)
			throws Exception {
		Path file = repository.getWorkTree().toPath().resolve(path);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
	}

	/**
	 * Move directory mtimes outside the index's racy window after fixture
	 * mutations.
	 * 
	 * @param repository
	 *            fixture repository
	 * @throws Exception
	 *             on filesystem failure
	 * @since 7.8
	 */
	public static void ageDirectories(Repository repository) throws Exception {
		try (var paths = Files.walk(repository.getWorkTree().toPath())) {
			for (Path path : paths.filter(Files::isDirectory).filter(
					p -> !p.startsWith(repository.getDirectory().toPath()))
					.toList())
				Files.setLastModifiedTime(path, FileTime
						.fromMillis(System.currentTimeMillis() - 60000));
		}
	}

	/**
	 * Assert normal-format summaries and native reuse without touching the real
	 * index.
	 * 
	 * @param repository
	 *            fixture repository
	 * @param expected
	 *            native normal-mode untracked paths (directory summaries end in
	 *            '/')
	 * @throws Exception
	 *             on oracle failure
	 * @since 7.8
	 */
	public static void assertReusable(Repository repository,
			Set<String> expected) throws Exception {
		var cache = repository.readDirCache().getUntrackedCache();
		assertEquals(6, cache.dirFlags);
		assertTrue(
				cache.root != null && cache.root.valid && cache.root.recurse);
		assertEquals(expected, nativeStatus(repository, "normal", true));
	}

	/**
	 * Native NUL-safe semantic oracle with an optional zero-opendir assertion.
	 * 
	 * @param repository
	 *            fixture repository
	 * @param mode
	 *            normal or all
	 * @param reuse
	 *            whether all directory records must be reused
	 * @return untracked paths
	 * @throws Exception
	 *             on oracle failure
	 * @since 7.8
	 */
	public static Set<String> nativeStatus(Repository repository, String mode,
			boolean reuse) throws Exception {
		Path trace = repository.getDirectory().toPath()
				.resolve("normal-publication.perf");
		Path output = repository.getDirectory().toPath()
				.resolve("normal-publication.z");
		Path error = repository.getDirectory().toPath()
				.resolve("normal-publication.err");
		Files.deleteIfExists(trace);
		byte[] original = Files
				.readAllBytes(repository.getIndexFile().toPath());
		var builder = new ProcessBuilder(
				System.getProperty("jgit.test.gitExecutable", "git"),
				"--no-optional-locks", "-c", "core.fsmonitor=false", "status",
				"--porcelain=v1", "-z", "--untracked-files=" + mode,
				"--no-renames").directory(repository.getWorkTree())
				.redirectOutput(output.toFile()).redirectError(error.toFile());
		builder.environment().keySet()
				.removeIf(name -> name.startsWith("GIT_"));
		builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
		builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
		builder.environment().put("GIT_TRACE2_PERF", trace.toString());
		Process child = builder.start();
		try {
			assertTrue(child.waitFor(20, TimeUnit.SECONDS));
			assertEquals(Files.readString(error), 0, child.exitValue());
		} finally {
			child.destroyForcibly();
		}
		assertArrayEquals(original,
				Files.readAllBytes(repository.getIndexFile().toPath()));
		if (reuse) {
			var matcher = Pattern.compile("(?m)\\|\\s*\\.*opendir:(\\d+)\\s*$")
					.matcher(Files.readString(trace));
			assertTrue(
					"Native UNTR counter missing: " + Files.readString(trace),
					matcher.find());
			assertEquals("Native must reuse Java-published records", 0,
					Integer.parseInt(matcher.group(1)));
		}
		Set<String> paths = new HashSet<>();
		for (String record : new String(Files.readAllBytes(output),
				StandardCharsets.UTF_8).split("\0")) {
			if (!record.isEmpty()) {
				assertTrue(record, record.startsWith("?? "));
				assertTrue("Duplicate native path: " + record,
						paths.add(record.substring(3)));
			}
		}
		return paths;
	}
}
