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
 * Unified project-diagnostics facade with six operations.
 *
 * <p>Collapses the validation and health-check tools under one name so an
 * agent has a single door onto "what is wrong with this project":
 * <ul>
 *   <li>{@code get_project_errors} - configuration problems, filtered and
 *       scoped (delegates to {@link ProjectProblemsReader})</li>
 *   <li>{@code get_problem_summary} - problem counts by severity and project
 *       (delegates to {@link ProblemSummaryReader})</li>
 *   <li>{@code revalidate_objects} - revalidate the whole project or named
 *       objects (delegates to {@link ObjectsRevalidator})</li>
 *   <li>{@code clean_project} - clean build plus full revalidation (delegates
 *       to {@link ProjectCleaner})</li>
 *   <li>{@code validate_for_export} - pre-export XDTO/XML source-file scan
 *       (delegates to {@link ValidateForExportTool})</li>
 *   <li>{@code get_check_description} - documentation for one EDT check
 *       (delegates to {@link CheckDocReader})</li>
 *   <li>{@code help} - built-in topic-driven help</li>
 * </ul>
 *
 * <p>Each operation routes to its standalone tool unchanged - params pass
 * through as-is, and the standalone tools stay registered for back-compat.
 * This facade always answers as MARKDOWN, the safest wrapper: it carries any
 * string body regardless of the routed tool's own native response type. An
 * agent that needs a JSON-typed result (structuredContent) from one of the
 * JSON-response standalones (revalidate_objects, clean_project,
 * validate_for_export) should call that standalone directly - the same
 * tradeoff {@code code_search} accepts for the same reason.
 */
public class DiagnosticsFacadeTool implements IMcpTool
{
    public static final String NAME = "diagnostics"; //$NON-NLS-1$

