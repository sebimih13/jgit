/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.pgm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.CLIRepositoryTestCase;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.UserConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.Before;
import org.junit.Test;

/** t7503 author selection and invocation-prefix checks through the CLI. */
public class CommitInvocationTest extends CLIRepositoryTestCase {
	private RevCommit initial;

	@Before
	public void stagedChange() throws Exception {
		org.junit.Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		try (Git git = Git.wrap(db)) {
			writeTrashFile("file", "initial\n");
			git.add().addFilepattern("file").call();
			initial = git.commit().setMessage("initial")
					.setAuthor(new PersonIdent("Original Author", "original@example.invalid"))
					.call();
			writeTrashFile("file", "staged\n");
			git.add().addFilepattern("file").call();
		}
	}

	private void hook(String body) throws Exception {
		var p = db.getDirectory().toPath().resolve("hooks/pre-commit");
		Files.createDirectories(p.getParent());
		Files.writeString(p, "#!/bin/sh\n" + body);
		assertTrue(FS.DETECTED.setExecute(p.toFile(), true));
	}

	private void authorEnvironment() {
		mockSystemReader.setProperty("GIT_AUTHOR_NAME", "New Author");
		mockSystemReader.setProperty("GIT_AUTHOR_EMAIL", "newauthor@example.com");
		db.getConfig().uncache(UserConfig.KEY);
	}

	private void requireAuthor(String name, String email) throws Exception {
		hook("test \"$GIT_AUTHOR_NAME\" = '" + name + "' && "
				+ "test \"$GIT_AUTHOR_EMAIL\" = '" + email + "'\n");
	}

	private RevCommit head() throws Exception {
		try (RevWalk walk = new RevWalk(db)) {
			return walk.parseCommit(db.resolve("HEAD"));
		}
	}

	@Test
	public void nativeAuthorSequenceRejectsDefaultThenAcceptsEnvironmentAndOption()
			throws Exception {
		hook("printf 'check-author\\n' >>\"$GIT_DIR/author-sequence\"\n"
				+ "test \"$GIT_AUTHOR_NAME\" = 'New Author' && "
				+ "test \"$GIT_AUTHOR_EMAIL\" = newauthor@example.com\n");
		assertThrows(Exception.class, () -> execute("git commit --allow-empty -m default"));
		assertEquals(initial, db.resolve("HEAD"));
		authorEnvironment();
		execute("git commit --allow-empty -m environment");
		RevCommit environmentCommit = head();
		assertEquals("New Author", environmentCommit.getAuthorIdent().getName());
		mockSystemReader.setProperty("GIT_AUTHOR_NAME", "Default Author");
		mockSystemReader.setProperty("GIT_AUTHOR_EMAIL", "default@example.invalid");
		db.getConfig().uncache(UserConfig.KEY);
		execute("git commit --allow-empty --author 'New Author <newauthor@example.com>' -m option");
		assertEquals(environmentCommit, head().getParent(0));
		assertEquals("newauthor@example.com", head().getAuthorIdent().getEmailAddress());
		assertEquals("check-author\ncheck-author\ncheck-author\n",
				Files.readString(db.getDirectory().toPath().resolve("author-sequence")));
	}

	@Test
	public void environmentSelectsAuthorSeenByPreCommit() throws Exception {
		authorEnvironment();
		requireAuthor("New Author", "newauthor@example.com");
		execute("git commit -m 'from environment'");
		assertEquals("New Author", head().getAuthorIdent().getName());
		assertEquals("newauthor@example.com", head().getAuthorIdent().getEmailAddress());
	}

	@Test
	public void explicitAuthorOverridesEnvironment() throws Exception {
		authorEnvironment();
		requireAuthor("Explicit Author", "explicit@example.invalid");
		execute("git commit --author 'Explicit Author <explicit@example.invalid>' -m explicit");
		assertEquals("Explicit Author", head().getAuthorIdent().getName());
		assertEquals("explicit@example.invalid", head().getAuthorIdent().getEmailAddress());
		// --author selects identity, not an epoch timestamp.
		assertEquals(mockSystemReader.getCurrentTime() / 1000,
				head().getAuthorIdent().getWhenAsInstant().getEpochSecond());
	}

	@Test
	public void amendRetainsOriginalAuthorDespiteEnvironment() throws Exception {
		authorEnvironment();
		requireAuthor("Original Author", "original@example.invalid");
		execute("git commit --amend -m amended");
		assertEquals(initial.getAuthorIdent().toExternalString(), head().getAuthorIdent().toExternalString());
	}

	@Test
	public void subdirectoryPrefixIsExportedWhileHookRunsAtRoot() throws Exception {
		var directory = db.getWorkTree().toPath().resolve("success");
		Files.createDirectories(directory);
		mockSystemReader.setProperty("user.dir", directory.toString());
		hook("test \"$GIT_PREFIX\" = success/ && test -f file\n");
		execute("git commit -m 'from success'");
		assertEquals(initial, head().getParent(0));
	}

	@Test
	public void differentSubdirectoryPrefixRejectsCommit() throws Exception {
		var directory = db.getWorkTree().toPath().resolve("fail");
		Files.createDirectories(directory);
		mockSystemReader.setProperty("user.dir", directory.toString());
		hook("test \"$GIT_PREFIX\" = success/\n");
		assertThrows(Exception.class, () -> execute("git commit -m rejected"));
		assertEquals(initial, db.resolve("HEAD"));
	}

	@Test
	public void rootInvocationExportsPresentEmptyPrefix() throws Exception {
		mockSystemReader.setProperty("user.dir", db.getWorkTree().getAbsolutePath());
		hook("test \"${GIT_PREFIX+x}\" = x && test -z \"$GIT_PREFIX\"\n");
		execute("git commit -m root");
	}
	@Test
	public void noVerifyRetainsPrefixForPreparationAndNotification() throws Exception {
		var directory = db.getWorkTree().toPath().resolve("success");
		Files.createDirectories(directory);
		mockSystemReader.setProperty("user.dir", directory.toString());
		hook("exit 7\n");
		for (String name : java.util.List.of("prepare-commit-msg", "post-commit")) {
			var p = db.getDirectory().toPath().resolve("hooks/" + name);
			Files.writeString(p, "#!/bin/sh\ntest \"$GIT_PREFIX\" = success/ || exit 9\nprintf '%s\\n' \"$GIT_PREFIX\" >>\"$GIT_DIR/prefixes\"\n");
			assertTrue(FS.DETECTED.setExecute(p.toFile(), true));
		}
		execute("git commit --no-verify -m bypass");
		assertEquals("success/\nsuccess/\n", Files.readString(db.getDirectory().toPath().resolve("prefixes")));
	}

	@Test
	public void shortNoVerifyAliasBypassesRejectingHook() throws Exception {
		hook("exit 7\n");
		execute("git commit -n -m bypass");
		assertEquals(initial, head().getParent(0));
	}

	@Test
	public void emptyCommitRequiresExplicitAllowance() throws Exception {
		execute("git commit -m staged");
		RevCommit parent = head();
		assertThrows(Exception.class, () -> execute("git commit -m empty"));
		assertEquals(parent, db.resolve("HEAD"));
		execute("git commit --allow-empty -m empty");
		assertEquals(parent, head().getParent(0));
		assertEquals(parent.getTree(), head().getTree());
	}

}
