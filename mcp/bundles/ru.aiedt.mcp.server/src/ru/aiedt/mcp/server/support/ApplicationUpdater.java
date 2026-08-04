/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com._1c.g5.v8.dt.lifecycle.LifecycleService;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.ApplicationUpdateType;
import com.e1c.g5.dt.applications.ExecutionContext;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

import ru.aiedt.mcp.server.Activator;
// LaunchConfigAccess lives in same package - no import needed

/**
 * Single source of truth for "update infobase before launch" logic across
 * MCP tools (DebugSessionStarter, DatabaseUpdater, future YaxunitTestsTool).
 * <p>
 * 1.40 enhancements over the legacy {@code DebugSessionStarter.updateDatabase}:
 * <ul>
 *   <li>{@code FULL_UPDATE_REQUIRED} state auto-switches the request to
 *       {@link ApplicationUpdateType#FULL} (legacy code always sent INCREMENTAL,
 *       which fails on structural changes - users had to manually rebuild)</li>
 *   <li>{@code BEING_UPDATED} state surfaced as a distinct return code so callers
 *       can choose to wait (Pending semantics) instead of failing</li>
 *   <li>{@link Result} record carries both outcome and human-friendly hint;
 *       callers don't have to parse strings</li>
 * </ul>
 *
 * Used by:
 * <ul>
 *   <li>{@code DebugSessionStarter} - {@code updateBeforeLaunch} option for debug session start</li>
 *   <li>{@code DatabaseUpdater} - dedicated "Update infobase" tool exposed via MCP</li>
 *   <li>{@code YaxunitTestsTool} (1.40) - automatic pre-test sync to avoid the
 *       modal "Update configuration?" dialog blocking unattended test runs</li>
 * </ul>
 */
public final class ApplicationUpdater
{
    /**
     * Outcome of an update attempt.
     */
    public enum Outcome
    {
        /** No update was needed (state was UPDATED). */
        ALREADY_UP_TO_DATE,
        /** Another process is currently updating - caller should wait or retry. */
        BEING_UPDATED_BY_ANOTHER,
        /** Update completed successfully. */
        UPDATED,
        /** Update completed but state is still not UPDATED (rare, escalation needed). */
        UPDATED_PARTIAL,
        /** Application not found in project. */
        APPLICATION_NOT_FOUND,
        /** Service unavailable (plugin shutting down or EDT bundle missing). */
        SERVICE_UNAVAILABLE,
        /** Project not open or invalid input. */
        SKIPPED,
        /** Update failed with an exception. */
        FAILED
    }

    /**
     * Result of an update attempt.
     */
    public static final class Result
    {
        public final Outcome outcome;
        public final ApplicationUpdateState stateBefore;
        public final ApplicationUpdateState stateAfter;
        public final ApplicationUpdateType updateTypeUsed;
        public final String errorMessage;
        public final String hint;

        private Result(Outcome outcome, ApplicationUpdateState before, ApplicationUpdateState after,
            ApplicationUpdateType type, String errorMessage, String hint)
        {
            this.outcome = outcome;
            this.stateBefore = before;
            this.stateAfter = after;
            this.updateTypeUsed = type;
            this.errorMessage = errorMessage;
            this.hint = hint;
        }

        /** True if the database is up to date after this call (regardless of whether work was done). */
        public boolean isUpToDate()
        {
            return outcome == Outcome.ALREADY_UP_TO_DATE || outcome == Outcome.UPDATED;
        }

        /** True if caller should fail-fast (no point retrying without intervention). */
        public boolean isHardFailure()
        {
            return outcome == Outcome.FAILED || outcome == Outcome.APPLICATION_NOT_FOUND;
        }

        @Override
        public String toString()
        {
            StringBuilder sb = new StringBuilder("ApplicationUpdater.Result{outcome=").append(outcome);
            if (stateBefore != null) sb.append(", previousState=").append(stateBefore);
            if (stateAfter != null) sb.append(", stateAfter=").append(stateAfter);
            if (updateTypeUsed != null) sb.append(", updateType=").append(updateTypeUsed);
            if (errorMessage != null) sb.append(", error=").append(errorMessage);
            sb.append('}');
            return sb.toString();
        }

        static Result skipped(String reason)
        {
            return new Result(Outcome.SKIPPED, null, null, null, null, reason);
        }

        static Result serviceUnavailable(String detail)
        {
            return new Result(Outcome.SERVICE_UNAVAILABLE, null, null, null, detail,
                "EDT applications service is missing - plugin may be shutting down or EDT bundle "
                    + "'com.e1c.g5.dt.applications' is not available.");
        }

        static Result appNotFound(String applicationId, String projectName)
        {
            return new Result(Outcome.APPLICATION_NOT_FOUND, null, null, null,
                "No application named '" + applicationId + "' in project '" + projectName + "'",
                "Use list_applications to refresh the list of registered infobases.");
        }

        static Result alreadyUpToDate(ApplicationUpdateState state)
        {
            return new Result(Outcome.ALREADY_UP_TO_DATE, state, state, null, null,
                "Database is already in sync with the project (state=" + state + ").");
        }

        static Result beingUpdated(ApplicationUpdateState state)
        {
            return new Result(Outcome.BEING_UPDATED_BY_ANOTHER, state, state, null, null,
                "Database is currently being updated by another process. Retry shortly.");
        }

        static Result success(ApplicationUpdateState before, ApplicationUpdateState after,
            ApplicationUpdateType typeUsed)
        {
            Outcome o = (after == ApplicationUpdateState.UPDATED) ? Outcome.UPDATED : Outcome.UPDATED_PARTIAL;
            return new Result(o, before, after, typeUsed, null,
                "Database updated (" + typeUsed + ") from state " + before + " to " + after + ".");
        }

