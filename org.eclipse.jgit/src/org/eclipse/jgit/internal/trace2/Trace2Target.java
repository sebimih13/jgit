/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.internal.trace2;

import java.time.Instant;
import java.time.LocalTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Independent Normal, Performance and Event targets (tr2_tgt_*). */
public final class Trace2Target implements AutoCloseable {
	/** Supported output targets. */
	public enum Format {
		/** Human-oriented command records. */
		NORMAL,
		/** Pipe-separated performance records. */
		PERF,
		/** JSON Lines. */
		EVENT
	}

	private final Trace2Destination destination;

	private final Format format;

	private final boolean brief;

	private final int maxNesting;

	/**
	 * @param destination
	 *            output destination
	 * @param format
	 *            output format
	 * @param brief
	 *            omit source/time prefix
	 * @param maxNesting
	 *            Event nesting limit; other targets are unfiltered
	 */
	public Trace2Target(Trace2Destination destination, Format format,
			boolean brief, int maxNesting) {
		this.destination = destination;
		this.format = format;
		this.brief = brief;
		this.maxNesting = maxNesting;
	}

	/** @return whether output remains usable */
	public boolean isEnabled() {
		return destination.isEnabled();
	}

	/**
	 * @param event
	 *            event name
	 * @param sid
	 *            session ID
	 * @param thread
	 *            thread name
	 * @param fields
	 *            event-specific fields
	 */
	public void emit(String event, String sid, String thread,
			Map<String, Object> fields) {
		if (!isEnabled()) {
			return;
		}
		if (format == Format.EVENT) {
			Number nesting = (Number) fields.get("nesting"); //$NON-NLS-1$
			if (nesting != null && nesting.intValue() > maxNesting) {
				return;
			}
			Map<String, Object> record = new LinkedHashMap<>();
			record.put("event", event); //$NON-NLS-1$
			record.put("sid", sid); //$NON-NLS-1$
			record.put("thread", thread); //$NON-NLS-1$
			if (!brief || "version".equals(event) || "atexit".equals(event)) { //$NON-NLS-1$ //$NON-NLS-2$
				record.put("time", Instant.now().toString()); //$NON-NLS-1$
			}
			record.putAll(fields);
			destination.write(json(record) + '\n');
			if (event.equals("version") && destination.isDiscardSentinel()) { //$NON-NLS-1$
				emit("too_many_files", sid, thread, Map.of()); //$NON-NLS-1$
			}
			return;
		}
		String payload = payload(event, fields);
		if (format == Format.NORMAL) {
			// Normal has no region/data/thread callbacks in C Git.
			if (event.startsWith("region_") || event.startsWith("thread_") //$NON-NLS-1$ //$NON-NLS-2$
					|| event.equals("data") || event.equals("data_json")) { //$NON-NLS-1$ //$NON-NLS-2$
				return;
			}
			destination.write((brief ? "" //$NON-NLS-1$
					: String.format(Locale.ROOT, "%-50s",
							LocalTime.now() + " Trace2.java:0 ")) //$NON-NLS-1$
					+ payload + '\n');
		} else {
			String prefix = brief ? "" : LocalTime.now() + " Trace2.java:0 | "; //$NON-NLS-1$ //$NON-NLS-2$
			int depth = (int) sid.chars().filter(c -> c == '/').count();
			destination.write(prefix + String.format(Locale.ROOT,
					"d%d | %-24s | %-12s | %3s | %9s | %9s | %-12s | %s\n", //$NON-NLS-1$
					Integer.valueOf(depth), thread, event,
					fields.containsKey("repo") ? "r" + fields.get("repo") : "", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
					duration(fields, "t_abs"), duration(fields, "t_rel"), //$NON-NLS-1$ //$NON-NLS-2$
					fields.getOrDefault("category", ""), //$NON-NLS-1$ //$NON-NLS-2$
					perfPayload(event, fields)));
		}
	}

	private static String duration(Map<String, Object> fields, String key) {
		return fields.containsKey(key) ? String.format(Locale.ROOT, "%.6f", //$NON-NLS-1$
				fields.get(key)) : ""; //$NON-NLS-1$
	}

