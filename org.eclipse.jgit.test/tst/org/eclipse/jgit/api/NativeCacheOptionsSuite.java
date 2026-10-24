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

import org.eclipse.jgit.dircache.IndexSkipHashTest;
import org.eclipse.jgit.dircache.NativeIndexVersionTest;
import org.eclipse.jgit.internal.storage.file.FsMonitorCacheTest;
import org.eclipse.jgit.internal.storage.file.UntrackedCacheTest;
import org.eclipse.jgit.treewalk.IndexPreloaderTest;
import org.eclipse.jgit.treewalk.UntrackedDirectoryListingTest;
import org.eclipse.jgit.treewalk.UntrackedCachePopulationTest;
import org.eclipse.jgit.util.FsMonitorIndexQueryTest;
import org.eclipse.jgit.util.FsMonitorInitializationTest;
import org.eclipse.jgit.util.FsMonitorWriterRefreshTest;
import org.eclipse.jgit.util.FsMonitorTest;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/** Validation of the implemented native cache paths; not universal Git parity. */
@RunWith(Suite.class)
@Suite.SuiteClasses({ NativeCacheStatusTest.class, FsMonitorTest.class,
		FsMonitorIndexQueryTest.class, FsMonitorInitializationTest.class,
		FsMonitorCacheTest.class, FsMonitorWriterRefreshTest.class,
		UntrackedCacheTest.class, UntrackedDirectoryListingTest.class,
		UntrackedCachePopulationTest.class,
		PreloadIndexCommandTest.class, IndexPreloaderTest.class,
		IndexSkipHashTest.class, NativeIndexVersionTest.class })
public class NativeCacheOptionsSuite {
}
