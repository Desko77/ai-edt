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
 * Holds apart the two reasons an entry of the support registry has no name.
 * <p>
 * They used to be one number, and it said the wrong thing. Measured on a real configuration: 7239
 * entries of 10 843 came back counted as objects the configuration no longer had, on a
 * configuration that had every one of them - the walk simply stopped at the top level and never
 * looked at attributes, forms or templates.
 * </p>
 * <p>
 * <b>An absent name means a deleted object only while the walk was whole.</b> That is the rule this
 * holds. The two readings lead somewhere different: one is a fact about the configuration, which a
 * person acts on, and the other is a fault in the reading of it, which a person investigates. A
 * single counter forces them to act on both the same way, and the safe direction is not obvious -
 * so the counter has to stop pretending it knows.
 * </p>
 */
public class UnnamedEntriesAreClassifiedTest
{
    @Test
    public void anEntryTheWholeWalkCouldNotFindIsAnObjectThatIsGone()
    {
        SupportSnapshot snapshot = new SupportSnapshot();
        BmSupportRegistryHelper.countUnnamed(snapshot, false, true);

        assertEquals("a complete walk not finding it is the one case that means deleted", //$NON-NLS-1$
            1, snapshot.unresolved);
        assertEquals(0, snapshot.unclassified);
    }

    @Test
    public void anEntryMissedByAnIncompleteWalkIsNotCalledDeleted()
    {
        SupportSnapshot snapshot = new SupportSnapshot();
        BmSupportRegistryHelper.countUnnamed(snapshot, false, false);

        assertEquals("claiming a deletion the walk never established is the defect this fixes", //$NON-NLS-1$
            0, snapshot.unresolved);
        assertEquals(1, snapshot.unclassified);
    }

    @Test
    public void anEntryWithANameIsCountedInNeither()
    {
        SupportSnapshot snapshot = new SupportSnapshot();
        BmSupportRegistryHelper.countUnnamed(snapshot, true, true);
        BmSupportRegistryHelper.countUnnamed(snapshot, true, false);

        assertEquals(0, snapshot.unresolved);
        assertEquals(0, snapshot.unclassified);
    }

    @Test
    public void theTwoNeverBothCountTheSameEntry()
    {
        // The categories are exclusive by construction, and the totals only add up while they are.
        SupportSnapshot snapshot = new SupportSnapshot();
        for (int i = 0; i < 10; i++)
        {
            BmSupportRegistryHelper.countUnnamed(snapshot, false, i % 2 == 0);
        }
        assertEquals(5, snapshot.unresolved);
        assertEquals(5, snapshot.unclassified);
        assertEquals(10, snapshot.unresolved + snapshot.unclassified);
    }

    @Test
    public void anIndexOfNothingIsNotAWholeWalk()
    {
        // A configuration that will not load is the quietest of the three ways completeness goes:
        // no exception reaches the caller, the index is simply empty, and without this every entry
        // in the registry would read as a deleted object.
        BmSupportRegistryHelper.NameIndex nothing = BmSupportRegistryHelper.NameIndex.ofNothing();

        assertFalse("an empty index is not evidence that the configuration is empty", //$NON-NLS-1$
            nothing.complete);
        assertTrue(nothing.names.isEmpty());
    }

    @Test
    public void aFreshIndexClaimsToBeWholeUntilSomethingSaysOtherwise()
    {
        // The default has to be true: a walk that meets nothing unusual is whole, and requiring
        // every path to set it would mean the one path that forgot reports the safe answer as
        // unsafe. What matters is that it never returns to true, which the three setters uphold by
        // only ever writing false.
        BmSupportRegistryHelper.NameIndex fresh = new BmSupportRegistryHelper.NameIndex();

        assertTrue(fresh.complete);
        assertEquals(0, fresh.ownersThatWouldNotOpen);
    }
}
