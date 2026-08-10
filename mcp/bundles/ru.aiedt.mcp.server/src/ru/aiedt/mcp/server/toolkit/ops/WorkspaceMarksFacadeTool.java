/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.support.ToolGate;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Unified workspace-marks facade with four operations.
 *
 * <p>Collapses the plugin's own tagging feature and the two IDE marker kinds under one
 * name:
 * <ul>
 *   <li>{@code get_tags} - every tag defined in a project (delegates to
 *       {@link TagsReader})</li>
 *   <li>{@code get_objects_by_tags} - metadata objects carrying one or more of the given
 *       tags (delegates to {@link TaggedObjectsReader})</li>
 *   <li>{@code get_bookmarks} - Eclipse bookmarks (delegates to {@link BookmarksReader})</li>
 *   <li>{@code get_tasks} - TODO/FIXME task markers (delegates to {@link TasksReader})</li>
 *   <li>{@code help} - built-in topic-driven help</li>
 * </ul>
 *
 * <p>Each operation routes to its standalone tool unchanged - params pass through as-is,
 * and the standalone tools stay registered for back-compat. get_tags and
 * get_objects_by_tags answer as MARKDOWN already; get_bookmarks and get_tasks default to
 * MARKDOWN too (neither overrides {@link IMcpTool#getResponseType()}), so this facade's
 * own MARKDOWN response type matches every operation it routes to - unlike the other
 * facades, there is no JSON-response standalone hiding behind it. All four operations are
 * read-only: this facade needs no preset-gating.
 */
public class WorkspaceMarksFacadeTool implements IMcpTool
{
    public static final String NAME = "workspace_marks"; //$NON-NLS-1$

    private static final Map<String, String> OPS = buildOpsCatalog();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Workspace marks - project tags, objects by tag, bookmarks, and " //$NON-NLS-1$
            + "TODO/FIXME task markers. Operations: get_tags, get_objects_by_tags, " //$NON-NLS-1$
            + "get_bookmarks, get_tasks, help. Pass operation=<name> (snake_case " //$NON-NLS-1$
            + "canonical; camelCase like getTags is also accepted); remaining parameters " //$NON-NLS-1$
            + "follow the per-operation contracts (call operation=help for the catalog). " //$NON-NLS-1$
            + "All four operations are read-only. The standalone tools remain available " //$NON-NLS-1$
            + "for back-compat."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", //$NON-NLS-1$
                "get_tags / get_objects_by_tags / get_bookmarks / get_tasks / help " //$NON-NLS-1$
                    + "(snake_case canonical; camelCase like getTags is also accepted). " //$NON-NLS-1$
                    + "Pass operation=help without other params for the operation " //$NON-NLS-1$
                    + "catalog.", true) //$NON-NLS-1$
            .stringProperty("topic", //$NON-NLS-1$
                "Help topic when operation=help. Without topic - lists all operations with " //$NON-NLS-1$
                    + "one-line summaries.") //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name. Required for get_tags and get_objects_by_tags; optional " //$NON-NLS-1$
                    + "filter for get_bookmarks and get_tasks (omitted searches every " //$NON-NLS-1$
                    + "project).") //$NON-NLS-1$
            .stringArrayProperty("tags", //$NON-NLS-1$
                "get_objects_by_tags: tag names to filter on, e.g. ['Important', " //$NON-NLS-1$
                    + "'NeedsReview']. Objects matching at least one listed tag are " //$NON-NLS-1$
                    + "returned. Required for that operation.") //$NON-NLS-1$
            .stringProperty("filePath", //$NON-NLS-1$
                "get_bookmarks and get_tasks: keep only rows whose path contains this " //$NON-NLS-1$
                    + "substring (optional).") //$NON-NLS-1$
            .stringProperty("priority", //$NON-NLS-1$
                "get_tasks: keep only this priority - high / normal / low (optional).") //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "get_objects_by_tags: cap per tag (default 100). get_bookmarks and " //$NON-NLS-1$
                    + "get_tasks: cap on rows returned (default 100, max 1000).") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String operation = JsonUtils.extractStringArgument(params, "operation"); //$NON-NLS-1$
        if (operation == null || operation.isBlank())
        {
            return ToolResult.error("operation is required. Allowed: get_tags / " //$NON-NLS-1$
                + "get_objects_by_tags / get_bookmarks / get_tasks / help.").toJson(); //$NON-NLS-1$
        }
        operation = JsonUtils.normalizeOperationToken(operation);
        if ("help".equals(operation)) //$NON-NLS-1$
        {
            return buildHelp(JsonUtils.extractStringArgument(params, "topic")); //$NON-NLS-1$
        }
        if (!OPS.containsKey(operation))
        {
            return ToolResult.error("Unknown operation '" + operation //$NON-NLS-1$
                + "'. Allowed: " + String.join(" / ", OPS.keySet()) //$NON-NLS-1$ //$NON-NLS-2$
                + " / help.").toJson(); //$NON-NLS-1$
        }
        // One gate for every operation this facade folds in. Reaching a tool through a facade is
        // still reaching that tool, and a preset that switched it off means it. Keyed on the
        // operation name because that IS the folded tool's name; an operation with no tool of its
        // own is in nobody's disabled set and passes straight through.
        String presetGate = ToolGate.gateIfPresetDisabled(operation);
        if (presetGate != null)
        {
            return ToolResult.error(presetGate).put("operation", operation).toJson(); //$NON-NLS-1$
        }
        switch (operation)
        {
            case "get_tags": //$NON-NLS-1$
                return new TagsReader().execute(params);
            case "get_objects_by_tags": //$NON-NLS-1$
                return new TaggedObjectsReader().execute(params);
            case "get_bookmarks": //$NON-NLS-1$
                return new BookmarksReader().execute(params);
            case "get_tasks": //$NON-NLS-1$
                return new TasksReader().execute(params);
            default:
                return ToolResult.error("Unhandled operation: " + operation).toJson(); //$NON-NLS-1$
        }
    }

    private static String buildHelp(String topic)
    {
        topic = JsonUtils.normalizeOperationToken(topic);
        if (topic == null || topic.isEmpty())
        {
            StringBuilder sb = new StringBuilder();
            sb.append("# workspace_marks - operations\n\n"); //$NON-NLS-1$
            sb.append("- **get_tags** - every tag defined in a project.\n"); //$NON-NLS-1$
            sb.append("- **get_objects_by_tags** - metadata objects carrying one or more " //$NON-NLS-1$
                + "of the given tags.\n"); //$NON-NLS-1$
            sb.append("- **get_bookmarks** - Eclipse bookmarks, optionally filtered by " //$NON-NLS-1$
                + "path.\n"); //$NON-NLS-1$
            sb.append("- **get_tasks** - TODO/FIXME task markers, optionally filtered by " //$NON-NLS-1$
                + "path or priority.\n"); //$NON-NLS-1$
            sb.append("- **help** - this catalog. Pass topic=workflow for the " //$NON-NLS-1$
                + "operation-picker guide.\n"); //$NON-NLS-1$
            return sb.toString();
        }
        if ("workflow".equals(topic)) //$NON-NLS-1$
        {
            StringBuilder sb = new StringBuilder();
            sb.append("# workspace_marks - operation picker\n\n"); //$NON-NLS-1$
            sb.append("| Goal | Operation |\n"); //$NON-NLS-1$
            sb.append("|------|-----------|\n"); //$NON-NLS-1$
            sb.append("| What tags exist in this project | get_tags |\n"); //$NON-NLS-1$
            sb.append("| Which objects carry a given tag | get_objects_by_tags |\n"); //$NON-NLS-1$
            sb.append("| What has the user bookmarked | get_bookmarks |\n"); //$NON-NLS-1$
            sb.append("| Where are the TODO/FIXME comments | get_tasks |\n"); //$NON-NLS-1$
            return sb.toString();
        }
        return "# Unknown topic '" + topic + "'.\n\nAvailable: workflow.\n"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Map<String, String> buildOpsCatalog()
    {
        Map<String, String> m = new LinkedHashMap<>();
        for (String op : Arrays.asList(
            "get_tags", "get_objects_by_tags", //$NON-NLS-1$ //$NON-NLS-2$
            "get_bookmarks", "get_tasks")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            m.put(op, op);
        }
        return Collections.unmodifiableMap(m);
    }
}