	private static String perfPayload(String event, Map<String, Object> f) {
		switch (event) {
		case "child_start": //$NON-NLS-1$
			return "[ch" + f.get("child_id") + "] class:" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					+ f.get("child_class") //$NON-NLS-1$
					+ (f.containsKey("hook_name") //$NON-NLS-1$
							? " hook:" + f.get("hook_name") : "") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					+ (f.containsKey("cd") //$NON-NLS-1$
							? " cd:" + shellQuote(String.valueOf(f.get("cd"))) //$NON-NLS-1$ //$NON-NLS-2$
							: "") //$NON-NLS-1$
					+ " argv:[" + argv((Collection<?>) f.get("argv")) + ']'; //$NON-NLS-1$ //$NON-NLS-2$
		case "version": //$NON-NLS-1$
			return String.valueOf(f.get("exe")); //$NON-NLS-1$
		case "start": //$NON-NLS-1$
			return argv((Collection<?>) f.get("argv")); //$NON-NLS-1$
		case "cmd_name": //$NON-NLS-1$
			return f.get("name") + " (" + f.get("hierarchy") + ')'; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		case "region_enter": //$NON-NLS-1$
		case "region_leave": //$NON-NLS-1$
			return "label:" + f.get("label"); //$NON-NLS-1$ //$NON-NLS-2$
		case "data": //$NON-NLS-1$
		case "data_json": //$NON-NLS-1$
			return f.get("key") + ":" + f.get("value"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		case "exit": //$NON-NLS-1$
		case "atexit": //$NON-NLS-1$
			return "code:" + f.get("code"); //$NON-NLS-1$ //$NON-NLS-2$
		case "cmd_mode": //$NON-NLS-1$
			return String.valueOf(f.get("name")); //$NON-NLS-1$
		case "cmd_path": //$NON-NLS-1$
			return String.valueOf(f.get("path")); //$NON-NLS-1$
		case "error": //$NON-NLS-1$
			return String.valueOf(f.get("msg")); //$NON-NLS-1$
		case "printf": //$NON-NLS-1$
			return String.valueOf(f.get("msg")); //$NON-NLS-1$
		case "thread_start": //$NON-NLS-1$
		case "thread_exit": //$NON-NLS-1$
			return ""; //$NON-NLS-1$
		default:
			return payload(event, f);
		}
	}

	private static String payload(String event, Map<String, Object> f) {
		switch (event) {
		case "version": //$NON-NLS-1$
			return "version " + f.get("exe"); //$NON-NLS-1$ //$NON-NLS-2$
		case "start": //$NON-NLS-1$
			return "start " + argv((Collection<?>) f.get("argv")); //$NON-NLS-1$ //$NON-NLS-2$
		case "cmd_name": //$NON-NLS-1$
			return "cmd_name " + f.get("name") + " (" + f.get("hierarchy") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
					+ ')';
		case "cmd_mode": //$NON-NLS-1$
			return "cmd_mode " + f.get("name"); //$NON-NLS-1$ //$NON-NLS-2$
		case "cmd_path": //$NON-NLS-1$
			return "cmd_path " + f.get("path"); //$NON-NLS-1$ //$NON-NLS-2$
		case "cmd_ancestry": //$NON-NLS-1$
			return "cmd_ancestry " + String.join(" <- ", //$NON-NLS-1$ //$NON-NLS-2$
					((Collection<?>) f.get("ancestry")).stream() //$NON-NLS-1$
							.map(String::valueOf).toArray(String[]::new));
		case "error": //$NON-NLS-1$
			return "error " + f.get("msg"); //$NON-NLS-1$ //$NON-NLS-2$
		case "def_repo": //$NON-NLS-1$
			return "worktree " + shellQuote(String.valueOf(f.get("worktree"))); //$NON-NLS-1$ //$NON-NLS-2$
		case "def_param": //$NON-NLS-1$
			return "def_param scope:" + f.get("scope") + ' ' + f.get("param") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					+ (f.containsKey("value") ? "=" + f.get("value") : ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		case "exit": //$NON-NLS-1$
		case "atexit": //$NON-NLS-1$
			return event + " elapsed:" + duration(f, "t_abs") + " code:" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					+ f.get("code");
		case "alias": //$NON-NLS-1$
			return "alias " + f.get("alias") + " -> " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					+ argv((Collection<?>) f.get("argv")); //$NON-NLS-1$
		case "child_start": //$NON-NLS-1$
			return "child_start[" + f.get("child_id") + "] " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					+ (f.containsKey("cd") //$NON-NLS-1$
							? "cd " + shellQuote(String.valueOf(f.get("cd"))) //$NON-NLS-1$ //$NON-NLS-2$
									+ "; " //$NON-NLS-1$
							: "") //$NON-NLS-1$
					+ argv((Collection<?>) f.get("argv")); //$NON-NLS-1$
		case "child_exit": //$NON-NLS-1$
			return "child_exit[" + f.get("child_id") + "] pid:" + f.get("pid") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
					+ " code:" + f.get("code") + " elapsed:" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					+ duration(f, "t_rel"); //$NON-NLS-1$
		case "child_ready": //$NON-NLS-1$
			return "child_ready[" + f.get("child_id") + "] pid:" + f.get("pid") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
					+ " ready:" + f.get("ready") + " elapsed:" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					+ duration(f, "t_rel"); //$NON-NLS-1$
		case "exec": //$NON-NLS-1$
			return "exec[" + f.get("exec_id") + "] " + f.get("exe") + ' ' //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
					+ argv((Collection<?>) f.get("argv")); //$NON-NLS-1$
		case "exec_result": //$NON-NLS-1$
			return "exec_result[" + f.get("exec_id") + "] code:" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					+ f.get("code"); //$NON-NLS-1$
		case "printf": //$NON-NLS-1$
			return String.valueOf(f.get("msg")); //$NON-NLS-1$
		case "counter": //$NON-NLS-1$
		case "th_counter": //$NON-NLS-1$
			return event + ' ' + f.get("category") + '/' + f.get("name") //$NON-NLS-1$ //$NON-NLS-2$
					+ " value:" + f.get("count"); //$NON-NLS-1$ //$NON-NLS-2$
		case "timer": //$NON-NLS-1$
		case "th_timer": //$NON-NLS-1$
			return event + ' ' + f.get("category") + '/' + f.get("name") //$NON-NLS-1$ //$NON-NLS-2$
					+ " intervals:" + f.get("intervals") + " total:" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					+ duration(f, "t_total") //$NON-NLS-1$
					+ " min:" + duration(f, "t_min") + " max:" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					+ duration(f, "t_max"); //$NON-NLS-1$
		default:
			return event + ' ' + json(f);
		}
	}

	private static String argv(Collection<?> args) {
		return String.join(" ", args.stream().map(String::valueOf) //$NON-NLS-1$
				.map(Trace2Target::shellQuote).toArray(String[]::new));
	}

	private static String shellQuote(String s) {
		return s.matches("[a-zA-Z0-9_./:=+-]+") ? s //$NON-NLS-1$
				: "'" + s.replace("'", "'\\''").replace("!", "'\\!'") + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
	}

	/**
	 * Serialize supported JSON values without permitting raw JSON injection.
	 *
	 * @param value
	 *            null, string, boolean, finite number, collection or
	 *            string-keyed map
	 * @return JSON
	 */
	public static String json(Object value) {
		if (value == null) {
			return "null"; //$NON-NLS-1$
		}
		if (value instanceof String) {
			return quote((String) value);
		}
		if (value instanceof Boolean) {
			return value.toString();
		}
		if (value instanceof Number) {
			if (!(value instanceof Byte || value instanceof Short
					|| value instanceof Integer || value instanceof Long
					|| value instanceof Float || value instanceof Double)) {
				throw new IllegalArgumentException(
						"Unsupported JSON number type"); //$NON-NLS-1$
			}
			if (!Double.isFinite(((Number) value).doubleValue())) {
				throw new IllegalArgumentException("Non-finite JSON number"); //$NON-NLS-1$
			}
			return value.toString();
		}
		StringBuilder b = new StringBuilder();
		if (value instanceof Map) {
			b.append('{');
			for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
				if (!(e.getKey() instanceof String)) {
					throw new IllegalArgumentException(
							"JSON object keys must be strings"); //$NON-NLS-1$
				}
				if (b.length() > 1) {
					b.append(',');
				}
				b.append(quote((String) e.getKey())).append(':')
						.append(json(e.getValue()));
			}
			return b.append('}').toString();
		}
		if (value instanceof Collection) {
			b.append('[');
			for (Object item : (Collection<?>) value) {
				if (b.length() > 1) {
					b.append(',');
				}
				b.append(json(item));
			}
			return b.append(']').toString();
		}
		throw new IllegalArgumentException("Unsupported JSON value"); //$NON-NLS-1$
	}

	/**
	 * @param text
	 *            input
	 * @return quoted JSON string
	 */
	public static String quote(String text) {
		StringBuilder result = new StringBuilder("\""); //$NON-NLS-1$
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\\' || c == '"') {
				result.append('\\').append(c);
			} else if (c < 32 || Character.isSurrogate(c)) {
				result.append("\\u"); //$NON-NLS-1$
				for (int shift = 12; shift >= 0; shift -= 4) {
					result.append(Character.forDigit((c >> shift) & 15, 16));
				}
			} else {
				result.append(c);
			}
		}
		return result.append('"').toString();
	}

	@Override
	public void close() {
		destination.close();
	}
}
