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
 * Unified configuration-insight facade with nine operations.
 *
 * <p>Collapses the read-only analysis and reporting tools under one name:
 * <ul>
 *   <li>{@code project_metrics} - LOC, methods, modules and technical-debt metrics for a
 *       project or subsystem (delegates to {@link ProjectMetricsTool})</li>
 *   <li>{@code dependency_graph} - BFS dependency graph between metadata objects or modules
 *       (delegates to {@link DependencyGraphTool})</li>
 *   <li>{@code compare_configurations} - diff two projects or two Designer-XML exports
 *       (delegates to {@link CompareConfigurationsTool})</li>
 *   <li>{@code detect_query_anti_patterns} - scan queries for known performance
 *       anti-patterns (delegates to {@link DetectQueryAntiPatternsTool})</li>
 *   <li>{@code generate_health_snapshot} - composite errors+metadata+metrics+anti-patterns
 *       snapshot in one call (delegates to {@link GenerateHealthSnapshotTool})</li>
 *   <li>{@code impact_analysis} - blast-radius analysis before a destructive change
 *       (delegates to {@link ImpactAnalysisTool})</li>
 *   <li>{@code object_summary} - metadata+modules+methods summary for one object
 *       (delegates to {@link ObjectSummaryTool})</li>
 *   <li>{@code semantic_metadata_search} - free-text search over object names, synonyms
 *       and comments (delegates to {@link SemanticMetadataSearchTool})</li>
 *   <li>{@code help} - built-in topic-driven help</li>
 * </ul>
 *
 * <p>Each operation routes to its standalone tool unchanged - params pass through as-is,
 * and the standalone tools stay registered for back-compat. Every absorbed standalone
 * answers as JSON except impact_analysis (MARKDOWN); this facade always answers as
 * MARKDOWN, the safest wrapper: it carries any string body regardless of the routed
 * tool's own native response type. An agent that needs a JSON-typed result
 * (structuredContent) should call the standalone directly - the same tradeoff
 * {@code code_search} and the other facades accept. All eight operations are read-only:
 * this facade needs no preset-gating.
 */
public class InsightsFacadeTool implements IMcpTool
{
    public static final String NAME = "insights"; //$NON-NLS-1$

