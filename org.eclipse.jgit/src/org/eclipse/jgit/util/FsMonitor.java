/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.hooks.Hooks;
import org.eclipse.jgit.internal.storage.file.FileSnapshot;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

/**
 * Opt-in, process-local fsmonitor hook v2 validation for status caches.
 * <p>
 * No FSMN bits or native UNTR data are used for scan reuse. Changes and
 * untrusted replies always require a full scan. Callers must retain a
 * successful scan result separately and serialize query/scan/remember on their
 * cache lock.
 * </p>
 *
 * @since 7.8
 */
public final class FsMonitor {
	private FsMonitor() {
	}

	/**
	 * @param repository
	 *            repository
	 * @return whether the experimental cache is explicitly enabled
	 */
	public static boolean isEnabled(Repository repository) {
		return !repository.isBare() && repository.getIndexFile() != null
				&& repository.getConfig().getBoolean("jgit", null, //$NON-NLS-1$
						"fsmonitorCache", false); //$NON-NLS-1$
	}

	/**
	 * Whether untracked status may be reused under the effective cache setting.
	 *
	 * @param repository
	 *            repository whose index and configuration apply
	 * @return true for an enabled cache or a retained native UNTR extension
	 * @since 7.8
	 */
	public static boolean isUntrackedCacheEnabled(Repository repository) {
		return isUntrackedCacheEnabled(repository, null);
	}

