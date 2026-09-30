/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.resource.EdtResourceMetadata;
import com._1c.g5.v8.dt.core.resource.IResourceStoreManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseChangesResolutionResult;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseEqualityState;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseSyncResolution;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseSynchronizationException;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.ObjectChangeType;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationStateManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.ModelFactory;
import com._1c.g5.wiring.ServiceAccess;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BmCommonModuleGuards;
import ru.aiedt.mcp.server.support.BuildTaskHelper;
import ru.aiedt.mcp.server.support.DatabaseChangesResolver;
import ru.aiedt.mcp.server.support.DumpInfoRebuilder;
import ru.aiedt.mcp.server.support.ErrorTags;
import ru.aiedt.mcp.server.support.InfobaseHolders;
import ru.aiedt.mcp.server.support.InfobaseIdentity;
import ru.aiedt.mcp.server.support.MonopolyLock;
import ru.aiedt.mcp.server.support.PendingEnvelope;
import ru.aiedt.mcp.server.support.PendingWorkRegistry;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.ProjectStateGuard;
import ru.aiedt.mcp.server.support.RemovedObjectFiles;
import ru.aiedt.mcp.server.support.SyncBaseline;
import ru.aiedt.mcp.server.support.SupportSnapshotStore;
import ru.aiedt.mcp.server.support.TextSuggest;
import ru.aiedt.mcp.server.support.TimeoutArgs;

/**
 * Inspect and control EDT&lt;-&gt;infobase synchronization (the engine that decides
 * full-config-reload vs incremental on "Update infobase").
 *
 * <p>EDT keeps a per-infobase baseline at {@code ib-sync\ss\<infobaseUuid>\index.idx} holding
 * the configuration UUID recorded at the last successful sync. EDT 2026 keeps that store in the
 * project's private working location inside the workspace
 * ({@code .metadata\.plugins\org.eclipse.core.resources\.projects\<project>\com._1c.g5.v8.dt.platform.services.core\ib-sync\ss},
 * beside the {@code ConfigDumpInfo.xml} of the infobase); older EDT kept it under
 * {@code %APPDATA%\.1cedt\ib-sync\ss}. Both are read, the workspace store first. {@code UpdateInfobaseFlow.start()} compares
 * the project's live {@code Configuration} UUID to that baseline UUID; a mismatch (or a
 * missing/empty baseline) forces a FULL reload of the whole configuration - slow on large
 * configs (ERP). See {@code operation=status}.
 *
 * <ul>
 * <li>{@code status} - READ-ONLY. Reads the project's {@code Configuration.mdo} UUID and the
 * sync-store baselines, lists every application the project is bound to with the state of its own
 * baseline, then predicts whether the next update will be FULL or INCREMENTAL and explains why
 * (e.g. "no matching baseline" / "indexes diverged" / "this binding has no baseline at all").</li>
 * <li>{@code suppress} - turns synchronization on/off for a project via the platform
 * {@link IInfobaseSynchronizationManager}. Suppressing the on-support main configuration makes
 * an "Update infobase" push ONLY the extensions (the main config is skipped), avoiding a full
 * reload. Reversible; the flag is in-memory (per EDT session).</li>
 * </ul>
 */
public class SyncControlTool implements IMcpTool
{
    public static final String NAME = "sync_control"; //$NON-NLS-1$

