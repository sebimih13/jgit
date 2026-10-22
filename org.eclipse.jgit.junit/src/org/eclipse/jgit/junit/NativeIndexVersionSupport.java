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
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.NB;
import org.slf4j.LoggerFactory;

/**
 * Shared assertions for every t1600 index-version precedence row.
 * @since 7.8
 */
public final class NativeIndexVersionSupport {
	private NativeIndexVersionSupport() {
		// Test support only.
	}

	/**
	 * Capture library warnings using the test runtimes' SLF4J stderr binding.
	 * The capture is scoped to a synchronous operation and always restores stderr.
	 *
	 * @return diagnostic assertion scope
	 */
	public static Warnings captureWarnings() {
		return new Warnings();
	}

	/** Diagnostic assertions corresponding to native t1600's stderr checks. */
	public static final class Warnings implements AutoCloseable {
		private final PrintStream original = System.err;

		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		private final PrintStream capture = new PrintStream(bytes, true,
				StandardCharsets.UTF_8);

		private Warnings() {
			var logger = LoggerFactory.getLogger(DirCache.class);
			assertTrue("Index warning tests require a WARN-enabled SLF4J provider: "
					+ logger.getClass().getName(), logger.isWarnEnabled());
			System.setErr(capture);
		}

		/**
		 * Assert the native warning contract for the enumerated precedence rows.
		 *
		 * @param configuredVersion explicit version, or zero when absent
		 * @param environmentVersion explicit environment version, or null
		 */
		public void assertInitial(int configuredVersion,
				String environmentVersion) {
			String setting = environmentVersion == null
					? configuredVersion == 1 ? "index.version" : null
					: List.of("2bogus", "1", "", "4 ", "٢")
							.contains(environmentVersion) ? "GIT_INDEX_VERSION" : null;
			if (setting == null) {
				assertNone();
				return;
			}
			String diagnostics = bytes.toString(StandardCharsets.UTF_8);
			String message = setting
					+ " set, but the value is invalid.\nUsing version 3";
			assertTrue(diagnostics, diagnostics.contains(message));
			assertEquals(diagnostics, 1,
					diagnostics.split("set, but the value is invalid\\.", -1).length - 1);
		}

		/** Assert that an existing index or valid defaults emit no version warning. */
		public void assertNone() {
			String diagnostics = bytes.toString(StandardCharsets.UTF_8);
			assertTrue(diagnostics,
					!diagnostics.contains("set, but the value is invalid."));
			assertTrue(diagnostics, !diagnostics.contains("Using version "));
		}

		@Override
		public void close() {
			System.setErr(original);
			capture.close();
			// Preserve diagnostics in the ordinary test console as well.
			original.writeBytes(bytes.toByteArray());
			original.flush();
		}
	}

	/** @return original native precedence rows and invalid-value controls */
	public static List<Object[]> scenarios() {
		return List.of(new Object[] { 0, false, null, 2 },
				new Object[] { 2, false, null, 2 },
				new Object[] { 3, false, null, 2 },
				new Object[] { 4, false, null, 4 },
				new Object[] { 2, false, "4", 4 },
				new Object[] { 2, true, null, 2 },
				new Object[] { 0, true, null, 4 },
				new Object[] { 0, true, "2", 2 },
				new Object[] { 0, false, "2bogus", 2 },
				new Object[] { 0, false, "1", 2 },
				new Object[] { 0, false, "", 2 },
				new Object[] { 1, false, null, 2 },
				new Object[] { 1, true, null, 2 },
				new Object[] { 0, true, "2bogus", 2 },
				new Object[] { 1, true, "4", 4 },
				new Object[] { 2, false, " +4", 4 },
				new Object[] { 0, false, "4 ", 2 },
				new Object[] { 0, true, "4294967298", 2 },
				new Object[] { 0, false, "٢", 2 },
				new Object[] { 0, false, " -4294967294", 2 });
	}

	/** Configure the same repository settings for both index writers.
	 * @param repository repository
	 * @param configuredVersion explicit version, or zero for absent
	 * @param manyFiles feature default
	 * @throws Exception if configuration cannot be saved
	 */
	public static void configure(Repository repository, int configuredVersion,
			boolean manyFiles) throws Exception {
		var config = repository.getConfig();
		config.unset("index", null, "version");
		config.unset("index", null, "skipHash");
		if (configuredVersion != 0)
			config.setInt("index", null, "version", configuredVersion);
		config.setBoolean("feature", null, "manyFiles", manyFiles);
		config.save();
	}

	/** Stage a in a separate native index, without changing the Java index.
	 * @param repository repository with worktree file a
	 * @param environmentVersion explicit environment value, or null for absent
	 * @param expectedVersion original native expected version
	 * @param manyFiles whether the default checksum is omitted
	 * @return native index path
	 * @throws Exception on command or assertion failure
	 */
	public static Path nativeIndex(Repository repository, String environmentVersion,
			int expectedVersion, boolean manyFiles) throws Exception {
		return stageNative(repository, environmentVersion, expectedVersion, manyFiles, false);
	}

	/** Restage an existing native index to verify defaults are no longer consulted.
	 * @param repository repository with updated worktree file a
	 * @param expectedVersion existing index version
	 * @param manyFiles checksum policy
	 * @return native index path
	 * @throws Exception on command or assertion failure
	 */
	public static Path nativeRestage(Repository repository, int expectedVersion,
			boolean manyFiles) throws Exception {
		return stageNative(repository, "1", expectedVersion, manyFiles, true);
	}

