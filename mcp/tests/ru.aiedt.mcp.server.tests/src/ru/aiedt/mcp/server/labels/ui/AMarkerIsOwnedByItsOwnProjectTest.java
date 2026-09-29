/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.labels.model.Marker;

/**
 * The same marker name in two projects belongs to the project that holds that instance.
 */
public class AMarkerIsOwnedByItsOwnProjectTest
{
    @Test
    public void theSecondProjectKeepsItsOwnMarker()
    {
        Marker alphaBug = new Marker("bug"); //$NON-NLS-1$
        Marker betaBug = new Marker("bug"); //$NON-NLS-1$
        Map<String, List<Marker>> markers = Map.of(
            "Alpha", List.of(alphaBug), //$NON-NLS-1$
            "Beta", List.of(betaBug)); //$NON-NLS-1$

        assertEquals("Beta", //$NON-NLS-1$
            MarkerOwnership.projectOf(List.of("Alpha", "Beta"), betaBug, markers::get)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Alpha", //$NON-NLS-1$
            MarkerOwnership.projectOf(List.of("Alpha", "Beta"), alphaBug, markers::get)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aMarkerNobodyHoldsHasNoProject()
    {
        Marker stray = new Marker("bug"); //$NON-NLS-1$
        Map<String, List<Marker>> markers = Map.of("Alpha", List.of(new Marker("bug"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(MarkerOwnership.projectOf(List.of("Alpha"), stray, markers::get)); //$NON-NLS-1$
    }
}
