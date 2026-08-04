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
     * @return the stored clusters, never <code>null</code>; empty when there is no file or it cannot be
     *         read
     */
    ClusterStore load(IProject project);

    /**
     * Saves a project's clusters. Saving an empty set deletes the file rather than writing an empty one.
     *
     * @param project the project
     * @param storage the clusters to save
     * @return <code>true</code> on success
     */
    boolean save(IProject project, ClusterStore storage);

    /**
     * Tells whether a project has a clusters file.
     *
     * @param project the project
     * @return <code>true</code> if the file exists
     */
    boolean exists(IProject project);

    /**
     * Deletes a project's clusters file.
     *
     * @param project the project
     * @return <code>true</code> if the file is gone afterwards - whether it was deleted or was never
     *         there
     */
    boolean delete(IProject project);
}
