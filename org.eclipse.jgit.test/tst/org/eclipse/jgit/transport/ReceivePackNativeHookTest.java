/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/** Receive hook scenarios ported from C Git t5401-update-hooks.sh. */
public class ReceivePackNativeHookTest extends RepositoryTestCase {
	private Repository server;

	private TestProtocol<Object> protocol;

	private URIish uri;

	private RevCommit first;

	private RevCommit second;

	private boolean atomic;

	private boolean rejectFromJavaHook;

	private int javaPostUpdates;

	@Before
	public void preparePush() throws Exception {
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		server = createBareRepository();
		try (Git git = Git.wrap(db)) {
			writeTrashFile("a", "This is a test.\n");
			git.add().addFilepattern("a").call();
			first = git.commit().setMessage("setup").call();
			writeTrashFile("a", "We hope it works.\n");
			git.add().addFilepattern("a").call();
			second = git.commit().setMessage("modify").call();
		}
		protocol = new TestProtocol<>(null,
				(Object request, Repository repository) -> {
					ReceivePack pack = new ReceivePack(repository);
					pack.setAllowNonFastForwards(true);
					pack.setPreReceiveHook((receiver, commands) -> {
						if (rejectFromJavaHook) {
							commands.stream()
									.filter(c -> "refs/heads/main"
											.equals(c.getRefName()))
									.forEach(c -> c.setResult(
											ReceiveCommand.Result.REJECTED_OTHER_REASON,
											"Java callback"));
						}
					});
					pack.setPostReceiveHook((receiver,
							commands) -> javaPostUpdates += commands.size());
					return pack;
				});
		Transport.register(protocol);
		uri = protocol.register(new Object(), server);
		push("refs/heads/main", first, "refs/heads/tofail", second);
	}

	@After
	public void unregisterProtocol() {
		if (protocol != null) {
			Transport.unregister(protocol);
		}
	}

	private PushResult push(String ref, ObjectId id, String otherRef,
			ObjectId otherId) throws Exception {
		List<RemoteRefUpdate> updates = new ArrayList<>();
		updates.add(new RemoteRefUpdate(db, null, id, ref, true, null, null));
		if (otherRef != null) {
			updates.add(new RemoteRefUpdate(db, null, otherId, otherRef, true,
					null, null));
		}
		try (Transport transport = protocol.open(uri, db, "server")) {
			transport.setPushAtomic(atomic);
			return transport.push(NullProgressMonitor.INSTANCE, updates);
		}
	}

	private Path hook(String name, String body) throws Exception {
		Path path = server.getDirectory().toPath().resolve("hooks/" + name);
		Files.createDirectories(path.getParent());
		Files.writeString(path, "#!/bin/sh\n" + body);
		assertTrue(path.toFile().setExecutable(true));
		return path;
	}

	private void installHooks(String rejected) throws Exception {
		hook("pre-receive", "printf %s \"$@\" >\"$GIT_DIR/pre-receive.args\"\n"
				+ "cat >\"$GIT_DIR/pre-receive.stdin\"\n");
		hook("update", "echo \"$@\" >>\"$GIT_DIR/update.args\"\n"
				+ "cat >\"$GIT_DIR/update.stdin\"\n" + (rejected == null ? ""
						: "test \"$1\" != " + rejected + "\n"));
		hook("post-receive",
				"printf %s \"$@\" >\"$GIT_DIR/post-receive.args\"\n"
						+ "cat >\"$GIT_DIR/post-receive.stdin\"\n");
		hook("post-update", "echo \"$@\" >\"$GIT_DIR/post-update.args\"\n"
				+ "cat >\"$GIT_DIR/post-update.stdin\"\n");
	}

	private String record(String path) throws Exception {
		return Files.readString(server.getDirectory().toPath().resolve(path));
	}

	private boolean recorded(String path) {
		return Files.exists(server.getDirectory().toPath().resolve(path));
	}

	private String line(ObjectId oldId, ObjectId newId, String ref) {
		return oldId.name() + " " + newId.name() + " " + ref + "\n";
	}

