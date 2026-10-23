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
import java.nio.ByteBuffer;
import java.util.BitSet;

/** Strict, expansion-bounded EWAH reader for optional index extensions. */
final class IndexBitmap {
	private IndexBitmap() {
	}

	static BitSet read(ByteBuffer in, int count) throws IOException {
		int bits = in.getInt();
		int words = in.getInt();
		if (bits < 0 || bits > count || words <= 0
				|| words > (in.remaining() - 4) / Long.BYTES) {
			throw malformed();
		}
		int start = in.position();
		int end = start + words * Long.BYTES;
		int rlw = in.getInt(end);
		if (rlw < 0 || rlw >= words) {
			throw malformed();
		}
		boolean rlwFound = false;
		long position = 0;
		long expandedWords = (bits + 63L) / 64;
		BitSet result = new BitSet(bits);
		int i = 0;
		while (i < words) {
			int controlIndex = i;
			long control = in.getLong();
			i++;
			rlwFound |= rlw == controlIndex;
			long runs = (control >>> 1) & 0xffffffffL;
			long literals = control >>> 33;
			if (runs + literals > expandedWords - position
					|| literals > words - i) {
				throw malformed();
			}
			if ((control & 1) != 0) {
				if ((position + runs) * 64 > bits) {
					throw malformed();
				}
				result.set((int) (position * 64),
						(int) ((position + runs) * 64));
			}
			position += runs;
			for (long j = 0; j < literals; j++) {
				long literal = in.getLong();
				int base = (int) (position++ * 64);
				while (literal != 0) {
					int bit = base + Long.numberOfTrailingZeros(literal);
					if (bit >= bits) {
						throw malformed();
					}
					result.set(bit);
					literal &= literal - 1;
				}
				i++;
			}
		}
		if (!rlwFound || position != expandedWords) {
			throw malformed();
		}
		in.position(end + Integer.BYTES);
		return result;
	}

	private static IOException malformed() {
		return new IOException("Invalid index extension bitmap"); //$NON-NLS-1$
	}
}
