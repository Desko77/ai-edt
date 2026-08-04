/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import ru.aiedt.mcp.server.wire.GsonHolder;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.ProjectResolver;
import com.google.gson.JsonElement;

/**
 * 1.40.x: One-shot project health snapshot - aggregates results from multiple
 * analysis tools into a single JSON document so an AI agent can survey the
 * project without making 8+ round-trips.
 * <p>
 * Combines:
 * <ul>
 *   <li>Errors / warnings ({@code get_project_errors} - severity counts)</li>
 *   <li>Anti-patterns ({@code detect_query_anti_patterns} - top 10 hits)</li>
 *   <li>Metrics ({@code project_metrics} - modules/methods/LOC totals)</li>
 *   <li>Metadata stats ({@code get_metadata_objects} - counts per type)</li>
 *   <li>Configuration metadata ({@code get_configuration_properties})</li>
 * </ul>
 * <p>
 * The aggregator catches per-section failures and surfaces them as
 * {@code <section>Error} fields in the response so a partial outage of one
 * sub-tool doesn't break the rest of the snapshot.
 */
public class GenerateHealthSnapshotTool implements IMcpTool
{
    public static final String NAME = "generate_health_snapshot"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `insights` `operation=generate_health_snapshot`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Comprehensive project health snapshot in one call. " //$NON-NLS-1$
            + "Aggregates errors, anti-patterns, metrics, metadata stats, " //$NON-NLS-1$
            + "and configuration info into a single JSON document."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project to work in", true) //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("includeAntiPatterns", //$NON-NLS-1$
                "Include anti-pattern scan (slower, default true)") //$NON-NLS-1$
            .booleanProperty("includeMetrics", //$NON-NLS-1$
                "Include LOC/methods/modules metrics (slower, default true)") //$NON-NLS-1$
            .booleanProperty("includeMetadata", //$NON-NLS-1$
                "Include metadata object counts per type (default true)") //$NON-NLS-1$
            .booleanProperty("includeErrors", //$NON-NLS-1$
                "Include errors/warnings summary (default true)") //$NON-NLS-1$
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
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        boolean includeAntiPatterns = JsonUtils.extractBooleanArgument(params, "includeAntiPatterns", true);
        boolean includeMetrics = JsonUtils.extractBooleanArgument(params, "includeMetrics", true);
        boolean includeMetadata = JsonUtils.extractBooleanArgument(params, "includeMetadata", true);
        boolean includeErrors = JsonUtils.extractBooleanArgument(params, "includeErrors", true);

        long startTime = System.currentTimeMillis();
        ToolResult result = ToolResult.success()
            .put("operation", NAME)
            .put("projectName", projectName)
            .put("timestamp", java.time.Instant.now().toString());

        // 1. Configuration properties (always included - lightweight)
        try
        {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("projectName", projectName);
            String json = new ConfigurationInfoReader().execute(p);
            result.put("configuration", parseJsonOrRaw(json));
        }
        catch (Exception e)
        {
            result.put("configurationError", e.getMessage());
        }

        // 2. Errors summary
        if (includeErrors)
        {
            try
            {
                Map<String, String> p = new LinkedHashMap<>();
                p.put("projectName", projectName);
                p.put("scope", "project");
                p.put("severityFilter", "summary"); // sentinel - falls through to default
                String json = new ProblemSummaryReader().execute(p);
                result.put("errors", parseJsonOrRaw(json));
            }
            catch (Exception e)
            {
                result.put("errorsError", e.getMessage());
            }
        }

        // 3. Metadata counts
        if (includeMetadata)
        {
            try
            {
                Map<String, String> p = new LinkedHashMap<>();
                p.put("projectName", projectName);
                p.put("groupByType", "true"); // hint to formatter, ignored if not supported
                String json = new MetadataObjectsReader().execute(p);
                result.put("metadata", parseJsonOrRaw(json));
            }
            catch (Exception e)
            {
                result.put("metadataError", e.getMessage());
            }
        }

        // 4. Project metrics (LOC, methods, modules)
        if (includeMetrics)
        {
            try
            {
                Map<String, String> p = new LinkedHashMap<>();
                p.put("projectName", projectName);
                p.put("topN", "0"); // counts only, no per-object listing
                String json = new ProjectMetricsTool().execute(p);
                result.put("metrics", parseJsonOrRaw(json));
            }
            catch (Exception e)
            {
                result.put("metricsError", e.getMessage());
            }
        }

        // 5. Anti-patterns scan (heaviest - last)
        if (includeAntiPatterns)
        {
            try
            {
                Map<String, String> p = new LinkedHashMap<>();
                p.put("projectName", projectName);
                p.put("limit", "10");
                String json = new DetectQueryAntiPatternsTool().execute(p);
                result.put("antiPatterns", parseJsonOrRaw(json));
            }
            catch (Exception e)
            {
                result.put("antiPatternsError", e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        result.put("elapsedMs", elapsed);
        result.put("hint", "Use individual tools (get_project_errors, project_metrics, "
            + "detect_query_anti_patterns, etc.) for deeper drill-down on any "
            + "section flagged here.");
        return result.toJson();
    }

    /**
     * Best-effort JSON-string passthrough. Sub-tools return JSON strings; we
     * want to nest them as structured objects inside our wrapper. Gson can
     * serialise {@link JsonElement} values back into the parent JSON without
     * escaping, so we parse the sub-result into {@code JsonElement} when it
     * looks like JSON. Otherwise we return the raw string (it will be
     * escaped on the wire - acceptable fallback for plain text).
     */
    private Object parseJsonOrRaw(String maybeJson)
    {
        if (maybeJson == null)
        {
            return null;
        }
        String trimmed = maybeJson.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            try
            {
                return GsonHolder.fromJson(trimmed, JsonElement.class);
            }
            catch (Exception e)
            {
                return maybeJson;
            }
        }
        return maybeJson;
    }
}
