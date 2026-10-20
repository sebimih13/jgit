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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Pre-push protocol scenarios from C Git t5571-pre-push-hook.sh.
 *
 * @since 7.8
 */
public abstract class PrePushHookTestCase extends RepositoryTestCase {
	/** Destination bare repository. */
	protected Repository server;

	/** First source commit. */
	protected RevCommit first;

	/** Latest source commit. */
	protected RevCommit next;

	/** Destination location passed to the transport. */
	protected String location;

	/**
	 * Execute a real push.
	 *
	 * @param remote
	 *            configured remote name or literal URL
	 * @param specifications
	 *            requested reference mappings
	 * @return push result
	 * @throws Exception
	 *             if transport or verification fails
	 */
	protected abstract PushResult push(String remote,
			List<RefSpec> specifications) throws Exception;

	/**
	 * Execute a push with local verification bypassed.
	 *
	 * @param remote
	 *            configured remote or URL
	 * @param specifications
	 *            requested reference mappings
	 * @return push result
	 * @throws Exception
	 *             if the adapter cannot execute the operation
	 */
	protected PushResult pushWithoutVerification(String remote,
			List<RefSpec> specifications) throws Exception {
		throw new UnsupportedOperationException();
	}

	/**
	 * Prepare the source history and empty destination.
	 *
	 * @throws Exception
	 *             if setup fails
	 */
	@Before
	public void preparePrePush() throws Exception {
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		db.updateRef("HEAD").link("refs/heads/main");
		try (Git git = Git.wrap(db)) {
			writeTrashFile("one", "one");
			git.add().addFilepattern("one").call();
			first = git.commit().setMessage("one").call();
			writeTrashFile("two", "two");
			git.add().addFilepattern("two").call();
			next = git.commit().setMessage("two").call();
			git.tag().setName("one").setAnnotated(false).setObjectId(first)
					.call();
		}
		try (Git bare = Git.init().setBare(true).setDirectory(
				new java.io.File(getTemporaryDirectory(), "pre-push.git"))
				.call()) {
			server = bare.getRepository();
			server.incrementOpen();
			addRepoToClose(server);
		}
		location = new URIish(server.getDirectory().toURI().toString())
				.toString();
		db.getConfig().setString("remote", "parent1", "url", location);
		db.getConfig().save();
	}

	/**
	 * Install the input-capturing hook.
	 *
	 * @param code
	 *            exit status
	 * @return script
	 * @throws IOException
	 *             if the script cannot be written
	 */
	protected Path installPrePush(int code) throws IOException {
		Path hook = db.getDirectory().toPath().resolve("hooks/pre-push");
		Files.createDirectories(hook.getParent());
		Files.writeString(hook, "#!/bin/sh\n"
				+ "printf '%s\\n' \"$#\" \"$@\" >\"$GIT_DIR/pre-push.args\"\n"
				+ "cat >\"$GIT_DIR/pre-push.input\"\nexit " + code + "\n");
		assertTrue(FS.DETECTED.setExecute(hook.toFile(), true));
		return hook;
	}

	private String input() throws IOException {
		return Files.readString(
				db.getDirectory().toPath().resolve("pre-push.input"));
	}

	private String arguments() throws IOException {
		return Files.readString(
				db.getDirectory().toPath().resolve("pre-push.args"));
	}

	private PushResult named(String... specifications) throws Exception {
		List<RefSpec> specs = java.util.Arrays.stream(specifications)
				.map(RefSpec::new).toList();
		db.getConfig().setStringList("remote", "parent1", "push",
				List.of(specifications));
		db.getConfig().save();
		return push("parent1", specs);
	}

	private void seed(String name, RevCommit value) throws Exception {
		try (Git git = Git.wrap(db)) {
			git.push().setRemote(location)
					.setRefSpecs(new RefSpec(value.name() + ":" + name)).call();
		}
	}

	/**
	 * Verify successful local hooks are bypassed.
	 *
	 * @throws Exception
	 *             if setup or operation fails
	 */
	@Test
	public void noVerifySkipsSuccessfulHook() throws Exception {
		bypassLocalHook(0);
	}

	/**
	 * Verify failing local hooks are bypassed.
	 *
	 * @throws Exception
	 *             if setup or operation fails
	 */
	@Test
	public void noVerifySkipsFailingHook() throws Exception {
		bypassLocalHook(7);
	}

