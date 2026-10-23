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
import java.util.ArrayList;
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
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.Trace2;

/** Operation-local metadata preload, adapted from C Git's preload-index.c. */
final class IndexPreloader {

	private static final int THREAD_COST = 500;

	private static final int MAX_PARALLEL = 20;

	private final Map<File, Map<String, FS.Attributes>> directories = new HashMap<>();

	private long statCalls;

	static IndexPreloader fromMonitor(Repository repository, FS fs,
			Collection<DirCacheEntry> entries) {
		IndexPreloader result = new IndexPreloader();
		for (DirCacheEntry entry : entries) {
			File file = fs.normalize(new File(repository.getWorkTree(), entry.getPathString()));
			result.directories.computeIfAbsent(file.getParentFile(), unused -> new HashMap<>())
					.put(file.getName(), FS.Attributes.fromIndex(fs, file,
							entry.getFileMode(), entry.getLastModifiedInstant(),
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
		int threads = Math.min(MAX_PARALLEL,
				index.getEntryCount() / THREAD_COST);
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
				int work = (index.getEntryCount() + threads - 1) / threads;
				for (int offset = 0; offset < index
						.getEntryCount(); offset += work) {
					int start = offset;
					int end = Math.min(index.getEntryCount(), offset + work);
					tasks.add(
							executor.submit(() -> read(repository.getWorkTree(),
									fs, index, start, end, trusted)));
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
			int start, int end, Set<DirCacheEntry> trusted) {
		IndexPreloader result = new IndexPreloader();
		Map<File, Boolean> safeDirectories = new HashMap<>();
		for (int i = start; i < end
				&& !Thread.currentThread().isInterrupted(); i++) {
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
		return directories.remove(directory);
	}
}
