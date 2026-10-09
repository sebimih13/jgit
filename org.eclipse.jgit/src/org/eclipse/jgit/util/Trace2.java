/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.util;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import org.eclipse.jgit.lib.Repository;

/**
 * Scoped Trace2 event output for embedded Git operations.
 * <p>
 * A session belongs to the library lifetime, not to a command or an Eclipse
 * job. Command scopes must be closed on the thread that opened them. Nested
 * operations share that thread's context; pooled threads retain no command
 * context after the outer scope closes. Asynchronous callers open a new scope
 * on their worker thread. This API does not propagate context across threads.
 * </p>
 *
 * @since 7.9
 */
public final class Trace2 implements AutoCloseable {
	private static final Scope NO_SCOPE = new Scope();

	private static class Holder {
		static final Trace2 INSTANCE = fromEnvironment();
	}

	private final Writer writer;
	private final boolean owned;
	private final boolean brief;
	private final boolean debug;
	private final int maxNesting;
	private final String sid;
	private final long start;
	private final ThreadLocal<Scope> current = new ThreadLocal<>();
	private final Map<Repository, Integer> repositories = new WeakHashMap<>();
	private int nextRepository;
	private volatile boolean enabled;
	private boolean closed;

	Trace2(Writer writer, boolean owned, boolean brief, int maxNesting,
			boolean debug, String parent) {
		this.writer = writer;
		this.owned = owned;
		this.brief = brief;
		this.maxNesting = maxNesting;
		this.debug = debug;
		this.enabled = writer != null;
		this.start = writer == null ? 0 : System.nanoTime();
		String id = writer == null ? "" : "jgit-" + UUID.randomUUID(); //$NON-NLS-1$ //$NON-NLS-2$
		this.sid = parent == null || parent.isEmpty() ? id : parent + '/' + id;
		if (enabled) {
			emit("version", "\"evt\":\"4\",\"exe\":\"jgit\""); //$NON-NLS-1$ //$NON-NLS-2$
			emit("start", "\"t_abs\":0.0,\"argv\":[\"jgit\",\"embedded\"]"); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	/** @return the process-wide, lazily configured event tracer */
	public static Trace2 getInstance() {
		return Holder.INSTANCE;
	}

	private static Trace2 fromEnvironment() {
		Writer destination = null;
		boolean owns = true;
		boolean debug = false;
		boolean brief = false;
		int nesting = 2;
		String parent = null;
		try {
			SystemReader reader = SystemReader.getInstance();
			debug = isTrue(setting(reader, "GIT_TRACE2_DST_DEBUG", //$NON-NLS-1$
					"destinationDebug")); //$NON-NLS-1$
			String target = setting(reader, "GIT_TRACE2_EVENT", "eventTarget"); //$NON-NLS-1$ //$NON-NLS-2$
			brief = isTrue(setting(reader, "GIT_TRACE2_EVENT_BRIEF", "eventBrief")); //$NON-NLS-1$ //$NON-NLS-2$
			String depth = setting(reader, "GIT_TRACE2_EVENT_NESTING", "eventNesting"); //$NON-NLS-1$ //$NON-NLS-2$
			if (depth != null) {
				try {
					nesting = Math.max(1, Integer.parseInt(depth));
				} catch (NumberFormatException e) {
					// Retain the default for malformed configuration.
				}
			}
			parent = reader.getenv("GIT_TRACE2_PARENT_SID"); //$NON-NLS-1$
			if (target != null && !target.isEmpty()
					&& !"0".equals(target) && !"false".equalsIgnoreCase(target)) { //$NON-NLS-1$ //$NON-NLS-2$
				if (isTrue(target) || "2".equals(target)) { //$NON-NLS-1$
					destination = new OutputStreamWriter(System.err, StandardCharsets.UTF_8);
					owns = false;
				} else {
					Path path = Path.of(target);
					if (!path.isAbsolute() || Files.isDirectory(path)) {
						throw new IOException("Unsupported Trace2 destination"); //$NON-NLS-1$
					}
					destination = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
							StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				}
			}
		} catch (Exception e) {
			if (debug) {
				System.err.println("JGit: cannot configure Trace2 event destination"); //$NON-NLS-1$
			}
		}
		Trace2 trace = new Trace2(destination, owns, brief, nesting, debug, parent);
		if (trace.enabled) {
			try {
				Runtime.getRuntime().addShutdownHook(new Thread(trace::close,
						"JGit-Trace2-shutdown")); //$NON-NLS-1$
			} catch (IllegalStateException | SecurityException e) {
				trace.close();
			}
		}
		if (!trace.enabled) {
			trace.close();
		}
		return trace;
	}

	private static String setting(SystemReader reader, String env, String key)
			throws Exception {
		String value = reader.getenv(env);
		return value != null ? value : reader.getUserConfig().getString("trace2", null, key); //$NON-NLS-1$
	}

	private static boolean isTrue(String value) {
		return "1".equals(value) || "true".equalsIgnoreCase(value); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * Open a command region. Nested commands do not emit another command name.
	 *
	 * @param repository repository, or {@code null}
	 * @param command Git command name
	 * @param origin static identifier of the calling API or application
	 * @return a scope to close on the current thread
	 */
	public Scope command(Repository repository, String command, String origin) {
		if (!enabled) {
			return NO_SCOPE;
		}
		Scope previous = current.get();
		while (previous != null && !previous.command) {
			previous = previous.parent;
		}
		if (previous == null) {
			emit("cmd_name", "\"name\":" + quote(command) //$NON-NLS-1$ //$NON-NLS-2$
					+ ",\"hierarchy\":" + quote(command)); //$NON-NLS-1$
		}
		Scope scope = region(repository, "jgit", command); //$NON-NLS-1$
		if (scope != NO_SCOPE) {
			scope.command = true;
		}
		scope.data("origin", origin); //$NON-NLS-1$
		return scope;
	}

	/**
	 * Open a nested operation region.
	 *
	 * @param repository repository, or {@code null}
	 * @param category static category name
	 * @param label static operation name
	 * @return a scope to close on the current thread
	 */
	public Scope region(Repository repository, String category, String label) {
		if (!enabled) {
			return NO_SCOPE;
		}
		Scope scope = new Scope(this, current.get(), repositoryId(repository),
				category, label);
		current.set(scope);
		if (scope.depth <= maxNesting) {
			emit("region_enter", scope.fields()); //$NON-NLS-1$
		}
		return scope;
	}

	private synchronized int repositoryId(Repository repository) {
		if (repository == null) {
			return 0;
		}
		File directory = repository.isBare() ? repository.getDirectory()
				: repository.getWorkTree();
		if (directory == null) {
			// In-memory repositories have no filesystem identity to report.
			return 0;
		}
		Integer id = repositories.get(repository);
		if (id == null) {
			id = Integer.valueOf(++nextRepository);
			repositories.put(repository, id);
			emit("def_repo", "\"repo\":" + id + ",\"worktree\":" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					+ quote(directory.getAbsolutePath()));
		}
		return id.intValue();
	}

	private synchronized void emit(String event, String fields) {
		if (!enabled) {
			return;
		}
		try {
			writer.write("{\"event\":" + quote(event) + ",\"sid\":" + quote(sid) //$NON-NLS-1$ //$NON-NLS-2$
					+ ",\"thread\":\"jgit-" + Thread.currentThread().getId() + "\"" //$NON-NLS-1$ //$NON-NLS-2$
					+ (!brief || "version".equals(event) || "atexit".equals(event) //$NON-NLS-1$ //$NON-NLS-2$
							? ",\"time\":" + quote(Instant.now().toString()) : "") //$NON-NLS-1$ //$NON-NLS-2$
					+ (fields.isEmpty() ? "" : "," + fields) + "}\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			writer.flush();
		} catch (IOException | RuntimeException e) {
			enabled = false;
			if (debug) {
				System.err.println("JGit: Trace2 event output disabled after write failure"); //$NON-NLS-1$
			}
		}
	}

	static String quote(String text) {
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

	/**
	 * End the tracing session and release its destination. Does not terminate
	 * the JVM. The atexit code describes tracer shutdown, not a process exit
	 * status, which an embedded library cannot observe.
	 */
	@Override
	public synchronized void close() {
		if (closed) {
			return;
		}
		closed = true;
		emit("atexit", "\"code\":0,\"t_abs\":" + elapsed(start)); //$NON-NLS-1$ //$NON-NLS-2$
		enabled = false;
		if (writer != null && owned) {
			try {
				writer.close();
			} catch (IOException | RuntimeException e) {
				if (debug) {
					System.err.println("JGit: cannot close Trace2 event destination"); //$NON-NLS-1$
				}
			}
		}
	}

	private static double elapsed(long since) {
		return (System.nanoTime() - since) / 1_000_000_000.0;
	}

	/** A thread-confined command or operation region. @since 7.9 */
	public static final class Scope implements AutoCloseable {
		private final Trace2 trace;
		private final Scope parent;
		private final int repo;
		private final String category;
		private final String label;
		private final int depth;
		private final long start;
		private boolean closed;
		private boolean command;

		private Scope() {
			trace = null;
			parent = null;
			repo = 0;
			category = label = null;
			depth = 0;
			start = 0;
		}

		private Scope(Trace2 trace, Scope parent, int repo, String category,
				String label) {
			this.trace = trace;
			this.parent = parent;
			this.repo = repo;
			this.category = category;
			this.label = label;
			depth = parent == null ? 1 : parent.depth + 1;
			start = System.nanoTime();
		}

		private String fields() {
			return "\"nesting\":" + depth + ",\"category\":" + quote(category) //$NON-NLS-1$ //$NON-NLS-2$
					+ ",\"label\":" + quote(label) //$NON-NLS-1$
					+ (repo == 0 ? "" : ",\"repo\":" + repo); //$NON-NLS-1$ //$NON-NLS-2$
		}

		/** @return whether this scope can emit events */
		public boolean isEnabled() {
			return trace != null && trace.enabled && !closed;
		}

		/**
		 * Emit a string-valued datum. Callers must exclude secrets.
		 * @param key metric name
		 * @param value metric value
		 */
		public void data(String key, String value) {
			if (trace != null && !closed && trace.enabled && depth <= trace.maxNesting) {
				trace.emit("data", fields() + ",\"key\":" + quote(key) //$NON-NLS-1$ //$NON-NLS-2$
						+ ",\"value\":" + quote(value) + ",\"t_abs\":" //$NON-NLS-1$ //$NON-NLS-2$
						+ elapsed(trace.start) + ",\"t_rel\":" + elapsed(start)); //$NON-NLS-1$
			}
		}

		/** Close this region without closing the process session. */
		@Override
		public void close() {
			if (trace == null || closed) {
				return;
			}
			if (trace.current.get() != this) {
				// Never corrupt another thread's stack or mask a Git failure.
				return;
			}
			closed = true;
			if (depth <= trace.maxNesting) {
				trace.emit("region_leave", fields() + ",\"t_rel\":" + elapsed(start)); //$NON-NLS-1$ //$NON-NLS-2$
			}
			if (parent == null) {
				trace.current.remove();
			} else {
				trace.current.set(parent);
			}
		}
	}
}
