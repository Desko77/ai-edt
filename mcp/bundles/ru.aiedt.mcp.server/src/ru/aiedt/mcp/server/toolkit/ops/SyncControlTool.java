/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.SupportSnapshotStore;
import ru.aiedt.mcp.server.support.TextSuggest;

/**
 * Inspect and control EDT&lt;-&gt;infobase synchronization (the engine that decides
 * full-config-reload vs incremental on "Update infobase").
 *
 * <p>EDT keeps a per-infobase baseline at
 * {@code %APPDATA%\.1cedt\ib-sync\ss\<infobaseUuid>\index.idx} holding the configuration
 * UUID recorded at the last successful sync. {@code UpdateInfobaseFlow.start()} compares
 * the project's live {@code Configuration} UUID to that baseline UUID; a mismatch (or a
 * missing/empty baseline) forces a FULL reload of the whole configuration - slow on large
 * configs (ERP). See {@code operation=status}.
 *
 * <ul>
 * <li>{@code status} - READ-ONLY. Reads the project's {@code Configuration.mdo} UUID and the
 * sync-store baselines, then predicts whether the next update will be FULL or INCREMENTAL and
 * explains why (e.g. "no matching baseline" / "indexes diverged").</li>
 * <li>{@code suppress} - turns synchronization on/off for a project via the platform
 * {@link IInfobaseSynchronizationManager}. Suppressing the on-support main configuration makes
 * an "Update infobase" push ONLY the extensions (the main config is skipped), avoiding a full
 * reload. Reversible; the flag is in-memory (per EDT session).</li>
 * </ul>
 */
public class SyncControlTool implements IMcpTool
{
    public static final String NAME = "sync_control"; //$NON-NLS-1$

    private static final Pattern UUID_ATTR =
        Pattern.compile("uuid=\"([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\""); //$NON-NLS-1$

    private static final int MAX_LISTED_BASELINES = 25;

    /** Bytes read from the head of Configuration.mdo to find the root uuid attribute. */
    private static final int MDO_HEAD_BYTES = 8192;

    /** Sanity cap on a per-signature byte length read from a (possibly corrupt) index.idx. */
    private static final int MAX_SIGNATURE_BYTES = 10_000_000;

