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

import static org.junit.Assert.assertTrue;

import java.nio.file.Path;

import org.eclipse.jgit.util.Trace2;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * Opt-in command regressions with tracing enabled before test system readers
 * replace the real environment. Run in a fresh JVM with GIT_TRACE2_EVENT set.
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
		org.eclipse.jgit.api.VerifySignatureCommandTest.class,
		org.eclipse.jgit.api.AddCommandTest.class,
		org.eclipse.jgit.api.ApplyCommandTest.class,
		org.eclipse.jgit.api.ArchiveCommandTest.class,
		org.eclipse.jgit.api.BlameCommandTest.class,
		org.eclipse.jgit.api.BranchCommandTest.class,
		org.eclipse.jgit.api.CheckoutCommandTest.class,
		org.eclipse.jgit.api.CherryPickCommandTest.class,
		org.eclipse.jgit.api.CleanCommandTest.class,
		org.eclipse.jgit.api.CloneCommandTest.class,
		org.eclipse.jgit.api.CommitAndLogCommandTest.class,
		org.eclipse.jgit.api.CommitCommandTest.class,
		org.eclipse.jgit.api.DescribeCommandTest.class,
		org.eclipse.jgit.api.DiffCommandTest.class,
		org.eclipse.jgit.api.FetchAndPullCommandsRecurseSubmodulesTest.class,
		org.eclipse.jgit.api.FetchCommandTest.class,
		org.eclipse.jgit.api.GarbageCollectCommandTest.class,
		org.eclipse.jgit.api.InitCommandTest.class,
		org.eclipse.jgit.api.LogCommandTest.class,
		org.eclipse.jgit.api.LsRemoteCommandTest.class,
		org.eclipse.jgit.api.MergeCommandTest.class,
		org.eclipse.jgit.api.NameRevCommandTest.class,
		org.eclipse.jgit.api.NotesCommandTest.class,
		org.eclipse.jgit.api.PathCheckoutCommandTest.class,
		org.eclipse.jgit.api.PullCommandTest.class,
		org.eclipse.jgit.api.PullCommandWithRebaseTest.class,
		org.eclipse.jgit.api.PushCommandTest.class,
		org.eclipse.jgit.api.RebaseCommandTest.class,
		org.eclipse.jgit.api.ReflogCommandTest.class,
		org.eclipse.jgit.api.RemoteAddCommandTest.class,
		org.eclipse.jgit.api.RemoteDeleteCommandTest.class,
		org.eclipse.jgit.api.RemoteListCommandTest.class,
		org.eclipse.jgit.api.RemoteSetUrlCommandTest.class,
		org.eclipse.jgit.api.RenameBranchCommandTest.class,
		org.eclipse.jgit.api.ResetCommandTest.class,
		org.eclipse.jgit.api.RevertCommandTest.class,
		org.eclipse.jgit.api.RmCommandTest.class,
		org.eclipse.jgit.api.StashApplyCommandTest.class,
		org.eclipse.jgit.api.StashCreateCommandTest.class,
		org.eclipse.jgit.api.StashDropCommandTest.class,
		org.eclipse.jgit.api.StashListCommandTest.class,
		org.eclipse.jgit.api.StatusCommandTest.class,
		org.eclipse.jgit.api.TagCommandTest.class,
		org.eclipse.jgit.submodule.SubmoduleAddTest.class,
		org.eclipse.jgit.submodule.SubmoduleDeinitTest.class,
		org.eclipse.jgit.submodule.SubmoduleInitTest.class,
		org.eclipse.jgit.submodule.SubmoduleStatusTest.class,
		org.eclipse.jgit.submodule.SubmoduleSyncTest.class,
		org.eclipse.jgit.submodule.SubmoduleUpdateTest.class,
		org.eclipse.jgit.submodule.SubmoduleWalkTest.class,
		org.eclipse.jgit.internal.storage.file.GcPackRefsTest.class,
		org.eclipse.jgit.util.Trace2Test.class })
public class Trace2ApiSuite {
	@BeforeClass
	public static void startTracing() {
		String target = System.getenv("GIT_TRACE2_EVENT");
		assertTrue("Set GIT_TRACE2_EVENT to an absolute output file",
				target != null && Path.of(target).isAbsolute());
		try (Trace2.Scope scope = Trace2.getInstance().region(null, "test",
				"initialize")) {
			assertTrue(scope.isEnabled());
		}
	}

	@AfterClass
	public static void stopTracing() {
		Trace2.getInstance().close();
	}
}
