/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.util.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.Test;

/** EOF must be observed rather than inferred from available(). */
public class LimitedInputStreamTest {
	private LimitedInputStream stream(int length, int limit) {
		return new LimitedInputStream(
				new ByteArrayInputStream(new byte[length]) {
					@Override
					public int available() {
						return 0;
					}
				}, limit) {
			@Override
			protected void limitExceeded() throws IOException {
				throw new IOException("limit exceeded");
			}
		};
	}

	@Test
	public void zeroAvailableDoesNotHideExcessByte() throws Exception {
		try (LimitedInputStream in = stream(3, 2)) {
			assertEquals(0, in.read());
			assertEquals(0, in.read());
			assertThrows(IOException.class, in::read);
		}
	}

	@Test
	public void zeroAvailableDoesNotHideExcessBuffer() throws Exception {
		try (LimitedInputStream in = stream(3, 2)) {
			byte[] buffer = new byte[4];
			assertEquals(2, in.read(buffer));
			assertThrows(IOException.class, () -> in.read(buffer));
		}
	}

	@Test
	public void exactLimitIsEndOfStream() throws Exception {
		try (LimitedInputStream in = stream(2, 2)) {
			assertEquals(2, in.read(new byte[4]));
			assertEquals(-1, in.read());
			assertEquals(-1, in.read(new byte[4]));
		}
	}

	@Test
	public void emptyStreamWithZeroLimitIsEndOfStream() throws Exception {
		try (LimitedInputStream in = stream(0, 0)) {
			assertEquals(-1, in.read());
		}
	}

	@Test
	public void zeroLimitRejectsNonemptyStream() throws Exception {
		try (LimitedInputStream in = stream(1, 0)) {
			assertThrows(IOException.class, in::read);
		}
	}

	@Test
	public void emptyReadDoesNotConsumeTheProbeByte() throws Exception {
		try (LimitedInputStream in = stream(1, 0)) {
			assertEquals(0, in.read(new byte[1], 0, 0));
			assertThrows(IOException.class, in::read);
		}
	}

	@Test
	public void invalidBufferDoesNotConsumeData() throws Exception {
		try (LimitedInputStream in = stream(1, 1)) {
			assertThrows(IndexOutOfBoundsException.class,
					() -> in.read(new byte[1], 2, 0));
			assertThrows(NullPointerException.class, () -> in.read(null, 0, 0));
			assertEquals(0, in.read());
		}
	}
}