	@Test
	public void partialRejectionFiltersPostNotifications() throws Exception {
		installHooks("refs/heads/tofail");
		PushResult result = push("refs/heads/main", second, "refs/heads/tofail",
				first);
		assertEquals(RemoteRefUpdate.Status.OK,
				result.getRemoteUpdate("refs/heads/main").getStatus());
		assertEquals(RemoteRefUpdate.Status.REJECTED_OTHER_REASON,
				result.getRemoteUpdate("refs/heads/tofail").getStatus());
		assertEquals(second, server.resolve("refs/heads/main"));
		assertEquals(second, server.resolve("refs/heads/tofail"));
		assertEquals(
				line(first, second, "refs/heads/main")
						+ line(second, first, "refs/heads/tofail"),
				record("pre-receive.stdin"));
		assertEquals("refs/heads/main " + first.name() + " " + second.name()
				+ "\n" + "refs/heads/tofail " + second.name() + " "
				+ first.name() + "\n", record("update.args"));
		assertEquals(line(first, second, "refs/heads/main"),
				record("post-receive.stdin"));
		assertEquals("refs/heads/main\n", record("post-update.args"));
		assertEquals("", record("pre-receive.args"));
		assertEquals("", record("post-receive.args"));
		assertEquals("", record("update.stdin"));
		assertEquals("", record("post-update.stdin"));
	}

	@Test
	public void preReceiveFailureRejectsAllUpdates() throws Exception {
		installHooks(null);
		hook("pre-receive", "cat >\"$GIT_DIR/pre-receive.stdin\"\nexit 7\n");
		PushResult result = push("refs/heads/main", second, "refs/heads/tofail",
				first);
		assertEquals(RemoteRefUpdate.Status.REJECTED_OTHER_REASON,
				result.getRemoteUpdate("refs/heads/main").getStatus());
		assertEquals(RemoteRefUpdate.Status.REJECTED_OTHER_REASON,
				result.getRemoteUpdate("refs/heads/tofail").getStatus());
		assertEquals(first, server.resolve("refs/heads/main"));
		assertFalse(recorded("update.args"));
		assertFalse(recorded("post-receive.stdin"));
		assertFalse(recorded("post-update.args"));
	}

	@Test
	public void postHookFailureCannotUndoUpdate() throws Exception {
		installHooks(null);
		hook("post-receive", "cat >\"$GIT_DIR/post-receive.stdin\"\nexit 7\n");
		hook("post-update",
				"echo \"$@\" >\"$GIT_DIR/post-update.args\"\nexit 9\n");
		PushResult result = push("refs/heads/main", second, null, null);
		assertEquals(RemoteRefUpdate.Status.OK,
				result.getRemoteUpdate("refs/heads/main").getStatus());
		assertEquals(second, server.resolve("refs/heads/main"));
		assertEquals(line(first, second, "refs/heads/main"),
				record("post-receive.stdin"));
		assertEquals("refs/heads/main\n", record("post-update.args"));
	}

	@Test
	public void creationAndDeletionUseZeroIds() throws Exception {
		installHooks(null);
		PushResult result = push("refs/heads/new", first, "refs/heads/tofail",
				ObjectId.zeroId());
		assertEquals(RemoteRefUpdate.Status.OK,
				result.getRemoteUpdate("refs/heads/new").getStatus());
		assertEquals(RemoteRefUpdate.Status.OK,
				result.getRemoteUpdate("refs/heads/tofail").getStatus());
		assertEquals(
				line(ObjectId.zeroId(), first, "refs/heads/new")
						+ line(second, ObjectId.zeroId(), "refs/heads/tofail"),
				record("post-receive.stdin"));
		assertEquals(null, server.resolve("refs/heads/tofail"));
	}

	@Test
	public void noOpPushDoesNotRunHooks() throws Exception {
		installHooks(null);
		push("refs/heads/main", first, null, null);
		assertFalse(recorded("pre-receive.stdin"));
		assertFalse(recorded("post-receive.stdin"));
	}

	@Test
	public void atomicPushRejectsEveryRefOnUpdateFailure() throws Exception {
		installHooks("refs/heads/tofail");
		atomic = true;
		PushResult result = push("refs/heads/main", second, "refs/heads/tofail",
				first);
		assertEquals(RemoteRefUpdate.Status.REJECTED_OTHER_REASON,
				result.getRemoteUpdate("refs/heads/main").getStatus());
		assertEquals(RemoteRefUpdate.Status.REJECTED_OTHER_REASON,
				result.getRemoteUpdate("refs/heads/tofail").getStatus());
		assertEquals(first, server.resolve("refs/heads/main"));
		assertFalse(recorded("post-receive.stdin"));
	}

