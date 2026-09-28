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
 * Holds the grain a merge with the delivery in front works at, when the tree names every method.
 * <p>
 * Measured with {@code methodLevel} on: the tree holds the module root AND each of its methods as
 * top nodes, and both were attributed BOTH and both reached the loop that hands out rules. The
 * subtree rule on the root reached down and overwrote whatever the methods carried - including a
 * method settled by an explicit decision - and the root was counted in {@code mergedWithDelivery}
 * beside its own methods, inflating the count by the number of modules.
 * </p>
 * <p>
 * What is asserted: the methods carry their own rules and nothing else's, the root carries none and
 * counts nowhere, and at the coarse grain - no methods enumerated - the root is still held as one
 * unit, which is the behaviour the coarse grain has always had.
 * </p>
 */
public class AModuleRootWithMethodsIsNotMergedWholeTest
{
    private static final String MODULE = "Catalog.Валюты.ObjectModule"; //$NON-NLS-1$

    private static FakeComparison moduleWithBothChangedMethods()
    {
        return FakeComparison.over(FakeComparison.plain(1L, null).child(
            FakeComparison.top(2L, MODULE, FakeComparison.changedOnBothSides())
                .child(FakeComparison.top(3L, MODULE + ".ПередЗаписью", //$NON-NLS-1$
                    FakeComparison.changedOnBothSides()))
                .child(FakeComparison.top(4L, MODULE + ".КодыВалют", //$NON-NLS-1$
                    FakeComparison.changedOnBothSides()))));
    }

    @Test
    public void theMethodsAreMergedAndTheRootCountsNowhere()
    {
        FakeComparison fake = moduleWithBothChangedMethods();
        BmComparisonHelper.Outcome outcome = readAtTheMethodGrain(fake);

        BmComparisonHelper.protectOurs(fake.session(), outcome);

        assertEquals("two methods both sides changed, counted as methods and not as the module " //$NON-NLS-1$
            + "root beside them", 2, outcome.mergedWithDelivery); //$NON-NLS-1$
        assertEquals(MergeRule.MERGE_PRIORITIZING_OTHER, fake.ruleOn(3L));
        assertEquals(MergeRule.MERGE_PRIORITIZING_OTHER, fake.ruleOn(4L));
        assertNull("the root takes no subtree rule: it would reach down into the methods", //$NON-NLS-1$
            fake.ruleOn(2L));
    }

    @Test
    public void aRuleSettledOnOneMethodSurvivesTheRoot()
    {
        // The overwrite ran the other way in the defect: the methods were merged with the delivery
        // in front, and the hold loop then set DO_NOT_MERGE on the module root - a subtree rule -
        // over them. The root is ours here, so its hold is the one that would do it.
        FakeComparison fake = FakeComparison.over(FakeComparison.plain(1L, null).child(
            FakeComparison.top(2L, MODULE, FakeComparison.changedByUs())
                .child(FakeComparison.top(3L, MODULE + ".КодыВалют", //$NON-NLS-1$
                    FakeComparison.changedOnBothSides()))));
        BmComparisonHelper.Outcome outcome = readAtTheMethodGrain(fake);

        BmComparisonHelper.protectOurs(fake.session(), outcome);

        assertEquals("the method both sides changed is merged with the delivery in front", 1, //$NON-NLS-1$
            outcome.mergedWithDelivery);
        assertEquals("and still carries that rule, not the root's hold over it", //$NON-NLS-1$
            MergeRule.MERGE_PRIORITIZING_OTHER, fake.ruleOn(3L));
        assertNull(fake.ruleOn(2L));
    }

    @Test
    public void atTheCoarseGrainTheRootIsStillHeldAsOneUnit()
    {
        // Without methodLevel nothing is enumerated, the root is the only unit there is, and the
        // hold that kept a method only this side had must keep working. The fix may not cost the
        // coarse grain its safety.
        FakeComparison fake = moduleWithBothChangedMethods();
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        outcome.threeWay = true;
        outcome.sectionsWanted = false;
        BmComparisonHelper.readTree(fake.session(), outcome, new BmComparisonHelper.Page());

        BmComparisonHelper.protectOurs(fake.session(), outcome);

        assertEquals(MergeRule.DO_NOT_MERGE, fake.ruleOn(2L));
        assertTrue(outcome.protectedFromUpdate > 0);
    }

    private static BmComparisonHelper.Outcome readAtTheMethodGrain(FakeComparison fake)
    {
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        outcome.threeWay = true;
        outcome.sectionsWanted = true;
        BmComparisonHelper.readTree(fake.session(), outcome, new BmComparisonHelper.Page());
        return outcome;
    }
}
