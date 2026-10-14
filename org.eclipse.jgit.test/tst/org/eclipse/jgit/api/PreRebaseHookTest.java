/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jgit.api.errors.AbortedByHookException;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.RebaseTodoLine;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/** C Git t3413 and t3420 pre-rebase scenarios. */
public class PreRebaseHookTest extends RepositoryTestCase {
	@Before
	public void prepareGraph() throws Exception {
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		graph();
	}

	private String upstreamArgument() {
		return "main";
	}

	private RebaseResult rebase(boolean interactive, String branch,
			boolean bypass) throws Exception {
		return rebaseWithUpstream("main", interactive, branch, bypass, false);
	}

	private RebaseResult rebaseWithUpstream(String upstream,
			boolean interactive, String branch, boolean bypass, boolean force)
			throws Exception {
		RebaseCommand command = git.rebase().setUpstream(upstream)
				.setBranch(branch).setNoVerify(bypass).setForce(force);
		if (interactive) {
			command.runInteractively(PICKS);
		}
		return command.call();
	}

	private RebaseResult resume() throws Exception {
		return git.rebase().setOperation(RebaseCommand.Operation.CONTINUE)
				.call();
	}

	private Git git;

	private RevCommit initial;

	private RevCommit main;

	private RevCommit side;

	private Path record;

	private static final RebaseCommand.InteractiveHandler PICKS = new RebaseCommand.InteractiveHandler() {
		@Override
		public void prepareSteps(List<RebaseTodoLine> steps) {
		}

		@Override
		public String modifyCommitMessage(String message) {
			return message;
		}
	};

	private void graph() throws Exception {
		db.updateRef(Constants.HEAD).link("refs/heads/main");
		git = Git.wrap(db);
		write("file", "hello\n");
		initial = git.commit().setMessage("initial").call();
		write("file", "goodbye\n");
		main = git.commit().setMessage("second").call();
		git.checkout().setCreateBranch(true).setName("side")
				.setStartPoint(initial).call();
		write("git", "world\n");
		side = git.commit().setMessage("side").call();
		git.branchCreate().setName("test").call();
		git.checkout().setName("test").call();
		record = db.getDirectory().toPath().resolve("PRE-REBASE-INPUT");
	}

	private void write(String path, String text) throws Exception {
		Files.writeString(db.getWorkTree().toPath().resolve(path), text);
		git.add().addFilepattern(path).call();
	}

	private void hook(int code, boolean executable) throws Exception {
		Path file = db.getDirectory().toPath().resolve("hooks/pre-rebase");
		Files.createDirectories(file.getParent());
		Files.writeString(file,
				"#!/bin/sh\nprintf '%s,%s\\n' \"$1\" \"$2\" >>\"$GIT_DIR/PRE-REBASE-INPUT\"\nexit "
						+ code + "\n");
		assertTrue(FS.DETECTED.setExecute(file.toFile(), executable));
	}

	private void done(RebaseResult result) throws Exception {
		assertTrue(result.getStatus().isSuccessful());
		assertEquals("world\n",
				Files.readString(db.getWorkTree().toPath().resolve("git")));
		assertEquals("goodbye\n",
				Files.readString(db.getWorkTree().toPath().resolve("file")));
		assertEquals("refs/heads/test", db.getFullBranch());
		assertEquals(RepositoryState.SAFE, db.getRepositoryState());
	}

	private void args(String branch) throws Exception {
		assertEquals(upstreamArgument() + "," + (branch == null ? "" : branch)
				+ "\n", Files.readString(record));
	}

	@Test
	public void rebaseWithoutHook() throws Exception {
		done(rebase(false, null, false));
	}

	@Test
	public void interactiveRebaseWithoutHook() throws Exception {
		done(rebase(true, null, false));
	}

	@Test
	public void currentBranchArgument() throws Exception {
		hook(0, true);
		done(rebase(false, null, false));
		args(null);
	}

	@Test
	public void explicitCurrentBranchArgument() throws Exception {
		hook(0, true);
		done(rebase(false, "test", false));
		args("test");
	}

	@Test
	public void explicitOtherBranchArgument() throws Exception {
		hook(0, true);
		git.checkout().setName("main").call();
		done(rebase(false, "test", false));
		args("test");
	}

	@Test
	public void interactiveCurrentBranchArgument() throws Exception {
		hook(0, true);
		done(rebase(true, null, false));
		args(null);
	}

