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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Independently prepared scenarios from C Git t5528-push-default.sh, including
 * centralized/triangular workflows and automatic upstream configuration.
 *
 * @since 7.8
 */
public abstract class PushDefaultTestCase extends RepositoryTestCase {
	/** Named scenario. */
	protected final String scenario;

	/** First receiving repository. */
	protected Repository parent1;

	/** Second receiving repository. */
	protected Repository parent2;

	/** Initial remote commit. */
	protected RevCommit initial;

	/** Current source commit. */
	protected RevCommit next;

	/**
	 * Create an adapter fixture.
	 *
	 * @param scenario
	 *            native scenario identifier
	 */
	protected PushDefaultTestCase(String scenario) {
		this.scenario = scenario;
	}

	/**
	 * Expanded policy/workflow scenarios; identifiers are also used by the
	 * independent native comparison.
	 *
	 * @return parameter rows
	 */
	public static List<Object[]> scenarios() {
		return Arrays.stream(new String[] { "upstream-configured",
				"upstream-no-remote", "upstream-no-merge",
				"upstream-other-remote", "current-ambiguous",
				"current-explicit", "current-origin", "current-single",
				"new-ambiguous-simple", "new-ambiguous-matching",
				"new-ambiguous-upstream", "new-ambiguous-current",
				"new-no-upstream-simple", "new-no-upstream-upstream",
				"matching-ignores-current", "nothing-tracked", "auto-upstream",
				"auto-simple", "matching-none", "ambiguous-simple",
				"ambiguous-matching", "ambiguous-upstream",
				"current-creates-branch", "existing-no-upstream-simple",
				"existing-no-upstream-upstream", "same-upstream", "same-simple",
				"different-upstream", "different-simple", "different-current",
				"central-current", "central-upstream", "central-simple",
				"central-matching", "triangular-current", "triangular-upstream",
				"triangular-simple", "triangular-matching", "default-same",
				"default-different", "default-triangular", "auto-current",
				"auto-no-remote-upstream", "auto-no-remote-simple",
				"auto-dry-run", "auto-up-to-date", "auto-hook-reject",
				"auto-server-reject", "auto-explicit-refspec",
				"auto-configured-refspec", "auto-existing-upstream",
				"auto-rebase-never", "auto-rebase-local", "auto-rebase-remote",
				"auto-rebase-always", "multiple-upstreams-simple",
				"multiple-upstreams-upstream", "auto-rebase-invalid",
				"single-upstream-no-remote-has-merge", "current-literal-remote",
				"current-relative-remote" }).map(name -> new Object[] { name })
				.toList();
	}

	/**
	 * Execute the adapter's actual push, opting into config-derived policy.
	 *
	 * @param remote
	 *            named remote, literal location, or null for discovery
	 * @param specs
	 *            explicit refspecs; empty for default policy
	 * @param dryRun
	 *            whether the push is a dry run
	 * @return whether the operation reported success
	 * @throws Exception
	 *             if the push fails
	 */
	protected abstract boolean executePush(String remote, List<RefSpec> specs,
			boolean dryRun) throws Exception;

	/**
	 * Whether the host intentionally logs and ignores invalid rebase policy.
	 *
	 * @return false for native/standalone behavior; IDE adapters may retain
	 *         their existing typed-config fallback and test its warning
	 */
	protected boolean ignoresInvalidRebasePolicy() {
		return false;
	}

	/**
	 * Prepare the native suite's two bare remotes and source history.
	 *
	 * @throws Exception
	 *             if setup fails
	 */
	@Before
	public void prepareDefaultPush() throws Exception {
		Assume.assumeTrue(FS.DETECTED instanceof FS_POSIX);
		db.getConfig().setBoolean("maintenance", null, "auto", false);
		db.getConfig().setInt("gc", null, "auto", 0);
		db.updateRef("HEAD").link("refs/heads/main");
		try (Git git = Git.wrap(db)) {
			writeTrashFile("one", "one\n");
			git.add().addFilepattern("one").call();
			initial = git.commit().setMessage("one").call();
			parent1 = receiver("parent1");
			parent2 = receiver("parent2");
			for (String name : List.of("parent1", "parent2")) {
				git.push().setRemote(name)
						.setRefSpecs(new RefSpec("HEAD:refs/heads/main"))
						.call();
			}
			writeTrashFile("two", "two\n");
			git.add().addFilepattern("two").call();
			next = git.commit().setMessage("two").call();
		}
	}

