/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com._1c.g5.v8.dt.platform.services.core.dump.IExternalObjectDumpSupport;
import com._1c.g5.wiring.ServiceAccess;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.ApplicationUpdateType;
import com.e1c.g5.dt.applications.ExecutionContext;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.ModalDialogWatch;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.DebugSessionBook;
import ru.aiedt.mcp.server.support.LaunchConfigAccess;
import ru.aiedt.mcp.server.support.BmExternalObjectDumpHelper;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.ProjectStateGuard;
import ru.aiedt.mcp.server.support.TextSuggest;

/**
 * Starts an EDT debug session, either by naming an existing launch configuration (runtime client or
 * Attach) or by project and application, auto-creating a minimal runtime-client configuration when
 * one does not yet exist for the pair.
 */
public final class DebugSessionStarter implements IMcpTool
{
    public static final String NAME = "debug_launch"; //$NON-NLS-1$

    private static final String DESC = "Back-compat alias of `launch_debugger` `action=launch`; prefer the facade for new prompts. " //$NON-NLS-1$
        + "Starts an EDT debug session. " //$NON-NLS-1$
        + "Supply launchConfigurationName to run any existing EDT debug configuration by its name " //$NON-NLS-1$
        + "(either a runtime client or 'Attach to 1C:Enterprise Debug Server' - needed for debugging " //$NON-NLS-1$
        + "server-side code such as HTTP services, background jobs, and scheduled jobs). " //$NON-NLS-1$
        + "Otherwise supply projectName + applicationId to launch the matching runtime-client configuration; " //$NON-NLS-1$
        + "if none exists yet, a minimal runtime-client configuration is auto-created and saved " //$NON-NLS-1$
        + "for that project/application pair (reported back as autoCreatedConfiguration)."; //$NON-NLS-1$

