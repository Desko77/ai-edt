/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support.naparnik;

/**
 * One installed copy of a 1C:Naparnik bundle, as the host reported it.
 * <p>
 * {@code identity} is the host's own handle for that copy. Two copies are the same bundle only when
 * they carry the same handle, so a second resolved install of a singleton name stays a second copy
 * even when the version text matches.
 * </p>
 */
public final class BundleCopy
{
    private final String name;

    private final String version;

    private final String state;

    private final Object identity;

    /**
     * @param name symbolic name
     * @param version OSGi version text, qualifier included when the host has one
     * @param state OSGi state name: {@code INSTALLED}, {@code RESOLVED}, {@code STARTING},
     *            {@code STOPPING} or {@code ACTIVE}
     * @param identity the host's handle for this copy; not {@code null}
     */
    public BundleCopy(String name, String version, String state, Object identity)
    {
        this.name = name;
        this.version = version;
        this.state = state;
        this.identity = identity;
    }

    /**
     * @return the symbolic name
     */
    public String name()
    {
        return name;
    }

    /**
     * @return the version text
     */
    public String version()
    {
        return version;
    }

    /**
     * @return the state name
     */
    public String state()
    {
        return state;
    }

    /**
     * @return the host's handle
     */
    public Object identity()
    {
        return identity;
    }

    /**
     * A chosen copy is resolved or further along. {@code INSTALLED} is present and not chosen.
     *
     * @return whether this copy may be the one the bridge calls
     */
    public boolean chosen()
    {
        return "RESOLVED".equals(state) || "STARTING".equals(state) //$NON-NLS-1$ //$NON-NLS-2$
            || "STOPPING".equals(state) || "ACTIVE".equals(state); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * @param other another copy; may be {@code null}
     * @return whether both handles are the same bundle
     */
    public boolean sameBundle(BundleCopy other)
    {
        return other != null && identity != null && identity == other.identity;
    }
}
