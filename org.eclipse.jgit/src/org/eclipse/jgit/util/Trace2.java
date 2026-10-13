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
import java.io.Writer;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jgit.internal.trace2.Trace2Destination;
import org.eclipse.jgit.internal.trace2.Trace2SystemConfig;
import org.eclipse.jgit.internal.trace2.Trace2Target;
import org.eclipse.jgit.internal.trace2.Trace2Target.Format;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.fnmatch.FileNameMatcher;

/**
 * Trace2 sessions, thread-local regions and independent output targets.
 * <p>
 * The default embedded session belongs to the library lifetime. Commands and
 * Eclipse jobs never terminate it. A CLI owns its exit status explicitly.
 * Scopes must close on their opening thread. Thread scopes describe a worker
 * lifetime, not each task executed by a thread pool.
 * </p>
 *
 * @since 7.8
 */
public final class Trace2 implements AutoCloseable {
	private static final Scope NO_SCOPE = new Scope();

	private static final AtomicInteger SESSION_NUMBER = new AtomicInteger();

	private static volatile String[] commandLine;

	private static volatile Trace2 initialized;

	private static class Holder {
		static final Trace2 INSTANCE = fromEnvironment();
	}

	private final List<Trace2Target> targets;

	private final String sid;

	private final long start;

	private final long mainThread;

	private final ThreadLocal<Scope> current = new ThreadLocal<>();

	private final ThreadLocal<Worker> worker = new ThreadLocal<>();

	private final Map<Repository, Integer> repositories = new WeakHashMap<>();

	private final AtomicInteger nextChild = new AtomicInteger();

	private final AtomicInteger nextWorker = new AtomicInteger();

	private int nextRepository;

	private String configPatterns;

	private final Map<MetricKey, Long> counters = new LinkedHashMap<>();

	private final Map<MetricKey, TimerStats> timers = new LinkedHashMap<>();

	private volatile boolean enabled;

	private boolean closed;

	private boolean exited;

	private int exitCode;

	Trace2(Writer writer, boolean owned, boolean brief, int nesting,
			boolean debug, String parent) {
		this(writer == null ? List.of()
				: List.of(new Trace2Target(
						new Trace2Destination(writer, owned, debug),
						Format.EVENT, brief, nesting)),
				sessionId(parent), List.of("jgit", "embedded")); //$NON-NLS-1$ //$NON-NLS-2$
	}

