/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Holds apart the two things "both sides changed it" can mean.
 * <p>
 * <b>They need opposite treatment, and one rule for both is wrong either way.</b> Where the
 * delivery still has the object, putting the delivery in front is what an update means: contested
 * lines resolve toward it and methods only this side has stay. Where the delivery no longer has the
 * object, its version of that object is its absence - and putting that in front deletes work
 * somebody did here.
 * </p>
 * <p>
 * Both mistakes have been made in this file's history. Holding everything produced a merge that
 * ran clean, protected 8 objects and took 0 - safe and useless. Merging everything would carry out
 * a deletion the environment is perfectly willing to perform: measured, a catalogue customised here
 * and removed by the delivery comes back with mustBeMerged set and GET_FROM_OTHER proposed.
 * </p>
 */
public class DeliveryInFrontNeverDeletesTest
{
    @Test
    public void anObjectTheDeliveryStillHasIsMergedWithItInFront()
    {
        BmComparisonHelper.Change change = new BmComparisonHelper.Change();
        change.nodeId = 11L;
        change.main = "CommonModule.F3Api"; //$NON-NLS-1$
        change.other = "CommonModule.F3Api"; //$NON-NLS-1$
        change.ancestor = "CommonModule.F3Api"; //$NON-NLS-1$
        change.changedBy = AttributionRule.BOTH;

        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        BmComparisonHelper.sortBothNode(outcome, change);

        assertEquals("this is the ordinary case an update exists for", //$NON-NLS-1$
            1, outcome.bothNodes.size());
        assertTrue(outcome.bothNodesGoneFromDelivery.isEmpty());
    }

    @Test
    public void anObjectTheDeliveryNoLongerHasIsKeptOutOfTheMerge()
    {
        // The delivery's version of it is its removal. Nothing about "put the delivery in front"
        // should reach this node.
        BmComparisonHelper.Change change = new BmComparisonHelper.Change();
        change.nodeId = 22L;
        change.main = "Catalog.WeCustomisedThis"; //$NON-NLS-1$
        change.other = null;
        change.ancestor = "Catalog.WeCustomisedThis"; //$NON-NLS-1$
        change.changedBy = AttributionRule.BOTH;

        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        BmComparisonHelper.sortBothNode(outcome, change);

        assertTrue("merging this with the delivery in front deletes work done here", //$NON-NLS-1$
            outcome.bothNodes.isEmpty());
        assertEquals(1, outcome.bothNodesGoneFromDelivery.size());
    }

    @Test
    public void anEmptyNameCountsAsAbsentJustLikeAMissingOne()
    {
        // The comparison reports "not there" as an empty name on that side rather than as null,
        // and reading only null would send the deletion case down the merging path.
        BmComparisonHelper.Change change = new BmComparisonHelper.Change();
        change.nodeId = 33L;
        change.main = "Catalog.WeCustomisedThis"; //$NON-NLS-1$
        change.other = ""; //$NON-NLS-1$
        change.changedBy = AttributionRule.BOTH;

        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        BmComparisonHelper.sortBothNode(outcome, change);

        assertTrue(outcome.bothNodes.isEmpty());
        assertEquals(1, outcome.bothNodesGoneFromDelivery.size());
    }

}
