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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;

import com.googlecode.javaewah.EWAHCompressedBitmap;

/**
 * Native Git UNTR index-extension data.
 * <p>
 * The layout follows gitformat-index and dir.c: environment identity, two
 * exclude-file records, traversal flags, a depth-first directory tree, three
 * EWAH bitmaps, directory stat records and per-directory ignore object IDs.
 * Parsing never establishes filesystem validity; callers must separately
 * validate the identity, traversal options, index and exclusion inputs.
 * </p>
 */
public final class UntrackedCache {
	/** Maximum optional extension size accepted into memory. */
	public static final int MAX_EXTENSION_SIZE = 64 * 1024 * 1024;

	/** Native DIR_SHOW_OTHER_DIRECTORIES flag. */
	public static final int SHOW_OTHER_DIRECTORIES = 1 << 1;

	private static final int MAX_DIRECTORIES = 500_000;

	private static final int MAX_NAMES = 1_000_000;

	/**
	 * Native stat_data has nine unsigned 32-bit fields, without an entry mode.
	 */
	public static final class StatData {
		/** ctime sec/nsec, mtime sec/nsec, device, inode, uid, gid, size. */
		public final int[] fields = new int[9];

		private static StatData read(ByteBuffer in) {
			StatData result = new StatData();
			for (int i = 0; i < result.fields.length; i++) {
				result.fields[i] = in.getInt();
			}
			return result;
		}

		private void write(DataOutputStream out) throws IOException {
			for (int field : fields) {
				out.writeInt(field);
			}
		}
	}

	/** The stat and blob object ID of an exclusion file. */
	public static final class OidStat {
		/** Exclusion-file stat data. */
		public StatData stat = new StatData();

		/** Zero means no exclusion file. */
		public ObjectId oid = ObjectId.zeroId();
	}

	/** A directory, corresponding to native untracked_cache_dir. */
	public static final class Directory {
		/** Raw name bytes; only the root has an empty name. */
		public final byte[] name;

		/** Subdirectories, including non-recursed nodes retained in memory. */
		public final List<Directory> directories = new ArrayList<>();

		/** Cached raw untracked names, relative to this directory. */
		public final List<byte[]> untracked = new ArrayList<>();

		/** Directory stat data, meaningful only when valid is set. */
		public StatData stat = new StatData();

		/** Blob object ID of this directory's exclusion file. */
		public ObjectId excludeOid = ObjectId.zeroId();

		/** Whether this node was only checked for a non-empty subtree. */
		public boolean checkOnly;

		/** Whether the cached untracked list and stat are valid. */
		public boolean valid;

		/** Whether to retain this node in the serialized traversal tree. */
		public boolean recurse;

		private boolean sortedChildren;

		/**
		 * @param name
		 *            raw directory component, empty for the root
		 */
		public Directory(byte[] name) {
			this.name = name.clone();
		}
	}

	/**
	 * NUL-separated environment identities; preserve legacy extra identities.
	 */
	public byte[] ident = new byte[0];

	/** Git common-directory info/exclude record. */
	public final OidStat infoExclude = new OidStat();

	/** Configured global excludes record. */
	public final OidStat excludesFile = new OidStat();

	/** Native dir_struct traversal flags. */
	public int dirFlags;

	/** Per-directory exclusion filename. */
	public byte[] excludePerDir = Constants.encode(Constants.DOT_GIT_IGNORE);

	/** Root directory, or null for an uninitialized cache. */
	public Directory root;

	/** Newly created directory nodes; not serialized. */
	public int dirCreated;

	/** Ignore hierarchy invalidations; not serialized. */
	public int gitignoreInvalidated;

	/** Approximate invalidated-directory count; not serialized. */
	public int dirInvalidated;

	/** Actual directory opens; not serialized. */
	public int dirOpened;

