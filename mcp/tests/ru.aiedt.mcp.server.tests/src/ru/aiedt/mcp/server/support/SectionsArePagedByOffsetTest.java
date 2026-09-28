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
 * Holds the paging of the module pieces a method-level comparison names.
 * <p>
 * A page of sections held at most 500 pieces and stopped, with {@code moreSections} saying so and
 * nothing able to see the rest: the only way past the page was narrowing the scope, which re-runs
 * a comparison that took minutes. The objects list had solved the same problem with an offset; the
 * sections now turn the same way.
 * </p>
 */
public class SectionsArePagedByOffsetTest
{
    private static FakeComparison moduleWithMethods(int howMany)
    {
        FakeComparison.Shape module = FakeComparison.top(1L, "CommonModule.Big.Module", //$NON-NLS-1$
            FakeComparison.changedOnBothSides());
        for (int i = 1; i <= howMany; i++)
        {
            module.child(FakeComparison.top(1L + i, "CommonModule.Big.Module.M" + i, //$NON-NLS-1$
                FakeComparison.changedOnBothSides()));
        }
        return FakeComparison.over(FakeComparison.plain(0L, null).child(module));
    }

    @Test
    public void theFirstPageStopsAtTheLimitAndSaysThereIsMore()
    {
        BmComparisonHelper.Outcome outcome = read(moduleWithMethods(505), 0);

        assertEquals(BmComparisonHelper.PAGE_LIMIT, outcome.sections.size());
        assertTrue(outcome.moreSections);
        assertEquals("the first piece of the first page", "CommonModule.Big.Module.M1", //$NON-NLS-1$ //$NON-NLS-2$
            outcome.sections.get(0).name);
    }

    @Test
    public void theOffsetTurnsToTheRestOfTheSameComparison()
    {
        BmComparisonHelper.Outcome outcome = read(moduleWithMethods(505),
            BmComparisonHelper.PAGE_LIMIT);

        assertEquals("the five pieces past the page, without re-running anything", 5, //$NON-NLS-1$
            outcome.sections.size());
        assertFalse(outcome.moreSections);
        assertEquals("CommonModule.Big.Module.M501", outcome.sections.get(0).name); //$NON-NLS-1$
        assertEquals("CommonModule.Big.Module.M505", outcome.sections.get(4).name); //$NON-NLS-1$
    }

    @Test
    public void anOffsetPastTheEndAnswersEmptyAndSaysNothingMore()
    {
        BmComparisonHelper.Outcome outcome = read(moduleWithMethods(600), 600);

        assertTrue(outcome.sections.isEmpty());
        assertFalse(outcome.moreSections);
    }

    private static BmComparisonHelper.Outcome read(FakeComparison fake, int sectionsOffset)
    {
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        outcome.threeWay = true;
        outcome.sectionsWanted = true;
        BmComparisonHelper.Page page = new BmComparisonHelper.Page();
        page.sectionsOffset = sectionsOffset;
        BmComparisonHelper.readTree(fake.session(), outcome, page);
        return outcome;
    }
}
