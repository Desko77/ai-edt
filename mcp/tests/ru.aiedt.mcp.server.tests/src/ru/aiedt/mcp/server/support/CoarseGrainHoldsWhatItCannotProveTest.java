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
 * Pins what an update does with an object both sides changed when it cannot see inside it.
 * <p>
 * <b>Measured, and the flag was the only difference.</b> Same project, same delivery, same scope,
 * one customisation - a method that existed only on this side, in a module the delivery had also
 * rewritten:
 * </p>
 * <pre>
 * methodLevel=true   protectedFromUpdate 1   ourContentLost 0   the method survived
 * without it         protectedFromUpdate 0   ourContentLost 1   the method was gone
 * </pre>
 * <p>
 * So merging with the delivery in front is right only where a decision can reach a method. At the
 * object grain there is no node to hold the method with, and nothing can promise it survives. The
 * object is held instead: the delivery's change to it does not arrive, that is said in
 * deliveryNotApplied, and the answer names the finer call.
 * </p>
 * <p>
 * Refusing the whole merge was the other option and was rejected: it throws away the part of the
 * release that was never in danger. Holding costs one object; refusing costs the update.
 * </p>
 * <p>
 * <b>What this file can and cannot hold.</b> Which branch runs is decided against a live comparison
 * session, which no unit test has - that half is a live check, recorded in the commit. What is
 * pinned here is the contract text, because the previous version of it promised behaviour the code
 * did not have, and that is how the last two defects in this area started.
 * </p>
 */
public class CoarseGrainHoldsWhatItCannotProveTest
{
    @Test
    public void theSchemaSaysWhatHappensWithoutMethodLevel()
    {
        String schema = new ru.aiedt.mcp.server.toolkit.ops.ThreeWayComparisonTool().getInputSchema();
        assertTrue(schema, schema.contains("WITHOUT it the comparison stops at the object")); //$NON-NLS-1$
        assertTrue(schema, schema.contains("deliveryNotApplied")); //$NON-NLS-1$
    }

    @Test
    public void theSchemaNoLongerPromisesTheMergeUnconditionally()
    {
        // The sentence about putting the delivery in front used to stand alone, so a reader took it
        // for what the mode always does. It is now qualified in the same breath.
        //
        // Asserted without the equals sign and the word after it: the schema is JSON, and the
        // writer escapes an equals sign and an apostrophe into their unicode forms. A probe
        // carrying either character never matches, however right it looks beside the source.
        // Caught by this test failing on its first run - the source said one thing and the
        // schema another.
        String schema = new ru.aiedt.mcp.server.toolkit.ops.ThreeWayComparisonTool().getInputSchema();
        assertTrue(schema, schema.contains("This applies only with methodLevel")); //$NON-NLS-1$
    }

    @Test
    public void theReportSeparatesObjectsBothSidesChanged()
    {
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        outcome.threeWay = true;
        outcome.otherIs = "Standard 3.2.1.505 by Vendor"; //$NON-NLS-1$
        outcome.ancestorIs = "Standard 3.1.5.446 by Vendor"; //$NON-NLS-1$
        outcome.projectDescendsFrom = "Standard"; //$NON-NLS-1$
        outcome.changed.add(riskyOne());

        String report = UpdateReport.render(outcome, true, true);
        assertTrue(report, report.contains("Objects changed on BOTH sides are a separate group")); //$NON-NLS-1$
        assertTrue(report, report.contains("held whole")); //$NON-NLS-1$
    }

    @Test
    public void aReportWithNothingAtRiskDoesNotExplainTheGroups()
    {
        // The explanation belongs beside the objects it is about. On a run with nothing at risk it
        // is noise, and noise is how a section stops being read.
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        outcome.threeWay = true;
        outcome.otherIs = "Standard 3.2.1.505 by Vendor"; //$NON-NLS-1$
        outcome.ancestorIs = "Standard 3.1.5.446 by Vendor"; //$NON-NLS-1$
        outcome.projectDescendsFrom = "Standard"; //$NON-NLS-1$

        String report = UpdateReport.render(outcome, true, true);
        assertFalse(report, report.contains("Objects changed on BOTH sides are a separate group")); //$NON-NLS-1$
    }

    private static BmComparisonHelper.Change riskyOne()
    {
        BmComparisonHelper.Change change = new BmComparisonHelper.Change();
        change.main = "Catalog.Partners"; //$NON-NLS-1$
        change.other = "Catalog.Partners"; //$NON-NLS-1$
        change.ancestor = "Catalog.Partners"; //$NON-NLS-1$
        change.changedBy = AttributionRule.OURS;
        change.mustBeMerged = true;
        change.recommendedRule = "GET_FROM_OTHER"; //$NON-NLS-1$
        return change;
    }
}
