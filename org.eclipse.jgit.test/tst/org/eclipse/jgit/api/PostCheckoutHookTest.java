/*
 * Copyright (C) 2006 Josh England, 2026 and others
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

import java.io.File;
import java.nio.file.Files;

import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.junit.JGitTestUtil;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.Before;
import org.junit.Test;
import org.junit.Assume;

/** Scenarios from C Git t5403-post-checkout-hook.sh. */
public class PostCheckoutHookTest extends RepositoryTestCase {
	private Git git;

	private RevCommit two;

	private RevCommit onto;

	private RevCommit three;

	private File args;

	private RevCommit commit(String name) throws Exception {
		writeTrashFile(name + ".t", name);
		git.add().addFilepattern(name + ".t").call();
		RevCommit result = git.commit().setMessage(name).call();
		git.tag().setName(name).setAnnotated(false).call();
		return result;
	}

	@Before
	public void setupT5403Graph() throws Exception {
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		db.updateRef(Constants.HEAD).link("refs/heads/main");
		git = Git.wrap(db);
		commit("one");
		two = commit("two");
		onto = commit("rebase-on-me");
		git.reset().setMode(ResetCommand.ResetType.HARD).setRef(two.name())
				.call();
		three = commit("three");
		args = new File(db.getDirectory(), "post-checkout.args");
		File hook = new File(db.getDirectory(), "hooks/post-checkout");
		JGitTestUtil.write(hook,
				"#!/bin/sh\nprintf 'old=%s new=%s flag=%s\\n' \"$1\" \"$2\" \"$3\" > \"$GIT_DIR/post-checkout.args\"\n");
		assertTrue(FS.DETECTED.setExecute(hook, true));
	}

	private void check(ObjectId oldHead, ObjectId newHead, boolean branch)
			throws Exception {
		assertEquals(
				"old=" + oldHead.name() + " new=" + newHead.name() + " flag="
						+ (branch ? "1" : "0") + "\n",
				Files.readString(args.toPath()));
	}

	@Test
	public void targetBranchRebaseMatchesNativeBranchScenario()
			throws Exception {
		git.branchCreate().setName("rebase-fast-forward").setStartPoint("three")
				.call();
		git.checkout().setName("two").call();
		Files.delete(args.toPath());
		git.rebase().setUpstream("HEAD").setBranch("rebase-fast-forward").call();
		check(two, three, true);
	}

	@Test
	public void targetBranchRebaseDoesNotOverwriteUntrackedFile()
			throws Exception {
		git.branchCreate().setName("rebase-fast-forward").setStartPoint("three")
				.call();
		git.checkout().setName("two").call();
		Files.delete(args.toPath());
		writeTrashFile("three.t", "untracked");
		assertEquals(RebaseResult.Status.CONFLICTS,
				git.rebase().setUpstream("HEAD").setBranch("rebase-fast-forward")
						.call().getStatus());
		assertFalse(args.exists());
		assertEquals(two, db.resolve(Constants.HEAD));
		assertEquals("untracked", Files
				.readString(new File(db.getWorkTree(), "three.t").toPath()));
	}

	@Test
	public void unchangedHeadStillNotifies() throws Exception {
		git.checkout().setName("main").call();
		check(three, three, true);
	}

	@Test
	public void branchCreationNotifies() throws Exception {
		git.checkout().setCreateBranch(true).setName("new1").call();
		check(three, three, true);
	}

	@Test
	public void changedHeadHasCorrectArguments() throws Exception {
		git.checkout().setName("two").call();
		check(three, two, true);
	}

	@Test
	public void fileCheckoutKeepsHeadAndUsesZeroFlag() throws Exception {
		git.checkout().setStartPoint("main").addPath("three.t").call();
		check(three, three, false);
	}

	@Test
	public void rebaseRewindNotifiesWithOriginalAndOntoHeads()
			throws Exception {
		git.rebase().setUpstream("rebase-on-me").call();
		check(three, onto, true);
	}

	@Test
	public void fastForwardRebaseNotifies() throws Exception {
		git.checkout().setName("two").call();
		Files.delete(args.toPath());
		git.rebase().setUpstream("rebase-on-me").call();
		check(two, onto, true);
	}

	@Test
	public void failedBranchCheckoutKeepsUntrackedFileAndDoesNotNotify()
			throws Exception {
		git.checkout().setName("two").call();
		Files.delete(args.toPath());
		writeTrashFile("three.t", "untracked");
		assertThrows(CheckoutConflictException.class,
				() -> git.checkout().setName("main").call());
		assertEquals("untracked", Files
				.readString(new File(db.getWorkTree(), "three.t").toPath()));
		assertFalse(args.exists());
		assertEquals(two, db.resolve(Constants.HEAD));
	}

	private File cloneTemplate() throws Exception {
		File templates = new File(db.getWorkTree(), "templates");
		File hook = new File(templates, "hooks/post-checkout");
		Files.createDirectories(hook.toPath().getParent());
		Files.copy(new File(db.getDirectory(), "hooks/post-checkout").toPath(),
				hook.toPath(),
				java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
		assertTrue(FS.DETECTED.setExecute(hook, true));
		return templates;
	}

	@Test
	public void cloneTemplateHookReceivesZeroAndClonedHead() throws Exception {
		File destination = new File(db.getWorkTree(), "clone");
		try (Git cloned = Git.cloneRepository()
				.setURI(db.getWorkTree().toURI().toString())
				.setDirectory(destination).setTemplateDirectory(cloneTemplate())
				.call()) {
			assertEquals(
					"old=" + ObjectId.zeroId().name() + " new=" + three.name()
							+ " flag=1\n",
					Files.readString(
							new File(cloned.getRepository().getDirectory(),
									"post-checkout.args").toPath()));
			assertEquals(three, cloned.getRepository().resolve(Constants.HEAD));
		}
	}

	@Test
	public void noCheckoutCloneDoesNotRunTemplateHook() throws Exception {
		try (Git cloned = Git.cloneRepository()
				.setURI(db.getWorkTree().toURI().toString())
				.setDirectory(new File(db.getWorkTree(), "clone"))
				.setTemplateDirectory(cloneTemplate()).setNoCheckout(true)
				.call()) {
			assertFalse(new File(cloned.getRepository().getDirectory(),
					"post-checkout.args").exists());
			assertTrue(new File(cloned.getRepository().getDirectory(),
					"hooks/post-checkout").exists());
		}
	}

	@Test
	public void bareCloneDoesNotRunTemplateHook() throws Exception {
		try (Git cloned = Git.cloneRepository()
				.setURI(db.getWorkTree().toURI().toString())
				.setDirectory(new File(db.getWorkTree(), "clone.git"))
				.setTemplateDirectory(cloneTemplate()).setBare(true).call()) {
			assertTrue(cloned.getRepository().isBare());
			assertFalse(new File(cloned.getRepository().getDirectory(),
					"post-checkout.args").exists());
			assertTrue(new File(cloned.getRepository().getDirectory(),
					"hooks/post-checkout").exists());
		}
	}
}
