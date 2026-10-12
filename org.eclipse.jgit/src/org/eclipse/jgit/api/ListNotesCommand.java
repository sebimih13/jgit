/*
 * Copyright (C) 2011, Chris Aniszczyk <caniszczyk@gmail.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.notes.Note;
import org.eclipse.jgit.notes.NoteMap;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.Trace2;

/**
 * List object notes.
 *
 * @see <a href="http://www.kernel.org/pub/software/scm/git/docs/git-notes.html"
 *      >Git documentation about Notes</a>
 */
public class ListNotesCommand extends GitCommand<List<Note>> {

	private String notesRef = Constants.R_NOTES_COMMITS;

	/**
	 * Constructor for ListNotesCommand.
	 *
	 * @param repo
	 *            the {@link org.eclipse.jgit.lib.Repository}
	 */
	protected ListNotesCommand(Repository repo) {
		super(repo);
	}

	@Override
	public List<Note> call() throws GitAPIException {
		try (Trace2.Scope trace = Trace2.getInstance().command(repo,
				"notes", "jgit.api.ListNotesCommand")) { //$NON-NLS-1$ //$NON-NLS-2$
			boolean returned = false;
			try {
				List<Note> result = callWithTrace();
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

	private List<Note> callWithTrace() throws GitAPIException {
		checkCallable();
		List<Note> notes = new ArrayList<>();
		NoteMap map = NoteMap.newEmptyMap();
		try (RevWalk walk = new RevWalk(repo)) {
			Ref ref = repo.findRef(notesRef);
			// if we have a notes ref, use it
			if (ref != null) {
				RevCommit notesCommit = walk.parseCommit(ref.getObjectId());
				map = NoteMap.read(walk.getObjectReader(), notesCommit);
			}

			Iterator<Note> i = map.iterator();
			while (i.hasNext())
				notes.add(i.next());
		} catch (IOException e) {
			throw new JGitInternalException(e.getMessage(), e);
		}

		return notes;
	}

	/**
	 * Set the {@code Ref} to read notes from
	 *
	 * @param notesRef
	 *            the name of the {@code Ref} to read notes from. Note, the
	 *            default value of
	 *            {@link org.eclipse.jgit.lib.Constants#R_NOTES_COMMITS} will be
	 *            used if nothing is set
	 * @return {@code this}
	 * @see Constants#R_NOTES_COMMITS
	 */
	public ListNotesCommand setNotesRef(String notesRef) {
		checkCallable();
		this.notesRef = notesRef;
		return this;
	}

}
