/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.ApplicationUpdateType;
import com.e1c.g5.dt.applications.ExecutionContext;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.support.TimeoutArgs;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BmCommonModuleGuards;
import ru.aiedt.mcp.server.support.DebugSessionBook;
import ru.aiedt.mcp.server.support.BranchInfobaseBook;
import ru.aiedt.mcp.server.support.ErrorTags;
import ru.aiedt.mcp.server.support.GitBranch;
import ru.aiedt.mcp.server.support.InfobaseHolders;
import ru.aiedt.mcp.server.support.InfobaseIdentity;
import ru.aiedt.mcp.server.support.LaunchConfigAccess;
import ru.aiedt.mcp.server.support.MonopolyLock;
import ru.aiedt.mcp.server.support.PendingWorkRegistry;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.ProjectStateGuard;

/**
 * Pushes a project's configuration into its infobase - a full reload or just the changes - through
 * EDT's application manager.
 * <p>
 * The update can outlast an HTTP handler, so a slow one is handed to a worker and the caller gets a
 * runKey to poll with; a fast one returns in place. Targeting is by launch configuration name
 * (preferred, as it pins the project and application together) or by an explicit project +
 * application id pair. A {@code dryRun} call is a probe rather than a run: answered in place, it
 * reads only what cannot synchronize with the infobase and starts nothing.
 * </p>
 */
public class DatabaseUpdater implements IMcpTool
{
    public static final String NAME = "update_database"; //$NON-NLS-1$

    /**
     * The kind this tool stamps its pending entries with.
     * <p>
     * The UPDATE registry also carries pending {@code edit_metadata} calls (stamped
     * {@code edit_metadata}). A status read that cannot tell them apart would name a metadata
     * write as an update, and a key handed back would resume work the caller never started.
     * </p>
     */
    public static final String WORK_KIND = "update_database"; //$NON-NLS-1$

    private static final int MIN_TIMEOUT_SECONDS = 5;
    private static final int MAX_TIMEOUT_SECONDS = 120;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** Cap on how deep a cause-chain we walk to find the actionable root. */
    private static final int CAUSE_CHAIN_DEPTH = 20;
    /** How long to wait (per launch) for a freed client to actually die before the update runs. */
    private static final long FREE_CLIENT_WAIT_MS = 10_000L;
    private static final long FREE_CLIENT_POLL_MS = 200L;

    /**
     * How long to keep the infobase claimed while EDT finishes an update it reported as still
     * running.
     * <p>
     * Ten minutes: long enough for a restructure on a real configuration, short enough that a
     * claim never outlives the answer by a working day. Past it the claim is released and the
     * answer says so - a claim nobody can account for is worse than a race the caller was warned
     * about.
     * </p>
     */
    private static final long BEING_UPDATED_WAIT_MS = 600_000L;

    /** How often to ask whether the update has finished. */
    private static final long BEING_UPDATED_POLL_MS = 1_000L;

    @Override
    public String getName()
    {
        return NAME;
    }

