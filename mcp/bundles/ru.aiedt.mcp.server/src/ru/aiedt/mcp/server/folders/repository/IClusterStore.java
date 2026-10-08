/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.repository;

import org.eclipse.core.resources.IProject;

import ru.aiedt.mcp.server.folders.model.ClusterStore;

/**
 * Reads and writes a project's clusters, one file per project.
 */
public interface IClusterStore
{
    /**
     * Loads a project's clusters.
     *
     * @param project the project
     * @return the stored clusters; empty when there is no file, or <code>null</code> when an existing
     *         file could not be read
     */
    ClusterStore load(IProject project);

    /**
     * Saves a project's clusters. Saving an empty set deletes the file rather than writing an empty one.
     * <p>
     * The result names what happened. {@link ClusterSaveOutcome#NO_CHANGE} means the file was already
     * in the asked state and is not a refusal. A refusal leaves the file as it was and carries a
     * reason code.
     * </p>
     *
     * @param project the project
     * @param storage the clusters to save
     * @return what the save did
     */
    ClusterSaveOutcome save(IProject project, ClusterStore storage);

    /**
     * Tells whether a project has a clusters file.
     *
     * @param project the project
     * @return <code>true</code> if the file exists
     */
    boolean exists(IProject project);

    /**
     * Tells whether the clusters file on disk still holds exactly what this store last read or
     * wrote for the project.
     * <p>
     * A change notification about a file carrying those bytes is the service's own write coming
     * back as an event, not a change from outside: the cache the write produced is current, and
     * the bytes nobody else touched are not something to reload from. A store with no fingerprint
     * for the project - it never read the project, or its read failed and was forgotten - counts
     * nothing as its own state, not even the absence of the file: only the absence it actually
     * read or wrote is its own.
     * </p>
     *
     * @param project the project
     * @return <code>true</code> when the file holds the bytes this store last saw; also
     *         <code>false</code> when the file cannot be read, which reads as foreign
     */
    boolean holdsWhatWasLastReadOrWritten(IProject project);

    /**
     * Deletes a project's clusters file.
     *
     * @param project the project
     * @return <code>true</code> if the file is gone afterwards - whether it was deleted or was never
     *         there
     */
    boolean delete(IProject project);
}
