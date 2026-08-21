/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Guards the filter that decides which changed objects a page names.
 * <p>
 * <b>What is at stake.</b> Protecting a customisation through an update means naming every object
 * that carries one. The listing used to stop at five hundred of tens of thousands, chosen by walk
 * order, so this filter is what turns the comparison from a report into something a decision can be
 * built on. A filter that quietly drops an object drops a customisation with it.
 * </p>
 */
public class ComparisonPageTest
{
    private static BmComparisonHelper.Change change(String main, String other, String ancestor,
        String changedBy, boolean oneSided, boolean mustBeMerged)
    {
        BmComparisonHelper.Change made = new BmComparisonHelper.Change();
        made.main = main;
        made.other = other;
        made.ancestor = ancestor;
        made.changedBy = changedBy;
        made.oneSided = oneSided;
        made.mustBeMerged = mustBeMerged;
        return made;
    }

    private static BmComparisonHelper.Change ours(String name)
    {
        return change(name, name, name, "OURS", false, true);
    }

    @Test
    public void anEmptyPageWantsEverything()
    {
        BmComparisonHelper.Page page = new BmComparisonHelper.Page();
        assertTrue(page.wants(ours("Catalog.Товары")));
        assertTrue(page.wants(change(null, "Document.Заказ", null, "VENDOR", true, false)));
    }

    @Test
    public void attributionFiltersAndIsCaseInsensitive()
    {
        BmComparisonHelper.Page page = new BmComparisonHelper.Page();
        page.changedBy = "ours";
        assertTrue("OURS is the filter a customisation-preserving update runs on; a case "
            + "difference must not silently drop every object", page.wants(ours("Catalog.Товары")));
        assertFalse(page.wants(change("X", "X", "X", "VENDOR", false, false)));
        assertFalse(page.wants(change("X", "X", "X", "BOTH", false, false)));
    }

    @Test
    public void typeIsReadFromTheQualifiedName()
    {
        BmComparisonHelper.Page page = new BmComparisonHelper.Page();
        page.type = "Catalog";
        assertTrue(page.wants(ours("Catalog.Товары")));
        assertFalse(page.wants(ours("Document.Заказ")));
        assertFalse("a bare name has no type and must not pass a type filter",
            page.wants(ours("Товары")));
    }

    @Test
    public void typeMatchesOnAnySide()
    {
        // An object the vendor added has no name on our side, and one we deleted has none on
        // theirs. Reading the type from a single side would drop exactly the objects an update is
        // most likely to break.
        BmComparisonHelper.Page page = new BmComparisonHelper.Page();
        page.type = "Document";
        assertTrue("an object added by the vendor is named only on their side",
            page.wants(change(null, "Document.Заказ", null, "VENDOR", true, false)));
        assertTrue("an object deleted by the vendor is named only on ours",
            page.wants(change("Document.Заказ", null, "Document.Заказ", "BOTH", true, false)));
    }

    @Test
    public void oneSidedFilterDistinguishesBothDirections()
    {
        BmComparisonHelper.Page page = new BmComparisonHelper.Page();
        page.oneSided = Boolean.TRUE;
        assertTrue(page.wants(change("X", null, "X", "OURS", true, false)));
        assertFalse(page.wants(ours("Catalog.Товары")));

        page.oneSided = Boolean.FALSE;
        assertFalse(page.wants(change("X", null, "X", "OURS", true, false)));
        assertTrue(page.wants(ours("Catalog.Товары")));
    }

    @Test
    public void mustBeMergedNarrowsOnlyWhenAsked()
    {
        BmComparisonHelper.Page page = new BmComparisonHelper.Page();
        assertTrue("off by default, so an ordinary listing is not silently narrowed",
            page.wants(change("X", "X", "X", "OURS", false, false)));

        page.mustBeMergedOnly = true;
        assertFalse(page.wants(change("X", "X", "X", "OURS", false, false)));
        assertTrue(page.wants(ours("Catalog.Товары")));
    }

    @Test
    public void filtersCombineAsAnd()
    {
        BmComparisonHelper.Page page = new BmComparisonHelper.Page();
        page.changedBy = "OURS";
        page.type = "Catalog";
        assertTrue(page.wants(ours("Catalog.Товары")));
        assertFalse("right attribution, wrong type", page.wants(ours("Document.Заказ")));
        assertFalse("right type, wrong attribution",
            page.wants(change("Catalog.Товары", "Catalog.Товары", "Catalog.Товары", "VENDOR",
                false, false)));
    }

    @Test
    public void anUnknownAttributionIsFilterableToo()
    {
        // Two-sided comparisons attribute nothing, and being able to list exactly those objects is
        // how a caller sees that the ancestor was missing rather than that nothing was ours.
        BmComparisonHelper.Page page = new BmComparisonHelper.Page();
        page.changedBy = "UNKNOWN";
        assertTrue(page.wants(change("X", "X", null, "UNKNOWN", false, false)));
        assertFalse(page.wants(ours("Catalog.Товары")));
    }
}
