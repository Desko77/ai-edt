/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Deleting an object takes the assignments of everything nested under it, the way the delete
 * refactoring hook asks for them.
 * <p>
 * An attribute or a form goes with the object that holds it; an assignment left behind under the
 * old name attaches itself to the next object that reuses the name.
 * </p>
 */
public class ADeletedObjectTakesItsNestedAssignmentsTest
{
    /** The nested assignments go with the object's own. */
    @Test
    public void nestedAssignmentsGoWithTheObject()
    {
        MarkerStore store = new MarkerStore();
        store.addMarker(new Marker("bug"));
        store.assignMarker("Catalog.Products", "bug");
        store.assignMarker("Catalog.Products.Attribute.Code", "bug");
        store.assignMarker("Catalog.Products.Form.ItemForm", "bug");
        store.assignMarker("Catalog.ProductsExtra", "bug");

        assertTrue(store.removeObject("Catalog.Products"));

        assertFalse(store.getAssignments().containsKey("Catalog.Products"));
        assertFalse(store.getAssignments().containsKey("Catalog.Products.Attribute.Code"));
        assertFalse(store.getAssignments().containsKey("Catalog.Products.Form.ItemForm"));
        // A sibling that merely shares a prefix keeps what it carries.
        assertTrue(store.getAssignments().containsKey("Catalog.ProductsExtra"));
    }

    /** An object with nothing nested still loses its own entry. */
    @Test
    public void anObjectWithoutNestedOnesLosesItsOwnEntry()
    {
        MarkerStore store = new MarkerStore();
        store.addMarker(new Marker("bug"));
        store.assignMarker("Document.Sale", "bug");

        assertTrue(store.removeObject("Document.Sale"));
        assertFalse(store.getAssignments().containsKey("Document.Sale"));
    }
}
