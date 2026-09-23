/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

/**
 * The unlimited budget is the full-text store's, and its per-value share is computed without
 * overflowing: an int product overflows past the budget's half, the share reads as zero, and the
 * minimum takes over - so the whole argument the store was promised was stored cut to eighty
 * characters.
 */
public class TheUnlimitedArgumentBudgetKeepsTheWholeArgumentTest
{
    /** An argument longer than the minimum reaches the full-text store whole. */
    @Test
    public void aLongArgumentIsKeptWholeAtTheUnlimitedBudget()
    {
        String value = "v".repeat(300);
        McpHistory.ArgsSummary whole = McpHistory.summarizeArguments(Map.of("code", value), //$NON-NLS-1$
            Integer.MAX_VALUE);
        assertFalse("nothing is cut at the unlimited budget", whole.cut); //$NON-NLS-1$
        assertTrue("the whole argument is kept: " + whole.text, whole.text.endsWith(value)); //$NON-NLS-1$
    }

    /** A bounded budget still caps one value's share of it. */
    @Test
    public void aBoundedBudgetStillCapsEachValue()
    {
        String value = "v".repeat(300);
        McpHistory.ArgsSummary summary = McpHistory.summarizeArguments(Map.of("code", value), 500); //$NON-NLS-1$
        assertTrue("a bounded budget still caps one value's share", summary.cut); //$NON-NLS-1$
        assertFalse(summary.text, summary.text.contains(value));
    }
}
