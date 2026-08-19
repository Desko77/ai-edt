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

import org.eclipse.core.resources.IProject;
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

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.support.TimeoutArgs;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BmCommonModuleGuards;
import ru.aiedt.mcp.server.support.DebugSessionBook;
import ru.aiedt.mcp.server.support.ErrorTags;
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
 * application id pair.
 * </p>
 */
public class DatabaseUpdater implements IMcpTool
{
    public static final String NAME = "update_database"; //$NON-NLS-1$

    private static final int MIN_TIMEOUT_SECONDS = 5;
    private static final int MAX_TIMEOUT_SECONDS = 120;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** Cap on how deep a cause-chain we walk to find the actionable root. */
    private static final int CAUSE_CHAIN_DEPTH = 20;
    /** How long to wait (per launch) for a freed client to actually die before the update runs. */
    private static final long FREE_CLIENT_WAIT_MS = 10_000L;
    private static final long FREE_CLIENT_POLL_MS = 200L;

    @Override
    public String getName()
    {
        return NAME;
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
            .booleanProperty("fullUpdate", "true triggers a full reload; false runs an incremental update instead (default: false)") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("autoRestructure", "Apply infobase restructuring automatically when it is required (default: true)") //$NON-NLS-1$ //$NON-NLS-2$
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
                    + "(identical params also reproduce the same runKey). All other params are ignored once " //$NON-NLS-1$
                    + "runKey is supplied. A fresh call without runKey always executes again - it never returns a stale " //$NON-NLS-1$
                    + "cached result.") //$NON-NLS-1$
            .booleanProperty("cancel", //$NON-NLS-1$
                "Combined with runKey: detach and stop tracking that update. BEST-EFFORT only - it makes the server stop " //$NON-NLS-1$
                    + "waiting on and caching the result, but does NOT guarantee that an update already " //$NON-NLS-1$
                    + "in progress against the infobase gets aborted (a structural update can keep running and still commit).") //$NON-NLS-1$
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
        String runKeyParam = JsonUtils.extractStringArgument(params, "runKey"); //$NON-NLS-1$
        if (runKeyParam != null && !runKeyParam.isEmpty())
        {
            boolean cancel = JsonUtils.extractBooleanArgument(params, "cancel", false); //$NON-NLS-1$ //$NON-NLS-2$
            if (cancel)
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
            return resumePending(runKeyParam, params);
        }

