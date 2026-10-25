/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.treewalk;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.internal.storage.file.UntrackedCacheConfig;
import org.eclipse.jgit.internal.util.IndexEntrySelection;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.eclipse.jgit.util.Trace2;

/** Operation-local metadata preload, adapted from C Git's preload-index.c. */
final class IndexPreloader {

	private static final int THREAD_COST = 500;

	private static final int MAX_PARALLEL = 20;

	private final Map<File, Map<String, FS.Attributes>> directories = new HashMap<>();

	private final Map<File, Map<String, MonitorEntry>> monitoredDirectories = new HashMap<>();

	private record MonitorEntry(FileMode mode, Instant modified, long length) {
	}

	private FS monitorFs;

	private long statCalls;

	static IndexPreloader fromMonitor(Repository repository, FS fs,
			Collection<DirCacheEntry> entries) {
		IndexPreloader result = new IndexPreloader();
		result.monitorFs = fs;
		Map<String, File> parents = new HashMap<>();
		File root = repository.getWorkTree();
		for (DirCacheEntry entry : entries) {
			String path = entry.getPathString();
			int slash = path.lastIndexOf('/');
			String directory = slash < 0 ? "" : path.substring(0, slash); //$NON-NLS-1$
			File parent = parents.computeIfAbsent(directory,
					key -> fs.normalize(new File(root, key)));
			result.monitoredDirectories
					.computeIfAbsent(parent, unused -> new HashMap<>())
					.put(fs.normalize(path.substring(slash + 1)),
							new MonitorEntry(entry.getFileMode(),
									entry.getLastModifiedInstant(),
									Integer.toUnsignedLong(entry.getLength())));
		}
		return result;
	}

	static IndexPreloader load(Repository repository, FS fs, DirCache index,
			ProgressMonitor monitor) {
		return load(repository, fs, index, monitor, null, Set.of());
	}

	static IndexPreloader load(Repository repository, FS fs, DirCache index,
			ProgressMonitor monitor, IndexPreloader monitored,
			Set<DirCacheEntry> trusted) {
		return load(repository, fs, index, monitor, monitored, trusted, TreeFilter.ALL);
	}

	@SuppressWarnings("deprecation")
	static IndexPreloader load(Repository repository, FS fs, DirCache index,
			ProgressMonitor monitor, IndexPreloader monitored,
			Set<DirCacheEntry> trusted, TreeFilter filter) {
		if (cancelled(monitor)) {
			return monitored;
		}
		BitSet selected = null;
		if (filter != null && filter != TreeFilter.ALL) {
			var paths = filter.getPathsBestEffort();
			if (paths.isEmpty()) {
				return monitored;
			}
			selected = IndexEntrySelection.select(index, paths.get(), monitor);
			if (selected != null && selected.isEmpty()) {
				return monitored;
			}
		}
		int[] positions = selected == null ? null : selected.stream().toArray();
		int workCount = positions == null ? index.getEntryCount() : positions.length;
		int threads = Math.min(MAX_PARALLEL, workCount / THREAD_COST);
		if (index.getEntryCount() > 1 && threads < 2
				&& Boolean.TRUE.equals(UntrackedCacheConfig.parseBoolean(
						SystemReader.getInstance().getenv("GIT_TEST_PRELOAD_INDEX")))) { //$NON-NLS-1$
			threads = 2;
		}
		if (threads < 2 || cancelled(monitor)) {
			return monitored;
		}
		ExecutorService executor = null;
		List<Future<IndexPreloader>> tasks = new ArrayList<>(threads);
		try (Trace2.Scope trace = Trace2.getInstance().region(repository,
				"index", "preload")) { //$NON-NLS-1$ //$NON-NLS-2$
			try {
				executor = Executors.newFixedThreadPool(threads, task -> {
					Thread worker = new Thread(task, "JGit-IndexPreload"); //$NON-NLS-1$
					worker.setDaemon(true);
					return worker;
				});
				int work = (workCount + threads - 1) / threads;
				for (int offset = 0; offset < workCount; offset += work) {
					int start = offset;
					int end = Math.min(workCount, offset + work);
					tasks.add(
							executor.submit(() -> read(repository.getWorkTree(),
									fs, index, start, end, trusted, positions)));
				}
				IndexPreloader result = monitored == null ? new IndexPreloader() : monitored;
				for (Future<IndexPreloader> task : tasks) {
					IndexPreloader part;
					while (true) {
						if (cancelled(monitor)) {
							return null;
						}
						try {
							part = task.get(50, TimeUnit.MILLISECONDS);
							break;
						} catch (TimeoutException waiting) {
							// Poll cancellation on the calling thread only.
						}
					}
					part.directories.forEach(
							(directory, attributes) -> result.directories
									.computeIfAbsent(directory,
											key -> new HashMap<>())
									.putAll(attributes));
					result.statCalls += part.statCalls;
				}
				if (trace.isEnabled()) {
					trace.data("preload/sum_lstat", //$NON-NLS-1$
							Long.toString(result.statCalls));
					trace.data("preload/threads", Integer.toString(threads)); //$NON-NLS-1$
				}
				return result;
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				return null;
			} catch (ExecutionException | RuntimeException unavailable) {
				// Preloading is optional; a normal walk remains authoritative.
				return null;
			} finally {
				for (Future<IndexPreloader> task : tasks) {
					task.cancel(true);
				}
				if (executor != null) {
					executor.shutdownNow();
				}
			}
		}
	}