	private static Path stageNative(Repository repository, String environmentVersion,
			int expectedVersion, boolean manyFiles, boolean existing) throws Exception {
		Path index = repository.getDirectory().toPath().resolve("native-version-index");
		Path output = repository.getDirectory().toPath().resolve("native-version.out");
		assertEquals(existing, Files.exists(index));
		ProcessBuilder builder = new ProcessBuilder(
				System.getProperty("jgit.test.gitExecutable", "git"),
				"-c", "core.fsmonitor=false", "add", "a")
				.directory(repository.getWorkTree()).redirectErrorStream(true)
				.redirectOutput(output.toFile());
		builder.environment().keySet().removeIf(n -> n.startsWith("GIT_"));
		builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
		builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
		builder.environment().put("GIT_INDEX_FILE", index.toString());
		if (environmentVersion != null)
			builder.environment().put("GIT_INDEX_VERSION", environmentVersion);
		Process process = builder.start();
		try {
			assertTrue("Native add timeout", process.waitFor(10, TimeUnit.SECONDS));
			assertEquals(Files.readString(output), 0, process.exitValue());
		} finally {
			if (process.isAlive()) {
				process.destroyForcibly();
				process.waitFor();
			}
		}
		boolean warning = !existing && (environmentVersion == null
				? repository.getConfig().getInt("index", "version", 0) == 1
				: List.of("2bogus", "1", "", "4 ", "٢").contains(environmentVersion));
		String diagnostics = Files.readString(output);
		if (warning) {
			assertTrue(diagnostics, diagnostics.contains((environmentVersion == null
					? "index.version" : "GIT_INDEX_VERSION")
					+ " set, but the value is invalid."));
			assertTrue(diagnostics, diagnostics.contains("Using version 3"));
		} else {
			assertEquals("", diagnostics);
		}
		assertEncoding(index, expectedVersion, manyFiles);
		return index;
	}

	/** Verify encoding and the staged entry against the independent native index.
	 * @param repository repository staged by the Java operation
	 * @param nativeIndex independently staged native index
	 * @param expectedVersion original native expected version
	 * @param manyFiles whether the default checksum is omitted
	 * @throws Exception if an index cannot be read
	 */
	public static void assertMatches(Repository repository, Path nativeIndex,
			int expectedVersion, boolean manyFiles) throws Exception {
		assertEncoding(repository.getIndexFile().toPath(), expectedVersion, manyFiles);
		DirCache expected = DirCache.read(nativeIndex.toFile(), repository.getFS());
		DirCache actual = repository.readDirCache();
		assertEquals(1, actual.getEntryCount());
		assertEquals(expected.getEntryCount(), actual.getEntryCount());
		assertArrayEquals(expected.getEntry(0).getRawPath(), actual.getEntry(0).getRawPath());
		assertEquals(expected.getEntry(0).getObjectId(), actual.getEntry(0).getObjectId());
		assertEquals(expected.getEntry(0).getFileMode(), actual.getEntry(0).getFileMode());
		assertEquals(0, actual.getEntry(0).getStage());
	}

	/**
	 * Port t1600's fsck acceptance assertion for a Java-written skipped hash.
	 * Native fsck must accept the objects/index without rewriting the candidate.
	 *
	 * @param repository disposable repository with the Java-written index
	 * @throws Exception if native fsck fails or modifies the index
	 */
	public static void assertNativeFsck(Repository repository) throws Exception {
		byte[] index = Files.readAllBytes(repository.getIndexFile().toPath());
		Path output = repository.getDirectory().toPath().resolve("native-index-fsck.out");
		ProcessBuilder builder = new ProcessBuilder(
				System.getProperty("jgit.test.gitExecutable", "git"),
				"-c", "core.fsmonitor=false", "fsck")
				.directory(repository.getWorkTree()).redirectErrorStream(true)
				.redirectOutput(output.toFile());
		builder.environment().keySet().removeIf(n -> n.startsWith("GIT_"));
		builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
		builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
		Process process = builder.start();
		try {
			assertTrue("Native fsck timeout", process.waitFor(10, TimeUnit.SECONDS));
			assertTrue("Bound native fsck output", Files.size(output) <= 1024 * 1024);
			assertEquals(Files.readString(output), 0, process.exitValue());
			assertArrayEquals("Native fsck must not rewrite the Java index", index,
					Files.readAllBytes(repository.getIndexFile().toPath()));
		} finally {
			if (process.isAlive()) {
				process.destroyForcibly();
				process.waitFor();
			}
		}
	}

	private static void assertEncoding(Path index, int expectedVersion,
			boolean manyFiles) throws Exception {
		byte[] bytes = Files.readAllBytes(index);
		assertEquals(expectedVersion, NB.decodeInt32(bytes, 4));
		byte[] footer = Arrays.copyOfRange(bytes, bytes.length - Constants.OBJECT_ID_LENGTH,
				bytes.length);
		assertArrayEquals(manyFiles ? new byte[Constants.OBJECT_ID_LENGTH]
				: Constants.newMessageDigest().digest(Arrays.copyOf(bytes,
						bytes.length - Constants.OBJECT_ID_LENGTH)), footer);
	}
}
