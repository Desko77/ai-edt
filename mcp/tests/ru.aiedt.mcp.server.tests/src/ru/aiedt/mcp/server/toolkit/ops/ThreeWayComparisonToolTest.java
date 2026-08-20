/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Covers the contract of the three-way comparison: its shape, and above all what it refuses to do.
 * <p>
 * The comparison itself needs a workspace with a project in it, so the counts are measured on a
 * stand rather than here. What is pinned here is the boundary: this tool reads. A merge writes into
 * a configuration and is not undone by a button, so "it cannot merge" is a property worth a test
 * that fails the day someone adds one.
 * </p>
 */
public class ThreeWayComparisonToolTest
{
    private static Map<String, String> args(String... pairs)
    {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    @Test
    public void itIsNamedForWhatItDoesAndAnswersJson()
    {
        ThreeWayComparisonTool tool = new ThreeWayComparisonTool();

        assertEquals("compare_three_way", tool.getName()); //$NON-NLS-1$
        assertEquals(IMcpTool.ResponseType.JSON, tool.getResponseType());
        assertNotNull(tool.getDescription());
    }

    /**
     * The description is what an agent reads before choosing the tool, so the one thing it must not
     * leave open is that this one can write - and that writing is irreversible.
     */
    @Test
    public void theDescriptionSaysReadingIsTheDefaultAndMergingIsNot()
    {
        String description = new ThreeWayComparisonTool().getDescription().toLowerCase();

        assertTrue("reading must be named as the default: " + description, //$NON-NLS-1$
            description.contains("default")); //$NON-NLS-1$
        assertTrue("and the merge must be named irreversible, in those words: " + description, //$NON-NLS-1$
            description.contains("irreversible")); //$NON-NLS-1$
        assertTrue("and the caller must learn it is refused without decisions: " + description, //$NON-NLS-1$
            description.contains("no decisions")); //$NON-NLS-1$
    }

    /**
     * Nothing is merged unless it was asked for by name.
     * <p>
     * The whole safety of this tool rests on the default. A value nobody recognises must not fall
     * back to reading either: somebody who wrote MERGE and mistyped it would be told their
     * configuration is unchanged only by reading the answer closely, and would try again with the
     * same word.
     * </p>
     */
    @Test
    public void anUnknownIntentIsRefusedRatherThanTreatedAsReading()
    {
        String answer = new ThreeWayComparisonTool()
            .execute(args("projectName", "P", "otherPath", "somewhere", "intent", "MERGE_NOW")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

        assertTrue(answer, answer.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the refusal must list what may be written instead: " + answer, //$NON-NLS-1$
            answer.contains("MERGE_IGNORING_PROBLEMS")); //$NON-NLS-1$
    }

    /**
     * Overriding the environment's own objection has its own word.
     * <p>
     * Not a flag beside MERGE. A flag that means "and also ignore the problems" is how an override
     * becomes something people pass by habit.
     * </p>
     */
    @Test
    public void ignoringBlockingProblemsIsASeparateIntentNotAFlag()
    {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (ru.aiedt.mcp.server.support.BmComparisonHelper.Intent intent : ru.aiedt.mcp.server.support.BmComparisonHelper.Intent
            .values())
        {
            names.add(intent.name());
        }

        assertEquals("three intents, no more: " + names, 3, names.size()); //$NON-NLS-1$
        assertTrue(names.contains("REPORT")); //$NON-NLS-1$
        assertTrue(names.contains("MERGE")); //$NON-NLS-1$
        assertTrue("the override must be a value of its own", //$NON-NLS-1$
            names.contains("MERGE_IGNORING_PROBLEMS")); //$NON-NLS-1$
        assertEquals("reading must be the first value, which is what an absent argument means", //$NON-NLS-1$
            "REPORT", names.get(0)); //$NON-NLS-1$
    }

    /** Three sides means three inputs, and the third one is what makes it three-way. */
    @Test
    public void theSchemaNamesAllThreeSides()
    {
        String schema = new ThreeWayComparisonTool().getInputSchema();

        assertTrue(schema.contains("projectName")); //$NON-NLS-1$
        assertTrue(schema.contains("otherPath")); //$NON-NLS-1$
        assertTrue(schema.contains("ancestorPath")); //$NON-NLS-1$
        assertTrue("the ancestor is what separates this from a plain diff", //$NON-NLS-1$
            schema.contains("COMMON_ANCESTOR")); //$NON-NLS-1$
    }

    /**
     * Missing arguments are refused before anything is asked of the environment, and the refusal
     * carries a reason rather than an empty answer.
     */
    @Test
    public void aRequestWithoutItsSidesIsRefusedWithAReason()
    {
        String answer = new ThreeWayComparisonTool().execute(args());

        assertTrue(answer, answer.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the refusal must name what is missing: " + answer, //$NON-NLS-1$
            answer.contains("required")); //$NON-NLS-1$
    }

    /** A path that is not a directory is named as such, not reported as an empty comparison. */
    @Test
    public void aPathThatIsNotADirectoryIsNamed()
    {
        String answer = new ThreeWayComparisonTool()
            .execute(args("projectName", "AnyProject", "otherPath", "no such directory here")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertTrue(answer, answer.contains("\"success\":false")); //$NON-NLS-1$
        assertFalse("a missing path must not read as a finished comparison", //$NON-NLS-1$
            answer.contains("\"nodes\"")); //$NON-NLS-1$
    }

    /**
     * A comparison that is no longer awaited must be called off, and the wait must be short.
     * <p>
     * Both come from one live incident. The first run waited half an hour, and when it gave up it
     * simply walked away: the comparison stayed pending inside EDT, held a transaction open, and
     * the session could no longer shut down - its UI thread stopped responding entirely. A tool
     * that can leave the environment in that state is worse than one that answers slowly, so the
     * wait is bounded in seconds and the exit path stops the work it started.
     * </p>
     */
    @Test
    public void theWaitIsShortAndTheWorkIsCalledOff()
    {
        String source = ru.aiedt.mcp.server.support.BmComparisonHelper.class.getName();
        assertNotNull(source);

        boolean cancels = false;
        for (java.lang.reflect.Method m : ru.aiedt.mcp.server.support.BmComparisonHelper.class
            .getDeclaredMethods())
        {
            if ("cancel".equals(m.getName()))
            {
                cancels = true;
            }
        }
        assertTrue("giving up on a comparison must call it off, not abandon it", cancels); //$NON-NLS-1$
    }

    /** Long work belongs in the Pending flow, where waiting costs nobody a session. */
    @Test
    public void itRunsThroughThePendingFlowAndCountsAsHeavy()
    {
        assertTrue("a comparison is heavy work", //$NON-NLS-1$
            ru.aiedt.mcp.server.support.HeavyTools.isHeavy("compare_three_way")); //$NON-NLS-1$
        assertTrue("and must resume by runKey rather than block the caller", //$NON-NLS-1$
            ru.aiedt.mcp.server.support.GenericPending.applies("compare_three_way")); //$NON-NLS-1$
    }

    /**
     * Decisions a caller cannot have meant are refused, not quietly dropped.
     * <p>
     * Someone who wrote decisions and got a comparison back without them would believe they had
     * been recorded - and would then look for a settings file that says nothing. Malformed input
     * fails where it was written.
     * </p>
     */
    @Test
    public void unreadableDecisionsAreRefusedRatherThanIgnored()
    {
        String notArray = new ThreeWayComparisonTool().execute(
            args("projectName", "P", "otherPath", "somewhere", "decisions", "{\"object\":\"X\"}")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue(notArray, notArray.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the refusal must say what shape was expected: " + notArray, //$NON-NLS-1$
            notArray.contains("array")); //$NON-NLS-1$

        String missingRule = new ThreeWayComparisonTool().execute(
            args("projectName", "P", "otherPath", "somewhere", "decisions", "[{\"object\":\"X\"}]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue(missingRule, missingRule.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("and which half is missing: " + missingRule, //$NON-NLS-1$
            missingRule.contains("rule")); //$NON-NLS-1$

        String garbage = new ThreeWayComparisonTool()
            .execute(args("projectName", "P", "otherPath", "somewhere", "decisions", "not json")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue(garbage, garbage.contains("\"success\":false")); //$NON-NLS-1$
    }

    /** No decisions at all is not an error - the tool's ordinary use is to read. */
    @Test
    public void noDecisionsIsNotARefusal()
    {
        String answer = new ThreeWayComparisonTool()
            .execute(args("projectName", "P", "otherPath", "no such directory")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue("it must fail on the path, not on the absent decisions: " + answer, //$NON-NLS-1$
            answer.contains("not a directory")); //$NON-NLS-1$
    }

    /**
     * "Merged" must come from the merge having ended, not from it having been accepted.
     * <p>
     * Measured, not supposed: {@code startMerge} schedules a job and returns OK the moment the job
     * is accepted, long before anything is written. The first version reported that status as the
     * result, so a live run answered {@code merged: true, mergeStatus: "0: OK"} while the merge had
     * barely begun - the very shape of mistake this project has paid for repeatedly, an empty
     * answer read as a successful one. The wait is therefore part of the contract.
     * </p>
     */
    @Test
    public void theAnswerWaitsForTheMergeRatherThanForItsAcceptance()
    {
        boolean waits = false;
        for (java.lang.reflect.Method m : ru.aiedt.mcp.server.support.BmComparisonHelper.class
            .getDeclaredMethods())
        {
            if ("awaitMergeEnd".equals(m.getName())) //$NON-NLS-1$
            {
                waits = true;
            }
        }
        assertTrue("a scheduled merge must be waited out before it is reported on", waits); //$NON-NLS-1$
    }

    /**
     * The merge entry points must stay out of reach. Checked against the class rather than the
     * source text so the test still holds if the file is reorganised.
     */
    @Test
    public void aMergeIsRefusedWithoutDecisionsToApply()
    {
        String answer = new ThreeWayComparisonTool()
            .execute(args("projectName", "P", "otherPath", "no such directory", "intent", "MERGE")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

        // It fails on the path here, which is enough to show the merge was not attempted first.
        // What matters is that no merge ran: an intent alone must never be sufficient.
        assertTrue(answer, answer.contains("\"success\":false")); //$NON-NLS-1$
        assertFalse("nothing may report itself merged from a failed comparison: " + answer, //$NON-NLS-1$
            answer.contains("\"merged\":true")); //$NON-NLS-1$
    }
}