	@Test
	public void interactiveExplicitCurrentBranchArgument() throws Exception {
		hook(0, true);
		done(rebase(true, "test", false));
		args("test");
	}

	@Test
	public void interactiveExplicitOtherBranchArgument() throws Exception {
		hook(0, true);
		git.checkout().setName("main").call();
		done(rebase(true, "test", false));
		args("test");
	}

	private void rejected(boolean interactive) throws Exception {
		hook(1, true);
		assertThrows(AbortedByHookException.class,
				() -> rebase(interactive, null, false));
		assertEquals(side, db.resolve(Constants.HEAD));
		assertEquals("refs/heads/test", db.getFullBranch());
		assertEquals(RepositoryState.SAFE, db.getRepositoryState());
		assertFalse(
				new java.io.File(db.getDirectory(), "rebase-merge").exists());
		assertFalse(
				new java.io.File(db.getDirectory(), "rebase-apply").exists());
	}

	@Test
	public void rejectionStopsNormalRebase() throws Exception {
		rejected(false);
	}

	@Test
	public void rejectionStopsInteractiveRebase() throws Exception {
		rejected(true);
	}

	@Test
	public void noVerifyBypassesNormalRebaseHook() throws Exception {
		hook(1, true);
		done(rebase(false, null, true));
		assertFalse(Files.exists(record));
	}

	@Test
	public void noVerifyBypassesInteractiveRebaseHook() throws Exception {
		hook(1, true);
		done(rebase(true, null, true));
		assertFalse(Files.exists(record));
	}

	@Test
	public void rejectionRestoresAutostash() throws Exception {
		db.getConfig().setBoolean("rebase", null, "autoStash", true);
		Files.writeString(db.getWorkTree().toPath().resolve("file"),
				"changed\n");
		rejected(false);
		assertEquals("changed\n",
				Files.readString(db.getWorkTree().toPath().resolve("file")));
		assertTrue(git.status().call().getModified().contains("file"));
	}

	@Test
	public void dirtyWorktreeStopsBeforeHook() throws Exception {
		hook(1, true);
		Files.writeString(db.getWorkTree().toPath().resolve("file"),
				"changed\n");
		assertEquals(RebaseResult.Status.UNCOMMITTED_CHANGES,
				rebase(false, null, false).getStatus());
		assertFalse(Files.exists(record));
		assertEquals(side, db.resolve(Constants.HEAD));
	}

	@Test
	public void dirtyFastForwardStopsBeforeHook() throws Exception {
		git.reset().setMode(ResetCommand.ResetType.HARD).setRef(initial.name())
				.call();
		hook(1, true);
		Files.writeString(db.getWorkTree().toPath().resolve("file"),
				"changed\n");
		assertEquals(RebaseResult.Status.UNCOMMITTED_CHANGES,
				rebase(false, null, false).getStatus());
		assertFalse(Files.exists(record));
		assertEquals(initial, db.resolve(Constants.HEAD));
	}

	@Test
	public void upToDateNormalRebaseSkipsHook() throws Exception {
		git.checkout().setName("main").call();
		hook(1, true);
		assertEquals(RebaseResult.Status.UP_TO_DATE,
				rebase(false, null, false).getStatus());
		assertFalse(Files.exists(record));
	}

	@Test
	public void upToDateInteractiveRebaseStillRunsHook() throws Exception {
		git.checkout().setName("main").call();
		hook(1, true);
		assertThrows(AbortedByHookException.class,
				() -> rebase(true, null, false));
		args(null);
		assertEquals(main, db.resolve(Constants.HEAD));
	}

	@Test
	public void fastForwardCanBeRejectedBeforeCheckout() throws Exception {
		git.reset().setMode(ResetCommand.ResetType.HARD).setRef(initial.name())
				.call();
		hook(1, true);
		assertThrows(AbortedByHookException.class,
				() -> rebase(false, null, false));
		args(null);
		assertEquals(initial, db.resolve(Constants.HEAD));
		assertEquals("hello\n",
				Files.readString(db.getWorkTree().toPath().resolve("file")));
	}

	@Test
	public void nonExecutableHookIsIgnored() throws Exception {
		hook(1, false);
		done(rebase(false, null, false));
		assertFalse(Files.exists(record));
	}

	@Test
	public void explicitBranchRejectionDoesNotSwitchHead() throws Exception {
		git.checkout().setName("main").call();
		hook(1, true);
		assertThrows(AbortedByHookException.class,
				() -> rebase(false, "test", false));
		args("test");
		assertEquals("refs/heads/main", db.getFullBranch());
		assertEquals(main, db.resolve(Constants.HEAD));
	}

