/*
 * Copyright (C) 2026, and others
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.junit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

/**
 * Correctness oracles for p7519's command workloads, without timing assertions.
 *
 * @since 7.8
 */
public final class NativeFsMonitorWorkloadSupport {
	/**
	 * Boolean and fallback forms accepted by native repo-settings.c/parse.c.
	 */
	public static final List<String> UNTRACKED_SETTINGS = java.util.Arrays
			.asList(null, "keep", "unexpected", "true", "false", "YES", "off",
					"", "2", "-1", "+2", "0x10", "010", "08", "1k", "0g", "2g",
					"-2147483648", "2147483647", "2147483648", " 1", "1 ", " 0",
					" ", "1 k");

	/**
	 * Prepare an independently written native index, install its initial state
	 * in the Java fixture, and ask C Git whether the requested policy retains
	 * UNTR. Neither native command uses the Java index as its output.
	 *
	 * @param repository
	 *            disposable fixture
	 * @param manyFiles
	 *            feature default
	 * @param setting
	 *            explicit setting, or null for the default
	 * @param existing
	 *            whether the initial index contains UNTR
	 * @return native extension presence after applying the configuration
	 * @throws Exception
	 *             on native command or index decoding failure
	 */
	public static boolean nativeUntrackedCachePolicy(Repository repository,
			boolean manyFiles, String setting, boolean existing)
			throws Exception {
		Path index = repository.getDirectory().toPath()
				.resolve("native-untracked-policy.index");
		Files.deleteIfExists(index);
		nativeGit(repository, index, true, "-c",
				"core.untrackedCache=" + existing, "update-index",
				"--index-version=2");
		Files.copy(index, repository.getIndexFile().toPath(),
				StandardCopyOption.REPLACE_EXISTING);
		return nativeUntrackedCacheRewrite(repository, manyFiles, setting);
	}

	/**
	 * Apply a native policy to a private copy of the current Java fixture index.
	 *
	 * @param repository disposable fixture
	 * @param manyFiles feature default
	 * @param setting explicit policy, or null for its default
	 * @return native extension presence
	 * @throws Exception on native or decoding failure
	 */
	public static boolean nativeUntrackedCacheRewrite(Repository repository,
			boolean manyFiles, String setting) throws Exception {
		return nativeUntrackedCacheRewrite(repository,
				Boolean.toString(manyFiles), setting);
	}

	/**
	 * Apply a native policy with a raw feature boolean for parser parity tests.
	 *
	 * @param repository disposable fixture
	 * @param manyFiles native feature.manyFiles boolean spelling
	 * @param setting explicit untracked policy, or null for its default
	 * @return native extension presence
	 * @throws Exception on native or decoding failure
	 */
	public static boolean nativeUntrackedCacheRewrite(Repository repository,
			String manyFiles, String setting) throws Exception {
		Path index = repository.getDirectory().toPath()
				.resolve("native-untracked-policy.index");
		Files.copy(repository.getIndexFile().toPath(), index,
				StandardCopyOption.REPLACE_EXISTING);
		// An absent value and 'keep' both leave the feature default intact.
		// Override the runner's defensive core.untrackedCache=false setting.
		// Force a write: changing only skipHash does not otherwise mark the
		// native index dirty, while the Java operation explicitly writes it.
		nativeGit(repository, index, true, "-c",
				"feature.manyFiles=" + manyFiles, "-c",
				"core.untrackedCache=" + (setting == null ? "keep" : setting),
				"update-index", "--index-version=2", "--force-write-index");
		return DirCache.read(index.toFile(), repository.getFS())
				.getUntrackedCache() != null;
	}

	/**
	 * Compare the complete stored UNTR payload after a Java index write with
	 * the independent native policy oracle's output.
	 *
	 * @param repository disposable fixture used by nativeUntrackedCachePolicy
	 * @throws Exception on an index or assertion failure
	 */
	public static void assertNativeUntrackedCachePolicy(Repository repository)
			throws Exception {
		DirCache expected = DirCache.read(repository.getDirectory().toPath()
				.resolve("native-untracked-policy.index").toFile(), repository.getFS());
		DirCache actual = DirCache.read(repository.getIndexFile(), repository.getFS());
		assertEquals("Native UNTR extension presence",
				expected.getUntrackedCache() != null, actual.getUntrackedCache() != null);
		if (expected.getUntrackedCache() != null) {
			assertArrayEquals("Complete native UNTR policy payload",
					storedPolicyPayload(repository.getDirectory().toPath()
							.resolve("native-untracked-policy.index")),
					storedPolicyPayload(repository.getIndexFile().toPath()));
		}
		if (expected.getEntryCount() == 0 && actual.getEntryCount() == 0) {
			assertArrayEquals("Complete empty native index including footer",
					Files.readAllBytes(repository.getDirectory().toPath()
							.resolve("native-untracked-policy.index")),
					Files.readAllBytes(repository.getIndexFile().toPath()));
		}
	}

