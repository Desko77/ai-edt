/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.ops.ThreeWayComparisonTool;

/**
 * A rule taken out of a parameter's description is answered by the help instead of being lost.
 * <p>
 * The schema travels to every client and stays there for the whole conversation, so it carries one
 * sentence and the values a caller picks from. The rules behind those values - when a mode refuses,
 * what it does to each group of objects, what a measurement showed - are answered when asked for.
 * That only holds while the help actually carries them, which is what this pins: the sentences
 * below are in one place or the other, and this says which.
 * </p>
 */
public class WhatTheSchemaStoppedSayingTheHelpSaysTest
{
    private static String help()
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("help", "parameters"); //$NON-NLS-1$ //$NON-NLS-2$
        return new ThreeWayComparisonTool().execute(params);
    }

    /**
     * The detail of an irreversible mode is where a caller can reach it.
     * <p>
     * Asserted on text without an apostrophe: the answer is a JSON document, and the serializer
     * escapes an apostrophe to {@code \\u0027}, so a phrase carrying one is in the answer and does
     * not read as present.
     * </p>
     */
    @Test
    public void theRulesOfTheMergeModesAreInTheHelp()
    {
        String answer = help();
        assertTrue("what MERGE_IGNORING_PROBLEMS is for", //$NON-NLS-1$
            answer.contains("should not share a word with ordinary merging")); //$NON-NLS-1$
        assertTrue("when UPDATE_UNCHANGED is refused", //$NON-NLS-1$
            answer.contains("refused unless the comparison is three-sided")); //$NON-NLS-1$
        assertTrue("what UPDATE_KEEPING_OURS does to objects both sides changed", //$NON-NLS-1$
            answer.contains("mergedWithDelivery")); //$NON-NLS-1$
        assertTrue("and what a scope cannot reach", //$NON-NLS-1$
            answer.contains("closureBlindSpots")); //$NON-NLS-1$
    }

    /** And it is no longer in the schema every client carries. */
    @Test
    public void theSchemaCarriesTheValuesAndNotTheRules()
    {
        String schema = new ThreeWayComparisonTool().getInputSchema();
        assertTrue("the values a caller picks from stay", //$NON-NLS-1$
            schema.contains("UPDATE_KEEPING_OURS") && schema.contains("MERGE_IGNORING_PROBLEMS")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("and that one of them cannot be undone", schema.contains("IRREVERSIBLE")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the rules behind them do not", //$NON-NLS-1$
            schema.contains("should not share a word with ordinary merging")); //$NON-NLS-1$
        assertFalse("nor the reach of a scope", schema.contains("closureBlindSpots")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The help still says what the parameter itself is, not only the rules behind it. */
    @Test
    public void theDescriptionAndItsRulesArriveTogether()
    {
        String answer = help();
        assertTrue("the sentence from the schema", //$NON-NLS-1$
            answer.contains("What the run does. REPORT (default) changes nothing")); //$NON-NLS-1$
        assertTrue("and the rules that continue it", //$NON-NLS-1$
            answer.contains("MERGE applies the decisions to the project")); //$NON-NLS-1$
    }
}
