/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.internal.storage.file;

import java.io.IOException;
import java.util.Arrays;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.StringUtils;
import org.eclipse.jgit.util.SystemReader;

/**
 * Native untracked-cache configuration and uninitialized extension lifecycle.
 */
public final class UntrackedCacheConfig {
	/** Parsed configuration key shared by traversal and index writers. */
	public static final Config.SectionParser<UntrackedCacheConfig> KEY = UntrackedCacheConfig::new;

	/** Policy applied after feature defaults and explicit boolean overrides. */
	public enum Mode {
		/** Preserve extension presence and identity. */
		KEEP,
		/** Create the extension, replacing a foreign environment identity. */
		WRITE,
		/** Remove the extension. */
		REMOVE
	}

	private final Mode mode;

	private final boolean manyFiles;

	private final int dirFlags;

	private UntrackedCacheConfig(Config config) {
		String feature = config.getString("feature", null, "manyFiles"); //$NON-NLS-1$ //$NON-NLS-2$
		Boolean enabled = parseBoolean(feature);
		if (feature != null && enabled == null) {
			// Unlike core.untrackedCache, invalid feature booleans are errors.
			config.getBoolean("feature", null, "manyFiles", false); //$NON-NLS-1$ //$NON-NLS-2$
		}
		manyFiles = Boolean.TRUE.equals(enabled);
		Boolean explicit = parseBoolean(
				config.getString("core", null, "untrackedCache")); //$NON-NLS-1$ //$NON-NLS-2$
		if (explicit != null) {
			mode = explicit.booleanValue() ? Mode.WRITE : Mode.REMOVE;
		} else {
			mode = manyFiles ? Mode.WRITE : Mode.KEEP;
		}
		// Keep this aligned with native new_untracked_cache_flags().
		dirFlags = "all".equals(config.getString("status", null, //$NON-NLS-1$ //$NON-NLS-2$
				"showUntrackedFiles")) ? 0 //$NON-NLS-1$
						: UntrackedCache.SHOW_OTHER_DIRECTORIES | (1 << 2);
	}

	/** @return effective native untracked-cache policy */
	public Mode getMode() {
		return mode;
	}

	/** @return effective feature.manyFiles boolean */
	public boolean isManyFiles() {
		return manyFiles;
	}

	/**
	 * Apply the policy when an index is written. A newly created cache has no
	 * valid traversal data; directory/stat/ignore validation remains mandatory.
	 *
	 * @param repository
	 *            repository whose index is being written
	 * @param current
	 *            stored extension, or null
	 * @return extension to write, or null
	 * @throws IOException
	 *             if the worktree identity cannot be resolved
	 */
	public UntrackedCache applyTo(Repository repository, UntrackedCache current)
			throws IOException {
		if (mode == Mode.REMOVE) {
			return null;
		}
		if (mode == Mode.KEEP || repository.isBare()) {
			return current;
		}
		SystemReader reader = SystemReader.getInstance();
		// Native compat/mingw.c:uname uses "Windows", not the OS version.
		String system = reader.isWindows() ? "Windows" //$NON-NLS-1$
				: reader.isMacOS() ? "Darwin" : reader.getProperty("os.name"); //$NON-NLS-1$ //$NON-NLS-2$
		String identity = "Location " //$NON-NLS-1$
				+ repository.getWorkTree().getCanonicalPath().replace(
						java.io.File.separatorChar, '/')
				+ ", system " + system; //$NON-NLS-1$
		byte[] encoded = Constants.encode(identity);
		if (current != null && matchesIdentity(current.ident, encoded)) {
			return current;
		}
		UntrackedCache created = new UntrackedCache();
		created.ident = Arrays.copyOf(encoded, encoded.length + 1);
		created.dirFlags = dirFlags;
		return created;
	}

	private static boolean matchesIdentity(byte[] stored, byte[] expected) {
		// Native accepts legacy trailing identities after the first NUL.
		if (stored.length <= expected.length || stored[expected.length] != 0) {
			return false;
		}
		return Arrays.equals(stored, 0, expected.length, expected, 0,
				expected.length);
	}

	private static Boolean parseBoolean(String value) {
		if (value == null) {
			return null;
		}
		if (Config.isMissing(value)) {
			return Boolean.TRUE;
		}
		if (value.isEmpty()) {
			return Boolean.FALSE;
		}
		Boolean text = StringUtils.toBooleanOrNull(value);
		if (text != null) {
			return text;
		}
		// git_parse_maybe_bool also accepts a signed, base-0 C int with an
		// optional binary unit suffix. An invalid number means 'keep'.
		int start = 0;
		while (start < value.length()
				&& " \t\n\r\f\u000b".indexOf(value.charAt(start)) >= 0) { //$NON-NLS-1$
			start++;
		}
		String number = value.substring(start);
		if (number.isEmpty()) {
			return null;
		}
		int shift = switch (number.charAt(number.length() - 1)) {
		case 'k', 'K' -> 10;
		case 'm', 'M' -> 20;
		case 'g', 'G' -> 30;
		default -> 0;
		};
		if (shift != 0) {
			number = number.substring(0, number.length() - 1);
		}
		if (!number.matches("[+-]?(0[xX][0-9a-fA-F]+|0[0-7]*|[1-9][0-9]*)")) { //$NON-NLS-1$
			return null;
		}
		try {
			long parsed = Long.decode(number).longValue();
			long factor = 1L << shift;
			if (parsed < Integer.MIN_VALUE / factor
					|| parsed > Integer.MAX_VALUE / factor) {
				return null;
			}
			return Boolean.valueOf(parsed != 0);
		} catch (NumberFormatException outOfRange) {
			return null;
		}
	}

}