	private static byte[] storedPolicyPayload(Path index) throws Exception {
		byte[] raw = Files.readAllBytes(index);
		ByteBuffer data = ByteBuffer.wrap(raw);
		// The policy oracle explicitly selects v2. Decode only this fixture
		// layout, so the assertion compares stored bytes rather than passing
		// both payloads through the model serializer under test.
		assertEquals("Policy fixture index version", 2, data.getInt(4));
		int offset = 12;
		for (int i = 0; i < data.getInt(8); i++) {
			int start = offset;
			offset += 62;
			while (raw[offset++] != 0) {
				// Skip the NUL-terminated pathname and then its v2 padding.
			}
			offset = start + ((offset - start + 7) & ~7);
		}
		while (offset < raw.length - 20) {
			int signature = data.getInt(offset);
			int size = data.getInt(offset + 4);
			offset += 8;
			assertTrue("Bound policy fixture extension", size >= 0
					&& size <= raw.length - 20 - offset);
			if (signature == 0x554e5452) { // UNTR
				return Arrays.copyOfRange(raw, offset, offset + size);
			}
			offset += size;
		}
		throw new AssertionError("Expected stored UNTR payload");
	}

	/**
	 * Run a native index mutation on a private copy, retaining the imported
	 * cache identity and traversal flags for byte comparisons.
	 *
	 * @param repository disposable fixture
	 * @param input input for --index-info, or null
	 * @param arguments update-index options
	 * @throws Exception on a command or index failure
	 */
	public static void nativeUntrackedIndexMutation(Repository repository,
			byte[] input, String... arguments) throws Exception {
		Path index = repository.getDirectory().toPath()
				.resolve("native-untracked-policy.index");
		Files.copy(repository.getIndexFile().toPath(), index,
				StandardCopyOption.REPLACE_EXISTING);
		List<String> options = new ArrayList<>(List.of("-c",
				"feature.manyFiles=false", "-c", "core.untrackedCache=keep",
				"update-index", "--force-write-index"));
		options.addAll(List.of(arguments));
		nativeGit(repository, index, true, input, options.toArray(String[]::new));
	}

	/**
	 * Run the native index-only unpack-trees operation used to compare checkout
	 * invalidation without changing the Java fixture's worktree or HEAD.
	 *
	 * @param repository disposable fixture
	 * @param tree target tree object ID
	 * @throws Exception on native failure
	 */
	public static void nativeUntrackedReadTree(Repository repository, ObjectId tree)
			throws Exception {
		Path index = repository.getDirectory().toPath().resolve("native-untracked-policy.index");
		Files.copy(repository.getIndexFile().toPath(), index, StandardCopyOption.REPLACE_EXISTING);
		nativeGit(repository, index, true, "-c", "feature.manyFiles=false", "-c",
				"core.untrackedCache=keep", "read-tree", "--reset", tree.name());
	}

	/**
	 * Verify that native checkout rejects the dirty fixture before changing HEAD.
	 *
	 * @param repository disposable fixture with local modifications
	 * @param target target commit
	 * @throws Exception on an unexpected native result
	 */
	public static void nativeUntrackedCheckoutRejected(Repository repository, ObjectId target)
			throws Exception {
		Path index = repository.getDirectory().toPath().resolve("native-untracked-policy.index");
		Files.copy(repository.getIndexFile().toPath(), index, StandardCopyOption.REPLACE_EXISTING);
		byte[] head = Files.readAllBytes(repository.getDirectory().toPath().resolve("HEAD"));
		nativeGit(repository, index, true, null, 1, "-c", "feature.manyFiles=false",
				"-c", "core.untrackedCache=keep", "checkout", "--detach", target.name());
		assertArrayEquals("Rejected native checkout preserves HEAD", head,
				Files.readAllBytes(repository.getDirectory().toPath().resolve("HEAD")));
	}