	Trace2(List<Trace2Target> targets, String sid, List<String> argv) {
		this.targets = List.copyOf(targets);
		this.sid = sid;
		enabled = !targets.isEmpty();
		start = enabled ? System.nanoTime() : 0;
		mainThread = Thread.currentThread().getId();
		if (enabled) {
			emit("version", fields("evt", "4", "exe", "jgit")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
																// //$NON-NLS-6$
			emit("start", fields("t_abs", elapsed(start), "argv", argv)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
	}

	/** @return the lazily configured library tracer */
	public static Trace2 getInstance() {
		return Holder.INSTANCE;
	}

	/**
	 * Initialize CLI argv before the first use of the default tracer. Only the
	 * CLI process owner should call this; embedded hosts need not expose argv.
	 *
	 * @param argv
	 *            arguments excluding the executable name
	 */
	public static void initializeCommandLine(String[] argv) {
		commandLine = new String[argv.length + 1];
		commandLine[0] = "jgit"; //$NON-NLS-1$
		System.arraycopy(argv, 0, commandLine, 1, argv.length);
		getInstance();
	}

	private static Trace2 fromEnvironment() {
		SystemReader reader = SystemReader.getInstance();
		List<Trace2Target> targets = new ArrayList<>();
		String sid = sessionId(reader.getenv("GIT_TRACE2_PARENT_SID")); //$NON-NLS-1$
		boolean debug = false;
		Config systemConfig = new Config();
		Config globalConfig = new Config();
		try {
			Trace2SystemConfig config = new Trace2SystemConfig(reader);
			systemConfig = config.system();
			globalConfig = config.global();
		} catch (Exception failure) {
			// Environment targets remain usable when configuration cannot be
			// read.
		}
		try {
			debug = isTrue(setting(reader, globalConfig, "GIT_TRACE2_DST_DEBUG", //$NON-NLS-1$
					"destinationDebug")); //$NON-NLS-1$
		} catch (Exception failure) {
			// A broken host config must not prevent Git operations.
		}
		String[][] names = { { "GIT_TRACE2", "normalTarget", "GIT_TRACE2_BRIEF", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				"normalBrief" }, //$NON-NLS-1$
				{ "GIT_TRACE2_PERF", "perfTarget", "GIT_TRACE2_PERF_BRIEF", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
						"perfBrief" }, //$NON-NLS-1$
				{ "GIT_TRACE2_EVENT", "eventTarget", "GIT_TRACE2_EVENT_BRIEF", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
						"eventBrief" } }; //$NON-NLS-1$
		for (int i = 0; i < names.length; i++) {
			try {
				String[] name = names[i];
				boolean brief = isTrue(
						setting(reader, globalConfig, name[2], name[3]));
				int nesting = 2;
				if (i == 2) {
					String depth = setting(reader, globalConfig,
							"GIT_TRACE2_EVENT_NESTING", //$NON-NLS-1$
							"eventNesting"); //$NON-NLS-1$
					try {
						if (depth != null && Integer.parseInt(depth) > 0) {
							nesting = Integer.parseInt(depth);
						}
					} catch (NumberFormatException failure) {
						// C Git retains its default for non-positive/malformed
						// limits.
					}
				}
				Trace2Destination destination = Trace2Destination
						.open(setting(reader, globalConfig, name[0], name[1]),
								sid, debug,
								positiveInteger(setting(reader, globalConfig,
										"GIT_TRACE2_MAX_FILES", "maxFiles"), //$NON-NLS-1$ //$NON-NLS-2$
										0));
				if (destination != null) {
					targets.add(new Trace2Target(destination,
							Format.values()[i], brief, nesting));
				}
			} catch (Exception failure) {
				if (debug) {
					System.err.println("JGit: cannot configure Trace2 target"); //$NON-NLS-1$
				}
			}
		}
		String[] argv = commandLine;
		Trace2 trace = new Trace2(targets, sid,
				argv == null ? List.of("jgit", "embedded") //$NON-NLS-1$ //$NON-NLS-2$
						: redactArguments(argv));
		if (trace.enabled) {
			try {
				trace.configPatterns = setting(reader, globalConfig,
						"GIT_TRACE2_CONFIG_PARAMS", "configParams"); //$NON-NLS-1$ //$NON-NLS-2$
				trace.reportConfig(systemConfig, "system"); //$NON-NLS-1$
				trace.reportConfig(globalConfig, "global"); //$NON-NLS-1$
				String variables = setting(reader, globalConfig,
						"GIT_TRACE2_ENV_VARS", "envVars"); //$NON-NLS-1$ //$NON-NLS-2$
				if (variables != null) {
					for (String variable : variables.split(",")) { //$NON-NLS-1$
						String name = variable.trim();
						String value = reader.getenv(name);
						if (value != null && !value.isEmpty()) {
							trace.parameter("command", name, value); //$NON-NLS-1$
						}
					}
				}
			} catch (Exception failure) {
				if (debug) {
					System.err.println("JGit: cannot report Trace2 parameters"); //$NON-NLS-1$
				}
			}
			try {
				Runtime.getRuntime().addShutdownHook(
						new Thread(trace::close, "JGit-Trace2-shutdown")); //$NON-NLS-1$
			} catch (IllegalStateException | SecurityException failure) {
				trace.close();
			}
		}
		initialized = trace;
		return trace;
	}

	static Trace2 peekInstance() {
		return initialized;
	}

	private static String setting(SystemReader reader, Config config,
			String env, String key) throws Exception {
		String value = reader.getenv(env);
		return value != null && !value.isEmpty() ? value
				: config.getString("trace2", null, key); //$NON-NLS-1$
	}

	private static int positiveInteger(String value, int fallback) {
		try {
			return value == null ? fallback
					: Math.max(0, Integer.parseInt(value));
		} catch (NumberFormatException failure) {
			return fallback;
		}
	}

	private static boolean isTrue(String value) {
		return "1".equals(value) || "true".equalsIgnoreCase(value) //$NON-NLS-1$ //$NON-NLS-2$
				|| "yes".equalsIgnoreCase(value) //$NON-NLS-1$
				|| "on".equalsIgnoreCase(value); //$NON-NLS-1$
	}

	private static String sessionId(String parent) {
		String stamp = DateTimeFormatter
				.ofPattern("yyyyMMdd'T'HHmmss.SSSSSS'Z'") //$NON-NLS-1$
				.withZone(ZoneOffset.UTC).format(Instant.now());
		String host = "Localhost"; //$NON-NLS-1$
		try {
			host = "H" + java.util.HexFormat.of() //$NON-NLS-1$
					.formatHex(java.security.MessageDigest.getInstance("SHA-1") //$NON-NLS-1$
							.digest(SystemReader.getInstance().getHostname()
									.getBytes(
											java.nio.charset.StandardCharsets.UTF_8)))
					.substring(0, 8);
		} catch (java.security.NoSuchAlgorithmException
				| RuntimeException failure) {
			// Portable fallback matching upstream's unknown-host
			// representation.
		}
		String id = stamp + '-' + host
				+ String.format(java.util.Locale.ROOT, "-P%08x-C%08x", //$NON-NLS-1$
						Long.valueOf(ProcessHandle.current().pid()),
						Integer.valueOf(SESSION_NUMBER.incrementAndGet()));
		if (id.endsWith("-C00000001")) {
//$NON-NLS-0$
			id = id.substring(0, id.length() - 10);
		}
		return parent == null || parent.isEmpty() ? id : parent + '/' + id;
	}

	private static List<String> redactArguments(String[] argv) {
		List<String> result = new ArrayList<>(argv.length);
		boolean redactNext = false;
		for (String arg : argv) {
			String lower = arg.toLowerCase(java.util.Locale.ROOT);
			boolean secret = lower
					.matches("(?s).*(password|token|authorization|credential).*"); //$NON-NLS-1$
			result.add(redactNext || secret ? "<redacted>" //$NON-NLS-1$
					: arg.replaceAll("(://)[^/@\\s]+@", "$1<redacted>@")); //$NON-NLS-1$ //$NON-NLS-2$
			redactNext = secret && !arg.contains("="); //$NON-NLS-1$
		}
		return result;
	}

	/** @return whether at least one target is enabled */
	public boolean isEnabled() {
		return enabled;
	}

	/**
	 * @param repository
	 *            repository or null
	 * @param command
	 *            Git command name
	 * @param origin
	 *            static caller identifier
	 * @return thread-confined command scope
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
			emit("cmd_name", fields("name", command, "hierarchy", command)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		Scope scope = region(repository, "jgit", command); //$NON-NLS-1$
		if (scope != NO_SCOPE) {
			scope.command = true;
		}
		scope.data("origin", origin); //$NON-NLS-1$
		return scope;
	}

	/**
	 * @param repository
	 *            repository or null
	 * @param category
	 *            category
	 * @param label
	 *            operation label
	 * @return thread-confined operation scope
	 */
	public Scope region(Repository repository, String category, String label) {
		return region(repository, category, label, null);
	}

	private Scope region(Repository repository, String category, String label,
			String message) {
		if (!enabled) {
			return NO_SCOPE;
		}
		Scope scope = new Scope(this, current.get(), repositoryId(repository),
				category, label);
		current.set(scope);
		Map<String, Object> entry = scope.fields();
		if (message != null) entry.put("msg", message); //$NON-NLS-1$
		emit("region_enter", entry); //$NON-NLS-1$
		return scope;
	}

	/* Bind hook identity to this builder, preserving FS.runProcess dispatch. */
	Scope hook(Repository repository, String name, ProcessBuilder builder) {
		Scope scope = region(repository, "hook", name); //$NON-NLS-1$
		if (scope.isEnabled()) {
			scope.hookProcess = builder;
		}
		return scope;
	}

	Scope hook(Repository repository, String name, int jobs) {
		if (!enabled) return NO_SCOPE;
		return region(repository, "hook", name, "max:" + jobs); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private synchronized int repositoryId(Repository repository) {
		if (repository == null) {
			return 0;
		}
		File directory = repository.isBare() ? repository.getDirectory()
				: repository.getWorkTree();
		if (directory == null) {
			return 0;
		}
		Integer id = repositories.get(repository);
		if (id == null) {
			id = Integer.valueOf(++nextRepository);
			repositories.put(repository, id);
			reportConfig(repository.getConfig(), "local"); //$NON-NLS-1$
			emit("def_repo", fields("repo", id, "worktree", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					directory.getAbsolutePath()));
		}
		return id.intValue();
	}

	/**
	 * @param mode
	 *            command mode
	 */
	public void commandMode(String mode) {
		if (enabled) {
			emit("cmd_mode", fields("name", mode)); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	/**
	 * @param path
	 *            executable path
	 */
	public void commandPath(String path) {
		if (enabled) {
			emit("cmd_path", fields("path", path)); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	/**
	 * @param names
	 *            ancestor command names, nearest first
	 */
	public void commandAncestry(String... names) {
		if (enabled) {
			emit("cmd_ancestry", fields("ancestry", Arrays.asList(names))); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	/**
	 * @param name
	 *            alias
	 * @param argv
	 *            expanded arguments
	 */
	public void alias(String name, String... argv) {
		if (enabled) {
			emit("alias", fields("alias", name, "argv", redactArguments(argv))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
	}

	/**
	 * Report a parameter after the caller's allow-list/privacy checks.
	 *
	 * @param scope
	 *            Git configuration scope
	 * @param name
	 *            parameter name
	 * @param value
	 *            value or null
	 */
	public void parameter(String scope, String name, String value) {
		if (!enabled) {
			return;
		}
		Map<String, Object> f = fields("scope", scope, "param", name); //$NON-NLS-1$ //$NON-NLS-2$
		if (value != null) {
			String lower = name.toLowerCase(java.util.Locale.ROOT);
			f.put("value", lower.matches( //$NON-NLS-1$
					".*(password|token|authorization|credential|extraheader).*") //$NON-NLS-1$
							? "<redacted>" //$NON-NLS-1$
							: value.replaceAll("(://)[^/@\\s]+@", //$NON-NLS-1$
									"$1<redacted>@")); //$NON-NLS-1$
		}
		emit("def_param", f); //$NON-NLS-1$
	}

	/**
	 * @param message
	 *            diagnostic text, excluding secrets
	 */
	public void error(String message) {
		if (enabled) {
			emit("error", fields("msg", message, "fmt", "%s")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		}
	}

	/**
	 * @param message
	 *            diagnostic text, excluding secrets
	 */
	public void printf(String message) {
		if (enabled) {
			emit("printf", fields("t_abs", elapsed(start), "msg", message)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
	}

	/**
	 * Record a process exit. Embedded command/job owners must not call this.
	 *
	 * @param code
	 *            process exit status
	 */
	public synchronized void exit(int code) {
		if (!enabled || exited) {
			return;
		}
		exited = true;
		exitCode = code;
		emit("exit", //$NON-NLS-1$
				fields("t_abs", elapsed(start), "code", Integer.valueOf(code))); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * Trace an explicitly managed worker lifetime. Must close on this thread.
	 *
	 * @param name
	 *            worker label
	 * @return worker lifetime
	 */
	public Worker thread(String name) {
		if (!enabled) {
			return new Worker(null, null);
		}
		if (worker.get() != null || current.get() != null) {
			throw new IllegalStateException("Worker context already active"); //$NON-NLS-1$
		}
		Worker lifetime = new Worker(this,
				"th" + nextWorker.incrementAndGet() + ':' + name); //$NON-NLS-1$
		worker.set(lifetime);
		emit("thread_start", fields()); //$NON-NLS-1$
		return lifetime;
	}

	/**
	 * @param childClass
	 *            child kind
	 * @param directory
	 *            working directory or null
	 * @param useShell
	 *            whether a shell is used
	 * @param argv
	 *            redacted child arguments
	 * @return child handle; call exit or ready with the real process result
	 */
	public Child childStart(String childClass, String directory,
			boolean useShell, String... argv) {
		return childStart(childClass, null, directory, useShell, argv);
	}

	private Child childStart(String childClass, String hookName,
			String directory, boolean useShell, String... argv) {
		if (!enabled) {
			return new Child(null, 0);
		}
		Child child = new Child(this, nextChild.getAndIncrement());
		Map<String, Object> f = fields("child_id", Integer.valueOf(child.id), //$NON-NLS-1$
				"child_class", childClass, "use_shell", //$NON-NLS-1$ //$NON-NLS-2$
				Boolean.valueOf(useShell), "argv", redactArguments(argv));
		if (directory != null) {
			f.put("cd", directory); //$NON-NLS-1$
		}
		if (hookName != null) {
			f.put("hook_name", hookName); //$NON-NLS-1$
		}
		emit("child_start", f); //$NON-NLS-1$
		return child;
	}

	/**
	 * Trace a ProcessBuilder and propagate the parent session to the child.
	 *
	 * @param builder
	 *            process to start
	 * @return child handle
	 */
	public Child childStart(ProcessBuilder builder) {
		if (!enabled) {
			return new Child(null, 0);
		}
		builder.environment().put("GIT_TRACE2_PARENT_SID", sid); //$NON-NLS-1$
		File directory = builder.directory();
		String hookName = null;
		for (Scope scope = current.get(); scope != null; scope = scope.parent) {
			if (scope.hookProcess == builder) {
				hookName = scope.label;
				break;
			}
		}
		return childStart(hookName == null ? "?" : "hook", hookName, //$NON-NLS-1$ //$NON-NLS-2$
				directory == null ? null : directory.getAbsolutePath(),
				hookName != null, builder.command().toArray(new String[0]));
	}

	/**
	 * @param id
	 *            exec ID
	 * @param executable
	 *            executable
	 * @param argv
	 *            arguments
	 */
	public void exec(int id, String executable, String... argv) {
		if (enabled) {
			emit("exec", fields("exec_id", Integer.valueOf(id), //$NON-NLS-1$ //$NON-NLS-2$
					"exe", executable, "argv", redactArguments(argv))); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	/**
	 * @param id
	 *            exec ID
	 * @param code
	 *            result code
	 */
	public void execResult(int id, int code) {
		if (enabled) {
			emit("exec_result", fields("exec_id", Integer.valueOf(id), //$NON-NLS-1$ //$NON-NLS-2$
					"code", Integer.valueOf(code))); //$NON-NLS-1$
		}
	}

	private void reportConfig(Config source, String scope) {
		if (configPatterns == null || configPatterns.isEmpty()) {
			return;
		}
		try {
			// Isolate this scope's own entries from inherited configuration.
			Config config = new Config();
			config.fromText(source.toText());
			for (String section : config.getSections()) {
				List<String> subsections = new ArrayList<>();
				subsections.add(null);
				subsections.addAll(config.getSubsections(section));
				for (String subsection : subsections) {
					for (String name : config.getNames(section, subsection)) {
						String key = section + '.'
								+ (subsection == null ? "" : subsection + '.') //$NON-NLS-1$
								+ name;
						for (String pattern : configPatterns.split(",")) { //$NON-NLS-1$
							FileNameMatcher matcher = new FileNameMatcher(
									pattern.trim().toLowerCase(
											java.util.Locale.ROOT),
									null);
							matcher.append(
									key.toLowerCase(java.util.Locale.ROOT));
							if (matcher.isMatch()) {
								for (String value : config.getStringList(
										section, subsection, name)) {
									parameter(scope, key, value);
								}
								break;
							}
						}
					}
				}
			}
		} catch (Exception failure) {
			// Configuration reporting must not change the Git operation.
		}
	}

	/**
	 * Add a counter value for emission when the session ends.
	 *
	 * @param category
	 *            category
	 * @param name
	 *            counter name
	 * @param delta
	 *            nonnegative increment
	 */
	public synchronized void counter(String category, String name, long delta) {
		if (!enabled) {
			return;
		}
		if (delta < 0) {
			throw new IllegalArgumentException("Negative counter increment"); //$NON-NLS-1$
		}
		MetricKey key = new MetricKey(category, name);
		counters.merge(key, Long.valueOf(delta), Long::sum);
		Worker w = worker.get();
		if (w != null) {
			w.counters.merge(key, Long.valueOf(delta), Long::sum);
		}
	}

	/**
	 * Time an interval using the monotonic clock; aggregates emit at session
	 * end.
	 *
	 * @param category
	 *            category
	 * @param name
	 *            timer name
	 * @return interval to close
	 */
	public Interval timer(String category, String name) {
		return enabled ? new Interval(this, new MetricKey(category, name))
				: Interval.DISABLED;
	}

	private record MetricKey(String category, String name) {
	}

	private static final class TimerStats {
		long intervals;

		long total;

		long min = Long.MAX_VALUE;

		long max;
	}

	/** Monotonic timer interval. @since 7.8 */
	public static final class Interval implements AutoCloseable {
		private static final Interval DISABLED = new Interval(null, null);

		private final Trace2 trace;

		private final MetricKey key;

		private final Worker worker;

		private final long start;

		private boolean closed;

		private Interval(Trace2 trace, MetricKey key) {
			this.trace = trace;
			this.key = key;
			worker = trace == null ? null : trace.worker.get();
			start = trace == null ? 0 : System.nanoTime();
		}

		@Override
		public synchronized void close() {
			if (trace == null || closed) {
				return;
			}
			closed = true;
			long elapsed = System.nanoTime() - start;
			synchronized (trace) {
				if (!trace.enabled) {
					return;
				}
				TimerStats stats = trace.timers.computeIfAbsent(key,
						k -> new TimerStats());
				stats.intervals++;
				stats.total += elapsed;
				stats.min = Math.min(stats.min, elapsed);
				stats.max = Math.max(stats.max, elapsed);
				if (worker != null) {
					TimerStats threadStats = worker.timers.computeIfAbsent(key,
							k -> new TimerStats());
					threadStats.intervals++;
					threadStats.total += elapsed;
					threadStats.min = Math.min(threadStats.min, elapsed);
					threadStats.max = Math.max(threadStats.max, elapsed);
				}
			}
		}
	}

	private synchronized void emit(String event, Map<String, Object> fields) {
		if (!enabled) {
			return;
		}
		Worker w = worker.get();
		String thread = "atexit".equals(event) ? "main"
				: w != null ? w.name
						: Thread.currentThread().getId() == mainThread ? "main" //$NON-NLS-1$
								: "jgit-" + Thread.currentThread().getId(); //$NON-NLS-1$
		boolean active = false;
		for (Trace2Target target : targets) {
			target.emit(event, sid, thread, fields);
			active |= target.isEnabled();
		}
		enabled = active;
	}

	static String quote(String text) {
		return Trace2Target.quote(text);
	}

	private static Map<String, Object> fields(Object... pairs) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			result.put((String) pairs[i], pairs[i + 1]);
		}
		return result;
	}

	private static double elapsed(long since) {
		return (System.nanoTime() - since) / 1_000_000_000.0;
	}

	/**
	 * End the library session and release owned destinations without exiting
	 * the JVM.
	 */
	@Override
	public synchronized void close() {
		if (closed) {
			return;
		}
		closed = true;
		for (Map.Entry<MetricKey, Long> entry : counters.entrySet()) {
			MetricKey key = entry.getKey();
			emit("counter", fields("category", key.category(), "name", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					key.name(), "count", entry.getValue())); //$NON-NLS-1$
		}
		for (Map.Entry<MetricKey, TimerStats> entry : timers.entrySet()) {
			MetricKey key = entry.getKey();
			TimerStats stats = entry.getValue();
			emit("timer", fields("category", key.category(), "name", key.name(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					"intervals", Long.valueOf(stats.intervals), "t_total", //$NON-NLS-1$ //$NON-NLS-2$
					stats.total / 1_000_000_000.0, "t_min", stats.min / 1_000_000_000.0, "t_max", //$NON-NLS-2$
					stats.max / 1_000_000_000.0));
		}
		emit("atexit", fields("code", Integer.valueOf(exitCode), "t_abs", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				elapsed(start)));
		enabled = false;
		for (Trace2Target target : targets) {
			target.close();
		}
		repositories.clear();
		current.remove();
		worker.remove();
	}

	/** Thread-confined region. @since 7.8 */
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

		private ProcessBuilder hookProcess;

		void hookProcess(ProcessBuilder builder) {
			if (isEnabled()) {
				hookProcess = builder;
			}
		}

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

		private Map<String, Object> fields() {
			Map<String, Object> f = Trace2.fields("nesting", //$NON-NLS-1$
					Integer.valueOf(depth), "category", category, "label", //$NON-NLS-2$
					label);
			if (repo != 0) {
				f.put("repo", Integer.valueOf(repo)); //$NON-NLS-1$
			}
			return f;
		}

		/** @return whether this scope can emit */
		public boolean isEnabled() {
			return trace != null && trace.enabled && !closed;
		}

		/**
		 * @param key
		 *            metric
		 * @param value
		 *            string value
		 */
		public void data(String key, String value) {
			dataValue("data", key, value); //$NON-NLS-1$
		}

		/**
		 * @param key
		 *            metric
		 * @param value
		 *            structured JSON-compatible value
		 */
		public void dataJson(String key, Object value) {
			if (isEnabled()) {
				Trace2Target.json(value); // Validate before changing any
											// target.
				dataValue("data_json", key, value); //$NON-NLS-1$
			}
		}

		private void dataValue(String event, String key, Object value) {
			if (!isEnabled()) {
				return;
			}
			Map<String, Object> f = fields();
			f.remove("label"); //$NON-NLS-1$
			f.put("key", key); //$NON-NLS-1$
			f.put("value", value); //$NON-NLS-1$
			f.put("t_abs", Double.valueOf(elapsed(trace.start))); //$NON-NLS-1$
			f.put("t_rel", Double.valueOf(elapsed(start))); //$NON-NLS-1$
			trace.emit(event, f);
		}

		/**
		 * Report the exception type without logging credential-bearing
		 * messages.
		 *
		 * @param failure
		 *            operation failure
		 */
		public void failure(Throwable failure) {
			if (isEnabled()) {
				trace.error(failure.getClass().getName());
			}
		}

		@Override
		public void close() {
			if (trace == null || closed || trace.current.get() != this) {
				return;
			}
			closed = true;
			Map<String, Object> f = fields();
			f.put("t_rel", Double.valueOf(elapsed(start))); //$NON-NLS-1$
			trace.emit("region_leave", f); //$NON-NLS-1$
			if (parent == null) {
				trace.current.remove();
			} else {
				trace.current.set(parent);
			}
		}
	}

	/** Explicit worker lifetime. @since 7.8 */
	public static final class Worker implements AutoCloseable {
		private final Trace2 trace;

		private final String name;

		private final long start;

		private final Map<MetricKey, Long> counters = new LinkedHashMap<>();

		private final Map<MetricKey, TimerStats> timers = new LinkedHashMap<>();

		private boolean closed;

		private Worker(Trace2 trace, String name) {
			this.trace = trace;
			this.name = name;
			start = trace == null ? 0 : System.nanoTime();
		}

		@Override
		public void close() {
			if (trace == null || closed || trace.worker.get() != this) {
				return;
			}
			closed = true;
			synchronized (trace) {
				for (Map.Entry<MetricKey, Long> entry : counters.entrySet()) {
					MetricKey key = entry.getKey();
					trace.emit("th_counter", fields("category", key.category(), //$NON-NLS-1$ //$NON-NLS-2$
							"name", key.name(), "count", entry.getValue())); //$NON-NLS-1$ //$NON-NLS-2$
				}
				for (Map.Entry<MetricKey, TimerStats> entry : timers
						.entrySet()) {
					MetricKey key = entry.getKey();
					TimerStats stats = entry.getValue();
					trace.emit("th_timer", //$NON-NLS-1$
							fields("category", key.category(), "name", //$NON-NLS-1$ //$NON-NLS-2$
									key.name(), "intervals", Long.valueOf(stats.intervals),
									"t_total", stats.total / 1_000_000_000.0, //$NON-NLS-1$
									"t_min", stats.min / 1_000_000_000.0, //$NON-NLS-1$
									"t_max", stats.max / 1_000_000_000.0)); //$NON-NLS-1$
				}
			}
			trace.emit("thread_exit", fields("t_rel", elapsed(start))); //$NON-NLS-1$ //$NON-NLS-2$
			trace.worker.remove();
		}
	}

	/** Child process correlation. @since 7.8 */
	public static final class Child {
		private final Trace2 trace;

		private final int id;

		private final long start;

		private boolean finished;

		private Child(Trace2 trace, int id) {
			this.trace = trace;
			this.id = id;
			start = trace == null ? 0 : System.nanoTime();
		}

		/**
		 * @param pid
		 *            child PID
		 * @param code
		 *            actual exit status
		 */
		public synchronized void exit(long pid, int code) {
			if (trace == null || finished) {
				return;
			}
			finished = true;
			trace.emit("child_exit", fields("child_id", Integer.valueOf(id), //$NON-NLS-1$ //$NON-NLS-2$
					"pid", Long.valueOf(pid), "code", Integer.valueOf(code), //$NON-NLS-1$ //$NON-NLS-2$
					"t_rel", elapsed(start))); //$NON-NLS-1$
		}

		/**
		 * @param pid
		 *            child PID
		 * @param readiness
		 *            readiness description
		 */
		public synchronized void ready(long pid, String readiness) {
			if (trace == null || finished) {
				return;
			}
			finished = true;
			trace.emit("child_ready", fields("child_id", Integer.valueOf(id), //$NON-NLS-1$ //$NON-NLS-2$
					"pid", Long.valueOf(pid), "ready", readiness, "t_rel", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					elapsed(start)));
		}
	}
}