	private void bypassLocalHook(int code) throws Exception {
		installPrePush(code);
		pushWithoutVerification("parent1",
				List.of(new RefSpec("HEAD:refs/heads/foreign")));
		assertEquals(next, server.resolve("refs/heads/foreign"));
		assertFalse(Files
				.exists(db.getDirectory().toPath().resolve("pre-push.input")));
	}

	/**
	 * Verify local bypass cannot disable receiving-repository verification.
	 *
	 * @throws Exception
	 *             if setup or operation fails
	 */
	@Test
	public void noVerifyStillRunsServerHooks() throws Exception {
		installPrePush(7);
		Path receiver = server.getDirectory().toPath()
				.resolve("hooks/pre-receive");
		Files.createDirectories(receiver.getParent());
		Files.writeString(receiver,
				"#!/bin/sh\ncat >/dev/null\necho rejected >\"$GIT_DIR/pre-receive.was-run\"\nexit 7\n");
		assertTrue(FS.DETECTED.setExecute(receiver.toFile(), true));
		PushResult result = pushWithoutVerification(location,
				List.of(new RefSpec("HEAD:refs/heads/foreign")));
		assertEquals(
				org.eclipse.jgit.transport.RemoteRefUpdate.Status.REJECTED_OTHER_REASON,
				result.getRemoteUpdate("refs/heads/foreign").getStatus());
		assertNull(server.exactRef("refs/heads/foreign"));
		assertTrue(Files.exists(
				server.getDirectory().toPath().resolve("pre-receive.was-run")));
		assertFalse(Files
				.exists(db.getDirectory().toPath().resolve("pre-push.input")));
	}

	private void upstream(String branch, String remote, String destination)
			throws IOException {
		db.getConfig().setString("branch", branch, "remote", remote);
		db.getConfig().setString("branch", branch, "merge", destination);
		db.getConfig().save();
	}

	private Repository otherRemote() throws Exception {
		try (Git git = Git.init().setBare(true).setDirectory(
				new java.io.File(getTemporaryDirectory(), "pre-push-other.git"))
				.call()) {
			Repository other = git.getRepository();
			other.incrementOpen();
			addRepoToClose(other);
			db.getConfig().setString("remote", "parent2", "url",
					new URIish(other.getDirectory().toURI().toString())
							.toString());
			db.getConfig().save();
			return other;
		}
	}

	/**
	 * Verify push without arguments follows its configured upstream.
	 *
	 * @throws Exception
	 *             if setup or operation fails
	 */
	@Test
	public void defaultPushUsesUpstreamBranchAndRemote() throws Exception {
		seed("refs/heads/foreign", first);
		try (Git git = Git.wrap(db)) {
			git.checkout().setCreateBranch(true).setName("other").call();
		}
		upstream("other", "parent1", "refs/heads/foreign");
		db.getConfig().setString("push", null, "default", "upstream");
		db.getConfig().save();
		installPrePush(0);
		push(null, List.of());
		assertEquals("2\nparent1\n" + location + "\n", arguments());
		assertEquals("refs/heads/other " + next.name() + " refs/heads/foreign "
				+ first.name() + "\n", input());
		assertEquals(next, server.resolve("refs/heads/foreign"));
		assertNull(server.exactRef("refs/heads/other"));
	}

	/**
	 * Verify an explicitly named remote still uses push.default=upstream.
	 *
	 * @throws Exception
	 *             if setup or operation fails
	 */
	@Test
	public void namedPushUsesConfiguredUpstreamDestination() throws Exception {
		seed("refs/heads/foreign", first);
		upstream("main", "parent1", "refs/heads/foreign");
		db.getConfig().setString("push", null, "default", "upstream");
		db.getConfig().save();
		installPrePush(0);
		push("parent1", List.of());
		assertEquals("refs/heads/main " + next.name() + " refs/heads/foreign "
				+ first.name() + "\n", input());
		assertEquals(next, server.resolve("refs/heads/foreign"));
		assertNull(server.exactRef("refs/heads/main"));
	}

	/**
	 * Verify simple mode rejects a differently named upstream before hooks.
	 *
	 * @throws Exception
	 *             if setup or operation fails
	 */
	@Test
	public void simpleMismatchRejectsBeforePrePushHook() throws Exception {
		upstream("main", "parent1", "refs/heads/foreign");
		db.getConfig().setString("push", null, "default", "simple");
		db.getConfig().save();
		installPrePush(0);
		try {
			push(null, List.of());
			fail("Simple mode must reject a differently named upstream");
		} catch (Exception expected) {
			assertTrue(server.getRefDatabase().getRefs().isEmpty());
		}
		assertFalse(Files
				.exists(db.getDirectory().toPath().resolve("pre-push.input")));
	}

