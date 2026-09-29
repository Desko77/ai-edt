/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.ui;

import java.util.Map;
import java.util.TreeMap;

/**
 * Indexes marker names for the context menu.
 * <p>
 * A case-insensitive map treats {@code Bug} and {@code bug} as one key and keeps whichever was inserted
 * first. They are different markers. The map this builds compares names as written, so both stay.
 * </p>
 */
public final class MarkerMenuIndex
{
    private MarkerMenuIndex()
    {
        // Static helpers.
    }

    /**
     * Returns an empty name map that does not fold case.
     *
     * @param <T> the value stored under each name
     * @return a new case-sensitive map, ordered by the name's natural order
     */
    public static <T> Map<String, T> byName()
    {
        return new TreeMap<>();
    }
}
