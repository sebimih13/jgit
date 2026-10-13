/*
 * Copyright (C) 2026, and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.lib;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configured hook events in effective configuration order, including includes.
 *
 * @since 7.8
 */
public final class HookConfig {
	/** Cached parser; configuration changes invalidate its snapshot. */
	public static final Config.SectionParser<HookConfig> KEY = HookConfig::new;

	private static final Logger LOG = LoggerFactory.getLogger(HookConfig.class);

	private static final Set<String> KNOWN = Set.of("applypatch-msg", //$NON-NLS-1$
			"commit-msg", "fsmonitor-watchman", //$NON-NLS-1$ //$NON-NLS-2$
			"p4-changelist", "p4-post-changelist", "p4-pre-submit", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			"p4-prepare-changelist", "post-applypatch", "post-checkout", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			"post-commit", "post-index-change", "post-merge", "post-receive", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			"post-rewrite", "post-update", "pre-applypatch", "pre-auto-gc", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			"pre-commit", "pre-merge-commit", "pre-push", "pre-rebase", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			"pre-receive", "prepare-commit-msg", "proc-receive", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			"push-to-checkout", "receive-report", "reference-transaction", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			"sendemail-validate", "update"); //$NON-NLS-1$ //$NON-NLS-2$

	/**
	 * A configured hook command.
	 * 
	 * @param name
	 *            friendly name
	 * @param command
	 *            shell command, or null for a disabled missing command
	 * @param enabled
	 *            whether this command is enabled
	 * @param parallel
	 *            whether concurrent execution is permitted
	 */
	public record Command(String name, String command, boolean enabled,
			boolean parallel) {
	}

	private final Map<String, List<Command>> hooks;

	private final Map<String, Integer> eventJobs;

	private final int jobs;

	private HookConfig(Config config) {
		Builder builder = new Builder();
		builder.read(config.getState());
		Map<String, List<Command>> result = new LinkedHashMap<>();
		Set<String> friendly = new LinkedHashSet<>(builder.commands.keySet());
		builder.parallel.forEach((name, value) -> {
			if (value.booleanValue()) {
				friendly.add(name);
			}
		});
		builder.events.values().forEach(friendly::addAll);
		builder.events.forEach((event, names) -> {
			List<Command> commands = new ArrayList<>();
			for (String name : names) {
				boolean enabled = builder.enabled
						.getOrDefault(name, Boolean.TRUE).booleanValue();
				String command = builder.commands.get(name);
				if (command == null) {
					if (enabled) {
						throw new JGitInternalException("hook." + name //$NON-NLS-1$
								+ ".command must be configured or its event removed"); //$NON-NLS-1$
					}
					LOG.warn("Disabled hook '{}' has no command configured", //$NON-NLS-1$
							name);
				}
				if (!friendly.contains(event) && !builder.enabled
						.getOrDefault(event, Boolean.TRUE).booleanValue()) {
					enabled = false;
				}
				commands.add(new Command(name, command, enabled,
						builder.parallel.getOrDefault(name, Boolean.FALSE)
								.booleanValue()));
			}
			result.put(event, List.copyOf(commands));
		});
		hooks = Collections.unmodifiableMap(result);
		eventJobs = Map.copyOf(builder.eventJobs);
		jobs = builder.jobs;
	}

	/**
	 * @param event
	 *            hook event
	 * @return configured commands in invocation order, including disabled ones
	 */
	public List<Command> getCommands(String event) {
		return hooks.getOrDefault(event, List.of());
	}

	/**
	 * @param event
	 *            hook event
	 * @return configured maximum job count, before command-specific serial
	 *         limits
	 */
	public int getJobs(String event) {
		int count = eventJobs.getOrDefault(event, Integer.valueOf(jobs))
				.intValue();
		for (Command command : getCommands(event)) {
			if (!command.parallel()) {
				return 1;
			}
		}
		return count;
	}

	private static final class Builder {
		final Map<String, LinkedHashSet<String>> events = new LinkedHashMap<>();

		final Map<String, String> commands = new LinkedHashMap<>();

		final Map<String, Boolean> enabled = new LinkedHashMap<>();

		final Map<String, Boolean> parallel = new LinkedHashMap<>();

		final Map<String, Integer> eventJobs = new LinkedHashMap<>();

		int jobs = 1;

