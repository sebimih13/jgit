/*
 * Copyright (C) 2010 Thomas Rast, 2026 and others
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
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RebaseTodoLine;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/** Native t5407 amend/rebase mappings and notification lifecycle cases. */
public class PostRewriteHookTest extends RepositoryTestCase {
	@Before
	public void prepareGraph() throws Exception {
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		graph();
	}

	private RevCommit amend(boolean suppress, boolean all) throws Exception {
		return git.commit().setAmend(true).setAll(all)
				.setNoPostRewrite(suppress).setMessage("D new message\n")
				.call();
	}

	private RebaseResult rebase(String upstream, String onto, Mode selected)
			throws Exception {
		mode = selected;
		RebaseCommand cmd = git.rebase().setUpstream(upstream);
		if (onto != null)
			cmd.setOnto(onto);
		if (mode != Mode.NORMAL)
			cmd.runInteractively(editor);
		return cmd.call();
	}

	private RebaseResult resume(RebaseCommand.Operation operation)
			throws Exception {
		RebaseCommand cmd = git.rebase().setOperation(operation);
		if (mode != Mode.NORMAL)
			cmd.runInteractively(editor);
		return cmd.call();
	}

	private Git git;

	private final Map<String, RevCommit> commits = new java.util.LinkedHashMap<>();

	private Path args;

	private Path data;

	private Mode mode = Mode.NORMAL;

	private boolean prepared;

	private enum Mode {
		NORMAL, PICKS, DROP, SQUASH, FIXUP, REWORD
	}

	private final RebaseCommand.InteractiveHandler editor = new RebaseCommand.InteractiveHandler() {
		@Override
		public void prepareSteps(List<RebaseTodoLine> steps) {
			if (prepared)
				return;
			prepared = true;
			switch (mode) {
			case DROP:
				steps.remove(0);
				break;
			case SQUASH:
				setAction(steps.get(1), RebaseTodoLine.Action.SQUASH);
				break;
			case FIXUP:
				setAction(steps.get(1), RebaseTodoLine.Action.FIXUP);
				break;
			case REWORD:
				setAction(steps.get(0), RebaseTodoLine.Action.REWORD);
				break;
			default:
				break;
			}
		}

		@Override
		public String modifyCommitMessage(String message) {
			return "rewritten\n";
		}
	};

	private RevCommit commit(String name, String path, String content)
			throws Exception {
		write(path, content);
		RevCommit result = git.commit().setMessage(name + "\n").call();
		git.tag().setName(name).setAnnotated(false).call();
		commits.put(name, result);
		return result;
	}

	private void write(String path, String content) throws Exception {
		Files.writeString(db.getWorkTree().toPath().resolve(path), content);
		git.add().addFilepattern(path).call();
	}

	private void graph() throws Exception {
		db.updateRef(Constants.HEAD).link("refs/heads/main");
		git = Git.wrap(db);
		for (String name : List.of("A", "B", "C", "D"))
			commit(name, "foo", name + "\n");
		git.checkout().setName("A").call();
		commit("E", "bar", "E\n");
		commit("F", "foo", "F\n");
		git.checkout().setName("B").call();
		git.merge().include(commits.get("E")).setMessage("merge E\n").call();
		git.tag().setName("merge-E").setAnnotated(false).call();
		for (String name : List.of("G", "H", "I"))
			commit(name, name, name + "\n");
		git.checkout().setName("main").call();
		args = db.getDirectory().toPath().resolve("post-rewrite.args");
		data = db.getDirectory().toPath().resolve("post-rewrite.data");
		hook(0, true);
	}

	private void hook(int exit, boolean executable) throws Exception {
		Path path = db.getDirectory().toPath().resolve("hooks/post-rewrite");
		Files.createDirectories(path.getParent());
		Files.writeString(path,
				"#!/bin/sh\nprintf '%s\\n' \"$@\" >>\"$GIT_DIR/post-rewrite.args\"\ncat >\"$GIT_DIR/post-rewrite.data\"\nexit "
						+ exit + "\n");
		assertTrue(FS.DETECTED.setExecute(path.toFile(), executable));
	}

	private void missing() {
		assertFalse(Files.exists(args));
		assertFalse(Files.exists(data));
	}

	private void mapping(String command, List<? extends ObjectId> oldIds,
			List<? extends ObjectId> newIds) throws Exception {
		assertEquals(command + "\n", Files.readString(args));
		StringBuilder expected = new StringBuilder();
		for (int i = 0; i < oldIds.size(); i++)
			expected.append(oldIds.get(i).name()).append(' ')
					.append(newIds.get(i).name()).append('\n');
		assertEquals(expected.toString(), Files.readString(data));
	}

