/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.internal.trace2;

import java.io.File;
import java.io.IOException;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;

/** Early system/global-only configuration corresponding to tr2_sysenv. */
public final class Trace2SystemConfig {
	private Config system = new Config();

	private Config global = new Config(system);

	/**
	 * Read early configuration using existing JGit config readers.
	 *
	 * @param reader
	 *            host environment
	 * @throws IOException
	 *             read failure
	 * @throws ConfigInvalidException
	 *             malformed config
	 */
	public Trace2SystemConfig(SystemReader reader)
			throws IOException, ConfigInvalidException {
		String skip = reader.getenv("GIT_CONFIG_NOSYSTEM"); //$NON-NLS-1$
		if (skip == null || skip.isEmpty() || "0".equals(skip) //$NON-NLS-1$
				|| "false".equalsIgnoreCase(skip) || "no".equalsIgnoreCase(skip) //$NON-NLS-1$
				|| "off".equalsIgnoreCase(skip)) {
			String override = reader.getenv("GIT_CONFIG_SYSTEM"); //$NON-NLS-1$
			FileBasedConfig config;
			if (override != null) {
				config = new FileBasedConfig(new File(override), FS.DETECTED);
			} else if (skip != null && !skip.isEmpty()
					&& FS.DETECTED.getGitSystemConfig() != null) {
				config = new FileBasedConfig(FS.DETECTED.getGitSystemConfig(),
						FS.DETECTED);
			} else {
				config = reader.openSystemConfig(null, FS.DETECTED);
			}
			config.load();
			system = config;
		}
		String override = reader.getenv("GIT_CONFIG_GLOBAL"); //$NON-NLS-1$
		FileBasedConfig config = override == null
				? reader.openUserConfig(system, FS.DETECTED)
				: new FileBasedConfig(system, new File(override), FS.DETECTED);
		config.load();
		global = config;
	}

	/** @return system configuration without JGit-specific inheritance */
	public Config system() {
		return system;
	}

	/** @return global configuration inheriting system settings */
	public Config global() {
		return global;
	}
}
