/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Before;
import org.junit.Test;

/**
 * Verifies the self_status aggregation: nearest-rank percentiles and the per-tool
 * grouping of durations and success/failure counts over the call history.
 */
public class McpHistoryStatsTest
{
    private static long asLong(Object o)
    {
        return ((Number) o).longValue();
    }

    private static int asInt(Object o)
    {
        return ((Number) o).intValue();
    }

    @Before
    public void reset()
    {
        McpHistory.clear();
    }

    @Test
    public void percentileNearestRank()
    {
        long[] a = {10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
        assertEquals(50, McpHistory.percentile(a, 50)); // ceil(5.0)=5 -> idx 4
        assertEquals(90, McpHistory.percentile(a, 90)); // ceil(9.0)=9 -> idx 8
        assertEquals(100, McpHistory.percentile(a, 95)); // ceil(9.5)=10 -> idx 9
        assertEquals(100, McpHistory.percentile(a, 99)); // ceil(9.9)=10 -> idx 9
        assertEquals(10, McpHistory.percentile(a, 1)); // ceil(0.1)=1 -> idx 0
    }

    @Test
    public void percentileEdgeCases()
    {
        assertEquals(0, McpHistory.percentile(new long[0], 50));
        assertEquals(42, McpHistory.percentile(new long[] {42}, 50));
        assertEquals(42, McpHistory.percentile(new long[] {42}, 99));
    }

    @Test
    public void perToolStatsGroupsDurationsAndOutcomes()
    {
        McpHistory.record("toolA", "", "", 100, true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        McpHistory.record("toolA", "", "", 300, true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        McpHistory.record("toolA", "", "", 200, false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        McpHistory.record("toolB", "", "", 50, true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Map<String, Map<String, Object>> stats = McpHistory.perToolStats();
        assertTrue(stats.containsKey("toolA")); //$NON-NLS-1$
        assertTrue(stats.containsKey("toolB")); //$NON-NLS-1$

        Map<String, Object> a = stats.get("toolA"); //$NON-NLS-1$
        assertEquals("count", 3, asInt(a.get("count"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("max", 300, asLong(a.get("maxMs"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("successCount", 2, asInt(a.get("successCount"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("failCount", 1, asInt(a.get("failCount"))); //$NON-NLS-1$ //$NON-NLS-2$
        // durations sorted [100,200,300]; p50 rank ceil(1.5)=2 -> idx 1 -> 200.
        assertEquals("p50", 200, asLong(a.get("p50Ms"))); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, Object> b = stats.get("toolB"); //$NON-NLS-1$
        assertEquals("count", 1, asInt(b.get("count"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("p50", 50, asLong(b.get("p50Ms"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("failCount", 0, asInt(b.get("failCount"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void perToolStatsPercentilesOverManyCalls()
    {
        // Durations 10,20,...,100 for one tool -> p50=50, p95=100, p99=100, max=100.
        for (int i = 1; i <= 10; i++)
        {
            McpHistory.record("t", "", "", i * 10L, true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        Map<String, Object> s = McpHistory.perToolStats().get("t"); //$NON-NLS-1$
        assertEquals("count", 10, asInt(s.get("count"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("p50", 50, asLong(s.get("p50Ms"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("p95", 100, asLong(s.get("p95Ms"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("p99", 100, asLong(s.get("p99Ms"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("max", 100, asLong(s.get("maxMs"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void perToolStatsEmptyWhenNoCalls()
    {
        assertTrue(McpHistory.perToolStats().isEmpty());
    }

    @Test
    public void thereIsNoLastCallBeforeAnythingIsCalled()
    {
        // The trim bar reads this on a timer from the moment the workbench opens, long before any
        // agent connects, so the empty buffer has to answer rather than blow up.
        assertNull(McpHistory.lastCall());
    }

    @Test
    public void theLastCallIsTheMostRecentOne()
    {
        McpHistory.record("first_tool", "", "", 10L, true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        McpHistory.record("second_tool", "", "", 10L, true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        McpHistory.LastCall last = McpHistory.lastCall();

        assertNotNull(last);
        assertEquals("second_tool", last.toolName); //$NON-NLS-1$
    }

    @Test
    public void aFailedCallIsStillTheLastCall()
    {
        // What ran last is what ran last; the trim bar reports activity, not success.
        McpHistory.record("ok_tool", "", "", 10L, true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        McpHistory.record("broken_tool", "", "exception: boom", 10L, false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("broken_tool", McpHistory.lastCall().toolName); //$NON-NLS-1$
    }

    @Test
    public void theLastCallCarriesAUsableTimestamp()
    {
        long before = System.currentTimeMillis();
        McpHistory.record("t", "", "", 10L, true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        long after = System.currentTimeMillis();

        long stamp = McpHistory.lastCall().timestamp;

        // The tooltip subtracts this from "now" to say how long ago it was, so a stamp outside the
        // window the call actually happened in would show a nonsensical age.
        assertTrue("stamp " + stamp + " outside [" + before + ", " + after + "]", //$NON-NLS-1$
            stamp >= before && stamp <= after);
    }

    @Test
    public void theLastCallSurvivesTheBufferFillingUp()
    {
        int depth = McpHistory.capacity();
        for (int i = 0; i < depth + 5; i++)
        {
            McpHistory.record("tool_" + i, "", "", 1L, true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        // Eviction takes from the front; the newest entry must never be the one dropped.
        assertEquals("tool_" + (depth + 4), McpHistory.lastCall().toolName); //$NON-NLS-1$
    }
}