	/** Original empty and populated pathspec directories. */
	public static final List<String> PATHS = List.of("1_file", "10_files",
			"100_files", "1000_files", "10000_files");

	private NativeFsMonitorWorkloadSupport() {
		// Test support only.
	}

	/**
	 * Create the original 11,110 empty tracked-file candidates.
	 *
	 * @param repository disposable repository
	 * @throws Exception if fixture creation fails
	 */
	public static void populate(Repository repository) throws Exception {
		Path root = repository.getWorkTree().toPath();
		Files.createDirectories(root.resolve("1_file"));
		for (int size : new int[] { 10, 100, 1000, 10000 }) {
			Path directory = root.resolve(size + "_files");
			Files.createDirectories(directory);
			for (int i = 1; i <= size; i++)
				Files.write(directory.resolve(Integer.toString(i)), new byte[0]);
		}
	}

	/**
	 * Backdate eligible tracked files by 300 seconds, without content edits.
	 *
	 * @param repository disposable repository
	 * @throws Exception if index or metadata cannot be read
	 */
	public static void backdateTrackedFiles(Repository repository) throws Exception {
		DirCache index = repository.readDirCache();
		for (int i = 0; i < Math.min(100000, index.getEntryCount()); i++) {
			String name = index.getEntry(i).getPathString();
			if (name.chars().anyMatch(Character::isWhitespace) || name.indexOf('"') >= 0)
				continue;
			Path file = repository.getWorkTree().toPath().resolve(name);
			Files.setLastModifiedTime(file, FileTime.fromMillis(
					Files.getLastModifiedTime(file).toMillis() - 300000));
		}
	}

	/**
	 * Assert empty machine-readable native status/diff output with caches disabled.
	 *
	 * @param repository disposable repository
	 * @param arguments status/diff arguments
	 * @throws Exception on command or assertion failure
	 */
	public static void assertNativeEmpty(Repository repository, String... arguments)
			throws Exception {
		assertArrayEquals("Native " + String.join(" ", arguments), new byte[0],
				nativeGit(repository, null, arguments));
	}

	/**
	 * Compare native M-only diff paths for the controlled edited-file fixture.
	 *
	 * @param repository disposable repository
	 * @param head whether to compare HEAD instead of the index
	 * @param path optional pathspec
	 * @param expected expected modified paths
	 * @throws Exception on command or assertion failure
	 */
	public static void assertNativeDiff(Repository repository, boolean head,
			String path, Set<String> expected) throws Exception {
		List<String> arguments = new ArrayList<>(List.of("diff"));
		if (head)
			arguments.add("HEAD");
		arguments.addAll(List.of("--no-ext-diff", "--name-status", "-z"));
		if (path != null)
			arguments.addAll(List.of("--", path));
		byte[] output = nativeGit(repository, null, arguments.toArray(String[]::new));
		if (output.length == 0) {
			assertTrue(expected.toString(), expected.isEmpty());
			return;
		}
		assertEquals(0, output[output.length - 1]);
		String[] fields = new String(output, StandardCharsets.UTF_8).split("\\x00");
		assertEquals(expected.size() * 2, fields.length);
		Set<String> paths = new TreeSet<>();
		for (int i = 0; i < fields.length; i += 2) {
			assertEquals("M", fields[i]);
			assertTrue("Duplicate native path", paths.add(fields[i + 1]));
		}
		assertEquals(expected, paths);
	}

	/**
	 * Stage the same initial index independently, leaving the Java index and
	 * object store untouched. Native writes use a private object directory with
	 * the fixture object directory as a read-only alternate.
	 *
	 * @param repository disposable repository
	 * @return independently staged native index
	 * @throws Exception on command or assertion failure
	 */
	public static Path nativeStageAll(Repository repository) throws Exception {
		Path index = repository.getDirectory().toPath().resolve("native-p7519-index");
		Files.copy(repository.getIndexFile().toPath(), index,
				StandardCopyOption.REPLACE_EXISTING);
		assertArrayEquals(new byte[0], nativeGit(repository, index, "add", "--all"));
		return index;
	}