        String configName = JsonUtils.extractStringArgument(params, "launchConfigurationName"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        boolean fullUpdate = JsonUtils.extractBooleanArgument(params, "fullUpdate", false); //$NON-NLS-1$ //$NON-NLS-2$
        boolean autoRestructure = JsonUtils.extractBooleanArgument(params, "autoRestructure", true); //$NON-NLS-1$ //$NON-NLS-2$
        boolean autoFreeClients = JsonUtils.extractBooleanArgument(params, "autoFreeClients", false); //$NON-NLS-1$ //$NON-NLS-2$

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
        // state from these params, so no live EDT handle crosses the thread boundary.
        final String fProjectName = projectName;
        final String fApplicationId = applicationId;
        final boolean fFull = fullUpdate;
        final boolean fRestr = autoRestructure;
        final boolean fFree = autoFreeClients;
        String runKey = PendingWorkRegistry.computeRunKey(fProjectName, fApplicationId,
            String.valueOf(fFull), String.valueOf(fRestr), String.valueOf(fFree));
        long timeoutMs = TimeoutArgs.readSeconds(params, DEFAULT_TIMEOUT_SECONDS,
            MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS) * 1000L;

        PendingWorkRegistry registry = PendingWorkRegistry.UPDATE;
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
        PendingWorkRegistry.PendingEntry entry = registry.getOrStart(runKey,
            () -> updateDatabase(fProjectName, fApplicationId, fFull, fRestr, fFree));

        String result = entry.await(timeoutMs);
        if (result != null)
        {
            registry.remove(runKey);
            return result;
        }
        return buildPendingJson(runKey, entry, fProjectName, timeoutMs);
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
     * Builds the Pending body returned when the wait budget runs out before the update finishes.
     *
     * @param runKey the key to resume with
     * @param entry the in-flight update
     * @param projectName the project, or {@code null} to omit
     * @param timeoutMs how long was waited
     * @return a JSON Pending body
     */
    private String buildPendingJson(String runKey, PendingWorkRegistry.PendingEntry entry, String projectName,
        long timeoutMs)
    {
        ToolResult body = ToolResult.success()
            .put("operation", NAME) //$NON-NLS-1$
            .put("status", "Pending") //$NON-NLS-1$ //$NON-NLS-2$
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
     * @return a JSON result body
     */
    private String updateDatabase(String projectName, String requestedApplicationId, boolean fullUpdate,
        boolean autoRestructure, boolean autoFreeClients)
    {
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
                return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
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

            ApplicationUpdateState stateBefore = appManager.getUpdateState(application);
            if (stateBefore == ApplicationUpdateState.BEING_UPDATED)
            {
                return ToolResult.error("This application has an update already in progress - wait for it to finish.").toJson(); //$NON-NLS-1$
            }

            // A neighbouring EDT updating the SAME infobase is the collision this catches. EDT's own
            // BEING_UPDATED state is per-instance and says nothing about the process next door; what
            // that looks like without a claim is a platform error about a locked configuration, or a
            // wait that ends in a timeout - neither of which names anybody.
            String infobaseIdentity = InfobaseIdentity.of(application);
            java.util.Optional<MonopolyLock> claim =
                MonopolyLock.take(infobaseIdentity, "update_database"); //$NON-NLS-1$
            infobaseClaim = claim.orElse(null);
            if (infobaseIdentity != null && claim.isEmpty())
            {
                ToolResult taken = ToolResult.error("Another AI-EDT instance is working on this " //$NON-NLS-1$
                    + "infobase. " + MonopolyLock.heldBy(infobaseIdentity)); //$NON-NLS-1$
                taken.put("tag", ErrorTags.BUSY.wire()); //$NON-NLS-1$
                taken.put("applicationId", applicationId); //$NON-NLS-1$
                putHolders(taken, applicationId, ownerNames);
                return taken.toJson();
            }

            ApplicationUpdateType updateType =
                fullUpdate ? ApplicationUpdateType.FULL : ApplicationUpdateType.INCREMENTAL;

            // The shell is wanted for any modal EDT pops; grab the active one off the UI thread.
            ExecutionContext context = new ExecutionContext();
            Display display = Display.getDefault();
            if (display != null && !display.isDisposed())
            {
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
            }

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

            ApplicationUpdateState stateAfter = appManager.update(application, updateType, context, monitor);

            boolean updateComplete = stateAfter == ApplicationUpdateState.UPDATED;
            ToolResult result = ToolResult.success()
                .put("project", projectName) //$NON-NLS-1$
                .put("applicationId", applicationId) //$NON-NLS-1$
                .put("applicationName", application.getName()) //$NON-NLS-1$
                .put("updateType", updateType.name()) //$NON-NLS-1$
                .put("stateBefore", stateBefore.name()) //$NON-NLS-1$
                .put("stateAfter", stateAfter.name()) //$NON-NLS-1$
                .put("updateComplete", updateComplete); //$NON-NLS-1$

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
                result.put("message", "Update finished; final state: " + stateAfter.name()); //$NON-NLS-1$ //$NON-NLS-2$
                result.put("updateIncomplete", "The update did NOT reach UPDATED (stateAfter=" //$NON-NLS-1$ //$NON-NLS-2$
                    + stateAfter.name() + "). success:true here only means the update call returned " //$NON-NLS-1$
                    + "without throwing, NOT that the infobase is fully updated. Double-check via " //$NON-NLS-1$
                    + "get_applications and re-run update_database if that matters."); //$NON-NLS-1$
            }

            if (autoFreeClients)
            {
                result.put("autoFreeClients", true); //$NON-NLS-1$
                result.put("freedClients", freedClients); //$NON-NLS-1$
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