    private static final Map<String, String> OPS = buildOpsCatalog();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Configuration insight and analysis - metrics, dependency graph, config " //$NON-NLS-1$
            + "comparison, query anti-patterns, health snapshot, impact analysis, object " //$NON-NLS-1$
            + "summary, database tables of an object, semantic metadata search. Operations: " //$NON-NLS-1$
            + "project_metrics, dependency_graph, compare_configurations, " //$NON-NLS-1$
            + "detect_query_anti_patterns, generate_health_snapshot, impact_analysis, " //$NON-NLS-1$
            + "object_summary, describe_db_tables, semantic_metadata_search, help. Pass " //$NON-NLS-1$
            + "operation=<name> (snake_case canonical; camelCase like projectMetrics is also " //$NON-NLS-1$
            + "accepted); remaining parameters follow the per-operation contracts (call " //$NON-NLS-1$
            + "operation=help for the catalog). All nine operations are read-only. The " //$NON-NLS-1$
            + "standalone tools remain available for back-compat."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", //$NON-NLS-1$
                "project_metrics / dependency_graph / compare_configurations / " //$NON-NLS-1$
                    + "detect_query_anti_patterns / generate_health_snapshot / " //$NON-NLS-1$
                    + "impact_analysis / object_summary / describe_db_tables / " //$NON-NLS-1$
                    + "semantic_metadata_search / help " //$NON-NLS-1$
                    + "(snake_case canonical; camelCase like projectMetrics is also " //$NON-NLS-1$
                    + "accepted). Pass operation=help without other params for the operation " //$NON-NLS-1$
                    + "catalog.", true) //$NON-NLS-1$
            .stringProperty("topic", //$NON-NLS-1$
                "Help topic when operation=help. Without topic - lists all operations with " //$NON-NLS-1$
                    + "one-line summaries.") //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name. Required for every operation except help; for " //$NON-NLS-1$
                    + "compare_configurations this is the FIRST project (the second comes " //$NON-NLS-1$
                    + "from target).") //$NON-NLS-1$
            .stringProperty("objectFqn", //$NON-NLS-1$
                "FQN of the object in question, e.g. 'Catalog.Products'. Russian type names " //$NON-NLS-1$
                    + "supported. Required for impact_analysis, object_summary and " //$NON-NLS-1$
                    + "describe_db_tables; used by " //$NON-NLS-1$
                    + "dependency_graph when scope=object and by compare_configurations when " //$NON-NLS-1$
                    + "scope=objectFqn. describe_db_tables also takes the four-segment address " //$NON-NLS-1$
                    + "of a table of an external data source, " //$NON-NLS-1$
                    + "'ExternalDataSource.Src.Table.Orders'; the other operations expect the " //$NON-NLS-1$
                    + "two-segment form.") //$NON-NLS-1$
            .stringProperty("scope", //$NON-NLS-1$
                "project_metrics: project / subsystem (default project). dependency_graph: " //$NON-NLS-1$
                    + "project / subsystem / object / module (default project). " //$NON-NLS-1$
                    + "detect_query_anti_patterns: project / module / method (default " //$NON-NLS-1$
                    + "project). compare_configurations: project / objectType / objectFqn " //$NON-NLS-1$
                    + "(default project). Different valid values per operation.") //$NON-NLS-1$
            .stringProperty("subsystemName", //$NON-NLS-1$
                "Subsystem name when scope=subsystem (project_metrics, dependency_graph).") //$NON-NLS-1$
            .stringProperty("moduleFqn", //$NON-NLS-1$
                "Module FQN when scope=module (dependency_graph) or scope=module/method " //$NON-NLS-1$
                    + "(detect_query_anti_patterns).") //$NON-NLS-1$
            .stringProperty("level", //$NON-NLS-1$
                "dependency_graph: metadata / modules / mixed (default metadata) - what the " //$NON-NLS-1$
                    + "graph nodes are. compare_configurations: object / attribute / module / " //$NON-NLS-1$
                    + "template (default object) - granularity of the diff. Same key, " //$NON-NLS-1$
                    + "different meaning per operation.") //$NON-NLS-1$
            .integerProperty("depth", //$NON-NLS-1$
                "dependency_graph: BFS depth, 1-5 (default 2).") //$NON-NLS-1$
            .stringProperty("direction", //$NON-NLS-1$
                "dependency_graph: in / out / both (default both).") //$NON-NLS-1$
            .integerProperty("maxNodes", //$NON-NLS-1$
                "dependency_graph: cap on nodes visited by the BFS (default 200).") //$NON-NLS-1$
            .integerProperty("maxEdges", //$NON-NLS-1$
                "dependency_graph: cap on edges returned (default 500).") //$NON-NLS-1$
            .stringProperty("mode", //$NON-NLS-1$
                "compare_configurations: projects / files. Required for that operation.") //$NON-NLS-1$
            .stringProperty("target", //$NON-NLS-1$
                "compare_configurations: for mode=projects, the second project's name; for " //$NON-NLS-1$
                    + "mode=files, the path to the second export. Required for that " //$NON-NLS-1$
                    + "operation.") //$NON-NLS-1$
            .booleanProperty("showRenames", //$NON-NLS-1$
                "compare_configurations: detect renames via structural similarity (default " //$NON-NLS-1$
                    + "true).") //$NON-NLS-1$
            .stringProperty("severity_filter", //$NON-NLS-1$
                "detect_query_anti_patterns: info / warning / error / all (default " //$NON-NLS-1$
                    + "warning).") //$NON-NLS-1$
            .stringProperty("rules", //$NON-NLS-1$
                "detect_query_anti_patterns: comma-separated rule names (default all): " //$NON-NLS-1$
                    + "SELECT_STAR, NO_WHERE_ON_LARGE_TABLE, VIRTUAL_TABLE_PARAMS, " //$NON-NLS-1$
                    + "CROSS_JOIN_NO_CONDITION, NESTED_QUERY_DEPTH, SUBQUERY_IN_SELECT, " //$NON-NLS-1$
                    + "QUERY_IN_LOOP.") //$NON-NLS-1$
            .booleanProperty("includeAntiPatterns", //$NON-NLS-1$
                "generate_health_snapshot: include the anti-pattern scan, slower (default " //$NON-NLS-1$
                    + "true).") //$NON-NLS-1$
            .booleanProperty("includeMetrics", //$NON-NLS-1$
                "generate_health_snapshot: include LOC/methods/modules metrics, slower " //$NON-NLS-1$
                    + "(default true).") //$NON-NLS-1$
            .booleanProperty("includeMetadata", //$NON-NLS-1$
                "generate_health_snapshot: include metadata object counts per type (default " //$NON-NLS-1$
                    + "true).") //$NON-NLS-1$
            .booleanProperty("includeErrors", //$NON-NLS-1$
                "generate_health_snapshot: include the errors/warnings summary (default " //$NON-NLS-1$
                    + "true). object_summary: run get_project_errors to count validation " //$NON-NLS-1$
                    + "problems (default true). Same key, two operations.") //$NON-NLS-1$
            .stringProperty("action", //$NON-NLS-1$
                "impact_analysis: planned action - delete / rename / modify. Optional, " //$NON-NLS-1$
                    + "influences the recommendation text only.") //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "impact_analysis: maximum references per category (default 100). " //$NON-NLS-1$
                    + "semantic_metadata_search: maximum results (default 50). Same key, two " //$NON-NLS-1$
                    + "operations.") //$NON-NLS-1$
            .booleanProperty("skipBsl", //$NON-NLS-1$
                "impact_analysis: skip BSL code references, metadata back-references only " //$NON-NLS-1$
                    + "(default false).") //$NON-NLS-1$
            .booleanProperty("includeReferences", //$NON-NLS-1$
                "object_summary: run find_references (skipBsl=true) to count metadata " //$NON-NLS-1$
                    + "back-references (default true).") //$NON-NLS-1$
            .booleanProperty("includeFields", //$NON-NLS-1$
                "describe_db_tables: include the fields and virtual-table parameters " //$NON-NLS-1$
                    + "(default true). Pass false for table names and field counts alone.") //$NON-NLS-1$
            .stringProperty("query", //$NON-NLS-1$
                "semantic_metadata_search: free-text query, matched case-insensitively " //$NON-NLS-1$
                    + "against name, synonym and comment of every object. Required for that " //$NON-NLS-1$
                    + "operation.") //$NON-NLS-1$
            .stringProperty("metadataType", //$NON-NLS-1$
                "semantic_metadata_search: optional filter by type (English singular: " //$NON-NLS-1$
                    + "Catalog / Document / InformationRegister / ... or Russian " //$NON-NLS-1$
                    + "equivalent).") //$NON-NLS-1$
            .stringProperty("format", //$NON-NLS-1$
                "project_metrics, compare_configurations and detect_query_anti_patterns: " //$NON-NLS-1$
                    + "json (default) or markdown. dependency_graph: json (default) / " //$NON-NLS-1$
                    + "mermaid / plantuml / dot.") //$NON-NLS-1$
            .booleanProperty("includeDebtList", //$NON-NLS-1$
                "project_metrics: include the detailed debt items list with file:line " //$NON-NLS-1$
                    + "(default false).") //$NON-NLS-1$
            .integerProperty("timeoutSeconds", //$NON-NLS-1$
                "project_metrics: timeout cap in seconds (default 60).") //$NON-NLS-1$
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
            return ToolResult.error("operation is required. Allowed: project_metrics / " //$NON-NLS-1$
                + "dependency_graph / compare_configurations / detect_query_anti_patterns / " //$NON-NLS-1$
                + "generate_health_snapshot / impact_analysis / object_summary / " //$NON-NLS-1$
                + "describe_db_tables / semantic_metadata_search / help.").toJson(); //$NON-NLS-1$
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
            case "project_metrics": //$NON-NLS-1$
                return new ProjectMetricsTool().execute(params);
            case "dependency_graph": //$NON-NLS-1$
                return new DependencyGraphTool().execute(params);
            case "compare_configurations": //$NON-NLS-1$
                return new CompareConfigurationsTool().execute(params);
            case "detect_query_anti_patterns": //$NON-NLS-1$
                return new DetectQueryAntiPatternsTool().execute(params);
            case "generate_health_snapshot": //$NON-NLS-1$
                return new GenerateHealthSnapshotTool().execute(params);
            case "impact_analysis": //$NON-NLS-1$
                return new ImpactAnalysisTool().execute(params);
            case "object_summary": //$NON-NLS-1$
                return new ObjectSummaryTool().execute(params);
            case "describe_db_tables": //$NON-NLS-1$
                return new DbTablesReader().execute(params);
            case "semantic_metadata_search": //$NON-NLS-1$
                return new SemanticMetadataSearchTool().execute(params);
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
            sb.append("# insights - operations\n\n"); //$NON-NLS-1$
            sb.append("- **project_metrics** - LOC, methods, modules and technical-debt " //$NON-NLS-1$
                + "metrics for a project or subsystem.\n"); //$NON-NLS-1$
            sb.append("- **dependency_graph** - BFS dependency graph between metadata " //$NON-NLS-1$
                + "objects or modules.\n"); //$NON-NLS-1$
            sb.append("- **compare_configurations** - diff two projects or two " //$NON-NLS-1$
                + "Designer-XML exports.\n"); //$NON-NLS-1$
            sb.append("- **detect_query_anti_patterns** - scan queries for known " //$NON-NLS-1$
                + "performance anti-patterns.\n"); //$NON-NLS-1$
            sb.append("- **generate_health_snapshot** - composite errors+metadata+" //$NON-NLS-1$
                + "metrics+anti-patterns snapshot in one call.\n"); //$NON-NLS-1$
            sb.append("- **impact_analysis** - blast-radius analysis before a destructive " //$NON-NLS-1$
                + "change (deep find_references plus a severity tier).\n"); //$NON-NLS-1$
            sb.append("- **object_summary** - metadata+modules+methods summary for one " //$NON-NLS-1$
                + "object.\n"); //$NON-NLS-1$
            sb.append("- **describe_db_tables** - the database tables one object turns into " //$NON-NLS-1$
                + "and the fields of each, in both languages.\n"); //$NON-NLS-1$
            sb.append("- **semantic_metadata_search** - free-text search over object " //$NON-NLS-1$
                + "names, synonyms and comments.\n"); //$NON-NLS-1$
            sb.append("- **help** - this catalog. Pass topic=workflow for the " //$NON-NLS-1$
                + "operation-picker guide.\n"); //$NON-NLS-1$
            return sb.toString();
        }
        if ("workflow".equals(topic)) //$NON-NLS-1$
        {
            StringBuilder sb = new StringBuilder();
            sb.append("# insights - operation picker\n\n"); //$NON-NLS-1$
            sb.append("| Goal | Operation |\n"); //$NON-NLS-1$
            sb.append("|------|-----------|\n"); //$NON-NLS-1$
            sb.append("| How big/complex is this project or subsystem | project_metrics |\n"); //$NON-NLS-1$
            sb.append("| What depends on what | dependency_graph |\n"); //$NON-NLS-1$
            sb.append("| What changed between two configurations | " //$NON-NLS-1$
                + "compare_configurations |\n"); //$NON-NLS-1$
            sb.append("| Are there slow-query patterns in the code | " //$NON-NLS-1$
                + "detect_query_anti_patterns |\n"); //$NON-NLS-1$
            sb.append("| One-call overview before starting work on a project | " //$NON-NLS-1$
                + "generate_health_snapshot |\n"); //$NON-NLS-1$
            sb.append("| Is it safe to delete/rename/modify this object | " //$NON-NLS-1$
                + "impact_analysis |\n"); //$NON-NLS-1$
            sb.append("| Everything about one object in one call | object_summary |\n"); //$NON-NLS-1$
            sb.append("| What may I select from this object, and which fields | " //$NON-NLS-1$
                + "describe_db_tables |\n"); //$NON-NLS-1$
            sb.append("| Find an object by what it is, not what it is called | " //$NON-NLS-1$
                + "semantic_metadata_search |\n"); //$NON-NLS-1$
            return sb.toString();
        }
        return "# Unknown topic '" + topic + "'.\n\nAvailable: workflow.\n"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Map<String, String> buildOpsCatalog()
    {
        Map<String, String> m = new LinkedHashMap<>();
        for (String op : Arrays.asList(
            "project_metrics", "dependency_graph", //$NON-NLS-1$ //$NON-NLS-2$
            "compare_configurations", "detect_query_anti_patterns", //$NON-NLS-1$ //$NON-NLS-2$
            "generate_health_snapshot", "impact_analysis", //$NON-NLS-1$ //$NON-NLS-2$
            "object_summary", "describe_db_tables", //$NON-NLS-1$ //$NON-NLS-2$
            "semantic_metadata_search")) //$NON-NLS-1$
        {
            m.put(op, op);
        }
        return Collections.unmodifiableMap(m);
    }
}
