/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.support.ToolGate;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Unified infobase and launch administration facade with seven operations.
 *
 * <p>Collapses the infobase-lifecycle and launch-target tools under one name:
 * <ul>
 *   <li>{@code read_event_log} - what actually happened in a file infobase</li>
 *   <li>{@code get_applications} - a project's applications, the infobase
 *       launch targets (delegates to {@link ApplicationsReader})</li>
 *   <li>{@code create_infobase} - create a FILE infobase and register it in
 *       EDT's list (delegates to {@link InfobaseCreator}; MUTATING)</li>
 *   <li>{@code delete_infobase} - remove an infobase from EDT's list, optionally
 *       its .1CD on disk (delegates to {@link InfobaseRemover}; MUTATING,
 *       DESTRUCTIVE with deleteContent=true)</li>
 *   <li>{@code set_infobase_credentials} - store connection credentials in
 *       EDT's encrypted store (delegates to {@link InfobaseCredentialsWriter};
 *       MUTATING)</li>
 *   <li>{@code create_launch_config} - associate an existing infobase to a
 *       project (delegates to {@link LaunchConfigCreator}; MUTATING)</li>
 *   <li>{@code update_database} - push the current configuration into an
 *       application's infobase (delegates to {@link DatabaseUpdater};
 *       MUTATING, may reply Pending with a runKey)</li>
 *   <li>{@code sync_control} - inspect and control EDT&lt;-&gt;infobase
 *       synchronization (delegates to {@link SyncControlTool})</li>
 *   <li>{@code help} - built-in topic-driven help</li>
 * </ul>
 *
 * <p>Each operation routes to its standalone tool unchanged - params pass
 * through as-is, and the standalone tools stay registered for back-compat.
 * {@code sync_control} is the one exception worth calling out: it is itself a
 * multi-operation tool with its own {@code operation} parameter (status /
 * diagnose / suppress / ...), which collides with this facade's own routing
 * {@code operation}. Calling it here therefore reads the sync action from a
 * separate {@code syncOperation} parameter and forwards it as {@code operation}
 * on a copy of the params - every other operation forwards the original map
 * untouched. This facade always answers as MARKDOWN, the safest wrapper: it
 * carries any string body regardless of the routed tool's own native response
 * type. An agent that needs a JSON-typed result (structuredContent) from one of
 * the JSON-response standalones should call that standalone directly - the
 * same tradeoff {@code code_search} and {@code diagnostics} accept.
 */
public class InfobaseAdminFacadeTool implements IMcpTool
{
    public static final String NAME = "infobase_admin"; //$NON-NLS-1$

