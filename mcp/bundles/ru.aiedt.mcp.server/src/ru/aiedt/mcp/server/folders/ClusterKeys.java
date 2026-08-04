/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders;

/**
 * The fixed names of the file that holds a project's clusters and the folder it lives in.
 */
public final class ClusterKeys
{
    /** The project-relative folder the settings file lives in. */
    public static final String SETTINGS_FOLDER = ".settings"; //$NON-NLS-1$

    /** The name of the file the clusters are stored in. */
    public static final String CLUSTERS_FILE = "aiedt-clusters.yaml"; //$NON-NLS-1$

    /**
     * The name earlier builds wrote clusters under. A project still holding one has it carried
     * over on first access, after which the old file is renamed aside.
     */
    public static final String LEGACY_CLUSTERS_FILE = "groups.yaml"; //$NON-NLS-1$

    /** The project-relative path of the clusters file: {@value #SETTINGS_FOLDER}/{@value #CLUSTERS_FILE}. */
    public static final String CLUSTERS_PATH = SETTINGS_FOLDER + "/" + CLUSTERS_FILE; //$NON-NLS-1$

    private ClusterKeys()
    {
        // constants
    }
}