	/**
	 * Read an optional SHA-1 UNTR payload. Raw filename bytes are preserved,
	 * including names that cannot be represented by JGit's UTF-8 path API.
	 *
	 * @param raw
	 *            extension payload without its signature/size header
	 * @return parsed data, not yet validated against the filesystem
	 * @throws IOException
	 *             if the payload is malformed or exceeds the limit
	 */
	public static UntrackedCache read(byte[] raw) throws IOException {
		if (raw.length == 0 || raw.length > MAX_EXTENSION_SIZE
				|| raw[raw.length - 1] != 0) {
			throw malformed();
		}
		ByteBuffer in = ByteBuffer.wrap(raw);
		try {
			UntrackedCache result = new UntrackedCache();
			result.ident = bytes(in, varint(in));
			if (result.ident.length != 0
					&& result.ident[result.ident.length - 1] != 0) {
				throw malformed();
			}
			result.infoExclude.stat = StatData.read(in);
			result.excludesFile.stat = StatData.read(in);
			result.dirFlags = in.getInt();
			result.infoExclude.oid = oid(in);
			result.excludesFile.oid = oid(in);
			result.excludePerDir = string(in);
			int count = varint(in);
			if (count == 0) {
				// C Git writes a single zero for an uninitialized root;
				// the documented extra guard zero is accepted as well.
				if (in.hasRemaining() && in.get() != 0 || in.hasRemaining()) {
					throw malformed();
				}
				return result;
			}
			if (count > MAX_DIRECTORIES || count > in.remaining() / 3) {
				throw malformed();
			}
			List<Directory> nodes = new ArrayList<>(count);
			Deque<Frame> parents = new ArrayDeque<>();
			int names = 0;
			for (int i = 0; i < count; i++) {
				int files = varint(in);
				int children = varint(in);
				if (files > MAX_NAMES - names || files > in.remaining()
						|| children > count - i - 1) {
					throw malformed();
				}
				names += files;
				Directory node = new Directory(string(in));
				checkName(node.name, i == 0);
				node.recurse = true;
				if (i == 0) {
					result.root = node;
				} else {
					while (!parents.isEmpty()
							&& parents.peek().remaining == 0) {
						parents.pop();
					}
					if (parents.isEmpty()) {
						throw malformed();
					}
					Frame parent = parents.peek();
					parent.node.directories.add(node);
					parent.remaining--;
				}
				for (int j = 0; j < files; j++) {
					byte[] name = string(in);
					if (name.length == 0) {
						throw malformed();
					}
					node.untracked.add(name);
				}
				nodes.add(node);
				if (children != 0) {
					parents.push(new Frame(node, children));
				}
			}
			for (Frame frame : parents) {
				if (frame.remaining != 0) {
					throw malformed();
				}
			}
			BitSet valid = bitmap(in, count);
			BitSet checkOnly = bitmap(in, count);
			BitSet hasOid = bitmap(in, count);
			for (int i = valid.nextSetBit(0); i >= 0; i = valid
					.nextSetBit(i + 1)) {
				nodes.get(i).stat = StatData.read(in);
				nodes.get(i).valid = true;
			}
			for (int i = checkOnly.nextSetBit(0); i >= 0; i = checkOnly
					.nextSetBit(i + 1)) {
				nodes.get(i).checkOnly = true;
			}
			for (int i = hasOid.nextSetBit(0); i >= 0; i = hasOid
					.nextSetBit(i + 1)) {
				nodes.get(i).excludeOid = oid(in);
			}
			if (in.remaining() != 1 || in.get() != 0) {
				throw malformed();
			}
			return result;
		} catch (BufferUnderflowException | IllegalArgumentException invalid) {
			throw new IOException("Invalid untracked cache extension", invalid); //$NON-NLS-1$
		}
	}

