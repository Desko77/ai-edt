/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/**
 * Which extInfo a group type asks for.
 * <p>
 * A container of pages and a single page are different model types - {@code PagesGroupExtInfo} and
 * {@code PageGroupExtInfo} - and the branch choosing between them read
 * {@code lower.contains("pages") && !lower.contains("page")}. The string "pages" contains "page", so
 * that condition is false for the one input it exists to catch: every Pages group took the
 * single-page branch. EDT reported MAJOR "Illegal extension type for group type 'Pages'", and in the
 * second reported case the platform would not open the form at all.
 * </p>
 * <p>
 * Substring containment cannot separate these two names; only order can. This asks
 * {@link BmFormHelper#groupExtInfoFactory} itself rather than restating the branch, so a rewrite
 * cannot answer the test correctly while answering the caller wrongly.
 * </p>
 */
public class PagesIsNotAPageTest
{
    @Test
    public void aContainerOfPagesGetsTheContainerExtInfo()
    {
        assertEquals("createPagesGroupExtInfo", BmFormHelper.groupExtInfoFactory("Pages")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("createPagesGroupExtInfo", BmFormHelper.groupExtInfoFactory("pages")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("createPagesGroupExtInfo", BmFormHelper.groupExtInfoFactory("PAGES")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aSinglePageGetsThePageExtInfo()
    {
        assertEquals("createPageGroupExtInfo", BmFormHelper.groupExtInfoFactory("Page")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("createPageGroupExtInfo", BmFormHelper.groupExtInfoFactory("page")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theGuardThatShippedCouldNeverFire()
    {
        // The exact expression that was here. Kept so the reason stays legible: the branch was not
        // ordered wrongly, its guard was unsatisfiable.
        String lower = "pages"; //$NON-NLS-1$
        assertFalse("\"pages\" contains \"page\", so this was always false", //$NON-NLS-1$
            lower.contains("pages") && !lower.contains("page")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theOtherGroupTypesStillResolve()
    {
        assertEquals("createColumnGroupExtInfo", BmFormHelper.groupExtInfoFactory("ColumnGroup")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("createCommandBarExtInfo", BmFormHelper.groupExtInfoFactory("CommandBar")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("createButtonGroupExtInfo", BmFormHelper.groupExtInfoFactory("ButtonGroup")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("createPopupGroupExtInfo", BmFormHelper.groupExtInfoFactory("Popup")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anythingElseFallsBackToTheUsualGroup()
    {
        assertEquals("createUsualGroupExtInfo", BmFormHelper.groupExtInfoFactory("UsualGroup")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("createUsualGroupExtInfo", BmFormHelper.groupExtInfoFactory("")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("createUsualGroupExtInfo", BmFormHelper.groupExtInfoFactory(null)); //$NON-NLS-1$
    }
}