	@Test
	public void continuationDoesNotInvokeHookAgain() throws Exception {
		write("file", "local\n");
		git.commit().setMessage("local").call();
		hook(0, true);
		assertEquals(RebaseResult.Status.STOPPED,
				rebase(false, null, false).getStatus());
		args(null);
		write("file", "resolved\n");
		hook(1, true);
		assertTrue(resume().getStatus().isSuccessful());
		args(null);
		assertEquals(RepositoryState.SAFE, db.getRepositoryState());
	}

	private void forcedRejectionRestoresAutostash(boolean interactive)
			throws Exception {
		db.getConfig().setBoolean("rebase", null, "autoStash", true);
		Files.writeString(db.getWorkTree().toPath().resolve("file"),
				"changed\n");
		hook(1, true);
		assertThrows(AbortedByHookException.class,
				() -> rebaseWithUpstream("HEAD^", interactive, null, false,
						true));
		assertEquals("HEAD^,\n", Files.readString(record));
		assertEquals(side, db.resolve(Constants.HEAD));
		assertEquals("refs/heads/test", db.getFullBranch());
		assertEquals(RepositoryState.SAFE, db.getRepositoryState());
		assertFalse(
				new java.io.File(db.getDirectory(), "rebase-merge").exists());
		assertEquals("changed\n",
				Files.readString(db.getWorkTree().toPath().resolve("file")));
	}

	@Test
	public void forcedNormalRejectionRestoresAutostash() throws Exception {
		forcedRejectionRestoresAutostash(false);
	}

	@Test
	public void forcedInteractiveRejectionRestoresAutostash() throws Exception {
		forcedRejectionRestoresAutostash(true);
	}

	@Test
	public void invalidBranchDoesNotCreateAutostash() throws Exception {
		db.getConfig().setBoolean("rebase", null, "autoStash", true);
		Files.writeString(db.getWorkTree().toPath().resolve("file"),
				"changed\n");
		hook(1, true);
		assertThrows(org.eclipse.jgit.api.errors.RefNotFoundException.class,
				() -> rebase(false, "missing", false));
		assertFalse(Files.exists(record));
		assertFalse(
				new java.io.File(db.getDirectory(), "rebase-merge").exists());
		assertEquals(side, db.resolve(Constants.HEAD));
		assertEquals("changed\n",
				Files.readString(db.getWorkTree().toPath().resolve("file")));
	}

	@Test
	public void forceReplaysAnUpToDateCommit() throws Exception {
		hook(0, true);
		((org.eclipse.jgit.junit.MockSystemReader) org.eclipse.jgit.util.SystemReader
				.getInstance()).tick(60);
		assertEquals(RebaseResult.Status.OK,
				rebaseWithUpstream("HEAD^", false, null, false, true)
						.getStatus());
		assertEquals("HEAD^,\n", Files.readString(record));
		assertFalse(side.equals(db.resolve(Constants.HEAD)));
		assertEquals("hello\n",
				Files.readString(db.getWorkTree().toPath().resolve("file")));
		assertEquals("world\n",
				Files.readString(db.getWorkTree().toPath().resolve("git")));
		assertEquals("refs/heads/test", db.getFullBranch());
		assertEquals(RepositoryState.SAFE, db.getRepositoryState());
	}

	@Test
	public void forcePersistsAcrossContinuation() throws Exception {
		write("file", "local\n");
		git.commit().setMessage("local").call();
		hook(0, true);
		assertEquals(RebaseResult.Status.STOPPED,
				rebaseWithUpstream(upstreamArgument(), false, null, false, true)
						.getStatus());
		assertTrue(new java.io.File(db.getDirectory(),
				"rebase-merge/jgit-force-rebase").exists());
		write("file", "resolved\n");
		hook(1, true);
		assertEquals(RebaseResult.Status.OK, resume().getStatus());
		args(null);
		assertEquals(RepositoryState.SAFE, db.getRepositoryState());
	}

	@Test
	public void objectIdUpstreamKeepsOriginalArgumentDespiteDisplayName() throws Exception {
		hook(0, true);
		org.eclipse.jgit.lib.AnyObjectId upstream = main;
		done(git.rebase().setUpstream(upstream).setUpstreamName("friendly-upstream").call());
		assertEquals(main.name() + ",\n", Files.readString(record));
	}
}
