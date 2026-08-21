/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders one comparison as the document a person reads before deciding to update.
 * <p>
 * <b>Assembled from the answer, not from a second pass.</b> The numbers here are the ones the
 * comparison already produced, so a report cannot disagree with the fields beside it - which is the
 * failure mode a separate reporting call would have.
 * </p>
 * <p>
 * <b>The section that says what was NOT checked is not a disclaimer.</b> It is the part a person
 * needs most: an update decided on a report that looks complete, and quietly was not, is how work
 * gets overwritten. So it names what this particular run did not look at, and it is never empty.
 * </p>
 */
public final class UpdateReport
{
    /** How many names a summary lists before it stops being a summary. */
    private static final int SUMMARY_NAMES = 10;

    private UpdateReport()
    {
        // Static renderer.
    }

    /**
     * Builds the report.
     *
     * @param outcome what the comparison found.
     * @param full <code>true</code> for every name, <code>false</code> for a summary.
     * @param methodLevel whether modules were told apart piece by piece.
     * @return the report as markdown
     */
    public static String render(BmComparisonHelper.Outcome outcome, boolean full,
        boolean methodLevel)
    {
        StringBuilder out = new StringBuilder();
        out.append("# Update report\n\n"); //$NON-NLS-1$
        sides(outcome, out);
        counts(outcome, out);
        atRisk(outcome, full, out);
        queue(outcome, full, out);
        support(outcome, out);
        notChecked(outcome, full, methodLevel, out);
        return out.toString();
    }

