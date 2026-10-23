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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.BitSet;

import com.googlecode.javaewah.EWAHCompressedBitmap;

/**
 * Native FSMN provider token and dirty-entry bitmap. Stored validity is not
 * proof of current filesystem state until the provider has been refreshed.
 */
public final class FsMonitorCache {
	/** Maximum optional extension size accepted into memory. */
	public static final int MAX_EXTENSION_SIZE = 64 * 1024 * 1024;

	private final int version;

	private final byte[] token;

	private BitSet dirtyEntries;

	/**
	 * Create v2 data. Tokens are opaque bytes and may not contain NUL.
	 * 
	 * @param token
	 *            provider correlation token
	 * @param dirtyEntries
	 *            set bits denote entries requiring validation
	 */
	public FsMonitorCache(byte[] token, BitSet dirtyEntries) {
		this(2, token, dirtyEntries);
	}

	private FsMonitorCache(int version, byte[] token, BitSet dirtyEntries) {
		// Native Git can persist an empty token after a failed v2 hook. It
		// represents storage state, never an accepted provider response.
		if (token.length > MAX_EXTENSION_SIZE - 25) {
			throw new IllegalArgumentException(
					"Invalid fsmonitor token length"); //$NON-NLS-1$
		}
		for (byte value : token) {
			if (value == 0) {
				throw new IllegalArgumentException("NUL in fsmonitor token"); //$NON-NLS-1$
			}
		}
		this.version = version;
		this.token = token.clone();
		this.dirtyEntries = (BitSet) dirtyEntries.clone();
	}

	/** @return original encoding version (writes always use v2) */
	public int getVersion() {
		return version;
	}

	/** @return a copy of the opaque provider token */
	public byte[] getToken() {
		return token.clone();
	}

	/** @return a copy of the dirty-entry positions */
	public BitSet getDirtyEntries() {
		return (BitSet) dirtyEntries.clone();
	}

	/**
	 * @param position
	 *            index position
	 * @return whether the serialized record requires validation at this
	 *         position
	 */
	public boolean isDirty(int position) {
		return dirtyEntries.get(position);
	}

	/**
	 * Update the bitmap after index-entry changes, retaining the provider
	 * token.
	 * 
	 * @param dirty
	 *            current dirty entry positions
	 */
	public void setDirtyEntries(BitSet dirty) {
		dirtyEntries = (BitSet) dirty.clone();
	}

	/**
	 * Parse native v1/v2 data with an expansion bound from the index header.
	 * 
	 * @param raw
	 *            extension payload without signature/length
	 * @param entryCount
	 *            number of entries in the associated index
	 * @return parsed storage, requiring a provider query before use
	 * @throws IOException
	 *             on malformed or oversized data
	 */
	public static FsMonitorCache read(byte[] raw, int entryCount)
			throws IOException {
		if (raw.length > MAX_EXTENSION_SIZE || entryCount < 0) {
			throw malformed();
		}
		ByteBuffer in = ByteBuffer.wrap(raw);
		try {
			int version = in.getInt();
			byte[] token;
			if (version == 1) {
				token = Long.toUnsignedString(in.getLong())
						.getBytes(StandardCharsets.US_ASCII);
			} else if (version == 2) {
				int start = in.position();
				while (in.hasRemaining() && in.get() != 0) {
					// Search within the bounded payload, never past its end.
				}
				if (in.position() == start || raw[in.position() - 1] != 0) {
					throw malformed();
				}
				token = Arrays.copyOfRange(raw, start, in.position() - 1);
			} else {
				throw malformed();
			}
			int bytes = in.getInt();
			if (bytes < 16 || bytes != in.remaining()) {
				throw malformed();
			}
			BitSet dirty = IndexBitmap.read(in, entryCount);
			if (in.hasRemaining()) {
				throw malformed();
			}
			return new FsMonitorCache(version, token, dirty);
		} catch (BufferUnderflowException | IllegalArgumentException invalid) {
			throw malformed();
		}
	}

	/**
	 * Serialize in native v2 format, including the length-delimited EWAH
	 * bitmap.
	 * 
	 * @return extension payload
	 * @throws IOException
	 *             when data exceeds the optional-extension limit
	 */
	public byte[] write() throws IOException {
		EWAHCompressedBitmap bitmap = new EWAHCompressedBitmap();
		for (int bit = dirtyEntries.nextSetBit(0); bit >= 0; bit = dirtyEntries
				.nextSetBit(bit + 1)) {
			bitmap.set(bit);
		}
		ByteArrayOutputStream encoded = new ByteArrayOutputStream();
		try (DataOutputStream data = new DataOutputStream(encoded)) {
			bitmap.serialize(data);
		}
		if ((long) token.length + 9 + encoded.size() > MAX_EXTENSION_SIZE) {
			throw malformed();
		}
		ByteArrayOutputStream raw = new ByteArrayOutputStream(
				token.length + 9 + encoded.size());
		try (DataOutputStream data = new DataOutputStream(raw)) {
			data.writeInt(2);
			data.write(token);
			data.writeByte(0);
			data.writeInt(encoded.size());
			encoded.writeTo(data);
		}
		return raw.toByteArray();
	}

	private static IOException malformed() {
		return new IOException("Invalid fsmonitor cache extension"); //$NON-NLS-1$
	}
}
