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

import ru.aiedt.mcp.server.support.ToolGate;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Unified project and configuration administration facade with nine operations.
 *
 * <p>Collapses the workspace-shape tools under one name: listing, inspecting,
 * creating, deleting, resynchronising and restarting.
 * <ul>
 *   <li>{@code list_projects} - every project in the workspace (delegates to
 *       {@link ProjectsLister})</li>
 *   <li>{@code list_configurations} - EDT launch configurations and their
 *       running state (delegates to {@link LaunchConfigsLister})</li>
 *   <li>{@code get_configuration_properties} - a configuration's top-level
 *       properties (delegates to {@link ConfigurationInfoReader})</li>
 *   <li>{@code create_project} - create a new BASE configuration DT project
 *       (delegates to {@link ProjectCreator}; MUTATING)</li>
 *   <li>{@code delete_project} - remove a project from the workspace
 *       (delegates to {@link ProjectRemover}; DESTRUCTIVE)</li>
 *   <li>{@code resync_to_disk} - force the in-memory BM out to on-disk .mdo
 *       (delegates to {@link DiskResynchronizer}; MUTATING)</li>
 *   <li>{@code restart_edt} - gracefully restart or shut down the host EDT
 *       instance (delegates to {@link RestartEdtTool})</li>
 *   <li>{@code self_upkeep} - whether a newer build of this plugin is published
 *       on the configured update site (delegates to {@link SelfUpkeepTool};
 *       gate-checked, see {@link ToolGate})</li>
 *   <li>{@code list_subsystems} - configuration subsystems as a hierarchy
 *       (delegates to {@link GetSubsystemsTool})</li>
 *   <li>{@code help} - built-in topic-driven help</li>
 * </ul>
 *
 * <p>Each operation routes to its standalone tool unchanged - params pass
 * through as-is, and create_project / delete_project / resync_to_disk /
 * restart_edt keep exactly the behavior their standalone tool has today; the
 * facade adds no dryRun and no extra confirmation step. The standalone tools
 * stay registered for back-compat. This facade always answers as MARKDOWN,
 * the safest wrapper: it carries any string body regardless of the routed
 * tool's own native response type. An agent that needs a JSON-typed result
 * (structuredContent) from one of the JSON-response standalones should call
 * that standalone directly - the same tradeoff {@code code_search} and
 * {@code diagnostics} accept.
 */
public class ProjectAdminFacadeTool implements IMcpTool
{
    public static final String NAME = "project_admin"; //$NON-NLS-1$