	private void twoMappings() throws Exception {
		mapping("rebase", List.of(commits.get("C"), commits.get("D")),
				List.of(db.resolve("HEAD^"), db.resolve("HEAD")));
		assertEquals(RepositoryState.SAFE, db.getRepositoryState());
	}

	private void resolve(String text) throws Exception {
		write("foo", text + "\n");
	}

	@Test
	public void amendSendsOldAndNewHeadsOnStdin() throws Exception {
		ObjectId old = db.resolve(Constants.HEAD);
		RevCommit result = amend(false, false);
		mapping("amend", List.of(old), List.of(result));
	}

	@Test
	public void noPostRewriteSuppressesAmendNotification() throws Exception {
		amend(true, false);
		missing();
	}

	@Test
	public void ordinaryCommitDoesNotNotify() throws Exception {
		write("foo", "new\n");
		git.commit().setMessage("ordinary\n").call();
		missing();
	}

	@Test
	public void notificationFailureDoesNotUndoAmend() throws Exception {
		hook(7, true);
		ObjectId old = db.resolve(Constants.HEAD);
		RevCommit result = amend(false, false);
		mapping("amend", List.of(old), List.of(result));
		assertEquals(result, db.resolve(Constants.HEAD));
	}

	@Test
	public void nonExecutableHookDoesNotRun() throws Exception {
		hook(1, false);
		amend(false, false);
		missing();
	}

	@Test
	public void configuredRewriteHookReceivesStdin() throws Exception {
		Files.delete(db.getDirectory().toPath().resolve("hooks/post-rewrite"));
		db.getConfig().setString("hook", "rewrite-check", "event",
				"post-rewrite");
		db.getConfig().setString("hook", "rewrite-check", "command",
				"printf '%s\\n' \"$@\" >.git/post-rewrite.args; cat >.git/post-rewrite.data; :");
		ObjectId old = db.resolve(Constants.HEAD);
		RevCommit result = amend(false, false);
		mapping("amend", List.of(old), List.of(result));
	}

	@Test
	public void successiveAmendsReportThePreviousHead() throws Exception {
		RevCommit first = amend(false, false);
		Files.delete(args);
		Files.delete(data);
		RevCommit second = amend(false, false);
		mapping("amend", List.of(first), List.of(second));
	}

	@Test
	public void postCommitRunsBeforeRewrite() throws Exception {
		Path postCommit = db.getDirectory().toPath()
				.resolve("hooks/post-commit");
		Files.writeString(postCommit,
				"#!/bin/sh\necho post-commit >\"$GIT_DIR/order\"\n");
		assertTrue(FS.DETECTED.setExecute(postCommit.toFile(), true));
		Path rewrite = db.getDirectory().toPath().resolve("hooks/post-rewrite");
		Files.writeString(rewrite,
				"#!/bin/sh\ntest \"$(cat \"$GIT_DIR/order\")\" = post-commit || exit 5\necho post-rewrite >>\"$GIT_DIR/order\"\ncat >/dev/null\n");
		amend(false, false);
		assertEquals("post-commit\npost-rewrite\n",
				Files.readString(db.getDirectory().toPath().resolve("order")));
	}

	@Test
	public void amendAllRetainsParentsAndReportsRewrite() throws Exception {
		Files.writeString(db.getWorkTree().toPath().resolve("foo"),
				"unstaged\n");
		ObjectId old = db.resolve(Constants.HEAD);
		RevCommit result = amend(false, true);
		mapping("amend", List.of(old), List.of(result));
		assertEquals(commits.get("C"), result.getParent(0));
		assertFalse(git.status().call().hasUncommittedChanges());
	}

	@Test
	public void continuedReplayReportsBothMappings() throws Exception {
		assertEquals(RebaseResult.Status.STOPPED,
				rebase("B", "A", Mode.NORMAL).getStatus());
		missing();
		resolve("C");
		assertTrue(resume(RebaseCommand.Operation.CONTINUE).getStatus()
				.isSuccessful());
		twoMappings();
	}

	@Test
	public void skipIntermediateCommitStillRecordsItsTarget() throws Exception {
		assertEquals(RebaseResult.Status.STOPPED,
				rebase("B", "A", Mode.NORMAL).getStatus());
		assertEquals(RebaseResult.Status.STOPPED,
				resume(RebaseCommand.Operation.SKIP).getStatus());
		missing();
		resolve("D");
		assertTrue(resume(RebaseCommand.Operation.CONTINUE).getStatus()
				.isSuccessful());
		twoMappings();
	}

