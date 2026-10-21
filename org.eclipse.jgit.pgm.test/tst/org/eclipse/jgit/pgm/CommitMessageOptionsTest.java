/*
 * Copyright (C) 2026, and others
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.pgm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import java.nio.file.Files;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.CLIRepositoryTestCase;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.Before;
import org.junit.Test;

/** t7504 verification option ordering and no-editor cleanup policy. */
public class CommitMessageOptionsTest extends CLIRepositoryTestCase {
	private RevCommit initial;
	@Before
	public void stage() throws Exception {
		org.junit.Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		try (Git git = Git.wrap(db)) {
			writeTrashFile("file", "initial\n"); git.add().addFilepattern("file").call();
			initial = git.commit().setMessage("initial").call();
			writeTrashFile("file", "staged\n"); git.add().addFilepattern("file").call();
		}
		var script = db.getDirectory().toPath().resolve("hooks/commit-msg");
		Files.createDirectories(script.getParent());
		Files.writeString(script, "#!/bin/sh\necho invoked >\"$GIT_DIR/invoked\"\nexit 7\n");
		assertTrue(FS.DETECTED.setExecute(script.toFile(), true));
	}

	@Test
	public void verifyAfterShortBypassRestoresVerification() throws Exception {
		assertThrows(Exception.class, () -> execute("git commit -n --verify -m rejected"));
		assertEquals(initial, db.resolve("HEAD"));
		assertTrue(Files.exists(db.getDirectory().toPath().resolve("invoked")));
	}

	@Test
	public void bypassAfterVerifySkipsVerification() throws Exception {
		execute("git commit --verify -n -m accepted");
		assertFalse(Files.exists(db.getDirectory().toPath().resolve("invoked")));
	}

	@Test
	public void invalidCleanupStopsBeforeHooks() throws Exception {
		assertThrows(Exception.class, () -> execute("git commit --cleanup nonsense -m rejected"));
		assertEquals(initial, db.resolve("HEAD"));
		assertFalse(Files.exists(db.getDirectory().toPath().resolve("invoked")));
	}

	@Test
	public void scissorsWithoutEditorKeepsCutLine() throws Exception {
		String input = "subject\n# ------------------------ >8 ------------------------\nretained";
		Commit command = new Commit();
		command.initRaw(db, null, new java.io.ByteArrayInputStream(new byte[0]),
				new java.io.ByteArrayOutputStream(), new java.io.ByteArrayOutputStream());
		command.execute(new String[] { "--no-verify", "--cleanup=scissors", "-m", input });
		try (RevWalk walk = new RevWalk(db)) {
			assertEquals(input + "\n", walk.parseCommit(db.resolve("HEAD")).getFullMessage());
		}
	}
}
