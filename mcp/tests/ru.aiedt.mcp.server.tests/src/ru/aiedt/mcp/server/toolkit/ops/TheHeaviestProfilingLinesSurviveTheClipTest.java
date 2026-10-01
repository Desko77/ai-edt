/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * A profiling module with more lines than the cap keeps its hot spots, not its beginning.
 * <p>
 * The profiler lists lines in its own order, so a clip by arrival order answers "the first 200
 * lines of the module" while the caller asked where the time went. The clip sorts by total time,
 * breaks ties by call count, and the answer says how many lines there were and how many were
 * dropped.
 * </p>
 */
public class TheHeaviestProfilingLinesSurviveTheClipTest
{
    /**
     * Builds one line entry the way the reader does: a line number, a call count and a time.
     *
     * @param line the line number
     * @param calls how often the line ran
     * @param dur the line's total time
     * @return the entry
     */
    private static Map<String, Object> line(int line, long calls, double dur)
    {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("line", line); //$NON-NLS-1$
        entry.put("calls", calls); //$NON-NLS-1$
        entry.put("dur", dur); //$NON-NLS-1$
        return entry;
    }

    /** The heaviest line survives wherever the profiler listed it. */
    @Test
    public void theHeaviestLineSurvivesEvenWhenListedLast()
    {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 1; i <= 250; i++)
        {
            lines.add(line(i, 1, 1.0));
        }
        lines.add(line(999, 1, 500.0));

        List<Map<String, Object>> kept = ProfilingResultsReader.clipLinesByWeight(lines, 200);

        assertEquals(200, kept.size());
        assertTrue("the hot spot is kept", kept.contains(line(999, 1, 500.0))); //$NON-NLS-1$
        assertEquals("the heaviest line comes first", //$NON-NLS-1$
            Integer.valueOf(999), kept.get(0).get("line")); //$NON-NLS-1$
    }

    /** Two lines that took the same time are ordered by how often they ran. */
    @Test
    public void anEqualTimeIsBrokenByTheCallCount()
    {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 1; i <= 250; i++)
        {
            lines.add(line(i, 1, 1.0));
        }
        lines.add(line(901, 10, 100.0));
        lines.add(line(902, 5, 100.0));

        List<Map<String, Object>> kept = ProfilingResultsReader.clipLinesByWeight(lines, 200);

        assertEquals(Integer.valueOf(901), kept.get(0).get("line")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(902), kept.get(1).get("line")); //$NON-NLS-1$
    }

    /** A module inside the cap is reported as it was read. */
    @Test
    public void aModuleInsideTheCapIsKeptWhole()
    {
        List<Map<String, Object>> lines = new ArrayList<>();
        lines.add(line(1, 1, 1.0));
        lines.add(line(2, 1, 9.0));

        assertSame(lines, ProfilingResultsReader.clipLinesByWeight(lines, 200));
    }
}
