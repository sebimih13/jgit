/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.internal.storage.file;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.internal.storage.file.UntrackedCache.Directory;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.junit.NativeUntrackedCacheSnapshots;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.Test;

public class UntrackedCacheTest extends RepositoryTestCase {
	private java.nio.file.Path nativeTrace;

	private static final ObjectId IGNORE = ObjectId
			.fromString("1234567890123456789012345678901234567890");

	private static UntrackedCache sample() {
		UntrackedCache cache = new UntrackedCache();
		cache.ident = Constants
				.encode("Location /tmp/repo, system Linux\0legacy\0");
		cache.dirFlags = 6;
		cache.infoExclude.oid = IGNORE;
		cache.excludesFile.oid = IGNORE;
		for (int i = 0; i < 9; i++) {
			cache.infoExclude.stat.fields[i] = 0xf0000000 + i;
			cache.excludesFile.stat.fields[i] = i + 1;
		}
		cache.root = new Directory(new byte[0]);
		cache.root.valid = cache.root.recurse = true;
		cache.root.excludeOid = IGNORE;
		cache.root.untracked.add(Constants.encode("a\n\t雪"));
		Directory child = new Directory(Constants.encode("sub"));
		child.valid = child.checkOnly = child.recurse = true;
		child.stat.fields[8] = 123;
		child.untracked.add(Constants.encode("file"));
		cache.root.directories.add(child);
		return cache;
	}

	@Test
	public void completeLayoutRoundTripsRawNamesAndUnsignedStatFields()
			throws Exception {
		UntrackedCache original = sample();
		original.root.untracked.add(new byte[] { (byte) 0xff, '\n' });
		byte[] raw = original.write();
		UntrackedCache decoded = UntrackedCache.read(raw);
		assertArrayEquals(raw, decoded.write());
		assertArrayEquals(original.ident, decoded.ident);
		assertEquals(original.dirFlags, decoded.dirFlags);
		assertArrayEquals(original.infoExclude.stat.fields,
				decoded.infoExclude.stat.fields);
		assertArrayEquals(original.excludesFile.stat.fields,
				decoded.excludesFile.stat.fields);
		assertEquals(IGNORE, decoded.infoExclude.oid);
		assertEquals(IGNORE, decoded.excludesFile.oid);
		assertEquals(IGNORE, decoded.root.excludeOid);
		assertArrayEquals(original.root.untracked.get(1),
				decoded.root.untracked.get(1));
		assertTrue(decoded.root.directories.get(0).checkOnly);
		assertEquals(123, decoded.root.directories.get(0).stat.fields[8]);
	}

	@Test
	public void uninitializedRootAcceptsNativeAndDocumentedTerminators()
			throws Exception {
		UntrackedCache cache = sample();
		cache.root = null;
		byte[] raw = cache.write();
		assertNull(UntrackedCache.read(raw).root);
		assertArrayEquals(raw, UntrackedCache.read(raw).write());
		assertNull(
				UntrackedCache.read(Arrays.copyOf(raw, raw.length + 1)).root);
	}

	@Test
	public void invalidNodesLoseResultsAndNonRecursedNodesAreOmitted()
			throws Exception {
		UntrackedCache cache = sample();
		cache.root.valid = false;
		cache.root.checkOnly = true;
		cache.root.directories.add(new Directory(Constants.encode("omitted")));
		UntrackedCache decoded = UntrackedCache.read(cache.write());
		assertFalse(decoded.root.valid);
		assertFalse(decoded.root.checkOnly);
		assertTrue(decoded.root.untracked.isEmpty());
		assertEquals(1, decoded.root.directories.size());
		assertTrue(decoded.root.directories.get(0).valid);
	}

