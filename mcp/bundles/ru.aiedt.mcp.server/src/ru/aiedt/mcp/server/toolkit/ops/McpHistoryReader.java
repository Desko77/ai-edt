/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Map;

import ru.aiedt.mcp.server.McpHistory;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Surfaces the recent MCP tool calls captured by {@link McpHistory} - what filled the agent's
 * context (which tools ran, with what arguments, how long, success/failure). Observability for
 * long sessions; the buffer is in-memory and bounded, never persisted.
 */
public final class McpHistoryReader implements IMcpTool
{
    public static final String NAME = "get_mcp_history"; //$NON-NLS-1$

    private static final String DESC =
        "Recent MCP tool calls on this EDT-MCP server (observability): which tools ran, " //$NON-NLS-1$
            + "with what arguments (truncated), duration and success/failure - what filled the " //$NON-NLS-1$
            + "agent's context. Bounded in-memory ring buffer (last 200), never persisted. " //$NON-NLS-1$
            + "Optional clear=true empties the buffer after reading."; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return DESC;
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .integerProperty("limit", "Max entries to return, newest first (default 50, max 200).", false) //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("includeStats", "Include aggregate counts (default true).", false) //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("clear", "Clear the buffer after reading (default false).", false) //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        Integer limitRaw = JsonUtils.extractIntegerArgument(params, "limit"); //$NON-NLS-1$
        int limit = (limitRaw != null && limitRaw > 0) ? Math.min(limitRaw, McpHistory.CAPACITY) : 50;
        boolean includeStats = JsonUtils.extractBooleanArgument(params, "includeStats", true); //$NON-NLS-1$
        boolean clear = JsonUtils.extractBooleanArgument(params, "clear", false); //$NON-NLS-1$
        ToolResult result = ToolResult.success()
            .put("operation", "get_mcp_history") //$NON-NLS-1$ //$NON-NLS-2$
            .put("history", McpHistory.recent(limit)); //$NON-NLS-1$
        if (includeStats)
        {
            result.put("stats", McpHistory.stats()); //$NON-NLS-1$
        }
        if (clear)
        {
            McpHistory.clear();
            result.put("cleared", true); //$NON-NLS-1$
        }
        return result.toJson();
    }
}
