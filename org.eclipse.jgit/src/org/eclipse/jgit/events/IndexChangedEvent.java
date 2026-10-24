/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.events;

import java.util.Set;

/**
 * Describes a change to one or more paths in the index file.
 */
public class IndexChangedEvent extends RepositoryEvent<IndexChangedListener> {
	private boolean internal;

	private final Set<String> fsMonitorPaths;

	private final byte[] indexChecksum;

	/**
	 * Notify that the index changed
	 *
	 * @param internal
	 *                     {@code true} if the index was changed by the same
	 *                     JGit process
	 * @since 5.0
	 */
	public IndexChangedEvent(boolean internal) {
		this(internal, null);
	}

	/**
	 * Notify an index change together with consumed monitor notifications.
	 *
	 * @param internal whether this process wrote the index
	 * @param fsMonitorPaths repository-relative worktree paths consumed by the
	 *            writer; null means unknown scope and requires a full refresh
	 * @since 7.8
	 */
	public IndexChangedEvent(boolean internal, Set<String> fsMonitorPaths) {
		this(internal, fsMonitorPaths, null);
	}

	/**
	 * Notify a change with consumed paths correlated to the written index.
	 * @param internal whether this process wrote the index
	 * @param fsMonitorPaths worktree paths, or null for unknown scope
	 * @param indexChecksum computed payload checksum, or null if unavailable
	 * @since 7.8
	 */
	public IndexChangedEvent(boolean internal, Set<String> fsMonitorPaths,
			byte[] indexChecksum) {
		this.indexChecksum = indexChecksum == null ? null : indexChecksum.clone();
		this.internal = internal;
		this.fsMonitorPaths = fsMonitorPaths == null ? null
				: Set.copyOf(fsMonitorPaths);
	}

	/**
	 * Get the index payload checksum associated with the consumed paths.
	 * @return a copy of the checksum, or null when correlation is unavailable
	 * @since 7.8
	 */
	public byte[] getIndexChecksum() {
		return indexChecksum == null ? null : indexChecksum.clone();
	}

	/**
	 * Get worktree notifications consumed before the index changed.
	 * @return immutable paths, possibly empty, or null for unknown scope;
	 *         these supplement rather than replace the index-entry delta
	 * @since 7.8
	 */
	public Set<String> getFsMonitorPaths() {
		return fsMonitorPaths;
	}

	/**
	 * Whether the index was changed by the same JGit process
	 *
	 * @return {@code true} if the index was changed by the same JGit process
	 * @since 5.0
	 */
	public boolean isInternal() {
		return internal;
	}

	@Override
	public Class<IndexChangedListener> getListenerType() {
		return IndexChangedListener.class;
	}

	@Override
	public void dispatch(IndexChangedListener listener) {
		listener.onIndexChanged(this);
	}
}
