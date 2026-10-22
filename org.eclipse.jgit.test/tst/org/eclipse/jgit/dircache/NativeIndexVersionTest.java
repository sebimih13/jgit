/*
 * Copyright (C) 2026, and others
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.dircache;

import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.junit.NativeIndexVersionSupport;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** All eight index-version precedence rows from native t1600. */
@RunWith(Parameterized.class)
public class NativeIndexVersionTest extends RepositoryTestCase {
	@Parameterized.Parameters(name = "config={0}, manyFiles={1}, env={2}, expected={3}")
	public static List<Object[]> scenarios() { return NativeIndexVersionSupport.scenarios(); }
	private final int configuredVersion;
	private final boolean manyFiles;
	private final String environmentVersion;
	private final int expectedVersion;

	public NativeIndexVersionTest(int configuredVersion, boolean manyFiles,
			String environmentVersion, int expectedVersion) {
		this.configuredVersion = configuredVersion;
		this.manyFiles = manyFiles;
		this.environmentVersion = environmentVersion;
		this.expectedVersion = expectedVersion;
	}

	@Test
	public void nativeIndexVersionPrecedence() throws Exception {
		NativeIndexVersionSupport.configure(db, configuredVersion, manyFiles);
		writeTrashFile("a", "1\n");
		var nativeIndex = NativeIndexVersionSupport.nativeIndex(db, environmentVersion,
				expectedVersion, manyFiles);
		mockSystemReader.setProperty("GIT_INDEX_VERSION",
				environmentVersion);
		try (var warnings = NativeIndexVersionSupport.captureWarnings();
				Git git = Git.wrap(db)) {
			git.add().addFilepattern("a").call();
			warnings.assertInitial(configuredVersion, environmentVersion);
		}
		NativeIndexVersionSupport.assertMatches(db, nativeIndex, expectedVersion, manyFiles);
		// t1600: invalid defaults must not affect an existing index.
		db.getConfig().setInt("index", null, "version", 1);
		db.getConfig().save();
		mockSystemReader.setProperty("GIT_INDEX_VERSION", "1");
		writeTrashFile("a", "restaged content\n");
		NativeIndexVersionSupport.nativeRestage(db, expectedVersion, manyFiles);
		try (var warnings = NativeIndexVersionSupport.captureWarnings();
				Git git = Git.wrap(db)) {
			git.add().addFilepattern("a").call();
			warnings.assertNone();
		}
		NativeIndexVersionSupport.assertMatches(db, nativeIndex, expectedVersion, manyFiles);
	}
}