	@Test
	public void bitmapAndVarintWordBoundariesRoundTrip() throws Exception {
		UntrackedCache cache = sample();
		cache.root.directories.clear();
		for (int i = 0; i < 260; i++) {
			Directory child = new Directory(Constants.encode("dir" + i));
			child.recurse = true;
			child.valid = i % 3 != 0;
			child.checkOnly = child.valid && i % 5 == 0;
			child.excludeOid = i % 7 == 0 ? IGNORE : ObjectId.zeroId();
			child.stat.fields[8] = i;
			cache.root.directories.add(child);
		}
		byte[] raw = cache.write();
		UntrackedCache decoded = UntrackedCache.read(raw);
		assertArrayEquals(raw, decoded.write());
		assertEquals(260, decoded.root.directories.size());
		for (int i = 0; i < 260; i++) {
			Directory child = decoded.root.directories.get(i);
			assertEquals(i % 3 != 0, child.valid);
			assertEquals(child.valid && i % 5 == 0, child.checkOnly);
			assertEquals(i % 7 == 0 ? IGNORE : ObjectId.zeroId(),
					child.excludeOid);
		}
	}

	@Test
	public void deepTreesUseBoundedIterationRatherThanJavaRecursion()
			throws Exception {
		UntrackedCache cache = sample();
		cache.root.directories.clear();
		Directory leaf = cache.root;
		for (int i = 0; i < 5000; i++) {
			Directory child = new Directory(Constants.encode("a"));
			child.recurse = child.valid = true;
			leaf.directories.add(child);
			leaf = child;
		}
		byte[] raw = cache.write();
		UntrackedCache decoded = UntrackedCache.read(raw);
		assertArrayEquals(raw, decoded.write());
		decoded.invalidateAll();
		assertFalse(decoded.root.valid);
	}

	@Test
	public void cyclicAndSharedDirectoryModelsAreRejected() throws Exception {
		UntrackedCache cache = sample();
		Directory child = cache.root.directories.get(0);
		child.directories.add(cache.root);
		assertThrows(java.io.IOException.class, cache::write);
		child.directories.clear();
		cache.root.directories.add(child);
		assertThrows(java.io.IOException.class, cache::write);
	}

	@Test
	public void truncatedPayloadsAndTrailingGarbageAreRejected()
			throws Exception {
		byte[] raw = sample().write();
		for (int i = 0; i < raw.length; i++) {
			byte[] truncated = Arrays.copyOf(raw, i);
			assertThrows("Truncation at " + i, java.io.IOException.class,
					() -> UntrackedCache.read(truncated));
		}
		byte[] extra = Arrays.copyOf(raw, raw.length + 1);
		assertThrows(java.io.IOException.class,
				() -> UntrackedCache.read(extra));
	}

	@Test
	public void overflowingVarintsAndDirectoryCountsAreRejected()
			throws Exception {
		byte[] raw = sample().write();
		Arrays.fill(raw, 0, 10, (byte) 0xff);
		assertThrows(java.io.IOException.class, () -> UntrackedCache.read(raw));
		byte[] original = sample().write();
		int countOffset = headerEnd(original);
		original[countOffset] = 127;
		assertThrows(java.io.IOException.class,
				() -> UntrackedCache.read(original));
	}

	@Test
	public void malformedBitmapsAreRejectedWithoutUnboundedExpansion()
			throws Exception {
		UntrackedCache cache = new UntrackedCache();
		cache.root = new Directory(new byte[0]);
		cache.root.recurse = true;
		byte[] original = cache.write();
		int bitmap = headerEnd(original) + 4; // count, file count, child count,
												// root name
		for (int field = 0; field < 3; field++) {
			byte[] changed = original.clone();
			int offset = bitmap + (field == 2 ? 16 : field * 4);
			ByteBuffer.wrap(changed).putInt(offset, Integer.MAX_VALUE);
			assertThrows(java.io.IOException.class,
					() -> UntrackedCache.read(changed));
		}
		byte[] expanded = original.clone();
		ByteBuffer.wrap(expanded).putLong(bitmap + 8, 0x1fffffffeL);
		assertThrows(java.io.IOException.class,
				() -> UntrackedCache.read(expanded));
	}