    /** What was compared against what. */
    private static void sides(BmComparisonHelper.Outcome outcome, StringBuilder out)
    {
        out.append("## The sides\n\n"); //$NON-NLS-1$
        out.append("- this project descends from: ") //$NON-NLS-1$
            .append(value(outcome.projectDescendsFrom)).append('\n');
        out.append("- the delivery compared against: ").append(value(outcome.otherIs)).append('\n'); //$NON-NLS-1$
        out.append("- the delivery both came from: ") //$NON-NLS-1$
            .append(outcome.threeWay ? value(outcome.ancestorIs) : "none - two-sided comparison") //$NON-NLS-1$
            .append('\n');
        if (!outcome.originMismatches.isEmpty())
        {
            out.append("- **the sides do not agree about what they are:** ") //$NON-NLS-1$
                .append(String.join("; ", outcome.originMismatches)).append('\n'); //$NON-NLS-1$
        }
        out.append("- the comparison took ").append(outcome.comparedInMs).append(" ms\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** How much moved, and on whose side. */
    private static void counts(BmComparisonHelper.Outcome outcome, StringBuilder out)
    {
        out.append("## What moved\n\n"); //$NON-NLS-1$
        if (!outcome.threeWay)
        {
            out.append("A two-sided comparison cannot say who changed what. Everything below is " //$NON-NLS-1$
                + "counted as unattributed, and no update decision should rest on it.\n\n"); //$NON-NLS-1$
        }
        out.append("| | objects |\n|---|---|\n"); //$NON-NLS-1$
        out.append("| changed here | ").append(outcome.objectsChangedByUs).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        out.append("| changed in the delivery | ").append(outcome.objectsChangedByVendor) //$NON-NLS-1$
            .append(" |\n"); //$NON-NLS-1$
        out.append("| changed on both sides | ").append(outcome.objectsChangedByBoth) //$NON-NLS-1$
            .append(" |\n"); //$NON-NLS-1$
        out.append("| could not be attributed | ").append(outcome.objectsChangedUnattributed) //$NON-NLS-1$
            .append(" |\n"); //$NON-NLS-1$
        out.append("| **total that differ** | ").append(outcome.objectsChanged).append(" |\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * What an update takes from the delivery over work done here, unless somebody stops it.
     * <p>
     * Measured behaviour, not a guess: an object the environment marks {@code mustBeMerged} with
     * GET_FROM_OTHER proposed is one it is willing to move. Where that object is also attributed to
     * this side, moving it replaces work somebody did here - and a deletion in the delivery is
     * carried out the same way.
     * </p>
     */
    private static void atRisk(BmComparisonHelper.Outcome outcome, boolean full, StringBuilder out)
    {
        List<String> risky = new ArrayList<>();
        for (BmComparisonHelper.Change change : outcome.changed)
        {
            boolean ours = AttributionRule.OURS.equals(change.changedBy)
                || AttributionRule.BOTH.equals(change.changedBy);
            if (ours && change.mustBeMerged)
            {
                risky.add(name(change) + " (" + change.changedBy + ", the environment proposes " //$NON-NLS-1$ //$NON-NLS-2$
                    + value(change.recommendedRule) + ")"); //$NON-NLS-1$
            }
        }
        out.append("## What an update overwrites if nobody intervenes\n\n"); //$NON-NLS-1$
        if (risky.isEmpty())
        {
            out.append("Nothing among the objects listed: no object attributed to this side is " //$NON-NLS-1$
                + "one the environment is willing to move.\n\n"); //$NON-NLS-1$
            return;
        }
        out.append(risky.size()).append(" object(s) attributed to this side are ones the " //$NON-NLS-1$
            + "environment is willing to take from the delivery. Left alone, this is the work " //$NON-NLS-1$
            + "that goes.\n\n"); //$NON-NLS-1$
        names(risky, full, out);
        out.append("\nintent=UPDATE_KEEPING_OURS holds all of them at DO_NOT_MERGE and reads the " //$NON-NLS-1$
            + "rule back off each one.\n\n"); //$NON-NLS-1$
    }

    /** The conflicts, which are work for a person. */
    private static void queue(BmComparisonHelper.Outcome outcome, boolean full, StringBuilder out)
    {
        out.append("## Conflicts to decide by hand\n\n"); //$NON-NLS-1$
        if (outcome.objectsChangedByBoth == 0)
        {
            out.append("None: no object was changed on both sides.\n\n"); //$NON-NLS-1$
            return;
        }
        out.append(outcome.objectsChangedByBoth).append(" object(s) were changed on both sides.\n\n"); //$NON-NLS-1$
        if (!outcome.conflictQueue.isEmpty())
        {
            names(outcome.conflictQueue, full, out);
            out.append('\n');
        }
        out.append("On a conflict where both sides changed the same content the environment " //$NON-NLS-1$
            + "keeps ours whatever rule is asked for - measured. Such a decision comes back in " //$NON-NLS-1$
            + "decisionsWithoutEffect rather than being reported as applied.\n\n"); //$NON-NLS-1$
    }

    /** What the update did, or would do, to the vendor support model. */
    private static void support(BmComparisonHelper.Outcome outcome, StringBuilder out)
    {
        out.append("## Vendor support\n\n"); //$NON-NLS-1$
        if (outcome.supportModesBefore.isEmpty() && outcome.supportModesAfter.isEmpty())
        {
            out.append("Not read on this run. A merge reads it either side of itself; a report " //$NON-NLS-1$
                + "does not, because taking the census is not free and nothing is at risk " //$NON-NLS-1$
                + "yet.\n\n"); //$NON-NLS-1$
            return;
        }
        out.append("Before: ").append(outcome.supportModesBefore).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        out.append("After: ").append(outcome.supportModesAfter).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (outcome.supportModesLostCount > 0)
        {
            out.append("**").append(outcome.supportModesLostCount) //$NON-NLS-1$
                .append(" object(s) survived the update and lost the mode they had.** That work " //$NON-NLS-1$
                    + "is no longer marked as ours.\n\n"); //$NON-NLS-1$
        }
        if (outcome.supportSnapshotFile != null)
        {
            out.append("The modes as they stood before the merge are in `") //$NON-NLS-1$
                .append(outcome.supportSnapshotFile)
                .append("`; support_registry operation=restore_modes puts them back.\n\n"); //$NON-NLS-1$
        }
    }

    /**
     * What this run did not look at.
     * <p>
     * Never empty. A report that lists only what it found reads as complete, and an update decided
     * on that basis is how work gets overwritten by something nobody was told about.
     * </p>
     */
    private static void notChecked(BmComparisonHelper.Outcome outcome, boolean full,
        boolean methodLevel, StringBuilder out)
    {
        List<String> gaps = new ArrayList<>();
        if (!outcome.threeWay)
        {
            gaps.add("who changed what - there was no common ancestor, so nothing is attributed"); //$NON-NLS-1$
        }
        if (!methodLevel)
        {
            gaps.add("the inside of modules - a module is reported whole. Pass methodLevel=true " //$NON-NLS-1$
                + "to have conflicts named method by method"); //$NON-NLS-1$
        }
        if (outcome.moreChanged)
        {
            gaps.add("objects beyond this page - " + outcome.changedMatching //$NON-NLS-1$
                + " matched the filter and the page stopped short of them"); //$NON-NLS-1$
        }
        if (outcome.matchingTruncated)
        {
            gaps.add("objects beyond the ceiling a single class-wide decision covers"); //$NON-NLS-1$
        }
        if (outcome.problemsNote != null)
        {
            gaps.add("the problems the environment raises against a merge - " //$NON-NLS-1$
                + outcome.problemsNote);
        }
        if (!outcome.scopeRequested.isEmpty())
        {
            gaps.add("everything outside the scope that was asked for: " //$NON-NLS-1$
                + String.join(", ", outcome.scopeRequested)); //$NON-NLS-1$
        }
        if (!outcome.scopeUnrecognised.isEmpty())
        {
            gaps.add("**names in the scope the comparison never saw: " //$NON-NLS-1$
                + String.join(", ", outcome.scopeUnrecognised) + "**"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // Always. Two things this tool cannot see at all, and a report that did not say so would
        // be claiming a completeness it has no way to have.
        gaps.add("whether anybody has unsaved work open in an editor - reaching into the " //$NON-NLS-1$
            + "workbench to find out interrupts them, so it is not done"); //$NON-NLS-1$
        gaps.add("whether the delivery is genuinely the next release of this configuration - " //$NON-NLS-1$
            + "only the identity, name, vendor and version recorded in the files are compared"); //$NON-NLS-1$

        out.append("## What this run did NOT check\n\n"); //$NON-NLS-1$
        names(gaps, full, out);
        out.append('\n');
    }

    /** Prints a list, saying plainly when it stopped short. */
    private static void names(List<String> items, boolean full, StringBuilder out)
    {
        int shown = full ? items.size() : Math.min(SUMMARY_NAMES, items.size());
        for (int i = 0; i < shown; i++)
        {
            out.append("- ").append(items.get(i)).append('\n'); //$NON-NLS-1$
        }
        if (shown < items.size())
        {
            // Named rather than trailed off. A list that stops without saying so is read as the
            // whole of what there is.
            out.append("- ... and ").append(items.size() - shown) //$NON-NLS-1$
                .append(" more, not listed in the summary - ask for report=full\n"); //$NON-NLS-1$
        }
    }

    /** Names a changed object the way the object list does. */
    private static String name(BmComparisonHelper.Change change)
    {
        if (change.main != null && !change.main.isEmpty())
        {
            return change.main;
        }
        if (change.other != null && !change.other.isEmpty())
        {
            return change.other;
        }
        return value(change.ancestor);
    }

    /** Prints a value, or says there is none rather than printing the word null. */
    private static String value(Object value)
    {
        if (value == null)
        {
            return "not established"; //$NON-NLS-1$
        }
        String text = value instanceof Map ? value.toString() : String.valueOf(value);
        return text.isEmpty() ? "not established" : text; //$NON-NLS-1$
    }
}