    /**
     * Polls an update this tool started.
     *
     * @param domain the registry domain the key was found in
     * @param operation unused; a direct call names none, and the facade declares its own poll
     * @return {@code update_database} when the key is in the update registry, or {@code null}
     */
    @Override
    public String resumes(String domain, String operation)
    {
        return PendingWorkRegistry.UPDATE.domain().equals(domain) ? NAME : null;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `infobase_admin` `operation=update_database`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Push the current configuration into an application's infobase. " //$NON-NLS-1$
            + "Point it at the target either with launchConfigurationName (preferred; " //$NON-NLS-1$
            + "see list_configurations) or with projectName alone, optionally naming an applicationId. " //$NON-NLS-1$
            + "For an extension project - which has no infobase of its own - the infobase of the " //$NON-NLS-1$
            + "configuration it extends is updated, which is what carries the extension's code into it. " //$NON-NLS-1$
            + "Handles both a full update (complete reload) and an incremental update (changes only). " //$NON-NLS-1$
            + "A slow full / restructure run replies with a Pending status and a runKey instead of blocking - " //$NON-NLS-1$
            + "call this tool again passing that runKey to keep waiting (cancel=true plus the runKey stops tracking)."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("launchConfigurationName", //$NON-NLS-1$
                "Exact name of an existing EDT runtime-client launch configuration (preferred - obtain it via list_configurations)") //$NON-NLS-1$
            .stringProperty("projectName", "Name of the EDT project (required when launchConfigurationName is not supplied)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application identifier from get_applications. Optional: omitted, the project's default " //$NON-NLS-1$
                    + "application is used, and for an extension project - which has no infobase of its own - " //$NON-NLS-1$
                    + "the default of the configuration it extends. The response says which was updated.") //$NON-NLS-1$
            .booleanProperty("dryRun", //$NON-NLS-1$
                "Answer what an update would face and start nothing: the update state the " //$NON-NLS-1$
                    + "environment holds, whether an update is needed, and - unless refreshWorkspace " //$NON-NLS-1$
                    + "is off - what that refresh picked up. Readiness and the export validation an " //$NON-NLS-1$
                    + "update runs first are NOT checked and are reported as notCheckedInDryRun: " //$NON-NLS-1$
                    + "readiness reaches the infobase synchronization cycle through a thick client and " //$NON-NLS-1$
                    + "does not return while a thick-client session holds the infobase; the export " //$NON-NLS-1$
                    + "validation walks the whole project - diagnostics operation=validate_for_export " //$NON-NLS-1$
                    + "answers it. No run is recorded, no runKey is issued, and no infobase is " //$NON-NLS-1$
                    + "claimed.") //$NON-NLS-1$
            .booleanProperty("fullUpdate", "true triggers a full reload; false runs an incremental update instead (default: false)") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("autoRestructure", "Apply infobase restructuring automatically when it is required (default: true)") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("ignoreBranchBinding", "Update even when the branch this project is " //$NON-NLS-1$ //$NON-NLS-2$
                + "on is bound to a different application (see branch_infobase). Off by default: " //$NON-NLS-1$
                + "the binding exists to stop an update restructuring the wrong infobase after a " //$NON-NLS-1$
                + "branch switch, which cannot be undone.") //$NON-NLS-1$
            .booleanProperty("autoFreeClients", "Opt-in: before running the update, stop this project's own " //$NON-NLS-1$ //$NON-NLS-2$
                + "EDT-launched runtime-client sessions for this infobase, so an active client cannot keep " //$NON-NLS-1$
                + "the infobase locked and block the update. Only runtime-client launches that match both this project " //$NON-NLS-1$
                + "and this applicationId get stopped; clients from other projects, Attach sessions, and unrelated " //$NON-NLS-1$
                + "processes are left alone. Defaults to false.") //$NON-NLS-1$
            .stringProperty("timeoutSeconds", //$NON-NLS-1$
                "Soft wait limit in seconds (5-120, default 30). When the update has not finished within this " //$NON-NLS-1$
                    + "window, the call replies with status Pending plus a runKey - invoke this tool again with that " //$NON-NLS-1$
                    + "same runKey to continue waiting. Fast / incremental updates come back synchronously.") //$NON-NLS-1$
            .stringProperty("runKey", //$NON-NLS-1$
                "Resumes a Pending update that was already issued, using its runKey instead of kicking off a new run " //$NON-NLS-1$
                    + "(identical params also reproduce the same runKey). How long this call waits before " //$NON-NLS-1$
                    + "answering Pending again is timeoutSeconds, accepted as waitSeconds too. " //$NON-NLS-1$
                    + "All other params are ignored once " //$NON-NLS-1$
                    + "runKey is supplied. A fresh call without runKey always executes again - it never returns a stale " //$NON-NLS-1$
                    + "cached result.") //$NON-NLS-1$
            .booleanProperty("cancel", //$NON-NLS-1$
                "Detach and stop tracking. With runKey it stops that one update; with projectName and no runKey it stops " //$NON-NLS-1$
                    + "every update still running for that project - the exit for a caller whose failed call never " //$NON-NLS-1$
                    + "returned a runKey. BEST-EFFORT only: it makes the server stop waiting on and caching the result, " //$NON-NLS-1$
                    + "but does NOT abort an update already in progress (it can keep running, still commit, and it holds " //$NON-NLS-1$
                    + "the infobase until it returns). A finished result nobody has collected is left in place.") //$NON-NLS-1$
            .booleanProperty("statusOnly", //$NON-NLS-1$
                "Read what updates are being tracked and start nothing: runKeys, state " //$NON-NLS-1$
                    + "(running / finished-with-result-waiting), elapsed time, progress. " //$NON-NLS-1$
                    + "projectName filters by project. Any other run-shaping parameter beside " //$NON-NLS-1$
                    + "runKey or cancel is refused, not ignored. A finished result is NOT " //$NON-NLS-1$
                    + "consumed by this read - its receiver collects it with the runKey.") //$NON-NLS-1$
            .booleanProperty("refreshWorkspace", //$NON-NLS-1$
                "Refresh the project (and its parent's, for an extension) from disk before the " //$NON-NLS-1$
                    + "update state is read and the update runs (default: true). Files written outside " //$NON-NLS-1$
                    + "this server - a file tool, git checkout, a pull - are otherwise invisible to " //$NON-NLS-1$
                    + "the model, and the update decision would be made against what the disk held " //$NON-NLS-1$
                    + "before them, answering Done or UPDATED over an update that never carried them. " //$NON-NLS-1$
                    + "The answer reports workspaceRefresh.changedResources: how many resources " //$NON-NLS-1$
                    + "the re-read actually changed (added, removed, replaced or content-changed); " //$NON-NLS-1$
                    + "0 means the model already matched the disk. Pass false only " //$NON-NLS-1$
                    + "when every change went through this server.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    /**
     * The exit for a caller that has no runKey, because the call that failed never returned one.
     * <p>
     * This stops TRACKING. It does not free the infobase and must not claim to: the platform call
     * keeps running and only its own return releases the lock. Those are two different things, and
     * conflating them is what would let a second updater into a database the first is still
     * writing to.
     * </p>
     *
     * @param projectName the project whose runs should stop being tracked.
     * @return the JSON answer
     */
    private String stopTrackingByProject(String projectName)
    {
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("cancel without runKey needs projectName - it is what " //$NON-NLS-1$
                + "addresses the run when the call that failed never returned a runKey.").toJson(); //$NON-NLS-1$
        }
        int stopped = PendingWorkRegistry.UPDATE.stopTrackingFor(projectName);
        // What this instance holds is keyed by infobase; the runs stopped here are keyed by
        // project, and nothing ties the two. Reporting whatever came first would tell a caller
        // cancelling project A that it still holds an operation belonging to project B, so this
        // answer names no holding at all. MonopolyLock.outstandingHere() reports them properly.
        ToolResult result = ToolResult.success()
            .put("operation", NAME) //$NON-NLS-1$
            .put("projectName", projectName) //$NON-NLS-1$
            .put("stoppedTracking", stopped) //$NON-NLS-1$
            .put("note", stopped > 0 //$NON-NLS-1$
                ? "Stopped tracking " + stopped + " update(s). The work itself is NOT stopped: a " //$NON-NLS-1$ //$NON-NLS-2$
                    + "platform update keeps running and can still commit, and it holds the " //$NON-NLS-1$
                    + "infobase until it returns." //$NON-NLS-1$
                : "Nothing was being tracked for this project. A finished result nobody has " //$NON-NLS-1$
                    + "collected is deliberately left in place - ask for it with its runKey."); //$NON-NLS-1$
        return result.toJson();
    }

    /** The parameters a status read accepts beside itself and the timeout of the poll it is not. */
    private static final java.util.Set<String> STATUS_ONLY_COMPANIONS =
        java.util.Set.of("statusOnly", "projectName", "runKey", "cancel", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "timeoutSeconds", "timeoutMs", "waitSeconds", "timeout"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /**
     * The parameter names a facade hands down for its own routing. They belong to the facade, not
     * to the call: a status read that rejected them would refuse every call that arrives through
     * infobase_admin, which always carries them.
     */
    private static final java.util.Set<String> ROUTING_PARAMETER_NAMES =
        java.util.Set.of("operation"); //$NON-NLS-1$

    /**
     * Answers with what is being tracked, launching nothing.
     * <p>
     * Measured on the stand 15.09: a caller that asked "is an update still going" started a second
     * update to find out. The answer must cost no work of its own, and the registry this tool
     * shares with {@code edit_metadata} must not lend it a metadata write to report: entries carry
     * a kind for exactly that question.
     * </p>
     * <p>
     * Completed-but-not-collected entries are part of the answer - the registry holds them until
     * their receiver collects or TTL evicts them, and "finished, result waiting" is a state a
     * caller has to know, not an absence to hide.
     * </p>
     *
     * @param params the call, which may carry projectName to filter by.
     * @return the JSON answer
     */
    private String readStatus(Map<String, String> params)
    {
        for (String key : params.keySet())
        {
            if (!STATUS_ONLY_COMPANIONS.contains(key) && !ROUTING_PARAMETER_NAMES.contains(key))
            {
                return ToolResult.error("statusOnly reads state and starts nothing; '" + key //$NON-NLS-1$ //$NON-NLS-2$
                    + "' shapes a run that is not started. Drop it, or drop statusOnly.").toJson();
            }
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        PendingWorkRegistry registry = PendingWorkRegistry.UPDATE;
        registry.pruneExpired();
        java.util.List<PendingWorkRegistry.PendingEntry> tracked = registry.trackedOf(WORK_KIND);
        java.util.List<Map<String, Object>> updates = new java.util.ArrayList<>();
        for (PendingWorkRegistry.PendingEntry entry : tracked)
        {
            if (projectName != null && !projectName.isEmpty()
                && (entry.subject == null || !projectName.equals(entry.subject)))
            {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("runKey", entry.runKey); //$NON-NLS-1$
            row.put("subject", entry.subject); //$NON-NLS-1$
            row.put("state", entry.isDone() ? "finished" : "running"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            row.put("elapsedMs", entry.elapsedMs()); //$NON-NLS-1$
            if (entry.progressNote != null)
            {
                row.put("progressNote", entry.progressNote); //$NON-NLS-1$
            }
            updates.add(row);
        }
        return ToolResult.success()
            .put("operation", NAME) //$NON-NLS-1$
            .put("statusOnly", true) //$NON-NLS-1$
            .put("trackedUpdates", updates.size()) //$NON-NLS-1$
            .put("updates", updates) //$NON-NLS-1$
            .put("nothingStarted", true) //$NON-NLS-1$
            .put("note", updates.isEmpty() //$NON-NLS-1$
                ? "No update is tracked" + (projectName != null && !projectName.isEmpty() //$NON-NLS-1$
                    ? " for this project" : "") //$NON-NLS-1$ //$NON-NLS-2$
                    + ". Finished entries are reported while their receiver can still collect "
                    + "them; an entry evicted by TTL is gone, not hidden." //$NON-NLS-1$
                : "This is a read: nothing was started or updated. 'finished' means the result is " //$NON-NLS-1$
                    + "waiting to be collected with its runKey.") //$NON-NLS-1$
            .toJson();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String runKeyParam = JsonUtils.extractStringArgument(params, "runKey"); //$NON-NLS-1$
        boolean statusOnly = JsonUtils.extractBooleanArgument(params, "statusOnly", false); //$NON-NLS-1$

        // Precedence, one answer per call. {runKey, cancel} and {runKey, cancel, statusOnly} is a
        // cancellation - the existing contract decides that. {runKey} and {runKey, statusOnly} is
        // a poll. {cancel, statusOnly} is a cancellation too, because cancel outranks the read.
        // statusOnly never launches a run, and it rejects any parameter that would shape one:
        // a value that cannot apply must not ride along in silence.
        if (runKeyParam != null && !runKeyParam.isEmpty()
            && JsonUtils.extractBooleanArgument(params, "cancel", false)) //$NON-NLS-1$
        {
            boolean removed = PendingWorkRegistry.UPDATE.cancel(runKeyParam);
            return ToolResult.success()
                .put("operation", NAME) //$NON-NLS-1$
                .put("runKey", runKeyParam) //$NON-NLS-1$
                .put("cancelled", removed) //$NON-NLS-1$
                .put("note", removed //$NON-NLS-1$
                    ? "Stopped tracking this update. Best-effort: an update already running against the " //$NON-NLS-1$
                        + "infobase may still finish and commit its changes." //$NON-NLS-1$
                    : "runKey was not found (the update already finished and was already " //$NON-NLS-1$
                        + "retrieved, or it was evicted by TTL).") //$NON-NLS-1$
                .toJson();
        }
        if ((runKeyParam == null || runKeyParam.isEmpty())
            && JsonUtils.extractBooleanArgument(params, "cancel", false)) //$NON-NLS-1$
        {
            return stopTrackingByProject(JsonUtils.extractStringArgument(params, "projectName")); //$NON-NLS-1$
        }
        if (runKeyParam != null && !runKeyParam.isEmpty())
        {
            return resumePending(runKeyParam, params);
        }
        if (statusOnly)
        {
            return readStatus(params);
        }

        String configName = JsonUtils.extractStringArgument(params, "launchConfigurationName"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        boolean fullUpdate = JsonUtils.extractBooleanArgument(params, "fullUpdate", false); //$NON-NLS-1$ //$NON-NLS-2$
        boolean autoRestructure = JsonUtils.extractBooleanArgument(params, "autoRestructure", true); //$NON-NLS-1$ //$NON-NLS-2$
        boolean autoFreeClients = JsonUtils.extractBooleanArgument(params, "autoFreeClients", false); //$NON-NLS-1$ //$NON-NLS-2$
        boolean ignoreBranchBinding =
            JsonUtils.extractBooleanArgument(params, "ignoreBranchBinding", false); //$NON-NLS-1$
        // Same word the .cf dump uses for the same guard, so one habit covers both.
        boolean skipValidation =
            JsonUtils.extractBooleanArgument(params, "skipValidation", false); //$NON-NLS-1$
        boolean checkOnly = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        boolean hasName = configName != null && !configName.isEmpty();
        if (!hasName)
        {
            if (projectName == null || projectName.isEmpty())
            {
                return ToolResult.error("projectName is required unless launchConfigurationName is supplied").toJson(); //$NON-NLS-1$
            }
            // applicationId may be omitted. An extension project has no application of its own,
            // so demanding one left the caller with nothing to look up - get_applications on the
            // extension answers "none" - and the parent-infobase route below unreachable through
            // the door an agent actually walks through.
        }

        // A launch configuration name fixes the project + applicationId pair, so prefer it.
        if (hasName)
        {
            DebugPlugin debugPlugin = DebugPlugin.getDefault();
            ILaunchManager launchManager = debugPlugin != null ? debugPlugin.getLaunchManager() : null;
            if (launchManager == null)
            {
                return ToolResult.error("The Eclipse launch manager is currently unavailable").toJson(); //$NON-NLS-1$
            }
            ILaunchConfiguration cfg = LaunchConfigAccess.findLaunchConfigByName(launchManager, configName);
            if (cfg == null)
            {
                return ToolResult.error("No launch configuration named '" + configName //$NON-NLS-1$
                    + "' exists. Call list_configurations to see the available ones.").toJson(); //$NON-NLS-1$
            }
            if (!LaunchConfigAccess.LAUNCH_CONFIG_TYPE_ID.equals(LaunchConfigAccess.getConfigTypeId(cfg)))
            {
                return ToolResult.error("Launch profile '" + cfg.getName() //$NON-NLS-1$
                    + "' is not a runtime-client configuration - update_database needs one of those.").toJson(); //$NON-NLS-1$
            }
            String cfgProject = LaunchConfigAccess.readAttribute(cfg, LaunchConfigAccess.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
            String cfgAppId = LaunchConfigAccess.readAttribute(cfg, LaunchConfigAccess.ATTR_APPLICATION_ID, ""); //$NON-NLS-1$
            if (cfgProject.isEmpty() || cfgAppId.isEmpty())
            {
                return ToolResult.error("Launch profile '" + cfg.getName() //$NON-NLS-1$
                    + "' is missing its project or applicationId attribute, so the update target cannot be determined.").toJson(); //$NON-NLS-1$
            }
            projectName = cfgProject;
            applicationId = cfgAppId;
        }

        String notReadyError = ProjectStateGuard.checkReadyOrError(projectName);
        if (notReadyError != null)
        {
            return ToolResult.error(notReadyError).toJson();
        }

        // A slow FULL / restructure update would otherwise hold an HTTP-handler thread for its whole
        // run. Hand it to the worker registry; the caller polls via runKey, and a fast update that
        // finishes inside the window still returns synchronously. updateDatabase() re-resolves its own
        // state from these params, so no live EDT handle crosses the thread boundary. A PROBE is
        // answered on this thread instead and never reaches the registry - see runOrAnswer - while the
        // key below stays the one a real update under these arguments owns.
        final String fProjectName = projectName;
        final String fApplicationId = applicationId;
        final boolean fFull = fullUpdate;
        final boolean fRestr = autoRestructure;
        final boolean fFree = autoFreeClients;
        final boolean fIgnoreBranch = ignoreBranchBinding;
        final boolean fSkipValidation = skipValidation;
        // The override is part of the run's identity: the same call with and without it is two
        // different intentions, and coalescing them would let a refusal be served as the answer
        // to a caller who had said to go ahead. A probe carries none of it - it is not a run, and
        // the key below is the one a real update under these arguments owns.
        String runKey = PendingWorkRegistry.computeRunKey(fProjectName, fApplicationId,
            String.valueOf(fFull), String.valueOf(fRestr), String.valueOf(fFree),
            String.valueOf(fIgnoreBranch));
        long timeoutMs = TimeoutArgs.readSeconds(params, DEFAULT_TIMEOUT_SECONDS,
            MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS) * 1000L;

        return runOrAnswer(checkOnly, runKey, PendingWorkRegistry.UPDATE, fProjectName, timeoutMs,
            () -> updateDatabase(fProjectName, fApplicationId, fFull, fRestr, fFree, fIgnoreBranch,
                fSkipValidation, checkOnly, params));
    }

    /**
     * Runs the call: through the run registry for an update, in place for a probe.
     * <p>
     * A probe is answered where it arrives and never enters the registry. It reads and starts
     * nothing, so it has no run to track and no runKey to hand back - but that is not the whole of
     * it. The registry coalesces on the key alone, and the key is built from the arguments an
     * update coalesces on, which a probe shares with the update it asks about. On that path a probe
     * was served a real update's answer - a run it never asked for, reported as the environment's
     * state before the update - and a real update arriving while a probe ran joined the probe and
     * never executed.
     * </p>
     *
     * @param answerInPlace whether this call is a probe, answered on the calling thread
     * @param runKey the key a real run under these arguments owns
     * @param registry the run registry, which a probe does not touch
     * @param projectName the project, named in a Pending body
     * @param timeoutMs how long a real run is waited for before a Pending answer
     * @param work the body: the probe's answer, or the update itself
     * @return a JSON result body
     */
    static String runOrAnswer(boolean answerInPlace, String runKey, PendingWorkRegistry registry,
        String projectName, long timeoutMs, java.util.function.Supplier<String> work)
    {
        if (answerInPlace)
        {
            return work.get();
        }
        registry.pruneExpired();
        // A FRESH call must never be silently served a finished cached result for the same params:
        // update_database asserts "this just happened", so a completed entry from a prior identical
        // call is evicted here, forcing a real re-run. An in-flight identical call still coalesces
        // onto the running future via getOrStart.
        PendingWorkRegistry.PendingEntry existing = registry.get(runKey);
        if (existing != null && existing.isDone())
        {
            registry.remove(runKey);
        }
        PendingWorkRegistry.PendingEntry entry = registry.getOrStart(runKey, work);
        // So a caller who never got the runKey can still address this run - and so the shared
        // registry answers questions about updates with updates: edit_metadata starts its pending
        // work in this same registry, and a kind-less entry cannot be told apart from either.
        entry.subject = projectName;
        entry.workKind = WORK_KIND;
        // The name a poll of this run arrives under, so a live key exempts only this tool's own
        // resumption path from the heavy gates.
        entry.startedBy = NAME;

        String result = entry.await(timeoutMs);
        if (result != null)
        {
            registry.remove(runKey);
            return result;
        }
        return buildPendingJson(runKey, entry, projectName, timeoutMs);
    }

    /**
     * Polls a previously-issued runKey: returns the cached result (and removes the entry) or a fresh
     * Pending body.
     *
     * @param runKey the key to poll
     * @param params the call params (for the timeout)
     * @return a JSON result body
     */
    private String resumePending(String runKey, Map<String, String> params)
    {
        PendingWorkRegistry registry = PendingWorkRegistry.UPDATE;
        registry.pruneExpired();
        PendingWorkRegistry.PendingEntry entry = registry.get(runKey);
        if (entry == null)
        {
            return ToolResult
                .error("runKey was not found - the update either already finished and was " //$NON-NLS-1$
                    + "retrieved, or it was abandoned and evicted by TTL. Send a fresh request without runKey " //$NON-NLS-1$
                    + "to start again.") //$NON-NLS-1$
                .put("operation", NAME) //$NON-NLS-1$
                .put("runKey", runKey) //$NON-NLS-1$
                .toJson();
        }
        if (entry.workKind != null && !WORK_KIND.equals(entry.workKind))
        {
            // A key that names a pending edit_metadata call must not resume it as an update - the
            // registry is shared, and resuming the wrong work is worse than not finding the key.
            return ToolResult.error("runKey belongs to " + entry.workKind + ", not to " //$NON-NLS-1$ //$NON-NLS-2$
                + NAME + ". Poll it with that tool - resuming it here would answer for work this " //$NON-NLS-1$
                + "call never started.").put("operation", NAME).put("runKey", runKey).toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        long timeoutMs = TimeoutArgs.readSeconds(params, DEFAULT_TIMEOUT_SECONDS,
            MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS) * 1000L;
        String result = entry.await(timeoutMs);
        if (result != null)
        {
            registry.remove(runKey);
            return result;
        }
        return buildPendingJson(runKey, entry, null, timeoutMs);
    }

    /**
     * The application to update when the caller named none.
     * <p>
     * Asking for the default rather than "the only one" on purpose: a project with several
     * applications already has an answer to this question, and picking a different one here
     * would update an infobase nobody asked about.
     * </p>
     *
     * @param appManager the application manager.
     * @param project the project to ask about.
     * @return the default application, or empty when the project has none.
     */
    private static Optional<IApplication> defaultApplication(IApplicationManager appManager, IProject project)
    {
        try
        {
            return appManager.getDefaultApplication(project);
        }
        catch (Exception e)
        {
            // Reported rather than swallowed: without it the caller is told there is no
            // application, which is a different thing from "could not be asked".
            Activator.logWarning("Could not resolve the default application for " //$NON-NLS-1$
                + project.getName() + ": " + e.getMessage()); //$NON-NLS-1$
            return Optional.empty();
        }
    }

    /**
     * The workspace refresh a probe performs before it reads the update state, or {@code null} when
     * the caller asked for none.
     * <p>
     * A seam rather than a call, because how often a probe refreshes is part of what the answer
     * promises - none when the caller turned it off, exactly one otherwise - and that count is not
     * readable back out of the answer once the refresh has nothing to report.
     * </p>
     */
    interface WorkspaceRefresh
    {
        /**
         * Makes the workspace hear about what the disk holds.
         *
         * @return the report: projects touched, resources changed, any failure noted
         */
        JsonObject refresh();
    }

    /**
     * The refresh a probe asks for, from the call as it arrived.
     * <p>
     * The flag is read here so the whole rule sits in one place: on unless the caller turns it off,
     * and turned off it is not a refresh that reports nothing, it is no refresh at all.
     * </p>
     *
     * @param params the call
     * @param project the project being updated
     * @param infobaseProject the project that owns the infobase - the parent, for an extension
     * @return the refresh to perform, or {@code null} when the caller turned it off
     */
    static WorkspaceRefresh refreshForProbe(Map<String, String> params, IProject project,
        IProject infobaseProject)
    {
        if (!JsonUtils.extractBooleanArgument(params, "refreshWorkspace", true)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return null;
        }
        return () -> refreshFromDisk(project, infobaseProject);
    }

    /**
     * What an update would face, reading only what cannot start one.
     * <p>
     * Two things are reachable without running an update: the update state the environment holds -
     * whether an update is needed and of which kind - and, when the caller asked for it, a workspace
     * refresh, so that state is read against a model that has heard about what the disk holds. The
     * COMPOSITION of an update, object by object, is not reachable: nothing in the application API
     * offers it, and it is not worth running an update to find out. Both are said here rather than
     * left for the caller to infer from a short answer.
     * </p>
     * <p>
     * The readiness check an update itself performs is NOT among these reads. It reaches the
     * infobase synchronization cycle - a thick client process, {@code config generation-id},
     * {@code config dump-files}, a load into the infobase - through
     * {@code InfobaseApplicationBehaviourDelegate.check} and
     * {@code IInfobaseSynchronizationManager.retrieveInfobaseChanges}. Behind an open thick-client
     * session that load waits for a monopoly it cannot get, so the check does not return, and a
     * probe that asked for it hung the EDT it was asked about. What it used to fill is now a
     * statement that it was not asked for, which is the whole of what a dry run can honestly say
     * about it.
     * </p>
     * <p>
     * Nothing is claimed and nothing is recorded: no run, no infobase claim, no change to the update
     * state.
     * </p>
     *
     * @param appManager the application manager, taken whole rather than as the one state it holds,
     *            so what this path reads of it is a fact a stand-in can count.
     * @param application the application that would be updated.
     * @param refresh the workspace refresh to perform first, or {@code null} for none.
     * @param applicationId its id, as the answer names it.
     * @param projectName the project the call named.
     * @param viaParent whether the infobase belongs to the parent configuration.
     * @param infobaseOwnerName the project that owns the infobase - the parent, for an extension.
     * @return the answer
     */
    static String whatAnUpdateWouldFace(IApplicationManager appManager, IApplication application,
        WorkspaceRefresh refresh, String applicationId, String projectName, boolean viaParent,
        String infobaseOwnerName)
    {
        JsonObject workspaceRefresh = refresh == null ? null : refresh.refresh();
        ApplicationUpdateState state;
        try
        {
            state = appManager.getUpdateState(application);
        }
        catch (Exception | LinkageError cannotRead)
        {
            return ToolResult.error("Could not read what '" + infobaseOwnerName //$NON-NLS-1$
                + "' holds, so nothing can be said about what an update would face: " + cannotRead) //$NON-NLS-1$
                .toJson();
        }
        ToolResult answer = ToolResult.success()
            .put("dryRun", Boolean.TRUE) //$NON-NLS-1$
            .put("projectName", projectName) //$NON-NLS-1$
            .put("applicationId", applicationId) //$NON-NLS-1$
            .put("updateState", state == null ? "UNKNOWN" : state.name()) //$NON-NLS-1$ //$NON-NLS-2$
            .put("wouldUpdate", Boolean.valueOf(state == ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED //$NON-NLS-1$
                || state == ApplicationUpdateState.FULL_UPDATE_REQUIRED));
        if (viaParent)
        {
            answer.put("infobaseOwner", infobaseOwnerName); //$NON-NLS-1$
        }
        if (workspaceRefresh != null)
        {
            // The answer asserts what the model holds, so it asserts the refresh too: the state
            // below is only as current as the workspace this call just read.
            answer.put("workspaceRefresh", workspaceRefresh); //$NON-NLS-1$
        }
        return answer
            .put("notCheckedInDryRun", List.of("readiness", "exportValidation")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .put("notCheckedInDryRunNote", "The readiness check an update itself performs is not run " //$NON-NLS-1$ //$NON-NLS-2$
                + "here. Asking for it reaches the infobase synchronization cycle through a thick " //$NON-NLS-1$
                + "client and does not return while a thick-client session holds the infobase, which " //$NON-NLS-1$
                + "is what a dry run exists to avoid. Only an update can answer it. The export " //$NON-NLS-1$
                + "validation an update runs before writing is not run either: it walks the whole " //$NON-NLS-1$
                + "project. diagnostics operation=validate_for_export runs it.") //$NON-NLS-1$
            .put("composition", "not available without running an update - the application API " //$NON-NLS-1$ //$NON-NLS-2$
                + "reports the state, not the objects an update would carry. " //$NON-NLS-1$
                + "Nothing was claimed, started or recorded by this call.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * An execution context carrying the active shell.
     * <p>
     * The update is given one, because it wants a shell for any modal it raises. A probe is not:
     * it raises none, and nothing else it reads asks for a shell.
     * </p>
     *
     * @return the context, carrying a shell when the workbench has one
     */
    private static ExecutionContext contextWithActiveShell()
    {
        ExecutionContext context = new ExecutionContext();
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
        {
            return context;
        }
        final Shell[] shellHolder = new Shell[1];
        display.syncExec(() ->
        {
            shellHolder[0] = display.getActiveShell();
            if (shellHolder[0] == null)
            {
                Shell[] shells = display.getShells();
                if (shells.length > 0)
                {
                    shellHolder[0] = shells[0];
                }
            }
        });
        if (shellHolder[0] != null)
        {
            context.setProperty(ExecutionContext.ACTIVE_SHELL_NAME, shellHolder[0]);
        }
        return context;
    }

    /**
     * Builds the Pending body returned when the wait budget runs out before the update finishes.
     *
     * @param runKey the key to resume with
     * @param entry the in-flight update
     * @param projectName the project, or {@code null} to omit
     * @param timeoutMs how long was waited
     * @return a JSON Pending body
     */
    private static String buildPendingJson(String runKey, PendingWorkRegistry.PendingEntry entry, String projectName,
        long timeoutMs)
    {
        ToolResult body = ToolResult.success()
            .put("operation", NAME) //$NON-NLS-1$
            .put("status", "Pending") //$NON-NLS-1$ //$NON-NLS-2$
            .put(ru.aiedt.mcp.server.support.PendingEnvelope.MARK, true)
            .put("runKey", runKey) //$NON-NLS-1$
            .put("elapsedMs", entry.elapsedMs()) //$NON-NLS-1$
            .put("waitedMs", timeoutMs) //$NON-NLS-1$
            .put("hint", "Update still running. Re-invoke this tool with runKey=\"" //$NON-NLS-1$ //$NON-NLS-2$
                + runKey + "\" to keep waiting (or resend the same params - they yield the same " //$NON-NLS-1$
                + "runKey). Add cancel=true alongside the runKey to stop tracking it."); //$NON-NLS-1$
        if (projectName != null)
        {
            body.put("projectName", projectName); //$NON-NLS-1$
        }
        return body.toJson();
    }

    /**
     * The worker: resolves the application and runs the update. Runs possibly off the HTTP thread.
     * <p>
     * A successful return does not mean the database is fully updated - only that the call came back
     * without error. The result says so explicitly via {@code updateComplete} so a caller does not read
     * a bare {@code success:true} as "done".
     * </p>
     *
     * @param projectName the project
     * @param requestedApplicationId the application id, or null/empty to resolve the default -
     *            the project's own, else the one belonging to the configuration it extends
     * @param fullUpdate full vs incremental
     * @param autoRestructure whether EDT may restructure
     * @param autoFreeClients whether to free held clients first
     * @param ignoreBranchBinding whether to go ahead when the branch names another application
     * @param skipValidation whether to skip the checks that refuse what the infobase would refuse
     * @param checkOnly whether to answer what an update would face and start nothing. Reached only
     *            from the caller's thread - a probe is never a tracked run, see {@link #runOrAnswer}
     * @param params the full call, for the refreshWorkspace flag
     * @return a JSON result body
     */
    private String updateDatabase(String projectName, String requestedApplicationId, boolean fullUpdate,
        boolean autoRestructure, boolean autoFreeClients, boolean ignoreBranchBinding,
        boolean skipValidation, boolean checkOnly, Map<String, String> params)
    {
        String blocked = exportScanBefore(projectName, skipValidation, checkOnly,
            DatabaseUpdater::refuseWhatTheInfobaseWillRefuse);
        if (blocked != null)
        {
            return blocked;
        }
        String applicationId = requestedApplicationId;

        // Populated by auto-free so both success and error paths can report what was stopped.
        List<Map<String, Object>> freedClients = null;
        // Held across the whole attempt and released in the finally below, so a throw halfway
        // through an update does not leave a claim behind. A claim outliving its work would be
        // cleared as stale only when this whole EDT went away.
        MonopolyLock infobaseClaim = null;
        // Whoever owns the infobase - the project asked about and, for an extension, its parent.
        // Declared out here so a failure can name who is holding the base, which is the one thing
        // the platform error never says.
        Set<String> ownerNames = new LinkedHashSet<>();
        try
        {
            IProject project = ProjectResolver.resolve(projectName);
            if (project == null)
            {
                return ProjectResolver.notFound(projectName).toJson();
            }

            IApplicationManager appManager = Activator.getDefault().getApplicationManager();
            if (appManager == null)
            {
                return ToolResult.error("The IApplicationManager service is currently unavailable").toJson(); //$NON-NLS-1$
            }

            // An extension project has no infobase of its own - it shares the one belonging
            // to the configuration it extends, and updating that infobase is what carries
            // the extension's code into it. Asked of the extension alone, this answered "no
            // application found", which reads as "cannot be done" and sent callers down a
            // hand-run export / unpack / substitute / repack / install cycle to get an
            // extension's current module into the base.
            boolean named = applicationId != null && !applicationId.isEmpty();
            Optional<IApplication> appOpt =
                named ? appManager.getApplication(project, applicationId) : defaultApplication(appManager, project);
            IProject infobaseProject = project;
            boolean viaParent = false;
            if (!appOpt.isPresent())
            {
                IProject parent = BmCommonModuleGuards.parentProjectOf(project);
                if (parent != null && parent.exists() && parent.isOpen())
                {
                    Optional<IApplication> parentApp =
                        named ? appManager.getApplication(parent, applicationId)
                            : defaultApplication(appManager, parent);
                    if (parentApp.isPresent())
                    {
                        appOpt = parentApp;
                        infobaseProject = parent;
                        viaParent = true;
                    }
                }
            }
            if (!appOpt.isPresent())
            {
                return ToolResult.error(named
                    ? "No application found for: " + applicationId //$NON-NLS-1$
                        + ". Call get_applications to list valid application IDs." //$NON-NLS-1$
                    : "No application to update: " + projectName + " has none, and neither does the " //$NON-NLS-1$ //$NON-NLS-2$
                        + "configuration it extends (if it extends one). Call get_applications to see " //$NON-NLS-1$
                        + "what is available, or name a launchConfigurationName.").toJson(); //$NON-NLS-1$
            }
            if (!named)
            {
                applicationId = appOpt.get().getId();
            }
            // The launches that matter belong to whoever owns the infobase. For an extension routed
            // to its parent that is the parent's name, and filtering by the extension's alone would
            // have quietly matched nothing on the very path the fallback opened.
            ownerNames.add(projectName);
            ownerNames.add(infobaseProject.getName());
            if (viaParent)
            {
                Activator.logInfo("update_database: " + project.getName() //$NON-NLS-1$
                    + " is an extension project and has no infobase; updating the one of its " //$NON-NLS-1$
                    + "parent, " + infobaseProject.getName()); //$NON-NLS-1$
            }

            IApplication application = appOpt.get();

            if (checkOnly)
            {
                // Answered here, and only here: the probe reaches this method on the caller's
                // thread, never as a tracked run - see runOrAnswer. What it reads is the state and,
                // if asked, a refresh; what it must not read is the readiness check, which
                // synchronizes with the infobase.
                return whatAnUpdateWouldFace(appManager, application,
                    refreshForProbe(params, project, infobaseProject), applicationId, projectName,
                    viaParent, infobaseProject.getName());
            }

            boolean refreshWorkspace =
                JsonUtils.extractBooleanArgument(params, "refreshWorkspace", true); //$NON-NLS-1$
            JsonObject workspaceRefresh = null;
            if (refreshWorkspace)
            {
                // Files written outside this server - a file tool, git checkout, a pull - are
                // invisible to the model until the workspace hears about them, and the update
                // decision right below is made against the model. Reported, never silent: 0 means
                // the model already matched the disk, a number names how much this call picked up.
                workspaceRefresh = refreshFromDisk(project, infobaseProject);
            }

            ApplicationUpdateState stateBefore = appManager.getUpdateState(application);
            if (stateBefore == ApplicationUpdateState.BEING_UPDATED)
            {
                return ToolResult.error("This application has an update already in progress - wait for it to finish.").toJson(); //$NON-NLS-1$
            }

            // The branch says which infobase belongs to this work. Checked BEFORE anything is
            // claimed or freed, because the whole point is to stop short of touching the wrong one
            // - an update that restructures tables is not a thing anybody undoes.
            String branchRefusal =
                branchMismatch(project, infobaseProject, applicationId, ignoreBranchBinding);
            if (branchRefusal != null)
            {
                ToolResult wrongBase = ToolResult.error(branchRefusal);
                wrongBase.put("applicationId", applicationId); //$NON-NLS-1$
                wrongBase.put("projectName", projectName); //$NON-NLS-1$
                return wrongBase.toJson();
            }

            // A neighbouring EDT updating the SAME infobase is the collision this catches. EDT's own
            // BEING_UPDATED state is per-instance and says nothing about the process next door; what
            // that looks like without a claim is a platform error about a locked configuration, or a
            // wait that ends in a timeout - neither of which names anybody.
            String infobaseIdentity = InfobaseIdentity.of(application);
            // claim, not take: take discards which side the refusal came from, and this path is
            // the one that reported "another AI-EDT instance is working on this infobase" about
            // an update THIS instance had left holding the lock.
            MonopolyLock.Claim infobaseAttempt =
                MonopolyLock.claim(infobaseIdentity, "update_database"); //$NON-NLS-1$
            java.util.Optional<MonopolyLock> claim = infobaseAttempt.granted()
                ? java.util.Optional.of(infobaseAttempt.held) : java.util.Optional.empty();
            infobaseClaim = claim.orElse(null);
            if (infobaseIdentity != null && claim.isEmpty())
            {
                // The sentence comes from the attempt, which is the only thing that knows WHICH
                // side refused. Naming a neighbour when the holder is this instance is what the
                // field report quoted, and it sent the reader looking for a process next door.
                boolean ours = MonopolyLock.isHeldByThisInstance(infobaseAttempt.heldBy);
                ToolResult taken = ToolResult.error(infobaseAttempt.refusal());
                if (ours)
                {
                    taken.put("heldByThisInstance", Boolean.TRUE); //$NON-NLS-1$
                }
                taken.put("tag", ErrorTags.BUSY.wire()); //$NON-NLS-1$
                taken.put("applicationId", applicationId); //$NON-NLS-1$
                putHolders(taken, applicationId, ownerNames);
                return taken.toJson();
            }

            ApplicationUpdateType updateType =
                fullUpdate ? ApplicationUpdateType.FULL : ApplicationUpdateType.INCREMENTAL;

            ExecutionContext context = contextWithActiveShell();

            Activator.logInfo("Applying database update - project=" + projectName //$NON-NLS-1$
                + ", app=" + applicationId //$NON-NLS-1$
                + ", type=" + updateType //$NON-NLS-1$
                + ", autoRestructure=" + autoRestructure); //$NON-NLS-1$

            IProgressMonitor monitor = new NullProgressMonitor();

            // Freeing the infobase before the update: stop this application's own EDT-tracked
            // runtime-client launches so a running client cannot hold the lock and fail the update.
            // terminate() is async, so each stop is waited out (bounded) before the update runs.
            if (autoFreeClients)
            {
                freedClients = freeClientsForApplication(applicationId, ownerNames);
                if (appManager.getUpdateState(application) == ApplicationUpdateState.BEING_UPDATED)
                {
                    ToolResult busy = ToolResult.error("The application flipped to BEING_UPDATED while " //$NON-NLS-1$
                        + "clients were being freed automatically (a concurrent update is running). The freed clients are listed; " //$NON-NLS-1$
                        + "retry once that update finishes."); //$NON-NLS-1$
                    busy.put("applicationId", applicationId); //$NON-NLS-1$
                    busy.put("autoFreeClients", true); //$NON-NLS-1$
                    busy.put("freedClients", freedClients); //$NON-NLS-1$
                    putHolders(busy, applicationId, ownerNames);
                    return busy.toJson();
                }
            }

            // Asked again, right here. Between the guard above and this line the call opens a
            // shell, may wait on the interface, and stops running clients one by one - seconds at
            // best, and on a large configuration considerably more. A branch checked out in that
            // window would have been checked against a state that no longer holds, and what the
            // guard exists to prevent is precisely the update that restructures the wrong infobase.
            // Cheap to ask, and the only moment where the answer is still true when it matters.
            String branchChanged =
                branchMismatch(project, infobaseProject, applicationId, ignoreBranchBinding);
            if (branchChanged != null)
            {
                ToolResult switched = ToolResult.error("The branch changed while this update was "
                    + "being prepared, and now says another infobase. " + branchChanged);
                switched.put("applicationId", applicationId); //$NON-NLS-1$
                switched.put("projectName", projectName); //$NON-NLS-1$
                switched.put("freedClients", freedClients); //$NON-NLS-1$
                switched.put("branchRecheckedBeforeUpdate", true); //$NON-NLS-1$
                return switched.toJson();
            }

            ApplicationUpdateState stateAfter = appManager.update(application, updateType, context, monitor);

            // update() can hand back BEING_UPDATED: the work goes on inside EDT after this call
            // returns. Releasing the claim then would announce the infobase free while it is being
            // restructured, and the neighbour that took it next would meet the platform's own lock
            // instead of our answer - which is the confusion this claim exists to remove. So the
            // claim is held until the state leaves BEING_UPDATED, or until waiting stops being
            // reasonable. Bounded, because a claim held forever is the other failure.
            String stillUpdating = null;
            if (stateAfter == ApplicationUpdateState.BEING_UPDATED)
            {
                stateAfter = awaitUpdateEnd(appManager, application);
                if (stateAfter == ApplicationUpdateState.BEING_UPDATED)
                {
                    stillUpdating = "The update was still running after " //$NON-NLS-1$
                        + (BEING_UPDATED_WAIT_MS / 1000) + " seconds of waiting, and the claim on " //$NON-NLS-1$
                        + "this infobase has been released. Another instance may now take it while " //$NON-NLS-1$
                        + "EDT is still working - check the state before starting anything else."; //$NON-NLS-1$
                }
            }

            boolean updateComplete = stateAfter == ApplicationUpdateState.UPDATED;
            ToolResult result = ToolResult.success()
                .put("project", projectName) //$NON-NLS-1$
                .put("applicationId", applicationId) //$NON-NLS-1$
                .put("applicationName", application.getName()) //$NON-NLS-1$
                .put("updateType", updateType.name()) //$NON-NLS-1$
                .put("stateBefore", stateBefore.name()) //$NON-NLS-1$
                .put("stateAfter", stateAfter.name()) //$NON-NLS-1$
                .put("updateComplete", updateComplete); //$NON-NLS-1$

            // An answer must not imply protection it did not have. A claim that could not be
            // written still lets the work through - deliberately - but saying nothing about it
            // reports a guarded update where none was guarded.
            if (infobaseClaim != null && infobaseClaim.unprotectedReason() != null)
            {
                result.put("withoutCrossProcessClaim", infobaseClaim.unprotectedReason()); //$NON-NLS-1$
            }

            if (stillUpdating != null)
            {
                result.put("stillUpdating", stillUpdating); //$NON-NLS-1$
            }

            if (viaParent)
            {
                // Said out loud: the infobase that moved is not the one named in the call.
                // A bare success here would leave a caller believing the extension project
                // has an infobase of its own.
                result.put("infobaseProject", infobaseProject.getName()); //$NON-NLS-1$
                result.put("viaParentProject", "This is an extension project and has no " //$NON-NLS-1$ //$NON-NLS-2$
                    + "infobase of its own. The infobase of the configuration it extends (" //$NON-NLS-1$
                    + infobaseProject.getName() + ") was updated, which is what carries the " //$NON-NLS-1$
                    + "extension's current code into the base."); //$NON-NLS-1$
            }

            if (updateComplete)
            {
                result.put("message", "Database update finished successfully"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else if (stateAfter == ApplicationUpdateState.BEING_UPDATED)
            {
                result.put("message", "Update is still running"); //$NON-NLS-1$ //$NON-NLS-2$
                result.put("updateIncomplete", "The update call returned before finishing " //$NON-NLS-1$ //$NON-NLS-2$
                    + "(stateAfter=BEING_UPDATED). The infobase might not yet match the " //$NON-NLS-1$
                    + "configuration - verify the application state with get_applications before " //$NON-NLS-1$
                    + "trusting it."); //$NON-NLS-1$
            }
            else
            {
                // An update that was required and did not reach UPDATED is a failed update, not a
                // successful call with a footnote. The platform refuses inside the run without
                // throwing - the state is the only programmatic sign it leaves, and its own text
                // goes to the workspace log. Measured 15.09 on a real configuration: the caller was
                // left polling a run that had already been refused.
                ToolResult refusal = ToolResult.error("The database was not updated: the update " //$NON-NLS-1$
                    + "ended in state " + stateAfter.name() + " rather than UPDATED. The platform " //$NON-NLS-1$ //$NON-NLS-2$
                    + "refuses inside the run without raising an error, and its own wording is in " //$NON-NLS-1$
                    + "the workspace log (.metadata/.log) - read it there for the reason.")
                    .put("project", projectName) //$NON-NLS-1$
                    .put("applicationId", applicationId) //$NON-NLS-1$
                    .put("applicationName", application.getName()) //$NON-NLS-1$
                    .put("updateType", updateType.name()) //$NON-NLS-1$
                    .put("stateBefore", stateBefore.name()) //$NON-NLS-1$
                    .put("stateAfter", stateAfter.name()) //$NON-NLS-1$
                    .put("updateComplete", Boolean.FALSE); //$NON-NLS-1$
                if (stillUpdating != null)
                {
                    refusal.put("claimReleased", stillUpdating); //$NON-NLS-1$
                }
                return refusal.toJson();
            }

            if (autoFreeClients)
            {
                result.put("autoFreeClients", true); //$NON-NLS-1$
                result.put("freedClients", freedClients); //$NON-NLS-1$
            }
            if (workspaceRefresh != null)
            {
                result.put("workspaceRefresh", workspaceRefresh); //$NON-NLS-1$
            }

            return result.toJson();
        }
        catch (ApplicationException e)
        {
            Activator.logError("Failed to update database for application: " + applicationId, e); //$NON-NLS-1$

            ToolResult errorResult = ToolResult.error("Could not update the database: " + e.getMessage()); //$NON-NLS-1$
            errorResult.put("applicationId", applicationId); //$NON-NLS-1$
            errorResult.put("projectName", projectName); //$NON-NLS-1$
            putCauseChain(errorResult, e);
            if (autoFreeClients && freedClients != null)
            {
                errorResult.put("autoFreeClients", true); //$NON-NLS-1$
                errorResult.put("freedClients", freedClients); //$NON-NLS-1$
            }
            putHolders(errorResult, applicationId, ownerNames);
            return errorResult.toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Unhandled exception while updating the database", e); //$NON-NLS-1$
            ToolResult errorResult = ToolResult.error("Unexpected failure: " + e.getMessage()); //$NON-NLS-1$
            putCauseChain(errorResult, e);
            if (autoFreeClients && freedClients != null)
            {
                errorResult.put("autoFreeClients", true); //$NON-NLS-1$
                errorResult.put("freedClients", freedClients); //$NON-NLS-1$
            }
            putHolders(errorResult, applicationId, ownerNames);
            return errorResult.toJson();
        }
        finally
        {
            if (infobaseClaim != null)
            {
                infobaseClaim.close();
            }
        }
    }

    /**
     * The export scan an update runs before it writes, or nothing for a dry run.
     * <p>
     * A dry run answers from what the environment already holds. The scan walks the whole
     * project, so a dry run does not run it and names it in {@code notCheckedInDryRun} instead.
     * </p>
     *
     * @param projectName the project about to be written to an infobase.
     * @param skip whether the caller asked to go ahead unchecked.
     * @param probe whether this call is a dry run.
     * @param scan the scan to run, {@link #refuseWhatTheInfobaseWillRefuse} outside tests.
     * @return the refusal as a JSON body, or <code>null</code> to go ahead
     */
    static String exportScanBefore(String projectName, boolean skip, boolean probe,
        java.util.function.BiFunction<String, Boolean, String> scan)
    {
        return probe ? null : scan.apply(projectName, Boolean.valueOf(skip));
    }

    /**
     * Stops an update the platform is going to refuse anyway, while the reason is still readable.
     * <p>
     * The scan exists for defects that pass every check EDT runs and then break the platform. Until
     * now nothing called it before an update: the caller learned of the defect from the platform, in
     * the platform's words, naming a file the project does not have - and on the measured case not
     * even that, because the update did not return and the answer was a timeout.
     * </p>
     * <p>
     * Opting out is the same word the .cf dump uses, {@code ignoreBranchBinding} being taken: pass
     * nothing and the scan runs. A scan that CANNOT run also blocks, for the same reason it blocks
     * the dump - a guard that did not run has cleared nothing.
     * </p>
     *
     * @param projectName the project about to be written to an infobase.
     * @param skip whether the caller asked to go ahead unchecked.
     * @return the refusal as a JSON body, or <code>null</code> to go ahead
     */
    private static String refuseWhatTheInfobaseWillRefuse(String projectName, boolean skip)
    {
        if (skip)
        {
            return null;
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            // Not this guard's business: the caller below reports an unknown project properly.
            return null;
        }
        ValidateForExportTool.ExportScan scan;
        try
        {
            scan = new ValidateForExportTool().scanForExport(project, null, null, 25);
        }
        catch (RuntimeException | LinkageError cannotScan)
        {
            return ToolResult.error("The pre-update scan could not run, so nothing vouches for this " //$NON-NLS-1$
                + "project: " + cannotScan + ". Run validate_for_export to see it in full, or pass " //$NON-NLS-1$
                + "skipValidation=true to update unchecked.").toJson(); //$NON-NLS-1$
        }
        if (scan.error != null)
        {
            return ToolResult.error("The pre-update scan could not complete, so nothing vouches for " //$NON-NLS-1$
                + "this project: " + scan.error + ". Run validate_for_export to see it in full, or " //$NON-NLS-1$
                + "pass skipValidation=true to update unchecked.").toJson(); //$NON-NLS-1$
        }
        if (scan.findingsCount == 0)
        {
            return null;
        }
        return ToolResult.error(scan.findingsCount + " export-breaker(s) found - the database was " //$NON-NLS-1$
            + "NOT updated. These pass get_project_errors and are refused by the platform. Fix " //$NON-NLS-1$
            + "them, or pass skipValidation=true to update unchecked.")
            .put("findingsCount", scan.findingsCount) //$NON-NLS-1$
            .put("findings", scan.findings) //$NON-NLS-1$
            .put("findingsShown", scan.findings.size()) //$NON-NLS-1$
            .put("findingsLimited", scan.limited) //$NON-NLS-1$
            .toJson();
    }

    /**
     * Waits for an update EDT reported as still running, while the claim is still held.
     *
     * @param appManager the application manager.
     * @param application the application being updated.
     * @return the state it settled on, or BEING_UPDATED when the wait ran out
     */
    private static ApplicationUpdateState awaitUpdateEnd(IApplicationManager appManager,
        IApplication application)
    {
        long deadline = System.currentTimeMillis() + BEING_UPDATED_WAIT_MS;
        ApplicationUpdateState state = ApplicationUpdateState.BEING_UPDATED;
        while (System.currentTimeMillis() < deadline)
        {
            try
            {
                Thread.sleep(BEING_UPDATED_POLL_MS);
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
                return state;
            }
            try
            {
                state = appManager.getUpdateState(application);
            }
            catch (Exception cannotAsk)
            {
                // The manager will not say. Holding the claim on the strength of a question that
                // cannot be asked would keep the infobase locked for no reason anybody could
                // check, so the wait ends here and the caller is told what state was last seen.
                Activator.logWarning("Could not read the update state while waiting: " //$NON-NLS-1$
                    + cannotAsk.getMessage());
                return state;
            }
            if (state != ApplicationUpdateState.BEING_UPDATED)
            {
                return state;
            }
        }
        return ApplicationUpdateState.BEING_UPDATED;
    }

    /**
     * Whether the branch says a different infobase than the call names.
     * <p>
     * Only when somebody has said so. With no binding there is nothing to disagree with, and this
     * must not start refusing updates on projects that never asked it to - a guard that fires on
     * its own guesses is worse than none.
     * </p>
     * <p>
     * Two projects, and which one is asked first matters. An extension has no infobase of its own,
     * so the update is routed to the configuration it extends - but the developer works in the
     * EXTENSION, on the extension's branch, and that is the project they bound. Reading only the
     * infobase project meant an extension's binding was never consulted at all: it could be set,
     * reported back, and have no effect whatsoever.
     * </p>
     *
     * @param project the project the call named - the extension, where one is in play.
     * @param infobaseProject the project that owns the infobase, which may be the same one.
     * @param applicationId the application the call resolved to.
     * @param ignoreBranchBinding whether the caller said to go ahead anyway.
     * @return the refusal, or {@code null} when there is nothing to object to
     */
    private static String branchMismatch(IProject project, IProject infobaseProject,
        String applicationId, boolean ignoreBranchBinding)
    {
        String refusal = bindingConflict(project, applicationId, ignoreBranchBinding);
        if (refusal == null && infobaseProject != null && !infobaseProject.equals(project))
        {
            refusal = bindingConflict(infobaseProject, applicationId, ignoreBranchBinding);
        }
        return refusal;
    }

    /**
     * Makes the workspace hear about what the disk holds, before an update decision is read from
     * the model. A file written outside this server - a file tool, git checkout, a pull - is
     * invisible to the model until then, and the update decision below answers against the model:
     * Done or UPDATED over an update that never carried the change. This is the refresh a caller
     * would have had to know to ask for; done here, it cannot be forgotten. The count it reports
     * is what this re-read actually changed - resources added, removed, replaced or whose content
     * moved into the model - so 0 means the model already matched the disk.
     * <p>
     * The platform's {@code refreshLocal} answers nothing about what it found, so the number is
     * measured rather than asked for: a POST_CHANGE listener counts leaf deltas delivered in the
     * calling thread while it is inside {@code refreshLocal}. The platform delivers that operation's
     * notification synchronously before returning. The listener is taken down in a closing step
     * whatever the refresh did - a failed one included.
     * </p>
     *
     * @param project the project being updated
     * @param infobaseProject the project that owns the infobase - the parent, for an extension,
     *            which is where the change usually is
     * @return the report: projects touched, resources changed, any failure noted rather than thrown
     */
    static JsonObject refreshFromDisk(IProject project, IProject infobaseProject)
    {
        JsonObject report = new JsonObject();
        java.util.LinkedHashSet<String> touched = new java.util.LinkedHashSet<>();
        java.util.Set<IProject> watched = new java.util.LinkedHashSet<>();
        for (IProject each : new IProject[] { project, infobaseProject })
        {
            if (each != null && each.isAccessible() && !touched.contains(each.getName()))
            {
                touched.add(each.getName());
                watched.add(each);
            }
        }
        String failure = null;
        RefreshChangeCounter counter = new RefreshChangeCounter(watched);
        counter.register();
        try
        {
            for (IProject each : watched)
            {
                counter.beginRefresh();
                try
                {
                    each.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
                }
                catch (Exception e)
                {
                    failure = (failure == null ? "" : failure + "; ") //$NON-NLS-1$ //$NON-NLS-2$
                        + each.getName() + ": " + e.getMessage(); //$NON-NLS-1$
                }
                finally
                {
                    counter.endRefresh();
                }
            }
        }
        finally
        {
            counter.unregister();
        }
        JsonArray names = new JsonArray();
        for (String name : touched)
        {
            names.add(name);
        }
        report.add("projects", names); //$NON-NLS-1$
        report.addProperty("changedResources", Integer.valueOf(counter.changed())); //$NON-NLS-1$
        if (failure != null)
        {
            report.addProperty("refreshError", failure); //$NON-NLS-1$
        }
        return report;
    }

    /**
     * How many refresh counters are registered right now. Exists for the test that says a failed
     * refresh takes its listener down with it: a counter left behind would keep counting other
     * calls' events into a report nobody reads.
     *
     * @return the number of registered counters
     */
    static int activeRefreshCounters()
    {
        return RefreshChangeCounter.ACTIVE.get();
    }

    /**
     * Counts the resource deltas one refresh produced.
     * <p>
     * {@code IResource.refreshLocal} returns nothing about what it found, so the number the
     * report promises is measured here: POST_CHANGE events delivered synchronously in the thread
     * executing {@code refreshLocal} are watched, and their changed leaf resources are counted.
     * Events from every other thread are ignored, including traffic in the same project while the
     * refresh runs.
     * </p>
     */
    static final class RefreshChangeCounter implements IResourceChangeListener
    {
        /** The counters registered right now; see {@link DatabaseUpdater#activeRefreshCounters()}. */
        private static final AtomicInteger ACTIVE = new AtomicInteger();

        private final Set<String> watched = new LinkedHashSet<>();

        private final AtomicInteger changed = new AtomicInteger();

        private volatile Thread refreshThread;

        /**
         * Binds the counter to the projects whose deltas count.
         *
         * @param watched the projects the refresh is asked about
         */
        RefreshChangeCounter(Set<IProject> watched)
        {
            for (IProject project : watched)
            {
                this.watched.add(project.getName());
            }
        }

        /** Marks the calling thread as being inside one watched {@code refreshLocal} call. */
        void beginRefresh()
        {
            refreshThread = Thread.currentThread();
        }

        /** Stops attributing events to the call that just returned or failed. */
        void endRefresh()
        {
            refreshThread = null;
        }

        /** Starts watching the workspace for the deltas the refresh is about to cause. */
        void register()
        {
            ResourcesPlugin.getWorkspace().addResourceChangeListener(this, IResourceChangeEvent.POST_CHANGE);
            ACTIVE.incrementAndGet();
        }

        /**
         * Stops watching. Runs in a closing step whatever the refresh did: a listener left
         * behind would keep counting into a report whose call was already answered.
         */
        void unregister()
        {
            ResourcesPlugin.getWorkspace().removeResourceChangeListener(this);
            ACTIVE.decrementAndGet();
        }

        /**
         * What the refresh changed: leaf resources it added, removed, replaced, or whose content
         * moved into the model.
         *
         * @return the count
         */
        int changed()
        {
            return changed.get();
        }

        /**
         * Walks one notification batch and counts changed leaf deltas of any resource kind, and
         * every node whose kind of resource was replaced - a folder that became a file carries
         * the removal of what it held as its children, and the replacement itself is only on the
         * node.
         *
         * @param event the POST_CHANGE notification
         */
        @Override
        public void resourceChanged(IResourceChangeEvent event)
        {
            if (Thread.currentThread() != refreshThread)
            {
                return;
            }
            IResourceDelta delta = event.getDelta();
            if (delta == null)
            {
                return;
            }
            try
            {
                delta.accept(child -> {
                    IResource resource = child.getResource();
                    if (resource instanceof IProject && !watched.contains(resource.getName()))
                    {
                        return false;
                    }
                    int kind = child.getKind();
                    if (child.getAffectedChildren().length != 0)
                    {
                        if ((kind & IResourceDelta.CHANGED) != 0
                            && (child.getFlags() & (IResourceDelta.TYPE | IResourceDelta.REPLACED)) != 0)
                        {
                            changed.incrementAndGet();
                        }
                        return true;
                    }
                    boolean counts = (kind & (IResourceDelta.ADDED | IResourceDelta.REMOVED)) != 0
                        || (kind & IResourceDelta.CHANGED) != 0
                            && (child.getFlags()
                                & (IResourceDelta.CONTENT | IResourceDelta.TYPE | IResourceDelta.REPLACED)) != 0;
                    if (counts)
                    {
                        changed.incrementAndGet();
                    }
                    return false;
                });
            }
            catch (CoreException walkFailed)
            {
                // A tree that cannot be walked leaves a partial count; the refresh error beside
                // it says more than throwing the answer away would.
                Activator.logWarning("Refresh change count lost part of the delta tree: " //$NON-NLS-1$
                    + walkFailed.getMessage());
            }
        }
    }

    /**
     * The conflict one project's bindings have with this call, if any.
     *
     * @param project the project whose bindings and branch are read.
     * @param applicationId the application the call resolved to.
     * @param ignoreBranchBinding whether the caller said to go ahead anyway.
     * @return the refusal, or {@code null}
     */
    private static String bindingConflict(IProject project, String applicationId,
        boolean ignoreBranchBinding)
    {
        if (ignoreBranchBinding)
        {
            return null;
        }
        boolean hasRules = !BranchInfobaseBook.all(project).isEmpty();
        if (!BranchInfobaseBook.readable(project))
        {
            // The file is there and cannot be read. Refusing is the fail-CLOSED choice and it is
            // the right one here: the rules exist, this server cannot see them, and the operation
            // on the other side of the question rebuilds tables irreversibly.
            return "This project has branch bindings (.settings/" + BranchInfobaseBook.FILE //$NON-NLS-1$
                + ") that cannot be read, so there is no way to tell whether " + applicationId //$NON-NLS-1$
                + " is the right infobase for the current branch. Repair the file, or pass " //$NON-NLS-1$
                + "ignoreBranchBinding=true to update without the check."; //$NON-NLS-1$
        }
        String branch = GitBranch.of(project);
        if (!GitBranch.isBranch(branch))
        {
            if (!hasRules)
            {
                return null;
            }
            // Bindings exist but the branch does not - a detached head, or a HEAD this cannot read.
            // Passing silently would apply exactly none of the rules the project asked for.
            return "This project has branch bindings, but its current branch cannot be determined" //$NON-NLS-1$
                + (branch == null ? " (it is not in a git repository, or HEAD is unreadable)." //$NON-NLS-1$
                    : " - " + branch + ".") //$NON-NLS-1$ //$NON-NLS-2$
                + " Check out a branch, or pass ignoreBranchBinding=true to update without the " //$NON-NLS-1$
                + "check."; //$NON-NLS-1$
        }
        String bound = BranchInfobaseBook.boundTo(project, branch);
        if (bound == null || bound.equals(applicationId))
        {
            return null;
        }
        return "Branch " + branch + " is bound to application " + bound + ", and this call would " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "update " + applicationId + " instead. Updating the wrong infobase restructures its " //$NON-NLS-1$ //$NON-NLS-2$
            + "tables to match a configuration it was not built from, and that is not undone. " //$NON-NLS-1$
            + "Either update " + bound + ", or change the binding with branch_infobase, or pass " //$NON-NLS-1$ //$NON-NLS-2$
            + "ignoreBranchBinding=true if this really is what you want."; //$NON-NLS-1$
    }

    /**
     * Names who is holding the infobase, when anyone visible is.
     * <p>
     * A refused update says what the platform said, and the platform does not say who is in the
     * way. Left at that, a held base and a broken tool look identical from outside, and the reader
     * goes looking in the wrong place.
     * </p>
     *
     * @param result the answer being built.
     * @param applicationId the application whose infobase is concerned.
     * @param ownerNames the projects that own it.
     */
    private static void putHolders(ToolResult result, String applicationId, Set<String> ownerNames)
    {
        Map<String, Object> holders = InfobaseHolders.describe(applicationId, ownerNames);
        if (holders != null)
        {
            result.put("infobaseHolders", holders); //$NON-NLS-1$
        }
    }

    /**
     * Appends the full exception cause chain to the error result. The actionable root cause (e.g. a
     * platform-not-supported message) is often several levels deep and was previously visible only in
     * the workspace {@code .log}; surfacing it here turns blind diagnosis into a read.
     *
     * @param result the error result to enrich
     * @param error the thrown error
     */
    private static void putCauseChain(ToolResult result, Throwable error)
    {
        Throwable cause = error.getCause();
        if (cause == null)
        {
            return;
        }
        result.put("causeMessage", cause.getMessage()); //$NON-NLS-1$
        result.put("causeType", cause.getClass().getSimpleName()); //$NON-NLS-1$

        StringBuilder chain = new StringBuilder();
        Throwable root = cause;
        int guard = 0;
        while (cause != null && guard++ < CAUSE_CHAIN_DEPTH)
        {
            if (chain.length() > 0)
            {
                chain.append(" <- "); //$NON-NLS-1$
            }
            chain.append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null)
            {
                chain.append(": ").append(cause.getMessage()); //$NON-NLS-1$
            }
            root = cause;
            cause = cause.getCause();
        }

        result.put("causeChain", chain.toString()); //$NON-NLS-1$
        result.put("rootCauseType", root.getClass().getSimpleName()); //$NON-NLS-1$
        if (root.getMessage() != null)
        {
            result.put("rootCauseMessage", root.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Stops this application's own EDT-tracked runtime-client launches so a held infobase does not fail
     * the update. A launch is touched only when it resolves the target {@code applicationId}, its
     * config is a runtime-client type, AND its config belongs to {@code projectName}. The applicationId
     * is infobase-scoped, so the project + type checks stop a different project's client on the same
     * infobase - or an Attach session - from being touched. Each {@code terminate()} is async, so this
     * waits (bounded) for the launch to actually die before returning.
     *
     * @param applicationId the application whose clients to free
     * @param projectNames the projects whose launches count as ours to free
     * @return one row per launch that was considered, saying what happened
     */
    private static List<Map<String, Object>> freeClientsForApplication(String applicationId,
        Set<String> projectNames)
    {
        List<Map<String, Object>> freed = new ArrayList<>();
        DebugPlugin debugPlugin = DebugPlugin.getDefault();
        ILaunchManager manager = debugPlugin != null ? debugPlugin.getLaunchManager() : null;
        if (manager == null)
        {
            return freed;
        }

        for (ILaunch launch : manager.getLaunches())
        {
            if (launch.isTerminated())
            {
                continue;
            }
            String appId = DebugSessionBook.findApplicationIdFor(launch);
            if (appId == null || !applicationId.equals(appId))
            {
                continue; // not an EDT launch for this application - never touch
            }
            ILaunchConfiguration cfg = launch.getLaunchConfiguration();
            if (cfg == null)
            {
                continue; // cannot verify project/type - do not risk terminating it
            }
            if (!LaunchConfigAccess.LAUNCH_CONFIG_TYPE_ID.equals(LaunchConfigAccess.getConfigTypeId(cfg)))
            {
                continue;
            }
            if (!projectNames.contains(
                LaunchConfigAccess.readAttribute(cfg, LaunchConfigAccess.ATTR_PROJECT_NAME, ""))) //$NON-NLS-1$
            {
                continue;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("launchConfiguration", cfg.getName()); //$NON-NLS-1$
            row.put("mode", launch.getLaunchMode()); //$NON-NLS-1$

            if (!launch.canTerminate())
            {
                row.put("terminated", false); //$NON-NLS-1$
                row.put("note", "the launch reports canTerminate=false, so it cannot be stopped"); //$NON-NLS-1$ //$NON-NLS-2$
                freed.add(row);
                continue;
            }

            try
            {
                launch.terminate();
                long deadline = System.currentTimeMillis() + FREE_CLIENT_WAIT_MS;
                while (!launch.isTerminated() && System.currentTimeMillis() < deadline)
                {
                    try
                    {
                        Thread.sleep(FREE_CLIENT_POLL_MS);
                    }
                    catch (InterruptedException ie)
                    {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                row.put("terminated", launch.isTerminated()); //$NON-NLS-1$
                // Guarded: clearSnapshot is bookkeeping. If the registry is unavailable in a degraded
                // OSGi state, the launch still terminated - report the true result, do not let a NPE
                // here overwrite it with terminated=false.
                DebugSessionBook snapshotStore = DebugSessionBook.get();
                if (snapshotStore != null)
                {
                    snapshotStore.clearSnapshot(appId);
                }
            }
            catch (Exception termEx)
            {
                row.put("terminated", false); //$NON-NLS-1$
                row.put("error", termEx.getMessage() != null ? termEx.getMessage() //$NON-NLS-1$
                    : termEx.getClass().getSimpleName());
            }
            freed.add(row);
        }
        return freed;
    }
}
