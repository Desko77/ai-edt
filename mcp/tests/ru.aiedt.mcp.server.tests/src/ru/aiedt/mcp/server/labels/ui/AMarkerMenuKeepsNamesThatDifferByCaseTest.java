/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/**
 * Marker names that differ only by case stay separate entries, and a cached match list is dropped
 * one project at a time.
 */
public class AMarkerMenuKeepsNamesThatDifferByCaseTest
{
    @Test
    public void caseIsPartOfTheName()
    {
        Map<String, String> names = MarkerMenuIndex.byName();
        names.put("Bug", "Bug"); //$NON-NLS-1$ //$NON-NLS-2$
        names.put("bug", "bug"); //$NON-NLS-1$ //$NON-NLS-2$
        names.put("Важно", "Важно"); //$NON-NLS-1$ //$NON-NLS-2$
        names.put("важно", "важно"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(4, names.size());
        assertTrue(names.containsKey("Bug")); //$NON-NLS-1$
        assertTrue(names.containsKey("bug")); //$NON-NLS-1$
        assertTrue(names.containsKey("Важно")); //$NON-NLS-1$
        assertTrue(names.containsKey("важно")); //$NON-NLS-1$
    }

    @Test
    public void oneProjectDropsWithoutClearingTheOther()
    {
        MarkerMatchCache<String> cache = new MarkerMatchCache<>();
        cache.put("Alpha", new HashSet<>(Set.of("Catalog.A"))); //$NON-NLS-1$ //$NON-NLS-2$
        cache.put("Beta", new HashSet<>(Set.of("Catalog.B"))); //$NON-NLS-1$ //$NON-NLS-2$

        cache.invalidate("Alpha"); //$NON-NLS-1$

        assertFalse(cache.contains("Alpha")); //$NON-NLS-1$
        assertTrue(cache.contains("Beta")); //$NON-NLS-1$
    }
}
