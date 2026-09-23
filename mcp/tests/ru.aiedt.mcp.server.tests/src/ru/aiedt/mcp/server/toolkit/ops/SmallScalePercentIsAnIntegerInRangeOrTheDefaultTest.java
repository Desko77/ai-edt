/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * check_print_width reads its smallScalePercent the way the operation parses it.
 * <p>
 * A non-numeric or fractional value must not turn into the default: a caller who typed a word or a
 * half-number asked for a threshold, and the refusal they get for 5 or 101 is the one they get for
 * "seventy" too. The call goes through the operation itself, so what is tested is the argument
 * parsing the server runs, not a copy of it; a value that parses gets past the refusal and on to
 * the next check the call fails on - the project names it never sent.
 * </p>
 */
public class SmallScalePercentIsAnIntegerInRangeOrTheDefaultTest
{
    private static final String REFUSAL = "smallScalePercent must be between 10 and 100"; //$NON-NLS-1$

    private static String answerOf(String value)
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "check_print_width"); //$NON-NLS-1$ //$NON-NLS-2$
        if (value != null)
        {
            params.put("smallScalePercent", value); //$NON-NLS-1$
        }
        return new MxlWorkshopTool().execute(params);
    }

    @Test
    public void anAbsentArgumentIsNotRefused()
    {
        assertFalse(answerOf(null).contains(REFUSAL));
    }

    @Test
    public void anIntegerInsideTheRangeIsNotRefused()
    {
        assertFalse(answerOf("75").contains(REFUSAL)); //$NON-NLS-1$
        assertFalse("the boundaries are in range", answerOf("10").contains(REFUSAL)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(answerOf("100").contains(REFUSAL)); //$NON-NLS-1$
    }

    @Test
    public void aNumberOutsideTheRangeIsRefused()
    {
        assertTrue(answerOf("9").contains(REFUSAL)); //$NON-NLS-1$
        assertTrue(answerOf("101").contains(REFUSAL)); //$NON-NLS-1$
    }

    @Test
    public void aNonIntegerIsRefusedRatherThanDefaulted()
    {
        assertTrue("a word", answerOf("seventy").contains(REFUSAL)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a fraction", answerOf("12.5").contains(REFUSAL)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("an empty string", answerOf("").contains(REFUSAL)); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
