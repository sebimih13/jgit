/*
 * Copyright (C) 2010, Chris Aniszczyk <caniszczyk@gmail.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.text.MessageFormat;
import java.util.concurrent.Callable;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRefNameException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.StringUtils;
import org.eclipse.jgit.util.SystemReader;
import org.eclipse.jgit.util.Trace2;

/**
 * Create an empty git repository or reinitalize an existing one
 *
 * @see <a href="http://www.kernel.org/pub/software/scm/git/docs/git-init.html"
 *      >Git documentation about init</a>
 */
public class InitCommand implements Callable<Git> {
	private File directory;

	private File templateDirectory;

	private File gitDir;

	private boolean bare;

	private FS fs;

	private String initialBranch;

	private boolean relativePaths;

	/**
	 * {@inheritDoc}
	 * <p>
	 * Executes the {@code Init} command.
	 *
	 * @return a {@code Git} instance that owns the {@code Repository} that it
	 *         wraps.
	 */
	@Override
	public Git call() throws GitAPIException {
		try (Trace2.Scope trace = Trace2.getInstance().command(null,
				"init", "jgit.api.InitCommand")) { //$NON-NLS-1$ //$NON-NLS-2$
			boolean returned = false;
			try {
				Git result = callWithTrace();
				returned = true;
				return result;
			} catch (Throwable failure) {
				trace.failure(failure);
				throw failure;
			} finally {
				trace.data("completion", returned ? "returned" : "threw"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
		}
	}

	private Git callWithTrace() throws GitAPIException {
		try {
			RepositoryBuilder builder = new RepositoryBuilder();
			if (bare)
				builder.setBare();
			if (fs != null) {
				builder.setFS(fs);
			}
			builder.readEnvironment();
			if (gitDir != null)
				builder.setGitDir(gitDir);
			else
				gitDir = builder.getGitDir();
			if (directory != null) {
				if (bare)
					builder.setGitDir(directory);
				else {
					builder.setWorkTree(directory);
					if (gitDir == null)
						builder.setGitDir(new File(directory, Constants.DOT_GIT));
				}
			} else if (builder.getGitDir() == null) {
				String dStr = SystemReader.getInstance()
						.getProperty("user.dir"); //$NON-NLS-1$
				if (dStr == null)
					dStr = "."; //$NON-NLS-1$
				File d = new File(dStr);
				if (!bare)
					d = new File(d, Constants.DOT_GIT);
				builder.setGitDir(d);
			} else {
				// directory was not set but gitDir was set
				if (!bare) {
					String dStr = SystemReader.getInstance().getProperty(
							"user.dir"); //$NON-NLS-1$
					if (dStr == null)
						dStr = "."; //$NON-NLS-1$
					builder.setWorkTree(new File(dStr));
				}
			}
			builder.setInitialBranch(StringUtils.isEmptyOrNull(initialBranch)
					? SystemReader.getInstance().getUserConfig().getString(
							ConfigConstants.CONFIG_INIT_SECTION, null,
							ConfigConstants.CONFIG_KEY_DEFAULT_BRANCH)
					: initialBranch);
			copyTemplates(builder.getGitDir());
			Repository repository = builder.build();
			if (!repository.getObjectDatabase().exists())
				if (repository instanceof FileRepository) {
					((FileRepository) repository).create(bare, relativePaths);
				} else {
					repository.create(bare);
				}
			return new Git(repository, true);
		} catch (IOException | ConfigInvalidException e) {
			throw new JGitInternalException(e.getMessage(), e);
		}
	}

	/**
	 * Set the repository template directory.
	 * @param directory templates, or null for environment/config defaults
	 * @return this command
	 * @since 7.8
	 */
	public InitCommand setTemplateDirectory(File directory) {
		templateDirectory = directory;
		return this;
	}

	private void copyTemplates(File destination) throws IOException, ConfigInvalidException {
		String configured = templateDirectory != null ? templateDirectory.getPath()
				: SystemReader.getInstance().getenv("GIT_TEMPLATE_DIR"); //$NON-NLS-1$
		if (configured == null) {
			configured = SystemReader.getInstance().getUserConfig().getString("init", null, "templateDir"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (configured == null || configured.isEmpty()) return;
		Path source = new File(configured).toPath();
		if (!Files.isDirectory(source)) return;
		Path root = source.toRealPath();
		try (var files = Files.walk(root)) {
			for (Path file : files.toList()) {
				Path relative = root.relativize(file);
				boolean hidden = false;
				for (Path part : relative) {
					if (part.toString().startsWith(".")) hidden = true; //$NON-NLS-1$
				}
				if (hidden) continue;
				Path target = destination.toPath().resolve(relative);
				if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue;
				if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
					Files.createDirectories(target);
				} else {
					Files.createDirectories(target.getParent());
					Files.copy(file, target, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES);
				}
			}
		}
	}

	/**
	 * The optional directory associated with the init operation. If no
	 * directory is set, we'll use the current directory
	 *
	 * @param directory
	 *            the directory to init to
	 * @return this instance
	 * @throws java.lang.IllegalStateException
	 *             if the combination of directory, gitDir and bare is illegal.
	 *             E.g. if for a non-bare repository directory and gitDir point
	 *             to the same directory of if for a bare repository both
	 *             directory and gitDir are specified
	 */
	public InitCommand setDirectory(File directory)
			throws IllegalStateException {
		validateDirs(directory, gitDir, bare);
		this.directory = directory;
		return this;
	}

	/**
	 * Set the repository meta directory (.git)
	 *
	 * @param gitDir
	 *            the repository meta directory
	 * @return this instance
	 * @throws java.lang.IllegalStateException
	 *             if the combination of directory, gitDir and bare is illegal.
	 *             E.g. if for a non-bare repository directory and gitDir point
	 *             to the same directory of if for a bare repository both
	 *             directory and gitDir are specified
	 * @since 3.6
	 */
	public InitCommand setGitDir(File gitDir)
			throws IllegalStateException {
		validateDirs(directory, gitDir, bare);
		this.gitDir = gitDir;
		return this;
	}

	private static void validateDirs(File directory, File gitDir, boolean bare)
			throws IllegalStateException {
		if (directory != null) {
			if (bare) {
				if (gitDir != null && !gitDir.equals(directory))
					throw new IllegalStateException(MessageFormat.format(
							JGitText.get().initFailedBareRepoDifferentDirs,
							gitDir, directory));
			} else {
				if (gitDir != null && gitDir.equals(directory))
					throw new IllegalStateException(MessageFormat.format(
							JGitText.get().initFailedNonBareRepoSameDirs,
							gitDir, directory));
			}
		}
	}

	/**
	 * Set whether the repository is bare or not
	 *
	 * @param bare
	 *            whether the repository is bare or not
	 * @throws java.lang.IllegalStateException
	 *             if the combination of directory, gitDir and bare is illegal.
	 *             E.g. if for a non-bare repository directory and gitDir point
	 *             to the same directory of if for a bare repository both
	 *             directory and gitDir are specified
	 * @return this instance
	 */
	public InitCommand setBare(boolean bare) {
		validateDirs(directory, gitDir, bare);
		this.bare = bare;
		return this;
	}

	/**
	 * Set the file system abstraction to be used for repositories created by
	 * this command.
	 *
	 * @param fs
	 *            the abstraction.
	 * @return {@code this} (for chaining calls).
	 * @since 4.10
	 */
	public InitCommand setFs(FS fs) {
		this.fs = fs;
		return this;
	}

	/**
	 * Set the initial branch of the new repository. If not specified
	 * ({@code null} or empty), fall back to the default name (currently
	 * master).
	 *
	 * @param branch
	 *            initial branch name of the new repository
	 * @return {@code this}
	 * @throws InvalidRefNameException
	 *             if the branch name is not valid
	 *
	 * @since 5.11
	 */
	public InitCommand setInitialBranch(String branch)
			throws InvalidRefNameException {
		this.initialBranch = branch;
		return this;
	}

	/**
	 * * Set whether the repository shall use relative paths for GIT_DIR and
	 * GIT_WORK_TREE
	 *
	 * @param relativePaths
	 *            if true, use relative paths for GIT_DIR and GIT_WORK_TREE
	 * @return {@code this}
	 * @since 7.2
	 */
	public InitCommand setRelativeDirs(boolean relativePaths) {
		this.relativePaths = relativePaths;
		return this;
	}
}