	/**
	 * Verify upstream mode requires an upstream before running hooks.
	 *
	 * @throws Exception
	 *             if setup or operation fails
	 */
	@Test
	public void missingUpstreamRejectsBeforePrePushHook() throws Exception {
		db.getConfig().setString("push", null, "default", "upstream");
		db.getConfig().save();
		installPrePush(0);
		try {
			push("parent1", List.of());
			fail("Upstream mode must require an upstream");
		} catch (Exception expected) {
			assertTrue(server.getRefDatabase().getRefs().isEmpty());
		}
		assertFalse(Files
				.exists(db.getDirectory().toPath().resolve("pre-push.input")));
	}

	/**
	 * Verify current mode overrides an upstream destination.
	 *
	 * @throws Exception
	 *             if setup or operation fails
	 */
	@Test
	public void explicitCurrentUsesLocalBranchName() throws Exception {
		upstream("main", "parent1", "refs/heads/foreign");
		db.getConfig().setString("push", null, "default", "current");
		db.getConfig().save();
		installPrePush(0);
		push(null, List.of());
		assertEquals("refs/heads/main " + next.name() + " refs/heads/main "
				+ ObjectId.zeroId().name() + "\n", input());
		assertEquals(next, server.resolve("refs/heads/main"));
		assertNull(server.exactRef("refs/heads/foreign"));
	}

	/**
	 * Verify remote.pushDefault routes the push to another repository.
	 *
	 * @throws Exception
	 *             if setup or operation fails
	 */
	@Test
	public void globalPushRemoteOverridesFetchRemote() throws Exception {
		Repository other = otherRemote();
		upstream("main", "parent1", "refs/heads/main");
		db.getConfig().setString("remote", null, "pushDefault", "parent2");
		db.getConfig().setString("push", null, "default", "current");
		db.getConfig().save();
		installPrePush(0);
		push(null, List.of());
		assertEquals("2\nparent2\n"
				+ db.getConfig().getString("remote", "parent2", "url") + "\n",
				arguments());
		assertEquals(next, other.resolve("refs/heads/main"));
		assertTrue(server.getRefDatabase().getRefs().isEmpty());
	}

	/**
	 * Verify branch.pushRemote takes precedence over remote.pushDefault.
	 *
	 * @throws Exception
	 *             if setup or operation fails
	 */
	@Test
	public void branchPushRemoteOverridesGlobalPushRemote() throws Exception {
		Repository other = otherRemote();
		upstream("main", "parent1", "refs/heads/main");
		db.getConfig().setString("remote", null, "pushDefault", "parent2");
		db.getConfig().setString("branch", "main", "pushRemote", "parent1");
		db.getConfig().setString("push", null, "default", "current");
		db.getConfig().save();
		installPrePush(0);
		push(null, List.of());
		assertEquals("2\nparent1\n" + location + "\n", arguments());
		assertEquals(next, server.resolve("refs/heads/main"));
		assertTrue(other.getRefDatabase().getRefs().isEmpty());
	}

	/**
	 * Verify the source HEAD spelling and zero old ID.
	 *
	 * @throws Exception
	 *             if setup or operation fails
	 */
	@Test
	public void headPushReportsOriginalSourceAndZeroOldId() throws Exception {
		installPrePush(0);
		named("HEAD:refs/heads/foreign");
		assertEquals("HEAD " + next.name() + " refs/heads/foreign "
				+ ObjectId.zeroId().name() + "\n", input());
		assertEquals(next, server.resolve("refs/heads/foreign"));
	}

	/**
	 * Verify the configured remote name and location.
	 *
	 * @throws Exception
	 *             if setup or operation fails
	 */
	@Test
	public void namedRemoteArgumentsArePreserved() throws Exception {
		installPrePush(0);
		named("refs/heads/main:refs/heads/foreign");
		assertEquals("2\nparent1\n" + location + "\n", arguments());
	}

