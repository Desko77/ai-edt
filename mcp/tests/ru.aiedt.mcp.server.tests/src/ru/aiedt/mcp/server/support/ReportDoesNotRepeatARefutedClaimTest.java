/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Keeps the conflicts section from telling a reader something that was disproved.
 * <p>
 * <b>This text drifted from measurement once already.</b> It said the environment keeps ours on a
 * conflict whatever rule is asked for, and called that measured. It was measured - but the rule had
 * been set on the OBJECT node, while a module's text lives in its own node underneath, so the run
 * recorded the environment's defaults and named them a limit of the platform. Re-measured with the
 * rule on the module node, MERGE_PRIORITIZING_OTHER resolves the contested lines toward the
 * delivery and still keeps a method only this side has.
 * </p>
 * <p>
 * The damage a stale sentence does here is particular: this document is what a person reads before
 * deciding to update, and the word "measured" lends it authority the claim no longer has. So the
 * refuted wording is asserted absent rather than left to be noticed.
 * </p>
 */
public class ReportDoesNotRepeatARefutedClaimTest
{
    private static BmComparisonHelper.Outcome withAConflict()
    {
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        outcome.threeWay = true;
        outcome.otherIs = "Standard 2.5.16.9 by Vendor"; //$NON-NLS-1$
        outcome.ancestorIs = "Standard 2.5.14.20 by Vendor"; //$NON-NLS-1$
        outcome.projectDescendsFrom = "Standard"; //$NON-NLS-1$
        outcome.objectsChangedByBoth = 1;
        return outcome;
    }

    @Test
    public void theRefutedSentenceIsGone()
    {
        String report = UpdateReport.render(withAConflict(), true, true);
        assertFalse(report, report.contains("keeps ours whatever rule is asked for")); //$NON-NLS-1$
    }

    @Test
    public void whatDecidesTheOutcomeIsNamed()
    {
        // mustBeMerged is the signal, and which node the decision is addressed at is the reason a
        // reader gets a different answer for the same object.
        String report = UpdateReport.render(withAConflict(), true, true);
        assertTrue(report, report.contains("mustBeMerged")); //$NON-NLS-1$
        assertTrue(report, report.contains("module node")); //$NON-NLS-1$
        assertTrue(report, report.contains("MERGE_PRIORITIZING_OTHER")); //$NON-NLS-1$
    }

    @Test
    public void theNodeThatCannotMoveIsStillNamed()
    {
        // The part of the old claim that survived: a node the environment will not move at all,
        // and where a decision aimed at it goes.
        String report = UpdateReport.render(withAConflict(), true, true);
        assertTrue(report, report.contains("decisionsWithoutEffect")); //$NON-NLS-1$
    }

    @Test
    public void aRunWithoutConflictsSaysSoWithoutTheExplanation()
    {
        BmComparisonHelper.Outcome quiet = withAConflict();
        quiet.objectsChangedByBoth = 0;
        String report = UpdateReport.render(quiet, true, true);
        assertTrue(report, report.contains("None: no object was changed on both sides")); //$NON-NLS-1$
        assertFalse(report, report.contains("mustBeMerged")); //$NON-NLS-1$
    }
}