	@Test
	public void malformedNamesAndIdentitiesAreRejected() throws Exception {
		UntrackedCache cache = sample();
		cache.ident = Constants.encode("unterminated");
		assertThrows(java.io.IOException.class, cache::write);
		cache.ident = new byte[0];
		cache.root.untracked.add(new byte[] { 'a', 0 });
		assertThrows(java.io.IOException.class, cache::write);
		cache.root.untracked.remove(cache.root.untracked.size() - 1);
		cache.root.directories.add(new Directory(Constants.encode("../bad")));
		cache.root.directories.get(1).recurse = true;
		assertThrows(java.io.IOException.class, cache::write);
	}

	@Test
	public void deterministicMalformedInputFuzzDoesNotLeakRuntimeExceptions()
			throws Exception {
		Random random = new Random(419);
		for (int i = 0; i < 1000; i++) {
			byte[] raw = new byte[random.nextInt(512)];
			random.nextBytes(raw);
			try {
				UntrackedCache.read(raw);
			} catch (java.io.IOException expected) {
				// Corrupt optional data is rejected with a checked failure.
			}
		}
	}

	@Test
	public void rootIndexInvalidationMatchesOriginalNativeIntermediateDumps()
			throws Exception {
		// t7063: rm --cached two, and then add two. Both invalidate only
		// the root listing, retaining the unaffected directory summaries.
		for (int[] transition : new int[][] { { 20, 21 }, { 23, 24 } }) {
			NativeUntrackedCacheSnapshots.install(db, transition[0]);
			UntrackedCache cache = DirCache.read(db).getUntrackedCache();
			cache.invalidatePath(Constants.encode("two"));
			assertEquals(1, cache.dirInvalidated);
			NativeUntrackedCacheSnapshots.assertNativeDump(cache, transition[1]);
			NativeUntrackedCacheSnapshots.assertNativeDump(
					UntrackedCache.read(cache.write()), transition[1]);
		}
	}

	@Test
	public void originalNativeDumpCheckpointsSurviveIndexRewrite()
			throws Exception {
		for (int checkpoint : NativeUntrackedCacheSnapshots.checkpoints()) {
			NativeUntrackedCacheSnapshots.install(db, checkpoint);
			DirCache original = DirCache.read(db);
			DirCache rewritten = db.lockDirCache();
			try {
				rewritten.write();
				assertTrue(rewritten.commit());
			} finally {
				rewritten.unlock();
			}
			NativeUntrackedCacheSnapshots.assertStored(db, checkpoint);
			DirCache actual = DirCache.read(db);
			assertEquals(original.getEntryCount(), actual.getEntryCount());
			for (int i = 0; i < original.getEntryCount(); i++) {
				var before = original.getEntry(i);
				var after = actual.getEntry(i);
				assertArrayEquals(before.getRawPath(), after.getRawPath());
				assertEquals(before.getObjectId(), after.getObjectId());
				assertEquals(before.getStage(), after.getStage());
				assertEquals(before.getRawMode(), after.getRawMode());
				assertEquals(before.isSkipWorkTree(), after.isSkipWorkTree());
				assertEquals(before.isAssumeValid(), after.isAssumeValid());
			}
		}
	}

	@Test
	public void readsNativeGitPayloadAndNativeGitReadsJGitRewrite()
			throws Exception {
		checkNativePayloadRoundTrip("all");
	}

	@Test
	public void nativeNormalModeDirectorySummariesSurviveJGitRewrite()
			throws Exception {
		checkNativePayloadRoundTrip("normal");
	}