	@Test
	public void skipLastCommitRecordsTheCurrentHead() throws Exception {
		git.reset().setMode(ResetCommand.ResetType.HARD).setRef("F").call();
		assertEquals(RebaseResult.Status.STOPPED,
				rebase("A", "D", Mode.NORMAL).getStatus());
		missing();
		assertTrue(resume(RebaseCommand.Operation.SKIP).getStatus()
				.isSuccessful());
		mapping("rebase", List.of(commits.get("E"), commits.get("F")),
				List.of(db.resolve("HEAD"), db.resolve("HEAD")));
	}

	@Test
	public void interactiveReplayReportsBothMappings() throws Exception {
		assertEquals(RebaseResult.Status.STOPPED,
				rebase("B", "A", Mode.PICKS).getStatus());
		missing();
		resolve("C");
		assertTrue(resume(RebaseCommand.Operation.CONTINUE).getStatus()
				.isSuccessful());
		twoMappings();
	}

	@Test
	public void interactiveDropDoesNotReportRemovedCommit() throws Exception {
		assertEquals(RebaseResult.Status.STOPPED,
				rebase("B", "A", Mode.DROP).getStatus());
		missing();
		resolve("D");
		assertTrue(resume(RebaseCommand.Operation.CONTINUE).getStatus()
				.isSuccessful());
		mapping("rebase", List.of(commits.get("D")),
				List.of(db.resolve("HEAD")));
	}

	@Test
	public void interactiveSquashMapsBothCommitsToFinalHead() throws Exception {
		assertEquals(RebaseResult.Status.STOPPED,
				rebase("B", "A", Mode.SQUASH).getStatus());
		missing();
		resolve("C");
		assertTrue(resume(RebaseCommand.Operation.CONTINUE).getStatus()
				.isSuccessful());
		mapping("amend\nrebase", List.of(commits.get("C"), commits.get("D")),
				List.of(db.resolve("HEAD"), db.resolve("HEAD")));
	}

	@Test
	public void interactiveFixupReportsAmendThenFinalMappings()
			throws Exception {
		assertTrue(rebase("B", null, Mode.FIXUP).getStatus().isSuccessful());
		mapping("amend\nrebase", List.of(commits.get("C"), commits.get("D")),
				List.of(db.resolve("HEAD"), db.resolve("HEAD")));
	}

	@Test
	public void interactiveRewordReportsAmendThenRebase() throws Exception {
		assertTrue(rebase("B", null, Mode.REWORD).getStatus().isSuccessful());
		mapping("amend\nrebase", List.of(commits.get("C"), commits.get("D")),
				List.of(db.resolve("HEAD^"), db.resolve("HEAD")));
	}

	@Test
	public void abortedRebaseDoesNotNotify() throws Exception {
		assertEquals(RebaseResult.Status.STOPPED,
				rebase("B", "A", Mode.NORMAL).getStatus());
		assertEquals(RebaseResult.Status.ABORTED,
				resume(RebaseCommand.Operation.ABORT).getStatus());
		missing();
		assertEquals(commits.get("D"), db.resolve(Constants.HEAD));
	}

	@Test
	public void upToDateRebaseDoesNotNotify() throws Exception {
		assertEquals(RebaseResult.Status.UP_TO_DATE,
				rebase("C", null, Mode.NORMAL).getStatus());
		missing();
	}

	@Test
	public void failedRebaseNotificationDoesNotLeaveRebaseState()
			throws Exception {
		hook(7, true);
		assertTrue(rebase("B", null, Mode.FIXUP).getStatus().isSuccessful());
		mapping("amend\nrebase", List.of(commits.get("C"), commits.get("D")),
				List.of(db.resolve("HEAD"), db.resolve("HEAD")));
		assertEquals(RepositoryState.SAFE, db.getRepositoryState());
	}

	private static void setAction(RebaseTodoLine step,
			RebaseTodoLine.Action action) {
		try {
			step.setAction(action);
		} catch (org.eclipse.jgit.errors.IllegalTodoFileModification e) {
			throw new AssertionError(e);
		}
	}

	/**
	 * C Git t7504: reword verifies the edited commit message.
	 *
	 * @throws Exception
	 *             if setup or execution fails
	 */
	@Test
	public void rewordInvokesCommitMessageHookAndAmendNotification()
			throws Exception {
		Path messageHook = db.getDirectory().toPath()
				.resolve("hooks/commit-msg");
		Files.writeString(messageHook, "#!/bin/sh\necho new message >\"$1\"\n");
		assertTrue(FS.DETECTED.setExecute(messageHook.toFile(), true));
		assertTrue(rebase("C", null, Mode.REWORD).getStatus().isSuccessful());
		try (var walk = new org.eclipse.jgit.revwalk.RevWalk(db)) {
			assertEquals("new message\n", walk
					.parseCommit(db.resolve(Constants.HEAD)).getFullMessage());
		}
		mapping("amend\nrebase", List.of(commits.get("D")),
				List.of(db.resolve(Constants.HEAD)));
	}
}