	private static boolean cancelled(ProgressMonitor monitor) {
		return Thread.currentThread().isInterrupted()
				|| monitor != null && monitor.isCancelled();
	}

	private static IndexPreloader read(File root, FS fs, DirCache index,
			int start, int end, Set<DirCacheEntry> trusted, int[] selected) {
		IndexPreloader result = new IndexPreloader();
		Map<File, Boolean> safeDirectories = new HashMap<>();
		for (int position = start; position < end
				&& !Thread.currentThread().isInterrupted(); position++) {
			int i = selected == null ? position : selected[position];
			DirCacheEntry entry = index.getEntry(i);
			if (trusted.contains(entry) || entry.getStage() != 0 || entry.isAssumeValid()
					|| entry.isSkipWorkTree()
					|| entry.getFileMode() == FileMode.GITLINK) {
				continue;
			}
			String path = entry.getPathString();
			// Root entries were already read by FileTreeIterator's constructor.
			if (path.indexOf('/') < 0) {
				continue;
			}
			File file = fs.normalize(new File(root, path));
			File parent = file.getParentFile();
			if (!safeDirectory(root, parent, fs, safeDirectories)) {
				continue;
			}
			FS.Attributes attributes = fs.getAttributes(file);
			result.statCalls++;
			if (attributes.isRegularFile() || attributes.isSymbolicLink()) {
				result.directories
						.computeIfAbsent(parent, key -> new HashMap<>())
						.put(file.getName(), attributes);
			}
		}
		return result;
	}

	private static boolean safeDirectory(File root, File directory, FS fs,
			Map<File, Boolean> checked) {
		if (root.equals(directory)) {
			return true;
		}
		if (directory == null) {
			return false;
		}
		Boolean known = checked.get(directory);
		if (known != null) {
			return known.booleanValue();
		}
		boolean safe = safeDirectory(root, directory.getParentFile(), fs,
				checked);
		if (safe) {
			FS.Attributes attributes = fs.getAttributes(directory);
			safe = attributes.isDirectory() && !attributes.isSymbolicLink();
		}
		checked.put(directory, Boolean.valueOf(safe));
		return safe;
	}

	Map<String, FS.Attributes> takeDirectory(File directory) {
		return takeDirectory(directory, null);
	}

	Map<String, FS.Attributes> takeDirectory(File directory, File[] files) {
		Map<String, FS.Attributes> result = directories.remove(directory);
		Map<String, MonitorEntry> trusted = monitoredDirectories.remove(directory);
		if (trusted == null) {
			return result;
		}
		if (result == null) {
			result = new HashMap<>();
		}
		if (files == null) {
			files = trusted.keySet().stream()
					.map(name -> new File(directory, name)).toArray(File[]::new);
		}
		for (File file : files) {
			String name = monitorFs.normalize(file.getName());
			MonitorEntry entry = trusted.get(name);
			if (entry != null && !result.containsKey(name)) {
				result.put(name, FS.Attributes.fromIndex(monitorFs, monitorFs.normalize(file),
						entry.mode(), entry.modified(), entry.length()));
			}
		}
		return result;
	}
}