	/**
	 * Write a native-compatible SHA-1 UNTR payload. Invalid directories lose
	 * their untracked/check-only data, and non-recursed children are omitted,
	 * as in native write_one_dir.
	 *
	 * @return serialized extension payload
	 * @throws IOException
	 *             if the model is invalid or exceeds the limit
	 */
	public byte[] write() throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(buffer);
		if (ident.length > MAX_EXTENSION_SIZE
				|| ident.length != 0 && ident[ident.length - 1] != 0) {
			throw malformed();
		}
		varint(out, ident.length);
		out.write(ident);
		infoExclude.stat.write(out);
		excludesFile.stat.write(out);
		out.writeInt(dirFlags);
		infoExclude.oid.copyRawTo(out);
		excludesFile.oid.copyRawTo(out);
		string(out, excludePerDir);
		if (buffer.size() > MAX_EXTENSION_SIZE) {
			throw malformed();
		}
		if (root == null) {
			varint(out, 0);
			return buffer.toByteArray();
		}
		List<Directory> nodes = new ArrayList<>();
		Deque<Directory> pending = new ArrayDeque<>();
		Set<Directory> visited = Collections
				.newSetFromMap(new IdentityHashMap<>());
		pending.push(root);
		while (!pending.isEmpty()) {
			Directory node = pending.pop();
			if (!visited.add(node)) {
				throw malformed();
			}
			checkName(node.name, nodes.isEmpty());
			nodes.add(node);
			if (nodes.size() > MAX_DIRECTORIES) {
				throw malformed();
			}
			for (int i = node.directories.size() - 1; i >= 0; i--) {
				Directory child = node.directories.get(i);
				if (child.recurse) {
					pending.push(child);
				}
			}
		}
		varint(out, nodes.size());
		EWAHCompressedBitmap valid = new EWAHCompressedBitmap();
		EWAHCompressedBitmap checkOnly = new EWAHCompressedBitmap();
		EWAHCompressedBitmap hasOid = new EWAHCompressedBitmap();
		int names = 0;
		for (int i = 0; i < nodes.size(); i++) {
			Directory node = nodes.get(i);
			if (!node.valid) {
				node.untracked.clear();
				node.checkOnly = false;
			}
			if (node.untracked.size() > MAX_NAMES - names) {
				throw malformed();
			}
			names += node.untracked.size();
			varint(out, node.untracked.size());
			int children = 0;
			for (Directory child : node.directories) {
				if (child.recurse) {
					children++;
				}
			}
			varint(out, children);
			string(out, node.name);
			for (byte[] name : node.untracked) {
				if (name.length == 0) {
					throw malformed();
				}
				string(out, name);
			}
			if (node.valid) {
				valid.set(i);
			}
			if (node.checkOnly) {
				checkOnly.set(i);
			}
			if (!ObjectId.zeroId().equals(node.excludeOid)) {
				hasOid.set(i);
			}
			if (buffer.size() > MAX_EXTENSION_SIZE) {
				throw malformed();
			}
		}
		valid.serialize(out);
		checkOnly.serialize(out);
		hasOid.serialize(out);
		for (Directory node : nodes) {
			if (node.valid) {
				node.stat.write(out);
			}
		}
		for (Directory node : nodes) {
			if (!ObjectId.zeroId().equals(node.excludeOid)) {
				node.excludeOid.copyRawTo(out);
			}
		}
		out.writeByte(0);
		if (buffer.size() > MAX_EXTENSION_SIZE) {
			throw malformed();
		}
		return buffer.toByteArray();
	}

	/**
	 * Find or create a directory component in native unsigned byte order.
	 *
	 * @param parent
	 *            parent, or null when no cache is active
	 * @param name
	 *            raw component
	 * @return existing/new directory, or null when parent is null
	 * @throws IOException
	 *             for an unsafe component
	 */
	public Directory lookupDirectory(Directory parent, byte[] name)
			throws IOException {
		if (parent == null) {
			return null;
		}
		if (name.length > 0 && name[name.length - 1] == '/') {
			name = Arrays.copyOf(name, name.length - 1);
		}
		checkName(name, false);
		if (!parent.sortedChildren) {
			parent.directories
					.sort((a, b) -> Arrays.compareUnsigned(a.name, b.name));
			parent.sortedChildren = true;
		}
		int low = 0, high = parent.directories.size();
		while (low < high) {
			int mid = low + (high - low) / 2;
			int cmp = Arrays.compareUnsigned(name,
					parent.directories.get(mid).name);
			if (cmp == 0) {
				return parent.directories.get(mid);
			}
			if (cmp < 0) {
				high = mid;
			} else {
				low = mid + 1;
			}
		}
		Directory created = new Directory(name);
		parent.directories.add(low, created);
		dirCreated++;
		return created;
	}

	/**
	 * Invalidate a changed listing and reset child recurse flags.
	 *
	 * @param node
	 *            changed directory
	 */
	public void invalidateDirectory(Directory node) {
		if (node.valid) {
			dirInvalidated++;
		}
		node.valid = false;
		node.untracked.clear();
		for (Directory child : node.directories) {
			child.recurse = false;
		}
	}

	/**
	 * Invalidate an ignore-file hierarchy, retaining its directory nodes.
	 *
	 * @param node
	 *            root of the affected hierarchy
	 */
	public void invalidateIgnore(Directory node) {
		gitignoreInvalidated++;
		Deque<Directory> pending = new ArrayDeque<>();
		Set<Directory> visited = Collections
				.newSetFromMap(new IdentityHashMap<>());
		pending.push(node);
		while (!pending.isEmpty()) {
			Directory directory = pending.pop();
			if (!visited.add(directory)) {
				continue;
			}
			directory.valid = false;
			directory.untracked.clear();
			pending.addAll(directory.directories);
		}
	}

	/**
	 * Invalidate an index pathname. Normal-mode summaries invalidate ancestors;
	 * all-mode invalidates only the containing directory.
	 *
	 * @param path
	 *            validated raw index pathname relative to the worktree
	 * @throws IOException
	 *             for unsafe components
	 */
	public void invalidatePath(byte[] path) throws IOException {
		if (root == null) {
			return;
		}
		List<byte[]> components = new ArrayList<>();
		int start = 0;
		for (int i = 0; i <= path.length; i++) {
			if (i == path.length || path[i] == '/') {
				byte[] component = Arrays.copyOfRange(path, start, i);
				checkName(component, false);
				components.add(component);
				start = i + 1;
			}
		}
		List<Directory> ancestors = new ArrayList<>();
		Directory node = root;
		ancestors.add(node);
		for (int i = 0; i < components.size() - 1; i++) {
			node = lookupDirectory(node, components.get(i));
			ancestors.add(node);
		}
		int first = (dirFlags & SHOW_OTHER_DIRECTORIES) != 0 ? 0
				: ancestors.size() - 1;
		for (int i = first; i < ancestors.size(); i++) {
			Directory affected = ancestors.get(i);
			dirInvalidated++;
			affected.valid = false;
			affected.untracked.clear();
		}
	}

	/**
	 * Invalidate every cached directory after an unknown index/exclusion
	 * change.
	 */
	public void invalidateAll() {
		invalidateSubtree(root);
	}

	/**
	 * Invalidate a notification consumed after a full working-tree scan.
	 * Besides the containing directory, invalidate an existing directory cone
	 * with this name: index-event paths do not retain a trailing slash.
	 *
	 * @param path validated worktree-relative notification
	 * @throws IOException for unsafe path components
	 * @since 7.8
	 */
	public void invalidateWorkingTreePath(byte[] path) throws IOException {
		invalidatePath(path);
		Directory node = root;
		int start = 0;
		for (int i = 0; node != null && i <= path.length; i++) {
			if (i == path.length || path[i] == '/') {
				byte[] component = Arrays.copyOfRange(path, start, i);
				Directory match = null;
				for (Directory child : node.directories) {
					if (Arrays.equals(child.name, component)) {
						match = child;
						break;
					}
				}
				node = match;
				start = i + 1;
			}
		}
		invalidateSubtree(node);
	}

	private static void invalidateSubtree(Directory root) {
		if (root == null) {
			return;
		}
		Deque<Directory> pending = new ArrayDeque<>();
		Set<Directory> visited = Collections
				.newSetFromMap(new IdentityHashMap<>());
		pending.push(root);
		while (!pending.isEmpty()) {
			Directory node = pending.pop();
			if (!visited.add(node)) {
				continue;
			}
			node.valid = node.checkOnly = false;
			node.untracked.clear();
			pending.addAll(node.directories);
		}
	}

	private static final class Frame {
		final Directory node;

		int remaining;

		Frame(Directory node, int remaining) {
			this.node = node;
			this.remaining = remaining;
		}
	}

	private static byte[] bytes(ByteBuffer in, int count) throws IOException {
		if (count > in.remaining()) {
			throw malformed();
		}
		byte[] result = new byte[count];
		in.get(result);
		return result;
	}

	private static ObjectId oid(ByteBuffer in) throws IOException {
		return ObjectId.fromRaw(bytes(in, Constants.OBJECT_ID_LENGTH));
	}

	private static byte[] string(ByteBuffer in) throws IOException {
		int start = in.position();
		while (in.hasRemaining()) {
			if (in.get() == 0) {
				return Arrays.copyOfRange(in.array(), start, in.position() - 1);
			}
		}
		throw malformed();
	}

	private static void string(DataOutputStream out, byte[] value)
			throws IOException {
		for (byte b : value) {
			if (b == 0) {
				throw malformed();
			}
		}
		out.write(value);
		out.writeByte(0);
	}

	private static void checkName(byte[] name, boolean root)
			throws IOException {
		if (root != (name.length == 0)) {
			throw malformed();
		}
		for (byte b : name) {
			if (b == '/' || b == 0) {
				throw malformed();
			}
		}
		if (Arrays.equals(name, new byte[] { '.' })
				|| Arrays.equals(name, new byte[] { '.', '.' })) {
			throw malformed();
		}
	}

	private static int varint(ByteBuffer in) throws IOException {
		int next = Byte.toUnsignedInt(in.get());
		long value = next & 127;
		while ((next & 128) != 0) {
			next = Byte.toUnsignedInt(in.get());
			value = ((value + 1) << 7) + (next & 127);
			if (value > Integer.MAX_VALUE) {
				throw malformed();
			}
		}
		return (int) value;
	}

	private static void varint(DataOutputStream out, int value)
			throws IOException {
		byte[] bytes = new byte[5];
		int pos = bytes.length - 1;
		bytes[pos] = (byte) (value & 127);
		while ((value >>>= 7) != 0) {
			bytes[--pos] = (byte) (128 | (--value & 127));
		}
		out.write(bytes, pos, bytes.length - pos);
	}

	private static BitSet bitmap(ByteBuffer in, int count) throws IOException {
		try {
			return IndexBitmap.read(in, count);
		} catch (IOException invalid) {
			throw malformed();
		}
	}

	private static IOException malformed() {
		return new IOException("Invalid untracked cache extension"); //$NON-NLS-1$
	}
}
