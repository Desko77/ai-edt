/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.settings.HistorySettings;

/**
 * Covers what one recorded call carries beyond its name and its timing.
 * <p>
 * Specifically whether the reader can tell a short answer from a long one cut down to the same size.
 * Without that they cannot: both arrive as a string of exactly the kept length, and a person reading
 * the history would conclude the tool answered briefly when in fact the setting decided what they
 * were allowed to see.
 * </p>
 */
public class McpHistoryRecordTest
{
    @Before
    public void emptyTheBuffer()
    {
        McpHistory.clear();
    }

    @Test
    public void anAnswerWithinTheExtentIsMarkedWhole()
    {
        McpHistory.record("get_edt_version", "", "2026.2.0", 5L, true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Map<String, Object> call = newest();
        assertEquals("2026.2.0", call.get("result")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.FALSE, call.get("argsCut")); //$NON-NLS-1$
        assertEquals("2026.2.0".length(), ((Number)call.get("resultChars")).intValue()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aLongAnswerKeepsTheExtentAndReportsItsTrueSize()
    {
        int extent = HistorySettings.current().resultChars();
        String answer = repeat('r', extent * 3);

        McpHistory.record("read_module_source", "", answer, 5L, true); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, Object> call = newest();
        String kept = (String)call.get("result"); //$NON-NLS-1$
        assertTrue(kept.length() < answer.length());
        assertTrue(kept.startsWith(repeat('r', extent)));
        // The true size, not the kept size: this is what tells the reader they are seeing a part.
        assertEquals(answer.length(), ((Number)call.get("resultChars")).intValue()); //$NON-NLS-1$
    }

    @Test
    public void longArgumentsAreMarkedAsCut()
    {
        int extent = HistorySettings.current().argChars();

        McpHistory.record("write_module_source", repeat('a', extent * 2), "ok", 5L, true); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.TRUE, newest().get("argsCut")); //$NON-NLS-1$

        McpHistory.record("get_edt_version", "x", "ok", 5L, true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(Boolean.FALSE, newest().get("argsCut")); //$NON-NLS-1$
    }

    @Test
    public void theBufferStopsAtTheDepthInForce()
    {
        int depth = HistorySettings.current().depth();

        for (int i = 0; i < depth + 25; i++)
        {
            McpHistory.record("some_tool", "", "ok", 1L, true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        assertEquals(depth, McpHistory.size());
        assertEquals(depth, McpHistory.capacity());
    }

    @Test
    public void aFailedCallIsRecordedTooAndIsMarkedAsSuchInTheStats()
    {
        McpHistory.record("broken_tool", "", "exception: boom", 3L, false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Map<String, Object> stats = McpHistory.stats();
        assertEquals(Integer.valueOf(1), stats.get("failure")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(0), stats.get("success")); //$NON-NLS-1$
        assertFalse(Boolean.TRUE.equals(newest().get("success"))); //$NON-NLS-1$
    }

    @Test
    public void argumentsShortenedInsideTheSummaryAreStillReportedAsCut()
    {
        // What the router does when one value is too long: the summary comes out WELL under the
        // extent, so nothing about its length says a value was lost. Only the caller knows.
        McpHistory.record("edit_metadata", "code=Procedure...", true, "ok", 5L, true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Map<String, Object> call = newest();
        assertEquals(Boolean.TRUE, call.get("argsCut")); //$NON-NLS-1$
        assertTrue(((String)call.get("args")).length() < HistorySettings.current().argChars()); //$NON-NLS-1$
    }

    @Test
    public void aShortSummaryTheCallerCallsWholeStaysWhole()
    {
        McpHistory.record("get_edt_version", "verbose=false", false, "ok", 5L, true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(Boolean.FALSE, newest().get("argsCut")); //$NON-NLS-1$
    }

    @Test
    public void keepingNoCharactersKeepsNothingRatherThanAnEllipsis()
    {
        assertEquals("", McpHistory.truncate("a long answer nobody asked to keep", 0)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("", McpHistory.truncate("x", 0)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("", McpHistory.truncate("", 0)); //$NON-NLS-1$ //$NON-NLS-2$
        // A null value has nothing to keep and nothing to say about it.
        assertEquals(null, McpHistory.truncate(null, 0));
    }

    private static Map<String, Object> newest()
    {
        List<Map<String, Object>> recent = McpHistory.recent(1);
        assertEquals(1, recent.size());
        return recent.get(0);
    }

    private static String repeat(char c, int times)
    {
        return String.valueOf(c).repeat(times);
    }
}
