/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.junit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.storage.file.FsMonitorCache;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

/**
 * Shared native t7519 checkout conflict fixtures for JGit and EGit.
 * 
 * @since 7.8
 */
public final class NativeFsMonitorCheckoutSupport {
	private NativeFsMonitorCheckoutSupport() {
	}

	/**
	 * A same-mtime edit that native checkout refuses to overwrite.
	 * 
	 * @param base
	 *            starting commit
	 * @param target
	 *            destination commit
	 * @param file
	 *            edited worktree file
	 * @param originalIndex
	 *            original index bytes
	 * @since 7.8
	 */
	public record Scenario(ObjectId base, ObjectId target, Path file,
			byte[] originalIndex) {
	}

	/**
	 * Prepare and independently verify a native checkout conflict.
	 * 
	 * @param db
	 *            disposable repository
	 * @param mode
	 *            reported, dirty, failure or root notification mode
	 * @param resultCache
	 *            whether the Java status-result cache is enabled
	 * @return prepared scenario
	 * @throws Exception
	 *             on fixture or native-oracle failure
	 * @since 7.8
	 */
	public static Scenario prepare(Repository db, String mode,
			boolean resultCache) throws Exception {
		return prepare(db, mode, resultCache, null, false);
	}

	/**
	 * Prepare native checkout parity with a tracked-path optimization flag.
	 * @param db disposable repository
	 * @param mode reported, dirty, failure, root or disabled monitor mode
	 * @param resultCache whether the Java status-result cache is enabled
	 * @param flag native update-index option, or null for ordinary entries
	 * @param clean whether to leave the worktree file unchanged
	 * @return independently native-verified checkout scenario
	 * @throws Exception on fixture or native-oracle failure
	 * @since 7.8
	 */
	public static Scenario prepare(Repository db, String mode,
			boolean resultCache, String flag, boolean clean) throws Exception {
		NativeFsMonitorIndexSupport.prepare(db);
		String provider = db.getConfig().getString("core", null, "fsmonitor");
		ObjectId base = java.util.Objects
				.requireNonNull(db.resolve(Constants.HEAD));
		ObjectId target;
		try (Git git = Git.wrap(db)) {
			db.getConfig().setBoolean("core", null, "fsmonitor", false);
			db.getConfig().save();
			Path project = db.getWorkTree().toPath().resolve(".project");
			if (Files.isRegularFile(project)
					&& db.readDirCache().getEntry(".project") == null) {
				git.add().addFilepattern(".project").call();
				base = git.commit()
						.setAuthor("Monitor Test", "monitor@example.invalid")
						.setCommitter("Monitor Test", "monitor@example.invalid")
						.setMessage("project").call();
			}
			Files.writeString(db.getWorkTree().toPath().resolve("a"), "dest\n");
			git.add().addFilepattern("a").call();
			target = git.commit()
					.setAuthor("Monitor Test", "monitor@example.invalid")
					.setCommitter("Monitor Test", "monitor@example.invalid")
					.setMessage("target").call();
			try (var reader = db.newObjectReader();
					var tree = org.eclipse.jgit.treewalk.TreeWalk.forPath(
							reader, "a",
							db.resolve(target.name() + "^{tree}"))) {
				assertEquals("dest\n",
						new String(reader.open(tree.getObjectId(0)).getBytes(),
								java.nio.charset.StandardCharsets.UTF_8));
			}
			git.checkout().setName(base.name()).call();
			Path file = db.getWorkTree().toPath().resolve("a");
			Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime
					.fromMillis(System.currentTimeMillis() - 60000));
			db.getConfig().setString("core", null, "fsmonitor", provider);
			db.getConfig().setBoolean("jgit", null, "fsmonitorCache",
					resultCache);
			db.getConfig().save();
			NativeFsMonitorIndexSupport.reportToken(db, "initial-clock");
			installNative(db, "update-index", "--refresh", "--fsmonitor",
					"--force-write-index");
			installNative(db, "update-index", "--fsmonitor-valid", "a", "m",
					"z");
			if (mode.equals("dirty")) {
				var index = db.lockDirCache();
				try {
					BitSet dirty = new BitSet();
					dirty.set(index.findEntry("a"));
					index.setFsMonitorCache(new FsMonitorCache(
							Constants.encode("initial-clock"), dirty));
					index.write();
					assertTrue(index.commit());
				} finally {
					index.unlock();
				}
			}
			if (flag != null) {
				installNative(db, "update-index", flag, "a");
				var entry = db.readDirCache().getEntry("a");
				assertEquals(flag.equals("--assume-unchanged"),
						entry.isAssumeValid());
				assertEquals(flag.equals("--skip-worktree"),
						entry.isSkipWorkTree());
			}
			if (mode.equals("disabled")) {
				db.getConfig().setBoolean("core", null, "fsmonitor", false);
				db.getConfig().save();
			}
			var time = Files.getLastModifiedTime(file);
			// The pinned C Git defaults core.useNanoSec to false. Cross a
			// ctime second while keeping the indexed mtime identical.
			if (!clean) {
				long now = System.currentTimeMillis();
				TimeUnit.MILLISECONDS.sleep(1010 - now % 1000);
				Files.writeString(file, "edit\n");
				Files.setLastModifiedTime(file, time);
			}
			NativeFsMonitorIndexSupport.reportToken(db, "checkout-clock",
					mode.equals("reported") ? new String[] { "a" }
							: mode.equals("root") ? new String[] { "/" }
									: new String[0]);
			if (mode.equals("failure"))
				Files.writeString(Path.of(provider), "#!/bin/sh\nexit 1\n");
			byte[] original = Files.readAllBytes(db.getIndexFile().toPath());
			try (var reader = db.newObjectReader()) {
				assertEquals("base\n",
						new String(
								reader.open(db.readDirCache().getEntry("a")
										.getObjectId()).getBytes(),
								java.nio.charset.StandardCharsets.UTF_8));
			}
			assertNativeCheckout(db, target, !clean);
			return new Scenario(base, target, file, original);
		}
	}

	private static void installNative(Repository db, String... arguments)
			throws Exception {
		var result = NativeFsMonitorIndexSupport.mutate(db, arguments);
		Files.copy(result.index(), db.getIndexFile().toPath(),
				StandardCopyOption.REPLACE_EXISTING);
	}

	private static void assertNativeCheckout(Repository db, ObjectId target,
			boolean conflict) throws Exception {
		Path index = db.getDirectory().toPath()
				.resolve("checkout-oracle.index");
		Files.copy(db.getIndexFile().toPath(), index,
				StandardCopyOption.REPLACE_EXISTING);
		Path output = db.getDirectory().toPath().resolve("checkout-oracle.out");
		String git = System.getProperty("jgit.test.gitExecutable", "git");
		var command = conflict
				? List.of(git, "--no-optional-locks", "checkout", "--detach", target.name())
				: List.of(git, "--no-optional-locks", "read-tree", "--dry-run", "-m", "-u",
						java.util.Objects.requireNonNull(db.resolve(Constants.HEAD)).name(), target.name());
		var builder = new ProcessBuilder(command).directory(db.getWorkTree())
				.redirectErrorStream(true).redirectOutput(output.toFile());
		builder.environment().keySet()
				.removeIf(name -> name.startsWith("GIT_"));
		builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
		builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
		builder.environment().put("GIT_INDEX_FILE", index.toString());
		builder.environment().put("LC_ALL", "C");
		Path head = db.getDirectory().toPath().resolve("HEAD");
		Path work = db.getWorkTree().toPath().resolve("a");
		byte[] originalHead = Files.readAllBytes(head);
		byte[] originalWork = Files.readAllBytes(work);
		var originalTime = Files.getLastModifiedTime(work);
		Process process = builder.start();
		try {
			assertTrue(process.waitFor(20, TimeUnit.SECONDS));
			assertEquals(Files.readString(output), conflict ? 1 : 0, process.exitValue());
			if (conflict) {
				assertTrue(Files.readString(output).contains("overwritten"));
			}
		} finally {
			process.destroyForcibly();
			if (!java.util.Arrays.equals(originalHead,
					Files.readAllBytes(head)))
				Files.write(head, originalHead);
			if (!java.util.Arrays.equals(originalWork,
					Files.readAllBytes(work))) {
				Files.write(work, originalWork);
				Files.setLastModifiedTime(work, originalTime);
			}
		}
	}
}
