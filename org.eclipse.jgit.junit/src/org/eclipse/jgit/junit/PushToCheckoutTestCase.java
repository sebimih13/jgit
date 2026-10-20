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
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * C Git t5516 updateInstead and push-to-checkout scenarios shared by command
 * adapters. Every mutation uses a disposable repository.
 *
 * @since 7.8
 */
public abstract class PushToCheckoutTestCase extends RepositoryTestCase {
	/** Target repository. */
	protected Repository server;

	/** Original target commit. */
	protected RevCommit initial;

	/** Proposed target commit. */
	protected RevCommit next;

	private boolean force;

	/**
	 * Execute the caller's real push command.
	 *
	 * @param source
	 *            source repository
	 * @param uri
	 *            target repository
	 * @param updates
	 *            proposed updates
	 * @return the push result
	 * @throws Exception
	 *             if the command fails
	 */
	protected abstract PushResult doPush(Repository source, URIish uri,
			List<RemoteRefUpdate> updates) throws Exception;

	/**
	 * Prepare deployment.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Before
	public void prepareDeployment() throws Exception {
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		db.updateRef(Constants.HEAD).link("refs/heads/main");
		try (Git git = Git.wrap(db)) {
			writeTrashFile("path1", "stable\n");
			writeTrashFile("path2", "old\n");
			git.add().addFilepattern(".").call();
			initial = git.commit().setMessage("initial").call();
			File destination = new File(getTemporaryDirectory(), "deploy");
			try (Git clone = Git.cloneRepository()
					.setURI(db.getDirectory().toURI().toString())
					.setDirectory(destination).call()) {
				server = clone.getRepository();
				server.incrementOpen();
				addRepoToClose(server);
			}
			writeTrashFile("path2", "new\n");
			writeTrashFile("path3", "new file\n");
			git.add().addFilepattern(".").call();
			next = git.commit().setMessage("next").call();
		}
		configure("updateInstead");
	}

	/**
	 * Set the target current-branch policy.
	 *
	 * @param value
	 *            policy, or null to remove it
	 * @throws Exception
	 *             if configuration cannot be saved
	 */
	protected void configure(String value) throws Exception {
		if (value == null) {
			server.getConfig().unset("receive", null, "denyCurrentBranch");
		} else {
			server.getConfig().setString("receive", null, "denyCurrentBranch",
					value);
		}
		server.getConfig().setBoolean("receive", null, "autogc", false);
		server.getConfig().save();
	}

	/**
	 * Push one reference through the selected command adapter.
	 *
	 * @param ref
	 *            target reference
	 * @param id
	 *            proposed object ID
	 * @return command result
	 * @throws Exception
	 *             if the command fails
	 */
	protected PushResult push(String ref, ObjectId id) throws Exception {
		return doPush(db, new URIish(server.getDirectory().toURI().toString()),
				List.of(new RemoteRefUpdate(db, null, id, ref, force, null,
						null)));
	}

	private PushResult push() throws Exception {
		return push("refs/heads/main", next);
	}

	/**
	 * Install a hook that records its invocation environment.
	 *
	 * @param body
	 *            script commands after the recording preamble
	 * @return executable script
	 * @throws Exception
	 *             if the script cannot be written
	 */
	protected Path installHook(String body) throws Exception {
		Path script = server.getCommonDirectory().toPath()
				.resolve("hooks/push-to-checkout");
		Files.createDirectories(script.getParent());
		Files.writeString(script, "#!/bin/sh\n"
				+ "printf '%s\\n' \"$#\" \"$1\" >>\"$GIT_DIR/push-to-checkout.args\"\n"
				+ "pwd >\"$GIT_DIR/push-to-checkout.cwd\"\n"
				+ "printf '%s\\n' \"$GIT_WORK_TREE\" >\"$GIT_DIR/push-to-checkout.worktree\"\n"
				+ "cat >\"$GIT_DIR/push-to-checkout.stdin\"\n" + body);
		assertTrue(FS.DETECTED.setExecute(script.toFile(), true));
		return script;
	}

