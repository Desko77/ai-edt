/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.support.EventLogReader;

/**
 * Pins what a call to {@code read_event_log} carries into the reader.
 * <p>
 * The mapping is read here rather than through a call, because a call needs a workspace and a
 * registered infobase and this half of the tool does not. It is the half worth pinning on its own:
 * an argument the schema advertises and no filter reads is a promise the server does not keep, and
 * the call it breaks succeeds anyway.
 * </p>
 */
public class TheEventLogQueryIsBuiltFromTheCallTest
{
    /**
     * The arguments a call carries, as the router hands them over.
     *
     * @param pairs name and value, in pairs.
     * @return the map.
     */
    private static Map<String, String> call(String... pairs)
    {
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            params.put(pairs[i], pairs[i + 1]);
        }
        return params;
    }

    /** Every declared filter reaches the query. */
    @Test
    public void everyFilterReachesTheQuery()
    {
        EventLogReader.Query q = EventLogTool.queryFrom(call(
            "from", "2026-03-01", //$NON-NLS-1$ //$NON-NLS-2$
            "to", "2026-03-02", //$NON-NLS-1$ //$NON-NLS-2$
            "event", "Session", //$NON-NLS-1$ //$NON-NLS-2$
            "user", "Auditor", //$NON-NLS-1$ //$NON-NLS-2$
            "severity", "E", //$NON-NLS-1$ //$NON-NLS-2$
            "limit", "7")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("2026-03-01", q.from); //$NON-NLS-1$
        assertEquals("2026-03-02", q.to); //$NON-NLS-1$
        assertEquals("Session", q.event); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Auditor", q.user); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("E", q.severity); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(7, q.limit);
    }

    /** A call that names no filters is read with the reader's own defaults. */
    @Test
    public void aCallWithNoFiltersKeepsTheDefaults()
    {
        EventLogReader.Query q = EventLogTool.queryFrom(call());

        assertEquals("", q.from); //$NON-NLS-1$
        assertEquals("", q.event); //$NON-NLS-1$
        assertEquals(100, q.limit);
        assertTrue("nothing was named, so nothing may be excluded", q.excludeEvents.isEmpty()); //$NON-NLS-1$
    }

    /** The exclusion list arrives in either spelling a client may use. */
    @Test
    public void theExclusionListArrivesAsAnArrayOrAsATextList()
    {
        EventLogReader.Query asArray = EventLogTool.queryFrom(call(
            "excludeEvents", "[\"_$Transaction$_*\",\"_$Session$_.Finish\"]")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, asArray.excludeEvents.size());
        assertEquals("_$Transaction$_*", asArray.excludeEvents.get(0)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("_$Session$_.Finish", asArray.excludeEvents.get(1)); //$NON-NLS-1$ //$NON-NLS-2$

        EventLogReader.Query asText = EventLogTool.queryFrom(call(
            "excludeEvents", "_$Transaction$_*, _$Session$_.Finish")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(asArray.excludeEvents, asText.excludeEvents);
    }
}
