/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * A restore after a failed write puts the markers back on the instances the holders already have.
 * <p>
 * A dialog or a tree that picked up a marker keeps the instance; a restore that replaced the list
 * with fresh copies left that instance carrying the values of the edit that was rolled back, so
 * the UI showed a rename or a color that never reached the file.
 * </p>
 */
public class ARestoredMarkerKeepsItsOwnInstanceTest
{
    /** The held instance sees its fields return to the restored values. */
    @Test
    public void theHeldInstanceReadsTheRestoredValues()
    {
        MarkerStore store = new MarkerStore();
        store.addMarker(new Marker("Old", "#111111", "kept"));
        Marker held = store.getTags().get(0);
        MarkerStore before = store.copy();

        // The mutation a rename makes: the same instance, new values.
        held.setName("New");
        held.setColor("#222222");
        held.setDescription("edited");
        store.getAssignments().put("Catalog.Products", new ArrayList<>(List.of("New")));

        store.restoreFrom(before);

        assertEquals("Old", held.getName());
        assertEquals("#111111", held.getColor());
        assertEquals("kept", held.getDescription());
        assertNotNull(store.getMarkerByName("Old"));
    }

    /** An entry the failed edit added is gone, and one it removed is back. */
    @Test
    public void addedAndRemovedEntriesComeBack()
    {
        MarkerStore store = new MarkerStore();
        store.addMarker(new Marker("kept"));
        MarkerStore before = store.copy();
        store.addMarker(new Marker("added"));
        store.removeMarker("kept");

        store.restoreFrom(before);

        assertEquals(1, store.getTags().size());
        assertNotNull(store.getMarkerByName("kept"));
    }
}