	private void installCheckoutHook(boolean unborn) throws Exception {
		installHook("git update-index -q --refresh &&\n"
				+ (unborn ? "git read-tree -u -m \"$1\"\n"
						: "git read-tree -u -m HEAD \"$1\"\n"));
	}

	private boolean hookRan(Repository workTree) {
		return Files.exists(workTree.getDirectory().toPath()
				.resolve("push-to-checkout.args"));
	}

	private void assertHookArguments(Repository workTree) throws Exception {
		assertEquals("1\n" + next.name() + "\n", Files.readString(workTree
				.getDirectory().toPath().resolve("push-to-checkout.args")));
		assertEquals("", Files.readString(workTree.getDirectory().toPath()
				.resolve("push-to-checkout.stdin")));
		assertEquals(server.getCommonDirectory().getCanonicalPath(),
				Files.readString(workTree.getDirectory().toPath()
						.resolve("push-to-checkout.cwd")).trim());
		assertEquals(workTree.getWorkTree().getCanonicalPath(),
				Files.readString(workTree.getDirectory().toPath()
						.resolve("push-to-checkout.worktree")).trim());
	}

	private void accepted(PushResult result, String ref) {
		assertEquals(result.getMessages(), RemoteRefUpdate.Status.OK,
				result.getRemoteUpdate(ref).getStatus());
	}

	private void accepted(PushResult result) {
		accepted(result, "refs/heads/main");
	}

	private void refused(PushResult result) throws Exception {
		assertEquals(RemoteRefUpdate.Status.REJECTED_OTHER_REASON,
				result.getRemoteUpdate("refs/heads/main").getStatus());
		assertEquals(initial, server.resolve(Constants.HEAD));
	}

	private Path path(String name) {
		return server.getWorkTree().toPath().resolve(name);
	}

	private void pristine(Repository workTree) throws Exception {
		assertEquals(next, workTree.resolve(Constants.HEAD));
		try (Git git = Git.wrap(workTree)) {
			assertTrue(git.status().call().isClean());
		}
		assertEquals("new\n", Files
				.readString(workTree.getWorkTree().toPath().resolve("path2")));
		assertEquals("new file\n", Files
				.readString(workTree.getWorkTree().toPath().resolve("path3")));
	}