    private static final int MAX_LISTED_BASELINES = 25;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `infobase_admin` `operation=sync_control`; prefer the facade for new " //$NON-NLS-1$
            + "prompts. Through the facade the inner action goes in `syncOperation`; called directly, " //$NON-NLS-1$
            + "this tool takes it in `operation`. " //$NON-NLS-1$
            + "Inspect and control EDT<->infobase synchronization (full-reload vs incremental). " //$NON-NLS-1$
            + "operation=status (read-only): predicts whether the next 'Update infobase' will be a FULL " //$NON-NLS-1$
            + "configuration reload or incremental, by comparing the project's Configuration UUID with the " //$NON-NLS-1$
            + "EDT sync baseline (ib-sync/ss in the project's working location inside the workspace, or " //$NON-NLS-1$
            + "%APPDATA%/.1cedt/ib-sync/ss on older EDT) - diagnoses 'indexes diverged / will be full'. " //$NON-NLS-1$
            + "operation=diagnose (read-only): for each baseline matching the project, reports the live " //$NON-NLS-1$
            + "getEqualityState + isConnected and the resulting application update state - explains exactly why the " //$NON-NLS-1$
            + "pre-launch 'load changed objects' dialog appears (UPDATED = no dialog). " //$NON-NLS-1$
            + "operation=suppress (enabled=true/false): turns sync off/on for a project; suppressing the " //$NON-NLS-1$
            + "on-support main configuration makes an update push ONLY the extensions (no full reload). Reversible. " //$NON-NLS-1$
            + "operation=reseed_baseline (infobaseUuid=... confirm=true): re-stamps a baseline's configuration UUID " //$NON-NLS-1$
            + "to match the project so the next update stays INCREMENTAL. " //$NON-NLS-1$
            + "operation=mark_synchronized (infobaseUuid=... confirm=true): tells EDT the current project state is " //$NON-NLS-1$
            + "fully synchronized with the infobase via EDT's own forceEdtSynchronization (updates the in-memory sync " //$NON-NLS-1$
            + "holder + writes the baseline through EDT's official writer + clears the sync timestamp) so " //$NON-NLS-1$
            + "getEqualityState becomes EQUAL immediately - no pre-launch 'update changed objects' prompt, no full " //$NON-NLS-1$
            + "reload, no restart - WITHOUT pushing anything to the infobase. " //$NON-NLS-1$
            + "operation=diagnose_stuck_locks (read-only): reports infobases whose synchronization flow is stuck " //$NON-NLS-1$
            + "'active' - an interrupted update left the flag set, blocking every subsequent update until EDT restart. " //$NON-NLS-1$
            + "operation=recover_stuck_merge (infobaseUuid=... confirm=true): force-clears that stuck flag so updates " //$NON-NLS-1$
            + "proceed without an EDT restart. " //$NON-NLS-1$
            + "operation=rebuild_dump_info (applicationId=... when several; confirm=true): rebuilds the stored " //$NON-NLS-1$
            + "ConfigDumpInfo.xml with the infobase platform's own Designer dump - the cure for a dump-info file " //$NON-NLS-1$
            + "whose format the platform does not understand (the answer to that is FullDump and every " //$NON-NLS-1$
            + "update silently becomes a full load; update_database names the mismatch before starting). The " //$NON-NLS-1$
            + "Designer is first asked for the dump-info alone, which takes seconds; the full hierarchical dump " //$NON-NLS-1$
            + "is the fallback only when that run finished without error and left no file. A failed quick run " //$NON-NLS-1$
            + "is refused with its own error. rebuildPath says which run produced the file and why. Releases " //$NON-NLS-1$
            + "the infobase for a Designer run, backs the previous file up beside it, replaces it, makes EDT " //$NON-NLS-1$
            + "re-read it and reconnects the infobase; a failed swap is rolled back from the backup. The format " //$NON-NLS-1$
            + "the new file carries is recorded for THIS infobase together with the platform it was measured on " //$NON-NLS-1$
            + "(formatPair). A record from another platform is not compared. Later checks compare against that " //$NON-NLS-1$
            + "record only when it was stored; a failed record is not described as a comparison the next update will make. " //$NON-NLS-1$
            + "operation=retrieve_database_changes (applicationId=... when the project has several; " //$NON-NLS-1$
            + "replaceLocal=false): pulls the changes made in the INFOBASE into the project, the opposite " //$NON-NLS-1$
            + "direction to update_database, through EDT's own synchronization manager. This is what makes a " //$NON-NLS-1$
            + "change made in Designer visible in the project. A project that carries changes of its own is " //$NON-NLS-1$
            + "REFUSED and left exactly as it was; replaceLocal=true takes the infobase's version of those " //$NON-NLS-1$
            + "objects and discards the project's changes to them. Sources of the objects the infobase no " //$NON-NLS-1$
            + "longer has are removed from the project afterwards, and the project is refreshed. " //$NON-NLS-1$
            + "markSynchronized=true then runs the same baseline rewrite as " //$NON-NLS-1$
            + "operation=mark_synchronized for the same infobase; a failed rewrite is " //$NON-NLS-1$
            + "baselineMarked=false with the reason on the answer. A thick client this EDT launched " //$NON-NLS-1$
            + "against the infobase is refused before the pull, by that launch's name. A call that " //$NON-NLS-1$
            + "outlasts timeoutSeconds answers Pending with a runKey; call again with that runKey to " //$NON-NLS-1$
            + "keep waiting. cancel=true with the runKey stops tracking and says when the platform " //$NON-NLS-1$
            + "call is still running - that call is not pulled back. " //$NON-NLS-1$
            + "reseed_baseline, mark_synchronized, recover_stuck_merge and rebuild_dump_info are DANGEROUS - only on explicit user request " //$NON-NLS-1$
            + "and only when you are CERTAIN of the state (project KNOWN to match the infobase / no update really " //$NON-NLS-1$
            + "running); otherwise EDT silently drops real changes or a genuine merge is aborted. NEVER call autonomously."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", "status | diagnose | diagnose_delta | suppress | reseed_baseline | " //$NON-NLS-1$ //$NON-NLS-2$
                + "mark_synchronized | diagnose_stuck_locks | recover_stuck_merge | " //$NON-NLS-1$
                + "list_support_snapshots | release_support_snapshot | rebuild_dump_info | " //$NON-NLS-1$
                + "retrieve_database_changes (required)", true) //$NON-NLS-1$
            .stringProperty("name", "For operation=release_support_snapshot: the snapshot's file " //$NON-NLS-1$ //$NON-NLS-2$
                + "name, as list_support_snapshots reports it. A protected snapshot is the only way back " //$NON-NLS-1$
                + "from a merge whose outcome is not known here; releasing it says that merge has been " //$NON-NLS-1$
                + "dealt with, after which the limit may remove it.") //$NON-NLS-1$
            .stringProperty("projectName", "Project name (required). For 'only update the extension', " //$NON-NLS-1$ //$NON-NLS-2$
                + "target the MAIN configuration project here.", true) //$NON-NLS-1$
            .booleanProperty("enabled", "For operation=suppress: true = suppress synchronization for the " //$NON-NLS-1$ //$NON-NLS-2$
                + "project (skip it on update), false = re-enable.") //$NON-NLS-1$
            .stringProperty("infobaseUuid", "For operation=reseed_baseline / mark_synchronized / " //$NON-NLS-1$ //$NON-NLS-2$
                + "recover_stuck_merge: the target infobase (an 'infobaseUuid' from status / diagnose_stuck_locks).") //$NON-NLS-1$
            .stringProperty("applicationId", "For operation=rebuild_dump_info: the application " //$NON-NLS-1$ //$NON-NLS-2$
                + "naming the infobase whose stored file is rebuilt. For " //$NON-NLS-1$
                + "operation=retrieve_database_changes: the application naming the infobase the " //$NON-NLS-1$
                + "changes are pulled from. Required when the project has " //$NON-NLS-1$
                + "several applications (see infobase_admin operation=get_applications); resolved " //$NON-NLS-1$
                + "otherwise.") //$NON-NLS-1$
            .booleanProperty("replaceLocal", "For operation=retrieve_database_changes: false (the " //$NON-NLS-1$ //$NON-NLS-2$
                + "default) refuses the pull and changes nothing when the project carries changes of " //$NON-NLS-1$
                + "its own; true takes the infobase's version of those objects and discards the " //$NON-NLS-1$
                + "project's changes to them.") //$NON-NLS-1$
            .booleanProperty("markSynchronized", "For operation=retrieve_database_changes: true runs " //$NON-NLS-1$ //$NON-NLS-2$
                + "the same baseline rewrite as operation=mark_synchronized for the infobase just " //$NON-NLS-1$
                + "pulled, once the pull succeeded. A failed rewrite is baselineMarked=false with " //$NON-NLS-1$
                + "the reason on the answer. Default false.") //$NON-NLS-1$
            .stringProperty("timeoutSeconds", "For operation=rebuild_dump_info: how long each " //$NON-NLS-1$ //$NON-NLS-2$
                + "Designer run is waited for, 60-3600 (default 600). Past it the run is abandoned, " //$NON-NLS-1$
                + "the stored file is not touched and the infobase is reconnected. For " //$NON-NLS-1$
                + "operation=retrieve_database_changes: how long this call waits before answering " //$NON-NLS-1$
                + "Pending with a runKey, 30-3600 (default 300). The same budget is what " //$NON-NLS-1$
                + "BuildTaskHelper.waitForBuildAndDerivedData uses for the derived-data wait after " //$NON-NLS-1$
                + "a pull; the build-job wait is not bounded, and running out of the derived-data " //$NON-NLS-1$
                + "budget ends quietly without an error. The platform pull is not cancelled when " //$NON-NLS-1$
                + "the budget runs out - call again with the runKey.") //$NON-NLS-1$
            .stringProperty("runKey", "For operation=retrieve_database_changes: resumes a Pending " //$NON-NLS-1$ //$NON-NLS-2$
                + "pull. How long this call waits before answering Pending again is timeoutSeconds. " //$NON-NLS-1$
                + "A fresh call without runKey starts again and is not served a previous result.") //$NON-NLS-1$
            .booleanProperty("cancel", "For operation=retrieve_database_changes: with runKey, stop " //$NON-NLS-1$ //$NON-NLS-2$
                + "tracking that pull. The platform call, once it has started, is still running, and " //$NON-NLS-1$
                + "the answer says so.") //$NON-NLS-1$
            .booleanProperty("confirm", "For operation=reseed_baseline / mark_synchronized / " //$NON-NLS-1$ //$NON-NLS-2$
                + "recover_stuck_merge / rebuild_dump_info: " //$NON-NLS-1$
                + "must be true to proceed. Confirms you are CERTAIN of the state (project matches the infobase, or " //$NON-NLS-1$
                + "no update is really running) - otherwise EDT silently drops real changes or aborts a genuine merge.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    // ---- the platform services this tool reads (methods, so a test can answer without EDT) ---

    /**
     * The platform application manager - the source of the project's bindings. A method rather
     * than a direct read of the activator so a test can answer with its own list: outside a running
     * EDT the service is absent, and both the per-binding report and the fresh-binding branch have
     * to be exercised there.
     *
     * @return the manager, or {@code null} when this runtime offers none
     */
    IApplicationManager applicationManager()
    {
        Activator activator = Activator.getDefault();
        return activator == null ? null : activator.getApplicationManager();
    }

    /**
     * The EDT synchronization state manager. A method for the same reason as
     * {@link #applicationManager()}: the delegate it hands out is what {@code mark_synchronized}
     * calls, and that call has to be observable without EDT running.
     *
     * @return the manager, or {@code null} when this runtime offers none
     */
    IInfobaseSynchronizationStateManager syncStateManager()
    {
        return ServiceAccess.get(IInfobaseSynchronizationStateManager.class);
    }

    /**
     * The EDT synchronization manager - the service both the diagnosis and
     * {@code retrieve_database_changes} ask. A method for the same reason as
     * {@link #syncStateManager()}: the pull is a call into it, and that call has to be observable
     * without EDT running.
     *
     * @return the manager, or {@code null} when this runtime offers none
     */
    IInfobaseSynchronizationManager synchronizationManager()
    {
        return ServiceAccess.get(IInfobaseSynchronizationManager.class);
    }

    /**
     * The delegate the sync state manager keeps behind its public interface. It is the only route
     * to {@code forceEdtSynchronization} and {@code forceConfigurationUUID}: neither is declared on
     * {@code IInfobaseSynchronizationStateManager}, so they are reached through {@code getDelegate}
     * and reflection. A method, like {@link #applicationManager()}, so a test can answer with a
     * delegate of its own - the interface cannot be proxied into one, because the accessor is not
     * part of it.
     *
     * @param stateMgr the manager to read the delegate from
     * @return the delegate, or {@code null} when this runtime does not hand one out
     */
    Object syncDelegate(IInfobaseSynchronizationStateManager stateMgr)
    {
        try
        {
            return stateMgr.getClass().getMethod("getDelegate").invoke(stateMgr); //$NON-NLS-1$
        }
        catch (ReflectiveOperationException e)
        {
            Activator.logWarning("sync_control: the synchronization state delegate could not be reached: " //$NON-NLS-1$
                + TextSuggest.safeMessage(e));
            return null;
        }
    }

    /**
     * Names the heavy action behind this call so the road weighs it.
     * <p>
     * {@code rebuild_dump_info} releases the infobase to a Designer. {@code retrieve_database_changes}
     * pulls the infobase into the project and can reload the configuration. Every other operation
     * here reads files and the model in-process, so a name returned for the rest would throttle
     * cheap reads. The action is matched exactly, as the dispatch below accepts it - a camelCase
     * selector is refused before any work runs.
     * </p>
     *
     * @param arguments the call arguments, as the client sent them; may be <code>null</code>
     * @return {@code rebuild_dump_info} or {@code retrieve_database_changes} for those actions,
     *         <code>null</code> otherwise
     */
    @Override
    public String routesTo(Map<String, String> arguments)
    {
        String operation = JsonUtils.extractStringArgument(arguments, "operation"); //$NON-NLS-1$
        if ("rebuild_dump_info".equals(operation)) //$NON-NLS-1$
        {
            return "rebuild_dump_info"; //$NON-NLS-1$
        }
        if ("retrieve_database_changes".equals(operation)) //$NON-NLS-1$
        {
            return "retrieve_database_changes"; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Polls a pull this tool started.
     *
     * @param domain the registry domain the key was found in
     * @param operation the operation argument; may be {@code null}
     * @return {@code retrieve_database_changes} when this call polls one, or {@code null}
     */
    @Override
    public String resumes(String domain, String operation)
    {
        if (!PendingWorkRegistry.RETRIEVE.domain().equals(domain))
        {
            return null;
        }
        return "retrieve_database_changes".equals(operation) ? "retrieve_database_changes" : null; //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String operation = JsonUtils.extractStringArgument(params, "operation"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (operation == null || operation.isEmpty())
        {
            return ToolResult.error(TextSuggest.missingParam("operation", //$NON-NLS-1$
                "sync_control operation=status projectName=MyConfig")).toJson(); //$NON-NLS-1$
        }
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error(TextSuggest.missingParam("projectName", //$NON-NLS-1$
                "sync_control operation=" + operation + " projectName=MyConfig")).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            IProject exact = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (exact.exists())
            {
                project = exact;
            }
        }
        if (project == null || !project.exists())
        {
            return ProjectResolver.notFound(projectName).toJson();
        }

        switch (operation)
        {
            case "status": //$NON-NLS-1$
                return doStatus(project);
            case "diagnose": //$NON-NLS-1$
                return doDiagnose(project);
            case "diagnose_delta": //$NON-NLS-1$
                return doDiagnoseDelta(project, params);
            case "suppress": //$NON-NLS-1$
                return doSuppress(project, params);
            case "reseed_baseline": //$NON-NLS-1$
                return doReseedBaseline(project, params);
            case "mark_synchronized": //$NON-NLS-1$
                return doMarkSynchronized(project, params);
            case "diagnose_stuck_locks": //$NON-NLS-1$
                return doDiagnoseStuckLocks(project);
            case "recover_stuck_merge": //$NON-NLS-1$
                return doRecoverStuckMerge(project, params);
            case "list_support_snapshots": //$NON-NLS-1$
                return doListSupportSnapshots(project);
            case "release_support_snapshot": //$NON-NLS-1$
                return doReleaseSupportSnapshot(project, params);
            case "rebuild_dump_info": //$NON-NLS-1$
                return doRebuildDumpInfo(project, params);
            case "retrieve_database_changes": //$NON-NLS-1$
                return doRetrieveDatabaseChanges(project, params);
            default:
                return ToolResult.error("Unknown operation '" + operation //$NON-NLS-1$
                    + "'. Valid: status, diagnose, diagnose_delta, suppress, reseed_baseline, mark_synchronized, " //$NON-NLS-1$
                    + "diagnose_stuck_locks, recover_stuck_merge, list_support_snapshots, " //$NON-NLS-1$
                    + "release_support_snapshot, rebuild_dump_info, retrieve_database_changes.").toJson(); //$NON-NLS-1$
        }
    }

    // ---- status (read-only diagnosis) --------------------------------------------------

    private String doStatus(IProject project)
    {
        String liveUuid = readConfigurationUuid(project);
        List<Path> ssRoots = SyncBaseline.stores(project);

        ToolResult res = ToolResult.success()
            .put("operation", "status") //$NON-NLS-1$ //$NON-NLS-2$
            .put("projectName", project.getName()) //$NON-NLS-1$
            .put("syncStorePath", ssRoots.isEmpty() ? SyncBaseline.workspaceStore(project).toString() //$NON-NLS-1$
                : ssRoots.get(0).toString());
        if (ssRoots.size() > 1)
        {
            res.put("syncStorePaths", ssRoots.stream().map(Path::toString).collect(Collectors.toList())); //$NON-NLS-1$
        }

        if (liveUuid == null)
        {
            res.put("liveConfigurationUuid", "(not found)"); //$NON-NLS-1$ //$NON-NLS-2$
            res.put("prediction", "UNKNOWN"); //$NON-NLS-1$ //$NON-NLS-2$
            res.put("summary", "Could not read the project's Configuration UUID " //$NON-NLS-1$
                + "(src/Configuration/Configuration.mdo). Is this a configuration (not extension) project?"); //$NON-NLS-1$
            return res.toJson();
        }
        res.put("liveConfigurationUuid", liveUuid); //$NON-NLS-1$

        if (ssRoots.isEmpty())
        {
            res.put("prediction", "FULL"); //$NON-NLS-1$ //$NON-NLS-2$
            res.put("willTriggerFullReload", true); //$NON-NLS-1$
            res.put("summary", "No EDT sync store found at " + SyncBaseline.workspaceStore(project) + " nor at " //$NON-NLS-1$ //$NON-NLS-2$
                + SyncBaseline.roamingStore()
                + " - there is no baseline, so the next update will be a FULL configuration reload."); //$NON-NLS-1$
            return res.toJson();
        }

        List<Map<String, Object>> all = new ArrayList<>();
        Map<String, Object> matched = null;
        List<File> ibDirs = new ArrayList<>();
        for (Path root : ssRoots)
        {
            File[] dirs = root.toFile().listFiles(File::isDirectory);
            if (dirs != null)
            {
                ibDirs.addAll(Arrays.asList(dirs));
            }
        }
        if (!ibDirs.isEmpty())
        {
            for (File ibDir : ibDirs)
            {
                File idx = new File(ibDir, "index.idx"); //$NON-NLS-1$
                if (!idx.isFile())
                {
                    continue;
                }
                IndexInfo info = parseIndexIdx(idx.toPath());
                if (info == null)
                {
                    continue;
                }
                // EDT's UpdateInfobaseFlow.start() compares with String.equals - match that exactly
                // (case-sensitive) so the prediction agrees with the real full/incremental decision.
                boolean isMatch = liveUuid.equals(info.configurationUuid);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("infobaseUuid", ibDir.getName()); //$NON-NLS-1$
                entry.put("store", ibDir.getParentFile().getParentFile().getParentFile().getName() //$NON-NLS-1$
                    .equals(SyncBaseline.STORE_PLUGIN) ? "workspace" : "roaming"); //$NON-NLS-1$ //$NON-NLS-2$
                entry.put("configurationUuid", info.configurationUuid); //$NON-NLS-1$
                entry.put("signatureCount", info.signatureCount); //$NON-NLS-1$
                entry.put("matchesProject", isMatch); //$NON-NLS-1$
                if (isMatch && matched == null)
                {
                    matched = new LinkedHashMap<>(entry);
                    matched.put("generationId", info.generationId); //$NON-NLS-1$
                    matched.put("timestamp", info.timestamp); //$NON-NLS-1$
                    matched.put("extensions", listExtensions(ibDir)); //$NON-NLS-1$
                }
                if (all.size() < MAX_LISTED_BASELINES)
                {
                    all.add(entry);
                }
            }
        }

        boolean willBeFull = matched == null;
        res.put("baselines", all); //$NON-NLS-1$
        if (matched != null)
        {
            res.put("matchedBaseline", matched); //$NON-NLS-1$
        }

        // The scan above answers "is there a baseline for this configuration anywhere"; which of
        // the project's bindings that baseline belongs to is a separate question, and the one the
        // caller actually updates by. A binding with no baseline of its own is carried whole
        // however well the scan reads, so the shared prediction is the pessimistic one over every
        // binding, and the summary names the binding it came from.
        List<Map<String, Object>> bindings = listBindings(project, liveUuid);
        int withoutBaseline = 0;
        int mismatched = 0;
        List<String> freshBindings = new ArrayList<>();
        List<String> mismatchedBindings = new ArrayList<>();
        if (bindings == null)
        {
            res.put("bindingsUnavailable", "The project's applications were not read in this runtime, so the " //$NON-NLS-1$ //$NON-NLS-2$
                + "prediction below covers the sync store, not the individual bindings."); //$NON-NLS-1$
        }
        else
        {
            res.put("bindings", bindings); //$NON-NLS-1$
            for (Map<String, Object> binding : bindings)
            {
                if (!Boolean.TRUE.equals(binding.get("hasBaseline"))) //$NON-NLS-1$
                {
                    withoutBaseline++;
                    freshBindings.add(String.valueOf(binding.get("infobaseUuid"))); //$NON-NLS-1$
                }
                else if (!Boolean.TRUE.equals(binding.get("matchesProject"))) //$NON-NLS-1$
                {
                    mismatched++;
                    mismatchedBindings.add(String.valueOf(binding.get("infobaseUuid"))); //$NON-NLS-1$
                }
            }
        }

        // The store scan says a matching baseline exists, but not which binding owns it: a binding
        // of this project with no baseline of its own is carried whole however well the scan reads.
        // With the bindings unread that question stays open, so the shared prediction cannot be
        // INCREMENTAL - it answers UNKNOWN instead of promising an update nobody has checked.
        boolean bindingsUnread = bindings == null;
        String summary;
        if (withoutBaseline > 0 || mismatched > 0)
        {
            willBeFull = true;
            StringBuilder text = new StringBuilder();
            if (withoutBaseline > 0)
            {
                text.append(withoutBaseline).append(" of the project's ").append(bindings.size()) //$NON-NLS-1$
                    .append(" application(s) have no baseline of their own (infobase ") //$NON-NLS-1$
                    .append(String.join(", ", freshBindings)) //$NON-NLS-1$
                    .append("): EDT carries every object of the configuration into such a binding, so updating " //$NON-NLS-1$
                        + "that one is a FULL configuration reload. "); //$NON-NLS-1$
            }
            if (mismatched > 0)
            {
                text.append(mismatched).append(" application(s) hold a baseline recorded for another " //$NON-NLS-1$
                    + "configuration (infobase ").append(String.join(", ", mismatchedBindings)) //$NON-NLS-1$ //$NON-NLS-2$
                    .append("): updating that one is a FULL configuration reload too. "); //$NON-NLS-1$
            }
            if (matched != null)
            {
                text.append("Infobase ").append(matched.get("infobaseUuid")) //$NON-NLS-1$
                    .append(" does have a baseline matching this configuration (").append(matched.get("signatureCount")) //$NON-NLS-1$ //$NON-NLS-2$
                    .append(" signatures): updating THAT binding stays INCREMENTAL (only changed files pushed)."); //$NON-NLS-1$
            }
            else
            {
                text.append(noMatchingBaselineText(liveUuid, all.size()));
            }
            summary = text.toString();
        }
        else if (matched == null)
        {
            summary = noMatchingBaselineText(liveUuid, all.size());
        }
        else if (bindingsUnread)
        {
            summary = "A baseline matching this configuration UUID exists (infobase " //$NON-NLS-1$
                + matched.get("infobaseUuid") + ", " + matched.get("signatureCount") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " signatures), but the project's applications were not read in this runtime, so which " //$NON-NLS-1$
                + "binding that baseline belongs to is unknown. The prediction covers the sync store only - a " //$NON-NLS-1$
                + "binding of this project with no baseline of its own would still make its next update a FULL " //$NON-NLS-1$
                + "configuration reload. See bindingsUnavailable."; //$NON-NLS-1$
        }
        else
        {
            summary = "A baseline matching this configuration UUID exists (infobase " //$NON-NLS-1$
                + matched.get("infobaseUuid") + ", " + matched.get("signatureCount") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " signatures). The next update should be INCREMENTAL (only changed files pushed)." //$NON-NLS-1$
                + (bindings != null && !bindings.isEmpty()
                    ? " All " + bindings.size() + " application(s) of this project hold a baseline of their own." //$NON-NLS-1$ //$NON-NLS-2$
                    : ""); //$NON-NLS-1$
        }
        res.put("prediction", willBeFull ? "FULL" : bindingsUnread ? "UNKNOWN" : "INCREMENTAL"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        // Unread bindings are on the pessimistic side of the boolean too: "no full reload" is a
        // promise only a complete reading can make.
        res.put("willTriggerFullReload", willBeFull || bindingsUnread); //$NON-NLS-1$
        res.put("summary", summary); //$NON-NLS-1$
        return res.toJson();
    }

    /**
     * The sentence a FULL prediction carries when no stored baseline matches the project's
     * configuration id - the causes that lead there, listed once for both places that answer it.
     *
     * @param liveUuid the project's configuration id
     * @param storedBaselines how many baselines the scan read
     * @return the summary text
     */
    private static String noMatchingBaselineText(String liveUuid, int storedBaselines)
    {
        return "No baseline with a matching configuration UUID (" + liveUuid //$NON-NLS-1$
            + ") was found among " + storedBaselines + " stored infobase baseline(s). The next update will be a " //$NON-NLS-1$ //$NON-NLS-2$
            + "FULL configuration reload. Causes: no prior successful EDT sync for this infobase, the sync " //$NON-NLS-1$
            + "store was wiped (OneDrive/manual cleanup or a crashed sync flow), or the configuration " //$NON-NLS-1$
            + "identity differs (e.g. the infobase was changed via Designer/repository outside EDT)."; //$NON-NLS-1$
    }

    /**
     * What a baseline re-read after a call records, as a phrase a refusal or a message can carry.
     *
     * @param after the baseline as re-read after the call; {@code null} when none could be read
     * @param idx the index path the baseline was read from
     * @return the recorded configuration id, that none is recorded, or that no baseline was
     *         readable at all
     */
    private static String recordedIdText(IndexInfo after, Path idx)
    {
        if (after == null)
        {
            return "no readable baseline (no index.idx at " + idx + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return after.configurationUuid.isEmpty()
            ? "no configuration id" //$NON-NLS-1$
            : "configuration id " + after.configurationUuid; //$NON-NLS-1$
    }

    /**
     * The project's bindings - the applications EDT would update from it, as
     * {@code infobase_admin operation=get_applications} lists them - each with the state of the
     * baseline it owns: whether one exists, whether the configuration id it recorded is the
     * project's, and what the next update of that binding therefore is. An extension borrows the
     * applications of the project it extends, the same fallback {@code get_applications} makes.
     *
     * @param project the project whose bindings are listed
     * @param liveUuid the project's configuration id
     * @return one row per bound infobase, or {@code null} when the application list could not be
     *         read at all - the caller then reports that its prediction covers the store only
     */
    private List<Map<String, Object>> listBindings(IProject project, String liveUuid)
    {
        IApplicationManager manager = applicationManager();
        if (manager == null)
        {
            return null;
        }
        try
        {
            List<IApplication> applications = manager.getApplications(project);
            IProject owner = project;
            boolean viaParent = false;
            if (applications == null || applications.isEmpty())
            {
                IProject parent = BmCommonModuleGuards.parentProjectOf(project);
                if (parent != null && parent.exists() && parent.isOpen())
                {
                    applications = manager.getApplications(parent);
                    if (applications != null && !applications.isEmpty())
                    {
                        owner = parent;
                        viaParent = true;
                    }
                }
            }
            if (applications == null)
            {
                return null;
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (IApplication application : applications)
            {
                if (!(application instanceof IInfobaseApplication))
                {
                    continue;
                }
                InfobaseReference infobase = ((IInfobaseApplication)application).getInfobase();
                if (infobase == null || infobase.getUuid() == null)
                {
                    continue;
                }
                String uuid = infobase.getUuid().toString();
                Path idx = SyncBaseline.indexOf(project, uuid);
                IndexInfo info = idx.toFile().isFile() ? parseIndexIdx(idx) : null;
                boolean matches = info != null && liveUuid.equals(info.configurationUuid);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("applicationId", application.getId()); //$NON-NLS-1$
                row.put("applicationName", application.getName()); //$NON-NLS-1$
                row.put("infobaseUuid", uuid); //$NON-NLS-1$
                row.put("hasBaseline", Boolean.valueOf(info != null)); //$NON-NLS-1$
                if (info != null)
                {
                    row.put("configurationUuid", info.configurationUuid); //$NON-NLS-1$
                    row.put("signatureCount", Integer.valueOf(info.signatureCount)); //$NON-NLS-1$
                }
                row.put("matchesProject", Boolean.valueOf(matches)); //$NON-NLS-1$
                row.put("prediction", info == null || !matches ? "FULL" : "INCREMENTAL"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                if (info == null)
                {
                    row.put("reason", "this infobase has no baseline at all, so EDT carries every object " //$NON-NLS-1$ //$NON-NLS-2$
                        + "of the configuration into it"); //$NON-NLS-1$
                }
                else if (!matches)
                {
                    row.put("reason", "the baseline records configuration " + info.configurationUuid //$NON-NLS-1$
                        + ", not this project's " + liveUuid); //$NON-NLS-1$
                }
                if (viaParent)
                {
                    row.put("applicationsOf", owner.getName()); //$NON-NLS-1$
                }
                rows.add(row);
            }
            return rows;
        }
        catch (Throwable t)
        {
            // Reading the applications is a diagnosis: a failure here is reported as "not read
            // here" (a null return), never as "this project has no bindings".
            Activator.logWarning("sync_control status: the applications of " + project.getName() //$NON-NLS-1$
                + " were not read: " + TextSuggest.safeMessage(t)); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * The infobase ids of the project's applications - the bindings a refusal has to name when it
     * turns down an infobase that is not among them.
     *
     * @param project the project whose applications are listed
     * @param liveUuid the project's configuration id
     * @return one id per bound infobase, or {@code null} when the application list could not be
     *         read at all
     */
    private List<String> bindingUuids(IProject project, String liveUuid)
    {
        List<Map<String, Object>> bindings = listBindings(project, liveUuid);
        if (bindings == null)
        {
            return null;
        }
        List<String> uuids = new ArrayList<>();
        for (Map<String, Object> binding : bindings)
        {
            uuids.add(String.valueOf(binding.get("infobaseUuid"))); //$NON-NLS-1$
        }
        return uuids;
    }

    /**
     * The refusal for a baseline-writing operation aimed at an infobase this project is not bound
     * to, or {@code null} when the infobase IS one of the project's applications. Every writer
     * asks this before it resolves the baseline's path, whatever the baseline records: the
     * application list is what says the infobase is this project's, and a baseline of an infobase
     * bound only to another workspace's project is that project's synchronization state - neither
     * re-signed nor re-stamped here. The refusal names the bindings the project does have.
     *
     * @param project the project
     * @param liveUuid the project's configuration id, already validated
     * @param infobaseUuid the infobase the caller named, already validated as a canonical UUID
     * @return {@code null} when bound, otherwise the refusal text
     */
    private String refuseUnboundInfobase(IProject project, String liveUuid, String infobaseUuid)
    {
        List<String> applications = bindingUuids(project, liveUuid);
        if (applications != null && applications.contains(infobaseUuid))
        {
            return null;
        }
        return "infobase " + infobaseUuid + " is not one of the project's applications (" //$NON-NLS-1$ //$NON-NLS-2$
            + (applications == null ? "the application list could not be read in this runtime" //$NON-NLS-1$
                : project.getName() + ": " //$NON-NLS-1$
                    + (applications.isEmpty() ? "none" : String.join(", ", applications))) //$NON-NLS-1$ //$NON-NLS-2$
            + "). A baseline is written only for an infobase this project is bound to. To bind " //$NON-NLS-1$
            + "this one, run update_database fullUpdate=true: it carries the whole configuration " //$NON-NLS-1$
            + "into the infobase and writes the baseline itself."; //$NON-NLS-1$
    }

    private List<Map<String, Object>> listExtensions(File ibDir)
    {
        List<Map<String, Object>> result = new ArrayList<>();
        File extRoot = new File(ibDir, "ext"); //$NON-NLS-1$
        File[] extDirs = extRoot.isDirectory() ? extRoot.listFiles(File::isDirectory) : null;
        if (extDirs != null)
        {
            for (File extDir : extDirs)
            {
                File idx = new File(extDir, "index.idx"); //$NON-NLS-1$
                if (!idx.isFile())
                {
                    continue;
                }
                IndexInfo info = parseIndexIdx(idx.toPath());
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", extDir.getName()); //$NON-NLS-1$
                entry.put("configurationUuid", info != null ? info.configurationUuid : "(unreadable)"); //$NON-NLS-1$ //$NON-NLS-2$
                entry.put("signatureCount", info != null ? info.signatureCount : -1); //$NON-NLS-1$
                result.add(entry);
            }
        }
        return result;
    }

    // ---- diagnose (read-only: the EXACT pre-launch update gate) -------------------------

    /**
     * Reports the runtime inputs of the pre-launch "load changed objects" dialog. The launch dialog is
     * gated by {@code InfobaseApplicationProvisionDelegate.getUpdateState}, which maps
     * {@code IInfobaseSynchronizationManager.getEqualityState(project, infobase)} + {@code isConnected}:
     * disconnected -> UNKNOWN (dialog); EQUAL -> UPDATED (no dialog); NOT_EQUAL -> INCREMENTAL (dialog).
     * For every stored baseline whose configuration UUID matches the project this reports connected +
     * equalityState + the resulting update state, so it is clear WHICH infobase drives the dialog and
     * whether {@code mark_synchronized} affected it. Read-only with respect to disk; {@code getEqualityState}
     * triggers an in-memory holder refresh (lastEdtUpdateTimestamps) as a benign side effect.
     */
    private String doDiagnose(IProject project)
    {
        String liveUuid = readConfigurationUuid(project);
        ToolResult res = ToolResult.success()
            .put("operation", "diagnose") //$NON-NLS-1$ //$NON-NLS-2$
            .put("projectName", project.getName()); //$NON-NLS-1$
        if (liveUuid == null)
        {
            return res.put("error", "Could not read the project's Configuration UUID " //$NON-NLS-1$ //$NON-NLS-2$
                + "(src/Configuration/Configuration.mdo) - is this a configuration project?").toJson(); //$NON-NLS-1$
        }
        res.put("liveConfigurationUuid", liveUuid); //$NON-NLS-1$

        IInfobaseSynchronizationManager mgr = ServiceAccess.get(IInfobaseSynchronizationManager.class);
        if (mgr == null)
        {
            return res.put("error", "IInfobaseSynchronizationManager unavailable (run inside EDT).").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        try
        {
            res.put("strategyId", mgr.getStrategyId(project)); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            res.put("strategyId", "(error: " + TextSuggest.safeMessage(e) + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        List<Map<String, Object>> matching = new ArrayList<>();
        List<File> ibDirs = new ArrayList<>();
        for (Path root : SyncBaseline.stores(project))
        {
            File[] dirs = root.toFile().listFiles(File::isDirectory);
            if (dirs != null)
            {
                ibDirs.addAll(Arrays.asList(dirs));
            }
        }
        if (!ibDirs.isEmpty())
        {
            for (File ibDir : ibDirs)
            {
                File idx = new File(ibDir, "index.idx"); //$NON-NLS-1$
                if (!idx.isFile())
                {
                    continue;
                }
                IndexInfo info = parseIndexIdx(idx.toPath());
                if (info == null || !liveUuid.equals(info.configurationUuid))
                {
                    continue; // only baselines for THIS configuration drive this project's dialog
                }
                UUID ibUuid;
                try
                {
                    ibUuid = UUID.fromString(ibDir.getName());
                }
                catch (IllegalArgumentException e)
                {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("infobaseUuid", ibDir.getName()); //$NON-NLS-1$
                entry.put("store", ibDir.getParentFile().getParentFile().getParentFile().getName() //$NON-NLS-1$
                    .equals(SyncBaseline.STORE_PLUGIN) ? "workspace" : "roaming"); //$NON-NLS-1$ //$NON-NLS-2$
                entry.put("signatureCount", info.signatureCount); //$NON-NLS-1$
                try
                {
                    InfobaseReference ref = ModelFactory.eINSTANCE.createInfobaseReference();
                    ref.setUuid(ibUuid);
                    ref.setName("diagnose"); //$NON-NLS-1$
                    boolean connected = mgr.isConnected(project, ref);
                    InfobaseEqualityState equality = mgr.getEqualityState(project, ref);
                    entry.put("connected", connected); //$NON-NLS-1$
                    entry.put("equalityState", String.valueOf(equality)); //$NON-NLS-1$
                    entry.put("predictedUpdateState", predictUpdateState(connected, equality)); //$NON-NLS-1$
                }
                catch (Exception e)
                {
                    entry.put("error", TextSuggest.safeMessage(e)); //$NON-NLS-1$
                }
                matching.add(entry);
            }
        }
        res.put("matchingBaselines", matching); //$NON-NLS-1$
        res.put("note", "The launch shows the 'load changed objects' dialog unless predictedUpdateState is " //$NON-NLS-1$
            + "UPDATED. predictedUpdateState mirrors InfobaseApplicationProvisionDelegate.getUpdateState for the " //$NON-NLS-1$
            + "PARENT project: disconnected -> UNKNOWN (dialog), EQUAL -> UPDATED (no dialog), NOT_EQUAL -> " //$NON-NLS-1$
            + "INCREMENTAL_UPDATE_REQUIRED (dialog). If connected is false / equalityState is NOT_EQUAL with no " //$NON-NLS-1$
            + "synchronization, the baseline is not what gates the dialog. If a baseline is EQUAL here but the " //$NON-NLS-1$
            + "dialog still appears, the application launches against a different infobase than that baseline."); //$NON-NLS-1$
        return res.toJson();
    }

    /** Maps (connected, equalityState) to the ApplicationUpdateState the launch gate computes for the parent. */
    private static String predictUpdateState(boolean connected, InfobaseEqualityState equality)
    {
        if (!connected)
        {
            return "UNKNOWN (disconnected -> dialog)"; //$NON-NLS-1$
        }
        if (equality == InfobaseEqualityState.EQUAL)
        {
            return "UPDATED (no dialog)"; //$NON-NLS-1$
        }
        if (equality == InfobaseEqualityState.LOADING)
        {
            return "BEING_UPDATED"; //$NON-NLS-1$
        }
        return "INCREMENTAL_UPDATE_REQUIRED (dialog)"; //$NON-NLS-1$
    }

    // ---- diagnose_delta (read-only: current effective signatures vs on-disk baseline) --

    /**
     * Resource-level comparison of the project's CURRENT effective signatures (the "current" side of the
     * equality check) against the on-disk baseline index.idx that mark_synchronized wrote. If they match,
     * a NOT_EQUAL dialog comes from a stale in-memory holder (force a reload); if they differ, the write
     * (key set / signature bytes / count) is wrong. Reports sizes, key-set differences and per-resource
     * signature mismatches with examples. Read-only.
     */
    private String doDiagnoseDelta(IProject project, Map<String, String> params)
    {
        String infobaseUuid = JsonUtils.extractStringArgument(params, "infobaseUuid"); //$NON-NLS-1$
        if (infobaseUuid == null || infobaseUuid.isEmpty())
        {
            return ToolResult.error(TextSuggest.missingParam("infobaseUuid", //$NON-NLS-1$
                "sync_control operation=diagnose_delta projectName=" + project.getName() //$NON-NLS-1$
                    + " infobaseUuid=<matchedBaseline from status>")).toJson(); //$NON-NLS-1$
        }
        if (SyncBaseline.parseInfobaseUuid(infobaseUuid) == null)
        {
            // Before any path is built from it: an id that is not a canonical UUID would read
            // whatever index.idx root.resolve(infobaseUuid) lands on, inside the store or not.
            return ToolResult.error("infobaseUuid is not a valid UUID: '" + infobaseUuid + "'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Map<String, byte[]> current = computeEdtSignatures(project);
        if (current == null)
        {
            return ToolResult.error("Could not obtain EDT effective signatures (run inside EDT, project loaded).").toJson(); //$NON-NLS-1$
        }
        Path idx = SyncBaseline.indexOf(project, infobaseUuid.trim());
        Map<String, byte[]> baseline;
        try
        {
            baseline = readIndexIdxSignatures(idx);
        }
        catch (Exception e)
        {
            return ToolResult.error("Could not read baseline index.idx at " + infobaseUuid + ": " //$NON-NLS-1$ //$NON-NLS-2$
                + TextSuggest.safeMessage(e)).toJson();
        }
        if (baseline == null)
        {
            return ToolResult.error("No baseline index.idx at infobase " + infobaseUuid + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        List<String> onlyCurrent = new ArrayList<>();
        List<Map<String, Object>> sigMismatch = new ArrayList<>();
        int sigMismatchCount = 0;
        int onlyCurrentCount = 0;
        for (Map.Entry<String, byte[]> e : current.entrySet())
        {
            byte[] b = baseline.get(e.getKey());
            if (b == null)
            {
                onlyCurrentCount++;
                if (onlyCurrent.size() < 8)
                {
                    onlyCurrent.add(e.getKey());
                }
                continue;
            }
            if (!java.util.Arrays.equals(e.getValue(), b))
            {
                sigMismatchCount++;
                if (sigMismatch.size() < 8)
                {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("key", e.getKey()); //$NON-NLS-1$
                    m.put("currentLen", e.getValue().length); //$NON-NLS-1$
                    m.put("baselineLen", b.length); //$NON-NLS-1$
                    m.put("currentHex", hexPrefix(e.getValue())); //$NON-NLS-1$
                    m.put("baselineHex", hexPrefix(b)); //$NON-NLS-1$
                    sigMismatch.add(m);
                }
            }
        }
        List<String> onlyBaseline = new ArrayList<>();
        int onlyBaselineCount = 0;
        for (String k : baseline.keySet())
        {
            if (!current.containsKey(k))
            {
                onlyBaselineCount++;
                if (onlyBaseline.size() < 8)
                {
                    onlyBaseline.add(k);
                }
            }
        }
        boolean identical = current.size() == baseline.size() && onlyCurrentCount == 0
            && onlyBaselineCount == 0 && sigMismatchCount == 0;
        return ToolResult.success()
            .put("operation", "diagnose_delta") //$NON-NLS-1$ //$NON-NLS-2$
            .put("projectName", project.getName()) //$NON-NLS-1$
            .put("infobaseUuid", infobaseUuid) //$NON-NLS-1$
            .put("currentSignatureCount", current.size()) //$NON-NLS-1$
            .put("baselineSignatureCount", baseline.size()) //$NON-NLS-1$
            .put("keysOnlyInCurrentCount", onlyCurrentCount) //$NON-NLS-1$
            .put("keysOnlyInBaselineCount", onlyBaselineCount) //$NON-NLS-1$
            .put("signatureMismatchCount", sigMismatchCount) //$NON-NLS-1$
            .put("keysOnlyInCurrentExamples", onlyCurrent) //$NON-NLS-1$
            .put("keysOnlyInBaselineExamples", onlyBaseline) //$NON-NLS-1$
            .put("signatureMismatchExamples", sigMismatch) //$NON-NLS-1$
            .put("identical", identical) //$NON-NLS-1$
            .put("interpretation", identical //$NON-NLS-1$
                ? "Current effective signatures EXACTLY match the on-disk baseline. A NOT_EQUAL launch dialog " //$NON-NLS-1$
                    + "therefore comes from a STALE in-memory holder that has not reloaded this baseline (force a " //$NON-NLS-1$
                    + "reload), not from the written content." //$NON-NLS-1$
                : "Current effective signatures DIFFER from the on-disk baseline - mark_synchronized's write does not " //$NON-NLS-1$
                    + "reproduce what the equality check computes now (see the mismatch examples).") //$NON-NLS-1$
            .toJson();
    }

    /** Reads an index.idx (legacy or versioned "1.0") into a path -> signature-bytes map. Null if absent. */
    private static Map<String, byte[]> readIndexIdxSignatures(Path file) throws IOException
    {
        if (!file.toFile().isFile())
        {
            return null;
        }
        SyncBaseline.Index index = SyncBaseline.read(file);
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (int i = 0; i < index.keys.size(); i++)
        {
            result.put(index.keys.get(i), index.signatures.get(i));
        }
        return result;
    }

    /** First up to 8 bytes of a signature as lowercase hex (for mismatch examples). */
    private static String hexPrefix(byte[] bytes)
    {
        if (bytes == null)
        {
            return "(null)"; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder();
        int n = Math.min(bytes.length, 8);
        for (int i = 0; i < n; i++)
        {
            sb.append(String.format("%02x", bytes[i] & 0xFF)); //$NON-NLS-1$
        }
        if (bytes.length > n)
        {
            sb.append(".."); //$NON-NLS-1$
        }
        return sb.toString();
    }

    // ---- suppress (control) ------------------------------------------------------------

    private String doSuppress(IProject project, Map<String, String> params)
    {
        Boolean enabled = JsonUtils.extractBooleanArgumentNullable(params, "enabled"); //$NON-NLS-1$
        if (enabled == null)
        {
            return ToolResult.error(TextSuggest.missingParam("enabled", //$NON-NLS-1$
                "sync_control operation=suppress projectName=" + project.getName() + " enabled=true")).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        IInfobaseSynchronizationManager manager;
        try
        {
            manager = ServiceAccess.get(IInfobaseSynchronizationManager.class);
        }
        catch (Exception e)
        {
            manager = null;
        }
        if (manager == null)
        {
            return ToolResult.error("IInfobaseSynchronizationManager is not available in this EDT runtime.").toJson(); //$NON-NLS-1$
        }

        try
        {
            manager.suppressSynchronization(project, enabled.booleanValue());
            Activator.logInfo("sync_control suppress: " + project.getName() //$NON-NLS-1$
                + " suppressed=" + enabled); //$NON-NLS-1$
            String message = enabled.booleanValue()
                ? "Synchronization is now SUPPRESSED for '" + project.getName() //$NON-NLS-1$
                    + "'. While suppressed, updating the infobase skips this project - if this is the main " //$NON-NLS-1$
                    + "(on-support) configuration, an update pushes only the extensions. The flag is in-memory " //$NON-NLS-1$
                    + "(per EDT session); re-enable with enabled=false." //$NON-NLS-1$
                : "Synchronization is now ENABLED for '" + project.getName() //$NON-NLS-1$
                    + "'. Updates will synchronize this project again."; //$NON-NLS-1$
            return ToolResult.success()
                .put("operation", "suppress") //$NON-NLS-1$ //$NON-NLS-2$
                .put("projectName", project.getName()) //$NON-NLS-1$
                .put("suppressed", enabled.booleanValue()) //$NON-NLS-1$
                .put("message", message) //$NON-NLS-1$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("sync_control suppress failed for " + project.getName(), e); //$NON-NLS-1$
            return ToolResult.error("Failed to change synchronization suppression for '" //$NON-NLS-1$
                + project.getName() + "': " + TextSuggest.safeMessage(e)).toJson(); //$NON-NLS-1$
        }
    }

    // ---- reseed_baseline (re-stamp baseline UUID so an update stays incremental) --------

    /**
     * Re-stamps a baseline's stored configuration UUID to the project's live UUID so
     * {@code UpdateInfobaseFlow.start()} no longer forces a full reload. Goes through the
     * internal delegate's {@code forceConfigurationUUID} (reached via the public
     * {@code getDelegate()}) so EDT's in-memory holder and the on-disk index.idx are both
     * updated under the infobase lock - a raw file write would be ignored while EDT has the
     * holder cached. DANGEROUS: only valid when the configuration truly matches the infobase.
     * The infobase must be one of the project's applications: the baseline of an infobase only
     * another workspace's project is bound to is that project's state and is not re-stamped.
     */
    private String doReseedBaseline(IProject project, Map<String, String> params)
    {
        Boolean confirm = JsonUtils.extractBooleanArgumentNullable(params, "confirm"); //$NON-NLS-1$
        String infobaseUuid = JsonUtils.extractStringArgument(params, "infobaseUuid"); //$NON-NLS-1$

        if (confirm == null || !confirm.booleanValue())
        {
            return ToolResult.error("reseed_baseline rewrites the EDT sync baseline so the next update is " //$NON-NLS-1$
                + "INCREMENTAL instead of a full reload. Do this ONLY when you are certain the configuration is " //$NON-NLS-1$
                + "unchanged vs the infobase (e.g. on support); otherwise EDT silently skips real changes and the " //$NON-NLS-1$
                + "project and infobase diverge. Re-run with confirm=true if that is intended.").toJson(); //$NON-NLS-1$
        }
        if (infobaseUuid == null || infobaseUuid.isEmpty())
        {
            return ToolResult.error(TextSuggest.missingParam("infobaseUuid", //$NON-NLS-1$
                "sync_control operation=reseed_baseline projectName=" + project.getName() //$NON-NLS-1$
                    + " infobaseUuid=<from status> confirm=true")).toJson(); //$NON-NLS-1$
        }
        UUID ibUuid = SyncBaseline.parseInfobaseUuid(infobaseUuid);
        if (ibUuid == null)
        {
            return ToolResult.error("infobaseUuid is not a valid UUID: '" + infobaseUuid + "'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        String liveUuid = readConfigurationUuid(project);
        if (liveUuid == null)
        {
            return ToolResult.error("Could not read the project's Configuration UUID " //$NON-NLS-1$
                + "(src/Configuration/Configuration.mdo) - is this a configuration (not extension) project?").toJson(); //$NON-NLS-1$
        }
        try
        {
            UUID.fromString(liveUuid); // validate the project's Configuration UUID is well-formed
        }
        catch (IllegalArgumentException e)
        {
            return ToolResult.error("The project's Configuration UUID is not a parseable UUID: '" //$NON-NLS-1$
                + liveUuid + "'.").toJson(); //$NON-NLS-1$
        }

        // A write is allowed only to a baseline of an infobase this project is bound to, whatever
        // the baseline records: indexOf would otherwise hand back a baseline of another
        // workspace's project from the shared per-user store, and re-stamping it would rewrite
        // that project's synchronization state.
        String unbound = refuseUnboundInfobase(project, liveUuid, infobaseUuid.trim());
        if (unbound != null)
        {
            return ToolResult.error(unbound).toJson();
        }

        // The baseline must already hold this project's resource signatures; reseeding an empty/missing
        // baseline only flips the UUID and the first update would still push everything.
        Path idx = SyncBaseline.indexOf(project, infobaseUuid.trim());
        IndexInfo before = idx.toFile().isFile() ? parseIndexIdx(idx) : null;
        if (before == null || before.signatureCount <= 0)
        {
            return ToolResult.error("No baseline with resource signatures at infobase " + infobaseUuid //$NON-NLS-1$
                + " (index.idx missing or empty). Run operation=status first; reseed only helps when a populated " //$NON-NLS-1$
                + "baseline exists whose configuration UUID drifted.").toJson(); //$NON-NLS-1$
        }
        if (liveUuid.equals(before.configurationUuid))
        {
            return ToolResult.success()
                .put("operation", "reseed_baseline") //$NON-NLS-1$ //$NON-NLS-2$
                .put("projectName", project.getName()) //$NON-NLS-1$
                .put("infobaseUuid", infobaseUuid) //$NON-NLS-1$
                .put("changed", false) //$NON-NLS-1$
                .put("message", "Baseline already matches the project Configuration UUID (" + liveUuid //$NON-NLS-1$
                    + "); nothing to reseed - the next update is already incremental.") //$NON-NLS-1$
                .toJson();
        }

        try
        {
            // 1. Rewrite the on-disk baseline directly (preserve timestamp/signatures/generationId, swap the
            //    configuration UUID). This is the authoritative, always-verifiable change.
            rewriteIndexIdxConfigUuid(idx, liveUuid);

            // 2. Best-effort: refresh EDT's in-memory holder so a sync in THIS session sees the new baseline.
            //    The delegate reloads its holder from disk (getState) when touched, so this picks up step 1
            //    regardless of whether the reflective call itself persists anything. If unavailable, the
            //    on-disk baseline is still correct and EDT will read it on the next session / first sync.
            String holderRefresh = SyncBaseline.dropCachedHolder(ibUuid);

            IndexInfo after = idx.toFile().isFile() ? parseIndexIdx(idx) : null;
            boolean ok = after != null && liveUuid.equals(after.configurationUuid);
            Activator.logInfo("sync_control reseed_baseline: " + project.getName() + " infobase " + infobaseUuid //$NON-NLS-1$ //$NON-NLS-2$
                + " " + before.configurationUuid + " -> " + liveUuid + " ok=" + ok + " holder=" + holderRefresh); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            return ToolResult.success()
                .put("operation", "reseed_baseline") //$NON-NLS-1$ //$NON-NLS-2$
                .put("projectName", project.getName()) //$NON-NLS-1$
                .put("infobaseUuid", infobaseUuid) //$NON-NLS-1$
                .put("previousConfigurationUuid", before.configurationUuid) //$NON-NLS-1$
                .put("newConfigurationUuid", liveUuid) //$NON-NLS-1$
                .put("signatureCount", before.signatureCount) //$NON-NLS-1$
                .put("changed", ok) //$NON-NLS-1$
                .put("holderRefresh", holderRefresh) //$NON-NLS-1$
                .put("message", ok //$NON-NLS-1$
                    ? "Baseline re-stamped to the project Configuration UUID (resource signatures preserved). The " //$NON-NLS-1$
                        + "next 'Update infobase' should be INCREMENTAL. If holderRefresh is not 'ok', restart EDT " //$NON-NLS-1$
                        + "(or reseed before the first sync of this session) so EDT re-reads the baseline. If the " //$NON-NLS-1$
                        + "configuration actually differs from the infobase, those changes will NOT be pushed." //$NON-NLS-1$
                    : "On-disk rewrite did not verify; re-check with operation=status.") //$NON-NLS-1$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("sync_control reseed_baseline failed for " + project.getName(), e); //$NON-NLS-1$
            return ToolResult.error("Failed to reseed the baseline for infobase " + infobaseUuid + ": " //$NON-NLS-1$ //$NON-NLS-2$
                + TextSuggest.safeMessage(e)).toJson();
        }
    }

    /**
     * Rewrites {@code index.idx} replacing only the trailing configuration UUID, preserving the timestamp,
     * every resource signature, and the generation id. Writes to a temp file then atomically moves it.
     */
    private static void rewriteIndexIdxConfigUuid(Path file, String newUuid) throws IOException
    {
        SyncBaseline.Index index = SyncBaseline.read(file);
        index.configurationUuid = newUuid;
        SyncBaseline.write(index, file);
    }

    // ---- stuck-merge recovery (clear a "flow active" flag left by an interrupted update) ----
    //
    // An interrupted infobase update (e.g. EDT crash mid-update) can leave the synchronization
    // flow's "active" flag set for an infobase: EDT's
    // InfobaseSynchronizationStateManagerDelegate keeps a Map<UUID, InfobaseLockStateHolder>
    // (infobaseLockStates); the holder's `project` field being non-null means "a flow claims
    // this infobase". It is set BEFORE any work by startSynchronizationFlow and cleared only by
    // the flow-close path, which needs the original opaque flow handle - lost when the call
    // stack died. So the flag stays set for the JVM's life and blocks EVERY subsequent update
    // (manual included) with a "being updated" bounce, until EDT restart. The official flow-close
    // path (cancelSynchronizationFlow / finishSynchronizationFlow) DOES exist but needs the
    // original opaque flow handle, which is unrecoverable after a crash - so there is no reachable
    // way to clear it, and this reflects into the private field via the same getDelegate() pattern
    // reseed_baseline / mark_synchronized already use.

    /**
     * Lists the support-mode snapshots a project holds, saying which cleanup will not touch.
     * <p>
     * The names matter, not the count. A protected snapshot is released one at a time, by the file
     * it belongs to, and after a restart there is nothing else to name it by.
     * </p>
     *
     * @param project the project.
     * @return the snapshots, newest first
     */
    private String doListSupportSnapshots(IProject project)
    {
        java.nio.file.Path settings = settingsOf(project);
        if (settings == null)
        {
            return ToolResult.error("project '" + project.getName() //$NON-NLS-1$
                + "' has no location on disk, so it holds no snapshots.").toJson(); //$NON-NLS-1$
        }
        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
        for (java.nio.file.Path file : SupportSnapshotStore.list(settings))
        {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("name", file.getFileName().toString()); //$NON-NLS-1$
            row.put("protected", SupportSnapshotStore.isProtected(file)); //$NON-NLS-1$
            try
            {
                row.put("bytes", java.nio.file.Files.size(file)); //$NON-NLS-1$
            }
            catch (java.io.IOException | RuntimeException sizeUnknown)
            {
                row.put("bytes", -1L); //$NON-NLS-1$
            }
            rows.add(row);
        }
        return ToolResult.success()
            .put("operation", "list_support_snapshots") //$NON-NLS-1$ //$NON-NLS-2$
            .put("projectName", project.getName()) //$NON-NLS-1$
            .put("kept", SupportSnapshotStore.KEPT) //$NON-NLS-1$
            .put("snapshots", rows) //$NON-NLS-1$
            .put("message", "A protected snapshot is the only way back from a merge whose outcome " //$NON-NLS-1$
                + "is not known here, so cleanup leaves it. Release one with " //$NON-NLS-1$
                + "operation=release_support_snapshot name=<file> once that merge has been dealt " //$NON-NLS-1$
                + "with; it then becomes ordinary and the limit applies to it.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Takes the protection off one snapshot, after which the limit may remove it.
     * <p>
     * Deliberate and one at a time. The protection says nobody has yet established what a merge
     * left behind, and only a person can establish it - so this is the one step the plugin will not
     * take on its own.
     * </p>
     *
     * @param project the project.
     * @param params the call's arguments; {@code name} names the snapshot.
     * @return what happened
     */
    private String doReleaseSupportSnapshot(IProject project, java.util.Map<String, String> params)
    {
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        if (name == null || name.isEmpty())
        {
            return ToolResult.error("release_support_snapshot requires name - the snapshot's file " //$NON-NLS-1$
                + "name, as list_support_snapshots reports it.").toJson(); //$NON-NLS-1$
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return ToolResult.error("name is a file name inside the project's settings, not a " //$NON-NLS-1$
                + "path.").toJson(); //$NON-NLS-1$
        }
        java.nio.file.Path settings = settingsOf(project);
        if (settings == null)
        {
            return ToolResult.error("project '" + project.getName() //$NON-NLS-1$
                + "' has no location on disk, so it holds no snapshots.").toJson(); //$NON-NLS-1$
        }
        java.nio.file.Path file = settings.resolve(name);
        // Named among the project's snapshots, not merely present in the directory. A typo naming
        // some other file was answered as a successful release, and if that file happened to carry
        // the protection comment it was rewritten - a settings file edited by a call that was
        // supposed to touch one snapshot.
        boolean listed = false;
        for (java.nio.file.Path known : SupportSnapshotStore.list(settings))
        {
            if (known.getFileName().toString().equals(name))
            {
                listed = true;
                break;
            }
        }
        if (!listed)
        {
            return ToolResult.error("'" + name + "' is not one of this project's support " //$NON-NLS-1$ //$NON-NLS-2$
                + "snapshots. Ask list_support_snapshots for the names.").toJson(); //$NON-NLS-1$
        }
        String stillProtected = SupportSnapshotStore.clearProtection(file);
        if (stillProtected != null)
        {
            return ToolResult.error("'" + name + "' is still protected: " + stillProtected).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        int removed = SupportSnapshotStore.prune(settings, SupportSnapshotStore.KEPT);
        return ToolResult.success()
            .put("operation", "release_support_snapshot") //$NON-NLS-1$ //$NON-NLS-2$
            .put("projectName", project.getName()) //$NON-NLS-1$
            .put("name", name) //$NON-NLS-1$
            .put("removedByLimit", removed) //$NON-NLS-1$
            .put("message", "'" + name + "' is an ordinary snapshot now, and the limit of " //$NON-NLS-1$ //$NON-NLS-2$
                + SupportSnapshotStore.KEPT + " applies to it.") //$NON-NLS-1$
            .toJson();
    }

    /** Where a project keeps its settings, or <code>null</code> when it has no location. */
    private static java.nio.file.Path settingsOf(IProject project)
    {
        if (project == null || project.getLocation() == null)
        {
            return null;
        }
        return project.getLocation().toFile().toPath().resolve(".settings"); //$NON-NLS-1$
    }

    // ---- rebuild_dump_info (rewrite the stored ConfigDumpInfo.xml with the platform's own) ----

    /** The least patience a rebuild is given - a full dump takes minutes, not seconds. */
    private static final long REBUILD_MIN_TIMEOUT_MS = 60_000L;

    /** The most, for configurations whose full dump is genuinely long. */
    private static final long REBUILD_MAX_TIMEOUT_MS = 3_600_000L;

    /** The default: ten minutes. */
    private static final long REBUILD_DEFAULT_TIMEOUT_MS = 600_000L;

    /**
     * Rebuilds the stored {@code ConfigDumpInfo.xml} of one infobase with the platform's own
     * Designer dump, under the per-infobase claim, with the previous file backed up beside it and
     * EDT taken off the infobase for the Designer run and put back after it.
     *
     * <p>Every outcome is named on its own: which dump produced the file ({@code rebuildPath}, with
     * the reason when the fallback was taken), what the swap did to the file ({@code fileState}),
     * what the reconnection did, what the format recorded for this infobase says
     * ({@code formatPair}). A mismatched file is the one condition {@code update_database} stops on
     * before asking the infobase anything, and this is the operation that cures it.</p>
     *
     * @param project the project whose infobase is targeted
     * @param params the call; {@code confirm} and, when the project has several applications,
     *            {@code applicationId} name the target
     * @return the outcome as a JSON answer
     */
    private String doRebuildDumpInfo(IProject project, Map<String, String> params)
    {
        Boolean confirm = JsonUtils.extractBooleanArgumentNullable(params, "confirm"); //$NON-NLS-1$
        if (confirm == null || !confirm.booleanValue())
        {
            return ToolResult.error("rebuild_dump_info replaces the stored ConfigDumpInfo.xml of " //$NON-NLS-1$
                + "one infobase with a fresh dump made by that infobase's own platform Designer: " //$NON-NLS-1$
                + "EDT is disconnected from the infobase for the Designer run, the previous file is " //$NON-NLS-1$
                + "backed up beside it and the new one is put in its place. Run update_database " //$NON-NLS-1$
                + "dryRun=true first if you want to see the mismatch this would cure. Re-run with " //$NON-NLS-1$
                + "confirm=true to proceed.").toJson(); //$NON-NLS-1$
        }
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        long timeoutMs = readRebuildTimeout(params);

        DumpInfoRebuilder.Outcome outcome = DumpInfoRebuilder.rebuildViaEdt(project.getName(),
            applicationId, timeoutMs);
        Activator.logInfo("sync_control rebuild_dump_info: " + project.getName() //$NON-NLS-1$
            + (applicationId == null || applicationId.isEmpty() ? "" : " app=" + applicationId) //$NON-NLS-1$ //$NON-NLS-2$
            + " ok=" + outcome.ok //$NON-NLS-1$
            + " path=" + outcome.rebuildPath //$NON-NLS-1$
            + " fileState=" + outcome.fileState //$NON-NLS-1$
            + (outcome.oldFormat == null ? "" : " " + outcome.oldFormat) //$NON-NLS-1$ //$NON-NLS-2$
            + (outcome.newFormat == null ? "" : " -> " + outcome.newFormat) //$NON-NLS-1$ //$NON-NLS-2$
            + (outcome.error == null ? "" : " error: " + outcome.error)); //$NON-NLS-1$ //$NON-NLS-2$

        ToolResult answer = outcome.ok
            ? ToolResult.success().put("message", DumpInfoRebuilder.successMessage(outcome)) //$NON-NLS-1$
            : ToolResult.error(outcome.error == null ? "The rebuild failed." : outcome.error); //$NON-NLS-1$
        answer.put("operation", "rebuild_dump_info"); //$NON-NLS-1$ //$NON-NLS-2$
        answer.put("projectName", project.getName()); //$NON-NLS-1$
        if (outcome.infobaseName != null)
        {
            answer.put("infobaseName", outcome.infobaseName); //$NON-NLS-1$
        }
        if (outcome.failureKind != null)
        {
            answer.put("failureKind", outcome.failureKind); //$NON-NLS-1$
        }
        answer.put("ok", Boolean.valueOf(outcome.ok)); //$NON-NLS-1$
        answer.put("fileState", outcome.fileState); //$NON-NLS-1$
        if (outcome.platformVersion != null)
        {
            answer.put("platformVersion", outcome.platformVersion); //$NON-NLS-1$
        }
        if (outcome.oldFormat != null)
        {
            answer.put("oldFormat", outcome.oldFormat); //$NON-NLS-1$
        }
        if (outcome.newFormat != null)
        {
            answer.put("newFormat", outcome.newFormat); //$NON-NLS-1$
        }
        if (outcome.rebuildPath != null)
        {
            answer.put("rebuildPath", outcome.rebuildPath); //$NON-NLS-1$
        }
        if (outcome.records >= 0)
        {
            answer.put("records", Integer.valueOf(outcome.records)); //$NON-NLS-1$
        }
        if (outcome.backupPath != null)
        {
            answer.put("backupPath", outcome.backupPath); //$NON-NLS-1$
        }
        if (outcome.pairRemembered != null)
        {
            answer.put("formatPair", outcome.pairRemembered); //$NON-NLS-1$
        }
        if (outcome.copyRecord != null)
        {
            answer.put("copyRecord", outcome.copyRecord); //$NON-NLS-1$
        }
        if (outcome.holderRefresh != null)
        {
            answer.put("holderRefresh", outcome.holderRefresh); //$NON-NLS-1$
        }
        if (outcome.reconnectError != null)
        {
            answer.put("reconnectError", outcome.reconnectError); //$NON-NLS-1$
        }
        answer.put("durationMs", Long.valueOf(outcome.durationMs)); //$NON-NLS-1$
        return answer.toJson();
    }

    /**
     * The wait budget off the call, clamped to the rebuild's own bounds and defaulted to ten
     * minutes - a value in the update's 5-120s range would abandon nearly every real dump.
     */
    private static long readRebuildTimeout(Map<String, String> params)
    {
        Integer askedSeconds = TimeoutArgs.requestedSeconds(params);
        if (askedSeconds == null)
        {
            return REBUILD_DEFAULT_TIMEOUT_MS;
        }
        long askedMs = askedSeconds.intValue() * 1000L;
        return Math.max(REBUILD_MIN_TIMEOUT_MS, Math.min(REBUILD_MAX_TIMEOUT_MS, askedMs));
    }

    // ---- retrieve_database_changes (pull the infobase's changes into the project) -------

    /** The least patience a pull's waits are given. */
    private static final long RETRIEVE_MIN_TIMEOUT_MS = 30_000L;

    /** The most, for a configuration whose model takes long to rebuild after the pull. */
    private static final long RETRIEVE_MAX_TIMEOUT_MS = 3_600_000L;

    /** The default: five minutes. */
    private static final long RETRIEVE_DEFAULT_TIMEOUT_MS = 300_000L;

    /**
     * Pulls the changes made in the infobase into the project, through EDT's own synchronization
     * manager - the opposite direction to {@code update_database}.
     *
     * <p>The change list is read by the platform, which then asks {@link DatabaseChangesResolver}
     * whether the infobase side may replace what the project holds. Nothing is written by this tool
     * itself: the platform applies the changes, and this method reports what it did. A refusal
     * ({@code replaceLocal=false} with changes in the project) reaches here as
     * {@code CHANGES_IGNORE} and is answered as a failed call carrying the resolver's own wording
     * and the size of what the project holds, because a pull that silently did nothing reads like a
     * pull that found nothing.</p>
     *
     * <p>What the platform does not do is remove the sources of objects the infobase no longer has:
     * the object leaves the model and its files stay behind. Those are removed afterwards, by name,
     * through {@link RemovedObjectFiles}, and the project is refreshed and its build waited for so
     * the caller's next call (usually {@code update_database dryRun=true}) sees a settled model.</p>
     *
     * <p>The platform call cannot be pulled back once it has started. The call waits
     * {@code timeoutSeconds} and, still running, answers Pending with a runKey; a later call with
     * that runKey waits again, and {@code cancel=true} with the runKey stops tracking and says when
     * the platform call is still going. A thick client this EDT launched is refused before the
     * platform is asked. {@code timeoutSeconds} is also the budget
     * {@link BuildTaskHelper#waitForBuildAndDerivedData} uses for the derived-data wait; the
     * build-job wait itself is not bounded, and running out of the derived-data budget ends
     * quietly.</p>
     *
     * @param project the project to pull into
     * @param params the call; {@code applicationId} names the binding when the project has several,
     *            {@code replaceLocal} allows the infobase side to replace the project's changes and
     *            {@code markSynchronized} asks for the baseline to be rewritten afterwards
     * @return the outcome as a JSON answer
     */
    private String doRetrieveDatabaseChanges(IProject project, Map<String, String> params)
    {
        String runKeyParam = JsonUtils.extractStringArgument(params, "runKey"); //$NON-NLS-1$
        boolean cancel = JsonUtils.extractBooleanArgument(params, "cancel", false); //$NON-NLS-1$
        if (runKeyParam != null && !runKeyParam.isEmpty() && cancel)
        {
            return cancelRetrieve(runKeyParam);
        }
        long timeoutMs = readRetrieveTimeout(params);
        if (runKeyParam != null && !runKeyParam.isEmpty())
        {
            return resumeRetrieve(runKeyParam, timeoutMs);
        }
        boolean replaceLocal = JsonUtils.extractBooleanArgument(params, "replaceLocal", false); //$NON-NLS-1$ //$NON-NLS-2$
        boolean markSynchronized = JsonUtils.extractBooleanArgument(params, "markSynchronized", false); //$NON-NLS-1$ //$NON-NLS-2$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        String runKey = PendingWorkRegistry.computeRunKey(project.getName(),
            applicationId == null ? "" : applicationId, //$NON-NLS-1$
            String.valueOf(replaceLocal), String.valueOf(markSynchronized));
        return trackRetrieve(runKey, project.getName(), timeoutMs,
            entry -> pullTheInfobase(project, params, entry));
    }

    /**
     * The pull itself, run on the retrieve registry's thread.
     *
     * @param project the project to pull into
     * @param params the call
     * @param entry the run this pull belongs to; its launch is claimed immediately before the
     *            platform call
     * @return the outcome as a JSON answer
     */
    private String pullTheInfobase(IProject project, Map<String, String> params,
        PendingWorkRegistry.PendingEntry entry)
    {
        boolean replaceLocal = JsonUtils.extractBooleanArgument(params, "replaceLocal", false); //$NON-NLS-1$ //$NON-NLS-2$
        boolean markSynchronized = JsonUtils.extractBooleanArgument(params, "markSynchronized", false); //$NON-NLS-1$ //$NON-NLS-2$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        long timeoutMs = readRetrieveTimeout(params);

        IInfobaseSynchronizationManager manager = synchronizationManager();
        if (manager == null)
        {
            return ToolResult.error("IInfobaseSynchronizationManager is not available in this EDT runtime - " //$NON-NLS-1$
                + "retrieve_database_changes must run inside EDT.").toJson(); //$NON-NLS-1$
        }

        // The pull reads the project model, so a build in progress is waited out first - the rule
        // every other reader here follows. What is NOT waited for is the infobase's own state: the
        // platform call below reads that itself.
        String notReady = ProjectStateGuard.checkReadyOrWait(project, timeoutMs);
        if (notReady != null)
        {
            return ToolResult.error(notReady).toJson();
        }

        IApplicationManager appManager = applicationManager();
        if (appManager == null)
        {
            return ToolResult.error("The IApplicationManager service is currently unavailable, so the " //$NON-NLS-1$
                + "infobase to pull from cannot be named.").toJson(); //$NON-NLS-1$
        }

        IProject infobaseProject = project;
        List<IApplication> applications;
        try
        {
            applications = appManager.getApplications(project);
            if (applications == null || applications.isEmpty())
            {
                // An extension project has no infobase of its own - it shares the one belonging to
                // the configuration it extends, and pulling that one is what brings the changes of
                // that database into reach of the extension.
                IProject parent = BmCommonModuleGuards.parentProjectOf(project);
                if (parent != null && parent.exists() && parent.isOpen())
                {
                    applications = appManager.getApplications(parent);
                    infobaseProject = parent;
                }
            }
        }
        catch (Throwable t)
        {
            return ToolResult.error("The applications of " + project.getName() + " could not be read: " //$NON-NLS-1$ //$NON-NLS-2$
                + TextSuggest.safeMessage(t)).toJson();
        }
        if (applications == null)
        {
            return ToolResult.error("The applications of " + infobaseProject.getName() //$NON-NLS-1$
                + " could not be read in this runtime, so no infobase can be named to pull from.").toJson(); //$NON-NLS-1$
        }

        boolean named = applicationId != null && !applicationId.isEmpty();
        IApplication application = null;
        List<String> available = new ArrayList<>();
        for (IApplication candidate : applications)
        {
            if (!(candidate instanceof IInfobaseApplication))
            {
                continue;
            }
            available.add(candidate.getId());
            if (!named || applicationId.equals(candidate.getId()))
            {
                // Without a name the first infobase binding is taken, which is what a project with
                // one infobase has; a project with several is answered below with their ids.
                application = candidate;
                break;
            }
        }
        if (application == null)
        {
            return ToolResult.error((named
                ? "No application with id '" + applicationId + "' on " //$NON-NLS-1$ //$NON-NLS-2$
                : "No infobase application on ") //$NON-NLS-1$
                + infobaseProject.getName() + ". Bound infobases: " //$NON-NLS-1$
                + (available.isEmpty() ? "none" : String.join(", ", available)) //$NON-NLS-1$ //$NON-NLS-2$
                + ". Call infobase_admin operation=get_applications to list valid application ids.").toJson(); //$NON-NLS-1$
        }
        InfobaseReference infobase = ((IInfobaseApplication)application).getInfobase();
        if (infobase == null || infobase.getUuid() == null)
        {
            return ToolResult.error("The application " + application.getId() //$NON-NLS-1$
                + " carries no infobase reference, so there is nothing to pull from.").toJson(); //$NON-NLS-1$
        }

        String infobaseUuid = infobase.getUuid().toString();
        boolean viaParent = !infobaseProject.getName().equals(project.getName());
        Boolean connected = null;
        InfobaseEqualityState equalityBefore = null;
        try
        {
            connected = Boolean.valueOf(manager.isConnected(infobaseProject, infobase));
            equalityBefore = manager.getEqualityState(infobaseProject, infobase);
        }
        catch (Throwable t)
        {
            // Reading the state is a diagnosis, not a condition of the pull: a failure here is
            // reported as "not read", never as a refusal.
            Activator.logWarning("sync_control retrieve_database_changes: the state of infobase " //$NON-NLS-1$
                + infobaseUuid + " was not read: " + TextSuggest.safeMessage(t)); //$NON-NLS-1$
        }

        DatabaseChangesResolver resolver = new DatabaseChangesResolver(replaceLocal);
        List<String> thickClients = InfobaseHolders.thickClientLaunchNames(application.getId());
        String clientRefusal = refusalForThickClients(thickClients);
        if (clientRefusal != null)
        {
            ToolResult refused = ToolResult.error(clientRefusal);
            refused.put("tag", ErrorTags.BUSY.wire()); //$NON-NLS-1$
            refused.put("heldBy", thickClients); //$NON-NLS-1$
            return describePull(refused, project, infobaseProject, application, infobaseUuid, replaceLocal,
                resolver, viaParent).toJson();
        }
        if (entry != null && !entry.claimTheLaunch())
        {
            ToolResult cancelled = ToolResult.error("The pull was cancelled before it reached " //$NON-NLS-1$
                + "the infobase. Nothing was started; the project is as it was."); //$NON-NLS-1$
            cancelled.put("tag", ErrorTags.CANCELLED.wire()); //$NON-NLS-1$
            return describePull(cancelled, project, infobaseProject, application, infobaseUuid,
                replaceLocal, resolver, viaParent).toJson();
        }
        String identity = InfobaseIdentity.of(infobase);
        MonopolyLock.Claim claim = MonopolyLock.claim(identity, "retrieve_database_changes"); //$NON-NLS-1$
        if (!claim.granted())
        {
            claim.close();
            ToolResult taken = ToolResult.error(claim.refusal());
            taken.put("tag", ErrorTags.BUSY.wire()); //$NON-NLS-1$
            if (MonopolyLock.isHeldByThisInstance(claim.heldBy))
            {
                taken.put("heldByThisInstance", Boolean.TRUE); //$NON-NLS-1$
            }
            return describePull(taken, project, infobaseProject, application, infobaseUuid, replaceLocal,
                resolver, viaParent).toJson();
        }
        try
        {
        long started = System.currentTimeMillis();
        InfobaseSyncResolution resolution;
        try
        {
            // The boolean says "pull even while EDT does not know the infobase's state". The call
            // was asked for by name, and an unknown state is reported in the answer below - as a
            // no-op it would be indistinguishable from a pull that found nothing.
            resolution = manager.retrieveInfobaseChanges(infobaseProject, infobase, resolver, true,
                new NullProgressMonitor());
        }
        catch (InfobaseSynchronizationException e)
        {
            ToolResult failed = ToolResult.error("Pulling the infobase's changes into " //$NON-NLS-1$
                + infobaseProject.getName() + " failed: " + TextSuggest.safeMessage(e)); //$NON-NLS-1$
            return describePull(failed, project, infobaseProject, application, infobaseUuid, replaceLocal,
                resolver, viaParent).toJson();
        }
        long durationMs = System.currentTimeMillis() - started;

        InfobaseChangesResolutionResult result = resolution == null
            ? null : resolution.getInfobaseChangesResolutionResult();
        if (result == null)
        {
            ToolResult empty = ToolResult.error("EDT answered nothing about the changes of infobase " //$NON-NLS-1$
                + infobaseUuid + ", so it is not known whether anything was pulled."); //$NON-NLS-1$
            return describePull(empty, project, infobaseProject, application, infobaseUuid, replaceLocal,
                resolver, viaParent).toJson();
        }
        if (result == InfobaseChangesResolutionResult.UNKNOWN)
        {
            ToolResult unknown = ToolResult.error("EDT could not tell whether infobase " + infobaseUuid //$NON-NLS-1$
                + " differs from " + infobaseProject.getName() + " (equality state " //$NON-NLS-1$ //$NON-NLS-2$
                + (equalityBefore == null ? "not read" : equalityBefore.name()) //$NON-NLS-1$
                + ", connected=" + connected + "), so nothing was pulled. Run operation=status to see " //$NON-NLS-1$ //$NON-NLS-2$
                + "which baseline EDT holds for this infobase."); //$NON-NLS-1$
            return describePull(unknown, project, infobaseProject, application, infobaseUuid, replaceLocal,
                resolver, viaParent).toJson();
        }
        if (result == InfobaseChangesResolutionResult.CHANGES_IGNORE)
        {
            // The resolver refused, which is the only answer this tool gives to a conflict it was
            // not allowed to settle.
            String why = resolver.refusal();
            ToolResult refused = ToolResult.error(why != null ? why
                : "EDT dropped the changes of infobase " + infobaseUuid //$NON-NLS-1$
                    + " without applying them, and did not say why."); //$NON-NLS-1$
            return describePull(refused, project, infobaseProject, application, infobaseUuid, replaceLocal,
                resolver, viaParent).toJson();
        }
        if (result == InfobaseChangesResolutionResult.CHANGES_NOT_RESOLVED)
        {
            ToolResult unresolved = ToolResult.error("EDT loaded the changes of infobase " + infobaseUuid //$NON-NLS-1$
                + " but left them unresolved - the conflict was deferred to a resolution this call " //$NON-NLS-1$
                + "cannot complete. Nothing is reported as applied; call operation=status before " //$NON-NLS-1$
                + "starting anything else."); //$NON-NLS-1$
            return describePull(unresolved, project, infobaseProject, application, infobaseUuid, replaceLocal,
                resolver, viaParent).toJson();
        }

        boolean pulled = result == InfobaseChangesResolutionResult.CHANGES_RESOLVED;
        RemovedObjectFiles.Outcome removal = new RemovedObjectFiles.Outcome();
        boolean refreshed = false;
        String refreshError = null;
        String buildWaitError = null;
        if (pulled)
        {
            // The object left the model, its sources did not: they are removed here by the names
            // the infobase's own change list gave, and nothing outside those objects is touched.
            removal = RemovedObjectFiles.deleteFor(infobaseProject, resolver.deletedObjectNames());
            try
            {
                infobaseProject.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
                refreshed = true;
            }
            catch (CoreException e)
            {
                refreshError = TextSuggest.safeMessage(e);
            }
            try
            {
                BuildTaskHelper.waitForBuildAndDerivedData(infobaseProject, timeoutMs, new NullProgressMonitor());
            }
            catch (RuntimeException e)
            {
                buildWaitError = TextSuggest.safeMessage(e);
            }
        }

        ToolResult answer = ToolResult.success();
        answer.put("pulled", Boolean.valueOf(pulled)); //$NON-NLS-1$
        answer.put("resolution", result.name()); //$NON-NLS-1$
        answer.put("durationMs", Long.valueOf(durationMs)); //$NON-NLS-1$
        answer.put("removedSources", removal.removed); //$NON-NLS-1$
        answer.put("skippedObjects", removal.skipped); //$NON-NLS-1$
        answer.put("removalFailures", removal.failures); //$NON-NLS-1$
        if (!removal.filesKept.isEmpty())
        {
            List<Map<String, String>> kept = new ArrayList<>();
            for (RemovedObjectFiles.Kept one : removal.filesKept)
            {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("name", one.name); //$NON-NLS-1$
                row.put("path", one.path); //$NON-NLS-1$
                kept.add(row);
            }
            answer.put("filesKept", kept); //$NON-NLS-1$
        }
        answer.put("refreshed", Boolean.valueOf(refreshed)); //$NON-NLS-1$
        if (resolver.fullReloadRequired())
        {
            answer.put("fullReloadRequired", Boolean.TRUE); //$NON-NLS-1$
        }
        if (refreshError != null)
        {
            answer.put("refreshError", refreshError); //$NON-NLS-1$
        }
        if (buildWaitError != null)
        {
            answer.put("buildWaitError", buildWaitError); //$NON-NLS-1$
        }
        answer.put("message", pulled //$NON-NLS-1$
            ? "The changes of infobase " + infobaseUuid + " were pulled into " //$NON-NLS-1$ //$NON-NLS-2$
                + infobaseProject.getName() + "." //$NON-NLS-1$
            : "The project already matches infobase " + infobaseUuid + "; nothing was pulled."); //$NON-NLS-1$
        describePull(answer, project, infobaseProject, application, infobaseUuid, replaceLocal, resolver,
            viaParent);
        if (markSynchronized)
        {
            // Only on a successful pull: the mark asserts that the project and the infobase are the
            // same, and after a pull that is exactly what the platform just applied.
            Map<String, String> markParams = new LinkedHashMap<>();
            markParams.put("infobaseUuid", infobaseUuid); //$NON-NLS-1$
            markParams.put("confirm", "true"); //$NON-NLS-1$ //$NON-NLS-2$
            surfaceTheBaselineMark(answer, doMarkSynchronized(infobaseProject, markParams));
        }
        return answer.toJson();
        }
        finally
        {
            claim.close();
        }
    }

    /**
     * Adds what every answer of {@code retrieve_database_changes} carries, whichever way the pull
     * ended: which project and application the call named, what the resolver saw, and who owns the
     * infobase.
     *
     * @param answer the answer under construction
     * @param project the project the call named
     * @param infobaseProject the project the infobase belongs to
     * @param application the binding the pull was aimed at
     * @param infobaseUuid the infobase pulled from
     * @param replaceLocal the flag the call carried
     * @param resolver what the pull's conflict question saw
     * @param viaParent whether the infobase belongs to the configuration this project extends
     * @return the same answer
     */
    private static ToolResult describePull(ToolResult answer, IProject project, IProject infobaseProject,
        IApplication application, String infobaseUuid, boolean replaceLocal,
        DatabaseChangesResolver resolver, boolean viaParent)
    {
        answer.put("operation", "retrieve_database_changes"); //$NON-NLS-1$ //$NON-NLS-2$
        answer.put("projectName", project.getName()); //$NON-NLS-1$
        answer.put("applicationId", application.getId()); //$NON-NLS-1$
        answer.put("applicationName", application.getName()); //$NON-NLS-1$
        answer.put("infobaseUuid", infobaseUuid); //$NON-NLS-1$
        answer.put("replaceLocal", Boolean.valueOf(replaceLocal)); //$NON-NLS-1$
        answer.put("localChanges", Integer.valueOf(resolver.localChangeCount())); //$NON-NLS-1$
        answer.put("conflictAsked", Boolean.valueOf(resolver.sawChangeSet())); //$NON-NLS-1$
        answer.put("infobaseChangesNew", Integer.valueOf(resolver.countOf(ObjectChangeType.NEW))); //$NON-NLS-1$
        answer.put("infobaseChangesModified", Integer.valueOf(resolver.countOf(ObjectChangeType.MODIFIED))); //$NON-NLS-1$
        answer.put("infobaseChangesDeleted", Integer.valueOf(resolver.countOf(ObjectChangeType.DELETED))); //$NON-NLS-1$
        if (viaParent)
        {
            answer.put("infobaseProject", infobaseProject.getName()); //$NON-NLS-1$
            answer.put("viaParentProject", "This is an extension project and has no infobase of its " //$NON-NLS-1$ //$NON-NLS-2$
                + "own. The infobase of the configuration it extends (" + infobaseProject.getName() //$NON-NLS-1$
                + ") was the one pulled from."); //$NON-NLS-1$
        }
        return answer;
    }

    /**
     * The wait budget off the call, clamped to this operation's bounds and defaulted to five
     * minutes - the update's 5-120s range leaves no room for a configuration whose model is rebuilt
     * from scratch after a pull.
     *
     * @param params the call
     * @return the budget in milliseconds
     */
    private static long readRetrieveTimeout(Map<String, String> params)
    {
        Integer askedSeconds = TimeoutArgs.requestedSeconds(params);
        if (askedSeconds == null)
        {
            return RETRIEVE_DEFAULT_TIMEOUT_MS;
        }
        long askedMs = askedSeconds.intValue() * 1000L;
        return Math.max(RETRIEVE_MIN_TIMEOUT_MS, Math.min(RETRIEVE_MAX_TIMEOUT_MS, askedMs));
    }

    /**
     * Runs a pull on the retrieve registry and waits up to the budget.
     * <p>
     * A finished entry for the same key is dropped first, so a fresh call is not served the previous
     * pull. An in-flight call with the same key joins that run. Past the budget the answer is Pending
     * and the run stays for a later call with the runKey.
     * </p>
     *
     * @param runKey the key these arguments own
     * @param projectName the project, named in a Pending body
     * @param timeoutMs how long to wait before answering Pending
     * @param work the pull, handed the entry it runs under
     * @return a JSON result body
     */
    static String trackRetrieve(String runKey, String projectName, long timeoutMs,
        Function<PendingWorkRegistry.PendingEntry, String> work)
    {
        PendingWorkRegistry registry = PendingWorkRegistry.RETRIEVE;
        registry.pruneExpired();
        PendingWorkRegistry.PendingEntry existing = registry.get(runKey);
        if (existing != null && existing.isDone())
        {
            registry.remove(runKey);
        }
        PendingWorkRegistry.PendingEntry entry = registry.getOrStart(runKey, work);
        entry.subject = projectName;
        entry.workKind = "retrieve_database_changes"; //$NON-NLS-1$
        entry.startedBy = "retrieve_database_changes"; //$NON-NLS-1$
        String result = entry.await(timeoutMs);
        if (result != null)
        {
            registry.remove(runKey);
            return result;
        }
        return pendingRetrieveJson(runKey, entry, projectName, timeoutMs);
    }

    /**
     * Waits again on a pull that already answered Pending.
     *
     * @param runKey the key to poll
     * @param timeoutMs how long to wait before answering Pending again
     * @return a JSON result body
     */
    static String resumeRetrieve(String runKey, long timeoutMs)
    {
        PendingWorkRegistry registry = PendingWorkRegistry.RETRIEVE;
        registry.pruneExpired();
        PendingWorkRegistry.PendingEntry entry = registry.get(runKey);
        if (entry == null)
        {
            return ToolResult.error("runKey was not found - the pull either already finished and was " //$NON-NLS-1$
                + "retrieved, or it was abandoned and evicted. Send a fresh request without runKey " //$NON-NLS-1$
                + "to start again.") //$NON-NLS-1$
                .put("operation", "retrieve_database_changes") //$NON-NLS-1$ //$NON-NLS-2$
                .put("runKey", runKey) //$NON-NLS-1$
                .toJson();
        }
        if (entry.workKind != null && !"retrieve_database_changes".equals(entry.workKind)) //$NON-NLS-1$
        {
            return ToolResult.error("runKey belongs to " + entry.workKind //$NON-NLS-1$
                + ", not to retrieve_database_changes.") //$NON-NLS-1$
                .put("operation", "retrieve_database_changes") //$NON-NLS-1$ //$NON-NLS-2$
                .put("runKey", runKey) //$NON-NLS-1$
                .toJson();
        }
        String result = entry.await(timeoutMs);
        if (result != null)
        {
            registry.remove(runKey);
            return result;
        }
        return pendingRetrieveJson(runKey, entry, null, timeoutMs);
    }

    /**
     * Stops tracking a pull and says whether the platform call is still running.
     *
     * @param runKey the key to detach
     * @return a JSON result body
     */
    static String cancelRetrieve(String runKey)
    {
        PendingWorkRegistry.StopOutcome outcome = PendingWorkRegistry.RETRIEVE.cancelAndStop(runKey);
        String note;
        if (outcome == PendingWorkRegistry.StopOutcome.STILL_RUNNING)
        {
            note = "Stopped tracking this pull. The platform call is still running and cannot be " //$NON-NLS-1$
                + "pulled back."; //$NON-NLS-1$
        }
        else if (outcome == PendingWorkRegistry.StopOutcome.STOPPED)
        {
            note = "Stopped tracking this pull. Its work is no longer executing: either the " //$NON-NLS-1$
                + "flag kept the launch from starting, or the platform call returned."; //$NON-NLS-1$
        }
        else
        {
            note = "runKey was not found (the pull already finished and was retrieved, or it was " //$NON-NLS-1$
                + "evicted)."; //$NON-NLS-1$
        }
        ToolResult answer = ToolResult.success()
            .put("operation", "retrieve_database_changes") //$NON-NLS-1$ //$NON-NLS-2$
            .put("runKey", runKey) //$NON-NLS-1$
            .put("cancelled", Boolean.valueOf(outcome != PendingWorkRegistry.StopOutcome.NOTHING_TO_STOP)) //$NON-NLS-1$
            .put("note", note); //$NON-NLS-1$
        if (outcome == PendingWorkRegistry.StopOutcome.STILL_RUNNING)
        {
            answer.put("platformCallStillRunning", Boolean.TRUE); //$NON-NLS-1$
        }
        return answer.toJson();
    }

    /**
     * The Pending body returned when the wait budget runs out before the pull finishes.
     *
     * @param runKey the key to resume with
     * @param entry the in-flight pull
     * @param projectName the project, or {@code null} to omit
     * @param timeoutMs how long was waited
     * @return a JSON Pending body
     */
    private static String pendingRetrieveJson(String runKey, PendingWorkRegistry.PendingEntry entry,
        String projectName, long timeoutMs)
    {
        ToolResult body = ToolResult.success()
            .put("operation", "retrieve_database_changes") //$NON-NLS-1$ //$NON-NLS-2$
            .put("status", "Pending") //$NON-NLS-1$ //$NON-NLS-2$
            .put("runKey", runKey) //$NON-NLS-1$
            .put("elapsedMs", entry.elapsedMs()) //$NON-NLS-1$
            .put("waitedMs", Long.valueOf(timeoutMs)) //$NON-NLS-1$
            .put("hint", "Pull still running. Re-invoke with runKey=\"" + runKey //$NON-NLS-1$ //$NON-NLS-2$
                + "\" to keep waiting. Add cancel=true alongside the runKey to stop tracking it; " //$NON-NLS-1$
                + "the platform call, once started, keeps running."); //$NON-NLS-1$
        if (projectName != null)
        {
            body.put("projectName", projectName); //$NON-NLS-1$
        }
        return PendingEnvelope.mark(body).toJson();
    }

    /**
     * The refusal to use when a thick client this EDT launched is holding the infobase, or
     * {@code null} when none is visible.
     * <p>
     * A Designer opened by hand is not named here: this server cannot see one, and the sentence is
     * not invented for a holder that was not found.
     * </p>
     *
     * @param launchNames the thick-client launch configurations {@link InfobaseHolders} reported;
     *            may be <code>null</code>
     * @return the refusal, or <code>null</code> when the pull may proceed
     */
    static String refusalForThickClients(List<String> launchNames)
    {
        if (launchNames == null || launchNames.isEmpty())
        {
            return null;
        }
        return "The pull was not started: a thick client this EDT launched is holding the infobase: " //$NON-NLS-1$
            + String.join(", ", launchNames) + "."; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Puts the baseline rewrite on the answer so a failure is visible beside {@code pulled}, not
     * only inside {@code baselineMark}.
     *
     * @param answer the successful pull
     * @param markJson what {@code mark_synchronized} answered
     */
    static void surfaceTheBaselineMark(ToolResult answer, String markJson)
    {
        JsonElement parsed;
        try
        {
            parsed = JsonParser.parseString(markJson);
        }
        catch (RuntimeException e)
        {
            answer.put("baselineMarked", Boolean.FALSE); //$NON-NLS-1$
            answer.put("baselineMarkError", "The baseline rewrite did not answer JSON: " //$NON-NLS-1$ //$NON-NLS-2$
                + TextSuggest.safeMessage(e));
            return;
        }
        answer.put("baselineMark", parsed); //$NON-NLS-1$
        boolean marked = false;
        String reason = "The baseline was not rewritten."; //$NON-NLS-1$
        if (parsed != null && parsed.isJsonObject())
        {
            JsonObject object = parsed.getAsJsonObject();
            marked = object.has("success") && object.get("success").isJsonPrimitive() //$NON-NLS-1$ //$NON-NLS-2$
                && object.get("success").getAsBoolean(); //$NON-NLS-1$
            if (!marked && object.has("error") && object.get("error").isJsonPrimitive()) //$NON-NLS-1$ //$NON-NLS-2$
            {
                reason = object.get("error").getAsString(); //$NON-NLS-1$
            }
        }
        answer.put("baselineMarked", Boolean.valueOf(marked)); //$NON-NLS-1$
        if (!marked)
        {
            answer.put("baselineMarkError", reason); //$NON-NLS-1$
        }
    }

    /**
     * Read-only: reports every infobase lock holder EDT has created THIS session and whether its
     * flow-active flag is stuck (project != null). Only infobases touched this session appear -
     * inherent to an in-memory-only map.
     */
    private String doDiagnoseStuckLocks(IProject project)
    {
        try
        {
            IInfobaseSynchronizationStateManager mgr =
                ServiceAccess.get(IInfobaseSynchronizationStateManager.class);
            if (mgr == null)
            {
                return ToolResult.error("Infobase synchronization state manager unavailable on this EDT build.").toJson(); //$NON-NLS-1$
            }
            Object delegate = mgr.getClass().getMethod("getDelegate").invoke(mgr); //$NON-NLS-1$
            if (delegate == null)
            {
                return ToolResult.error("Synchronization state delegate unavailable.").toJson(); //$NON-NLS-1$
            }
            Field lockStatesField = SyncBaseline.findField(delegate.getClass(), "infobaseLockStates"); //$NON-NLS-1$
            if (lockStatesField == null)
            {
                return ToolResult.error("infobaseLockStates field not found - the stuck-merge mechanism differs " //$NON-NLS-1$
                    + "on this EDT build; cannot diagnose.").toJson(); //$NON-NLS-1$
            }
            lockStatesField.setAccessible(true);
            Object value = lockStatesField.get(delegate);
            if (!(value instanceof Map))
            {
                return ToolResult.error("infobaseLockStates is present but not a Map on this EDT build - the " //$NON-NLS-1$
                    + "stuck-merge mechanism differs here; cannot diagnose.").toJson(); //$NON-NLS-1$
            }
            List<Map<String, Object>> locks = new ArrayList<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>)value).entrySet())
            {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("infobaseUuid", String.valueOf(e.getKey())); //$NON-NLS-1$
                String stuckProject = holderProjectName(e.getValue());
                row.put("stuck", stuckProject != null); //$NON-NLS-1$
                row.put("stuckProject", stuckProject); //$NON-NLS-1$
                locks.add(row);
            }
            return ToolResult.success()
                .put("operation", "diagnose_stuck_locks") //$NON-NLS-1$ //$NON-NLS-2$
                .put("locks", locks) //$NON-NLS-1$
                .put("note", "stuck=true means a synchronization flow claims that infobase and never released " //$NON-NLS-1$
                    + "it (typically an interrupted update) - it blocks every update until EDT restart or " //$NON-NLS-1$
                    + "recover_stuck_merge. Only infobases touched in THIS EDT session appear here.") //$NON-NLS-1$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("sync_control diagnose_stuck_locks failed", e); //$NON-NLS-1$
            return ToolResult.error("diagnose_stuck_locks failed: " + TextSuggest.safeMessage(e)).toJson(); //$NON-NLS-1$
        }
    }

    /** The name of the IProject holding the flow-active flag, or {@code null} if not stuck / unreadable. */
    private static String holderProjectName(Object holder)
    {
        if (holder == null)
        {
            return null;
        }
        try
        {
            Field pf = SyncBaseline.findField(holder.getClass(), "project"); //$NON-NLS-1$
            if (pf == null)
            {
                return null;
            }
            pf.setAccessible(true);
            Object p = pf.get(holder);
            return (p instanceof IProject) ? ((IProject)p).getName() : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * DANGEROUS (confirm=true): force-clears the flow-active flag for one infobase so a stuck
     * update stops blocking every update - no EDT restart. It CANNOT distinguish an abandoned flow
     * from a genuine one still running, so it will also clear a real in-progress merge. Explicit
     * opt-in only, mirroring reseed_baseline / mark_synchronized.
     */
    private String doRecoverStuckMerge(IProject project, Map<String, String> params)
    {
        Boolean confirm = JsonUtils.extractBooleanArgumentNullable(params, "confirm"); //$NON-NLS-1$
        String infobaseUuid = JsonUtils.extractStringArgument(params, "infobaseUuid"); //$NON-NLS-1$
        if (confirm == null || !confirm.booleanValue())
        {
            return ToolResult.error("recover_stuck_merge force-clears the 'flow active' flag for an infobase so a " //$NON-NLS-1$
                + "stuck update (left by an interrupted operation) stops blocking every update - WITHOUT an EDT " //$NON-NLS-1$
                + "restart. DANGER: it cannot tell an abandoned flow from a genuine one still running - it will " //$NON-NLS-1$
                + "ALSO clear a real in-progress merge/update. Use ONLY when you are certain no update is actually " //$NON-NLS-1$
                + "running in EDT. Run diagnose_stuck_locks first, then re-run with confirm=true.").toJson(); //$NON-NLS-1$
        }
        if (infobaseUuid == null || infobaseUuid.isEmpty())
        {
            return ToolResult.error(TextSuggest.missingParam("infobaseUuid", //$NON-NLS-1$
                "sync_control operation=recover_stuck_merge infobaseUuid=<from diagnose_stuck_locks> confirm=true")).toJson(); //$NON-NLS-1$
        }
        UUID ibUuid = SyncBaseline.parseInfobaseUuid(infobaseUuid);
        if (ibUuid == null)
        {
            return ToolResult.error("infobaseUuid is not a valid UUID: '" + infobaseUuid + "'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        try
        {
            IInfobaseSynchronizationStateManager mgr =
                ServiceAccess.get(IInfobaseSynchronizationStateManager.class);
            if (mgr == null)
            {
                return ToolResult.error("Infobase synchronization state manager unavailable on this EDT build.").toJson(); //$NON-NLS-1$
            }
            Object delegate = mgr.getClass().getMethod("getDelegate").invoke(mgr); //$NON-NLS-1$
            if (delegate == null)
            {
                return ToolResult.error("Synchronization state delegate unavailable.").toJson(); //$NON-NLS-1$
            }
            Field lockStatesField = SyncBaseline.findField(delegate.getClass(), "infobaseLockStates"); //$NON-NLS-1$
            if (lockStatesField == null)
            {
                return ToolResult.error("infobaseLockStates field not found on this EDT build.").toJson(); //$NON-NLS-1$
            }
            lockStatesField.setAccessible(true);
            Object value = lockStatesField.get(delegate);
            if (!(value instanceof Map))
            {
                return ToolResult.error("infobaseLockStates is present but not a Map on this EDT build - the " //$NON-NLS-1$
                    + "stuck-merge mechanism differs here; cannot recover safely.").toJson(); //$NON-NLS-1$
            }
            Object holder = null;
            for (Map.Entry<?, ?> e : ((Map<?, ?>)value).entrySet())
            {
                if (SyncBaseline.matchesUuid(ibUuid, e.getKey()))
                {
                    holder = e.getValue();
                    break;
                }
            }
            if (holder == null)
            {
                return ToolResult.success()
                    .put("operation", "recover_stuck_merge") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("infobaseUuid", infobaseUuid) //$NON-NLS-1$
                    .put("wasStuck", false) //$NON-NLS-1$
                    .put("cleared", false) //$NON-NLS-1$
                    .put("note", "No lock holder for this infobase in the current EDT session - nothing was " //$NON-NLS-1$
                        + "stuck (the holder only exists after a flow touched the infobase this session).") //$NON-NLS-1$
                    .toJson();
            }
            Field projectField = SyncBaseline.findField(holder.getClass(), "project"); //$NON-NLS-1$
            if (projectField == null)
            {
                return ToolResult.error("The lock holder has no 'project' field - EDT internal shape differs here.").toJson(); //$NON-NLS-1$
            }
            projectField.setAccessible(true);
            // Serialize the read-check-write against EDT's own flow mutators, which all run inside
            // synchronized(delegate.lock). Best-effort: if the lock field isn't found, fall back to
            // synchronizing on the field object (a single reference write is atomic regardless).
            Object lockObj = readFieldValue(delegate, "lock"); //$NON-NLS-1$
            String beforeProject;
            boolean wasStuck;
            synchronized (lockObj != null ? lockObj : projectField)
            {
                Object before = projectField.get(holder);
                // Type-check (defense-in-depth on this security-classified path): only treat a real
                // IProject as a stuck flag; anything else is left untouched.
                wasStuck = before instanceof IProject;
                beforeProject = wasStuck ? ((IProject)before).getName() : null;
                if (wasStuck)
                {
                    projectField.set(holder, null);
                    Activator.logInfo("sync_control recover_stuck_merge: cleared flow-active flag for infobase " //$NON-NLS-1$
                        + infobaseUuid + " (was held by project " + beforeProject + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            // Forward-compat safety net: confirm via the platform's own public isFlowActive read.
            Boolean stillActive = probeFlowActive(mgr, ibUuid);
            boolean mismatch = wasStuck && beforeProject != null && !beforeProject.equals(project.getName());
            ToolResult tr = ToolResult.success()
                .put("operation", "recover_stuck_merge") //$NON-NLS-1$ //$NON-NLS-2$
                .put("infobaseUuid", infobaseUuid) //$NON-NLS-1$
                .put("wasStuck", wasStuck) //$NON-NLS-1$
                .put("cleared", wasStuck) //$NON-NLS-1$
                .put("previouslyHeldBy", beforeProject); //$NON-NLS-1$
            if (stillActive != null)
            {
                tr.put("flowStillActiveAfter", stillActive.booleanValue()); //$NON-NLS-1$
            }
            if (mismatch)
            {
                tr.put("projectMismatch", true); //$NON-NLS-1$
                tr.put("note", "WARNING: the flag was held by project '" + beforeProject //$NON-NLS-1$
                    + "', NOT the projectName '" + project.getName() + "' you passed - it was still cleared. " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Confirm you meant to clear THIS infobase; if that other project had a real merge running, " //$NON-NLS-1$
                    + "it was just aborted."); //$NON-NLS-1$
            }
            else
            {
                tr.put("note", wasStuck //$NON-NLS-1$
                    ? "Cleared the stuck flow-active flag - updates to this infobase should proceed now. If a real " //$NON-NLS-1$
                        + "update was in fact still running, its result plus its in-memory flow state and on-disk " //$NON-NLS-1$
                        + "temp dir are orphaned until the next EDT restart; re-check with status." //$NON-NLS-1$
                    : "The flag was already clear (project=null) - nothing to recover, no-op."); //$NON-NLS-1$
            }
            return tr.toJson();
        }
        catch (Exception e)
        {
            Activator.logError("sync_control recover_stuck_merge failed for infobase " + infobaseUuid, e); //$NON-NLS-1$
            return ToolResult.error("recover_stuck_merge failed: " + TextSuggest.safeMessage(e)).toJson(); //$NON-NLS-1$
        }
    }

    /** Reflectively reads a field's value from {@code target}, or {@code null} on any failure. */
    private static Object readFieldValue(Object target, String fieldName)
    {
        try
        {
            Field f = SyncBaseline.findField(target.getClass(), fieldName);
            if (f == null)
            {
                return null;
            }
            f.setAccessible(true);
            return f.get(target);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * The platform's own public authoritative "is this infobase's sync flow active" read
     * ({@link IInfobaseSynchronizationStateManager#isFlowActive}), or {@code null} if unavailable.
     * Used only as a post-clear safety net, never to gate the clear.
     */
    private static Boolean probeFlowActive(IInfobaseSynchronizationStateManager mgr, UUID ibUuid)
    {
        try
        {
            InfobaseReference ref = ModelFactory.eINSTANCE.createInfobaseReference();
            ref.setUuid(ibUuid);
            ref.setName("recover_stuck_merge"); //$NON-NLS-1$
            return Boolean.valueOf(mgr.isFlowActive(ref));
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    // ---- mark_synchronized (re-sign baseline = current project, zero delta, no push) ---

    /**
     * Marks the current project state as fully synchronized with the infobase via EDT's own
     * {@code InfobaseSynchronizationStateManagerDelegate.forceEdtSynchronization(InfobaseReference, IProject)}
     * (reached through the public {@code getDelegate()}). That updates the IN-MEMORY sync holder to the
     * current effective signatures, writes the baseline through EDT's official writer, and clears the sync
     * timestamp - so the equality check ({@code isProjectDirty} -> {@code checkAndUpdateSynchronizationState},
     * which compares the current {@code getEffectiveResourceMetadata} against the in-memory holder state)
     * recomputes to EQUAL immediately, no restart. Hand-writing the baseline file directly (the previous
     * approach) produced an index.idx that EDT's own reader did not reconcile into the holder, so the state
     * stayed NOT_EQUAL even after a restart - the whole reason this now goes through the EDT API. Nothing is
     * pushed to the infobase. DANGEROUS: it tells EDT the whole current project already equals the IB - any
     * real un-pushed difference is silently forgotten. Only on explicit user command + confirm=true. Future
     * edits are tracked normally from this point.
     *
     * <p>An infobase this project is bound to but which has no baseline yet (registered through
     * {@code register_infobase}, never updated) is marked the same way: there is nothing to re-sign, and the
     * call is what creates the baseline. Because the state such a store reads is {@code UNDEFINED}, the
     * configuration id the delegate writes is empty, which {@code UpdateInfobaseFlow.start()} would read as a
     * foreign configuration and answer with a full reload - so the project's own configuration id is stamped
     * onto the fresh baseline afterwards through the same delegate
     * ({@code forceConfigurationUUID(InfobaseReference, IProject, UUID)}).
     *
     * <p>Every mark requires the infobase to be among the project's applications, checked before
     * the baseline's path is resolved and whatever the baseline records - a recorded configuration
     * id matching the project does not make a foreign infobase this project's. One that is not is
     * refused with the list of the ones that are.</p>
     *
     * <p>A baseline that exists and records an EMPTY configuration id is treated as that same fresh case
     * rather than as one belonging to another configuration: it is the state a failed stamp leaves behind,
     * and refusing it would turn down the only call that repairs it. Every mark re-stamps such a baseline.
     * A stamp that fails is answered as a failed call carrying what the baseline now records and the repair
     * - the baseline itself exists by then and cannot be un-written.</p>
     */
    private String doMarkSynchronized(IProject project, Map<String, String> params)
    {
        Boolean confirm = JsonUtils.extractBooleanArgumentNullable(params, "confirm"); //$NON-NLS-1$
        String infobaseUuid = JsonUtils.extractStringArgument(params, "infobaseUuid"); //$NON-NLS-1$

        if (confirm == null || !confirm.booleanValue())
        {
            return ToolResult.error("mark_synchronized rewrites the ENTIRE baseline so EDT treats the current project " //$NON-NLS-1$
                + "state as fully synchronized with the infobase WITHOUT pushing anything. Any real difference between " //$NON-NLS-1$
                + "the project and the IB is then silently dropped (EDT will not push it). Use ONLY when you are certain " //$NON-NLS-1$
                + "the project matches the IB (e.g. just imported, on support, unchanged). Re-run with confirm=true.").toJson(); //$NON-NLS-1$
        }
        if (infobaseUuid == null || infobaseUuid.isEmpty())
        {
            return ToolResult.error(TextSuggest.missingParam("infobaseUuid", //$NON-NLS-1$
                "sync_control operation=mark_synchronized projectName=" + project.getName() //$NON-NLS-1$
                    + " infobaseUuid=<matchedBaseline from status> confirm=true")).toJson(); //$NON-NLS-1$
        }
        UUID ibUuid = SyncBaseline.parseInfobaseUuid(infobaseUuid);
        if (ibUuid == null)
        {
            return ToolResult.error("infobaseUuid is not a valid UUID: '" + infobaseUuid + "'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String liveUuid = readConfigurationUuid(project);
        if (liveUuid == null)
        {
            return ToolResult.error("Could not read the project's Configuration UUID " //$NON-NLS-1$
                + "(src/Configuration/Configuration.mdo) - is this a configuration (not extension) project?").toJson(); //$NON-NLS-1$
        }
        try
        {
            UUID.fromString(liveUuid); // validate the project's Configuration UUID is well-formed
        }
        catch (IllegalArgumentException e)
        {
            return ToolResult.error("The project's Configuration UUID is not a parseable UUID: '" //$NON-NLS-1$
                + liveUuid + "'.").toJson(); //$NON-NLS-1$
        }
        // The binding is checked on EVERY mark, whatever the baseline records: a baseline whose
        // configuration id matches the project is not thereby this project's to rewrite - an
        // infobase bound only to another workspace's project would otherwise get a baseline of
        // this project written into the store.
        String unbound = refuseUnboundInfobase(project, liveUuid, infobaseUuid.trim());
        if (unbound != null)
        {
            return ToolResult.error(unbound).toJson();
        }
        Path idx = SyncBaseline.indexOf(project, infobaseUuid.trim());
        IndexInfo before = idx.toFile().isFile() ? parseIndexIdx(idx) : null;
        // An infobase registered in EDT but not yet updated from this project is one of its
        // applications with no baseline: index.idx is written by the first update, there is
        // nothing to re-sign, and forceEdtSynchronization is what creates one.
        //
        // A baseline that exists but records NO configuration id is the same case, not the
        // "different configuration" one: that is the state forceEdtSynchronization leaves behind
        // when a stamp failed, and reading it as a foreign configuration would refuse the only
        // call that can repair it. Such a baseline is stamped again on every mark_synchronized.
        boolean freshBinding = before == null || before.configurationUuid.isEmpty();
        if (!freshBinding && !liveUuid.equals(before.configurationUuid))
        {
            return ToolResult.error("The baseline at infobase " + infobaseUuid + " is for a different configuration (" //$NON-NLS-1$ //$NON-NLS-2$
                + before.configurationUuid + " vs project " + liveUuid //$NON-NLS-1$
                + "). Use the matchedBaseline infobaseUuid from operation=status.").toJson(); //$NON-NLS-1$
        }

        try
        {
            // Mark synchronized through EDT's OWN sync state manager. Hand-writing index.idx
            // (the previous approach) produced a baseline whose in-memory holder EDT never
            // reconciled to the current signatures, so getEqualityState stayed NOT_EQUAL even
            // after a full restart (root cause: 'identical on disk yet still NOT_EQUAL').
            // forceEdtSynchronization updates the in-memory holder to the current effective
            // signatures, writes the baseline via EDT's official writer, and clears the sync
            // timestamp - the equality check then recomputes to EQUAL immediately, no restart.
            InfobaseReference ref = ModelFactory.eINSTANCE.createInfobaseReference();
            ref.setUuid(ibUuid);
            ref.setName(project.getName());

            IInfobaseSynchronizationStateManager stateMgr = syncStateManager();
            if (stateMgr == null)
            {
                return ToolResult.error("IInfobaseSynchronizationStateManager is not available in this EDT " //$NON-NLS-1$
                    + "runtime - mark_synchronized must run inside EDT.").toJson(); //$NON-NLS-1$
            }
            Object delegate = syncDelegate(stateMgr);
            if (delegate == null)
            {
                return ToolResult.error("The EDT sync state delegate is unavailable on this runtime.").toJson(); //$NON-NLS-1$
            }
            java.lang.reflect.Method forceSync;
            try
            {
                forceSync = delegate.getClass().getMethod("forceEdtSynchronization", //$NON-NLS-1$
                    InfobaseReference.class, IProject.class);
            }
            catch (NoSuchMethodException e)
            {
                return ToolResult.error("forceEdtSynchronization(InfobaseReference, IProject) is not present on the " //$NON-NLS-1$
                    + "EDT sync delegate - incompatible EDT runtime.").toJson(); //$NON-NLS-1$
            }
            forceSync.setAccessible(true);
            forceSync.invoke(delegate, ref, project);

            // forceEdtSynchronization fills the holder with the current signatures and writes the
            // baseline, but a store that held nothing starts from InfobaseSyncState.UNDEFINED, so
            // the configuration id it writes is empty. UpdateInfobaseFlow.start() compares that
            // field as a string and reads an empty one as a foreign configuration, which is a FULL
            // reload again. Stamping the project's own id is what leaves the fresh binding
            // genuinely synchronized.
            boolean uuidStamped = false;
            String stampError = null;
            if (freshBinding)
            {
                try
                {
                    java.lang.reflect.Method forceConfigUuid = delegate.getClass().getMethod( //$NON-NLS-1$
                        "forceConfigurationUUID", InfobaseReference.class, IProject.class, UUID.class); //$NON-NLS-1$
                    forceConfigUuid.setAccessible(true);
                    forceConfigUuid.invoke(delegate, ref, project, UUID.fromString(liveUuid));
                    uuidStamped = true;
                }
                catch (Exception e)
                {
                    // The holder was already rewritten by the call above and that effect cannot be
                    // rolled back, but the baseline is left in the state this stamp exists to
                    // prevent. Record the failure here and read the on-disk result below, so the
                    // refusal can say what was written; the call is then answered as failed.
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    stampError = TextSuggest.safeMessage(cause);
                    Activator.logWarning("sync_control mark_synchronized: forceConfigurationUUID failed for " //$NON-NLS-1$
                        + project.getName() + " infobase " + infobaseUuid + ": " + stampError); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }

            // The mutation has now applied. Verify in a SEPARATE try so that a failure to
            // READ the equality state back is not misreported as a failed mark (the change
            // already happened; a caller told "Failed" might wrongly retry or report no-op).
            InfobaseEqualityState equalityAfter = null;
            String verifyError = null;
            try
            {
                IInfobaseSynchronizationManager syncMgr =
                    ServiceAccess.get(IInfobaseSynchronizationManager.class);
                if (syncMgr != null)
                {
                    equalityAfter = syncMgr.getEqualityState(project, ref);
                }
            }
            catch (Exception ve)
            {
                verifyError = TextSuggest.safeMessage(ve);
            }
            boolean nowEqual = equalityAfter == InfobaseEqualityState.EQUAL;
            IndexInfo after = idx.toFile().isFile() ? parseIndexIdx(idx) : null;
            int newCount = after != null ? after.signatureCount : -1;
            int beforeCount = before != null ? before.signatureCount : -1;
            // What the baseline records is read back from disk, not inferred from the call having
            // returned: forceConfigurationUUID refreshing only the in-memory holder leaves the file
            // on disk unchanged, and that file is what the next update compares against.
            boolean idRecorded = after != null && liveUuid.equals(after.configurationUuid);

            Activator.logInfo("sync_control mark_synchronized (forceEdtSynchronization): " + project.getName() //$NON-NLS-1$
                + " infobase " + infobaseUuid + " signatures " + beforeCount + " -> " + newCount //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " equalityAfter=" + equalityAfter + " freshBinding=" + freshBinding //$NON-NLS-1$ //$NON-NLS-2$
                + " uuidStamped=" + uuidStamped + " idRecorded=" + idRecorded); //$NON-NLS-1$ //$NON-NLS-2$
            if (stampError != null)
            {
                // The mark itself applied, but the baseline is left exactly as the stamp exists to
                // prevent it: carrying the current signatures and no configuration id. Answered as
                // a failure, with the repair named - a success here reads as "the next update is
                // incremental" for a binding that will instead be carried whole.
                return ToolResult.error("forceEdtSynchronization wrote the baseline for infobase " + infobaseUuid //$NON-NLS-1$
                    + " (" + newCount + " signatures), but stamping the project's configuration id " + liveUuid //$NON-NLS-1$ //$NON-NLS-2$
                    + " on it failed (" + stampError + "). The baseline records " + recordedIdText(after, idx) //$NON-NLS-1$ //$NON-NLS-2$
                    + ", which the next update reads as a different configuration and answers with a FULL reload. " //$NON-NLS-1$
                    + "Re-run mark_synchronized for this infobase: a baseline without a configuration id is stamped " //$NON-NLS-1$
                    + "again, and the retry leaves it carrying the project's id. Alternatively run " //$NON-NLS-1$
                    + "operation=reseed_baseline infobaseUuid=" + infobaseUuid + " confirm=true, which rewrites the " //$NON-NLS-1$ //$NON-NLS-2$
                    + "recorded id on a populated baseline.") //$NON-NLS-1$
                    .put("operation", "mark_synchronized") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("projectName", project.getName()) //$NON-NLS-1$
                    .put("infobaseUuid", infobaseUuid) //$NON-NLS-1$
                    .put("baselineExisted", before != null) //$NON-NLS-1$
                    .put("previousSignatureCount", beforeCount) //$NON-NLS-1$
                    .put("newSignatureCount", newCount) //$NON-NLS-1$
                    .put("configurationUuidStamped", false) //$NON-NLS-1$ //$NON-NLS-2$
                    .put("stampError", stampError) //$NON-NLS-1$
                    .toJson();
            }
            String message;
            if (freshBinding)
            {
                message = (before == null
                    ? "This infobase had no baseline; forceEdtSynchronization created one from the current " //$NON-NLS-1$
                        + "project state (" + newCount + " signatures). " //$NON-NLS-1$ //$NON-NLS-2$
                    : "The baseline at this infobase recorded no configuration id; forceEdtSynchronization " //$NON-NLS-1$
                        + "rewrote it from the current project state (" + newCount + " signatures). ") //$NON-NLS-1$ //$NON-NLS-2$
                    + (idRecorded
                        ? "The project's configuration id " + liveUuid + " is recorded on it, so the next update " //$NON-NLS-1$ //$NON-NLS-2$
                            + "of this binding stays INCREMENTAL (no full reload). Nothing was pushed to the " //$NON-NLS-1$
                            + "infobase; if the project actually differed, those differences are NOT pushed." //$NON-NLS-1$
                        : "The baseline records " + recordedIdText(after, idx) + ", not the project's " + liveUuid //$NON-NLS-1$ //$NON-NLS-2$
                            + ", so the next update of this binding will still be a FULL configuration reload. " //$NON-NLS-1$
                            + "Re-run mark_synchronized for this infobase, or run operation=reseed_baseline " //$NON-NLS-1$
                            + "infobaseUuid=" + infobaseUuid + " confirm=true."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else if (nowEqual)
            {
                message = "Marked synchronized via EDT's official forceEdtSynchronization: the in-memory holder and " //$NON-NLS-1$
                    + "the on-disk baseline now match the current project state and getEqualityState reports EQUAL. " //$NON-NLS-1$
                    + "The next update is incremental/empty (no full reload, no 'update changed objects' dialog) - no " //$NON-NLS-1$
                    + "restart needed. Nothing was pushed to the infobase; if the project actually differed, those " //$NON-NLS-1$
                    + "differences are NOT pushed."; //$NON-NLS-1$
            }
            else if (verifyError != null)
            {
                message = "forceEdtSynchronization applied, but reading the equality state back failed (" //$NON-NLS-1$
                    + verifyError + ") - the mark most likely succeeded; run operation=diagnose to confirm."; //$NON-NLS-1$
            }
            else
            {
                message = "forceEdtSynchronization ran but getEqualityState is still " + equalityAfter //$NON-NLS-1$
                    + ". If the infobase is not connected in this EDT session, connect it and retry; otherwise the " //$NON-NLS-1$
                    + "project genuinely differs from the infobase."; //$NON-NLS-1$
            }
            ToolResult ok = ToolResult.success()
                .put("operation", "mark_synchronized") //$NON-NLS-1$ //$NON-NLS-2$
                .put("projectName", project.getName()) //$NON-NLS-1$
                .put("infobaseUuid", infobaseUuid) //$NON-NLS-1$
                .put("baselineExisted", before != null) //$NON-NLS-1$
                .put("previousSignatureCount", beforeCount) //$NON-NLS-1$
                .put("newSignatureCount", newCount) //$NON-NLS-1$
                .put("method", "forceEdtSynchronization") //$NON-NLS-1$ //$NON-NLS-2$
                .put("equalityStateAfter", String.valueOf(equalityAfter)) //$NON-NLS-1$
                .put("nowEqual", nowEqual) //$NON-NLS-1$
                .put("message", message); //$NON-NLS-1$
            if (freshBinding)
            {
                // The outcome, not the call: a stamp that returned while the file on disk still
                // records something else has not stamped this baseline.
                ok.put("configurationUuidStamped", idRecorded); //$NON-NLS-1$
            }
            if (verifyError != null)
            {
                ok.put("verifyError", verifyError); //$NON-NLS-1$
            }
            return ok.toJson();
        }
        catch (java.lang.reflect.InvocationTargetException e)
        {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Activator.logError("sync_control mark_synchronized (forceEdtSynchronization) failed for " //$NON-NLS-1$
                + project.getName(), cause);
            return ToolResult.error("Failed to mark synchronized via forceEdtSynchronization for infobase " //$NON-NLS-1$
                + infobaseUuid + ": " + TextSuggest.safeMessage(cause)).toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("sync_control mark_synchronized failed for " + project.getName(), e); //$NON-NLS-1$
            return ToolResult.error("Failed to mark synchronized for infobase " + infobaseUuid + ": " //$NON-NLS-1$ //$NON-NLS-2$
                + TextSuggest.safeMessage(e)).toJson();
        }
    }

    /**
     * The EDT-authoritative per-resource signatures for the project, exactly as the infobase synchronization
     * equality check reads them ({@link IResourceStoreManager#getEffectiveResourceMetadata}, the source the
     * delegate's {@code checkAndUpdateSynchronizationState} compares against). This is NOT a raw-file SHA-256 -
     * EDT normalizes some resources, so a raw hash differs for a fraction of files, which is why an external
     * re-sign never cleared the pre-launch dialog. Keyed identically to the on-disk baseline. Returns
     * {@code null} if the services or the project's IDtProject are unavailable (must run inside EDT, loaded).
     */
    private static Map<String, byte[]> computeEdtSignatures(IProject project)
    {
        Activator activator = Activator.getDefault();
        if (activator == null)
        {
            return null;
        }
        IDtProjectManager projectManager = activator.getDtProjectManager();
        IResourceStoreManager storeManager = activator.getResourceStoreManager();
        if (projectManager == null || storeManager == null)
        {
            return null;
        }
        IDtProject dtProject = projectManager.getDtProject(project);
        if (dtProject == null)
        {
            return null;
        }
        Map<String, EdtResourceMetadata> metadata = storeManager.getEffectiveResourceMetadata(dtProject);
        if (metadata == null)
        {
            return null;
        }
        // The on-disk baseline (index.idx) and EDT's equality check both key by resource path and compare the
        // signature bytes only (Arrays.equals on EdtResourceMetadata.getSignature; the per-resource UUID is not
        // compared), so flatten to path -> signature. An empty signature is written as a zero-length array.
        Map<String, byte[]> signatures = new LinkedHashMap<>(metadata.size());
        for (Map.Entry<String, EdtResourceMetadata> entry : metadata.entrySet())
        {
            EdtResourceMetadata meta = entry.getValue();
            byte[] signature = meta != null ? meta.getSignature() : null;
            signatures.put(entry.getKey(), signature != null ? signature : new byte[0]);
        }
        return signatures;
    }

    // ---- helpers -----------------------------------------------------------------------

    /**
     * Reads the root {@code uuid} attribute of {@code src/Configuration/Configuration.mdo} -
     * the same UUID {@code UpdateInfobaseFlow.start()} reads from the BM model and compares
     * against the sync baseline. Returns {@code null} if not found (e.g. extension project).
     */
    private String readConfigurationUuid(IProject project)
    {
        return SyncBaseline.configurationUuid(project);
    }

    /**
     * Parses an {@code index.idx} baseline file. Binary format (big-endian, Java DataOutput):
     * long timestamp, int signatureCount, then per signature (UTF key, int length, length
     * bytes), then UTF generationId, UTF configurationUUID. EDT 2026 writes a versioned layout:
     * UTF version {@code "1.0"} first, and after each signature a boolean that says whether a
     * per-resource UUID (UTF) follows. Returns {@code null} if unreadable.
     */
    private IndexInfo parseIndexIdx(Path file)
    {
        try
        {
            SyncBaseline.Index index = SyncBaseline.read(file);
            return new IndexInfo(index.timestamp, index.keys.size(), index.generationId, index.configurationUuid);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static final class IndexInfo
    {
        final long timestamp;
        final int signatureCount;
        final String generationId;
        final String configurationUuid;

        IndexInfo(long timestamp, int signatureCount, String generationId, String configurationUuid)
        {
            this.timestamp = timestamp;
            this.signatureCount = signatureCount;
            this.generationId = generationId;
            this.configurationUuid = configurationUuid;
        }
    }
}
