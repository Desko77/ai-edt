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
import ru.aiedt.mcp.server.support.GateCheck;
import ru.aiedt.mcp.server.support.PendingWorkRegistry;
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
            // Its inner action travels as syncOperation and is forwarded verbatim, so the heavy
            // actions (rebuild_dump_info, retrieve_database_changes) are named from that argument,
            // not from DESCRIBED - which cannot hold sync_control, whose delegate would need this
            // facade's own remapping.
            // SyncControlTool answers the same question for a standalone call; both spellings of
            // the action are compared exactly, as its own dispatch does.
            String syncOperation = JsonUtils.extractStringArgument(arguments, "syncOperation"); //$NON-NLS-1$
            if ("rebuild_dump_info".equals(syncOperation)) //$NON-NLS-1$
            {
                return "rebuild_dump_info"; //$NON-NLS-1$
            }
            if ("retrieve_database_changes".equals(syncOperation)) //$NON-NLS-1$
            {
                return "retrieve_database_changes"; //$NON-NLS-1$
            }
            return null;
        }
        if ("start_client".equals(normalized)) //$NON-NLS-1$
        {
            // The delegate updates the infobase before starting only when the caller asks for it
            // (default false, decided the way ClientSessionStarter decides it), and that update is
            // the work update_database is weighed for. Without it the call keeps its previous
            // answer, the delegate's own name, which weighs nothing.
            return DebugSessionStarter.launchUpdate(arguments, false).update //$NON-NLS-1$
                ? "update_database" : ClientSessionStarter.NAME; //$NON-NLS-1$
        }
        Supplier<IMcpTool> delegate = DESCRIBED.get(normalized);
        return delegate == null ? null : delegate.get().getName();
    }

    /**
     * Polls a long run this facade started, when the operation is one that reads {@code runKey}.
     * <p>
     * {@code update_database} calls {@link DatabaseUpdater#execute}; the two snapshot operations go
     * through {@link DtSnapshotRunner}, which registers them under {@link
     * PendingWorkRegistry#SNAPSHOT}; a pull is started under {@code retrieve_database_changes} and
     * polled through this facade as {@code sync_control}. The other operations read no key, so a
     * live run must not exempt them. The name returned is the one the run was started under, which
     * is this facade's own for the snapshots and the delegate's for an update and a pull.
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
        if (PendingWorkRegistry.RETRIEVE.domain().equals(domain))
        {
            return "sync_control".equals(normalized) ? "retrieve_database_changes" : null; //$NON-NLS-1$ //$NON-NLS-2$
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
        return "Infobase and launch administration - list, create, register and delete infobases, set credentials, create a launch configuration, start a 1C client, update the database, dump or load the whole infobase as a .dt, and control EDT<->infobase sync. operation=help lists the operations and topics. create_infobase / register_infobase / delete_infobase / set_infobase_credentials / update_database / export_database_snapshot / restore_database_snapshot mutate; a load replaces everything the infobase holds, writes its backup first, marks the stored copy and names it when the mark could not be written; inspect_database_sync reads only. Runs of update_database / export_database_snapshot / restore_database_snapshot / sync_control may answer Pending with a runKey. update_database dryRun answers in place with no runKey; protectData (default on) stops before deleting data and answers dataLossTables (acceptDataLoss=true carries it through). sync_control's own action travels as syncOperation."; //$NON-NLS-1$
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
        rules.put("verifyInfobaseContent", "update_database only. Off by default. When on, an " //$NON-NLS-1$
            + "incremental update reads the infobase's own ConfigDumpInfo before it starts and " //$NON-NLS-1$
            + "refuses with infobaseChanged when that dump does not match the stored copy. A dry " //$NON-NLS-1$
            + "run and a full update do not read it and say so."); //$NON-NLS-1$
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
        rules.put("launchConfigurationName", "Preferred over projectName + applicationId; " //$NON-NLS-1$
            + "list_configurations shows the names."); //$NON-NLS-1$
        rules.put("backupTo", "Omitted, the backup goes beside the .dt being loaded, named " //$NON-NLS-1$
            + "after it and stamped with the time."); //$NON-NLS-1$
        rules.put("clientType", "Applied to this launch's own configuration copy; a " //$NON-NLS-1$
            + "configuration created here is saved with it."); //$NON-NLS-1$
        rules.put("runMode", "The additional launch parameters list on disk is not written; " //$NON-NLS-1$
            + "the answer says what changed."); //$NON-NLS-1$
        rules.put("autoFreeClients", "The stopped sessions cannot keep the infobase locked."); //$NON-NLS-1$
        rules.put("timeoutSeconds", "The same budget bounds " //$NON-NLS-1$
            + "BuildTaskHelper.waitForBuildAndDerivedData; the build-job wait is unbounded " //$NON-NLS-1$
            + "and running out ends quietly. The platform pull is not cancelled."); //$NON-NLS-1$
        rules.put("runKey", "sync_control syncOperation=retrieve_database_changes: how long " //$NON-NLS-1$
            + "this call waits is timeoutSeconds."); //$NON-NLS-1$
        rules.put("statusOnly", "projectName filters by project; a snapshot run is polled " //$NON-NLS-1$
            + "with its own runKey."); //$NON-NLS-1$
        rules.put("refreshWorkspace", "The answer carries " //$NON-NLS-1$
            + "workspaceRefresh.changedResources."); //$NON-NLS-1$
        rules.put("replaceLocal", "True takes the infobase's version of those objects."); //$NON-NLS-1$
        rules.put("markSynchronized", "The reason is on the answer when baselineMarked is " //$NON-NLS-1$
            + "false."); //$NON-NLS-1$
        rules.put("infobaseUuid", "Take the value from syncOperation=status or " //$NON-NLS-1$
            + "diagnose_stuck_locks."); //$NON-NLS-1$
        rules.put("startupOption", "An external-object binding and /Execute are set by the " //$NON-NLS-1$
            + "environment from that configuration, not by this argument."); //$NON-NLS-1$
        rules.put("allowSecondSession", "The running one is reported instead of a second " //$NON-NLS-1$
            + "start."); //$NON-NLS-1$
        rules.put("ignoreDumpInfoFormat", "The answer names both formats; when it does not " //$NON-NLS-1$
            + "stop you it expects a FULL load."); //$NON-NLS-1$
        rules.put("platform", "Blank means the latest available platform."); //$NON-NLS-1$
        rules.put("templateCf", "Loaded into the new infobase on create."); //$NON-NLS-1$
        rules.put("deleteContent", "False removes only the reference from EDT's list."); //$NON-NLS-1$
        rules.put("infobaseName", "get_applications and list_registered_infobases show the " //$NON-NLS-1$
            + "names."); //$NON-NLS-1$
        rules.put("fullUpdate", "Falls back to an incremental update otherwise."); //$NON-NLS-1$
        rules.put("autoRestructure", "Applied only when the platform requires restructuring."); //$NON-NLS-1$
        return Collections.unmodifiableMap(rules);
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", "get_applications / list_registered_infobases / read_event_log / create_infobase / register_infobase / delete_infobase / set_infobase_credentials / create_launch_config / start_client / branch_infobase / update_database / inspect_database_sync / export_database_snapshot / restore_database_snapshot / sync_control / help (snake_case canonical; camelCase like getApplications accepted). help lists them; topic=<operation> answers its parameters.", true) //$NON-NLS-1$
            .stringProperty("topic", "Help topic for operation=help (without topic: the catalog).") //$NON-NLS-1$
            .stringProperty("find", FacadeHelpSearch.FIND_DESCRIPTION)
            .stringProperty("action", //$NON-NLS-1$
                "branch_infobase: current (default) / list / bind / unbind.") //$NON-NLS-1$
            .booleanProperty("dryRun", //$NON-NLS-1$
                "update_database: answer what an update would face and start nothing.") //$NON-NLS-1$
            .booleanProperty("ignoreBranchBinding", //$NON-NLS-1$
                "update_database: update even when the branch is bound to another " //$NON-NLS-1$
                    + "application.") //$NON-NLS-1$
            .booleanProperty("ignoreDumpInfoFormat", "update_database: apply the ConfigDumpInfo format change; the answer names both formats when it refuses; when it proceeds it expects a FULL load.") //$NON-NLS-1$
            .stringProperty("branch", //$NON-NLS-1$
                "branch_infobase: the branch to bind or unbind. Defaults to the branch the " //$NON-NLS-1$
                    + "project is on.") //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name.") //$NON-NLS-1$
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application (infobase) id from get_applications. start_client: an explicit id must belong to the named project; invalid ids never fall back. If omitted, use its registered default, otherwise its sole application; stale defaults and ambiguity refuse.") //$NON-NLS-1$
            .stringArrayProperty("excludeEvents", "read_event_log: omit events by whole name or prefix (ending *, e.g. _$Transaction$_*), case-insensitive, before limit.") //$NON-NLS-1$
            .stringProperty("name", "create_infobase / delete_infobase / server register_infobase: infobase name; sync_control release_support_snapshot: snapshot file name from list_support_snapshots.") //$NON-NLS-1$
            .stringProperty("path", "create_infobase: new/empty infobase directory. register_infobase: existing directory. snapshots: required .dt path; export_database_snapshot: an existing one is refused; restore_database_snapshot requires an existing input.") //$NON-NLS-1$
            .stringProperty("backupTo", "restore_database_snapshot: backup path written before the load; an existing file is refused; omitted: beside the .dt.") //$NON-NLS-1$
            .stringProperty("connectionString", //$NON-NLS-1$
                "register_infobase: a server infobase address, Srvr=<server>;Ref=<infobase>.") //$NON-NLS-1$
            .booleanProperty("makeDefault", //$NON-NLS-1$
                "register_infobase: make the application the project's default (omitted: only " //$NON-NLS-1$
                    + "when it has none).") //$NON-NLS-1$
            .stringProperty("platform", "create_infobase: platform version (blank = latest).") //$NON-NLS-1$
            .stringProperty("templateCf", "create_infobase: optional .cf/.cfe to load on create.") //$NON-NLS-1$
            .booleanProperty("deleteContent", "delete_infobase: also delete the .1CD directory on disk (DESTRUCTIVE, default false).") //$NON-NLS-1$
            .stringProperty("infobaseName", "create_launch_config: existing infobase to associate (required).") //$NON-NLS-1$
            .stringProperty("accessMode", //$NON-NLS-1$
                "set_infobase_credentials / register_infobase: INFOBASE (user + password) or " //$NON-NLS-1$
                    + "OS (pass-through, no user/password).") //$NON-NLS-1$
            .stringProperty("userName", //$NON-NLS-1$
                "set_infobase_credentials / register_infobase: infobase user name (for " //$NON-NLS-1$
                    + "INFOBASE access).") //$NON-NLS-1$
            .stringProperty("password", //$NON-NLS-1$
                "set_infobase_credentials / register_infobase: infobase password (for INFOBASE " //$NON-NLS-1$
                    + "access). Stored encrypted; never logged or returned.") //$NON-NLS-1$
            .stringProperty("launchConfigurationName", "update_database / start_client: existing launch configuration name (preferred over projectName + applicationId). start_client: an explicit name never falls back; supplied projectName/applicationId must match its binding.") //$NON-NLS-1$
            .booleanProperty("updateBeforeLaunch", "start_client: update first (default false). Explicit true is refused when update_database is preset-disabled; a running client with no startupOption, waitForEndpoint, clientType or runMode answers alreadyRunning (started=false) before that gate.") //$NON-NLS-1$
            .booleanProperty("allowSecondSession", "start_client: start even when a client is already running (default false; the running one is reported).") //$NON-NLS-1$
            .stringProperty("startupOption", "start_client: /C only; applies to this launch's copy - the saved configuration is not changed. /Execute and the external-object binding come from that configuration. Refused while its client runs.") //$NON-NLS-1$
            .stringProperty("clientType", "start_client: thin / thick / web; default the configuration's, thin for one created here, thick for ordinary run mode.") //$NON-NLS-1$
            .stringProperty("runMode", "start_client: ordinary / managed; default the configuration's. Ordinary adds /RunModeOrdinaryApplication for this session only; managed removes it.") //$NON-NLS-1$
            .stringProperty("waitForEndpoint", "start_client: wait for this URL to answer (<500, at most five redirects); a client that never answers is not stopped.") //$NON-NLS-1$
            .integerProperty("endpointTimeoutSeconds", "start_client: wait in seconds for waitForEndpoint (default 20, max 50); refused without waitForEndpoint.") //$NON-NLS-1$
            .booleanProperty("fullUpdate", "update_database: full reload instead of incremental (default false).") //$NON-NLS-1$
            .booleanProperty("verifyInfobaseContent", "update_database: refuse an incremental update whose infobase ConfigDumpInfo differs (default false).") //$NON-NLS-1$
            .booleanProperty("autoRestructure", "update_database: apply restructuring automatically (default true).")
            .booleanProperty("protectData", "update_database: compare baseline and model before starting; a data-loss difference stops the call with confirmationRequired and dataLossTables (default true).")
            .booleanProperty("acceptDataLoss", "update_database: carry through the deletion protectData found (default false: refused).") //$NON-NLS-1$
            .booleanProperty("autoFreeClients", "update_database: stop this project's matching clients first (default false).") //$NON-NLS-1$
            .stringProperty("timeoutSeconds", "update_database: wait before Pending (5-120, default 30). Snapshots: the same wait (alias waitSeconds). rebuild_dump_info: per Designer run (60-3600, default 600). retrieve_database_changes: 30-3600, default 300; also the BuildTaskHelper.waitForBuildAndDerivedData budget; the build-job wait is unbounded.") //$NON-NLS-1$
            .stringProperty("runKey", "update_database / snapshots: resume a Pending run; other params are ignored with runKey. retrieve_database_changes: resumes that pull.") //$NON-NLS-1$
            .booleanProperty("cancel", "update_database / snapshots / sync_control retrieve_database_changes with runKey: detach and stop tracking (best-effort). A load already begun is not undone and the platform call keeps running.") //$NON-NLS-1$
            .booleanProperty("statusOnly", "update_database: list tracked updates and start nothing; any other run-shaping parameter beside runKey or cancel is refused. Snapshot runs are polled by their own runKey.") //$NON-NLS-1$
            .booleanProperty("refreshWorkspace", "update_database: refresh the project (and an extension's parent) from disk first (default true).") //$NON-NLS-1$
            .stringProperty("syncOperation", //$NON-NLS-1$
                "sync_control's OWN action - status / diagnose / diagnose_delta / suppress " //$NON-NLS-1$
                    + "/ reseed_baseline / mark_synchronized / diagnose_stuck_locks / " //$NON-NLS-1$
                    + "recover_stuck_merge / list_support_snapshots / release_support_snapshot / " //$NON-NLS-1$
                    + "rebuild_dump_info / retrieve_database_changes " //$NON-NLS-1$
                    + "(required when operation=sync_control).") //$NON-NLS-1$
            .booleanProperty("replaceLocal", "retrieve_database_changes: true takes the infobase version when the project has changes (default false: refused).") //$NON-NLS-1$
            .booleanProperty("markSynchronized", "retrieve_database_changes: rewrite the baseline after a pull (default false); a failed rewrite is baselineMarked=false.") //$NON-NLS-1$
            .booleanProperty("enabled", //$NON-NLS-1$
                "sync_control syncOperation=suppress: true = suppress synchronization for " //$NON-NLS-1$
                    + "the project (skip it on update), false = re-enable.") //$NON-NLS-1$
            .stringProperty("infobaseUuid", "reseed_baseline / mark_synchronized / recover_stuck_merge: target infobaseUuid.") //$NON-NLS-1$
            .booleanProperty("confirm", "sync_control: reseed_baseline, mark_synchronized, recover_stuck_merge - and rebuild_dump_info - must be true.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    /**
     * The preset checks {@link #execute} applies to an operation, in the order it applies them.
     * <p>
     * Every operation this facade folds in is keyed on its own name - that IS the folded tool's
     * name - so one check is enough for each. An operation with no tool of its own is in nobody's
     * disabled set and passes. The method is public because the route guidance reads the same list
     * the call runs, and it lives in another package.
     * </p>
     *
     * @param operation the normalized operation name; may be {@code null}
     * @return the checks, never {@code null}; empty when there is no operation to check
     */
    public static List<GateCheck> gateChecksOf(String operation)
    {
        return operation == null ? Collections.emptyList()
            : Collections.singletonList(GateCheck.preset(operation));
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
        List<GateCheck> gateChecks = gateChecksOf(operation);
        String gateRefusal = GateCheck.firstRefusal(gateChecks);
        if (gateRefusal != null)
        {
            return ToolResult.error(gateRefusal).put("operation", operation).toJson(); //$NON-NLS-1$
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
                return ToolResult.error("Unhandled operation: " + operation + ". Allowed: " //$NON-NLS-1$ //$NON-NLS-2$
                    + String.join(" / ", OPS.keySet()) + " / help.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
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
                + "list_support_snapshots / release_support_snapshot / rebuild_dump_info / " //$NON-NLS-1$
                + "retrieve_database_changes) - " //$NON-NLS-1$
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
                + "acceptDataLoss=true carries it through. The platform's active-flow flag is " //$NON-NLS-1$
                + "read for the infobase under the claim: an active synchronization flow of this EDT refuses " //$NON-NLS-1$
                + "with busy naming the infobase, and a flow begun after that read does not " //$NON-NLS-1$
                + "stop the update.\n"); //$NON-NLS-1$
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
                + "ConfigDumpInfo.xml with the platform's own Designer dump. " //$NON-NLS-1$
                + "retrieve_database_changes pulls the infobase's changes into the project " //$NON-NLS-1$
                + "(replaceLocal, markSynchronized); a slow pull answers Pending with a runKey.\n"); //$NON-NLS-1$
            sb.append("- **start_client** - start a 1C client from a saved launch configuration, " //$NON-NLS-1$
                + "with that configuration's attributes, without a debugger. Dump readiness is " //$NON-NLS-1$
                + "not checked. An external object under debug is launch_debugger action=launch " //$NON-NLS-1$
                + "with externalObjectName. startupOption carries /C only; the external-object " //$NON-NLS-1$
                + "binding and /Execute come from that configuration. updateBeforeLaunch defaults " //$NON-NLS-1$
                + "to false; explicit true is refused when a preset disabled update_database. A " //$NON-NLS-1$
                + "running client with no startupOption, waitForEndpoint, clientType or runMode " //$NON-NLS-1$
                + "answers alreadyRunning (started=false) before that gate.\n"); //$NON-NLS-1$
            sb.append("- **branch_infobase** - bind a git branch to an application, so that " //$NON-NLS-1$
                + "update_database refuses an infobase belonging to another branch. Actions: " //$NON-NLS-1$
                + "current (default) / list / bind / unbind. For an extension project the " //$NON-NLS-1$
                + "binding lives in the extension itself.\n"); //$NON-NLS-1$
            sb.append("- **read_event_log** - read a FILE infobase's event log: who logged in, " //$NON-NLS-1$
                + "what was posted, what the platform refused. Filterable by from / to / event " //$NON-NLS-1$
                + "/ user / severity.\n"); //$NON-NLS-1$
            sb.append("- **help** - this catalog. Pass topic=workflow for the operation picker " //$NON-NLS-1$
                + "guide.\n"); //$NON-NLS-1$
            sb.append("The standalone tools behind these operations remain callable for " //$NON-NLS-1$
                + "back-compat.\n"); //$NON-NLS-1$
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
            sb.append("| Start a client from a saved launch configuration, without a debugger | " //$NON-NLS-1$
                + "start_client |\n"); //$NON-NLS-1$
            sb.append("| Read a FILE infobase event log | read_event_log |\n"); //$NON-NLS-1$
            sb.append("\nstart_client uses the saved configuration and its attributes. Dump " //$NON-NLS-1$
                + "readiness is not checked. An external object under debug is launch_debugger " //$NON-NLS-1$
                + "action=launch with externalObjectName. startupOption carries /C only; the " //$NON-NLS-1$
                + "external-object binding and /Execute come from that configuration. " //$NON-NLS-1$
                + "updateBeforeLaunch defaults to false; explicit true is refused when a preset " //$NON-NLS-1$
                + "disabled update_database. A running client with no startupOption, " //$NON-NLS-1$
                + "waitForEndpoint, clientType or runMode answers alreadyRunning " //$NON-NLS-1$
                + "(started=false) before that gate.\n"); //$NON-NLS-1$
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