	/**
	 * Clean work tree deploys without hook.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void cleanWorkTreeDeploysWithoutHook() throws Exception {
		accepted(push());
		pristine(server);
	}

	/**
	 * Stat only change is refreshed without content loss.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void statOnlyChangeIsRefreshedWithoutContentLoss() throws Exception {
		Files.setLastModifiedTime(path("path1"),
				FileTime.fromMillis(System.currentTimeMillis() + 100000));
		accepted(push());
		pristine(server);
	}

	/**
	 * Unstaged change refuses default deployment.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void unstagedChangeRefusesDefaultDeployment() throws Exception {
		Files.writeString(path("path1"), "changed\n");
		refused(push());
		assertEquals("changed\n", Files.readString(path("path1")));
	}

	/**
	 * Staged change refuses default deployment.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void stagedChangeRefusesDefaultDeployment() throws Exception {
		Files.writeString(path("path1"), "changed\n");
		Git.wrap(server).add().addFilepattern("path1").call();
		refused(push());
		assertTrue(Git.wrap(server).status().call().getChanged()
				.contains("path1"));
	}

	/**
	 * Untracked collision refuses default deployment.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void untrackedCollisionRefusesDefaultDeployment() throws Exception {
		Files.writeString(path("path3"), "untracked\n");
		refused(push());
		assertEquals("untracked\n", Files.readString(path("path3")));
	}

	/**
	 * Staged content matching target is still rejected.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void stagedContentMatchingTargetIsStillRejected() throws Exception {
		Files.writeString(path("path3"), "new file\n");
		Git.wrap(server).add().addFilepattern("path3").call();
		refused(push());
		assertTrue(
				Git.wrap(server).status().call().getAdded().contains("path3"));
	}

	/**
	 * Unrelated untracked file survives default deployment.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void unrelatedUntrackedFileSurvivesDefaultDeployment()
			throws Exception {
		Files.writeString(path("untracked"), "keep\n");
		accepted(push());
		assertEquals("keep\n", Files.readString(path("untracked")));
		assertEquals(next, server.resolve(Constants.HEAD));
	}

	/**
	 * Unborn work tree can be deployed without hook.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void unbornWorkTreeCanBeDeployedWithoutHook() throws Exception {
		server = createWorkRepository();
		server.updateRef(Constants.HEAD).link("refs/heads/main");
		configure("updateInstead");
		accepted(push());
		pristine(server);
	}

	/**
	 * Hook deploys pristine work tree.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void hookDeploysPristineWorkTree() throws Exception {
		installCheckoutHook(false);
		accepted(push());
		assertHookArguments(server);
		pristine(server);
	}

	/**
	 * Hook rejects conflicting work tree change.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void hookRejectsConflictingWorkTreeChange() throws Exception {
		installCheckoutHook(false);
		Files.writeString(path("path2"), "conflicting\n");
		refused(push());
		assertTrue(hookRan(server));
		assertEquals("conflicting\n", Files.readString(path("path2")));
		assertTrue(Git.wrap(server).status().call().getChanged().isEmpty());
	}

	/**
	 * Hook can preserve unrelated work tree and index changes.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void hookCanPreserveUnrelatedWorkTreeAndIndexChanges()
			throws Exception {
		installCheckoutHook(false);
		Files.writeString(path("path1"), "unrelated\n");
		Files.writeString(path("path5"), "irrelevant\n");
		Git.wrap(server).add().addFilepattern("path5").call();
		accepted(push());
		assertHookArguments(server);
		assertEquals(next, server.resolve(Constants.HEAD));
		assertEquals("unrelated\n", Files.readString(path("path1")));
		assertEquals("irrelevant\n", Files.readString(path("path5")));
		assertEquals(java.util.Set.of("path5"),
				Git.wrap(server).status().call().getAdded());
	}

	/**
	 * Hook deploys into unborn work tree.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void hookDeploysIntoUnbornWorkTree() throws Exception {
		server = createWorkRepository();
		server.updateRef(Constants.HEAD).link("refs/heads/main");
		configure("updateInstead");
		installCheckoutHook(true);
		accepted(push());
		assertHookArguments(server);
		pristine(server);
	}

	/**
	 * Refusing hook keeps ref index and work tree.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void refusingHookKeepsRefIndexAndWorkTree() throws Exception {
		installHook("exit 7\n");
		refused(push());
		assertHookArguments(server);
		assertEquals("old\n", Files.readString(path("path2")));
		assertTrue(Git.wrap(server).status().call().isClean());
	}

	/**
	 * Accepting hook owns checkout instead of default checkout.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void acceptingHookOwnsCheckoutInsteadOfDefaultCheckout()
			throws Exception {
		installHook("exit 0\n");
		accepted(push());
		assertHookArguments(server);
		assertEquals(next, server.resolve(Constants.HEAD));
		assertEquals("old\n", Files.readString(path("path2")));
	}

	/**
	 * Non executable hook falls back to default deployment.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void nonExecutableHookFallsBackToDefaultDeployment()
			throws Exception {
		assertTrue(FS.DETECTED.setExecute(installHook("exit 7\n").toFile(),
				false));
		accepted(push());
		assertFalse(hookRan(server));
		pristine(server);
	}

	/**
	 * Configured hook deploys without traditional hook.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void configuredHookDeploysWithoutTraditionalHook() throws Exception {
		Path script = installHook(
				"git update-index -q --refresh && git read-tree -u -m HEAD \"$1\"\n");
		Path configured = script.resolveSibling("deploy-configured");
		Files.move(script, configured);
		server.getConfig().setString("hook", "deploy", "event",
				"push-to-checkout");
		server.getConfig().setString("hook", "deploy", "command",
				"'" + configured + "'");
		server.getConfig().save();
		accepted(push());
		assertHookArguments(server);
		pristine(server);
	}

	/**
	 * Disabled configured hook falls back to default deployment.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void disabledConfiguredHookFallsBackToDefaultDeployment()
			throws Exception {
		server.getConfig().setString("hook", "deploy", "event",
				"push-to-checkout");
		server.getConfig().setString("hook", "deploy", "command", "exit 7");
		server.getConfig().setBoolean("hook", "deploy", "enabled", false);
		server.getConfig().save();
		accepted(push());
		pristine(server);
	}

	/**
	 * Other branch does not invoke deployment hook.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void otherBranchDoesNotInvokeDeploymentHook() throws Exception {
		installHook("exit 7\n");
		accepted(push("refs/heads/other", next), "refs/heads/other");
		assertFalse(hookRan(server));
		assertEquals(initial, server.resolve(Constants.HEAD));
	}

	/**
	 * Default policy refuses current branch without invoking hook.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void defaultPolicyRefusesCurrentBranchWithoutInvokingHook()
			throws Exception {
		configure(null);
		installHook("exit 0\n");
		refused(push());
		assertFalse(hookRan(server));
	}

	/**
	 * Explicit refuse policy does not invoke hook.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void explicitRefusePolicyDoesNotInvokeHook() throws Exception {
		configure("true");
		installHook("exit 0\n");
		refused(push());
		assertFalse(hookRan(server));
	}

	/**
	 * Ignore policy updates ref without checkout or hook.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void ignorePolicyUpdatesRefWithoutCheckoutOrHook() throws Exception {
		configure("false");
		installHook("exit 7\n");
		accepted(push());
		assertFalse(hookRan(server));
		assertEquals("old\n", Files.readString(path("path2")));
	}

	/**
	 * Warn policy updates ref without checkout and reports warning.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void warnPolicyUpdatesRefWithoutCheckoutAndReportsWarning()
			throws Exception {
		configure("warn");
		installHook("exit 7\n");
		PushResult result = push();
		accepted(result);
		assertTrue(
				result.getMessages().contains("updating the current branch"));
		assertFalse(hookRan(server));
	}

	/**
	 * Update hook can reject before deployment hook.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void updateHookCanRejectBeforeDeploymentHook() throws Exception {
		installHook("exit 0\n");
		Path update = server.getDirectory().toPath().resolve("hooks/update");
		Files.writeString(update, "#!/bin/sh\nexit 7\n");
		assertTrue(FS.DETECTED.setExecute(update.toFile(), true));
		refused(push());
		assertFalse(hookRan(server));
	}

	/**
	 * Receive fast forward policy rejects before deployment hook.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void receiveFastForwardPolicyRejectsBeforeDeploymentHook()
			throws Exception {
		accepted(push());
		server.getConfig().setBoolean("receive", null, "denyNonFastForwards",
				true);
		server.getConfig().save();
		installHook("exit 0\n");
		force = true;
		PushResult result = push("refs/heads/main", initial);
		assertEquals(RemoteRefUpdate.Status.REJECTED_OTHER_REASON,
				result.getRemoteUpdate("refs/heads/main").getStatus());
		assertEquals(next, server.resolve(Constants.HEAD));
		assertFalse(hookRan(server));
	}

	/**
	 * Current branch deletion is rejected before deployment hook.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void currentBranchDeletionIsRejectedBeforeDeploymentHook()
			throws Exception {
		installHook("exit 0\n");
		refused(push("refs/heads/main", ObjectId.zeroId()));
		assertFalse(hookRan(server));
	}

	/**
	 * Hook output is delivered on remote message channel.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void hookOutputIsDeliveredOnRemoteMessageChannel() throws Exception {
		installHook(
				"echo STDOUT push-to-checkout\necho STDERR push-to-checkout >&2\nexit 7\n");
		PushResult result = push();
		refused(result);
		assertTrue(result.getMessages().contains("STDOUT push-to-checkout"));
		assertTrue(result.getMessages().contains("STDERR push-to-checkout"));
	}

	/**
	 * Run native Git in a disposable fixture.
	 *
	 * @param repository
	 *            fixture repository
	 * @param args
	 *            native command arguments
	 * @throws Exception
	 *             if the command fails
	 */
	protected void nativeGit(Repository repository, String... args)
			throws Exception {
		java.util.ArrayList<String> command = new java.util.ArrayList<>();
		command.add("git");
		command.add("--git-dir=" + repository.getDirectory().getAbsolutePath());
		command.addAll(List.of(args));
		ProcessBuilder builder = new ProcessBuilder(command);
		builder.environment().remove("GIT_DIR");
		builder.environment().remove("GIT_WORK_TREE");
		builder.environment().remove("GIT_INDEX_FILE");
		builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
		builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
		builder.redirectErrorStream(true);
		Process process = builder.start();
		String output = new String(process.getInputStream().readAllBytes(),
				java.nio.charset.StandardCharsets.UTF_8);
		assertEquals(output, 0, process.waitFor());
	}

