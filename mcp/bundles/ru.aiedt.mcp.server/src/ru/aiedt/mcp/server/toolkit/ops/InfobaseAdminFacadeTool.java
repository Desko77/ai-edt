/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.function.Supplier;
import ru.aiedt.mcp.server.support.FacadeHelpSearch;
import ru.aiedt.mcp.server.support.FacadeParameterHelp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.support.DtSnapshotRunner;
import ru.aiedt.mcp.server.support.PendingWorkRegistry;
import ru.aiedt.mcp.server.support.ToolGate;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Unified infobase and launch administration facade.
 *
 * <p>Collapses the infobase-lifecycle and launch-target tools under one name:
 * <ul>
 *   <li>{@code read_event_log} - what actually happened in a file infobase</li>
 *   <li>{@code get_applications} - a project's applications, the infobase
 *       launch targets (delegates to {@link ApplicationsReader})</li>
 *   <li>{@code list_registered_infobases} - the infobases registered in EDT, with their groups
 *       (delegates to {@link RegisteredInfobasesReader})</li>
 *   <li>{@code create_infobase} - create a FILE infobase and register it in
 *       EDT's list (delegates to {@link InfobaseCreator}; MUTATING)</li>
 *   <li>{@code register_infobase} - register an EXISTING infobase (file or
 *       server) in EDT's list and associate it to a project, optionally storing
 *       its access credentials before the binding (delegates to
 *       {@link InfobaseRegistrar}; MUTATING)</li>
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
 *   <li>{@code inspect_database_sync} - read what stands between a project and its
 *       infobase: the update state, the restructure-confirmation preference and the
 *       data an update now would delete (runs {@link DatabaseSyncInspector};
 *       read-only)</li>
 *   <li>{@code export_database_snapshot} - dump an application's infobase into a
 *       {@code .dt} file (handled here through {@link DtSnapshotRunner}; MUTATING,
 *       may reply Pending with a runKey)</li>
 *   <li>{@code restore_database_snapshot} - load a {@code .dt} back into an
 *       application's infobase, writing a backup of what it holds first
 *       (handled here through {@link DtSnapshotRunner}; MUTATING, DESTRUCTIVE -
 *       the load replaces everything the infobase holds; may reply Pending)</li>
 *   <li>{@code sync_control} - inspect and control EDT&lt;-&gt;infobase
 *       synchronization (delegates to {@link SyncControlTool})</li>
 *   <li>{@code help} - built-in topic-driven help</li>
 * </ul>
 *
 * <p>Each operation routes to its standalone tool unchanged - params pass
 * through as-is, and the standalone tools stay registered for back-compat.
 * {@code export_database_snapshot} and {@code restore_database_snapshot} are
 * answered here rather than delegated: no standalone tool of theirs exists, and
 * both operations share one runner ({@link DtSnapshotRunner}) with one registry
 * domain, so a facade split in two would keep two copies of the same guards.
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

    private static final Map<String, Supplier<IMcpTool>> DESCRIBED = buildDescribed();

    /**
     * The tool each operation routes to, for describing it rather than running it.
     * <p>
     * A map and not a second switch on the same word: the operation-parameter census reads
     * the widest {@code switch (operation)} in a file as the facade's vocabulary, and two
     * switches of equal width leave which one it reads to the order they sit in. The two
     * lists are held together by {@code scripts/check-facade-routing.py}.
     * </p>
     *
     * @return operation name to the tool it reaches, never <code>null</code>
     */
    static Map<String, Supplier<IMcpTool>> describedOperations()
    {
        return DESCRIBED;
    }

    @Override
    public String routesTo(Map<String, String> arguments)
    {
        String operation = JsonUtils.extractStringArgument(arguments, "operation"); //$NON-NLS-1$
        if (operation == null || operation.isEmpty())
        {
            return null;
        }
        String normalized = JsonUtils.normalizeOperationToken(operation);
        if ("sync_control".equals(normalized)) //$NON-NLS-1$
        {
            // Its inner action travels as syncOperation and is forwarded verbatim, so the Designer
            // behind rebuild_dump_info is named from that argument, not from DESCRIBED - which
            // cannot hold sync_control, whose delegate would need this facade's own remapping.
            // SyncControlTool answers the same question for a standalone call; both spellings of
            // the action are compared exactly, as its own dispatch does.
            return "rebuild_dump_info".equals(JsonUtils.extractStringArgument(arguments, //$NON-NLS-1$
                "syncOperation")) ? "rebuild_dump_info" : null; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if ("start_client".equals(normalized)) //$NON-NLS-1$
        {
            // The delegate updates the infobase before starting only when the caller asks for it
            // (default false, read the way ClientSessionStarter reads it), and that update is the
            // work update_database is weighed for. Without it the call keeps its previous answer,
            // the delegate's own name, which weighs nothing.
            return JsonUtils.extractBooleanArgument(arguments, "updateBeforeLaunch", false) //$NON-NLS-1$
                ? "update_database" : ClientSessionStarter.NAME; //$NON-NLS-1$
        }
        Supplier<IMcpTool> delegate = DESCRIBED.get(normalized);
        return delegate == null ? null : delegate.get().getName();
    }

    /**
     * Polls a long run this facade started, when the operation is one of the three that read
     * {@code runKey}.
     * <p>
     * {@code update_database} calls {@link DatabaseUpdater#execute}; the two snapshot operations go
     * through {@link DtSnapshotRunner}, which registers them under {@link
     * PendingWorkRegistry#SNAPSHOT}. The other operations read no key, so a live run must not exempt
     * them. The name returned is the one the run was started under, which is this facade's own for
     * the snapshots and the delegate's for an update.
     * </p>
     *
     * @param domain the registry domain the key was found in
     * @param operation the operation argument; may be {@code null}
     * @return the name the polling call runs under, or {@code null} when this call polls nothing
     */
    @Override
    public String resumes(String domain, String operation)
    {
        String normalized = JsonUtils.normalizeOperationToken(operation);
        if (PendingWorkRegistry.UPDATE.domain().equals(domain))
        {
            return "update_database".equals(normalized) ? DatabaseUpdater.NAME : null; //$NON-NLS-1$
        }
        if (PendingWorkRegistry.SNAPSHOT.domain().equals(domain))
        {
            return DtSnapshotRunner.EXPORT_OPERATION.equals(normalized)
                || DtSnapshotRunner.RESTORE_OPERATION.equals(normalized) ? NAME : null;
        }
        return null;
    }

    private static Map<String, Supplier<IMcpTool>> buildDescribed()
    {
        Map<String, Supplier<IMcpTool>> m = new LinkedHashMap<>();
        m.put("get_applications", ApplicationsReader::new); //$NON-NLS-1$
        m.put("list_registered_infobases", RegisteredInfobasesReader::new); //$NON-NLS-1$
        m.put("read_event_log", EventLogTool::new); //$NON-NLS-1$
        m.put("create_infobase", InfobaseCreator::new); //$NON-NLS-1$
        m.put("register_infobase", InfobaseRegistrar::new); //$NON-NLS-1$
        m.put("delete_infobase", InfobaseRemover::new); //$NON-NLS-1$
        m.put("set_infobase_credentials", InfobaseCredentialsWriter::new); //$NON-NLS-1$
        m.put("create_launch_config", LaunchConfigCreator::new); //$NON-NLS-1$
        m.put("start_client", ClientSessionStarter::new); //$NON-NLS-1$
        m.put("branch_infobase", BranchInfobaseTool::new); //$NON-NLS-1$
        m.put("update_database", DatabaseUpdater::new); //$NON-NLS-1$
        return Collections.unmodifiableMap(m);
    }

    private static final Map<String, String> OPS = buildOpsCatalog();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Infobase and launch administration - list applications, create / register / delete an " //$NON-NLS-1$
            + "infobase, set credentials, create a launch configuration, start a 1C client from " //$NON-NLS-1$
            + "one, update the database, dump or load the whole infobase as a .dt file, control " //$NON-NLS-1$
            + "EDT<->infobase sync. Operations: " //$NON-NLS-1$
            + "get_applications, list_registered_infobases, read_event_log, create_infobase, " //$NON-NLS-1$
            + "register_infobase, delete_infobase, " //$NON-NLS-1$
            + "set_infobase_credentials, " //$NON-NLS-1$
            + "create_launch_config, start_client, branch_infobase, update_database, " //$NON-NLS-1$
            + "inspect_database_sync, export_database_snapshot, restore_database_snapshot, sync_control, " //$NON-NLS-1$
            + "help. Pass operation=<name>; remaining parameters follow the per-operation " //$NON-NLS-1$
            + "contracts (call operation=help for the catalog). create_infobase / " //$NON-NLS-1$
            + "register_infobase / delete_infobase / set_infobase_credentials / update_database / " //$NON-NLS-1$
            + "export_database_snapshot / restore_database_snapshot mutate, and a load replaces " //$NON-NLS-1$
            + "everything the infobase holds; inspect_database_sync reads and changes nothing. " //$NON-NLS-1$
            + "A real run of update_database / export_database_snapshot / " //$NON-NLS-1$
            + "restore_database_snapshot / sync_control may reply with a Pending status and " //$NON-NLS-1$
            + "a runKey to resume. update_database takes dryRun to answer what an update would " //$NON-NLS-1$
            + "face and start nothing, answered in place with no runKey, and protectData (default on) " //$NON-NLS-1$
            + "to stop before the update when it would delete data, answering dataLossTables " //$NON-NLS-1$
            + "(acceptDataLoss=true carries the deletion through). " //$NON-NLS-1$
            + "sync_control has its own " //$NON-NLS-1$
            + "inner operation (status / diagnose / suppress / ...): pass it as syncOperation, " //$NON-NLS-1$
            + "not operation - operation here always selects the infobase_admin routing target. " //$NON-NLS-1$
            + "The standalone tools remain available for back-compat."; //$NON-NLS-1$
    }

    /**
     * What each parameter carries beyond the sentence in its schema.
     * <p>
     * Every client holds the schema for the whole conversation, so it says what the parameter is
     * for in one sentence and names the values a caller picks from. The rest - when a value is
     * refused, what it does to what is already there, what a measurement showed - is answered when
     * operation=help topic=parameters asks for it. The two continue each other rather than
     * repeating, so neither can drift out of step with the other.
     * </p>
     */
    private static final Map<String, String> PARAMETER_RULES = buildParameterRules();

    /**
     * Builds the rules map.
     *
     * @return parameter name to the rules its description no longer carries
     */
    private static Map<String, String> buildParameterRules()
    {
        Map<String, String> rules = new LinkedHashMap<>();
        rules.put("operation", "Pass operation=help without other params for the operation catalog."); //$NON-NLS-1$
        rules.put("ignoreBranchBinding", "Declared here because the refusal tells the caller to pass it, and a " //$NON-NLS-1$
            + "facade that does not accept it leaves that instruction impossible to " //$NON-NLS-1$
            + "follow."); //$NON-NLS-1$
        rules.put("projectName", "Required for get_applications, set_infobase_credentials, " //$NON-NLS-1$
            + "create_launch_config, register_infobase and sync_control; optional association target " //$NON-NLS-1$
            + "for create_infobase; optional dissociation target for " //$NON-NLS-1$
            + "delete_infobase; required for update_database when " //$NON-NLS-1$
            + "launchConfigurationName is not supplied - on its own it is enough, " //$NON-NLS-1$
            + "applicationId may be omitted."); //$NON-NLS-1$
        rules.put("connectionString", "register_infobase only: the server infobase address, " //$NON-NLS-1$
            + "Srvr=...;Ref=.... A user or password in it (Usr, Pwd) is refused before anything " //$NON-NLS-1$
            + "is written - store those with set_infobase_credentials. Exactly one of path / " //$NON-NLS-1$
            + "connectionString."); //$NON-NLS-1$
        rules.put("makeDefault", "register_infobase only: by default the application becomes the " //$NON-NLS-1$
            + "project's default only when the project has none; pass true to replace the " //$NON-NLS-1$
            + "standing default, false to leave a standing one. EDT makes the first application " //$NON-NLS-1$
            + "the default on association, and false does not undo that; the answer says so. " //$NON-NLS-1$
            + "The answer names the default that stood before either way."); //$NON-NLS-1$
        rules.put("applicationId", "Optional for set_infobase_credentials when the project has a single " //$NON-NLS-1$
            + "application, and for update_database, which falls back to the " //$NON-NLS-1$
            + "project's default application and - for an extension project, which " //$NON-NLS-1$
            + "has no infobase of its own - to the default of the configuration it " //$NON-NLS-1$
            + "extends."); //$NON-NLS-1$
        rules.put("accessMode", "Optional - defaults to INFOBASE when userName is supplied, else OS."); //$NON-NLS-1$
        rules.put("protectData", "update_database only. The comparison fails open: it refuses only " //$NON-NLS-1$
            + "on a baseline and a model that were both read whole. No baseline in this workspace " //$NON-NLS-1$
            + "(a base never synchronized through EDT), a model whose walk was incomplete or an " //$NON-NLS-1$
            + "unreadable file each answer dataLossCheck with the reason instead of a refusal - " //$NON-NLS-1$
            + "nothing is claimed from a partial reading. A refusal is taken before the infobase is " //$NON-NLS-1$
            + "claimed and before any client is stopped, and says so."); //$NON-NLS-1$
        rules.put("acceptDataLoss", "update_database only. Accepts the deletion the comparison found, " //$NON-NLS-1$
            + "for this call: the update then runs with the table gone. It is never implied by " //$NON-NLS-1$
            + "anything else, and it accepts only what was named in dataLossTables - a deletion the " //$NON-NLS-1$
            + "comparison could not see is not covered by it."); //$NON-NLS-1$
        rules.put("syncOperation", "Kept separate from this facade's routing operation on purpose - " //$NON-NLS-1$
            + "sync_control has its own operation concept."); //$NON-NLS-1$
        rules.put("name", "sync_control release_support_snapshot: a protected snapshot is the only " //$NON-NLS-1$
            + "way back from a merge whose outcome is not known here; releasing it says that " //$NON-NLS-1$
            + "merge has been dealt with."); //$NON-NLS-1$
        rules.put("confirm", "Must be true for reseed_baseline, mark_synchronized, " //$NON-NLS-1$
            + "recover_stuck_merge and rebuild_dump_info. These are DANGEROUS - only on " //$NON-NLS-1$
            + "explicit user request and only when certain of the state."); //$NON-NLS-1$
        return Collections.unmodifiableMap(rules);
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", //$NON-NLS-1$
                "get_applications / list_registered_infobases / read_event_log / create_infobase / " //$NON-NLS-1$
                    + "register_infobase / delete_infobase / " //$NON-NLS-1$
                    + "set_infobase_credentials / create_launch_config / start_client / " //$NON-NLS-1$
                    + "branch_infobase / update_database / inspect_database_sync / " //$NON-NLS-1$
                    + "export_database_snapshot / restore_database_snapshot / sync_control / " //$NON-NLS-1$
                    + "help (snake_case " //$NON-NLS-1$
                    + "canonical; camelCase like getApplications is also accepted). " //$NON-NLS-1$
                    + "operation=help lists them; topic=<operation> answers what that one " //$NON-NLS-1$
                    + "takes.", true) //$NON-NLS-1$
            .stringProperty("topic", //$NON-NLS-1$
                "Help topic when operation=help. Without topic - lists all operations with " //$NON-NLS-1$
                    + "one-line summaries.") //$NON-NLS-1$
            .stringProperty("find", FacadeHelpSearch.FIND_DESCRIPTION)
            .stringProperty("action", //$NON-NLS-1$
                "branch_infobase: current (default) / list / bind / unbind.") //$NON-NLS-1$
            .booleanProperty("dryRun", //$NON-NLS-1$
                "update_database: answer what an update would face and start nothing.") //$NON-NLS-1$
            .booleanProperty("ignoreBranchBinding", //$NON-NLS-1$
                "update_database: update even when the branch is bound to another " //$NON-NLS-1$
                    + "application.") //$NON-NLS-1$
            .booleanProperty("ignoreDumpInfoFormat", //$NON-NLS-1$
                "update_database: update even when the stored ConfigDumpInfo.xml carries a " //$NON-NLS-1$
                    + "format other than the one this infobase's Designer wrote - the answer names " //$NON-NLS-1$
                    + "both formats when it stops you, and expects a FULL load when it does not.") //$NON-NLS-1$
            .stringProperty("branch", //$NON-NLS-1$
                "branch_infobase: the branch to bind or unbind. Defaults to the branch the " //$NON-NLS-1$
                    + "project is on.") //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name.") //$NON-NLS-1$
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application (infobase) id from get_applications.") //$NON-NLS-1$
            .stringProperty("name", //$NON-NLS-1$
                "Infobase name: required for create_infobase, delete_infobase and " //$NON-NLS-1$
                    + "register_infobase of a server infobase. sync_control " //$NON-NLS-1$
                    + "release_support_snapshot: the snapshot's file name from " //$NON-NLS-1$
                    + "list_support_snapshots.") //$NON-NLS-1$
            .stringProperty("path", //$NON-NLS-1$
                "create_infobase: absolute path to the infobase directory (required for that " //$NON-NLS-1$
                    + "operation) - an empty/new directory for the .1CD. register_infobase: an " //$NON-NLS-1$
                    + "existing file infobase directory. export_database_snapshot / " //$NON-NLS-1$
                    + "restore_database_snapshot: the .dt file to write or to load (required for " //$NON-NLS-1$
                    + "both); an existing file at that path is refused, not replaced.") //$NON-NLS-1$
            .stringProperty("backupTo", //$NON-NLS-1$
                "restore_database_snapshot: where the backup of the infobase's current contents " //$NON-NLS-1$
                    + "goes before the load starts. Omitted - beside the .dt being loaded, named " //$NON-NLS-1$
                    + "after it and stamped with the time. The load does not start until the backup " //$NON-NLS-1$
                    + "is written; an existing file at the backup path is refused.") //$NON-NLS-1$
            .stringProperty("connectionString", //$NON-NLS-1$
                "register_infobase: a server infobase address, Srvr=<server>;Ref=<infobase>.") //$NON-NLS-1$
            .booleanProperty("makeDefault", //$NON-NLS-1$
                "register_infobase: make the application the project's default (omitted: only " //$NON-NLS-1$
                    + "when it has none).") //$NON-NLS-1$
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
                "set_infobase_credentials / register_infobase: INFOBASE (user + password) or " //$NON-NLS-1$
                    + "OS (pass-through, no user/password).") //$NON-NLS-1$
            .stringProperty("userName", //$NON-NLS-1$
                "set_infobase_credentials / register_infobase: infobase user name (for " //$NON-NLS-1$
                    + "INFOBASE access).") //$NON-NLS-1$
            .stringProperty("password", //$NON-NLS-1$
                "set_infobase_credentials / register_infobase: infobase password (for INFOBASE " //$NON-NLS-1$
                    + "access). Stored encrypted; never logged or returned.") //$NON-NLS-1$
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
            .stringProperty("startupOption", //$NON-NLS-1$
                "start_client: the /C startup string for the client that starts; the saved " //$NON-NLS-1$
                    + "configuration is not changed. Refused while its client is already running - " //$NON-NLS-1$
                    + "that one was not started with it.") //$NON-NLS-1$
            .stringProperty("clientType", //$NON-NLS-1$
                "start_client: the client to start - thin, thick or web. Omitted: the launch " //$NON-NLS-1$
                    + "configuration's own, thin for one created here, and thick whenever the run " //$NON-NLS-1$
                    + "mode is ordinary. Applied to this launch's own configuration copy; a " //$NON-NLS-1$
                    + "configuration created here is saved with it.") //$NON-NLS-1$
            .stringProperty("runMode", //$NON-NLS-1$
                "start_client: ordinary or managed; omitted, the configuration's default run " //$NON-NLS-1$
                    + "mode. Ordinary puts /RunModeOrdinaryApplication among the infobase's " //$NON-NLS-1$
                    + "additional launch parameters for this EDT session, managed takes it out; " //$NON-NLS-1$
                    + "the list on disk is not written. The answer says what changed.") //$NON-NLS-1$
            .stringProperty("waitForEndpoint", //$NON-NLS-1$
                "start_client: wait for this URL to answer before reporting started - ready is " //$NON-NLS-1$
                    + "any final status below 500, at most five redirects. A client whose " //$NON-NLS-1$
                    + "endpoint never answers is NOT stopped; the refusal says so.") //$NON-NLS-1$
            .integerProperty("endpointTimeoutSeconds", //$NON-NLS-1$
                "start_client: how long to wait for waitForEndpoint, default 20, limit 50. " //$NON-NLS-1$
                    + "Refused without waitForEndpoint.") //$NON-NLS-1$
            .booleanProperty("fullUpdate", //$NON-NLS-1$
                "update_database: true triggers a full reload; false runs an incremental " //$NON-NLS-1$
                    + "update instead (default false).") //$NON-NLS-1$
            .booleanProperty("autoRestructure", //$NON-NLS-1$
                "update_database: apply infobase restructuring automatically when required " //$NON-NLS-1$
                    + "(default true).")
            .booleanProperty("protectData", //$NON-NLS-1$
                "update_database: before starting, compare the infobase's synchronization " //$NON-NLS-1$
                    + "baseline with the model; a data-carrying entity the base holds and the " //$NON-NLS-1$
                    + "model does not stops the call with status=confirmationRequired and the " //$NON-NLS-1$
                    + "addresses in dataLossTables (default true).")
            .booleanProperty("acceptDataLoss", //$NON-NLS-1$
                "update_database: carry through the deletion protectData found, so the update " //$NON-NLS-1$
                    + "runs and the table is dropped (default false - the call is refused " //$NON-NLS-1$
                    + "instead).") //$NON-NLS-1$
            .booleanProperty("autoFreeClients", //$NON-NLS-1$
                "update_database: before updating, stop this project's own matching " //$NON-NLS-1$
                    + "runtime-client sessions so they cannot keep the infobase locked " //$NON-NLS-1$
                    + "(default false).") //$NON-NLS-1$
            .stringProperty("timeoutSeconds", //$NON-NLS-1$
                "update_database: soft wait limit in seconds (5-120, default 30) before " //$NON-NLS-1$
                    + "replying Pending with a runKey to resume. export_database_snapshot / " //$NON-NLS-1$
                    + "restore_database_snapshot: the same soft wait (waitSeconds is accepted as " //$NON-NLS-1$
                    + "an alias); a dump or load of a large infobase routinely outlives it and is " //$NON-NLS-1$
                    + "collected with the runKey. sync_control " //$NON-NLS-1$
                    + "syncOperation=rebuild_dump_info: how long each Designer run is waited " //$NON-NLS-1$
                    + "for (60-3600, default 600).") //$NON-NLS-1$
            .stringProperty("runKey", //$NON-NLS-1$
                "update_database / export_database_snapshot / restore_database_snapshot: " //$NON-NLS-1$
                    + "resumes a Pending run issued earlier with this runKey; other params are " //$NON-NLS-1$
                    + "ignored once runKey is supplied.") //$NON-NLS-1$
            .booleanProperty("cancel", //$NON-NLS-1$
                "update_database / export_database_snapshot / restore_database_snapshot: " //$NON-NLS-1$
                    + "combined with runKey, detach and stop tracking that run (best-effort " //$NON-NLS-1$
                    + "only; a load that has begun is not undone).") //$NON-NLS-1$
            .booleanProperty("statusOnly", //$NON-NLS-1$
                "update_database: read what updates are being tracked and start nothing - " //$NON-NLS-1$
                    + "runKeys, state, elapsed time. projectName filters by project; any other " //$NON-NLS-1$
                    + "run-shaping parameter beside runKey or cancel is refused. A snapshot run " //$NON-NLS-1$
                    + "is not in this list; poll it with its own runKey.") //$NON-NLS-1$
            .booleanProperty("refreshWorkspace", //$NON-NLS-1$
                "update_database: refresh the project (and an extension's parent) from disk " //$NON-NLS-1$
                    + "before the update state is read (default true); the answer carries " //$NON-NLS-1$
                    + "workspaceRefresh.changedResources.") //$NON-NLS-1$
            .stringProperty("syncOperation", //$NON-NLS-1$
                "sync_control's OWN action - status / diagnose / diagnose_delta / suppress " //$NON-NLS-1$
                    + "/ reseed_baseline / mark_synchronized / diagnose_stuck_locks / " //$NON-NLS-1$
                    + "recover_stuck_merge / list_support_snapshots / release_support_snapshot / " //$NON-NLS-1$
                    + "rebuild_dump_info " //$NON-NLS-1$
                    + "(required when operation=sync_control).") //$NON-NLS-1$
            .booleanProperty("enabled", //$NON-NLS-1$
                "sync_control syncOperation=suppress: true = suppress synchronization for " //$NON-NLS-1$
                    + "the project (skip it on update), false = re-enable.") //$NON-NLS-1$
            .stringProperty("infobaseUuid", //$NON-NLS-1$
                "sync_control syncOperation=reseed_baseline / mark_synchronized / " //$NON-NLS-1$
                    + "recover_stuck_merge: the target infobase (an infobaseUuid from " //$NON-NLS-1$
                    + "syncOperation=status / diagnose_stuck_locks).") //$NON-NLS-1$
            .booleanProperty("confirm", //$NON-NLS-1$
                "sync_control: reseed_baseline, mark_synchronized, recover_stuck_merge, " //$NON-NLS-1$
                    + "rebuild_dump_info - must be true.") //$NON-NLS-1$
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
                + "list_registered_infobases / read_event_log / " //$NON-NLS-1$
                + "create_infobase / register_infobase / delete_infobase / set_infobase_credentials / " //$NON-NLS-1$
                + "create_launch_config / start_client / branch_infobase / update_database / " //$NON-NLS-1$
                + "inspect_database_sync / export_database_snapshot / restore_database_snapshot / " //$NON-NLS-1$
                + "sync_control / " //$NON-NLS-1$
                + "help.").toJson(); //$NON-NLS-1$
        }
        operation = JsonUtils.normalizeOperationToken(operation);
        if ("help".equals(operation)) //$NON-NLS-1$
        {
            return buildHelp(JsonUtils.extractStringArgument(params, "topic"), //$NON-NLS-1$
                JsonUtils.extractStringArgument(params, "find"), getInputSchema()); //$NON-NLS-1$
        }
        if (!OPS.containsKey(operation))
        {
            return ToolResult.error("Unknown operation '" + operation + "'." //$NON-NLS-1$ //$NON-NLS-2$
                + FacadeHelpSearch.closestMatches(operation, OPS.keySet(),
                    FacadeHelpSearch.describe(buildHelp(null, null, getInputSchema())))
                + "\n\nAllowed: " + String.join(" / ", OPS.keySet()) //$NON-NLS-1$ //$NON-NLS-2$
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
            case "list_registered_infobases": //$NON-NLS-1$
                return new RegisteredInfobasesReader().execute(params);
            case "read_event_log": //$NON-NLS-1$
                return new EventLogTool().execute(params);
            case "create_infobase": //$NON-NLS-1$
                return new InfobaseCreator().execute(params);
            case "register_infobase": //$NON-NLS-1$
                return new InfobaseRegistrar().execute(params);
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
            // A facade-run operation with no standalone behind it: the inspection is this
            // facade's own work, which is also why DESCRIBED does not name it - there is no
            // delegate schema to describe it from.
            case "inspect_database_sync": //$NON-NLS-1$
                return DatabaseSyncInspector.inspect(params);
            case "export_database_snapshot": //$NON-NLS-1$
                return runSnapshotExport(params);
            case "restore_database_snapshot": //$NON-NLS-1$
                return runSnapshotRestore(params);
            case "sync_control": //$NON-NLS-1$
                return routeSyncControl(params);
            default:
                return ToolResult.error("Unhandled operation: " + operation).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Runs {@code export_database_snapshot}: reads the arguments a dump takes off the call and hands
     * them to {@link DtSnapshotRunner}, which owns the guards, the run and the answer.
     * <p>
     * The reads happen here rather than in the runner so the runner sees a plain request and stays
     * callable from a test without a facade. Nothing is created or updated at this point: the file
     * and its directory are the runner's business, made only once a run actually starts.
     * </p>
     *
     * @param params the call arguments
     * @return the answer, or the {@code Pending} envelope naming the run's key
     */
    private static String runSnapshotExport(Map<String, String> params)
    {
        return DtSnapshotRunner.dispatchExport(params,
            JsonUtils.extractStringArgument(params, "projectName"), //$NON-NLS-1$
            JsonUtils.extractStringArgument(params, "applicationId"), //$NON-NLS-1$
            JsonUtils.extractStringArgument(params, "path"), //$NON-NLS-1$
            JsonUtils.extractStringArgument(params, "runKey"), //$NON-NLS-1$
            JsonUtils.extractBooleanArgument(params, "cancel", false), //$NON-NLS-1$
            DtSnapshotRunner.EDT_IO, NAME);
    }

    /**
     * Runs {@code restore_database_snapshot}: as the dump, with the path a load's backup must be
     * written to before the infobase is touched.
     *
     * @param params the call arguments
     * @return the answer, or the {@code Pending} envelope naming the run's key
     */
    private static String runSnapshotRestore(Map<String, String> params)
    {
        return DtSnapshotRunner.dispatchRestore(params,
            JsonUtils.extractStringArgument(params, "projectName"), //$NON-NLS-1$
            JsonUtils.extractStringArgument(params, "applicationId"), //$NON-NLS-1$
            JsonUtils.extractStringArgument(params, "path"), //$NON-NLS-1$
            JsonUtils.extractStringArgument(params, "backupTo"), //$NON-NLS-1$
            JsonUtils.extractStringArgument(params, "runKey"), //$NON-NLS-1$
            JsonUtils.extractBooleanArgument(params, "cancel", false), //$NON-NLS-1$
            DtSnapshotRunner.EDT_IO, NAME);
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
                + "mark_synchronized / diagnose_stuck_locks / recover_stuck_merge / " //$NON-NLS-1$
                + "list_support_snapshots / release_support_snapshot / rebuild_dump_info) - " //$NON-NLS-1$
                + "sync_control has its own inner operation, kept separate from this facade's " //$NON-NLS-1$
                + "routing operation.").toJson(); //$NON-NLS-1$
        }
        Map<String, String> forwarded = new HashMap<>(params);
        forwarded.put("operation", syncOperation); //$NON-NLS-1$
        return new SyncControlTool().execute(forwarded);
    }

    /** Every help topic, in the order the catalog names them: operations, then named topics. */
    private static final List<String> HELP_TOPICS = helpTopics();

    /**
     * The topics {@code find} searches, catalog first by the caller, these after.
     *
     * @return the topic names, never <code>null</code>
     */
    private static List<String> helpTopics()
    {
        List<String> topics = new ArrayList<>(OPS.keySet());
        topics.add("workflow"); //$NON-NLS-1$
        return Collections.unmodifiableList(topics);
    }

    private static String buildHelp(String topic, String find, String schema)
    {
        if (find != null && !find.isBlank())
        {
            return FacadeHelpSearch.search(NAME, find, topic, HELP_TOPICS, OPS.keySet(),
                asked -> buildHelp(asked, null, schema));
        }
        topic = JsonUtils.normalizeOperationToken(topic);
        if (topic == null || topic.isEmpty())
        {
            StringBuilder sb = new StringBuilder();
            sb.append("# infobase_admin - operations\n\n"); //$NON-NLS-1$
            sb.append("- **get_applications** - a project's applications, the infobase launch " //$NON-NLS-1$
                + "targets.\n"); //$NON-NLS-1$
            sb.append("- **list_registered_infobases** - every infobase registered in EDT, with its " //$NON-NLS-1$
                + "group, type, connection string (passwords masked), version and bound projects.\n"); //$NON-NLS-1$
            sb.append("- **create_infobase** - create a FILE infobase and register it in " //$NON-NLS-1$
                + "EDT's list. MUTATING.\n"); //$NON-NLS-1$
            sb.append("- **register_infobase** - register an EXISTING infobase (file or " //$NON-NLS-1$
                + "server) in EDT's list and associate it to a project in one call; a " //$NON-NLS-1$
                + "duplicate address is reused, a failed binding rolls the added entry back. " //$NON-NLS-1$
                + "A file infobase is named after its directory unless name is given. " //$NON-NLS-1$
                + "MUTATING.\n"); //$NON-NLS-1$
            sb.append("- **delete_infobase** - remove an infobase from EDT's list, optionally " //$NON-NLS-1$
                + "its .1CD on disk. MUTATING, DESTRUCTIVE with deleteContent=true.\n"); //$NON-NLS-1$
            sb.append("- **set_infobase_credentials** - store connection credentials in EDT's " //$NON-NLS-1$
                + "encrypted store. MUTATING.\n"); //$NON-NLS-1$
            sb.append("- **create_launch_config** - associate an existing infobase to a " //$NON-NLS-1$
                + "project. MUTATING.\n"); //$NON-NLS-1$
            sb.append("- **update_database** - push the current configuration into an " //$NON-NLS-1$
                + "application's infobase. MUTATING; may reply Pending with a runKey. A " //$NON-NLS-1$
                + "deletion is looked for before it starts (protectData, default on): the " //$NON-NLS-1$
                + "baseline of the base is matched against the model and an entity the base " //$NON-NLS-1$
                + "holds and the model does not stops the call, which names the addresses " //$NON-NLS-1$
                + "(dataLossTables) with status=confirmationRequired and starts nothing; " //$NON-NLS-1$
                + "acceptDataLoss=true carries it through.\n"); //$NON-NLS-1$
            sb.append("- **inspect_database_sync** - read what stands between a project and its " //$NON-NLS-1$
                + "infobase: the update state, the restructure-confirmation preference, and the " //$NON-NLS-1$
                + "data an update now would delete (pendingDataLoss). Read-only - starts " //$NON-NLS-1$
                + "nothing, writes nothing.\n"); //$NON-NLS-1$
            sb.append("- **export_database_snapshot** - dump the application's infobase into a " //$NON-NLS-1$
                + ".dt file with the platform's own thick client (path required, an existing file " //$NON-NLS-1$
                + "there is refused). MUTATING; may reply Pending with a runKey.\n"); //$NON-NLS-1$
            sb.append("- **restore_database_snapshot** - load a .dt file back into the " //$NON-NLS-1$
                + "application's infobase. The backup of what the infobase holds now is written " //$NON-NLS-1$
                + "first (backupTo, or beside the .dt), and the load does not start without it. " //$NON-NLS-1$
                + "MUTATING and DESTRUCTIVE - the load replaces everything the infobase holds; " //$NON-NLS-1$
                + "may reply Pending with a runKey.\n"); //$NON-NLS-1$
            sb.append("- **sync_control** - inspect and control EDT<->infobase " //$NON-NLS-1$
                + "synchronization. Pass its own action as syncOperation, not operation; " //$NON-NLS-1$
                + "some syncOperation values (reseed_baseline, mark_synchronized, " //$NON-NLS-1$
                + "recover_stuck_merge) are DANGEROUS. rebuild_dump_info rewrites the stored " //$NON-NLS-1$
                + "ConfigDumpInfo.xml with the platform's own Designer dump.\n"); //$NON-NLS-1$
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
            sb.append("| Which infobases EDT knows, in which groups, bound to which projects | " //$NON-NLS-1$
                + "list_registered_infobases |\n"); //$NON-NLS-1$
            sb.append("| Create a brand-new FILE infobase | create_infobase |\n"); //$NON-NLS-1$
            sb.append("| Register an EXISTING infobase (file or server) and bind it to a " //$NON-NLS-1$
                + "project | register_infobase |\n"); //$NON-NLS-1$
            sb.append("| Remove an infobase from EDT's list | delete_infobase |\n"); //$NON-NLS-1$
            sb.append("| Store a user/password so update_database stops prompting | " //$NON-NLS-1$
                + "set_infobase_credentials |\n"); //$NON-NLS-1$
            sb.append("| Associate an existing infobase to a project | " //$NON-NLS-1$
                + "create_launch_config |\n"); //$NON-NLS-1$
            sb.append("| Push the configuration into an infobase | update_database |\n"); //$NON-NLS-1$
            sb.append("| Read the state between a project and its infobase, and what an update " //$NON-NLS-1$
                + "now would delete | inspect_database_sync |\n"); //$NON-NLS-1$
            sb.append("| Back the whole infobase up to a .dt file | export_database_snapshot |\n"); //$NON-NLS-1$
            sb.append("| Load a .dt file into the infobase, keeping what it holds now | " //$NON-NLS-1$
                + "restore_database_snapshot |\n"); //$NON-NLS-1$
            sb.append("| Predict or control whether the next update is FULL or incremental | " //$NON-NLS-1$
                + "sync_control (syncOperation=status / diagnose / suppress / ...) |\n"); //$NON-NLS-1$
            return sb.toString();
        }
        return FacadeParameterHelp.answer(topic, DESCRIBED, OPS.keySet(),
            "workflow", "InfobaseAdminFacadeTool", schema, PARAMETER_RULES, //$NON-NLS-1$
            buildHelp(null, null, schema));
    }

    private static Map<String, String> buildOpsCatalog()
    {
        Map<String, String> m = new LinkedHashMap<>();
        for (String op : Arrays.asList(
            "get_applications", "list_registered_infobases", "read_event_log", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "create_infobase", //$NON-NLS-1$
            "register_infobase", //$NON-NLS-1$
            "delete_infobase", "set_infobase_credentials", //$NON-NLS-1$ //$NON-NLS-2$
            "create_launch_config", "start_client", //$NON-NLS-1$ //$NON-NLS-2$
            "branch_infobase", //$NON-NLS-1$
            "update_database", "inspect_database_sync", "export_database_snapshot", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "restore_database_snapshot", "sync_control")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            m.put(op, op);
        }
        return Collections.unmodifiableMap(m);
    }
}
