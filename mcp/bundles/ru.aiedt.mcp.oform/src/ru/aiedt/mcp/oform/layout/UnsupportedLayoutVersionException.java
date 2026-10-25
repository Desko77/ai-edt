/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.layout;

/**
 * Thrown before any edit when a layout's version is not one the writers were measured on.
 * <p>
 * The version is the first token of the layout text - the version of the platform that last
 * saved the form. The writers were measured on 25, 26 and 27; a form of another version is read
 * as before and refused for writing by name, rather than written by a shape that was never
 * checked against the platform.
 * </p>
 */
public final class UnsupportedLayoutVersionException
    extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    private final String version;

    /**
     * @param version the layout's version token, or {@code null} when the text has none
     */
    public UnsupportedLayoutVersionException(String version)
    {
        super(version == null
            ? "The layout has no version token; nothing is written into a layout that cannot be read." //$NON-NLS-1$
            : "The layout is of version " + version + "; writing was measured on versions " //$NON-NLS-1$ //$NON-NLS-2$
                + String.join(", ", new java.util.TreeSet<>(LayoutEditor.SUPPORTED_VERSIONS)) //$NON-NLS-1$
                + " only, and this form is read but not written."); //$NON-NLS-1$
        this.version = version;
    }

    /** @return the version token, or {@code null} */
    public String version()
    {
        return version;
    }
}