	private Repository linkedWorkTree(String name, boolean bare)
			throws Exception {
		if (bare) {
			Repository old = server;
			File bareDir = new File(getTemporaryDirectory(), "bare.git");
			nativeGit(old, "clone", "--bare",
					old.getWorkTree().getAbsolutePath(),
					bareDir.getAbsolutePath());
			server = new FileRepositoryBuilder().setGitDir(bareDir).build();
			addRepoToClose(server);
			configure("updateInstead");
		}
		File destination = new File(getTemporaryDirectory(), "linked-" + name);
		nativeGit(server, "worktree", "add", "-b", name,
				destination.getAbsolutePath(), initial.name());
		Repository linked = new FileRepositoryBuilder().setWorkTree(destination)
				.findGitDir(destination).build();
		addRepoToClose(linked);
		return linked;
	}

	/**
	 * Bare repositories do not invoke deployment hooks for their own HEAD.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void bareRepositoryDoesNotDeployItsOwnHead() throws Exception {
		server = createBareRepository();
		server.updateRef(Constants.HEAD).link("refs/heads/main");
		configure("updateInstead");
		installHook("exit 7\n");
		accepted(push());
		assertEquals(next, server.resolve(Constants.HEAD));
		assertFalse(hookRan(server));
	}

	/**
	 * Separate Git directories use the configured working tree.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void separateGitDirectoryDeploysConfiguredWorkTree()
			throws Exception {
		File gitDirectory = new File(getTemporaryDirectory(), "separate.git");
		File workTree = new File(getTemporaryDirectory(), "separate");
		nativeGit(server, "clone", "--separate-git-dir=" + gitDirectory,
				server.getWorkTree().getAbsolutePath(),
				workTree.getAbsolutePath());
		server = new FileRepositoryBuilder().setGitDir(gitDirectory)
				.setWorkTree(workTree).build();
		addRepoToClose(server);
		server.getConfig().setString("core", null, "worktree",
				workTree.getAbsolutePath());
		configure("updateInstead");
		accepted(push());
		pristine(server);
	}

	/**
	 * The main worktree can be deployed when receiving through a linked one.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void linkedReceiverCanDeployMainWorkTree() throws Exception {
		Repository linked = linkedWorkTree("linked", false);
		PushResult result = doPush(db,
				new URIish(linked.getDirectory().toURI().toString()),
				List.of(new RemoteRefUpdate(db, null, next, "refs/heads/main",
						false, null, null)));
		accepted(result);
		pristine(server);
		assertEquals(initial, linked.resolve(Constants.HEAD));
	}

	/**
	 * Refusal also protects branches checked out in linked worktrees.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void refusePolicyProtectsLinkedWorkTree() throws Exception {
		Repository linked = linkedWorkTree("linked", false);
		configure("refuse");
		installHook("exit 0\n");
		PushResult result = push("refs/heads/linked", next);
		assertEquals(RemoteRefUpdate.Status.REJECTED_OTHER_REASON,
				result.getRemoteUpdate("refs/heads/linked").getStatus());
		assertEquals(initial, linked.resolve(Constants.HEAD));
		assertFalse(hookRan(linked));
	}

	/**
	 * A push can deploy two independently checked-out branches.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void twoWorkTreesCanBeDeployedByOnePush() throws Exception {
		Repository linked = linkedWorkTree("linked", false);
		PushResult result = doPush(db,
				new URIish(server.getDirectory().toURI().toString()),
				List.of(new RemoteRefUpdate(db, null, next, "refs/heads/main",
						false, null, null),
						new RemoteRefUpdate(db, null, next, "refs/heads/linked",
								false, null, null)));
		accepted(result);
		accepted(result, "refs/heads/linked");
		pristine(server);
		pristine(linked);
	}

	/**
	 * A dirty linked target does not prevent another non-atomic deployment.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void dirtyLinkedWorkTreeDoesNotRejectIndependentDeployment()
			throws Exception {
		Repository linked = linkedWorkTree("linked", false);
		Files.writeString(linked.getWorkTree().toPath().resolve("path1"),
				"dirty\n");
		PushResult result = doPush(db,
				new URIish(server.getDirectory().toURI().toString()),
				List.of(new RemoteRefUpdate(db, null, next, "refs/heads/main",
						false, null, null),
						new RemoteRefUpdate(db, null, next, "refs/heads/linked",
								false, null, null)));
		accepted(result);
		assertEquals(RemoteRefUpdate.Status.REJECTED_OTHER_REASON,
				result.getRemoteUpdate("refs/heads/linked").getStatus());
		pristine(server);
		assertEquals(initial, linked.resolve(Constants.HEAD));
		assertEquals("dirty\n", Files
				.readString(linked.getWorkTree().toPath().resolve("path1")));
	}

	/**
	 * Missing linked working directories still protect their checked-out refs.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void missingLinkedWorkTreeStillProtectsItsBranch() throws Exception {
		Repository linked = linkedWorkTree("linked", false);
		Path original = linked.getWorkTree().toPath();
		Files.move(original, original.resolveSibling("disconnected"));
		configure("refuse");
		PushResult result = push("refs/heads/linked", next);
		assertEquals(RemoteRefUpdate.Status.REJECTED_OTHER_REASON,
				result.getRemoteUpdate("refs/heads/linked").getStatus());
		assertEquals(initial, linked.resolve(Constants.HEAD));
		assertFalse(Files.exists(original));
	}

	/**
	 * Default deployment cannot recreate a missing linked working directory.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void defaultDeploymentRefusesMissingLinkedWorkTree()
			throws Exception {
		Repository linked = linkedWorkTree("linked", false);
		Path original = linked.getWorkTree().toPath();
		Files.move(original, original.resolveSibling("disconnected"));
		PushResult result = push("refs/heads/linked", next);
		assertEquals(RemoteRefUpdate.Status.REJECTED_OTHER_REASON,
				result.getRemoteUpdate("refs/heads/linked").getStatus());
		assertEquals(initial, linked.resolve(Constants.HEAD));
		assertFalse(Files.exists(original));
	}

	/**
	 * Linked work tree receives default deployment.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void linkedWorkTreeReceivesDefaultDeployment() throws Exception {
		Repository linked = linkedWorkTree("linked", false);
		accepted(push("refs/heads/linked", next), "refs/heads/linked");
		pristine(linked);
		assertEquals(initial, server.resolve(Constants.HEAD));
	}

	/**
	 * Linked work tree hook receives its own index and work tree.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void linkedWorkTreeHookReceivesItsOwnIndexAndWorkTree()
			throws Exception {
		Repository linked = linkedWorkTree("linked", false);
		installCheckoutHook(false);
		accepted(push("refs/heads/linked", next), "refs/heads/linked");
		assertHookArguments(linked);
		pristine(linked);
		assertEquals(initial, server.resolve(Constants.HEAD));
	}

	/**
	 * Bare repository linked work tree can be deployed.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void bareRepositoryLinkedWorkTreeCanBeDeployed() throws Exception {
		Repository linked = linkedWorkTree("linked", true);
		accepted(push("refs/heads/linked", next), "refs/heads/linked");
		pristine(linked);
	}

	/**
	 * Bare repository unborn head does not hide linked work tree.
	 *
	 * @throws Exception
	 *             if the fixture or command fails
	 */
	@Test
	public void bareRepositoryUnbornHeadDoesNotHideLinkedWorkTree()
			throws Exception {
		Repository linked = linkedWorkTree("linked", true);
		server.updateRef(Constants.HEAD).link("refs/heads/unborn");
		accepted(push("refs/heads/linked", next), "refs/heads/linked");
		pristine(linked);
	}
}