    private static final Map<String, String> OPS = buildOpsCatalog();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Infobase and launch administration - list applications, create / delete an " //$NON-NLS-1$
            + "infobase, set credentials, create a launch configuration, start a 1C client from " //$NON-NLS-1$
            + "one, update the database, control EDT<->infobase sync. Operations: " //$NON-NLS-1$
            + "get_applications, read_event_log, create_infobase, delete_infobase, " //$NON-NLS-1$
            + "set_infobase_credentials, " //$NON-NLS-1$
            + "create_launch_config, start_client, branch_infobase, update_database, " //$NON-NLS-1$
            + "sync_control, help. Pass operation=<name> (snake_case canonical; camelCase like " //$NON-NLS-1$
            + "getApplications is also accepted); remaining parameters follow the per-operation " //$NON-NLS-1$
            + "contracts (call operation=help for the catalog). create_infobase / " //$NON-NLS-1$
            + "delete_infobase / set_infobase_credentials / update_database mutate, and " //$NON-NLS-1$
            + "update_database / sync_control may reply with a Pending status and a runKey to " //$NON-NLS-1$
            + "resume - the facade only routes, it adds no dryRun. sync_control has its own " //$NON-NLS-1$
            + "inner operation (status / diagnose / suppress / ...): pass it as syncOperation, " //$NON-NLS-1$
            + "not operation - operation here always selects the infobase_admin routing target. " //$NON-NLS-1$
            + "The standalone tools remain available for back-compat."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", //$NON-NLS-1$
                "get_applications / read_event_log / create_infobase / delete_infobase / " //$NON-NLS-1$
                    + "set_infobase_credentials / create_launch_config / start_client / " //$NON-NLS-1$
                    + "branch_infobase / update_database / " //$NON-NLS-1$
                    + "sync_control / help (snake_case canonical; camelCase like " //$NON-NLS-1$
                    + "getApplications is also accepted). Pass operation=help without other " //$NON-NLS-1$
                    + "params for the operation catalog.", true) //$NON-NLS-1$
            .stringProperty("topic", //$NON-NLS-1$
                "Help topic when operation=help. Without topic - lists all operations with " //$NON-NLS-1$
                    + "one-line summaries.") //$NON-NLS-1$
            .stringProperty("action", //$NON-NLS-1$
                "branch_infobase: current (default) / list / bind / unbind.") //$NON-NLS-1$
            .booleanProperty("ignoreBranchBinding", //$NON-NLS-1$
                "update_database: update even when the branch is bound to another application. " //$NON-NLS-1$
                    + "Declared here because the refusal tells the caller to pass it, and a facade " //$NON-NLS-1$
                    + "that does not accept it leaves that instruction impossible to follow.") //$NON-NLS-1$
            .stringProperty("branch", //$NON-NLS-1$
                "branch_infobase: the branch to bind or unbind. Defaults to the branch the " //$NON-NLS-1$
                    + "project is on.") //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name. Required for get_applications, set_infobase_credentials, " //$NON-NLS-1$
                    + "create_launch_config and sync_control; optional association target for " //$NON-NLS-1$
                    + "create_infobase; optional dissociation target for delete_infobase; " //$NON-NLS-1$
                    + "required for update_database when launchConfigurationName is not " //$NON-NLS-1$
                    + "supplied - on its own it is enough, applicationId may be omitted.") //$NON-NLS-1$
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application (infobase) id from get_applications. Optional for " //$NON-NLS-1$
                    + "set_infobase_credentials when the project has a single application, and " //$NON-NLS-1$
                    + "for update_database, which falls back to the project's default application " //$NON-NLS-1$
                    + "and - for an extension project, which has no infobase of its own - to the " //$NON-NLS-1$
                    + "default of the configuration it extends.") //$NON-NLS-1$
            .stringProperty("name", //$NON-NLS-1$
                "Infobase name. Required for create_infobase (the new infobase's name) and " //$NON-NLS-1$
                    + "delete_infobase (the infobase to remove).") //$NON-NLS-1$
            .stringProperty("path", //$NON-NLS-1$
                "create_infobase: absolute path to the infobase directory (required for that " //$NON-NLS-1$
                    + "operation) - an empty/new directory for the .1CD.") //$NON-NLS-1$
            .stringProperty("platform", //$NON-NLS-1$
                "create_infobase: 1C:Enterprise platform version (optional, blank = latest " //$NON-NLS-1$
                    + "available).") //$NON-NLS-1$
            .stringProperty("templateCf", //$NON-NLS-1$
                "create_infobase: optional path to a .cf / .cfe to load into the new infobase " //$NON-NLS-1$
                    + "on create.") //$NON-NLS-1$
            .booleanProperty("deleteContent", //$NON-NLS-1$
                "delete_infobase: also delete the .1CD directory on disk (DESTRUCTIVE, " //$NON-NLS-1$
                    + "irreversible; default false = only remove the reference from EDT's " //$NON-NLS-1$
                    + "list).") //$NON-NLS-1$
            .stringProperty("infobaseName", //$NON-NLS-1$
                "create_launch_config: name of an existing infobase in EDT's list to " //$NON-NLS-1$
                    + "associate (required for that operation).") //$NON-NLS-1$
            .stringProperty("accessMode", //$NON-NLS-1$
                "set_infobase_credentials: INFOBASE (user + password) or OS (pass-through, " //$NON-NLS-1$
                    + "no user/password). Optional - defaults to INFOBASE when userName is " //$NON-NLS-1$
                    + "supplied, else OS.") //$NON-NLS-1$
            .stringProperty("userName", //$NON-NLS-1$
                "set_infobase_credentials: infobase user name (for INFOBASE access).") //$NON-NLS-1$
            .stringProperty("password", //$NON-NLS-1$
                "set_infobase_credentials: infobase password (for INFOBASE access). Stored " //$NON-NLS-1$
                    + "encrypted; never logged or returned.") //$NON-NLS-1$
            .stringProperty("launchConfigurationName", //$NON-NLS-1$
                "update_database / start_client: exact name of an existing EDT runtime-client " //$NON-NLS-1$
                    + "launch configuration (preferred over projectName + applicationId - see " //$NON-NLS-1$
                    + "list_configurations).") //$NON-NLS-1$
            .booleanProperty("updateBeforeLaunch", //$NON-NLS-1$
                "start_client: update the infobase before starting, and refuse to start when it " //$NON-NLS-1$
                    + "cannot be brought up to date (default false).") //$NON-NLS-1$
            .booleanProperty("allowSecondSession", //$NON-NLS-1$
                "start_client: start even when this configuration already has a client running " //$NON-NLS-1$
                    + "(default false - the running one is reported instead).") //$NON-NLS-1$
            .booleanProperty("fullUpdate", //$NON-NLS-1$
                "update_database: true triggers a full reload; false runs an incremental " //$NON-NLS-1$
                    + "update instead (default false).") //$NON-NLS-1$
            .booleanProperty("autoRestructure", //$NON-NLS-1$
                "update_database: apply infobase restructuring automatically when required " //$NON-NLS-1$
                    + "(default true).") //$NON-NLS-1$
            .booleanProperty("autoFreeClients", //$NON-NLS-1$
                "update_database: before updating, stop this project's own matching " //$NON-NLS-1$
                    + "runtime-client sessions so they cannot keep the infobase locked " //$NON-NLS-1$
                    + "(default false).") //$NON-NLS-1$
            .stringProperty("timeoutSeconds", //$NON-NLS-1$
                "update_database: soft wait limit in seconds (5-120, default 30) before " //$NON-NLS-1$
                    + "replying Pending with a runKey to resume.") //$NON-NLS-1$
            .stringProperty("runKey", //$NON-NLS-1$
                "update_database: resumes a Pending update issued earlier with this runKey; " //$NON-NLS-1$
                    + "other params are ignored once runKey is supplied.") //$NON-NLS-1$
            .booleanProperty("cancel", //$NON-NLS-1$
                "update_database: combined with runKey, detach and stop tracking that update " //$NON-NLS-1$
                    + "(best-effort only).") //$NON-NLS-1$
            .stringProperty("syncOperation", //$NON-NLS-1$
                "sync_control's OWN action - status / diagnose / diagnose_delta / suppress / " //$NON-NLS-1$
                    + "reseed_baseline / mark_synchronized / diagnose_stuck_locks / " //$NON-NLS-1$
                    + "recover_stuck_merge (required when operation=sync_control). Kept " //$NON-NLS-1$
                    + "separate from this facade's routing operation on purpose - sync_control " //$NON-NLS-1$
                    + "has its own operation concept.") //$NON-NLS-1$
            .booleanProperty("enabled", //$NON-NLS-1$
                "sync_control syncOperation=suppress: true = suppress synchronization for " //$NON-NLS-1$
                    + "the project (skip it on update), false = re-enable.") //$NON-NLS-1$
            .stringProperty("infobaseUuid", //$NON-NLS-1$
                "sync_control syncOperation=reseed_baseline / mark_synchronized / " //$NON-NLS-1$
                    + "recover_stuck_merge: the target infobase (an infobaseUuid from " //$NON-NLS-1$
                    + "syncOperation=status / diagnose_stuck_locks).") //$NON-NLS-1$
            .booleanProperty("confirm", //$NON-NLS-1$
                "sync_control syncOperation=reseed_baseline / mark_synchronized / " //$NON-NLS-1$
                    + "recover_stuck_merge: must be true to proceed. These are DANGEROUS - " //$NON-NLS-1$
                    + "only on explicit user request and only when certain of the state.") //$NON-NLS-1$
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
            return ToolResult.error("operation is required. Allowed: get_applications / " //$NON-NLS-1$
                + "read_event_log / " //$NON-NLS-1$
                + "create_infobase / delete_infobase / set_infobase_credentials / " //$NON-NLS-1$
                + "create_launch_config / start_client / branch_infobase / update_database / " //$NON-NLS-1$
                + "sync_control / " //$NON-NLS-1$
                + "help.").toJson(); //$NON-NLS-1$
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
            case "get_applications": //$NON-NLS-1$
                return new ApplicationsReader().execute(params);
            case "read_event_log": //$NON-NLS-1$
                return new EventLogTool().execute(params);
            case "create_infobase": //$NON-NLS-1$
                return new InfobaseCreator().execute(params);
            case "delete_infobase": //$NON-NLS-1$
                return new InfobaseRemover().execute(params);
            case "set_infobase_credentials": //$NON-NLS-1$
                return new InfobaseCredentialsWriter().execute(params);
            case "create_launch_config": //$NON-NLS-1$
                return new LaunchConfigCreator().execute(params);
            case "start_client": //$NON-NLS-1$
                return new ClientSessionStarter().execute(params);
            case "branch_infobase": //$NON-NLS-1$
                return new BranchInfobaseTool().execute(params);
            case "update_database": //$NON-NLS-1$
                return new DatabaseUpdater().execute(params);
            case "sync_control": //$NON-NLS-1$
                return routeSyncControl(params);
            default:
                return ToolResult.error("Unhandled operation: " + operation).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Routes to {@link SyncControlTool}, which has its own {@code operation} parameter (status /
     * diagnose / suppress / ...) that collides with this facade's routing {@code operation}. The sync
     * action travels as {@code syncOperation} instead and is remapped onto a copy of the params before
     * the call, so {@code SyncControlTool} sees exactly the {@code operation} value it expects.
     *
     * @param params the facade's own params, unmodified
     * @return the result of {@code SyncControlTool.execute} on the remapped params, or a facade-level
     *         error when syncOperation is missing
     */
    private static String routeSyncControl(Map<String, String> params)
    {
        String syncOperation = JsonUtils.extractStringArgument(params, "syncOperation"); //$NON-NLS-1$
        if (syncOperation == null || syncOperation.isBlank())
        {
            return ToolResult.error("operation=sync_control requires syncOperation (status / " //$NON-NLS-1$
                + "diagnose / diagnose_delta / suppress / reseed_baseline / " //$NON-NLS-1$
                + "mark_synchronized / diagnose_stuck_locks / recover_stuck_merge) - " //$NON-NLS-1$
                + "sync_control has its own inner operation, kept separate from this facade's " //$NON-NLS-1$
                + "routing operation.").toJson(); //$NON-NLS-1$
        }
        Map<String, String> forwarded = new HashMap<>(params);
        forwarded.put("operation", syncOperation); //$NON-NLS-1$
        return new SyncControlTool().execute(forwarded);
    }

    private static String buildHelp(String topic)
    {
        topic = JsonUtils.normalizeOperationToken(topic);
        if (topic == null || topic.isEmpty())
        {
            StringBuilder sb = new StringBuilder();
            sb.append("# infobase_admin - operations\n\n"); //$NON-NLS-1$
            sb.append("- **get_applications** - a project's applications, the infobase launch " //$NON-NLS-1$
                + "targets.\n"); //$NON-NLS-1$
            sb.append("- **create_infobase** - create a FILE infobase and register it in " //$NON-NLS-1$
                + "EDT's list. MUTATING.\n"); //$NON-NLS-1$
            sb.append("- **delete_infobase** - remove an infobase from EDT's list, optionally " //$NON-NLS-1$
                + "its .1CD on disk. MUTATING, DESTRUCTIVE with deleteContent=true.\n"); //$NON-NLS-1$
            sb.append("- **set_infobase_credentials** - store connection credentials in EDT's " //$NON-NLS-1$
                + "encrypted store. MUTATING.\n"); //$NON-NLS-1$
            sb.append("- **create_launch_config** - associate an existing infobase to a " //$NON-NLS-1$
                + "project. MUTATING.\n"); //$NON-NLS-1$
            sb.append("- **update_database** - push the current configuration into an " //$NON-NLS-1$
                + "application's infobase. MUTATING; may reply Pending with a runKey.\n"); //$NON-NLS-1$
            sb.append("- **sync_control** - inspect and control EDT<->infobase " //$NON-NLS-1$
                + "synchronization. Pass its own action as syncOperation, not operation; " //$NON-NLS-1$
                + "some syncOperation values (reseed_baseline, mark_synchronized, " //$NON-NLS-1$
                + "recover_stuck_merge) are DANGEROUS.\n"); //$NON-NLS-1$
            sb.append("- **start_client** - start a 1C client from a launch configuration, " //$NON-NLS-1$
                + "without a debugger. Use it instead of building a 1cv8.exe command line.\n"); //$NON-NLS-1$
            sb.append("- **branch_infobase** - bind a git branch to an application, so that " //$NON-NLS-1$
                + "update_database refuses an infobase belonging to another branch. Actions: " //$NON-NLS-1$
                + "current (default) / list / bind / unbind. For an extension project the " //$NON-NLS-1$
                + "binding lives in the extension itself.\n"); //$NON-NLS-1$
            sb.append("- **read_event_log** - read a FILE infobase's event log: who logged in, " //$NON-NLS-1$
                + "what was posted, what the platform refused. Filterable by from / to / event " //$NON-NLS-1$
                + "/ user / severity.\n"); //$NON-NLS-1$
            sb.append("- **help** - this catalog. Pass topic=workflow for the operation-picker " //$NON-NLS-1$
                + "guide.\n"); //$NON-NLS-1$
            return sb.toString();
        }
        if ("workflow".equals(topic)) //$NON-NLS-1$
        {
            StringBuilder sb = new StringBuilder();
            sb.append("# infobase_admin - operation picker\n\n"); //$NON-NLS-1$
            sb.append("| Goal | Operation |\n"); //$NON-NLS-1$
            sb.append("|------|-----------|\n"); //$NON-NLS-1$
            sb.append("| What applications (infobases) can this project run against | " //$NON-NLS-1$
                + "get_applications |\n"); //$NON-NLS-1$
            sb.append("| Create a brand-new FILE infobase | create_infobase |\n"); //$NON-NLS-1$
            sb.append("| Remove an infobase from EDT's list | delete_infobase |\n"); //$NON-NLS-1$
            sb.append("| Store a user/password so update_database stops prompting | " //$NON-NLS-1$
                + "set_infobase_credentials |\n"); //$NON-NLS-1$
            sb.append("| Associate an existing infobase to a project | " //$NON-NLS-1$
                + "create_launch_config |\n"); //$NON-NLS-1$
            sb.append("| Push the configuration into an infobase | update_database |\n"); //$NON-NLS-1$
            sb.append("| Predict or control whether the next update is FULL or incremental | " //$NON-NLS-1$
                + "sync_control (syncOperation=status / diagnose / suppress / ...) |\n"); //$NON-NLS-1$
            return sb.toString();
        }
        return "# Unknown topic '" + topic + "'.\n\nAvailable: workflow.\n"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Map<String, String> buildOpsCatalog()
    {
        Map<String, String> m = new LinkedHashMap<>();
        for (String op : Arrays.asList(
            "get_applications", "read_event_log", //$NON-NLS-1$ //$NON-NLS-2$
            "create_infobase", //$NON-NLS-1$
            "delete_infobase", "set_infobase_credentials", //$NON-NLS-1$ //$NON-NLS-2$
            "create_launch_config", "start_client", //$NON-NLS-1$ //$NON-NLS-2$
            "branch_infobase", //$NON-NLS-1$
            "update_database", "sync_control")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            m.put(op, op);
        }
        return Collections.unmodifiableMap(m);
    }
}
