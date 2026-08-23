/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Holds the request object that replaced eleven positional parameters.
 * <p>
 * <b>Six of the eleven were strings and two were booleans.</b> Any two of a kind swapped at the
 * call site compiled without a word and changed what the comparison did - a comparison against the
 * ancestor instead of the delivery, a session closed instead of kept. Nothing in the type system
 * was ever going to catch that, which is why the parameters are named now and why the check below
 * is about names rather than about compiling.
 * </p>
 * <p>
 * The normalising lives here too, and once. Spread through the comparison it was possible for a
 * path to read a limit nobody had bounded; a page arriving with no limit at all has to come out
 * with the page limit, and a negative offset has to come out at zero, whatever asked for it.
 * </p>
 */
public class ComparisonRequestTest
{
    @Test
    public void aRequestWithNoPageStillComesOutWithABoundedOne()
    {
        BmComparisonHelper.Request request = new BmComparisonHelper.Request();
        request.normalise();

        assertNotNull("a missing page is the ordinary case, not a refusal", request.page); //$NON-NLS-1$
        assertEquals(BmComparisonHelper.PAGE_LIMIT, request.page.limit);
        assertEquals(0, request.page.offset);
    }

    @Test
    public void aLimitPastThePageLimitIsBroughtBackToIt()
    {
        BmComparisonHelper.Request request = new BmComparisonHelper.Request();
        request.page = new BmComparisonHelper.Page();
        request.page.limit = BmComparisonHelper.PAGE_LIMIT * 10;
        request.normalise();

        assertEquals("asking for more than a page may not produce more than a page", //$NON-NLS-1$
            BmComparisonHelper.PAGE_LIMIT, request.page.limit);
    }

    @Test
    public void aNegativeOffsetBecomesTheBeginning()
    {
        BmComparisonHelper.Request request = new BmComparisonHelper.Request();
        request.page = new BmComparisonHelper.Page();
        request.page.offset = -40;
        request.normalise();

        assertEquals(0, request.page.offset);
    }

    @Test
    public void normalisingTwiceChangesNothingTheSecondTime()
    {
        // The comparison normalises on entry, and a caller may have done it already. The second
        // pass has to be a no-op, or a page that arrived at the limit would be treated as a page
        // that asked for nothing.
        BmComparisonHelper.Request request = new BmComparisonHelper.Request();
        request.page = new BmComparisonHelper.Page();
        request.page.limit = 25;
        request.page.offset = 100;
        request.normalise();
        BmComparisonHelper.Page after = request.page;
        request.normalise();

        assertSame(after, request.page);
        assertEquals(25, request.page.limit);
        assertEquals(100, request.page.offset);
    }

    @Test
    public void everyFieldTheComparisonNeedsIsCarriedByName()
    {
        // The point of the move, held as a list. A field dropped in a later edit would silently
        // become the default at every call site - null for a path, false for a flag - and the
        // comparison would go on working while doing something else. Reading it back by name is
        // the only check that notices.
        List<String> expected = new ArrayList<>(List.of("mainProjectName", "otherPath", //$NON-NLS-1$ //$NON-NLS-2$
            "ancestorPath", "decisions", "decisionsPath", "decisionsFrom", "intent", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "ignoreOriginMismatch", "page", "closeSession", "scopeNames")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        List<String> present = new ArrayList<>();
        for (Field field : BmComparisonHelper.Request.class.getDeclaredFields())
        {
            if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers()))
            {
                present.add(field.getName());
            }
        }

        List<String> missing = new ArrayList<>(expected);
        missing.removeAll(present);
        assertTrue("the comparison would silently take the default for: " + missing, //$NON-NLS-1$
            missing.isEmpty());
    }

    @Test
    public void whatIsPutInIsWhatIsRead()
    {
        // Distinct values in every field of the same type, so a swap between two of them shows up
        // as a mismatch rather than as an equal pair. This is the shape of the failure the move
        // exists to prevent.
        BmComparisonHelper.Request request = new BmComparisonHelper.Request();
        request.mainProjectName = "ours"; //$NON-NLS-1$
        request.otherPath = "delivery"; //$NON-NLS-1$
        request.ancestorPath = "ancestor"; //$NON-NLS-1$
        request.decisionsPath = "settings-out"; //$NON-NLS-1$
        request.decisionsFrom = "settings-in"; //$NON-NLS-1$
        request.ignoreOriginMismatch = true;
        request.closeSession = false;
        request.scopeNames = List.of("Catalog.One"); //$NON-NLS-1$
        request.normalise();

        assertEquals("ours", request.mainProjectName); //$NON-NLS-1$
        assertEquals("delivery", request.otherPath); //$NON-NLS-1$
        assertEquals("ancestor", request.ancestorPath); //$NON-NLS-1$
        assertEquals("settings-out", request.decisionsPath); //$NON-NLS-1$
        assertEquals("settings-in", request.decisionsFrom); //$NON-NLS-1$
        assertTrue(request.ignoreOriginMismatch);
        assertEquals(false, request.closeSession);
        assertEquals(1, request.scopeNames.size());
    }
}