    private static final Map<String, String> OPS = buildOpsCatalog();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Project and configuration administration - list, inspect, create, delete, " //$NON-NLS-1$
            + "resync, restart, subsystem listing. Operations: list_projects, " //$NON-NLS-1$
            + "list_configurations, get_configuration_properties, create_project, " //$NON-NLS-1$
            + "delete_project, resync_to_disk, restart_edt, self_upkeep, list_subsystems, " //$NON-NLS-1$
            + "help. Pass " //$NON-NLS-1$
            + "operation=<name> (snake_case canonical; camelCase like listProjects is also " //$NON-NLS-1$
            + "accepted); remaining parameters follow the per-operation contracts (call " //$NON-NLS-1$
            + "operation=help for the catalog). create_project / delete_project / " //$NON-NLS-1$
            + "resync_to_disk mutate and restart_edt restarts the host EDT instance - the " //$NON-NLS-1$
            + "facade only routes, it adds no dryRun. The standalone tools remain available " //$NON-NLS-1$
            + "for back-compat."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", //$NON-NLS-1$
                "list_projects / list_configurations / get_configuration_properties / " //$NON-NLS-1$
                    + "create_project / delete_project / resync_to_disk / restart_edt / " //$NON-NLS-1$
                    + "self_upkeep / list_subsystems / help (snake_case canonical; " //$NON-NLS-1$
                    + "camelCase like " //$NON-NLS-1$
                    + "listProjects is also accepted). Pass operation=help without other " //$NON-NLS-1$
                    + "params for the operation catalog.", true) //$NON-NLS-1$
            .stringProperty("topic", //$NON-NLS-1$
                "Help topic when operation=help. Without topic - lists all operations with " //$NON-NLS-1$
                    + "one-line summaries.") //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name. Required for create_project (the new project's name), " //$NON-NLS-1$
                    + "delete_project, resync_to_disk and list_subsystems; optional filter " //$NON-NLS-1$
                    + "for get_configuration_properties and list_configurations.") //$NON-NLS-1$
            .stringProperty("version", //$NON-NLS-1$
                "create_project: platform runtime version 'major.minor.micro', e.g. " //$NON-NLS-1$
                    + "'8.3.21'. Optional - defaults to the latest version this EDT " //$NON-NLS-1$
                    + "installation supports.") //$NON-NLS-1$
            .booleanProperty("deleteContent", //$NON-NLS-1$
                "delete_project: also delete the project's files from disk (default false " //$NON-NLS-1$
                    + "= keep files, remove only from the workspace).") //$NON-NLS-1$
            .stringArrayProperty("objects", //$NON-NLS-1$
                "resync_to_disk: top-object FQNs to force-export to disk (required for that " //$NON-NLS-1$
                    + "operation, e.g. ['Catalog.Products', 'Document.SalesOrder']).") //$NON-NLS-1$
            .stringProperty("action", //$NON-NLS-1$
                "restart_edt: restart (default, relaunches the workspace) or shutdown " //$NON-NLS-1$
                    + "(closes EDT and leaves it down).") //$NON-NLS-1$
            .integerProperty("delayMs", //$NON-NLS-1$
                "restart_edt: delay before the action so the response is delivered first " //$NON-NLS-1$
                    + "(default 1000, max 60000).") //$NON-NLS-1$
            .stringProperty("type", //$NON-NLS-1$
                "list_configurations filter: attach / client / all (default).") //$NON-NLS-1$
            .stringProperty("nameFilter", //$NON-NLS-1$
                "list_subsystems: partial name match filter (case-insensitive).") //$NON-NLS-1$
            .booleanProperty("includeContent", //$NON-NLS-1$
                "list_subsystems: list each subsystem's content object FQNs (default " //$NON-NLS-1$
                    + "false).") //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "list_subsystems: maximum subsystems rendered across the whole tree " //$NON-NLS-1$
                    + "(default 500).") //$NON-NLS-1$
            .booleanProperty("refresh", //$NON-NLS-1$
                "resync_to_disk: refresh the workspace after the BM export so Eclipse sees the " //$NON-NLS-1$
                    + "written files (default true).") //$NON-NLS-1$
            .integerProperty("waitTimeoutMs", //$NON-NLS-1$
                "resync_to_disk: max wait for the BM export to settle, in ms (default 10000).") //$NON-NLS-1$
            .stringProperty("language", //$NON-NLS-1$
                "list_subsystems: language code for synonyms (e.g. 'en', 'ru'). Defaults to " //$NON-NLS-1$
                    + "the first available language.") //$NON-NLS-1$
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
            return ToolResult.error("operation is required. Allowed: list_projects / " //$NON-NLS-1$
                + "list_configurations / get_configuration_properties / create_project / " //$NON-NLS-1$
                + "delete_project / resync_to_disk / restart_edt / self_upkeep / " //$NON-NLS-1$
                + "list_subsystems / help.") //$NON-NLS-1$
                .toJson();
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
            case "list_projects": //$NON-NLS-1$
                return new ProjectsLister().execute(params);
            case "list_configurations": //$NON-NLS-1$
                return new LaunchConfigsLister().execute(params);
            case "get_configuration_properties": //$NON-NLS-1$
                return new ConfigurationInfoReader().execute(params);
            case "create_project": //$NON-NLS-1$
                return new ProjectCreator().execute(params);
            case "delete_project": //$NON-NLS-1$
                return new ProjectRemover().execute(params);
            case "resync_to_disk": //$NON-NLS-1$
                return new DiskResynchronizer().execute(params);
            case "restart_edt": //$NON-NLS-1$
                return new RestartEdtTool().execute(params);
            case "self_upkeep": //$NON-NLS-1$
            {
                // Gate-checked, unlike the delegations above: self_upkeep is the one operation
                // here whose standalone a preset switches off by name, and without this check
                // the facade would be a way around that switch.
                String refusal = ToolGate.gateOrNull(SelfUpkeepTool.NAME);
                return refusal != null ? ToolResult.error(refusal).toJson()
                    : new SelfUpkeepTool().execute(params);
            }
            case "list_subsystems": //$NON-NLS-1$
                return new GetSubsystemsTool().execute(params);
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
            sb.append("# project_admin - operations\n\n"); //$NON-NLS-1$
            sb.append("- **list_projects** - every project in the workspace, EDT's and not.\n"); //$NON-NLS-1$
            sb.append("- **list_configurations** - EDT launch configurations and their " //$NON-NLS-1$
                + "running state.\n"); //$NON-NLS-1$
            sb.append("- **get_configuration_properties** - a configuration's top-level " //$NON-NLS-1$
                + "properties.\n"); //$NON-NLS-1$
            sb.append("- **create_project** - create a new BASE configuration DT project. " //$NON-NLS-1$
                + "MUTATING.\n"); //$NON-NLS-1$
            sb.append("- **delete_project** - remove a project from the workspace. " //$NON-NLS-1$
                + "DESTRUCTIVE.\n"); //$NON-NLS-1$
            sb.append("- **resync_to_disk** - force the in-memory BM out to on-disk .mdo. " //$NON-NLS-1$
                + "MUTATING.\n"); //$NON-NLS-1$
            sb.append("- **restart_edt** - gracefully restart or shut down the host EDT " //$NON-NLS-1$
                + "instance.\n"); //$NON-NLS-1$
            sb.append("- **self_upkeep** - whether a newer build of this plugin is published " //$NON-NLS-1$
                + "on the configured update site.\n"); //$NON-NLS-1$
            sb.append("- **list_subsystems** - configuration subsystems as a hierarchy.\n"); //$NON-NLS-1$
            sb.append("- **help** - this catalog. Pass topic=workflow for the operation-picker " //$NON-NLS-1$
                + "guide.\n"); //$NON-NLS-1$
            return sb.toString();
        }
        if ("workflow".equals(topic)) //$NON-NLS-1$
        {
            StringBuilder sb = new StringBuilder();
            sb.append("# project_admin - operation picker\n\n"); //$NON-NLS-1$
            sb.append("| Goal | Operation |\n"); //$NON-NLS-1$
            sb.append("|------|-----------|\n"); //$NON-NLS-1$
            sb.append("| What projects are in this workspace | list_projects |\n"); //$NON-NLS-1$
            sb.append("| What launch configs exist, which are running | " //$NON-NLS-1$
                + "list_configurations |\n"); //$NON-NLS-1$
            sb.append("| Read a configuration's name / synonym / compatibility mode | " //$NON-NLS-1$
                + "get_configuration_properties |\n"); //$NON-NLS-1$
            sb.append("| Start a new base configuration from scratch | create_project |\n"); //$NON-NLS-1$
            sb.append("| Remove a throwaway/test project | delete_project |\n"); //$NON-NLS-1$
            sb.append("| EDT shows an object as valid but disk is stale | resync_to_disk |\n"); //$NON-NLS-1$
            sb.append("| Apply a plugin update / recover a stuck IDE | restart_edt |\n"); //$NON-NLS-1$
            sb.append("| Is there a newer AI-EDT build to install | self_upkeep |\n"); //$NON-NLS-1$
            sb.append("| See the subsystem tree with command-interface flags | " //$NON-NLS-1$
                + "list_subsystems |\n"); //$NON-NLS-1$
            return sb.toString();
        }
        return "# Unknown topic '" + topic + "'.\n\nAvailable: workflow.\n"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Map<String, String> buildOpsCatalog()
    {
        Map<String, String> m = new LinkedHashMap<>();
        for (String op : Arrays.asList(
            "list_projects", "list_configurations", //$NON-NLS-1$ //$NON-NLS-2$
            "get_configuration_properties", "create_project", //$NON-NLS-1$ //$NON-NLS-2$
            "delete_project", "resync_to_disk", //$NON-NLS-1$ //$NON-NLS-2$
            "restart_edt", "self_upkeep", //$NON-NLS-1$ //$NON-NLS-2$
            "list_subsystems")) //$NON-NLS-1$
        {
            m.put(op, op);
        }
        return Collections.unmodifiableMap(m);
    }
}
