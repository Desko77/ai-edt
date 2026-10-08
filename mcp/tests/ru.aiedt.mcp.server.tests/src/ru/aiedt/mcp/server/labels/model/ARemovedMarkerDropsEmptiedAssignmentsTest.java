/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * A marker deleted from the definitions leaves no empty assignment entry behind.
 * <p>
 * The assignments map is what the "show unmarked only" filter reads as the marked set: an object
 * whose entry survived the deletion with an empty list would stay hidden although it carries no
 * marker at all.
 * </p>
 */
public class ARemovedMarkerDropsEmptiedAssignmentsTest
{
    /** The object's entry goes when the deleted marker was its last one. */
    @Test
    public void theEntryOfAnObjectLeftWithoutMarkersGoes()
    {
        MarkerStore store = new MarkerStore();
        store.addMarker(new Marker("bug"));
        store.addMarker(new Marker("hot"));
        store.assignMarker("Catalog.Products", "bug");
        store.assignMarker("Catalog.Products", "hot");

        assertTrue(store.removeMarker("bug"));

        Map<String, List<String>> assignments = store.getAssignments();
        assertTrue("the object still carries a marker, so its entry stays", //$NON-NLS-1$
            assignments.containsKey("Catalog.Products")); //$NON-NLS-1$
        assertTrue(assignments.get("Catalog.Products").contains("hot")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(store.removeMarker("hot"));

        assertFalse("an object with no markers left is not marked", //$NON-NLS-1$
            assignments.containsKey("Catalog.Products")); //$NON-NLS-1$
    }

    /** An object that never carried the deleted marker keeps its entry untouched. */
    @Test
    public void otherObjectsKeepTheirEntries()
    {
        MarkerStore store = new MarkerStore();
        store.addMarker(new Marker("bug"));
        store.addMarker(new Marker("hot"));
        store.assignMarker("Catalog.Products", "bug");
        store.assignMarker("Catalog.Clients", "hot");

        assertTrue(store.removeMarker("bug"));

        assertTrue(store.getAssignments().containsKey("Catalog.Clients")); //$NON-NLS-1$
        assertFalse(store.getAssignments().containsKey("Catalog.Products")); //$NON-NLS-1$
    }

    /** The unmarked-only view counts an emptied entry as unmarked even if one survives somewhere. */
    @Test
    public void anEmptyListHidesNothing()
    {
        MarkerStore store = new MarkerStore();
        store.addMarker(new Marker("bug"));
        store.assignMarker("Catalog.Products", "bug");
        List<String> emptied = new ArrayList<>();
        store.getAssignments().put("Catalog.Ghost", emptied);

        store.removeMarker("bug");

        // The defense the filter itself applies: whatever wrote an empty list, an object without
        // markers is not hidden by it.
        boolean anyEmpty = false;
        for (List<String> names : store.getAssignments().values())
        {
            anyEmpty |= names.isEmpty();
        }
        assertFalse("no empty entry survives a removal", anyEmpty); //$NON-NLS-1$
    }
}
