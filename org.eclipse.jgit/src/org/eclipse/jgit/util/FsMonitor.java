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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.errors.CommandFailedException;
import org.eclipse.jgit.hooks.Hooks;
import org.eclipse.jgit.internal.storage.file.FileSnapshot;
import org.eclipse.jgit.internal.storage.file.FsMonitorCache;
import org.eclipse.jgit.internal.storage.file.UntrackedCacheConfig;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

/**
 * Fsmonitor provider validation for status caches and native index correlation.
 * <p>
 * Stored FSMN validity requires a correlated provider query before reuse.
 * Whole-result caches separately retain a successful scan and serialize
 * query/scan/remember on their cache lock. Untrusted replies require checking
 * rather than trusting serialized validity alone.
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
		UntrackedCacheConfig.Mode mode = repository.getConfig()
				.get(UntrackedCacheConfig.KEY).getMode();
		if (mode != UntrackedCacheConfig.Mode.KEEP) {
			return mode == UntrackedCacheConfig.Mode.WRITE;
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


	/**
	 * A direct index query, separate from whole-result cache publication.
	 * @param probe successful query, or null
	 * @param forceContentCheck whether failed correlation needs content checks
	 */
	public record IndexQuery(Probe probe, boolean forceContentCheck) {
		/**
		 * Select entries whose contents can be reused by checkout. Notifications,
		 * unknown scope and case-insensitive contexts require verification.
		 * @param repository repository queried
		 * @param index matching index
		 * @return operation-local trusted entries
		 * @since 7.8
		 */
		public Set<DirCacheEntry> getCheckoutValidEntries(Repository repository, DirCache index) {
			if (forceContentCheck || probe == null || !probe.validatesIndex(index)
					|| probe.invalidationScopeUnknown
					|| repository.getConfig().getBoolean("core", null, "ignoreCase", false)) { //$NON-NLS-1$ //$NON-NLS-2$
				return Set.of();
			}
			Set<String> paths = new HashSet<>();
			for (String path : probe.changedPaths) {
				if (path.equals(".gitattributes") || path.endsWith("/.gitattributes") //$NON-NLS-1$ //$NON-NLS-2$
						|| path.equals(".gitignore") || path.endsWith("/.gitignore")) { //$NON-NLS-1$ //$NON-NLS-2$
					return Set.of();
				}
				paths.add(path.endsWith("/") ? path.substring(0, path.length() - 1) : path); //$NON-NLS-1$
			}
			Set<DirCacheEntry> trusted = probe.getIndexValidEntries(index);
			for (String path : paths) {
				int exact = index.findEntry(path);
				if (exact >= 0) trusted.remove(index.getEntry(exact));
				String prefix = path + "/"; //$NON-NLS-1$
				int position = index.findEntry(prefix);
				if (position < 0) position = -position - 1;
				for (; position < index.getEntryCount(); position++) {
					DirCacheEntry entry = index.getEntry(position);
					if (!entry.getPathString().startsWith(prefix)) break;
					trusted.remove(entry);
				}
			}
			return trusted;
		}

		/**
		 * Apply provider invalidation before consuming native directory records.
		 * This changes only the operation-local model, never the index file.
		 * @param repository repository queried
		 * @param index matching index to scan
		 * @throws IOException for unsafe notification paths
		 * @since 7.8
		 */
		public void invalidateUntrackedCache(Repository repository, DirCache index)
				throws IOException {
			var cache = index.getUntrackedCache();
			if (cache == null || !isConfigured(repository)) {
				return;
			}
			if (forceContentCheck || probe == null || !probe.validatesIndex(index)
					|| probe.invalidationScopeUnknown
					|| repository.getConfig().getBoolean("core", null, "ignoreCase", false)) { //$NON-NLS-1$ //$NON-NLS-2$
				cache.invalidateAll();
				return;
			}
			for (String path : probe.changedPaths) {
				cache.invalidateWorkingTreePath(Constants.encode(path.endsWith("/") //$NON-NLS-1$
						? path.substring(0, path.length() - 1) : path));
			}
		}
	}

	/**
	 * Establish monitor storage before a repository-aware index mutation.
	 * Existing entries start dirty even if the initial hook reports no paths.
	 * Newly verified entries may subsequently be marked valid by the writer.
	 * This method does not write the index or start a daemon.
	 *
	 * @param repository repository whose hook is configured
	 * @param index index about to be mutated
	 * @return initial hook state, or null when no hook path is configured
	 * @since 7.8
	 */
	public static FsMonitorCache initializeIndex(Repository repository,
			DirCache index) {
		String hook = configuredHook(repository);
		if (repository.isBare() || hook == null || hook.isEmpty()
				|| Boolean.FALSE.equals(UntrackedCacheConfig.parseBoolean(hook))) {
			return null;
		}
		boolean daemon = isDaemonSetting(hook);
		Instant now = Instant.now();
		String boundary = Long.toUnsignedString(now.getEpochSecond()
				* 1_000_000_000L + now.getNano());
		State state = new State();
		state.token = daemon ? "0" : boundary; //$NON-NLS-1$
		state.version = daemon ? 2 : 1;
		Probe probe = state.query(repository, true, index);
		int requested = daemon ? 2 : repository.getConfig().getInt("core", null, //$NON-NLS-1$
				"fsmonitorHookVersion", -1); //$NON-NLS-1$
		String token = probe != null ? probe.token
				: daemon ? "builtin:fake" : requested == 2 ? "" : boundary; //$NON-NLS-1$ //$NON-NLS-2$
		BitSet dirty = new BitSet();
		dirty.set(0, index.getEntryCount());
		return new FsMonitorCache(Constants.encode(token), dirty);
	}

	private static String configuredHook(Repository repository) {
		String hook = repository.getConfig().getString("core", null, //$NON-NLS-1$
				"fsmonitor"); //$NON-NLS-1$
		return hook != null ? hook
				: SystemReader.getInstance().getenv("GIT_TEST_FSMONITOR"); //$NON-NLS-1$
	}

	/**
	 * Whether a hook or daemon is configured, including unavailable providers.
	 * Unavailable providers still require conservative cache publication.
	 * @param repository repository to inspect
	 * @return whether monitoring is configured
	 * @since 7.8
	 */
	public static boolean isConfigured(Repository repository) {
		String value = configuredHook(repository);
		return value != null && !value.isEmpty()
				&& !Boolean.FALSE.equals(UntrackedCacheConfig.parseBoolean(value));
	}

	/**
	 * Whether hook-based monitoring is configured, including an unavailable
	 * hook that must select conservative fallback. Boolean daemon settings
	 * are not hook paths.
	 * @param repository repository to inspect
	 * @return whether a hook path is configured
	 * @since 7.8
	 */
	public static boolean isHookConfigured(Repository repository) {
		String hook = configuredHook(repository);
		return !repository.isBare() && hook != null && !hook.isEmpty()
				&& !isBooleanSetting(hook);
	}

	/**
	 * Refresh persisted monitor state before an index-writing operation.
	 * Existing dirty entries remain dirty when the provider clock advances.
	 * This does not write the index; callers must install the returned owner
	 * context before verifying or changing entries.
	 * @param repository repository whose index is locked
	 * @param index freshly read index before mutation
	 * @return refreshed model, or null when monitoring is disabled
	 * @throws IOException on invalid untracked-cache mutation
	 * @since 7.8
	 */
	public static FsMonitorCache refreshIndex(Repository repository,
			DirCache index) throws IOException {
		return refreshIndexWithChanges(repository, index).cache();
	}

	/**
	 * State and worktree scope consumed before an index mutation.
	 * @param cache refreshed native model, possibly null
	 * @param paths immutable worktree paths, or null when a full scan is required
	 * @since 7.8
	 */
	public record IndexRefresh(FsMonitorCache cache, Set<String> paths) {
		/**
		 * Create a refresh result with an immutable notification scope.
		 * @param cache refreshed native model
		 * @param paths paths, or null for unknown scope
		 */
		public IndexRefresh {
			paths = paths == null ? null : Set.copyOf(paths);
		}
	}

	/**
	 * Refresh the index and retain the notifications consumed by its writer.
	 * The scope must accompany any resulting index event, since the serialized
	 * bitmap cannot represent paths outside the index.
	 * @param repository repository whose index is locked
	 * @param index freshly read index
	 * @return refreshed model and notification scope
	 * @throws IOException on invalid untracked-cache mutation
	 * @since 7.8
	 */
	public static IndexRefresh refreshIndexWithChanges(Repository repository,
			DirCache index) throws IOException {
		FsMonitorCache previous = index.getFsMonitorCache();
		if (previous == null) {
			return new IndexRefresh(initializeIndex(repository, index), null);
		}
		String hook = configuredHook(repository);
		if (hook == null || hook.isEmpty() || Boolean.FALSE.equals(
				UntrackedCacheConfig.parseBoolean(hook))) {
			return new IndexRefresh(null, null);
		}
		boolean daemon = isDaemonSetting(hook);
		State state;
		try {
			state = storedState(repository, previous);
		} catch (CharacterCodingException binaryToken) {
			// Preserve opaque storage that cannot be passed through Java's
			// String argv API. Direct status queries require a full check.
			return new IndexRefresh(previous, null);
		}
		Instant now = Instant.now();
		String boundary = Long.toUnsignedString(now.getEpochSecond()
				* 1_000_000_000L + now.getNano());
		Probe probe = state.query(repository, true, index);
		int requested = daemon ? 2 : repository.getConfig().getInt("core", null, //$NON-NLS-1$
				"fsmonitorHookVersion", -1); //$NON-NLS-1$
		String token = probe != null ? probe.token
				: daemon ? "builtin:fake" : requested == 2 ? "" : boundary; //$NON-NLS-1$ //$NON-NLS-2$
		BitSet dirty = new BitSet();
		Set<String> paths = new HashSet<>();
		boolean full = probe == null || probe.invalidationScopeUnknown
				|| repository.getConfig().getBoolean("core", null, //$NON-NLS-1$
						"ignoreCase", false); //$NON-NLS-1$
		if (probe == null || probe.changedPaths.contains("/")) { //$NON-NLS-1$
			full = true;
			dirty.set(0, index.getEntryCount());
		} else {
			for (int i = 0; i < index.getEntryCount(); i++) {
				if (!index.isFsMonitorValid(i)) {
					dirty.set(i);
				}
			}
			for (String path : probe.changedPaths) {
				String trimmed = path.endsWith("/") //$NON-NLS-1$
						? path.substring(0, path.length() - 1) : path;
				try {
					SystemReader.getInstance().checkPath(trimmed);
				} catch (org.eclipse.jgit.errors.CorruptObjectException unsafe) {
					full = true;
					continue;
				}
				paths.add(trimmed);
				if (trimmed.equals(".gitignore") //$NON-NLS-1$
						|| trimmed.endsWith("/.gitignore") //$NON-NLS-1$
						|| trimmed.equals(".gitattributes") //$NON-NLS-1$
						|| trimmed.endsWith("/.gitattributes")) { //$NON-NLS-1$
					full = true;
				}
				int matched = invalidateNotification(index, dirty, path);
				if (matched == 0 && repository.getConfig().getBoolean("core", //$NON-NLS-1$
						null, "ignoreCase", false)) { //$NON-NLS-1$
					invalidateCaseNotification(index, dirty, trimmed);
				}
			}
		}
		return new IndexRefresh(new FsMonitorCache(Constants.encode(token), dirty),
				full ? null : paths);
	}

	private static State storedState(Repository repository, FsMonitorCache stored)
			throws CharacterCodingException {
		boolean daemon = isDaemonSetting(configuredHook(repository));
		State state = new State();
		state.token = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(stored.getToken())).toString();
		int requested = daemon ? 2 : repository.getConfig().getInt("core", null, //$NON-NLS-1$
				"fsmonitorHookVersion", -1); //$NON-NLS-1$
		state.version = requested == 1 || requested != 2
				&& state.token.matches("[0-9]+") ? 1 : 2; //$NON-NLS-1$
		return state;
	}

	private static int invalidateNotification(DirCache index, BitSet dirty,
			String path) throws IOException {
		boolean directory = path.endsWith("/"); //$NON-NLS-1$
		String trimmed = directory ? path.substring(0, path.length() - 1) : path;
		if (index.getUntrackedCache() != null) {
			index.getUntrackedCache().invalidatePath(Constants.encode(trimmed));
		}
		int pos = directory ? -1 : index.findEntry(path);
		if (pos >= 0 && index.getEntry(pos).getStage() == 0) {
			dirty.set(pos);
			return 1;
		}
		if (!directory && index.getUntrackedCache() != null) {
			index.getUntrackedCache().invalidatePath(Constants.encode(trimmed));
		}
		String prefix = trimmed + "/"; //$NON-NLS-1$
		pos = index.findEntry(prefix);
		if (pos < 0) {
			pos = -pos - 1;
		}
		int count = 0;
		for (; pos < index.getEntryCount()
				&& index.getEntry(pos).getPathString().startsWith(prefix); pos++) {
			dirty.set(pos);
			count++;
		}
		return count;
	}

	private static void invalidateCaseNotification(DirCache index, BitSet dirty,
			String name) throws IOException {
		for (int i = 0; i < index.getEntryCount(); i++) {
			DirCacheEntry entry = index.getEntry(i);
			if (entry.getStage() == 0 && StringUtils.equalsIgnoreCase(name,
					entry.getPathString())) {
				invalidateNotification(index, dirty, entry.getPathString());
				return;
			}
		}
		String prefix = name + "/"; //$NON-NLS-1$
		for (int i = 0; i < index.getEntryCount(); i++) {
			String candidate = index.getEntry(i).getPathString();
			if (candidate.length() >= prefix.length()
					&& StringUtils.equalsIgnoreCase(prefix,
							candidate.substring(0, prefix.length()))) {
				invalidateNotification(index, dirty,
						candidate.substring(0, prefix.length()));
				return;
			}
		}
	}

	/**
	 * Validate persisted FSMN for one scan. The disk token is queried each time
	 * until a later index publication advances it; notifications are not lost
	 * between ephemeral scans. The opt-in whole-result cache owns its queries.
	 * @param repository repository to scan
	 * @param index already read index
	 * @return validation or conservative fallback
	 * @since 7.8
	 */
	public static IndexQuery queryStoredIndex(Repository repository, DirCache index) {
		if (isEnabled(repository)) {
			return new IndexQuery(null, false);
		}
		return queryIndexForCheckout(repository, index);
	}

	/**
	 * Validate persisted state for checkout independently of the status-result
	 * cache, which cannot authorize checkout of working-tree contents.
	 * @param repository repository to query
	 * @param index already read index
	 * @return provider query or conservative fallback
	 * @since 7.8
	 */
	public static IndexQuery queryIndexForCheckout(Repository repository, DirCache index) {
		FsMonitorCache stored = index.getFsMonitorCache();
		String configured = configuredHook(repository);
		if (stored == null || configured == null
				|| configured.isEmpty() || Boolean.FALSE.equals(
						UntrackedCacheConfig.parseBoolean(configured))) {
			return new IndexQuery(null, false);
		}
		try {
			State state = storedState(repository, stored);
			Probe probe = state.query(repository, true, index);
			if (probe != null) {
				probe.indexChecksum = index.getReadIndexChecksum();
				probe.dirtyEntries = stored.getDirtyEntries();
				probe.entryCount = index.getEntryCount();
			}
			return new IndexQuery(probe, state.requiresFullContentCheck());
		} catch (Exception invalid) {
			return new IndexQuery(null, true);
		}
	}

	/** Query result; valid means its token may seed a successful full scan. */
	public static final class Probe {
		private final String fingerprint;

		private final String token;

		private final int version;

		private final boolean unchanged;

		private final List<String> changedPaths;

		private boolean invalidationScopeUnknown;

		private byte[] indexChecksum;

		private BitSet dirtyEntries;

		private int entryCount;

		private final boolean hasUntrackedCache;

		private Probe(String fingerprint, String token, boolean unchanged,
				List<String> changedPaths, boolean hasUntrackedCache, int version) {
			this.fingerprint = fingerprint;
			this.token = token;
			this.version = version;
			this.unchanged = unchanged;
			this.changedPaths = List.copyOf(changedPaths);
			this.hasUntrackedCache = hasUntrackedCache;
		}

		/**
		 * Whether this provider result belongs to the supplied index payload.
		 * @param index index to scan
		 * @return whether native validity may be considered
		 * @since 7.8
		 */
		public boolean validatesIndex(DirCache index) {
			return indexChecksum != null && entryCount == index.getEntryCount()
					&& Arrays.equals(indexChecksum, index.getReadIndexChecksum())
					&& !changedPaths.contains("/"); //$NON-NLS-1$
		}

		/**
		 * Native dirty entries must be checked even without a new notification.
		 * @param index matching index
		 * @return paths requiring content validation
		 * @since 7.8
		 */
		public List<String> getIndexCheckPaths(DirCache index) {
			if (!validatesIndex(index)) return List.of("/"); //$NON-NLS-1$
			List<String> paths = new ArrayList<>(changedPaths);
			for (int i = dirtyEntries.nextSetBit(0); i >= 0;
					i = dirtyEntries.nextSetBit(i + 1)) {
				paths.add(index.getEntry(i).getPathString());
			}
			return paths;
		}

		/**
		 * Entries eligible for metadata reuse after forced paths are removed.
		 * @param index matching index
		 * @return operation-local entry identities, excluding gitlinks and flags
		 * @since 7.8
		 */
		public Set<DirCacheEntry> getIndexValidEntries(DirCache index) {
			return getIndexValidEntries(index, null);
		}

		/**
		 * Select validated metadata only for the supplied index positions.
		 * @param index matching index
		 * @param selected positions required by the walk, or null for all
		 * @return operation-local trusted entry identities
		 * @since 7.8
		 */
		public Set<DirCacheEntry> getIndexValidEntries(DirCache index, BitSet selected) {
			Set<DirCacheEntry> result = new HashSet<>();
			if (validatesIndex(index)) {
				for (int i = selected == null ? 0 : selected.nextSetBit(0);
						i >= 0 && i < index.getEntryCount();
						i = selected == null ? i + 1 : selected.nextSetBit(i + 1)) {
					DirCacheEntry entry = index.getEntry(i);
					if (!dirtyEntries.get(i) && entry.getStage() == 0
							&& !entry.isAssumeValid() && !entry.isSkipWorkTree()
							&& entry.getFileMode() != FileMode.GITLINK) {
						result.add(entry);
					}
				}
			}
			return result;
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

		private int version;

		private String checkedIndex;

		private byte[] indexChecksum;

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
			indexChecksum = null;
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
			return query(repository, false, null);
		}

		private synchronized Probe query(Repository repository, boolean storedQuery,
				DirCache suppliedIndex) {
			// Once correlation is lost, ordinary stat equality is insufficient
			// until a successful monitored scan establishes a new token.
			boolean lostCorrelation = token != null || fullContentCheckRequired;
			fullContentCheckRequired = false;
			if (!storedQuery && !isEnabled(repository)) {
				fallback(lostCorrelation);
				return null;
			}
			String configuredProvider = configuredHook(repository);
			boolean daemon = isDaemonSetting(configuredProvider);
			try (Trace2.Scope trace = Trace2.getInstance().region(repository,
					daemon ? "fsm_client" : "fsm_hook", "query")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				boolean supported = false;
				try {
					Config config = repository.getConfig();
					int requestedVersion = daemon ? 2 : config.getInt("core", null, //$NON-NLS-1$
							"fsmonitorHookVersion", -1); //$NON-NLS-1$
					boolean auto = requestedVersion != 1
							&& requestedVersion != 2;
					int queryVersion = daemon ? 2 : requestedVersion == 1 ? 1 : 2;
					String hook = configuredProvider;
					if (hook == null || hook.isEmpty()
							|| Boolean.FALSE.equals(UntrackedCacheConfig.parseBoolean(hook))) {
						fallback(lostCorrelation);
						return null;
					}
					File executable = daemon ? repository.getDirectory() : new File(hook);
					if (!daemon && !executable.isAbsolute()) {
						executable = new File(repository.getWorkTree(), hook);
					}
					if (!daemon && (!executable.isFile() || !executable.canExecute())) {
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
					String index;
					byte[] expectedChecksum = storedQuery
							? suppliedIndex.getReadIndexChecksum() : null;
					if (storedQuery) {
						IndexDigest current = digestIndex(
								repository.getIndexFile().toPath());
						if (!Arrays.equals(expectedChecksum, current.checksum)) {
							fallback(true);
							return null;
						}
						index = current.fingerprint;
						indexChecksum = current.checksum;
						hasUntrackedCache = suppliedIndex.getUntrackedCache() != null;
						supportedIndex = true;
						checkedIndex = index;
					} else {
						index = digest(repository.getIndexFile().toPath());
					}
					if (!storedQuery && !index.equals(checkedIndex)) {
						DirCache cache = repository.readDirCache();
						indexChecksum = cache.getReadIndexChecksum();
						hasUntrackedCache = cache.getUntrackedCache() != null;
						supportedIndex = true;
						for (int i = 0; i < cache.getEntryCount(); i++) {
							if (!storedQuery && cache.getEntry(i)
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
							daemon ? null : executable.toPath());
					if (storedQuery && Arrays.equals(expectedChecksum, indexChecksum)) {
						// The configured provider validates this persisted token;
						// no process-local Status result is implied by this anchor.
						fingerprint = key;
					}
					// A stale Watchman clock requests a fresh-instance reply
					// even
					// when this JVM starts after another client established the
					// watch.
					// Other v2 providers may reject it; rejection requires a
					// full scan.
					// Capture the v1 boundary before invoking the provider. Its
					// next query must include changes during this query/scan.
					Instant now = Instant.now();
					String timestamp = Long.toUnsignedString(now.getEpochSecond()
							* 1_000_000_000L + now.getNano());
					String since = daemon ? token != null && token.startsWith("builtin:") //$NON-NLS-1$
							? token : "0" : storedQuery && token != null ? token //$NON-NLS-1$
							: token != null && version == queryVersion ? token
									: queryVersion == 1 ? timestamp : "c:0:0:0:0"; //$NON-NLS-1$
					byte[] response;
					if (daemon) {
						response = FsMonitorDaemonClient.query(repository, since);
						trace.data("query/response-length", Integer.toString(response.length)); //$NON-NLS-1$
					} else try {
						response = Hooks.fsMonitor(repository, executable,
								queryVersion, since).call();
					} catch (IOException failure) {
						// Retry only a failed child, never a timeout, interruption,
						// oversized response or malformed successful v2 reply.
						if (!auto || !(failure
								.getCause() instanceof CommandFailedException)) {
							throw failure;
						}
						queryVersion = 1;
						since = token != null && (version == 1
								|| storedQuery && token.matches("[0-9]+")) ? token : timestamp; //$NON-NLS-1$
						response = Hooks.fsMonitor(repository, executable, 1,
								since).call();
					}
					String[] records = queryVersion == 1
							? parseVersionOne(response, timestamp) : parse(response);
					if (records == null) {
						trace.data("query/failed", "invalid-response"); //$NON-NLS-1$ //$NON-NLS-2$
						clear();
						fullContentCheckRequired = true;
						return null;
					}
					boolean noChanges = true;
					List<String> changedPaths = new ArrayList<>();
					boolean scopeUnknown = false;
					for (int i = 1; i < records.length; i++) {
						// Native Git does not have worktree entries inside its
						// .git directory.
						// Relevant metadata is independently fingerprinted
						// below.
						if (!records[i].equals(".git") //$NON-NLS-1$
								&& !records[i].startsWith(".git/")) {
							noChanges = false;
							changedPaths.add(records[i]);
						} else if (!records[i].equals(".git/index")) { //$NON-NLS-1$
							scopeUnknown = true;
						}
					}
					// External index/ref/config changes during the hook
					// invalidate reuse.
					if (!key.equals(fingerprint(repository,
							digest(repository.getIndexFile().toPath()),
							daemon ? null : executable.toPath()))) {
						trace.data("query/failed", "context-changed"); //$NON-NLS-1$ //$NON-NLS-2$
						clear();
						fullContentCheckRequired = true;
						return null;
					}
					boolean sameContext = key.equals(fingerprint)
							&& (storedQuery || version == queryVersion);
					// The supplied hook can return an ephemeral zero-epoch
					// clock
					// when its daemon is unavailable. Such a reply cannot
					// validate
					// cached data, even if it lists only Git metadata.
					boolean sameInstance = queryVersion == 1
							? token != null && (version == 1 || storedQuery)
									&& token.matches("[0-9]+") //$NON-NLS-1$
									&& Long.compareUnsigned(
											Long.parseUnsignedLong(timestamp),
											Long.parseUnsignedLong(token)) >= 0
							: sameMonitorInstance(token, records[0]);
					noChanges &= sameInstance;
					if (!sameContext || !sameInstance) {
						changedPaths = List.of("/"); //$NON-NLS-1$
					}
					trace.data("cache/hit", //$NON-NLS-1$
							Boolean.toString(noChanges && sameContext));
					Probe result = new Probe(key, records[0], noChanges && sameContext,
							changedPaths, hasUntrackedCache, queryVersion);
					result.invalidationScopeUnknown = scopeUnknown;
					return result;
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
			version = probe.version;
		}
	}

	private static boolean sameMonitorInstance(String before, String after) {
		if (before == null || before.isEmpty() || after.isEmpty()) {
			return false;
		}
		if (before.startsWith("builtin:") || after.startsWith("builtin:")) { //$NON-NLS-1$ //$NON-NLS-2$
			String[] oldClock = before.split(":", -1); //$NON-NLS-1$
			String[] newClock = after.split(":", -1); //$NON-NLS-1$
			if (oldClock.length != 3 || newClock.length != 3
					|| !oldClock[0].equals("builtin") || !newClock[0].equals("builtin") //$NON-NLS-1$ //$NON-NLS-2$
					|| oldClock[1].isEmpty() || !oldClock[1].equals(newClock[1])
					|| !oldClock[2].matches("[0-9]+") || !newClock[2].matches("[0-9]+")) { //$NON-NLS-1$ //$NON-NLS-2$
				return false;
			}
			try {
				return Long.compareUnsigned(Long.parseUnsignedLong(newClock[2]),
						Long.parseUnsignedLong(oldClock[2])) >= 0;
			} catch (NumberFormatException invalidClock) {
				return false;
			}
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
		update(md, executable == null ? "builtin" : digest(executable)); //$NON-NLS-1$
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

	private static boolean isDaemonSetting(String value) {
		return value != null && isBooleanSetting(value)
				&& Boolean.TRUE.equals(UntrackedCacheConfig.parseBoolean(value));
	}

	// C Git parse.c: boolean text or a signed int with base-0 and k/m/g units.
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

	private record IndexDigest(String fingerprint, byte[] checksum) {
	}

	private static IndexDigest digestIndex(Path file) throws Exception {
		if (!Files.exists(file)) {
			return new IndexDigest("absent", new byte[0]); //$NON-NLS-1$
		}
		MessageDigest full = MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
		MessageDigest payload = Constants.newMessageDigest();
		int footerSize = Constants.OBJECT_ID_LENGTH;
		byte[] buffer = new byte[64 * 1024 + footerSize];
		int retained = 0;
		try (InputStream in = Files.newInputStream(file)) {
			for (int n; (n = in.read(buffer, retained,
					buffer.length - retained)) != -1;) {
				full.update(buffer, retained, n);
				int available = retained + n;
				int content = Math.max(0, available - footerSize);
				payload.update(buffer, 0, content);
				retained = available - content;
				System.arraycopy(buffer, content, buffer, 0, retained);
			}
		}
		if (retained != footerSize) {
			throw new IOException("Truncated index footer"); //$NON-NLS-1$
		}
		byte[] checksum = payload.digest();
		boolean zero = true;
		for (int i = 0; i < footerSize; i++) {
			zero &= buffer[i] == 0;
		}
		// Zero skipHash footers do not replace the computed payload checksum.
		if (!zero && !Arrays.equals(checksum,
				Arrays.copyOf(buffer, footerSize))) {
			throw new IOException("Invalid index checksum"); //$NON-NLS-1$
		}
		return new IndexDigest(HexFormat.of().formatHex(full.digest()), checksum);
	}


	static String[] parseVersionOne(byte[] response, String timestamp)
			throws CharacterCodingException {
		if (response.length == 0) {
			return new String[] { timestamp };
		}
		// The original fsmonitor-all fixture uses echo "/", including LF.
		if (response[0] == '/') {
			return new String[] { timestamp, "/" }; //$NON-NLS-1$
		}
		String paths = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(response)).toString();
		return parseRecords((timestamp + '\0' + paths).split("\u0000", -1)); //$NON-NLS-1$
	}

	static String[] parse(byte[] response) throws CharacterCodingException {
		if (response.length == 0) {
			return null;
		}
		String text = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(response)).toString();
		return parseRecords(text.split("\u0000", -1)); //$NON-NLS-1$
	}

	private static String[] parseRecords(String[] fields) {
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
