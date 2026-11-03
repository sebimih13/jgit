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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class Trace2RepositoryMetadataTest extends RepositoryTestCase {

	@Test
	public void commitCapturesParentHeadRatherThanNewCommit() throws Exception {
		RevCommit parent = commit(db);
		String branch = db.getFullBranch();
		StringWriter out = runCommand(db, "commit");
		db.getRefDatabase().refresh();
		try (var walk = new org.eclipse.jgit.revwalk.RevWalk(db)) {
			RevCommit created = walk.parseCommit(db.resolve(Constants.HEAD));
			assertEquals(parent.getId(), created.getParent(0).getId());
			assertFalse(parent.getId().equals(created.getId()));
		}
		assertEquals(branch, db.getFullBranch());
		assertCommandSnapshot(out, "commit", branch, parent.name());
	}

	@Test
	public void firstCommitCapturesUnbornBranchAndNullCommit() throws Exception {
		String branch = db.getFullBranch();
		StringWriter out = runCommand(db, "commit");
		db.getRefDatabase().refresh();
		assertTrue(db.resolve(Constants.HEAD) != null);
		try (var walk = new org.eclipse.jgit.revwalk.RevWalk(db)) {
			assertEquals(0, walk.parseCommit(db.resolve(Constants.HEAD))
					.getParentCount());
		}
		assertEquals(branch, db.getFullBranch());
		assertCommandSnapshot(out, "commit", branch, null);
	}

	@Test
	public void pushCapturesCurrentHeadWhenPushingADifferentBranch()
			throws Exception {
		RevCommit pushed = commit(db);
		try (Git git = Git.wrap(db)) {
			git.branchCreate().setName("to-push").call();
		}
		RevCommit current = commit(db);
		String branch = db.getFullBranch();
		try (Repository remote = createBareRepository()) {
			StringWriter out = runCommand(db, "push",
					remote.getDirectory().toURI().toString(),
					"refs/heads/to-push:refs/heads/destination");
			remote.getRefDatabase().refresh();
			assertEquals(pushed.getId(), remote.resolve("refs/heads/destination"));
			assertEquals(current.getId(), db.resolve(Constants.HEAD));
			assertEquals(branch, db.getFullBranch());
			assertCommandSnapshot(out, "push", branch, current.name());
		}
	}

	@Test
	public void pullCapturesStartingHeadBeforeFastForward() throws Exception {
		RevCommit before = commit(db);
		String branch = db.getFullBranch();
		File directory = new File(trash, "pull-target");
		try (Git target = Git.cloneRepository()
				.setURI(db.getDirectory().toURI().toString())
				.setDirectory(directory).call()) {
			RevCommit incoming = commit(db);
			Repository repository = target.getRepository();
			assertEquals(before.getId(), repository.resolve(Constants.HEAD));
			StringWriter out = runCommand(repository, "pull");
			repository.getRefDatabase().refresh();
			assertEquals(incoming.getId(), repository.resolve(Constants.HEAD));
			assertEquals(branch, repository.getFullBranch());
			assertCommandSnapshot(out, "pull", branch, before.name());
		}
	}

	@Test
	public void commandCapturesFullBranchAndCommitOnProcessAndRegion()
			throws Exception {
		RevCommit commit = commit(db);
		StringWriter out = new StringWriter();
		try (Trace2 trace = tracer(out);
				Trace2.Scope scope = trace.command(db, "status", "test")) {
			assertTrue(scope.isEnabled());
		}
		JsonObject process = processEvents(out).get(0);
		assertEquals(1, process.get("nesting").getAsInt());
		assertEquals("1", process.get("key").getAsString());
		assertEquals(1, process.get("repo").getAsInt());
		assertHead(process.getAsJsonObject("value"), db.getFullBranch(),
				commit.name());
		JsonObject region = regionEvents(out).get(0);
		assertEquals(2, region.get("nesting").getAsInt());
		assertEquals(process.get("value"), region.get("value"));
	}

	@Test
	public void unbornDetachedAndNewBranchDoNotRetainAnOldSnapshot()
			throws Exception {
		StringWriter out = new StringWriter();
		String initialBranch = db.getFullBranch();
		try (Trace2 trace = tracer(out)) {
			capture(trace, db);
			RevCommit first = commit(db);
			capture(trace, db);
			try (Git git = Git.wrap(db)) {
				git.checkout().setName(first.name()).call();
				capture(trace, db);
				git.checkout().setCreateBranch(true).setName("feature/metadata")
						.call();
				RevCommit second = commit(db);
				capture(trace, db);
				List<JsonObject> snapshots = processEvents(out);
				assertEquals(4, snapshots.size());
				assertHead(snapshots.get(0).getAsJsonObject("value"),
						initialBranch, null);
				assertHead(snapshots.get(1).getAsJsonObject("value"),
						initialBranch, first.name());
				assertHead(snapshots.get(2).getAsJsonObject("value"), null,
						first.name());
				assertHead(snapshots.get(3).getAsJsonObject("value"),
						"refs/heads/feature/metadata", second.name());
			}
		}
	}

	@Test
	public void backgroundRefreshScopesKeepRepositoriesSeparate()
			throws Exception {
		RevCommit first = commit(db);
		StringWriter out = new StringWriter();
		var pool = Executors.newFixedThreadPool(2);
		try (Repository other = createWorkRepository();
				Trace2 trace = tracer(out)) {
			other.updateRef(Constants.HEAD).link("refs/heads/other");
			commit(other);
			RevCommit second = commit(other);
			var full = pool.submit(() -> {
				try (Trace2.Scope scope = trace.command(db, "status",
						"egit.indexdiff.full")) {
					assertTrue(scope.isEnabled());
				}
			});
			var incremental = pool.submit(() -> {
				try (Trace2.Scope scope = trace.command(other, "status",
						"egit.indexdiff.incremental")) {
					assertTrue(scope.isEnabled());
				}
			});
			full.get();
			incremental.get();
			List<JsonObject> snapshots = processEvents(out);
			assertEquals(2, snapshots.size());
			assertFalse(snapshots.get(0).get("key")
					.equals(snapshots.get(1).get("key")));
			for (JsonObject snapshot : snapshots) {
				JsonObject head = snapshot.getAsJsonObject("value");
				String oid = head.get("commit").getAsString();
				assertTrue(oid.equals(first.name()) || oid.equals(second.name()));
				assertEquals(oid.equals(first.name()) ? db.getFullBranch()
						: "refs/heads/other", head.get("branch").getAsString());
				assertTrue(snapshot.get("thread").getAsString()
						.startsWith("th"));
			}
			assertEquals(2, regionEvents(out).size());
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	public void failedHeadLookupDoesNotFailCommandOrReuseOldMetadata()
			throws Exception {
		commit(db);
		Repository repository = spy(db);
		StringWriter out = new StringWriter();
		try (Trace2 trace = tracer(out)) {
			capture(trace, repository);
			doThrow(new IOException("unavailable"))
					.when(repository).exactRef(Constants.HEAD);
			capture(trace, repository);
		}
		JsonObject failed = processEvents(out).get(1).getAsJsonObject("value");
		assertHead(failed, null, null);
		assertTrue(failed.get("unavailable").getAsBoolean());
	}

	@Test
	public void disabledTracingDoesNotReadRepository() {
		Repository repository = mock(Repository.class);
		try (Trace2 trace = new Trace2(null, false, false, 10, false, null);
				Trace2.Scope scope = trace.command(repository, "status", "test")) {
			assertFalse(scope.isEnabled());
		}
		verifyNoInteractions(repository);
	}

	private static RevCommit commit(Repository repository) throws Exception {
		try (Git git = Git.wrap(repository)) {
			return git.commit().setAllowEmpty(true).setMessage("metadata")
					.setAuthor("Trace", "trace@example.invalid")
					.setCommitter("Trace", "trace@example.invalid").call();
		}
	}

	private static Trace2 tracer(StringWriter out) {
		return new Trace2(out, false, false, 10, false, null);
	}

	private static StringWriter runCommand(Repository repository,
			String operation, String... args) throws Exception {
		Path events = Files.createTempFile(repository.getDirectory().toPath(),
				"trace-metadata-", ".jsonl");
		Path output = Files.createTempFile(repository.getDirectory().toPath(),
				"trace-metadata-", ".log");
		List<String> command = new ArrayList<>(List.of(
				Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-cp", System.getProperty("surefire.test.class.path",
						System.getProperty("java.class.path")),
				CommandProbe.class.getName(), repository.getDirectory().getPath(),
				operation));
		command.addAll(List.of(args));
		ProcessBuilder builder = new ProcessBuilder(command);
		builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
		builder.environment().put("GIT_CONFIG_GLOBAL", File.separatorChar == '\\'
				? "NUL" : "/dev/null");
		builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
		builder.environment().put("GIT_TRACE2_EVENT", events.toString());
		builder.environment().put("GIT_TRACE2_EVENT_BRIEF", "0");
		builder.environment().put("GIT_TRACE2_EVENT_NESTING", "20");
		builder.redirectErrorStream(true).redirectOutput(output.toFile());
		Process child = builder.start();
		try {
			assertTrue("Command subprocess timed out",
					child.waitFor(30, TimeUnit.SECONDS));
			assertEquals(Files.readString(output), 0, child.exitValue());
		} finally {
			child.destroyForcibly();
		}
		StringWriter capture = new StringWriter();
		capture.write(Files.readString(events));
		return capture;
	}

	/** Isolate the real singleton tracer and its lifecycle in a fresh JVM. */
	public static class CommandProbe {
		public static void main(String[] args) throws Exception {
			try (Repository repository = new FileRepositoryBuilder()
					.setGitDir(new File(args[0])).build();
					Git git = Git.wrap(repository)) {
				switch (args[1]) {
				case "commit":
					git.commit().setAllowEmpty(true).setSign(false)
							.setMessage("traced commit")
							.setAuthor("Trace", "trace@example.invalid")
							.setCommitter("Trace", "trace@example.invalid").call();
					break;
				case "push":
					int updates = 0;
					for (var result : git.push().setRemote(args[2])
							.setRefSpecs(new RefSpec(args[3])).call()) {
						for (var update : result.getRemoteUpdates()) {
							assertEquals(RemoteRefUpdate.Status.OK,
									update.getStatus());
							updates++;
						}
					}
					assertEquals(1, updates);
					break;
				case "pull":
					var result = git.pull().setRebase(false).call();
					assertTrue(result.isSuccessful());
					assertEquals(org.eclipse.jgit.api.MergeResult.MergeStatus.FAST_FORWARD,
							result.getMergeResult().getMergeStatus());
					break;
				default:
					throw new IllegalArgumentException(args[1]);
				}
			} finally {
				// This subprocess owns its tracing session, not a shared IDE job.
				Trace2.getInstance().close();
			}
		}
	}

	private static void assertCommandSnapshot(StringWriter out, String command,
			String branch, String commit) {
		List<JsonObject> records = out.toString().lines()
				.map(JsonParser::parseString).map(e -> e.getAsJsonObject()).toList();
		JsonObject entry = records.stream()
				.filter(e -> "region_enter".equals(e.get("event").getAsString()))
				.filter(e -> "jgit".equals(e.get("category").getAsString()))
				.filter(e -> command.equals(e.get("label").getAsString()))
				.findFirst().orElseThrow();
		String thread = entry.get("thread").getAsString();
		String sid = entry.get("sid").getAsString();
		int depth = entry.get("nesting").getAsInt();
		boolean headSeen = false;
		boolean closed = false;
		for (int i = records.indexOf(entry) + 1; i < records.size(); i++) {
			JsonObject event = records.get(i);
			if (!sid.equals(event.get("sid").getAsString())
					|| !thread.equals(event.get("thread").getAsString())) {
				continue;
			}
			if ("region_leave".equals(event.get("event").getAsString())
					&& event.get("nesting").getAsInt() == depth) {
				closed = true;
				break;
			}
			if ("data_json".equals(event.get("event").getAsString())
					&& "jgit".equals(event.get("category").getAsString())
					&& "head".equals(event.get("key").getAsString())
					&& event.get("nesting").getAsInt() == depth + 1) {
				assertHead(event.getAsJsonObject("value"), branch, commit);
				headSeen = true;
			}
		}
		assertTrue("Actual command must record its starting HEAD", headSeen);
		assertTrue("Actual command must close its operation scope", closed);
		JsonObject process = processEvents(out).stream()
				.filter(e -> e.get("repo").equals(entry.get("repo")))
				.filter(e -> sid.equals(e.get("sid").getAsString()))
				.findFirst().orElseThrow();
		assertHead(process.getAsJsonObject("value"), branch, commit);
	}

	private static void capture(Trace2 trace, Repository repository) {
		try (Trace2.Scope scope = trace.command(repository, "status", "test")) {
			assertTrue(scope.isEnabled());
		}
	}

	private static List<JsonObject> processEvents(StringWriter out) {
		return events(out, "repository", null);
	}

	private static List<JsonObject> regionEvents(StringWriter out) {
		return events(out, "jgit", "head");
	}

	private static List<JsonObject> events(StringWriter out, String category,
			String key) {
		return out.toString().lines().map(JsonParser::parseString)
				.map(e -> e.getAsJsonObject())
				.filter(e -> "data_json".equals(e.get("event").getAsString()))
				.filter(e -> category.equals(e.get("category").getAsString()))
				.filter(e -> key == null || key.equals(e.get("key").getAsString()))
				.toList();
	}

	private static void assertHead(JsonObject head, String branch,
			String commit) {
		assertEquals(branch, head.get("branch").isJsonNull() ? null
				: head.get("branch").getAsString());
		assertEquals(commit, head.get("commit").isJsonNull() ? null
				: head.get("commit").getAsString());
	}
}