	private void checkNativePayloadRoundTrip(String mode) throws Exception {
		requireNativeGit();
		writeTrashFile("tracked", "base");
		try (Git git = new Git(db)) {
			git.add().addFilepattern("tracked").call();
		}
		writeTrashFile("untracked", "new");
		writeTrashFile("sub/child", "new");
		writeTrashFile(".gitignore", "ignored\n");
		writeTrashFile("ignored", "hidden");
		nativeGit("config", "status.showUntrackedFiles", mode);
		nativeGit("update-index", "--untracked-cache");
		for (int i = 0; i < 130; i++) {
			writeTrashFile("dir" + i + "/file", "new");
		}
		// Native untracked-cache validation rejects directories from the same
		// timestamp tick as the index. Age the fixture before populating it.
		Thread.sleep(1100);
		for (String version : new String[] { "2", "3", "4" }) {
			if ("3".equals(version)) {
				// Native Git otherwise downgrades an index without extended
				// entries to v2. Skip-worktree requires a real v3 entry.
				nativeGit("update-index", "--skip-worktree", "tracked");
			}
			nativeGit("update-index", "--index-version", version);
			assertEquals(Integer.parseInt(version), ByteBuffer.wrap(
					Files.readAllBytes(db.getIndexFile().toPath())).getInt(4));
			byte[] before = nativeGit("status", "--porcelain=v1", "-z",
					"--untracked-files=" + mode);
			// t7063: an explicit mode different from the configured mode
			// bypasses the cache without replacing its directory summaries.
			byte[] populated = DirCache.read(db).getUntrackedCache().write();
			nativeGit("status", "--porcelain=v1", "-z", "--untracked-files="
					+ ("all".equals(mode) ? "normal" : "all"));
			assertArrayEquals("Bypass must preserve the populated UNTR payload",
					populated, DirCache.read(db).getUntrackedCache().write());
			assertArrayEquals(before, nativeGit("status", "--porcelain=v1", "-z",
					"--untracked-files=" + mode));
			DirCache cache = db.lockDirCache();
			try {
				assertNotNull(cache.getUntrackedCache());
				assertNotNull(cache.getUntrackedCache().root);
				assertEquals("all".equals(mode) ? 0 : 6, cache.getUntrackedCache().dirFlags);
				Directory child = cache.getUntrackedCache().root.directories.stream()
						.filter(d -> Arrays.equals(d.name, Constants.encode("sub")))
						.findFirst().orElseThrow();
				assertTrue(child.valid);
				assertTrue(child.recurse);
				assertEquals("normal".equals(mode), child.checkOnly);
				assertEquals("normal".equals(mode),
						cache.getUntrackedCache().root.untracked.stream()
								.anyMatch(n -> Arrays.equals(n, Constants.encode("sub/"))));
				byte[] originalIndex = Files.readAllBytes(db.getIndexFile().toPath());
				int extension = -1;
				for (int i = 12; i + 8 < originalIndex.length - 20; i++) {
					if (ByteBuffer.wrap(originalIndex).getInt(i) == 0x554e5452) { extension = i; break; }
				}
				assertTrue("Native UNTR extension must be present", extension >= 0);
				int length = ByteBuffer.wrap(originalIndex).getInt(extension + 4);
				assertArrayEquals("Native payload must round-trip",
						Arrays.copyOfRange(originalIndex, extension + 8, extension + 8 + length),
						cache.getUntrackedCache().write());
				cache.write();
				assertTrue(cache.commit());
			} finally {
				cache.unlock();
			}
			nativeTrace = db.getDirectory().toPath()
					.resolve("untracked-interop-trace");
			Files.deleteIfExists(nativeTrace);
			try {
				assertArrayEquals(before, nativeGit("status", "--porcelain=v1",
						"-z", "--untracked-files=" + mode));
				java.util.regex.Matcher opened = java.util.regex.Pattern
						.compile(
								"\"category\":\"read_directory\".*\"key\":\"opendir\".*\"value\":\"([0-9]+)\"")
						.matcher(Files.readString(nativeTrace));
				assertTrue("Native directory traversal counter must be present",
						opened.find());
				assertEquals("Native Git must consume the rewritten cache", "0",
						opened.group(1));
				assertNotNull(DirCache.read(db).getUntrackedCache());
			} finally {
				nativeTrace = null;
			}
		}
	}

