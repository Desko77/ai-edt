/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * An exception breakpoint breaks on any error or on a given error text: catchAll=false without a
 * message would break on nothing, and is refused before anything is created.
 */
public class AnExceptionBreakpointFiltersOnSomethingTest
{
    /**
     * Only catchAll=false with no message is refused.
     */
    @Test
    public void onlyAFilterOnNothingIsRefused()
    {
        assertNull(SetExceptionBreakpointTool.filterRefusal(true, false));
        assertNull(SetExceptionBreakpointTool.filterRefusal(true, true));
        assertNull(SetExceptionBreakpointTool.filterRefusal(false, true));
        String refusal = SetExceptionBreakpointTool.filterRefusal(false, false);
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("message")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("No breakpoint was set")); //$NON-NLS-1$
    }

    /**
     * The tool refuses catchAll=false with a blank message before resolving the project, so the
     * call names the combination rather than a missing project.
     */
    @Test
    public void theToolRefusesBeforeResolvingTheProject()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "NoSuchProject_" + System.nanoTime()); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("catchAll", "false"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("message", "   "); //$NON-NLS-1$ //$NON-NLS-2$
        String answer = new SetExceptionBreakpointTool().execute(params);
        assertTrue(answer, answer.contains("catchAll=false")); //$NON-NLS-1$
        assertFalse(answer, answer.contains("\"success\":true")); //$NON-NLS-1$
    }
}