	private Repository receiver(String name) throws Exception {
		try (Git git = Git.init().setBare(true).setDirectory(
				new java.io.File(getTemporaryDirectory(), name + ".git"))
				.call()) {
			Repository result = git.getRepository();
			result.getConfig().setBoolean("maintenance", null, "auto", false);
			result.getConfig().setBoolean("receive", null, "autogc", false);
			result.getConfig().setInt("gc", null, "auto", 0);
			result.getConfig().save();
			result.incrementOpen();
			addRepoToClose(result);
			db.getConfig().setString("remote", name, "url",
					new URIish(result.getDirectory().toURI().toString())
							.toString());
			db.getConfig().save();
			return result;
		}
	}

	private void branch(String name) throws Exception {
		try (Git git = Git.wrap(db)) {
			git.checkout().setCreateBranch(true).setName(name).call();
		}
	}

	private void upstream(String branch, String remote, String destination) {
		db.getConfig().setString("branch", branch, "remote", remote);
		if (destination != null) {
			db.getConfig().setString("branch", branch, "merge",
					"refs/heads/" + destination);
		}
	}

	private static Map<String, ObjectId> refs(Repository repo)
			throws Exception {
		Map<String, ObjectId> result = new TreeMap<>();
		for (Ref ref : repo.getRefDatabase().getRefsByPrefix("refs/")) {
			result.put(ref.getName(), ref.getObjectId());
		}
		return result;
	}

	private void hook(Repository repo, String name) throws Exception {
		Path script = repo.getDirectory().toPath().resolve("hooks/" + name);
		Files.createDirectories(script.getParent());
		Files.writeString(script, "#!/bin/sh\ncat >/dev/null\nexit 7\n");
		assertTrue(FS.DETECTED.setExecute(script.toFile(), true));
	}

	private void seedFoo() throws Exception {
		var update = parent1.updateRef("refs/heads/foo");
		update.setNewObjectId(initial);
		assertEquals(org.eclipse.jgit.lib.RefUpdate.Result.NEW,
				update.update());
	}

