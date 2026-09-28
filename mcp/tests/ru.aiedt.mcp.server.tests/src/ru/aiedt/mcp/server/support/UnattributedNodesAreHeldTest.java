/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.compare.model.MergeRule;

/**
 * Holds what an update does with an object nobody could attribute.
 * <p>
 * UPDATE_UNCHANGED already refuses on the unattributed count: an object that answers to no side
 * cannot be promised free of work of ours, and taking the delivery whole on the strength of that
 * is how customisations get overwritten. UPDATE_KEEPING_OURS left the very same objects to the
 * merge defaults - the one mode whose stated purpose is keeping work of ours.
 * </p>
 */
public class UnattributedNodesAreHeldTest
{
    private static FakeComparison withAnUnattributedCatalogue()
    {
        // Differs between the sides while neither moved from the ancestor: the shape that
        // attributes to UNKNOWN on a three-sided comparison.
        return FakeComparison.over(FakeComparison.plain(1L, null).child(
            FakeComparison.top(2L, "Catalog.Тайна", FakeComparison.changedBetweenTheSidesOnly())));
    }

    @Test
    public void anUnattributedObjectIsHeldAndNamed()
    {
        FakeComparison fake = withAnUnattributedCatalogue();
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        outcome.threeWay = true;
        BmComparisonHelper.readTree(fake.session(), outcome, new BmComparisonHelper.Page());

        BmComparisonHelper.protectOurs(fake.session(), outcome);

        assertEquals("held, not trusted to the merge defaults", MergeRule.DO_NOT_MERGE, //$NON-NLS-1$
            fake.ruleOn(2L));
        assertTrue(outcome.protectedFromUpdate > 0);
        assertEquals("and the delivery's change not arriving is said out loud", 1, //$NON-NLS-1$
            outcome.deliveryNotApplied.size());
        assertTrue(outcome.deliveryNotApplied.get(0),
            outcome.deliveryNotApplied.get(0).contains("could not be attributed")); //$NON-NLS-1$
    }

    @Test
    public void aTwoSidedComparisonHoldsNothingOnAttribution()
    {
        // Without an ancestor everything is UNKNOWN, and none of it is work this mode can tell
        // from a vendor change - the mode itself refuses two-sided runs. Holding the whole
        // comparison's worth of nodes would be noise on top of a refusal nobody can act on.
        FakeComparison fake = withAnUnattributedCatalogue();
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        outcome.threeWay = false;
        BmComparisonHelper.readTree(fake.session(), outcome, new BmComparisonHelper.Page());

        BmComparisonHelper.protectOurs(fake.session(), outcome);

        assertNull(fake.ruleOn(2L));
        assertEquals(0, outcome.protectedFromUpdate);
    }
}
