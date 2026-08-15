/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.LinkedHashMap;
import java.util.Map;

import ru.aiedt.mcp.server.McpHistory;
import ru.aiedt.mcp.server.support.HeapHeadroom;
import ru.aiedt.mcp.server.support.PendingWorkRegistry;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Reports the server's own runtime diagnostics: per-tool call timing percentiles
 * and success/failure counts from the in-memory call history, plus the number of
 * in-flight async (Pending) operations per domain.
 * <p>
 * Read-only, and a complement to the HTTP {@code /health} liveness endpoint: that
 * one answers "is the server up and the workspace ready" for a monitor, this one
 * answers "which tools are slow or failing, and is async work piling up" for the
 * agent, as a normal tool call.
 */
public class SelfStatusTool
    implements IMcpTool
{
    @Override
    public String getName()
    {
        return "self_status"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Server self-diagnostics: per-tool duration percentiles (p50/p95/p99), call " //$NON-NLS-1$
            + "counts and success/failure counts over the recent-call history, per Pending " //$NON-NLS-1$
            + "domain the count of async operations still running and the count tracked (running " //$NON-NLS-1$
            + "plus completed-but-not-yet-collected), and the heap EDT still holds after a " //$NON-NLS-1$
            + "collection against the share at which expensive tools start being refused - check " //$NON-NLS-1$
            + "it when pacing a long series of expensive calls. Read-only, takes no arguments."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object().build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        return ToolResult.success()
            .put("historySize", McpHistory.size()) //$NON-NLS-1$
            .put("historyCapacity", McpHistory.CAPACITY) //$NON-NLS-1$
            .put("tools", McpHistory.perToolStats()) //$NON-NLS-1$
            .put("pending", pendingCounts()) //$NON-NLS-1$
            .put("heap", heapReading()) //$NON-NLS-1$
            .toJson();
    }

    /**
     * How much of the heap is still held after a collection, and how much of it may be held before
     * expensive tools are turned away.
     * <p>
     * Here because an agent pacing a long series of expensive calls has no other way to see the heap
     * filling up: without it, the first news of trouble is the server going quiet altogether.
     * </p>
     *
     * @return the reading, as held megabytes, the ceiling, the share in use and the refusal threshold
     */
    private static Map<String, Object> heapReading()
    {
        HeapHeadroom.Reading reading = HeapHeadroom.current();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("heldMb", reading.getRetainedMegabytes()); //$NON-NLS-1$
        m.put("liveMb", reading.getLiveMegabytes()); //$NON-NLS-1$
        m.put("ceilingMb", reading.getCeilingMegabytes()); //$NON-NLS-1$
        m.put("freeMb", reading.getFreeMegabytes()); //$NON-NLS-1$
        m.put("percent", reading.getPercentUsed()); //$NON-NLS-1$
        m.put("livePercent", reading.getLivePercent()); //$NON-NLS-1$
        m.put("measuredAfterCollection", reading.isTrustworthy()); //$NON-NLS-1$
        m.put("refusalPercent", HeapHeadroom.refusalPercent()); //$NON-NLS-1$
        return m;
    }

    /**
     * Per Pending domain, the count of async operations still {@code running} and
     * the count {@code tracked} (running plus completed-but-not-yet-collected),
     * plus the totals across domains.
     */
    private static Map<String, Object> pendingCounts()
    {
        Map<String, Object> m = new LinkedHashMap<>();
        int totalRunning = 0;
        int totalTracked = 0;
        totalRunning += putDomain(m, "update_database", PendingWorkRegistry.UPDATE); //$NON-NLS-1$
        totalRunning += putDomain(m, "export_object", PendingWorkRegistry.EXPORT); //$NON-NLS-1$
        totalRunning += putDomain(m, "find_references", PendingWorkRegistry.REFERENCES); //$NON-NLS-1$
        totalRunning += putDomain(m, "generic", PendingWorkRegistry.GENERIC); //$NON-NLS-1$
        totalRunning += putDomain(m, "import_configuration_from_binary", //$NON-NLS-1$
            PendingWorkRegistry.IMPORT_BINARY);
        totalTracked = PendingWorkRegistry.UPDATE.size() + PendingWorkRegistry.EXPORT.size()
            + PendingWorkRegistry.REFERENCES.size() + PendingWorkRegistry.GENERIC.size()
            + PendingWorkRegistry.IMPORT_BINARY.size();
        m.put("totalRunning", totalRunning); //$NON-NLS-1$
        m.put("totalTracked", totalTracked); //$NON-NLS-1$
        return m;
    }

    /** Records {@code {running, tracked}} for one domain and returns its running count. */
    private static int putDomain(Map<String, Object> into, String key, PendingWorkRegistry domain)
    {
        int running = domain.runningCount();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("running", running); //$NON-NLS-1$
        d.put("tracked", domain.size()); //$NON-NLS-1$
        into.put(key, d);
        return running;
    }
}

