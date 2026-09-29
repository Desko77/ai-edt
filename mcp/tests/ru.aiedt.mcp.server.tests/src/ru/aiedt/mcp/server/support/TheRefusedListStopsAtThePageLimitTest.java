/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The list of refused objects stops at one page.
 * <p>
 * Every branch that names a refused object answers through the same bounded route. One branch used
 * to check the page limit and the others did not, so a configuration where the write refused
 * wholesale - a delivery the whole of which refuses - built an answer the size the object listing
 * is paged to avoid.
 * </p>
 */
public class TheRefusedListStopsAtThePageLimitTest
{
    /**
     * Refusals past the page limit are dropped from the list, not accumulated without end, and
     * every one of them is counted.
     * <p>
     * Both halves are needed. A list that stops at the page limit without a total reads as a
     * complete account of the refusals it carries, and the objects past it cannot be found from the
     * answer at all.
     * </p>
     */
    @Test
    public void refusalsStopAtThePageLimit()
    {
        BmSupportRegistryHelper.Restore restore = new BmSupportRegistryHelper.Restore();
        int refusals = BmSupportRegistryHelper.PAGE_LIMIT + 250;
        for (int i = 0; i < refusals; i++)
        {
            BmSupportRegistryHelper.refuse(restore, "object " + i); //$NON-NLS-1$
        }
        assertEquals(BmSupportRegistryHelper.PAGE_LIMIT, restore.refused.size());
        assertEquals("the count is the whole of what was refused, not the length of the page",
            refusals, restore.refusedCount);
    }
}