    /**
     * Coarse guard closing the launch TOCTOU windows (inbox row 45): the
     * already-running check ({@code findActiveTarget} -> preflight ->
     * {@code performLaunch}) and the find/create/launch path were non-atomic, so
     * two near-simultaneous launches for the same target could both miss the
     * registering target / both create a persisted configuration. MCP execute is
     * normally sequential, so this lock is uncontended in practice - it is a
     * safety net that serializes the whole check-and-launch unit. Debug launches
     * are inherently exclusive (one live session per target), so a global lock is
     * preferable to a per-key map (simpler, no leak surface).
     */
    private static final java.util.concurrent.locks.ReentrantLock LAUNCH_LOCK =
        new java.util.concurrent.locks.ReentrantLock();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return DESC;
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "Name of the EDT project (required unless launchConfigurationName is supplied)") //$NON-NLS-1$
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application identifier from get_applications (required for runtime-client launches)") //$NON-NLS-1$
            .stringProperty("launchConfigurationName", //$NON-NLS-1$
                "Exact name of an EDT debug launch configuration, either runtime client or Attach. " //$NON-NLS-1$
                    + "Use this for Attach configurations or to select a specific client configuration by name.") //$NON-NLS-1$
            .booleanProperty("updateBeforeLaunch", //$NON-NLS-1$
                "true updates the database before launching (default: true; ignored for Attach)") //$NON-NLS-1$
            .integerProperty("debugServerPort", //$NON-NLS-1$
                "Debug server port for this launch only, 1..65535; the saved configuration is " //$NON-NLS-1$
                    + "not changed. Use when another 1C:EDT holds the default port, which the " //$NON-NLS-1$
                    + "environment refuses in a dialog. Zero: the environment decides. Attach " //$NON-NLS-1$
                    + "ignores it.") //$NON-NLS-1$
            .booleanProperty("enableExternalObjectDump", //$NON-NLS-1$
                "Turn on dump generation for the external-object project when it is off. The " //$NON-NLS-1$
                    + "environment opens such an object by building it, and with the setting off " //$NON-NLS-1$
                    + "the client starts empty. Default false: the launch is refused instead, " //$NON-NLS-1$
                    + "because this changes the project.", //$NON-NLS-1$
                false)
            .stringProperty("externalObjectName", //$NON-NLS-1$
                "Open this external data processor or report in the client that starts, so its code " //$NON-NLS-1$
                    + "runs under the debugger. The object is one this workspace holds as an " //$NON-NLS-1$
                    + "external-object project; a ready .epf / .erf from elsewhere goes in first " //$NON-NLS-1$
                    + "through config_io operation=import_external_object. Omit to open nothing.") //$NON-NLS-1$
            .stringProperty("externalObjectProject", //$NON-NLS-1$
                "The external-object project holding externalObjectName. Omit when the project has " //$NON-NLS-1$
                    + "one object and its name is unambiguous in the workspace.") //$NON-NLS-1$
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
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        String configName = JsonUtils.extractStringArgument(params, "launchConfigurationName"); //$NON-NLS-1$
        boolean updateBeforeLaunch = JsonUtils.extractBooleanArgument(params, "updateBeforeLaunch", true); //$NON-NLS-1$
        int debugServerPort = JsonUtils.extractIntArgument(params, "debugServerPort", 0); //$NON-NLS-1$
        if (debugServerPort != 0 && (debugServerPort < 1 || debugServerPort > 65535))
        {
            return ToolResult.error("debugServerPort must be between 1 and 65535, or omitted to let " //$NON-NLS-1$
                + "the environment choose. Nothing was launched.") //$NON-NLS-1$
                .put("debugServerPort", Integer.valueOf(debugServerPort)) //$NON-NLS-1$
                .put("nothingWasLaunchedOrUpdated", Boolean.TRUE) //$NON-NLS-1$
                .toJson();
        }
        boolean enableDump =
            JsonUtils.extractBooleanArgument(params, "enableExternalObjectDump", false); //$NON-NLS-1$
        String externalObjectName = JsonUtils.extractStringArgument(params, "externalObjectName"); //$NON-NLS-1$
        String externalObjectProject = JsonUtils.extractStringArgument(params, "externalObjectProject"); //$NON-NLS-1$

        if (configName != null && !configName.isEmpty())
        {
            return launchByConfigName(configName, updateBeforeLaunch, debugServerPort);
        }

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required unless you pass launchConfigurationName").toJson(); //$NON-NLS-1$
        }
        if (applicationId == null || applicationId.isEmpty())
        {
            return ToolResult
                .error("applicationId is required. Look up the application list via get_applications, "
                    + "or pass launchConfigurationName to start a configuration by name (e.g. an Attach configuration).")
                .toJson();
        }

        String notReadyError = ProjectStateGuard.checkReadyOrError(projectName);
        if (notReadyError != null)
        {
            return ToolResult.error(notReadyError).toJson();
        }

        return launchDebug(projectName, applicationId, updateBeforeLaunch,
            externalObjectProject, externalObjectName, debugServerPort, enableDump);
    }

    private String launchByConfigName(String configName, boolean updateBeforeLaunch,
        int debugServerPort)
    {
        LAUNCH_LOCK.lock();
        try
        {
            ILaunchManager launchManager = LaunchConfigAccess.getLaunchManager();
            if (launchManager == null)
            {
                return ToolResult.error("The Eclipse launch manager is unavailable right now").toJson(); //$NON-NLS-1$
            }

            ILaunchConfiguration config = LaunchConfigAccess.findLaunchConfigByName(launchManager, configName);
            if (config == null)
            {
                ToolResult err = ToolResult
                    .error("No launch configuration named '" + configName + "' was found. Create one in EDT first.");
                err.put("availableConfigurations", listAvailableConfigs(launchManager)); //$NON-NLS-1$
                return err.toJson();
            }

            String typeId = LaunchConfigAccess.getConfigTypeId(config);
            boolean isAttach = LaunchConfigAccess.isAttachConfigTypeId(typeId);
            String configProject =
                LaunchConfigAccess.readAttribute(config, LaunchConfigAccess.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
            String effectiveAppId = LaunchConfigAccess.getApplicationIdFor(config);

            if (effectiveAppId != null && DebugSessionBook.findActiveTarget(effectiveAppId) != null)
            {
                ToolResult already = ToolResult.success()
                    .put("launchConfiguration", config.getName()) //$NON-NLS-1$
                    .put("configurationType", typeId) //$NON-NLS-1$
                    .put("attach", isAttach) //$NON-NLS-1$
                    .put("applicationId", effectiveAppId) //$NON-NLS-1$
                    .put("alreadyRunning", true) //$NON-NLS-1$
                    .put("mode", "debug"); //$NON-NLS-1$ //$NON-NLS-2$
                already.put("message", //$NON-NLS-1$
                    "Launch configuration is already running - launch was skipped.");
                if (configProject != null && !configProject.isEmpty())
                {
                    already.put("project", configProject); //$NON-NLS-1$
                }
                return already.toJson();
            }

            if (!isAttach && updateBeforeLaunch && configProject != null && !configProject.isEmpty())
            {
                String notReady = ProjectStateGuard.checkReadyOrError(configProject);
                if (notReady != null)
                {
                    return ToolResult.error(notReady).toJson();
                }
                String updateError = updateDatabaseIfNeeded(configProject, effectiveAppId);
                if (updateError != null)
                {
                    return ToolResult.error(updateError).toJson();
                }
            }

            ILaunchConfiguration toLaunch = config;
            if (debugServerPort > 0 && !isAttach)
            {
                toLaunch = LaunchConfigAccess.listeningOnDebugPort(config, debugServerPort);
            }

            LaunchOutcome outcome = performLaunch(toLaunch, isAttach);
            if (!outcome.started)
            {
                return refusalFor(outcome, "Could not launch the debug session").toJson(); //$NON-NLS-1$
            }

            ToolResult result = ToolResult.success()
                .put("launchConfiguration", config.getName()) //$NON-NLS-1$
                .put("configurationType", typeId) //$NON-NLS-1$
                .put("attach", isAttach) //$NON-NLS-1$
                .put("mode", "debug"); //$NON-NLS-1$ //$NON-NLS-2$
            if (debugServerPort > 0)
            {
                result.put("debugServerPort", isAttach ? null : Integer.valueOf(debugServerPort)); //$NON-NLS-1$
                if (isAttach)
                {
                    result.put("debugServerPortNote", //$NON-NLS-1$
                        "An Attach configuration starts no debug server, so debugServerPort was not " //$NON-NLS-1$
                            + "applied. It connects to the debug server named by the configuration."); //$NON-NLS-1$
                }
            }
            result.put("message", isAttach //$NON-NLS-1$
                ? "Attach debug session started - use debug_status to check on it, "
                    + "or wait_for_break to block until a breakpoint fires."
                : "Debug session is now running");
            if (configProject != null && !configProject.isEmpty())
            {
                result.put("project", configProject); //$NON-NLS-1$
            }
            if (effectiveAppId != null)
            {
                result.put("applicationId", effectiveAppId); //$NON-NLS-1$
            }
            return result.toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Unhandled exception while launching debug session by name", e); //$NON-NLS-1$
            return ToolResult.error("Unhandled error: " + TextSuggest.safeMessage(e)).toJson();
        }
        finally
        {
            LAUNCH_LOCK.unlock();
        }
    }

    private String launchDebug(String projectName, String applicationId,
        boolean updateBeforeLaunch, String externalObjectProject, String externalObjectName,
        int debugServerPort, boolean enableDump)
    {
        LAUNCH_LOCK.lock();
        try
        {
            IProject project = ProjectResolver.resolve(projectName);
            if (project == null)
            {
                return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
            }

            IApplicationManager appManager = Activator.getDefault().getApplicationManager();
            String applicationName = applicationId;
            IApplication application = null;
            if (appManager != null)
            {
                try
                {
                    Optional<IApplication> appOpt = appManager.getApplication(project, applicationId);
                    if (!appOpt.isPresent())
                    {
                        return ToolResult.error("No application found for: " + applicationId
                            + ". Call get_applications to see the valid application IDs.").toJson();
                    }
                    application = appOpt.get();
                    applicationName = application.getName();
                }
                catch (ApplicationException e)
                {
                    Activator.logError("Failed to check application", e); //$NON-NLS-1$
                    // Continue - try to find a launch configuration anyway.
                }
            }

            // Before the update, not after: an object that cannot be resolved is a typo, and a typo
            // should not cost an infobase update and leave the caller with a changed database and no
            // launch.
            String openedObject = null;
            String objectProjectName = null;
            String openedObjectClassName = null;
            if (externalObjectName != null && !externalObjectName.isEmpty())
            {
                IProject objectProject = externalObjectProject == null || externalObjectProject.isEmpty()
                    ? project : ProjectResolver.resolve(externalObjectProject);
                if (objectProject == null)
                {
                    return ToolResult.error(ProjectResolver.describeNotFound(externalObjectProject)).toJson();
                }
                BmExternalObjectDumpHelper.RootResolution found =
                    BmExternalObjectDumpHelper.resolveRoot(objectProject, externalObjectName);
                if (found.error != null)
                {
                    return ToolResult.error(found.error)
                        .put("externalObjectName", externalObjectName) //$NON-NLS-1$
                        .put("externalObjectProject", objectProject.getName()) //$NON-NLS-1$
                        .put("nothingWasLaunchedOrUpdated", Boolean.TRUE) //$NON-NLS-1$
                        .toJson();
                }
                String dumpProblem = dumpReadiness(objectProject, enableDump);
                if (dumpProblem != null)
                {
                    return ToolResult.error(dumpProblem)
                        .put("externalObjectName", externalObjectName) //$NON-NLS-1$
                        .put("externalObjectProject", objectProject.getName()) //$NON-NLS-1$
                        .put("nothingWasLaunchedOrUpdated", Boolean.TRUE) //$NON-NLS-1$
                        .toJson();
                }
                openedObject = found.objectName;
                objectProjectName = objectProject.getName();
                openedObjectClassName = found.object.getClass().getName();
            }

            if (updateBeforeLaunch && appManager != null && application != null)
            {
                String updateError = updateDatabase(appManager, application);
                if (updateError != null)
                {
                    return ToolResult.error(updateError).toJson();
                }
            }

            ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
            if (launchManager == null)
            {
                return ToolResult.error("The Eclipse launch manager is unavailable").toJson(); //$NON-NLS-1$
            }

            ILaunchConfigurationType configType =
                launchManager.getLaunchConfigurationType(LaunchConfigAccess.LAUNCH_CONFIG_TYPE_ID);
            if (configType == null)
            {
                return ToolResult
                    .error("No such launch configuration type: " + LaunchConfigAccess.LAUNCH_CONFIG_TYPE_ID)
                    .toJson();
            }

            ILaunchConfiguration matchingConfig =
                LaunchConfigAccess.findLaunchConfig(launchManager, configType, projectName, applicationId);
            boolean autoCreatedConfig = false;
            if (matchingConfig == null)
            {
                try
                {
                    matchingConfig = LaunchConfigAccess.createRuntimeClientConfig(launchManager, configType,
                        projectName, applicationId, applicationName);
                    autoCreatedConfig = true;
                    Activator.logInfo("Created a new runtime-client launch configuration '" //$NON-NLS-1$
                        + matchingConfig.getName()
                        + "' scoped to project '" + projectName + "', application '" + applicationId + "'."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                catch (CoreException e)
                {
                    Activator.logError("Could not auto-create a launch configuration for project '" //$NON-NLS-1$
                        + projectName + "', application '" + applicationId + "'", e); //$NON-NLS-1$ //$NON-NLS-2$
                    String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    ToolResult errorResult = ToolResult
                        .error("Could not find a launch configuration for project '" + projectName
                            + "' and application '" + applicationName + "' (" + applicationId
                            + "); auto-creating one also failed: " + reason
                            + ". Create a runtime-client launch configuration in EDT yourself, "
                            + "or pass launchConfigurationName to start an existing Attach configuration.");
                    errorResult.put("availableConfigurations", listAvailableConfigs(launchManager)); //$NON-NLS-1$
                    return errorResult.toJson();
                }
            }

            final String configName = matchingConfig.getName();
            Activator.logInfo("Starting debug launch: config=" + configName + ", project=" + projectName //$NON-NLS-1$ //$NON-NLS-2$
                + ", app=" + applicationId); //$NON-NLS-1$

            // row 45: mirror launchByConfigName's already-running guard so a second
            // (serialized) launch for the same application short-circuits instead of
            // starting a duplicate session. Best-effort: EDT registers the debug
            // target asynchronously, so a target launched a moment ago may not be
            // visible yet - the LAUNCH_LOCK closes the concurrent window; this check
            // catches the common already-registered case.
            if (applicationId != null && DebugSessionBook.findActiveTarget(applicationId) != null)
            {
                return ToolResult.success()
                    .put("project", projectName) //$NON-NLS-1$
                    .put("applicationId", applicationId) //$NON-NLS-1$
                    .put("launchConfiguration", configName) //$NON-NLS-1$
                    .put("alreadyRunning", true) //$NON-NLS-1$
                    .put("attach", false) //$NON-NLS-1$
                    .put("mode", "debug") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("message", "A debug session for this application is already running - launch skipped.") //$NON-NLS-1$
                    .toJson();
            }

            if (openedObject != null)
            {
                // The class of the instance, not the metadata type: that is what the environment
                // matches the object by - see LaunchConfigAccess.ATTR_EXTERNAL_OBJECT_TYPE.
                matchingConfig = LaunchConfigAccess.openingExternalObject(matchingConfig,
                    objectProjectName, openedObject, openedObjectClassName);
            }

            if (debugServerPort > 0)
            {
                // The same working copy the external object went onto, not a second one.
                matchingConfig = LaunchConfigAccess.listeningOnDebugPort(matchingConfig, debugServerPort);
            }

            LaunchOutcome outcome = performLaunch(matchingConfig, false);
            if (!outcome.started)
            {
                return refusalFor(outcome, "Debug session launch failed") //$NON-NLS-1$
                    .put("project", projectName) //$NON-NLS-1$
                    .put("applicationId", applicationId) //$NON-NLS-1$
                    .put("launchConfiguration", configName) //$NON-NLS-1$
                    .toJson();
            }

            return ToolResult.success()
                .put("project", projectName) //$NON-NLS-1$
                .put("applicationId", applicationId) //$NON-NLS-1$
                .put("launchConfiguration", configName) //$NON-NLS-1$
                .put("configurationType", LaunchConfigAccess.getConfigTypeId(matchingConfig)) //$NON-NLS-1$
                .put("autoCreatedConfiguration", autoCreatedConfig) //$NON-NLS-1$
                .put("attach", false) //$NON-NLS-1$
                .put("mode", "debug") //$NON-NLS-1$ //$NON-NLS-2$
                .put("externalObjectOpened", openedObject) //$NON-NLS-1$
                .put("debugServerPort", debugServerPort > 0 ? Integer.valueOf(debugServerPort) : null) //$NON-NLS-1$
                .put("message", autoCreatedConfig //$NON-NLS-1$
                    ? "Debug session is now running (a launch configuration was auto-created for it)"
                    : "Debug session is now running")
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Unhandled exception while launching debug session", e); //$NON-NLS-1$
            return ToolResult.error("Unhandled error: " + TextSuggest.safeMessage(e)).toJson();
        }
        finally
        {
            LAUNCH_LOCK.unlock();
        }
    }

    private String updateDatabaseIfNeeded(String projectName, String applicationId)
    {
        if (applicationId == null || applicationId.isEmpty()
            || applicationId.startsWith(LaunchConfigAccess.ATTACH_APP_ID_PREFIX))
        {
            return null;
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return null;
        }
        IApplicationManager appManager = Activator.getDefault().getApplicationManager();
        if (appManager == null)
        {
            return null;
        }
        try
        {
            Optional<IApplication> appOpt = appManager.getApplication(project, applicationId);
            if (!appOpt.isPresent())
            {
                return null;
            }
            return updateDatabase(appManager, appOpt.get());
        }
        catch (ApplicationException e)
        {
            Activator.logError("Failed to resolve the application for a pre-launch database update", e); //$NON-NLS-1$
            return null;
        }
    }

    private String updateDatabase(IApplicationManager appManager, IApplication application)
    {
        try
        {
            ApplicationUpdateState updateState = appManager.getUpdateState(application);
            if (updateState == ApplicationUpdateState.UPDATED
                || updateState == ApplicationUpdateState.BEING_UPDATED)
            {
                return null;
            }
            Activator.logInfo("Applying pre-launch database update: application=" + application.getId()); //$NON-NLS-1$

            ExecutionContext context = new ExecutionContext();
            Display display = Display.getDefault();
            if (display != null && !display.isDisposed())
            {
                final Shell[] shellHolder = new Shell[1];
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
                if (shellHolder[0] != null)
                {
                    context.setProperty(ExecutionContext.ACTIVE_SHELL_NAME, shellHolder[0]);
                }
            }

            IProgressMonitor monitor = new NullProgressMonitor();
            ApplicationUpdateState stateAfter =
                appManager.update(application, ApplicationUpdateType.INCREMENTAL, context, monitor);
            Activator.logInfo("Pre-launch database update finished: stateAfter=" + stateAfter); //$NON-NLS-1$
            return null;
        }
        catch (ApplicationException e)
        {
            Activator.logError("Failed to update the database before launch", e); //$NON-NLS-1$
            return "Could not update the database before launch: " + e.getMessage()
                + ". Retry with updateBeforeLaunch=false to skip the update.";
        }
    }

    /** How long a runtime client is given to register a debug target, in milliseconds. */
    static final long TARGET_WAIT_MS = 10_000L;

    /** How often the launch is re-read while waiting, in milliseconds. */
    static final long TARGET_STEP_MS = 250L;

    /**
     * What watching a launch found.
     * <p>
     * A launch is a success only when it is OBSERVED to be alive; the environment reports a failed
     * debug-server start by opening a dialog of its own and returning from the launch call normally,
     * so the absence of an exception says nothing.
     * </p>
     */
    static final class LaunchOutcome
    {
        final boolean started;
        final String refusal;
        final String observed;
        final List<Map<String, Object>> dialogsSeen;

        LaunchOutcome(boolean started, String refusal, String observed,
            List<Map<String, Object>> dialogsSeen)
        {
            this.started = started;
            this.refusal = refusal;
            this.observed = observed;
            this.dialogsSeen = dialogsSeen;
        }

        static LaunchOutcome ok()
        {
            return new LaunchOutcome(true, null, "running", java.util.Collections.emptyList()); //$NON-NLS-1$
        }
    }

    /**
     * Decides whether a launch is alive, by reading it rather than by trusting the call that made it.
     * <p>
     * The criterion differs by configuration kind, and mixing them produces both false answers. A
     * runtime client registers its debug target as part of starting, so no target within the window
     * means the start did not happen. An Attach configuration registers its target only once the
     * debugger connects to something that may not be there yet, so demanding a target inside ten
     * seconds would refuse a healthy launch.
     * </p>
     *
     * @param launch the launch the environment returned, never <code>null</code>.
     * @param isAttach whether the configuration attaches rather than starts a client.
     * @return what was observed
     */
    private static LaunchOutcome watchLaunch(ILaunch launch, boolean isAttach)
    {
        long deadline = System.currentTimeMillis() + TARGET_WAIT_MS;
        boolean everHadTargets = false;
        while (true)
        {
            IDebugTarget[] targets = launch.getDebugTargets();
            boolean live = false;
            for (IDebugTarget target : targets)
            {
                if (target != null && !target.isTerminated())
                {
                    live = true;
                    break;
                }
            }
            everHadTargets = everHadTargets || targets.length > 0;
            LaunchOutcome decided = decide(launch.isTerminated(), live, isAttach,
                System.currentTimeMillis() >= deadline, everHadTargets);
            if (decided != null)
            {
                return decided;
            }
            try
            {
                Thread.sleep(TARGET_STEP_MS);
            }
            catch (InterruptedException stop)
            {
                Thread.currentThread().interrupt();
                return new LaunchOutcome(false, "the wait for a debug target was interrupted", //$NON-NLS-1$
                    "interrupted", null); //$NON-NLS-1$
            }
        }
    }

    /**
     * Whether the environment can build the .epf the client needs, and what to say when it cannot.
     * <p>
     * The launch delegate reads the external-object attributes, then asks the dump service whether
     * generation is enabled FOR THAT PROJECT, and only then looks the object up. With generation off
     * it skips the whole branch without a word, and the client starts with nothing open. So the
     * question is asked here, where there is still someone to tell.
     * </p>
     *
     * @param objectProject the external-object project.
     * @param enableDump whether the caller allowed turning generation on.
     * @return what is wrong, or <code>null</code> when the environment is ready to build the dump
     */
    private static String dumpReadiness(IProject objectProject, boolean enableDump)
    {
        IExternalObjectDumpSupport dumps;
        try
        {
            dumps = ServiceAccess.get(IExternalObjectDumpSupport.class);
        }
        catch (Exception e)
        {
            Activator.logDebug("external object dump service not reachable: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
        if (dumps == null)
        {
            return null;
        }
        if (!dumps.isEnabled(objectProject))
        {
            if (!enableDump)
            {
                return "The project '" + objectProject.getName() + "' has external object dump " //$NON-NLS-1$ //$NON-NLS-2$
                    + "generation switched off, and the environment opens an external object in a " //$NON-NLS-1$
                    + "launched client by building that dump. With the setting off the client starts " //$NON-NLS-1$
                    + "with nothing open. Pass enableExternalObjectDump=true to turn it on for this " //$NON-NLS-1$
                    + "project, or turn it on in the project's properties. Nothing was launched."; //$NON-NLS-1$
            }
            dumps.setEnabled(objectProject, true);
        }
        IStatus ready = dumps.validateDumpGeneration(objectProject);
        if (ready != null && !ready.isOK())
        {
            return "The environment cannot build the external object's dump for project '" //$NON-NLS-1$
                + objectProject.getName() + "': " + ready.getMessage() //$NON-NLS-1$ //$NON-NLS-2$
                + ". The client would start with nothing open, so nothing was launched."; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Decides from what one reading of the launch showed.
     * <p>
     * Kept free of the launch object itself so that every outcome can be put to it directly: the
     * defect this replaces was not in the reading but in the judgement, which called any call that
     * did not throw a success.
     * </p>
     *
     * @param terminated whether the launch has already terminated.
     * @param anyLiveTarget whether it carries a debug target that is not terminated.
     * @param isAttach whether the configuration attaches rather than starts a client.
     * @param deadlineReached whether the waiting window is over.
     * @param everHadTargets whether a debug target was seen at any point of the wait.
     * @return the outcome, or <code>null</code> when the answer is "keep waiting"
     */
    static LaunchOutcome decide(boolean terminated, boolean anyLiveTarget, boolean isAttach,
        boolean deadlineReached, boolean everHadTargets)
    {
        if (terminated)
        {
            return new LaunchOutcome(false, "the launch was created and terminated straight away", //$NON-NLS-1$
                "terminated", null); //$NON-NLS-1$
        }
        if (anyLiveTarget)
        {
            return LaunchOutcome.ok();
        }
        if (isAttach)
        {
            // An attach registers its target when the debugger connects, which may be later than any
            // window this call can hold. Created and alive is all it can promise here.
            return LaunchOutcome.ok();
        }
        if (!deadlineReached)
        {
            return null;
        }
        return everHadTargets
            ? new LaunchOutcome(false,
                "the launch registered debug targets and all of them are already terminated", //$NON-NLS-1$
                "allTargetsTerminated", null) //$NON-NLS-1$
            : new LaunchOutcome(false,
                "no debug target appeared within " + (TARGET_WAIT_MS / 1000) //$NON-NLS-1$
                    + " seconds, so the debug session did not start", //$NON-NLS-1$
                "noTargets", null); //$NON-NLS-1$
    }

    /**
     * Starts the configuration and reports what was observed afterwards.
     *
     * @param config the configuration to launch.
     * @param isAttach whether it attaches rather than starts a client.
     * @return the outcome; {@link LaunchOutcome#started} false carries the refusal
     */
    private LaunchOutcome performLaunch(ILaunchConfiguration config, boolean isAttach)
    {
        List<Map<String, Object>> dialogsBefore = ModalDialogWatch.current().getDialogs();
        final String[] launchError = {null};
        final ILaunch[] launched = {null};
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
        {
            display.syncExec(() -> {
                try
                {
                    launched[0] = config.launch(ILaunchManager.DEBUG_MODE, null);
                }
                catch (Exception e)
                {
                    Activator.logError("Failed to launch debug session", e); //$NON-NLS-1$
                    launchError[0] = e.getMessage();
                }
            });
        }
        else
        {
            try
            {
                launched[0] = config.launch(ILaunchManager.DEBUG_MODE, null);
            }
            catch (CoreException e)
            {
                Activator.logError("Failed to launch debug session", e); //$NON-NLS-1$
                launchError[0] = e.getMessage();
            }
        }

        LaunchOutcome outcome;
        if (launchError[0] != null)
        {
            outcome = new LaunchOutcome(false, launchError[0], "threw", null); //$NON-NLS-1$
        }
        else if (launched[0] == null)
        {
            outcome = new LaunchOutcome(false, "the environment created no launch", //$NON-NLS-1$
                "notCreated", null); //$NON-NLS-1$
        }
        else
        {
            outcome = watchLaunch(launched[0], isAttach);
        }
        if (outcome.started)
        {
            return outcome;
        }
        // Dialogs are reported as what was seen, not as the cause: the watch sees every modal in the
        // workbench, and one of them may belong to whatever the person at the keyboard was doing.
        return new LaunchOutcome(false, outcome.refusal, outcome.observed,
            dialogsOpenedDuring(dialogsBefore));
    }

    /**
     * The modal dialogs that were not up before the launch and are up now.
     *
     * @param before what was open before the call.
     * @return the ones that appeared since, possibly empty
     */
    private static List<Map<String, Object>> dialogsOpenedDuring(List<Map<String, Object>> before)
    {
        return newDialogs(before, ModalDialogWatch.current().getDialogs());
    }

    /**
     * The dialogs of the second reading that the first reading did not have.
     *
     * @param before what was open before the launch.
     * @param now what is open now.
     * @return the ones that appeared since, in the order the later reading lists them
     */
    static List<Map<String, Object>> newDialogs(List<Map<String, Object>> before,
        List<Map<String, Object>> now)
    {
        List<Map<String, Object>> appeared = new java.util.ArrayList<>();
        for (Map<String, Object> dialog : now)
        {
            boolean wasThere = false;
            for (Map<String, Object> old : before)
            {
                if (java.util.Objects.equals(old.get("title"), dialog.get("title")) //$NON-NLS-1$ //$NON-NLS-2$
                    && java.util.Objects.equals(old.get("message"), dialog.get("message"))) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    wasThere = true;
                    break;
                }
            }
            if (!wasThere)
            {
                appeared.add(dialog);
            }
        }
        return appeared;
    }

    /**
     * Puts what was observed into a refusal.
     *
     * @param outcome the failed outcome.
     * @param lead the sentence the caller wants in front.
     * @return the refusal, ready to return
     */
    private static ToolResult refusalFor(LaunchOutcome outcome, String lead)
    {
        ToolResult result = ToolResult.error(lead + ": " + outcome.refusal) //$NON-NLS-1$
            .put("observed", outcome.observed); //$NON-NLS-1$
        if (outcome.dialogsSeen != null && !outcome.dialogsSeen.isEmpty())
        {
            result.put("dialogsOpenedDuringLaunch", outcome.dialogsSeen) //$NON-NLS-1$
                .put("dialogNote", "These dialogs were not open before the launch and are open now. " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Read them, and answer one with project_admin operation=answer_dialog when it " //$NON-NLS-1$
                    + "is the environment asking something.");
        }
        return result;
    }

    private static JsonArray listAvailableConfigs(ILaunchManager launchManager)
    {
        JsonArray arr = new JsonArray();
        for (ILaunchConfiguration cfg : LaunchConfigAccess.getAllDebugConfigs(launchManager))
        {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", cfg.getName()); //$NON-NLS-1$
            String typeId = LaunchConfigAccess.getConfigTypeId(cfg);
            obj.addProperty("type", typeId); //$NON-NLS-1$
            obj.addProperty("attach", LaunchConfigAccess.isAttachConfigTypeId(typeId)); //$NON-NLS-1$
            obj.addProperty("project", //$NON-NLS-1$
                LaunchConfigAccess.readAttribute(cfg, LaunchConfigAccess.ATTR_PROJECT_NAME, "")); //$NON-NLS-1$
            obj.addProperty("applicationId", //$NON-NLS-1$
                LaunchConfigAccess.readAttribute(cfg, LaunchConfigAccess.ATTR_APPLICATION_ID, "")); //$NON-NLS-1$
            String alias =
                LaunchConfigAccess.readAttribute(cfg, LaunchConfigAccess.ATTR_DEBUG_INFOBASE_ALIAS, ""); //$NON-NLS-1$
            if (!alias.isEmpty())
            {
                obj.addProperty("infobaseAlias", alias); //$NON-NLS-1$
            }
            String url =
                LaunchConfigAccess.readAttribute(cfg, LaunchConfigAccess.ATTR_DEBUG_SERVER_URL, ""); //$NON-NLS-1$
            if (!url.isEmpty())
            {
                obj.addProperty("debugServerUrl", url); //$NON-NLS-1$
            }
            arr.add(obj);
        }
        return arr;
    }
}
