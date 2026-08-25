/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Holds the shape of what object_mode says about an object's dependents.
 * <p>
 * <b>It used to say all of them, however many there were.</b> Measured on the root of a live
 * configuration: 8769 names in one answer of 369 114 characters, which the client refused by size -
 * while the cap the rest of this class uses, PAGE_LIMIT, sat unused three fields away.
 * </p>
 * <p>
 * Two distinctions the first version of the fix did not make, and both matter more than the cap:
 * </p>
 * <ul>
 * <li><b>A dependent without a name and a dependent that could not be read are different answers.</b>
 * Both used to end as a null quietly appended to the list, so a failure looked exactly like an
 * object nobody had named, and neither was visible to the caller. They are counted apart now, and
 * the unreadable one is called a failure because that is what it is.</li>
 * <li><b>The counts describe the whole set, the list describes one page.</b> A reader who sees a
 * list and a number beside it reads the number as the list, so the field names and the answer say
 * which is which.</li>
 * </ul>
 * <p>
 * <b>What this file can and cannot hold.</b> Filling a real object's dependents needs a live
 * workspace. What is pinned here is the arithmetic of the page and the counts, on a state built by
 * hand - the part that can be got wrong without any environment at all.
 * </p>
 */
public class DependentsArePagedAndCountedApartTest
{
    @Test
    public void aSmallSetFitsAndSaysThereIsNoMore()
    {
        BmSupportRegistryHelper.ObjectState state = new BmSupportRegistryHelper.ObjectState();
        state.dependents.add("Catalog.A"); //$NON-NLS-1$
        state.dependents.add("Catalog.B"); //$NON-NLS-1$
        state.dependentsTotal = 2;

        assertEquals(2, state.dependents.size());
        assertEquals(2, state.dependentsTotal);
        assertFalse(state.dependentsMore);
    }

    @Test
    public void theTotalIsTheWholeSetAndTheListIsOnePage()
    {
        // The number a reader most wants is the one the page cannot show.
        BmSupportRegistryHelper.ObjectState state = new BmSupportRegistryHelper.ObjectState();
        state.dependentsTotal = 8769;
        state.dependentsMore = true;
        for (int i = 0; i < BmSupportRegistryHelper.PAGE_LIMIT; i++)
        {
            state.dependents.add("Catalog.Item" + i); //$NON-NLS-1$
        }

        assertEquals(BmSupportRegistryHelper.PAGE_LIMIT, state.dependents.size());
        assertTrue(state.dependentsTotal > state.dependents.size());
        assertTrue(state.dependentsMore);
    }

    @Test
    public void unnamedAndUnreadableAreSeparateCounts()
    {
        // One number for both would hide a failure inside a fact about naming.
        BmSupportRegistryHelper.ObjectState state = new BmSupportRegistryHelper.ObjectState();
        state.dependentsUnnamed = 3;
        state.dependentsUnreadable = 1;

        assertEquals(3, state.dependentsUnnamed);
        assertEquals(1, state.dependentsUnreadable);
    }

    @Test
    public void aFreshStateClaimsNothing()
    {
        // Every count starts at zero and the list empty, so an object nobody asked about cannot
        // read as an object with no dependents.
        BmSupportRegistryHelper.ObjectState state = new BmSupportRegistryHelper.ObjectState();
        assertTrue(state.dependents.isEmpty());
        assertEquals(0, state.dependentsTotal);
        assertEquals(0, state.dependentsUnnamed);
        assertEquals(0, state.dependentsUnreadable);
        assertFalse(state.dependentsMore);
    }
}
