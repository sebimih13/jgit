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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jgit.util.FS;

/** Bounded parallel metadata reads for a single large directory listing. */
final class DirectoryMetadata {
	static final int MIN_PARALLEL = 1000;

	private static final int FILES_PER_WORKER = 500;

	private static final int MAX_WORKERS = 20;

	private static final int CHUNK_SIZE = 64;

	private DirectoryMetadata() {
	}

	static FS.Attributes[] read(FS fs, File[] files,
			Map<String, FS.Attributes> cached) {
		if (files.length < MIN_PARALLEL
				|| Thread.currentThread().isInterrupted()) {
			return null;
		}
		FS.Attributes[] attributes = new FS.Attributes[files.length];
		int missing = 0;
		for (int i = 0; i < files.length; i++) {
			if (cached != null) {
				attributes[i] = cached.get(files[i].getName());
			}
			if (attributes[i] == null) {
				missing++;
			}
		}
		int workers = Math.min(MAX_WORKERS, missing / FILES_PER_WORKER);
		if (workers < 2) {
			return null;
		}
		ExecutorService executor = null;
		List<Future<?>> tasks = new ArrayList<>(workers);
		AtomicInteger next = new AtomicInteger();
		try {
			executor = Executors.newFixedThreadPool(workers, task -> {
				Thread worker = new Thread(task, "JGit-DirectoryMetadata"); //$NON-NLS-1$
				worker.setDaemon(true);
				return worker;
			});
			for (int i = 0; i < workers; i++) {
				tasks.add(executor.submit(() -> {
					for (int start; !Thread.currentThread().isInterrupted()
							&& (start = next
									.getAndAdd(CHUNK_SIZE)) < files.length;) {
						int end = Math.min(start + CHUNK_SIZE, files.length);
						for (int j = start; j < end; j++) {
							if (attributes[j] == null) {
								attributes[j] = fs
										.getAttributes(fs.normalize(files[j]));
							}
						}
					}
				}));
			}
			for (Future<?> task : tasks) {
				task.get();
			}
			return attributes;
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return null;
		} catch (ExecutionException | RuntimeException unavailable) {
			// Discard partial results and let the caller use its normal serial
			// path.
			return null;
		} finally {
			for (Future<?> task : tasks) {
				task.cancel(true);
			}
			if (executor != null) {
				executor.shutdownNow();
			}
		}
	}
}