	/**
	 * Verify an existing remote's advertised ID is passed to the hook.
	 *
	 * @throws Exception
	 *             if setup or operation fails
	 */
	@Test
	public void updateReportsAdvertisedOldId() throws Exception {
		seed("refs/heads/foreign", first);
		installPrePush(0);
		named("refs/heads/main:refs/heads/foreign");
		assertEquals("refs/heads/main " + next.name() + " refs/heads/foreign "
				+ first.name() + "\n", input());
		assertEquals(next, server.resolve("refs/heads/foreign"));
	}

	/**
	 * Verify the documented deletion marker and object IDs.
	 *
	 * @throws Exception
	 *             if setup or operation fails
	 */
	@Test
	public void deletionUsesDeleteMarker() throws Exception {
		seed("refs/heads/prev", first);
		installPrePush(0);
		named(":refs/heads/prev");
		assertEquals("(delete) " + ObjectId.zeroId().name()
				+ " refs/heads/prev " + first.name() + "\n", input());
		assertNull(server.exactRef("refs/heads/prev"));
	}

	/**
	 * Verify tags and revision expressions retain their source spelling.
	 *
	 * @throws Exception
	 *             if setup or operation fails
	 */
	@Test
	public void nonBranchSourcesAreReported() throws Exception {
		installPrePush(0);
		named("refs/tags/one:refs/tags/tag1", "HEAD~:refs/heads/prev");
		assertEquals(
				Set.of("refs/tags/one " + first.name() + " refs/tags/tag1 "
						+ ObjectId.zeroId().name(),
						"HEAD~ " + first.name() + " refs/heads/prev "
								+ ObjectId.zeroId().name()),
				Set.copyOf(input().lines().toList()));
		assertEquals(first, server.resolve("refs/tags/tag1"));
		assertEquals(first, server.resolve("refs/heads/prev"));
	}

	/**
	 * Verify direct URL pushes pass the URL as both arguments.
	 *
	 * @throws Exception
	 *             if setup or operation fails
	 */
	@Test
	public void literalUrlIsBothNameAndLocation() throws Exception {
		installPrePush(0);
		push(location, List.of(new RefSpec("HEAD:refs/heads/other")));
		assertEquals("2\n" + location + "\n" + location + "\n", arguments());
		assertEquals(next, server.resolve("refs/heads/other"));
	}

	/**
	 * Verify rejection occurs before destination refs are changed.
	 *
	 * @throws Exception
	 *             if setup or operation fails
	 */
	@Test
	public void failingHookRejectsBeforeRemoteChanges() throws Exception {
		installPrePush(1);
		try {
			named("HEAD:refs/heads/foreign");
			fail("Pre-push must reject");
		} catch (Exception expected) {
			assertNull(server.exactRef("refs/heads/foreign"));
		}
		assertEquals("HEAD " + next.name() + " refs/heads/foreign "
				+ ObjectId.zeroId().name() + "\n", input());
	}

	/**
	 * Verify a disabled executable bit skips the hook.
	 *
	 * @throws Exception
	 *             if setup or operation fails
	 */
	@Test
	public void nonExecutablePrePushIsSkipped() throws Exception {
		Path hook = installPrePush(7);
		assertTrue(FS.DETECTED.setExecute(hook.toFile(), false));
		named("HEAD:refs/heads/foreign");
		assertEquals(next, server.resolve("refs/heads/foreign"));
		assertFalse(Files
				.exists(db.getDirectory().toPath().resolve("pre-push.input")));
	}

	/**
	 * Verify a hook closing stdin immediately cannot cause a SIGPIPE failure.
	 *
	 * @throws Exception
	 *             if setup or operation fails
	 */
	@Test
	public void earlyHookExitWithThousandRefsSucceeds() throws Exception {
		var batch = db.getRefDatabase().newBatchUpdate();
		for (int n = 1001; n <= 2000; n++) {
			batch.addCommand(new ReceiveCommand(ObjectId.zeroId(), next,
					"refs/heads/b/" + n));
		}
		try (RevWalk walk = new RevWalk(db)) {
			batch.execute(walk, NullProgressMonitor.INSTANCE);
		}
		for (ReceiveCommand command : batch.getCommands()) {
			assertEquals(ReceiveCommand.Result.OK, command.getResult());
		}
		Path hook = installPrePush(0);
		Files.writeString(hook, "#!/bin/sh\nexit 0\n");
		named("refs/heads/b/*:refs/heads/b/*");
		assertEquals(1000, server.getRefDatabase()
				.getRefsByPrefix("refs/heads/b/").size());
	}
}
