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
import java.util.stream.Collectors;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.resource.EdtResourceMetadata;
import com._1c.g5.v8.dt.core.resource.IResourceStoreManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseEqualityState;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationStateManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.ModelFactory;
import com._1c.g5.wiring.ServiceAccess;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BmCommonModuleGuards;
import ru.aiedt.mcp.server.support.DumpInfoRebuilder;
import ru.aiedt.mcp.server.support.ProjectResolver;
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
        return "Back-compat alias of `infobase_admin` `operation=sync_control` (pass the inner action in " //$NON-NLS-1$
            + "`syncOperation`); prefer the facade for new prompts. " //$NON-NLS-1$
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
                + "list_support_snapshots | release_support_snapshot | rebuild_dump_info (required)", true) //$NON-NLS-1$
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
                + "naming the infobase whose stored file is rebuilt. Required when the project has " //$NON-NLS-1$
                + "several applications (see infobase_admin operation=get_applications); resolved " //$NON-NLS-1$
                + "otherwise.") //$NON-NLS-1$
            .stringProperty("timeoutSeconds", "For operation=rebuild_dump_info: how long each " //$NON-NLS-1$ //$NON-NLS-2$
                + "Designer run is waited for, 60-3600 (default 600). Past it the run is abandoned, " //$NON-NLS-1$
                + "the stored file is not touched and the infobase is reconnected.") //$NON-NLS-1$
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
            default:
                return ToolResult.error("Unknown operation '" + operation //$NON-NLS-1$
                    + "'. Valid: status, diagnose, diagnose_delta, suppress, reseed_baseline, mark_synchronized, " //$NON-NLS-1$
                    + "diagnose_stuck_locks, recover_stuck_merge, list_support_snapshots, " //$NON-NLS-1$
                    + "release_support_snapshot, rebuild_dump_info.").toJson(); //$NON-NLS-1$
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
        UUID ibUuid;
        try
        {
            ibUuid = UUID.fromString(infobaseUuid.trim());
        }
        catch (IllegalArgumentException e)
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
        UUID ibUuid;
        try
        {
            ibUuid = UUID.fromString(infobaseUuid.trim());
        }
        catch (IllegalArgumentException ex)
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
     * ({@code forceConfigurationUUID(InfobaseReference, IProject, UUID)}). An infobase that is NOT among the
     * project's applications is refused with the list of the ones that are.
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
        UUID ibUuid;
        try
        {
            ibUuid = UUID.fromString(infobaseUuid.trim());
        }
        catch (IllegalArgumentException e)
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
        Path idx = SyncBaseline.indexOf(project, infobaseUuid.trim());
        IndexInfo before = idx.toFile().isFile() ? parseIndexIdx(idx) : null;
        // An infobase registered in EDT but not yet updated from this project is one of its
        // applications with no baseline: index.idx is written by the first update, there is
        // nothing to re-sign, and forceEdtSynchronization is what creates one. An infobase that is
        // NOT an application of this project is a different answer - nothing can be stamped for a
        // binding that does not exist, and the caller needs the list of the ones that do.
        //
        // A baseline that exists but records NO configuration id is the same case, not the
        // "different configuration" one: that is the state forceEdtSynchronization leaves behind
        // when a stamp failed, and reading it as a foreign configuration would refuse the only
        // call that can repair it. Such a baseline is stamped again on every mark_synchronized.
        boolean freshBinding = false;
        if (before == null || before.configurationUuid.isEmpty())
        {
            List<String> applications = bindingUuids(project, liveUuid);
            if (applications == null || !applications.contains(infobaseUuid.trim()))
            {
                // Whichever of the two states this baseline is in, it is still re-signed only for a
                // binding of this project - the application list is what says the infobase is one.
                String opening = before == null
                    ? "No baseline at infobase " + infobaseUuid + " (index.idx missing)" //$NON-NLS-1$ //$NON-NLS-2$
                    : "The baseline at infobase " + infobaseUuid + " records no configuration id"; //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.error(opening
                    + ", and it is not one of the project's applications (" //$NON-NLS-1$
                    + (applications == null ? "the application list could not be read in this runtime" //$NON-NLS-1$
                        : project.getName() + ": " + (applications.isEmpty() ? "none" : String.join(", ", applications))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    + "). A baseline is re-signed only for an infobase this project is bound to. To bind " //$NON-NLS-1$
                    + "this one, run update_database fullUpdate=true: it carries the whole configuration " //$NON-NLS-1$
                    + "into the infobase and writes the baseline itself.").toJson(); //$NON-NLS-1$
            }
            freshBinding = true;
        }
        else if (!liveUuid.equals(before.configurationUuid))
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
