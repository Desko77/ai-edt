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
 * Holds the merge to saying when it took work from this side.
 * <p>
 * <b>Measured, and the reason this exists.</b> A real update of a vendor library, 3.1.5.446 to 3.2.1.505,
 * 198630 nodes - was run with a method that existed only on this side, in an object the delivery had
 * also changed. The merge answered {@code merged=true}, {@code mergedWithDelivery=2},
 * {@code deliveryNotApplied} empty and {@code protectionRefused} empty, and the file came back byte
 * for byte as the delivery's. Nothing in the answer said so, because there was no field that could.
 * </p>
 * <p>
 * The contract promised the opposite - "methods only this side has stay" - so the answer was not
 * merely quiet, it was quiet about a promise it had broken. What is asserted here is the reporting,
 * not the merge: the environment may legitimately take everything when the delivery rewrote the
 * whole of an object, but a reader has to be told that it did.
 * </p>
 */
public class MergeSaysWhatThisSideLostTest
{
    private static BmComparisonHelper.Outcome merged()
    {
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        outcome.threeWay = true;
        outcome.otherIs = "Standard 3.2.1.505 by Vendor"; //$NON-NLS-1$
        outcome.ancestorIs = "Standard 3.1.5.446 by Vendor"; //$NON-NLS-1$
        outcome.projectDescendsFrom = "Standard"; //$NON-NLS-1$
        outcome.objectsChangedByBoth = 1;
        return outcome;
    }

    @Test
    public void aQuietMergeStillHasTheThreeFields()
    {
        // They exist whether or not anything was lost. A field that appears only on bad news is a
        // field a caller cannot check for.
        BmComparisonHelper.Outcome outcome = merged();
        assertTrue(outcome.ourContentLost.isEmpty());
        assertTrue(outcome.ourContentUnchecked.isEmpty());
        assertTrue(outcome.ourContentLostCount == 0);
    }

    @Test
    public void whatWasLostReachesTheDocument()
    {
        BmComparisonHelper.Outcome outcome = merged();
        outcome.ourContentLostCount = 1;
        outcome.ourContentLost.add("Catalog.Partners.ManagerModule came out of the merge byte " //$NON-NLS-1$
            + "for byte as the delivery's copy."); //$NON-NLS-1$

        String report = UpdateReport.render(outcome, true, true);
        assertTrue(report, report.contains("## What this side lost")); //$NON-NLS-1$
        assertTrue(report, report.contains("Catalog.Partners.ManagerModule")); //$NON-NLS-1$
    }

    @Test
    public void whatCouldNotBeCheckedIsNotCountedAsSurviving()
    {
        // The distinction the whole check turns on: unread is not intact.
        BmComparisonHelper.Outcome outcome = merged();
        outcome.ourContentUnchecked.add("Catalog.Partners"); //$NON-NLS-1$

        String report = UpdateReport.render(outcome, true, true);
        assertTrue(report, report.contains("## What this side lost")); //$NON-NLS-1$
        assertTrue(report, report.contains("not checked either way")); //$NON-NLS-1$
        assertTrue(report, report.contains("Catalog.Partners")); //$NON-NLS-1$
    }

    @Test
    public void aMergeThatTookNothingDoesNotRaiseTheSection()
    {
        // A section that appears on every run stops being read. This one earns its place.
        String report = UpdateReport.render(merged(), true, true);
        assertFalse(report, report.contains("## What this side lost")); //$NON-NLS-1$
    }

    @Test
    public void objectsPastThePageAreCountedOutLoud()
    {
        // conflictQueue stops at a page. Objects past it are neither lost nor unchecked, and their
        // absence from both lists would otherwise read as safety.
        BmComparisonHelper.Outcome outcome = merged();
        outcome.objectsChangedByBoth = 700;
        outcome.ourContentBeyondThePage = 200;

        String report = UpdateReport.render(outcome, true, true);
        assertTrue(report, report.contains("## What this side lost"));
        assertTrue(report, report.contains("200"));
        assertTrue(report, report.contains("not evidence that they came through"));
    }

    @Test
    public void thePromiseNoLongerReadsAsAGuarantee()
    {
        // The description told callers that work only this side had would survive. It does not
        // always, and the sentence that said it does had to go with the fix.
        String schema = new ru.aiedt.mcp.server.toolkit.ops.ThreeWayComparisonTool()
            .getInputSchema();
        assertTrue(schema, schema.contains("does NOT guarantee")); //$NON-NLS-1$
        assertTrue(schema, schema.contains("ourContentLost")); //$NON-NLS-1$
    }
}