	@Test
	public void indexMembershipChangesInvalidateImportedNativeCache()
			throws Exception {
		requireNativeGit();
		writeTrashFile("file", "new");
		nativeGit("update-index", "--untracked-cache");
		nativeGit("status", "--porcelain=v1", "-z");
		try (Git git = new Git(db)) {
			git.add().addFilepattern("file").call();
		}
		UntrackedCache decoded = DirCache.read(db).getUntrackedCache();
		assertNotNull(decoded);
		assertFalse(decoded.root.valid);
		assertTrue(decoded.root.untracked.isEmpty());
		assertArrayEquals(
				nativeGit("-c", "core.untrackedCache=false", "status",
						"--porcelain=v1", "-z"),
				nativeGit("status", "--porcelain=v1", "-z"));
	}

	@Test
	public void corruptOptionalExtensionDoesNotDestroyIndexEntries()
			throws Exception {
		writeTrashFile("tracked", "base");
		try (Git git = new Git(db)) {
			git.add().addFilepattern("tracked").call();
		}
		byte[] index = Files.readAllBytes(db.getIndexFile().toPath());
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(index, 0, index.length - Constants.OBJECT_ID_LENGTH);
		DataOutputStream data = new DataOutputStream(out);
		data.writeInt(0x554e5452);
		data.writeInt(3);
		data.write(new byte[] { 1, 2, 3 });
		data.flush();
		byte[] body = out.toByteArray();
		out.write(Constants.newMessageDigest().digest(body));
		Files.write(db.getIndexFile().toPath(), out.toByteArray());
		DirCache cache = DirCache.read(db);
		assertEquals(1, cache.getEntryCount());
		assertEquals("tracked", cache.getEntry(0).getPathString());
		assertNull(cache.getUntrackedCache());
	}

	@Test
	public void lookupMaintainsUnsignedByteOrderAndReusesNodes()
			throws Exception {
		UntrackedCache cache = sample();
		Directory high = cache.lookupDirectory(cache.root,
				new byte[] { (byte) 0xff });
		Directory low = cache.lookupDirectory(cache.root,
				Constants.encode("a"));
		assertSame(low,
				cache.lookupDirectory(cache.root, Constants.encode("a")));
		assertSame(low, cache.lookupDirectory(cache.root, Constants.encode("a/")));
		assertArrayEquals(Constants.encode("a"),
				cache.root.directories.get(0).name);
		assertSame(high, cache.root.directories.get(2));
		assertEquals(2, cache.dirCreated);
		assertNull(cache.lookupDirectory(null, Constants.encode("a")));
	}

	@Test
	public void allModeIndexInvalidationOnlyTouchesContainingDirectory()
			throws Exception {
		UntrackedCache cache = sample();
		cache.dirFlags = 0;
		Directory unaffected = cache.lookupDirectory(cache.root,
				Constants.encode("other"));
		unaffected.valid = unaffected.recurse = true;
		Directory sub = cache.root.directories.stream()
				.filter(d -> Arrays.equals(d.name, Constants.encode("sub")))
				.findFirst().get();
		cache.invalidatePath(Constants.encode("sub/new"));
		assertTrue(cache.root.valid);
		assertFalse(sub.valid);
		assertTrue(sub.untracked.isEmpty());
		assertTrue(unaffected.valid);
		assertEquals(1, cache.dirInvalidated);
	}

	@Test
	public void normalModeIndexInvalidationPropagatesDirectorySummaries()
			throws Exception {
		UntrackedCache cache = sample();
		Directory sub = cache.root.directories.get(0);
		cache.invalidatePath(Constants.encode("sub/new"));
		assertFalse(cache.root.valid);
		assertFalse(sub.valid);
		assertTrue(cache.root.untracked.isEmpty());
		assertTrue(sub.untracked.isEmpty());
		assertEquals(2, cache.dirInvalidated);
	}

