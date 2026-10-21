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
import org.eclipse.jgit.internal.storage.file.UntrackedCacheTest;
import org.eclipse.jgit.treewalk.IndexPreloaderTest;
import org.eclipse.jgit.util.FsMonitorTest;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * Opt-in validation of the implemented FSMonitor, untracked-cache, preload and
 * many-files paths. This suite does not imply complete native feature parity.
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({ NativeCacheStatusTest.class, FsMonitorTest.class,
		UntrackedCacheTest.class, PreloadIndexCommandTest.class,
		IndexPreloaderTest.class, IndexSkipHashTest.class, NativeIndexVersionTest.class })
public class NativeCacheOptionsSuite {
}
