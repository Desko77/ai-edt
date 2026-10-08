/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Iterator;

import org.junit.Test;

/**
 * The markers of an object read in the order the user put them on, the order the marker file
 * stores. The first marker of the list is the first of the answer, so a decoration that shows the
 * leading marker - and a hotkey picker that reads the first - answer the same marker the user
 * meant.
 */
public class TheMarkersOfAnObjectFollowTheUsersOrderTest
{
    /** Names whose hash order differs from the assignment order, so a hash set cannot pass. */
    @Test
    public void theOrderFollowsTheAssignmentList()
    {
        MarkerStore store = new MarkerStore();
        store.addMarker(new Marker("Zulu"));
        store.addMarker(new Marker("Ab"));
        // "Zulu" was put on the object first; a HashSet would answer Alpha first whatever the
        // user did, because that is where the hashes land.
        store.assignMarker("Catalog.Products", "Zulu");
        store.assignMarker("Catalog.Products", "Ab");

        Iterator<Marker> markers = store.getObjectMarkers("Catalog.Products").iterator();

        assertEquals("Zulu", markers.next().getName());
        assertEquals("Ab", markers.next().getName());
        assertFalse(markers.hasNext());
    }
}