		void read(ConfigSnapshot snapshot) {
			if (snapshot.baseState != null) {
				read(snapshot.baseState);
			}
			for (ConfigLine line : snapshot.entryList) {
				if (line.section == null
						|| !StringUtils.equalsIgnoreCase("hook", line.section) //$NON-NLS-1$
						|| line.name == null) {
					continue;
				}
				String value = Config.isMissing(line.value) ? null
						: line.value == null ? "" : line.value; //$NON-NLS-1$
				if (line.subsection == null) {
					if (line.name.equalsIgnoreCase("jobs") && value != null) { //$NON-NLS-1$
						Integer parsed = parseJobs(value);
						if (parsed != null) {
							jobs = parsed.intValue();
						}
					}
					continue;
				}
				if (value == null) {
					throw new JGitInternalException("Missing value for hook." //$NON-NLS-1$
							+ line.subsection + '.' + line.name);
				}
				String name = line.subsection;
				switch (line.name.toLowerCase(Locale.ROOT)) {
				case "event": //$NON-NLS-1$
					if (value.isEmpty()) {
						events.values().forEach(names -> names.remove(name));
					} else {
						if (KNOWN.contains(name)) {
							throw new JGitInternalException(
									"Hook friendly-name collides with event: " //$NON-NLS-1$
											+ name);
						}
						if (name.equals(value)) {
							LOG.warn("Hook friendly-name '{}' equals its event", //$NON-NLS-1$
									name);
						}
						LinkedHashSet<String> names = events.computeIfAbsent(
								value, key -> new LinkedHashSet<>());
						names.remove(name);
						names.add(name);
					}
					break;
				case "command": //$NON-NLS-1$
					commands.put(name, value);
					break;
				case "enabled": //$NON-NLS-1$
					Boolean state = parseBoolean(value);
					if (state != null) {
						enabled.put(name, state);
					}
					break;
				case "parallel": //$NON-NLS-1$
					Boolean concurrent = parseBoolean(value);
					if (concurrent == null) {
						LOG.warn(
								"Ignoring invalid hook parallel setting for '{}'", //$NON-NLS-1$
								name);
					} else {
						parallel.put(name, concurrent);
					}
					break;
				case "jobs": //$NON-NLS-1$
					Integer count = parseJobs(value);
					if (count != null) {
						eventJobs.put(name, count);
					}
					break;
				default:
					break;
				}
			}
		}
	}

	private static Integer parseJobs(String value) {
		Integer count = parseInteger(value);
		if (count != null && count.intValue() == -1) {
			return Integer.valueOf(Runtime.getRuntime().availableProcessors());
		}
		if (count == null || count.intValue() <= 0) {
			LOG.warn("Ignoring invalid hook job count"); //$NON-NLS-1$
			return null;
		}
		return count;
	}

	private static Boolean parseBoolean(String value) {
		switch (value.toLowerCase(Locale.ROOT)) {
		case "true": //$NON-NLS-1$
		case "yes": //$NON-NLS-1$
		case "on": //$NON-NLS-1$
			return Boolean.TRUE;
		case "": //$NON-NLS-1$
		case "false": //$NON-NLS-1$
		case "no": //$NON-NLS-1$
		case "off": //$NON-NLS-1$
			return Boolean.FALSE;
		default:
			Integer number = parseInteger(value);
			return number == null ? null
					: Boolean.valueOf(number.intValue() != 0);
		}
	}

	private static final Pattern INTEGER = Pattern.compile(
			"([+-]?)(0[xX][0-9a-fA-F]+|0[0-7]*|[1-9][0-9]*)([kKmMgG]?)"); //$NON-NLS-1$

	private static Integer parseInteger(String value) {
		int offset = 0;
		while (offset < value.length()) {
			char ch = value.charAt(offset);
			if (ch != ' ' && (ch < '\t' || ch > '\r'))
				break;
			offset++;
		}
		Matcher match = INTEGER.matcher(value.substring(offset));
		if (!match.matches())
			return null;
		String digits = match.group(2);
		int radix = 10;
		if (digits.startsWith("0x") || digits.startsWith("0X")) { //$NON-NLS-1$ //$NON-NLS-2$
			radix = 16;
			digits = digits.substring(2);
		} else if (digits.charAt(0) == '0') {
			radix = 8;
		}
		int firstDigit = 0;
		while (firstDigit < digits.length() - 1 && digits.charAt(firstDigit) == '0') firstDigit++;
		if (digits.length() - firstDigit > 32) return null;
		BigInteger number = new BigInteger(digits.substring(firstDigit), radix);
		if ("-".equals(match.group(1))) //$NON-NLS-1$
			number = number.negate();
		String suffix = match.group(3).toLowerCase(Locale.ROOT);
		long multiplier = switch (suffix) {
		case "k" -> 1024L; //$NON-NLS-1$
		case "m" -> 1024L * 1024; //$NON-NLS-1$
		case "g" -> 1024L * 1024 * 1024; //$NON-NLS-1$
		default -> 1L;
		};
		try {
			return Integer.valueOf(number
					.multiply(BigInteger.valueOf(multiplier)).intValueExact());
		} catch (ArithmeticException invalid) {
			return null;
		}
	}
}