    private static final Map<String, String> OPS = buildOpsCatalog();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Project validation and health - errors, problems, revalidation, cleaning, " //$NON-NLS-1$
            + "export-readiness and check docs. Operations: get_project_errors, " //$NON-NLS-1$
            + "get_problem_summary, revalidate_objects, clean_project, validate_for_export, " //$NON-NLS-1$
            + "get_check_description, help. Pass operation=<name> (snake_case canonical; " //$NON-NLS-1$
            + "camelCase like getProjectErrors is also accepted); remaining parameters follow " //$NON-NLS-1$
            + "the per-operation contracts (call operation=help for the catalog). The standalone " //$NON-NLS-1$
            + "tools (get_project_errors, get_problem_summary, revalidate_objects, clean_project, " //$NON-NLS-1$
            + "validate_for_export, get_check_description) remain available for back-compat."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", //$NON-NLS-1$
                "get_project_errors / get_problem_summary / revalidate_objects / clean_project / " //$NON-NLS-1$
                    + "validate_for_export / get_check_description / help (snake_case canonical; " //$NON-NLS-1$
                    + "camelCase like getProjectErrors is also accepted). Pass operation=help " //$NON-NLS-1$
                    + "without other params for the operation catalog.", true) //$NON-NLS-1$
            .stringProperty("topic", //$NON-NLS-1$
                "Help topic when operation=help. Without topic - lists all operations with " //$NON-NLS-1$
                    + "one-line summaries.") //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name. Required for revalidate_objects and validate_for_export; " //$NON-NLS-1$
                    + "optional for clean_project (omitted cleans every project), " //$NON-NLS-1$
                    + "get_project_errors and get_problem_summary (both scope to the whole " //$NON-NLS-1$
                    + "workspace when omitted).") //$NON-NLS-1$
            .stringProperty("checkId", //$NON-NLS-1$
                "get_check_description: the check id (required, e.g. 'ql-temp-table-index'). " //$NON-NLS-1$
                    + "get_project_errors: keep only checks whose id contains this substring " //$NON-NLS-1$
                    + "(optional).") //$NON-NLS-1$
            .stringArrayProperty("objects", //$NON-NLS-1$
                "revalidate_objects: FQNs to revalidate (empty/omitted = whole project). " //$NON-NLS-1$
                    + "get_project_errors: keep only these object FQNs.") //$NON-NLS-1$
            .stringProperty("severity", //$NON-NLS-1$
                "get_project_errors severity filter: ERROR (default), WARNING, INFO, ALL, or a " //$NON-NLS-1$
                    + "concrete native level (ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL, " //$NON-NLS-1$
                    + "NONE).") //$NON-NLS-1$
            .stringProperty("scope", //$NON-NLS-1$
                "get_project_errors marker scope: session (default) / object / project / all.") //$NON-NLS-1$
            .booleanProperty("compact", //$NON-NLS-1$
                "get_project_errors: fold the result into grouped per-check / per-location " //$NON-NLS-1$
                    + "counts instead of one row per marker.") //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "get_project_errors: cap on results (default 100, max 1000). " //$NON-NLS-1$
                    + "validate_for_export: cap on findings (default 500).") //$NON-NLS-1$
            .stringProperty("checkFilter", //$NON-NLS-1$
                "validate_for_export: keep only findings whose check id contains this " //$NON-NLS-1$
                    + "substring.") //$NON-NLS-1$
            .stringProperty("pathFilter", //$NON-NLS-1$
                "validate_for_export: keep only findings whose file path contains this " //$NON-NLS-1$
                    + "substring.") //$NON-NLS-1$
            .stringProperty("fileFilter", //$NON-NLS-1$
                "get_project_errors: substring filter on the marker's object presentation, " //$NON-NLS-1$
                    + "applied on top of scope (e.g. 'CommonModule.Common').") //$NON-NLS-1$
            .booleanProperty("waitForRefresh", //$NON-NLS-1$
                "get_project_errors: poll EDT up to 3x300ms for freshly-computed markers " //$NON-NLS-1$
                    + "(default true). Disable when latency matters more than freshness.") //$NON-NLS-1$
            .stringArrayProperty("objectFqns", //$NON-NLS-1$
                "revalidate_objects: FQNs to revalidate - alias of `objects`. Empty/omitted = " //$NON-NLS-1$
                    + "whole project.") //$NON-NLS-1$
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
            return ToolResult.error("operation is required. Allowed: get_project_errors / " //$NON-NLS-1$
                + "get_problem_summary / revalidate_objects / clean_project / " //$NON-NLS-1$
                + "validate_for_export / get_check_description / help.").toJson(); //$NON-NLS-1$
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
            case "get_project_errors": //$NON-NLS-1$
                return new ProjectProblemsReader().execute(params);
            case "get_problem_summary": //$NON-NLS-1$
                return new ProblemSummaryReader().execute(params);
            case "revalidate_objects": //$NON-NLS-1$
                return new ObjectsRevalidator().execute(params);
            case "clean_project": //$NON-NLS-1$
                return new ProjectCleaner().execute(params);
            case "validate_for_export": //$NON-NLS-1$
                return new ValidateForExportTool().execute(params);
            case "get_check_description": //$NON-NLS-1$
                return new CheckDocReader().execute(params);
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
            sb.append("# diagnostics - operations\n\n"); //$NON-NLS-1$
            sb.append("- **get_project_errors** - configuration problems, filtered by " //$NON-NLS-1$
                + "severity / scope / objects.\n"); //$NON-NLS-1$
            sb.append("- **get_problem_summary** - problem counts by severity, overall and " //$NON-NLS-1$
                + "per project.\n"); //$NON-NLS-1$
            sb.append("- **revalidate_objects** - revalidate the whole project or named FQNs.\n"); //$NON-NLS-1$
            sb.append("- **clean_project** - clean build plus full revalidation.\n"); //$NON-NLS-1$
            sb.append("- **validate_for_export** - pre-export scan of .mdo/.form/.dcs/.mxlx " //$NON-NLS-1$
                + "source files for defects get_project_errors cannot see (it validates the " //$NON-NLS-1$
                + "in-memory model, not the exported XDTO).\n"); //$NON-NLS-1$
            sb.append("- **get_check_description** - documentation for one EDT check id.\n"); //$NON-NLS-1$
            sb.append("- **help** - this catalog. Pass topic=workflow for the operation-picker " //$NON-NLS-1$
                + "guide.\n"); //$NON-NLS-1$
            return sb.toString();
        }
        if ("workflow".equals(topic)) //$NON-NLS-1$
        {
            StringBuilder sb = new StringBuilder();
            sb.append("# diagnostics - operation picker\n\n"); //$NON-NLS-1$
            sb.append("| Goal | Operation |\n"); //$NON-NLS-1$
            sb.append("|------|-----------|\n"); //$NON-NLS-1$
            sb.append("| What is broken right now | get_project_errors |\n"); //$NON-NLS-1$
            sb.append("| How many problems, by severity | get_problem_summary |\n"); //$NON-NLS-1$
            sb.append("| Force EDT to recheck an object / the project | revalidate_objects |\n"); //$NON-NLS-1$
            sb.append("| Wipe markers and rebuild from scratch | clean_project |\n"); //$NON-NLS-1$
            sb.append("| Will update_database crash on a hand-edited .mdo/.form | " //$NON-NLS-1$
                + "validate_for_export |\n"); //$NON-NLS-1$
            sb.append("| What does check X mean | get_check_description |\n"); //$NON-NLS-1$
            return sb.toString();
        }
        return "# Unknown topic '" + topic + "'.\n\nAvailable: workflow.\n"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Map<String, String> buildOpsCatalog()
    {
        Map<String, String> m = new LinkedHashMap<>();
        for (String op : Arrays.asList(
            "get_project_errors", "get_problem_summary", //$NON-NLS-1$ //$NON-NLS-2$
            "revalidate_objects", "clean_project", //$NON-NLS-1$ //$NON-NLS-2$
            "validate_for_export", "get_check_description")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            m.put(op, op);
        }
        return Collections.unmodifiableMap(m);
    }
}
