/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.workbench;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * What the user is told when the strip's own Start or Restart fails.
 * <p>
 * The log receives the stack trace, and nobody reads the log: what the user is left with is a strip
 * reading "off" and a menu item that appears to have done nothing at all. The endpoint's own words
 * are the actionable part - the ports that were tried, and the instance holding one of them - so
 * they travel into the notice rather than being replaced by a generic line.
 * </p>
 */
public class TheStripTellsWhyTheServerDidNotStartTest
{
    /** What a start that found no port hands back. */
    private static final String NO_PORT =
        "MCP server could not bind any port in 12250-12259. Held by: 12253 - workspace. " //$NON-NLS-1$
            + "Widen the span or change the port in preferences."; //$NON-NLS-1$

    @Test
    public void aStartThatFoundNoPortReachesTheUserWithTheReason()
    {
        List<String> shown = new ArrayList<>();

        McpStatusBarItem.reportServerControlFailure("start", new IOException(NO_PORT), shown::add); //$NON-NLS-1$

        assertEquals("the user gets one notice, not none and not several", 1, shown.size()); //$NON-NLS-1$
        assertTrue("the notice has to carry what the endpoint said: " + shown, //$NON-NLS-1$
            shown.get(0).contains("12250-12259")); //$NON-NLS-1$
        assertTrue("the reason the user can act on has to survive: " + shown, //$NON-NLS-1$
            shown.get(0).contains("12253 - workspace")); //$NON-NLS-1$
    }

    @Test
    public void theNoticeNamesWhatWasBeingDone()
    {
        List<String> restarted = new ArrayList<>();
        List<String> started = new ArrayList<>();

        McpStatusBarItem.reportServerControlFailure("restart", new IOException(NO_PORT), restarted::add); //$NON-NLS-1$
        McpStatusBarItem.reportServerControlFailure("start", new IOException(NO_PORT), started::add); //$NON-NLS-1$

        assertTrue(restarted.get(0), restarted.get(0).contains("restart")); //$NON-NLS-1$
        assertTrue(started.get(0), started.get(0).contains("start")); //$NON-NLS-1$
    }
}
