/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Guards the document a person reads before deciding to update.
 * <p>
 * The section that carries the most weight is the one saying what was NOT checked. A report listing
 * only what it found reads as complete, and an update decided on that basis is how work gets
 * overwritten by something nobody was told about - so that section is never empty, whatever the run
 * looked at.
 * </p>
 */
public class UpdateReportTest
{
    private static BmComparisonHelper.Outcome outcome()
    {
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        outcome.threeWay = true;
        outcome.otherIs = "Standard 2.5.16.9 by Vendor";
        outcome.ancestorIs = "Standard 2.5.14.20 by Vendor";
        outcome.projectDescendsFrom = "Standard";
        return outcome;
    }

    private static BmComparisonHelper.Change change(String name, String by, boolean mustBeMerged)
    {
        BmComparisonHelper.Change change = new BmComparisonHelper.Change();
        change.main = name;
        change.changedBy = by;
        change.mustBeMerged = mustBeMerged;
        change.recommendedRule = mustBeMerged ? "GET_FROM_OTHER" : "DO_NOT_MERGE";
        return change;
    }

    @Test
    public void everySectionThePlanAsksForIsThere()
    {
        String report = UpdateReport.render(outcome(), true, true);
        assertTrue(report, report.contains("## The sides"));
        assertTrue(report, report.contains("## What moved"));
        assertTrue(report, report.contains("## What an update overwrites"));
        assertTrue(report, report.contains("## Conflicts to decide by hand"));
        assertTrue(report, report.contains("## Vendor support"));
        assertTrue(report, report.contains("## What this run did NOT check"));
    }

    @Test
    public void whatWasNotCheckedIsNeverEmpty()
    {
        // Even on the most thorough run there are two things this tool cannot see, and a report
        // that stayed silent about them would be claiming a completeness it has no way to have.
        String thorough = UpdateReport.render(outcome(), true, true);
        int start = thorough.indexOf("## What this run did NOT check");
        String section = thorough.substring(start);
        assertTrue("the section has to carry entries, not just a heading: " + section,
            section.contains("- "));
        assertTrue("unsaved editor content is one of them", section.contains("unsaved work"));
        assertTrue("and whether the delivery really is the next release",
            section.contains("next release"));
    }

    @Test
    public void aTwoSidedComparisonSaysItAttributesNothing()
    {
        BmComparisonHelper.Outcome outcome = outcome();
        outcome.threeWay = false;
        String report = UpdateReport.render(outcome, true, true);
        assertTrue("an update decided on a two-sided comparison rests on nothing, and the report "
            + "has to say so where a person will read it: " + report,
            report.contains("cannot say who changed what"));
        assertTrue(report.contains("no common ancestor"));
    }

    @Test
    public void workTheEnvironmentIsWillingToOverwriteIsNamed()
    {
        BmComparisonHelper.Outcome outcome = outcome();
        outcome.changed.add(change("Catalog.Ours", AttributionRule.OURS, true));
        outcome.changed.add(change("Catalog.Safe", AttributionRule.OURS, false));
        outcome.changed.add(change("Catalog.Theirs", AttributionRule.VENDOR, true));

        String report = UpdateReport.render(outcome, true, false);
        assertTrue("an object attributed to us that the environment will move is the work an "
            + "update takes: " + report, report.contains("Catalog.Ours"));
        assertFalse("one it will not move is not at risk and naming it would pad the list",
            report.contains("Catalog.Safe"));
        assertFalse("and the delivery's own changes are the point of updating, not a loss",
            report.contains("Catalog.Theirs"));
    }

    @Test
    public void nothingAtRiskIsStatedRatherThanLeftBlank()
    {
        BmComparisonHelper.Outcome outcome = outcome();
        outcome.changed.add(change("Catalog.Theirs", AttributionRule.VENDOR, true));
        String report = UpdateReport.render(outcome, true, true);
        assertTrue(report, report.contains("Nothing among the objects listed"));
    }

    @Test
    public void aSummaryThatStoppedShortSaysSo()
    {
        BmComparisonHelper.Outcome outcome = outcome();
        for (int i = 0; i < 25; i++)
        {
            outcome.changed.add(change("Catalog.Ours" + i, AttributionRule.OURS, true));
        }
        String summary = UpdateReport.render(outcome, false, true);
        assertTrue("a list that stops without saying so is read as the whole of what there is: "
            + summary, summary.contains("more, not listed in the summary"));

        String full = UpdateReport.render(outcome, true, true);
        assertFalse(full, full.contains("more, not listed in the summary"));
        assertTrue(full.contains("Catalog.Ours24"));
    }

    @Test
    public void aScopeNameTheComparisonNeverSawIsCalledOut()
    {
        BmComparisonHelper.Outcome outcome = outcome();
        outcome.scopeRequested.add("Catalog.Real");
        outcome.scopeUnrecognised.add("Catalog.Misspelled");
        String report = UpdateReport.render(outcome, true, true);
        assertTrue("a scope of names that exist nowhere compares nothing and reports no "
            + "differences, which is the worst answer this tool can give: " + report,
            report.contains("Catalog.Misspelled"));
    }

    @Test
    public void modulesReportedWholeAreNamedAsAGap()
    {
        String shallow = UpdateReport.render(outcome(), true, false);
        assertTrue(shallow, shallow.contains("the inside of modules"));
        String deep = UpdateReport.render(outcome(), true, true);
        assertFalse("with modules told apart there is no such gap to report",
            deep.contains("the inside of modules"));
    }
}
