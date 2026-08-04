/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders;

import org.eclipse.core.resources.IProject;

/**
 * Told when a project's clusters have changed, so that whoever is showing them can refresh.
 */
public interface IClusterChangeObserver
{
    /**
     * Called after a project's clusters have changed - whether by an edit through the service or by the
     * file being rewritten from outside.
     *
     * @param project the project whose clusters changed
     */
    void onClustersChanged(IProject project);
}