	/**
	 * Resolve the untracked-cache setting using an index already validated by
	 * the supplied query, avoiding another index read for the keep setting.
	 *
	 * @param repository
	 *            repository used by the query
	 * @param probe
	 *            successful query for this repository, or null to read the index
	 * @return whether untracked status may be reused
	 * @since 7.8
	 */
	public static boolean isUntrackedCacheEnabled(Repository repository,
			Probe probe) {
		Config config = repository.getConfig();
		String value = config.getString("core", null, "untrackedCache"); //$NON-NLS-1$ //$NON-NLS-2$
		if (value == null && config.getBoolean("feature", null, //$NON-NLS-1$
				"manyFiles", false)) { //$NON-NLS-1$
			return true;
		}
		if (value != null && !"keep".equalsIgnoreCase(value)) { //$NON-NLS-1$
			return config.getBoolean("core", null, "untrackedCache", false); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (probe != null) {
			return probe.hasUntrackedCache;
		}
		try {
			return repository.readDirCache().getUntrackedCache() != null;
		} catch (IOException unreadableIndex) {
			return false;
		}
	}

	/** Query result; valid means its token may seed a successful full scan. */
	public static final class Probe {
		private final String fingerprint;

		private final String token;

		private final boolean unchanged;

		private final List<String> changedPaths;

		private final boolean hasUntrackedCache;

		private Probe(String fingerprint, String token, boolean unchanged,
				List<String> changedPaths, boolean hasUntrackedCache) {
			this.fingerprint = fingerprint;
			this.token = token;
			this.unchanged = unchanged;
			this.changedPaths = List.copyOf(changedPaths);
			this.hasUntrackedCache = hasUntrackedCache;
		}

		/** @return whether no filesystem/context changes were observed */
		public boolean isUnchanged() {
			return unchanged;
		}

		/**
		 * @return immutable paths whose stat metadata cannot be trusted, or a
		 *         single root marker when correlation must be rebuilt
		 * @since 7.8
		 */
		public List<String> getChangedPaths() {
			return changedPaths;
		}
	}

	/** Per-cache validation state; does not retain the repository or result. */
	public static final class State {
		private String fingerprint;

		private String token;

		private String checkedIndex;

		private boolean supportedIndex;

		private boolean hasUntrackedCache;

		private FileSnapshot unsupportedIndex;

		private boolean fullContentCheckRequired;

		/**
		 * Drop correlation after cancellation, failure or external cache
		 * updates. Preserve any obligation to check contents until monitoring
		 * can establish a new correlation.
		 */
		public synchronized void clear() {
			boolean checkContents = token != null || fullContentCheckRequired;
			fingerprint = token = checkedIndex = null;
			supportedIndex = false;
			hasUntrackedCache = false;
			unsupportedIndex = null;
			fullContentCheckRequired = checkContents;
		}

		/**
		 * Query a configured executable hook with a bounded timeout/output
		 * size.
		 *
		 * @param repository
		 *            repository
		 * @return a probe, or null when a full scan cannot seed this cache
		 */
		public synchronized Probe query(Repository repository) {
			// Once correlation is lost, ordinary stat equality is insufficient
			// until a successful monitored scan establishes a new token.
			boolean lostCorrelation = token != null || fullContentCheckRequired;
			fullContentCheckRequired = false;
			if (!isEnabled(repository)) {
				fallback(lostCorrelation);
				return null;
			}
			try (Trace2.Scope trace = Trace2.getInstance().region(repository,
					"fsm_hook", "query")) { //$NON-NLS-1$ //$NON-NLS-2$
				boolean supported = false;
				try {
					Config config = repository.getConfig();
					if (config.getInt("core", null, "fsmonitorHookVersion", //$NON-NLS-1$ //$NON-NLS-2$
							2) != 2) {
						fallback(lostCorrelation);
						return null;
					}
					String hook = config.getString("core", null, "fsmonitor"); //$NON-NLS-1$ //$NON-NLS-2$
					if (hook == null || hook.isEmpty()
							|| isBooleanSetting(hook)) {
						fallback(lostCorrelation);
						return null;
					}
					File executable = new File(hook);
					if (!executable.isAbsolute()) {
						executable = new File(repository.getWorkTree(), hook);
					}
					if (!executable.isFile() || !executable.canExecute()) {
						fallback(lostCorrelation);
						return null;
					}
					// A metadata snapshot is sufficient for a negative cache:
					// an
					// undetected change only prolongs full scans, never reuses
					// data.
					if (unsupportedIndex != null && !unsupportedIndex
							.isModified(repository.getIndexFile())) {
						fullContentCheckRequired = lostCorrelation;
						return null;
					}
					FileSnapshot indexSnapshot = FileSnapshot
							.save(repository.getIndexFile());
					String index = digest(repository.getIndexFile().toPath());
					if (!index.equals(checkedIndex)) {
						DirCache cache = repository.readDirCache();
						hasUntrackedCache = cache.getUntrackedCache() != null;
						supportedIndex = true;
						for (int i = 0; i < cache.getEntryCount(); i++) {
							if (cache.getEntry(i)
									.getFileMode() == FileMode.GITLINK) {
								supportedIndex = false;
								break;
							}
						}
						checkedIndex = index;
					}
					if (!supportedIndex) {
						unsupportedIndex = indexSnapshot;
						fingerprint = token = null;
						fullContentCheckRequired = lostCorrelation;
						return null;
					}
					unsupportedIndex = null;
					supported = true;
					String key = fingerprint(repository, index,
							executable.toPath());
					// A stale Watchman clock requests a fresh-instance reply
					// even
					// when this JVM starts after another client established the
					// watch.
					// Other v2 providers may reject it; rejection requires a
					// full scan.
					String since = token == null ? "c:0:0:0:0" : token; //$NON-NLS-1$
					byte[] response = Hooks.fsMonitor(repository, executable, since).call();
					String[] records = parse(response);
					if (records == null) {
						trace.data("query/failed", "invalid-response"); //$NON-NLS-1$ //$NON-NLS-2$
						clear();
						fullContentCheckRequired = true;
						return null;
					}
					boolean noChanges = true;
					List<String> changedPaths = new ArrayList<>();
					for (int i = 1; i < records.length; i++) {
						// Native Git does not have worktree entries inside its
						// .git directory.
						// Relevant metadata is independently fingerprinted
						// below.
						if (!records[i].equals(".git") //$NON-NLS-1$
								&& !records[i].startsWith(".git/")) {
							noChanges = false;
							changedPaths.add(records[i]);
						}
					}
					// External index/ref/config changes during the hook
					// invalidate reuse.
					if (!key.equals(fingerprint(repository,
							digest(repository.getIndexFile().toPath()),
							executable.toPath()))) {
						trace.data("query/failed", "context-changed"); //$NON-NLS-1$ //$NON-NLS-2$
						clear();
						fullContentCheckRequired = true;
						return null;
					}
					boolean sameContext = key.equals(fingerprint);
					// The supplied hook can return an ephemeral zero-epoch
					// clock
					// when its daemon is unavailable. Such a reply cannot
					// validate
					// cached data, even if it lists only Git metadata.
					boolean sameInstance = sameMonitorInstance(token,
							records[0]);
					noChanges &= sameInstance;
					if (!sameContext || !sameInstance) {
						changedPaths = List.of("/"); //$NON-NLS-1$
					}
					trace.data("cache/hit", //$NON-NLS-1$
							Boolean.toString(noChanges && sameContext));
					return new Probe(key, records[0], noChanges && sameContext,
							changedPaths, hasUntrackedCache);
				} catch (Exception failure) {
					if (failure instanceof InterruptedException) {
						Thread.currentThread().interrupt();
					}
					trace.data("query/failed", //$NON-NLS-1$
							failure.getClass().getSimpleName());
					fallback(supported || lostCorrelation);
					return null;
				}
			}
		}

		private void fallback(boolean checkContents) {
			clear();
			fullContentCheckRequired = checkContents;
		}

		/**
		 * @return whether loss of monitored correlation requires content
		 *         checks on fallback, including repeated fallback attempts
		 * @since 7.8
		 */
		public synchronized boolean requiresFullContentCheck() {
			return fullContentCheckRequired;
		}

		/**
		 * Remember a token only after a complete, successful scan/result reuse.
		 *
		 * @param probe
		 *            probe associated with that scan
		 */
		public synchronized void remember(Probe probe) {
			if (probe == null) {
				fingerprint = token = null;
				return;
			}
			fingerprint = probe.fingerprint;
			token = probe.token;
		}
	}

	private static boolean sameMonitorInstance(String before, String after) {
		if (before == null) {
			return false;
		}
		if (!before.startsWith("c:") && !after.startsWith("c:")) { //$NON-NLS-1$ //$NON-NLS-2$
			return true; // Other providers must honor the hook v2 contract.
		}
		String[] oldClock = before.split(":"); //$NON-NLS-1$
		String[] newClock = after.split(":"); //$NON-NLS-1$
		if (oldClock.length != 5 || newClock.length != 5) {
			return false;
		}
		for (int i = 1; i <= 3; i++) {
			if (!newClock[i].matches("[1-9][0-9]*") //$NON-NLS-1$
					|| !newClock[i].equals(oldClock[i])) {
				return false;
			}
		}
		try {
			return Long.compareUnsigned(Long.parseUnsignedLong(newClock[4]),
					Long.parseUnsignedLong(oldClock[4])) >= 0;
		} catch (NumberFormatException invalidClock) {
			return false;
		}
	}

	private static String fingerprint(Repository repo, String index,
			Path executable) throws Exception {
		MessageDigest md = MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
		update(md, index);
		update(md, repo.getDirectory().getAbsolutePath());
		update(md, repo.getWorkTree().getAbsolutePath());
		update(md, repo.getIndexFile().getAbsolutePath());
		ObjectId head = repo.resolve(Constants.HEAD);
		update(md, head == null ? "unborn" : head.name()); //$NON-NLS-1$
		SystemReader reader = SystemReader.getInstance();
		// Reload inherited external config before comparing effective inputs.
		reader.getUserConfig();
		Config config = repo.getConfig();
		for (String section : new TreeSet<>(config.getSections())) {
			fingerprintConfig(md, config, section, null);
			for (String subsection : new TreeSet<>(
					config.getSubsections(section))) {
				fingerprintConfig(md, config, section, subsection);
			}
		}
		update(md, digest(executable));
		update(md, digest(
				repo.getCommonDirectory().toPath().resolve("info/exclude"))); //$NON-NLS-1$
		update(md, digest(
				repo.getDirectory().toPath().resolve("info/attributes"))); //$NON-NLS-1$
		Path excludes = repo.getConfig().getPath("core", null, "excludesFile", //$NON-NLS-1$ //$NON-NLS-2$
				repo.getFS(), null, null);
		if (excludes != null) {
			update(md, digest(excludes));
		} else {
			Path xdg = reader.getXdgConfigDirectory(repo.getFS());
			if (xdg != null) {
				update(md, digest(xdg.resolve("git/ignore"))); //$NON-NLS-1$
			}
		}
		String attributes = repo.getConfig().getString("core", null, //$NON-NLS-1$
				"attributesFile"); //$NON-NLS-1$
		if (attributes != null && !attributes.isEmpty()) {
			File path = attributes.startsWith("~/") //$NON-NLS-1$
					? repo.getFS().resolve(repo.getFS().userHome(),
							attributes.substring(2))
					: repo.getFS().resolve(null, attributes);
			update(md, digest(path.toPath()));
		}
		return HexFormat.of().formatHex(md.digest());
	}

	// C Git parse.c: boolean text or a signed int with base-0 and k/m/g units.
	// Boolean true selects Git's daemon, which this hook-only client cannot
	// use.
	private static boolean isBooleanSetting(String value) {
		if (StringUtils.toBooleanOrNull(value) != null) {
			return true;
		}
		String number = value.replaceFirst("^[ \\t\\r\\n\\f\\x0B]+", ""); //$NON-NLS-1$ //$NON-NLS-2$
		if (!number.matches(
				"[+-]?(?:0[xX][0-9a-fA-F]+|0[0-7]*|[1-9][0-9]*)(?:[kKmMgG])?")) { //$NON-NLS-1$
			return false;
		}
		long factor = 1;
		char last = Character.toLowerCase(number.charAt(number.length() - 1));
		if (last == 'k' || last == 'm' || last == 'g') {
			factor = last == 'k' ? 1024
					: last == 'm' ? 1024 * 1024 : 1024 * 1024 * 1024;
			number = number.substring(0, number.length() - 1);
		}
		try {
			long result = Math.multiplyExact(Long.decode(number).longValue(),
					factor);
			return result >= Integer.MIN_VALUE && result <= Integer.MAX_VALUE;
		} catch (NumberFormatException | ArithmeticException invalid) {
			return false;
		}
	}

	private static void fingerprintConfig(MessageDigest md, Config config,
			String section, String subsection) {
		update(md, section);
		update(md, subsection == null ? "0" : "1" + subsection); //$NON-NLS-1$ //$NON-NLS-2$
		for (String name : new TreeSet<>(
				config.getNames(section, subsection, true))) {
			update(md, name);
			String[] values = config.getStringList(section, subsection, name);
			update(md, Integer.toString(values.length));
			for (String value : values) {
				// A valueless config entry means true; an empty string means
				// false.
				update(md, value == null ? "-1" //$NON-NLS-1$
						: Config.isMissing(value) ? "-2" //$NON-NLS-1$
								: Integer.toString(value.length()));
				if (value != null) {
					update(md, value);
				}
			}
		}
	}

	private static void update(MessageDigest md, String value) {
		md.update(value.getBytes(StandardCharsets.UTF_8));
		md.update((byte) 0);
	}

	private static String digest(Path file) throws Exception {
		if (!Files.exists(file)) {
			return "absent"; //$NON-NLS-1$
		}
		MessageDigest md = MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
		try (InputStream in = Files.newInputStream(file)) {
			byte[] buffer = new byte[64 * 1024];
			for (int n; (n = in.read(buffer)) != -1;) {
				md.update(buffer, 0, n);
			}
		}
		return HexFormat.of().formatHex(md.digest());
	}


	static String[] parse(byte[] response) throws CharacterCodingException {
		if (response.length == 0) {
			return null;
		}
		String text = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(response)).toString();
		String[] fields = text.split("\u0000", -1); //$NON-NLS-1$
		if (fields.length < 2 || fields[0].isEmpty()) {
			return null;
		}
		// Git accepts a final pathname without a trailing NUL; its sample
		// Watchman hook writes separators rather than pathname terminators.
		int length = fields[fields.length - 1].isEmpty() ? fields.length - 1
				: fields.length;
		for (int i = 1; i < length; i++) {
			String path = fields[i];
			if (path.isEmpty() || path.startsWith("/") && !path.equals("/") //$NON-NLS-1$ //$NON-NLS-2$
					|| path.contains("\\") //$NON-NLS-1$
					|| path.equals("..") //$NON-NLS-1$
					|| path.startsWith("../") //$NON-NLS-1$
					|| path.endsWith("/..") //$NON-NLS-1$
					|| path.contains("/../")) { //$NON-NLS-1$
				return null;
			}
		}
		return java.util.Arrays.copyOf(fields, length);
	}
}