	@Test
	public void ignoreChangesInvalidateDescendantsButRetainRecurseFlags()
			throws Exception {
		UntrackedCache cache = sample();
		cache.invalidateIgnore(cache.root);
		assertFalse(cache.root.valid);
		assertFalse(cache.root.directories.get(0).valid);
		assertTrue(cache.root.recurse);
		assertTrue(cache.root.directories.get(0).recurse);
		assertEquals(1, cache.gitignoreInvalidated);
	}

	@Test
	public void changedListingsResetChildRecurseFlagsWithoutDiscardingChildren()
			throws Exception {
		UntrackedCache cache = sample();
		Directory sub = cache.root.directories.get(0);
		cache.invalidateDirectory(cache.root);
		assertFalse(cache.root.valid);
		assertFalse(sub.recurse);
		assertTrue(sub.valid);
		assertEquals(1, cache.dirInvalidated);
		UntrackedCache decoded = UntrackedCache.read(cache.write());
		assertTrue(decoded.root.directories.isEmpty());
		assertEquals(1, cache.root.directories.size());
	}

	@Test
	public void unsafeIndexPathsDoNotPartiallyInvalidateTheTree()
			throws Exception {
		UntrackedCache cache = sample();
		for (String path : new String[] { "", "/absolute", "sub/../evil",
				"sub/./file", "sub//file" }) {
			assertThrows(java.io.IOException.class,
					() -> cache.invalidatePath(Constants.encode(path)));
			assertTrue(cache.root.valid);
			assertTrue(cache.root.directories.get(0).valid);
		}
	}

	private static int headerEnd(byte[] raw) {
		int pos = 0;
		int current = Byte.toUnsignedInt(raw[pos++]);
		int length = current & 127;
		while ((current & 128) != 0) {
			current = Byte.toUnsignedInt(raw[pos++]);
			length = ((length + 1) << 7) + (current & 127);
		}
		pos += length + 2 * 36 + 4 + 2 * Constants.OBJECT_ID_LENGTH;
		while (raw[pos++] != 0) {
			/* per-directory exclude filename */ }
		return pos;
	}

	private void requireNativeGit() {
		try {
			assumeTrue(nativeGit("--version").length > 0);
		} catch (Exception absent) {
			assumeTrue(
					"Installed Git is required for native format interoperability",
					false);
		}
	}

	private byte[] nativeGit(String... arguments) throws Exception {
		java.util.List<String> command = new java.util.ArrayList<>();
		command.add(System.getProperty("jgit.test.gitExecutable", "git"));
		command.add("-c");
		command.add("core.fsmonitor=false");
		command.addAll(Arrays.asList(arguments));
		ProcessBuilder builder = new ProcessBuilder(command);
		builder.directory(db.getWorkTree());
		builder.environment().keySet().removeIf(name -> name.startsWith("GIT_"));
		builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
		builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
		if (nativeTrace != null) {
			builder.environment().put("GIT_TRACE2_EVENT",
					nativeTrace.toString());
			builder.environment().put("GIT_TRACE2_EVENT_NESTING", "20");
		}
		java.nio.file.Path stdout = Files.createTempFile(
				db.getDirectory().toPath(), "native-git-", ".out");
		java.nio.file.Path stderr = Files.createTempFile(
				db.getDirectory().toPath(), "native-git-", ".err");
		builder.redirectOutput(stdout.toFile());
		builder.redirectError(stderr.toFile());
		Process process = builder.start();
		try {
			assertTrue("Git command timed out",
					process.waitFor(10, TimeUnit.SECONDS));
			assertTrue("Bound native fixture output",
					Files.size(stdout) <= 4 * 1024 * 1024);
			byte[] output = Files.readAllBytes(stdout);
			byte[] error = Files.readAllBytes(stderr);
			assertEquals(
					new String(error, java.nio.charset.StandardCharsets.UTF_8),
					0, process.exitValue());
			return output;
		} finally {
			if (process.isAlive()) {
				process.destroyForcibly();
			}
			Files.deleteIfExists(stdout);
			Files.deleteIfExists(stderr);
		}
	}
}