        static Result failed(String message)
        {
            return new Result(Outcome.FAILED, null, null, null, message,
                "You can retry with updateBeforeLaunch=false to skip update, or rebuild the project manually in EDT.");
        }
    }

    private ApplicationUpdater()
    {
        // utility
    }

    /**
     * Updates the infobase if needed, resolving the project + application id first.
     * Returns a {@link Result} describing the outcome - never throws.
     *
     * @param projectName   workspace project name (must be open)
     * @param applicationId target infobase id (skipped for attach-mode app ids)
     * @return outcome record
     */
    public static Result updateIfNeeded(String projectName, String applicationId)
    {
        if (applicationId == null || applicationId.isEmpty())
        {
            return Result.skipped("applicationId is empty");
        }
        if (applicationId.startsWith(LaunchConfigAccess.ATTACH_APP_ID_PREFIX))
        {
            return Result.skipped("attach-mode launch - update not applicable");
        }
        if (projectName == null || projectName.isEmpty())
        {
            return Result.skipped("projectName is empty");
        }
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject project = workspace.getRoot().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen())
        {
            return Result.skipped("project not open: " + projectName);
        }
        IApplicationManager appManager = Activator.getDefault().getApplicationManager();
        if (appManager == null)
        {
            return Result.serviceUnavailable("IApplicationManager is null");
        }
        try
        {
            Optional<IApplication> appOpt = appManager.getApplication(project, applicationId);
            if (!appOpt.isPresent())
            {
                return Result.appNotFound(applicationId, projectName);
            }
            return updateIfNeeded(appManager, appOpt.get());
        }
        catch (ApplicationException e)
        {
            Activator.logError("Application resolution failed for DB update", e);
            return Result.failed("Error resolving application: " + e.getMessage());
        }
    }

    /**
     * Updates the infobase if needed (low-level). Skips when state is UPDATED,
     * surfaces BEING_UPDATED, and auto-switches to FULL when state is FULL_UPDATE_REQUIRED.
     */
    public static Result updateIfNeeded(IApplicationManager appManager, IApplication application)
    {
        try
        {
            ApplicationUpdateState stateBefore = appManager.getUpdateState(application);
            if (stateBefore == ApplicationUpdateState.UPDATED)
            {
                return Result.alreadyUpToDate(stateBefore);
            }
            if (stateBefore == ApplicationUpdateState.BEING_UPDATED)
            {
                return Result.beingUpdated(stateBefore);
            }

            ApplicationUpdateType type = chooseUpdateType(stateBefore);

            Activator.logInfo("Updating database: application=" + application.getId() //$NON-NLS-1$
                + ", state=" + stateBefore + ", type=" + type);

            ExecutionContext context = buildExecutionContext();
            IProgressMonitor monitor = new NullProgressMonitor();
            ApplicationUpdateState stateAfter = appManager.update(application, type, context, monitor);
            Activator.logInfo("Database update completed: stateAfter=" + stateAfter); //$NON-NLS-1$
            return Result.success(stateBefore, stateAfter, type);
        }
        catch (ApplicationException e)
        {
            Activator.logError("Error updating database", e); //$NON-NLS-1$
            return Result.failed(e.getMessage());
        }
    }

    /**
     * Picks {@link ApplicationUpdateType#FULL} when the platform reports
     * FULL_UPDATE_REQUIRED (state name match - reflection-resilient against
     * EDT enum changes), otherwise INCREMENTAL.
     */
    private static ApplicationUpdateType chooseUpdateType(ApplicationUpdateState state)
    {
        if (state == null)
        {
            return ApplicationUpdateType.INCREMENTAL;
        }
        String stateName = state.name();
        // Conservative match - any state mentioning FULL is treated as requiring FULL.
        if (stateName.contains("FULL"))
        {
            return ApplicationUpdateType.FULL;
        }
        return ApplicationUpdateType.INCREMENTAL;
    }

    /**
     * Builds an {@link ExecutionContext} with the active SWT shell, so EDT
     * can route any modal prompts through it. When no UI thread is available
     * (e.g. server context), returns an empty context.
     */
    private static ExecutionContext buildExecutionContext()
    {
        ExecutionContext context = new ExecutionContext();
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
        {
            return context;
        }
        final Shell[] shellHolder = new Shell[1];
        try
        {
            display.syncExec(() -> {
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
        }
        catch (Exception ignored)
        {
            // Display might be disposing - context without shell is still usable.
        }
        if (shellHolder[0] != null)
        {
            context.setProperty(ExecutionContext.ACTIVE_SHELL_NAME, shellHolder[0]);
        }
        return context;
    }

    /**
     * Convenience legacy adapter: returns {@code null} on success or a
     * human-readable error string. Mimics the legacy
     * {@code DebugSessionStarter.updateDatabase} signature for incremental migration.
     */
    public static String updateDatabaseLegacy(IApplicationManager appManager, IApplication application)
    {
        Result r = updateIfNeeded(appManager, application);
        if (r.outcome == Outcome.FAILED)
        {
            return "Database update before launch failed: " + r.errorMessage
                + ". Retry with updateBeforeLaunch=false to bypass the update.";
        }
        return null;
    }

    /**
     * Suppresses the unused-import warning for {@link LifecycleService} that may
     * appear if EDT lifecycle utilities are not used directly.
     */
    @SuppressWarnings("unused")
    private static final Class<?> KEEP_LIFECYCLE_SERVICE_REFERENCE = LifecycleService.class;
}