	/**
	 * Compare complete receiving refs and branch configuration for one native
	 * scenario. The known native matching TODO is tested for its actual success
	 * behavior, rather than weakening or ignoring a Java assertion.
	 *
	 * @throws Exception
	 *             if setup or the tested behavior fails
	 */
	@Test
	public void nativePushDefaultScenario() throws Exception {
		Map<String, ObjectId> expected1 = new TreeMap<>(refs(parent1));
		Map<String, ObjectId> expected2 = new TreeMap<>(refs(parent2));
		String policy = null;
		String remote = null;
		String trackingBranch = null;
		String trackingTarget = null;
		boolean success = true;
		boolean dryRun = false;
		List<RefSpec> specs = List.of();
		String branch = "main";

		if (scenario.startsWith("central-")
				|| scenario.startsWith("triangular-")) {
			boolean triangular = scenario.startsWith("triangular-");
			policy = scenario.substring(scenario.indexOf('-') + 1);
			upstream("main", "parent1", "foo");
			db.getConfig().setString("remote", null, "pushDefault",
					triangular ? "parent2" : "parent1");
			seedFoo();
			expected1.put("refs/heads/foo", initial);
			success = !(triangular && policy.equals("upstream"))
					&& !(!triangular && policy.equals("simple"));
			if (success) {
				(triangular ? expected2 : expected1).put(
						"refs/heads/"
								+ (policy.equals("upstream") ? "foo" : "main"),
						next);
			}
		} else if (scenario.startsWith("new-ambiguous-")) {
			branch("new-branch");
			branch = "new-branch";
			policy = scenario.substring("new-ambiguous-".length());
			success = false;
		} else if (scenario.startsWith("new-no-upstream-")) {
			branch("new-branch");
			branch = "new-branch";
			upstream(branch, "parent1", null);
			policy = scenario.substring("new-no-upstream-".length());
			success = false;
		} else if (scenario.startsWith("ambiguous-")) {
			branch("ambiguous");
			branch = "ambiguous";
			try (Git git = Git.wrap(db)) {
				git.tag().setName(branch).setAnnotated(false).call();
			}
			upstream(branch, "parent1", branch);
			policy = scenario.substring("ambiguous-".length());
			if (policy.equals("matching") || policy.equals("upstream")) {
				var existing = parent1.updateRef("refs/heads/ambiguous");
				if (policy.equals("upstream")) {
					try (Git git = Git.wrap(db)) {
						git.push().setRemote("parent1").setRefSpecs(
								new RefSpec("HEAD:refs/heads/ambiguous")).call();
					}
				} else {
					existing.setNewObjectId(initial);
					assertEquals(org.eclipse.jgit.lib.RefUpdate.Result.NEW,
							existing.update());
				}
				expected1.put("refs/heads/ambiguous", next);
			}
			expected1.put(policy.equals("matching") ? "refs/heads/main"
					: "refs/heads/ambiguous", next);
		} else if (scenario.startsWith("existing-no-upstream-")) {
			upstream("main", "parent1", null);
			policy = scenario.substring("existing-no-upstream-".length());
			success = false;
		} else if (scenario.startsWith("same-")) {
			upstream("main", "parent1", "main");
			policy = scenario.substring("same-".length());
			expected1.put("refs/heads/main", next);
		} else if (scenario.startsWith("different-")) {
			upstream("main", "parent1", "foo");
			policy = scenario.substring("different-".length());
			success = !policy.equals("simple");
			if (success)
				expected1.put(
						"refs/heads/"
								+ (policy.equals("upstream") ? "foo" : "main"),
						next);
		} else if (scenario.startsWith("default-")) {
			boolean triangular = scenario.equals("default-triangular");
			upstream("main", "parent1",
					scenario.equals("default-same") ? "main" : "foo");
			db.getConfig().setString("remote", null, "pushDefault",
					triangular ? "parent2" : "parent1");
			success = !scenario.equals("default-different");
			if (success)
				(triangular ? expected2 : expected1).put("refs/heads/main",
						next);
		} else if (scenario.startsWith("multiple-upstreams-")) {
			policy = scenario.substring("multiple-upstreams-".length());
			upstream("main", "parent1", "main");
			db.getConfig().setStringList("branch", "main", "merge",
					List.of("refs/heads/main", "refs/heads/foo"));
			success = false;
		} else if (scenario.startsWith("auto-")) {
			branch("new-branch");
			branch = "new-branch";
			policy = scenario.equals("auto-simple")
					|| scenario.equals("auto-no-remote-simple") ? "simple"
							: scenario.equals("auto-current") ? "current"
									: "upstream";
			db.getConfig().setBoolean("push", null, "autoSetupRemote", true);
			if (scenario.startsWith("auto-no-remote-")) {
				db.getConfig().unsetSection("remote", "parent2");
			} else {
				upstream(branch, "parent1", null);
			}
			trackingBranch = branch;
			trackingTarget = "refs/heads/" + branch;
			if (scenario.equals("auto-dry-run"))
				dryRun = true;
			if (scenario.equals("auto-up-to-date")) {
				var update = parent1.updateRef(trackingTarget);
				update.setNewObjectId(next);
				update.update();
				expected1.put(trackingTarget, next);
			}
			if (scenario.equals("auto-hook-reject")) {
				hook(db, "pre-push");
				success = false;
			}
			if (scenario.equals("auto-server-reject")) {
				hook(parent1, "pre-receive");
				success = false;
			}
			if (scenario.equals("auto-rebase-invalid"))
				success = ignoresInvalidRebasePolicy();
			if (scenario.startsWith("auto-rebase-")) {
				db.getConfig().setString("branch", null, "autoSetupRebase",
						scenario.substring("auto-rebase-".length()));
			}
			if (scenario.equals("auto-explicit-refspec")) {
				specs = List.of(new RefSpec("HEAD:refs/heads/explicit"));
				remote = "parent1";
				trackingBranch = null;
				expected1.put("refs/heads/explicit", next);
			} else if (scenario.equals("auto-configured-refspec")) {
				db.getConfig().setString("remote", "parent1", "push",
						"HEAD:refs/heads/configured");
				trackingBranch = null;
				expected1.put("refs/heads/configured", next);
			} else if (scenario.equals("auto-existing-upstream")) {
				upstream(branch, "parent1", "foo");
				trackingBranch = null;
				expected1.put("refs/heads/foo", next);
			} else if (success && !dryRun) {
				expected1.put(trackingTarget, next);
			}
		} else {
			switch (scenario) {
			case "upstream-configured":
				policy = "upstream";
				upstream("main", "parent1", "foo");
				expected1.put("refs/heads/foo", next);
				break;
			case "upstream-no-remote":
				policy = "upstream";
				success = false;
				break;
			case "upstream-no-merge":
				policy = "upstream";
				upstream("main", "parent1", null);
				success = false;
				break;
			case "upstream-other-remote":
				policy = "upstream";
				upstream("main", "parent1", "foo");
				remote = "parent2";
				success = false;
				break;
			case "current-ambiguous":
				policy = "current";
				success = false;
				break;
			case "current-explicit":
				policy = "current";
				remote = "parent1";
				expected1.put("refs/heads/main", next);
				break;
			case "current-origin":
				policy = "current";
				db.getConfig().setString("remote", "origin", "url",
						new URIish(parent1.getDirectory().toURI().toString())
								.toString());
				expected1.put("refs/heads/main", next);
				break;
			case "single-upstream-no-remote-has-merge":
				policy = "upstream";
				db.getConfig().unsetSection("remote", "parent2");
				db.getConfig().setString("branch", "main", "merge",
						"refs/heads/main");
				success = false;
				break;
			case "current-single":
				policy = "current";
				db.getConfig().unsetSection("remote", "parent1");
				expected2.put("refs/heads/main", next);
				break;
			case "matching-ignores-current":
				branch("new-branch");
				branch = "new-branch";
				upstream(branch, "parent1", null);
				policy = "matching";
				expected1.put("refs/heads/main", next);
				break;
			case "nothing-tracked":
				policy = "nothing";
				upstream("main", "parent1", "main");
				success = false;
				break;
			case "matching-none":
				remote = new URIish(
						receiver("empty").getDirectory().toURI().toString())
						.toString();
				policy = "matching";
				success = false;
				break;
			case "current-relative-remote":
				branch("new-branch");
				branch = "new-branch";
				upstream(branch, db.getWorkTree().toPath()
						.relativize(parent1.getDirectory().toPath()).toString(),
						null);
				policy = "current";
				expected1.put("refs/heads/new-branch", next);
				break;
			case "current-literal-remote":
				branch("new-branch");
				branch = "new-branch";
				upstream(branch,
						new URIish(parent1.getDirectory().toURI().toString())
								.toString(),
						null);
				policy = "current";
				expected1.put("refs/heads/new-branch", next);
				break;
			case "current-creates-branch":
				branch("new-branch");
				branch = "new-branch";
				upstream(branch, "parent1", null);
				policy = "current";
				expected1.put("refs/heads/new-branch", next);
				break;
			default:
				throw new AssertionError(scenario);
			}
		}
		if (policy != null)
			db.getConfig().setString("push", null, "default", policy);
		db.getConfig().save();
		String before = db.getConfig().toText();
		boolean pushed = false;
		try {
			pushed = executePush(remote, specs, dryRun);
		} catch (Exception failure) {
			if (scenario.equals("matching-none")) {
				assertTrue(String.valueOf(failure.getMessage())
						.contains("Perhaps you should specify a branch"));
			}
			if (success)
				throw failure;
		}
		assertEquals(scenario, success, pushed);
		assertEquals(scenario + " parent1", expected1, refs(parent1));
		assertEquals(scenario + " parent2", expected2, refs(parent2));
		assertEquals(next, db.resolve("HEAD"));
		if (!success || dryRun)
			assertEquals(before, db.getConfig().toText());
		if (trackingBranch != null && success && !dryRun) {
			assertEquals("parent1", db.getConfig().getString("branch",
					trackingBranch, "remote"));
			assertEquals(trackingTarget, db.getConfig().getString("branch",
					trackingBranch, "merge"));
		}
		if (scenario.startsWith("auto-rebase-")) {
			boolean enabled = scenario.equals("auto-rebase-remote")
					|| scenario.equals("auto-rebase-always");
			assertEquals(enabled ? "true" : null,
					db.getConfig().getString("branch", branch, "rebase"));
		}
		if (scenario.equals("auto-explicit-refspec")
				|| scenario.equals("auto-configured-refspec")) {
			assertNull(db.getConfig().getString("branch", branch, "merge"));
		}
		if (scenario.equals("auto-existing-upstream")) {
			assertEquals("refs/heads/foo",
					db.getConfig().getString("branch", branch, "merge"));
		}
	}
}