	/**
	 * Compare complete staged path/blob/mode/stage sequences after Java staging.
	 *
	 * @param repository Java-staged disposable repository
	 * @param nativeIndex independently staged native index
	 * @throws Exception if indexes cannot be read
	 */
	public static void assertStagedMatches(Repository repository, Path nativeIndex)
			throws Exception {
		DirCache expected = DirCache.read(nativeIndex.toFile(), repository.getFS());
		DirCache actual = repository.readDirCache();
		assertEquals(expected.getEntryCount(), actual.getEntryCount());
		Set<ObjectId> checked = new HashSet<>();
		for (int i = 0; i < expected.getEntryCount(); i++) {
			var a = expected.getEntry(i);
			var b = actual.getEntry(i);
			assertArrayEquals(a.getRawPath(), b.getRawPath());
			assertEquals(a.getObjectId(), b.getObjectId());
			assertEquals(a.getRawMode(), b.getRawMode());
			assertEquals(a.getStage(), b.getStage());
			if (checked.add(b.getObjectId()))
				assertTrue("Java staging must insert its objects",
						repository.getObjectDatabase().has(b.getObjectId()));
		}
	}

	/**
	 * Populate native UNTR for a metadata-notification fixture before Java runs.
	 *
	 * @param repository disposable file repository
	 * @throws Exception on native setup failure
	 */
	public static void warmNativeUntrackedCache(Repository repository)
			throws Exception {
		nativeGit(repository, null, true, "-c", "core.untrackedCache=true",
				"update-index", "--untracked-cache");
		for (int i = 0; i < 2; i++)
			nativeGit(repository, null, true, "-c", "core.untrackedCache=true",
					"status", "--porcelain=v1", "-z");
	}

	private static byte[] nativeGit(Repository repository, Path index,
			String... arguments) throws Exception {
		return nativeGit(repository, index, false, arguments);
	}

	private static byte[] nativeGit(Repository repository, Path index,
			boolean allowIndexRefresh, String... arguments) throws Exception {
		return nativeGit(repository, index, allowIndexRefresh, null, arguments);
	}

	private static byte[] nativeGit(Repository repository, Path index,
			boolean allowIndexRefresh, byte[] input, String... arguments) throws Exception {
		return nativeGit(repository, index, allowIndexRefresh, input, 0, arguments);
	}

	private static byte[] nativeGit(Repository repository, Path index,
			boolean allowIndexRefresh, byte[] input, int expectedExit, String... arguments) throws Exception {
		List<String> command = new ArrayList<>(List.of(
				System.getProperty("jgit.test.gitExecutable", "git"),
				"-c", "core.fsmonitor=false", "-c",
				"core.untrackedCache=false"));
		if (!allowIndexRefresh)
			command.add(1, "--no-optional-locks");
		command.addAll(List.of(arguments));
		Path output = repository.getDirectory().toPath().resolve("native-p7519.out");
		Path errors = repository.getDirectory().toPath().resolve("native-p7519.err");
		ProcessBuilder builder = new ProcessBuilder(command)
				.directory(repository.getWorkTree()).redirectOutput(output.toFile())
				.redirectError(errors.toFile());
		builder.environment().keySet().removeIf(n -> n.startsWith("GIT_"));
		builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
		builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
		if (index != null) {
			builder.environment().put("GIT_INDEX_FILE", index.toString());
			Path objects = repository.getDirectory().toPath()
					.resolve("native-p7519-objects");
			Files.createDirectories(objects);
			builder.environment().put("GIT_OBJECT_DIRECTORY", objects.toString());
			// These fixtures are newly initialized file repositories, not
			// linked worktrees. Native writes must not seed the Java store.
			builder.environment().put("GIT_ALTERNATE_OBJECT_DIRECTORIES",
					repository.getDirectory().toPath().resolve("objects").toString());
		}
		Process process = builder.start();
		try {
			try (var stdin = process.getOutputStream()) {
				if (input != null)
					stdin.write(input);
			}
			assertTrue("Native workload timeout", process.waitFor(20, TimeUnit.SECONDS));
			assertTrue("Bound native output", Files.size(output) <= 1024 * 1024);
			assertTrue("Bound native errors", Files.size(errors) <= 1024 * 1024);
			assertEquals(Files.readString(errors), expectedExit, process.exitValue());
			return Files.readAllBytes(output);
		} finally {
			if (process.isAlive()) {
				process.destroyForcibly();
				process.waitFor();
			}
		}
	}
}
