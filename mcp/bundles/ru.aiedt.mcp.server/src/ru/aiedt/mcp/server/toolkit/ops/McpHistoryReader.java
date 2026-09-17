/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Map;

import ru.aiedt.mcp.server.McpHistory;
import ru.aiedt.mcp.server.settings.HistorySettings;
import ru.aiedt.mcp.server.support.HistoryFullText;
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
            .stringProperty("entryId", //$NON-NLS-1$
                "Read the FULL text of one call instead of the list: the id a history entry " //$NON-NLS-1$
                    + "carries. The buffer keeps a shortened copy; the full arguments and answer " //$NON-NLS-1$
                    + "live on disk while the retention allows. One entry per call - there is no " //$NON-NLS-1$
                    + "bulk form, because a page of full answers does not fit one response.") //$NON-NLS-1$
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
        int limit = (limitRaw != null && limitRaw > 0) ? Math.min(limitRaw, McpHistory.capacity()) : 50;
        boolean includeStats = JsonUtils.extractBooleanArgument(params, "includeStats", true); //$NON-NLS-1$
        boolean clear = JsonUtils.extractBooleanArgument(params, "clear", false); //$NON-NLS-1$
        String entryId = JsonUtils.extractStringArgument(params, "entryId"); //$NON-NLS-1$
        if (entryId != null && !entryId.isEmpty())
        {
            return oneEntry(entryId, clear);
        }
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

    /**
     * The full text of one call, read from the on-disk store.
     *
     * @param entryId the id the caller named.
     * @param clear whether the caller also asked to clear, which cannot be combined with reading.
     * @return the answer, ready to return
     */
    private static String oneEntry(String entryId, boolean clear)
    {
        if (clear)
        {
            return ToolResult.error("entryId and clear cannot be used in one call: reading a record " //$NON-NLS-1$
                + "and deleting the records have no order between them that would be safe to " //$NON-NLS-1$
                + "assume. Read first, clear second.") //$NON-NLS-1$
                .toJson();
        }
        Map<String, Object> stored = HistoryFullText.read(entryId, HistorySettings.current());
        if (stored == null)
        {
            return ToolResult.error("No call with that entryId is in the store. It may have been " //$NON-NLS-1$
                + "cleared, aged out of the retention, or pushed out by the size limit; the " //$NON-NLS-1$
                + "shortened copy in the buffer is all there is.") //$NON-NLS-1$
                .put("entryId", entryId) //$NON-NLS-1$
                .toJson();
        }
        ToolResult result = ToolResult.success()
            .put("operation", "get_mcp_history") //$NON-NLS-1$ //$NON-NLS-2$
            .put("entry", stored); //$NON-NLS-1$
        String problem = HistoryFullText.pathProblem();
        if (problem != null)
        {
            result.put("storageWarning", problem); //$NON-NLS-1$
        }
        return result.toJson();
    }
}