	@Test
	public void hookOutputIsForwardedToClient() throws Exception {
		hook("pre-receive",
				"cat >/dev/null\necho STDOUT pre-receive\necho STDERR pre-receive >&2\n");
		PushResult result = push("refs/heads/main", second, null, null);
		assertTrue(result.getMessages().contains("STDOUT pre-receive"));
		assertTrue(result.getMessages().contains("STDERR pre-receive"));
	}

	@Test
	public void updateHooksSeeRefsBeforeBatchCommit() throws Exception {
		hook("update",
				"if test \"$1\" = refs/heads/tofail; then\n" + "grep -qx "
						+ first.name()
						+ " \"$GIT_DIR/refs/heads/main\" || exit 7\n" + "fi\n");
		PushResult result = push("refs/heads/main", second, "refs/heads/tofail",
				first);
		assertEquals(RemoteRefUpdate.Status.OK,
				result.getRemoteUpdate("refs/heads/main").getStatus());
		assertEquals(RemoteRefUpdate.Status.OK,
				result.getRemoteUpdate("refs/heads/tofail").getStatus());
		assertEquals(first, server.resolve("refs/heads/tofail"));
	}

	@Test
	public void preReceiveRunsBeforeUpdateHooks() throws Exception {
		hook("pre-receive", "cat >/dev/null\necho pre >\"$GIT_DIR/order\"\n");
		hook("update", "test -f \"$GIT_DIR/order\" || exit 7\n");
		PushResult result = push("refs/heads/main", second, null, null);
		assertEquals(RemoteRefUpdate.Status.OK,
				result.getRemoteUpdate("refs/heads/main").getStatus());
	}

	@Test(timeout = 60000)
	public void preReceiveMayIgnoreLargeInput() throws Exception {
		hook("pre-receive", "exit 0\n");
		List<RemoteRefUpdate> updates = new ArrayList<>();
		for (int i = 100; i <= 999; i++) {
			updates.add(new RemoteRefUpdate(db, null, second,
					"refs/heads/branch_" + i, true, null, null));
		}
		try (Transport transport = protocol.open(uri, db, "server")) {
			PushResult result = transport.push(NullProgressMonitor.INSTANCE,
					updates);
			assertEquals(900, result.getRemoteUpdates().size());
			for (RemoteRefUpdate update : result.getRemoteUpdates()) {
				assertEquals(RemoteRefUpdate.Status.OK, update.getStatus());
				assertEquals(second, server.resolve(update.getRemoteName()));
			}
		}
	}

	@Test
	public void nativeHooksCoexistWithJavaCallbacks() throws Exception {
		installHooks(null);
		rejectFromJavaHook = true;
		javaPostUpdates = 0;
		PushResult result = push("refs/heads/main", second, "refs/heads/tofail",
				first);
		assertEquals(RemoteRefUpdate.Status.REJECTED_OTHER_REASON,
				result.getRemoteUpdate("refs/heads/main").getStatus());
		assertEquals(RemoteRefUpdate.Status.OK,
				result.getRemoteUpdate("refs/heads/tofail").getStatus());
		assertEquals(line(second, first, "refs/heads/tofail"),
				record("pre-receive.stdin"));
		assertEquals(line(second, first, "refs/heads/tofail"),
				record("post-receive.stdin"));
		assertEquals(1, javaPostUpdates);
	}

	@Test
	public void configuredReceiveHookRuns() throws Exception {
		Path path = hook("notification",
				"cat >\"$GIT_DIR/pre-receive.stdin\"\n");
		server.getConfig().setString("hook", "notification", "event",
				"pre-receive");
		server.getConfig().setString("hook", "notification", "command",
				"'" + path + "'");
		push("refs/heads/main", second, null, null);
		assertEquals(line(first, second, "refs/heads/main"),
				record("pre-receive.stdin"));
	}

	@Test
	public void nonExecutableReceiveHookDoesNotReject() throws Exception {
		assertTrue(hook("pre-receive", "exit 7\n").toFile().setExecutable(false,
				false));
		PushResult result = push("refs/heads/main", second, null, null);
		assertEquals(RemoteRefUpdate.Status.OK,
				result.getRemoteUpdate("refs/heads/main").getStatus());
	}
}
