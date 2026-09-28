/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.Set;

import org.junit.Test;

import ru.aiedt.mcp.server.support.FileMarkers;

/**
 * Covers the {@code objects} filter and the session scope of {@code get_project_errors}.
 * <p>
 * Both compare a marker's object presentation against a set of FQNs, and both did it with a
 * plain substring match: asking about {@code Catalog.Products} also kept the markers of
 * {@code Catalog.ProductsExtra}, and {@code scope=session} kept a sibling whose name only
 * begins like the file the session touched. Measured on a stand:
 * {@code objects=["Справочник.Номенклатура"]} returned the markers of
 * {@code Справочник.НоменклатураПоставщиков}. These tests pin the match to whole segments -
 * the object itself and everything under it passes, a longer sibling does not.
 * </p>
 */
public class ProblemObjectFilterTest
{
    @Test
    public void theObjectItselfMatches()
    {
        assertTrue(filterOn("catalog.products") //$NON-NLS-1$
            .matchesEclipse(null, null, "Catalog.Products")); //$NON-NLS-1$
    }

    @Test
    public void aChildOfTheObjectMatches()
    {
        assertTrue("a marker on something under the object is a marker on the object", //$NON-NLS-1$
            filterOn("catalog.products") //$NON-NLS-1$
                .matchesEclipse(null, null, "Catalog.Products.Form.ItemForm")); //$NON-NLS-1$
    }

    @Test
    public void aLongerSiblingDoesNotMatch()
    {
        // The stand case: one segment shares a prefix with the request, and a substring
        // match read that as the same object.
        assertFalse(filterOn("catalog.products") //$NON-NLS-1$
            .matchesEclipse(null, null, "Catalog.ProductsExtra")); //$NON-NLS-1$
    }

    @Test
    public void aLongerSiblingDoesNotMatchInRussianEither()
    {
        assertFalse(filterOn("справочник.номенклатура") //$NON-NLS-1$
            .matchesEclipse(null, null, "Справочник.НоменклатураПоставщиков")); //$NON-NLS-1$
        assertTrue(filterOn("справочник.номенклатура") //$NON-NLS-1$
            .matchesEclipse(null, null, "Справочник.Номенклатура.Форма.ФормаЭлемента")); //$NON-NLS-1$
    }

    @Test
    public void aMatchStartingMidSegmentDoesNotCount()
    {
        assertFalse("the FQN has to start at the beginning or right after a dot", //$NON-NLS-1$
            filterOn("catalog.products") //$NON-NLS-1$
                .matchesEclipse(null, null, "OldCatalog.Products")); //$NON-NLS-1$
    }

    @Test
    public void theSessionScopeUsesTheSameBoundaries()
    {
        ProjectProblemsReader.Filter filter = new ProjectProblemsReader.Filter(null, null, null,
            Collections.emptySet(), Set.of("catalog.products"), true, null); //$NON-NLS-1$

        assertTrue(filter.matchesEclipse(null, null, "Catalog.Products.ObjectModule")); //$NON-NLS-1$
        assertFalse(filter.matchesEclipse(null, null, "Catalog.ProductsExtra")); //$NON-NLS-1$
    }

    @Test
    public void theSharedMatcherAnswersTheSameWay()
    {
        // FileMarkers.getMarkersByObjectPresentation is the second path that kept a sibling
        // by substring; it matches through the same helper.
        assertTrue(FileMarkers.matchesAtSegmentBoundary("catalog.products", "catalog.products")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(FileMarkers.matchesAtSegmentBoundary("catalog.products.form.itemform", //$NON-NLS-1$
            "catalog.products")); //$NON-NLS-1$
        assertFalse(FileMarkers.matchesAtSegmentBoundary("catalog.productsextra", //$NON-NLS-1$
            "catalog.products")); //$NON-NLS-1$
        assertFalse(FileMarkers.matchesAtSegmentBoundary("справочник.номенклатурапоставщиков", //$NON-NLS-1$
            "справочник.номенклатура")); //$NON-NLS-1$
        assertFalse(FileMarkers.matchesAtSegmentBoundary(null, "catalog.products")); //$NON-NLS-1$
        assertFalse(FileMarkers.matchesAtSegmentBoundary("catalog.products", "")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static ProjectProblemsReader.Filter filterOn(String fqn)
    {
        return new ProjectProblemsReader.Filter(null, null, null, Set.of(fqn),
            Collections.emptySet(), false, null);
    }
}
