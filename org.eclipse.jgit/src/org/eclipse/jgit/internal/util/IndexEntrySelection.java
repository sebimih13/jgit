/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.internal.util;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;

/**
 * Conservative index ranges for literal tree-filter bounds.
 * 
 * @since 7.8
 */
public final class IndexEntrySelection {
	private IndexEntrySelection() {
	}

	/**
	 * Select a filter's available bounds without evaluating it on tree entries.
	 * 
	 * @param index
	 *            sorted index
	 * @param filter
	 *            filter, or null for the whole index
	 * @param monitor
	 *            cancellation monitor, or null
	 * @return selected positions, or null when bounds are unavailable
	 * @since 7.8
	 */
	@SuppressWarnings("deprecation")
	public static BitSet select(DirCache index, TreeFilter filter,
			ProgressMonitor monitor) {
		if (filter == null || filter == TreeFilter.ALL)
			return null;
		Class<?> type = filter.getClass();
		// Changed-path hints from arbitrary filters need not bound their walk.
		if (type != PathFilter.class
				&& type.getEnclosingClass() != PathFilterGroup.class)
			return null;
		var paths = filter.getPathsBestEffort();
		return paths.isPresent() ? select(index, paths.get(), monitor) : null;
	}

	/**
	 * Select exact paths and slash-delimited descendants from raw path bounds.
	 * Cancellation abandons the bound so required checks remain conservative.
	 * 
	 * @param index
	 *            sorted index
	 * @param paths
	 *            raw literal bounds
	 * @param monitor
	 *            cancellation monitor, or null
	 * @return selected positions, or null for root/unknown/cancelled bounds
	 * @since 7.8
	 */
	public static BitSet select(DirCache index, Collection<byte[]> paths,
			ProgressMonitor monitor) {
		BitSet selected = new BitSet();
		for (byte[] path : paths) {
			if (cancelled(monitor))
				return null;
			int length = path.length;
			while (length > 0 && path[length - 1] == '/')
				length--;
			if (length == 0)
				return null;
			int exact = index.findEntry(path, length);
			if (exact >= 0)
				selected.set(exact);
			byte[] prefix = Arrays.copyOf(path, length + 1);
			prefix[length] = '/';
			int start = index.findEntry(prefix, prefix.length);
			if (start < 0)
				start = -start - 1;
			for (int i = start; i < index.getEntryCount(); i++) {
				byte[] candidate = index.getEntry(i).getRawPath();
				if (candidate.length < prefix.length || Arrays.mismatch(prefix,
						0, prefix.length, candidate, 0, prefix.length) >= 0)
					break;
				selected.set(i);
				if ((i & 255) == 0 && cancelled(monitor))
					return null;
			}
		}
		return selected;
	}

	private static boolean cancelled(ProgressMonitor monitor) {
		return Thread.currentThread().isInterrupted()
				|| monitor != null && monitor.isCancelled();
	}
}
