/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels;

/**
 * Fixed strings and sizes the marker subsystem shares.
 * <p>
 * The file name and folder locate the per-project marker file; the navigator id names the EDT tree the
 * decorator, filter and toolbar plug into; the URI scheme is how a metadata object's project is read
 * back out of its BM resource URI.
 * </p>
 */
public final class MarkerKeys
{
    /** The project folder the marker file lives in, alongside the other Eclipse settings. */
    public static final String SETTINGS_FOLDER = ".settings"; //$NON-NLS-1$

    /** The name of the per-project marker file. */
    public static final String MARKERS_FILE = "aiedt-markers.yaml"; //$NON-NLS-1$

    /**
     * The name earlier builds wrote markers under. A project still holding one has it carried
     * over on first access, after which the old file is renamed aside.
     */
    public static final String LEGACY_MARKERS_FILE = "metadata-tags.yaml"; //$NON-NLS-1$

    /** The scheme a BM resource URI starts with; its authority is the project name. */
    public static final String BM_URI_SCHEME = "bm://"; //$NON-NLS-1$

    /** The id of the EDT metadata Navigator this subsystem decorates and filters. */
    public static final String NAVIGATOR_VIEW_ID = "com._1c.g5.v8.dt.ui2.navigator"; //$NON-NLS-1$

    /** The color a marker falls back to when none is set - a neutral mid-gray. */
    public static final String DEFAULT_TAG_COLOR = "#808080"; //$NON-NLS-1$

    /** The usual edge length, in pixels, of a color swatch icon. */
    public static final int COLOR_ICON_SIZE_NORMAL = 16;

    private MarkerKeys()
    {
        // Constants only.
    }
}
