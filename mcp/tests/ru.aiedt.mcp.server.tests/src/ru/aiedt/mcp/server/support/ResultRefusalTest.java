/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Holds the stable refusal codes a client branches on, and the fact that every reason has one.
 */
public class ResultRefusalTest
{
    @Test
    public void everyReasonHasItsOwnCode()
    {
        for (ResultRefusal.Reason reason : ResultRefusal.Reason.values())
        {
            ResultRefusal refusal = new ResultRefusal(reason, "message"); //$NON-NLS-1$
            assertEquals(reason, refusal.reason());
            assertTrue("a code must be snake_case: " + refusal.code(), //$NON-NLS-1$
                refusal.code().matches("[a-z_]+")); //$NON-NLS-1$
        }
    }

    @Test
    public void theShortFormRefusalSaysWhatToDo()
    {
        ResultRefusal refusal = new ResultRefusal(
            ResultRefusal.Reason.SHORT_FORM_NO_LONGER_STORED,
            "repeat the original call"); //$NON-NLS-1$
        assertEquals("short_form_selection_evicted", refusal.code()); //$NON-NLS-1$
        assertTrue(refusal.getMessage().contains("repeat")); //$NON-NLS-1$
    }

    @Test(expected = IllegalArgumentException.class)
    public void aRefusalWithoutAReasonIsRefused()
    {
        new ResultRefusal(null, "message"); //$NON-NLS-1$
    }
}