    /** Sanity cap on the signature COUNT (real configs seen up to ~100k; this only rejects corruption). */
    private static final int MAX_SIGNATURE_COUNT = 5_000_000;

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
            + "EDT sync baseline (%APPDATA%\\.1cedt\\ib-sync\\ss) - diagnoses 'indexes diverged / will be full'. " //$NON-NLS-1$
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
            + "reseed_baseline, mark_synchronized and recover_stuck_merge are DANGEROUS - only on explicit user request " //$NON-NLS-1$
            + "and only when you are CERTAIN of the state (project KNOWN to match the infobase / no update really " //$NON-NLS-1$
            + "running); otherwise EDT silently drops real changes or a genuine merge is aborted. NEVER call autonomously."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", "status | diagnose | diagnose_delta | suppress | reseed_baseline | " //$NON-NLS-1$ //$NON-NLS-2$
                + "mark_synchronized | diagnose_stuck_locks | recover_stuck_merge | " //$NON-NLS-1$
                + "list_support_snapshots | release_support_snapshot (required)", true) //$NON-NLS-1$
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
            .booleanProperty("confirm", "For operation=reseed_baseline / mark_synchronized / recover_stuck_merge: " //$NON-NLS-1$ //$NON-NLS-2$
                + "must be true to proceed. Confirms you are CERTAIN of the state (project matches the infobase, or " //$NON-NLS-1$
                + "no update is really running) - otherwise EDT silently drops real changes or aborts a genuine merge.") //$NON-NLS-1$
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
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
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
            default:
                return ToolResult.error("Unknown operation '" + operation //$NON-NLS-1$
                    + "'. Valid: status, diagnose, diagnose_delta, suppress, reseed_baseline, mark_synchronized, " //$NON-NLS-1$
                    + "diagnose_stuck_locks, recover_stuck_merge, list_support_snapshots, " //$NON-NLS-1$
                    + "release_support_snapshot.").toJson(); //$NON-NLS-1$
        }
    }

    // ---- status (read-only diagnosis) --------------------------------------------------

    private String doStatus(IProject project)
    {
        String liveUuid = readConfigurationUuid(project);
        Path ssRoot = syncStoreSsPath();

        ToolResult res = ToolResult.success()
            .put("operation", "status") //$NON-NLS-1$ //$NON-NLS-2$
            .put("projectName", project.getName()) //$NON-NLS-1$
            .put("syncStorePath", ssRoot.toString()); //$NON-NLS-1$

        if (liveUuid == null)
        {
            res.put("liveConfigurationUuid", "(not found)"); //$NON-NLS-1$ //$NON-NLS-2$
            res.put("prediction", "UNKNOWN"); //$NON-NLS-1$ //$NON-NLS-2$
            res.put("summary", "Could not read the project's Configuration UUID " //$NON-NLS-1$
                + "(src/Configuration/Configuration.mdo). Is this a configuration (not extension) project?"); //$NON-NLS-1$
            return res.toJson();
        }
        res.put("liveConfigurationUuid", liveUuid); //$NON-NLS-1$

        if (!ssRoot.toFile().isDirectory())
        {
            res.put("prediction", "FULL"); //$NON-NLS-1$ //$NON-NLS-2$
            res.put("willTriggerFullReload", true); //$NON-NLS-1$
            res.put("summary", "No EDT sync store found at " + ssRoot //$NON-NLS-1$
                + " - there is no baseline, so the next update will be a FULL configuration reload."); //$NON-NLS-1$
            return res.toJson();
        }

        List<Map<String, Object>> all = new ArrayList<>();
        Map<String, Object> matched = null;
        File[] ibDirs = ssRoot.toFile().listFiles(File::isDirectory);
        if (ibDirs != null)
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
        res.put("prediction", willBeFull ? "FULL" : "INCREMENTAL"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        res.put("willTriggerFullReload", willBeFull); //$NON-NLS-1$
        res.put("baselines", all); //$NON-NLS-1$
        if (matched != null)
        {
            res.put("matchedBaseline", matched); //$NON-NLS-1$
            res.put("summary", "A baseline matching this configuration UUID exists (infobase " //$NON-NLS-1$
                + matched.get("infobaseUuid") + ", " + matched.get("signatureCount") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " signatures). The next update should be INCREMENTAL (only changed files pushed)."); //$NON-NLS-1$
        }
        else
        {
            res.put("summary", "No baseline with a matching configuration UUID (" + liveUuid //$NON-NLS-1$
                + ") was found among " + all.size() + " stored infobase baseline(s). The next update will be a " //$NON-NLS-1$ //$NON-NLS-2$
                + "FULL configuration reload. Causes: no prior successful EDT sync for this infobase, the sync " //$NON-NLS-1$
                + "store was wiped (OneDrive/manual cleanup or a crashed sync flow), or the configuration " //$NON-NLS-1$
                + "identity differs (e.g. the infobase was changed via Designer/repository outside EDT)."); //$NON-NLS-1$
        }
        return res.toJson();
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
        Path ssRoot = syncStoreSsPath();
        File[] ibDirs = ssRoot.toFile().isDirectory() ? ssRoot.toFile().listFiles(File::isDirectory) : null;
        if (ibDirs != null)
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
        Path idx = syncStoreSsPath().resolve(infobaseUuid.trim()).resolve("index.idx"); //$NON-NLS-1$
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
        byte[] all = java.nio.file.Files.readAllBytes(file);
        boolean versioned = all.length >= 5 && all[0] == 0 && all[1] == 3
            && all[2] == '1' && all[3] == '.' && all[4] == '0';
        try (java.io.DataInputStream dis = new java.io.DataInputStream(new java.io.ByteArrayInputStream(all)))
        {
            if (versioned)
            {
                dis.readUTF(); // version "1.0"
            }
            dis.readLong(); // timestamp
            int count = dis.readInt();
            Map<String, byte[]> result = new LinkedHashMap<>();
            for (int i = 0; i < count; i++)
            {
                String key = dis.readUTF();
                int len = dis.readInt();
                if (len < 0 || len > 100000)
                {
                    throw new IOException("Unexpected signature length " + len + " - format mismatch."); //$NON-NLS-1$ //$NON-NLS-2$
                }
                byte[] sig = new byte[len];
                dis.readFully(sig);
                if (versioned && dis.readBoolean())
                {
                    dis.readUTF(); // per-resource UUID
                }
                result.put(key, sig);
            }
            return result;
        }
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
        Path idx = syncStoreSsPath().resolve(infobaseUuid.trim()).resolve("index.idx"); //$NON-NLS-1$
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
            String holderRefresh = refreshHolder(ibUuid);

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
        long timestamp;
        String generationId;
        List<String> keys = new ArrayList<>();
        List<byte[]> sigs = new ArrayList<>();
        try (DataInputStream dis = new DataInputStream(new FileInputStream(file.toFile())))
        {
            timestamp = dis.readLong();
            int count = dis.readInt();
            if (count < 0 || count > MAX_SIGNATURE_COUNT)
            {
                throw new IOException("index.idx signature count out of range: " + count); //$NON-NLS-1$
            }
            for (int i = 0; i < count; i++)
            {
                keys.add(dis.readUTF());
                int len = dis.readInt();
                if (len < 0 || len > MAX_SIGNATURE_BYTES)
                {
                    throw new IOException("index.idx signature length out of range: " + len); //$NON-NLS-1$
                }
                byte[] b = new byte[len];
                dis.readFully(b);
                sigs.add(b);
            }
            generationId = dis.readUTF();
            dis.readUTF(); // old configuration UUID (replaced below)
        }
        File tmp = new File(file.toFile().getAbsolutePath() + ".tmp"); //$NON-NLS-1$
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(tmp)))
        {
            dos.writeLong(timestamp);
            dos.writeInt(keys.size());
            for (int i = 0; i < keys.size(); i++)
            {
                dos.writeUTF(keys.get(i));
                dos.writeInt(sigs.get(i).length);
                dos.write(sigs.get(i));
            }
            dos.writeUTF(generationId);
            dos.writeUTF(newUuid);
        }
        Files.move(tmp.toPath(), file, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Drops EDT's cached in-memory synchronization holder for the infobase so the next equality check
     * reloads the (corrected) baseline from disk. EDT only (re)loads the holder when it is ABSENT
     * (updateInternalSyncStateIfNecessary), so removing it is what triggers the reload - a restart would
     * reload it too; this avoids the restart. 2026.1 keys the holders in a NESTED map
     * {@code projectInfobaseSyncStates: Map<projectName, Map<infobaseUuid, holder>>}; older EDT used a flat
     * {@code synchronizationStates: Map<infobaseUuid, holder>}. Both are handled. Never throws.
     */
    private static String refreshHolder(UUID ibUuid)
    {
        try
        {
            IInfobaseSynchronizationStateManager mgr = ServiceAccess.get(IInfobaseSynchronizationStateManager.class);
            if (mgr == null)
            {
                return "skipped (state manager unavailable)"; //$NON-NLS-1$
            }
            Object delegate = mgr.getClass().getMethod("getDelegate").invoke(mgr); //$NON-NLS-1$
            if (delegate == null)
            {
                return "skipped (delegate null)"; //$NON-NLS-1$
            }
            int dropped = dropNestedHolder(delegate, "projectInfobaseSyncStates", ibUuid) //$NON-NLS-1$
                + dropFlatHolder(delegate, "synchronizationStates", ibUuid); //$NON-NLS-1$
            return dropped > 0 ? "ok (dropped " + dropped + " cached holder(s), reloads from disk)" //$NON-NLS-1$ //$NON-NLS-2$
                : "ok (no cached holder for this infobase; disk baseline is authoritative)"; //$NON-NLS-1$
        }
        catch (Exception e)
        {
            return "skipped (" + TextSuggest.safeMessage(e) + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** Removes the infobase holder from a 2026.1 nested {@code Map<projectName, Map<uuid, holder>>}. */
    private static int dropNestedHolder(Object delegate, String fieldName, UUID ibUuid)
    {
        Field field = findField(delegate.getClass(), fieldName);
        if (field == null)
        {
            return 0;
        }
        try
        {
            field.setAccessible(true);
            Object value = field.get(delegate);
            if (!(value instanceof Map))
            {
                return 0;
            }
            int dropped = 0;
            for (Object inner : ((Map<?, ?>)value).values())
            {
                if (inner instanceof Map && ((Map<?, ?>)inner).keySet().removeIf(k -> matchesUuid(ibUuid, k)))
                {
                    dropped++;
                }
            }
            return dropped;
        }
        catch (Exception e)
        {
            return 0;
        }
    }

    /** Removes the infobase holder from an older flat {@code Map<uuid, holder>}. */
    private static int dropFlatHolder(Object delegate, String fieldName, UUID ibUuid)
    {
        Field field = findField(delegate.getClass(), fieldName);
        if (field == null)
        {
            return 0;
        }
        try
        {
            field.setAccessible(true);
            Object value = field.get(delegate);
            if (!(value instanceof Map))
            {
                return 0;
            }
            return ((Map<?, ?>)value).keySet().removeIf(k -> matchesUuid(ibUuid, k)) ? 1 : 0;
        }
        catch (Exception e)
        {
            return 0;
        }
    }

    /** True if {@code key} is the infobase UUID, as a java.util.UUID or its string form. */
    private static boolean matchesUuid(UUID ibUuid, Object key)
    {
        return ibUuid.equals(key) || ibUuid.toString().equalsIgnoreCase(String.valueOf(key));
    }

    /** Finds a declared field by name, walking up the class hierarchy (it may live on a superclass). */
    private static Field findField(Class<?> type, String name)
    {
        for (Class<?> c = type; c != null; c = c.getSuperclass())
        {
            try
            {
                return c.getDeclaredField(name);
            }
            catch (NoSuchFieldException ignored)
            {
                // try the superclass
            }
        }
        return null;
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
     * Read-only: reports every infobase lock holder EDT has created THIS session and whether its
     * flow-active flag is stuck (project != null). Only infobases touched this session appear -
     * inherent to an in-memory-only map.
     */
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
            Field lockStatesField = findField(delegate.getClass(), "infobaseLockStates"); //$NON-NLS-1$
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
            Field pf = findField(holder.getClass(), "project"); //$NON-NLS-1$
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
            Field lockStatesField = findField(delegate.getClass(), "infobaseLockStates"); //$NON-NLS-1$
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
                if (matchesUuid(ibUuid, e.getKey()))
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
            Field projectField = findField(holder.getClass(), "project"); //$NON-NLS-1$
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
            Field f = findField(target.getClass(), fieldName);
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
        Path idx = syncStoreSsPath().resolve(infobaseUuid.trim()).resolve("index.idx"); //$NON-NLS-1$
        IndexInfo before = idx.toFile().isFile() ? parseIndexIdx(idx) : null;
        if (before == null)
        {
            return ToolResult.error("No baseline at infobase " + infobaseUuid //$NON-NLS-1$
                + " (index.idx missing). Run operation=status and use a matchedBaseline infobaseUuid.").toJson(); //$NON-NLS-1$
        }
        if (!liveUuid.equals(before.configurationUuid))
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

            IInfobaseSynchronizationStateManager stateMgr =
                ServiceAccess.get(IInfobaseSynchronizationStateManager.class);
            if (stateMgr == null)
            {
                return ToolResult.error("IInfobaseSynchronizationStateManager is not available in this EDT " //$NON-NLS-1$
                    + "runtime - mark_synchronized must run inside EDT.").toJson(); //$NON-NLS-1$
            }
            Object delegate = stateMgr.getClass().getMethod("getDelegate").invoke(stateMgr); //$NON-NLS-1$
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

            Activator.logInfo("sync_control mark_synchronized (forceEdtSynchronization): " + project.getName() //$NON-NLS-1$
                + " infobase " + infobaseUuid + " signatures " + before.signatureCount + " -> " + newCount //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " equalityAfter=" + equalityAfter); //$NON-NLS-1$
            String message;
            if (nowEqual)
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
                .put("previousSignatureCount", before.signatureCount) //$NON-NLS-1$
                .put("newSignatureCount", newCount) //$NON-NLS-1$
                .put("method", "forceEdtSynchronization") //$NON-NLS-1$ //$NON-NLS-2$
                .put("equalityStateAfter", String.valueOf(equalityAfter)) //$NON-NLS-1$
                .put("nowEqual", nowEqual) //$NON-NLS-1$
                .put("message", message); //$NON-NLS-1$
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
        if (project.getLocation() == null)
        {
            return null;
        }
        Path mdo = Paths.get(project.getLocation().toOSString(), "src", "Configuration", "Configuration.mdo"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (!mdo.toFile().isFile())
        {
            return null;
        }
        try
        {
            byte[] head = readHead(mdo, MDO_HEAD_BYTES);
            String text = new String(head, StandardCharsets.UTF_8);
            Matcher m = UUID_ATTR.matcher(text);
            if (m.find())
            {
                return m.group(1);
            }
        }
        catch (Exception e)
        {
            Activator.logError("sync_control: failed to read Configuration.mdo UUID", e); //$NON-NLS-1$
        }
        return null;
    }

    private static byte[] readHead(Path file, int max) throws java.io.IOException
    {
        try (java.io.InputStream in = Files.newInputStream(file))
        {
            byte[] buf = new byte[max];
            int total = 0;
            int n;
            while (total < max && (n = in.read(buf, total, max - total)) > 0)
            {
                total += n;
            }
            return total == max ? buf : java.util.Arrays.copyOf(buf, total);
        }
    }

    /**
     * Parses an {@code index.idx} baseline file. Binary format (big-endian, Java DataOutput):
     * long timestamp, int signatureCount, then per signature (UTF key, int length, length
     * bytes), then UTF generationId, UTF configurationUUID. Returns {@code null} if unreadable.
     */
    private IndexInfo parseIndexIdx(Path file)
    {
        try (DataInputStream dis = new DataInputStream(new FileInputStream(file.toFile())))
        {
            long timestamp = dis.readLong();
            int count = dis.readInt();
            if (count < 0 || count > MAX_SIGNATURE_COUNT)
            {
                return null;
            }
            for (int i = 0; i < count; i++)
            {
                dis.readUTF();
                int len = dis.readInt();
                if (len < 0 || len > MAX_SIGNATURE_BYTES)
                {
                    return null;
                }
                byte[] buf = new byte[len];
                dis.readFully(buf);
            }
            String generationId = dis.readUTF();
            String configurationUuid = dis.readUTF();
            return new IndexInfo(timestamp, count, generationId, configurationUuid);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static Path syncStoreSsPath()
    {
        String appData = System.getenv("APPDATA"); //$NON-NLS-1$
        Path base = (appData != null && !appData.isEmpty())
            ? Paths.get(appData)
            : Paths.get(System.getProperty("user.home")); //$NON-NLS-1$
        return base.resolve(".1cedt").resolve("ib-sync").resolve("ss"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
