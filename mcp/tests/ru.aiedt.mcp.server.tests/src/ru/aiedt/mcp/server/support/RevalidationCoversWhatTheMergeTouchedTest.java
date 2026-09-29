/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

/**
 * Holds the set of objects an after-merge check looks at.
 * <p>
 * When decisions were passed, the revalidation looked at exactly the objects they named - and an
 * update driven by intent or by a settings file moves every object the comparison found, not the
 * handful somebody wrote down by hand. Measured: a merge that wrote 5181 files revalidated the
 * named few and reported {@code errorsAfterMerge} off them. The set is the decisions AND the page
 * of the comparison, once each.
 * </p>
 */
public class RevalidationCoversWhatTheMergeTouchedTest
{
    private static BmComparisonHelper.Change changed(String main)
    {
        BmComparisonHelper.Change change = new BmComparisonHelper.Change();
        change.main = main;
        change.other = main;
        change.changedBy = "BOTH"; //$NON-NLS-1$
        return change;
    }

    @Test
    public void theDecisionsAndThePageTogether()
    {
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        outcome.changed.add(changed("Catalog.Alpha"));
        outcome.changed.add(changed("Catalog.Gamma"));
        outcome.changed.add(changed("Catalog.Delta"));

        List<String> targets = BmComparisonHelper.revalidationTargets(
            List.of(new BmComparisonHelper.Decision("Catalog.Alpha", "DO_NOT_MERGE"), //$NON-NLS-1$ //$NON-NLS-2$
                new BmComparisonHelper.Decision("Catalog.Beta", "GET_FROM_OTHER")), //$NON-NLS-1$ //$NON-NLS-2$
            outcome);

        assertEquals("the decisions, then what the comparison found on this page", 4, targets.size()); //$NON-NLS-1$
        assertEquals("Catalog.Alpha", targets.get(0)); //$NON-NLS-1$
        assertEquals("Catalog.Beta", targets.get(1)); //$NON-NLS-1$
        assertEquals("Catalog.Gamma", targets.get(2)); //$NON-NLS-1$
        assertEquals("Catalog.Delta", targets.get(3)); //$NON-NLS-1$
    }

    @Test
    public void aMergeDrivenByNoDecisionsStillCoversThePage()
    {
        // The old fallback, kept: a settings-file or intent-driven merge passes no decisions, and
        // the page is the only record of what could have moved.
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        outcome.changed.add(changed("Catalog.Gamma")); //$NON-NLS-1$
        outcome.changed.add(changed("Catalog.Delta")); //$NON-NLS-1$

        List<String> targets = BmComparisonHelper.revalidationTargets(null, outcome);

        assertEquals(2, targets.size());
    }

    @Test
    public void aNameTheOtherSideCarriesCountsToo()
    {
        // An object the vendor added has no name on our side; the comparison names it on theirs,
        // and the check has to look at it under that name or not at all.
        BmComparisonHelper.Change added = new BmComparisonHelper.Change();
        added.other = "Catalog.NewFromVendor"; //$NON-NLS-1$
        added.oneSided = true;
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        outcome.changed.add(added);

        List<String> targets = BmComparisonHelper.revalidationTargets(null, outcome);

        assertEquals(1, targets.size());
        assertEquals("Catalog.NewFromVendor", targets.get(0)); //$NON-NLS-1$
    }
}
