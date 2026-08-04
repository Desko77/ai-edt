/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
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
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.DebugSessionBook;
import ru.aiedt.mcp.server.support.LaunchConfigAccess;
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

        if (configName != null && !configName.isEmpty())
        {
            return launchByConfigName(configName, updateBeforeLaunch);
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

        return launchDebug(projectName, applicationId, updateBeforeLaunch);
    }

    private String launchByConfigName(String configName, boolean updateBeforeLaunch)
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

            String launchError = performLaunch(config);
            if (launchError != null)
            {
                return ToolResult.error("Could not launch the debug session: " + launchError).toJson();
            }

            ToolResult result = ToolResult.success()
                .put("launchConfiguration", config.getName()) //$NON-NLS-1$
                .put("configurationType", typeId) //$NON-NLS-1$
                .put("attach", isAttach) //$NON-NLS-1$
                .put("mode", "debug"); //$NON-NLS-1$ //$NON-NLS-2$
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

    private String launchDebug(String projectName, String applicationId, boolean updateBeforeLaunch)
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

            String launchError = performLaunch(matchingConfig);
            if (launchError != null)
            {
                return ToolResult.error("Debug session launch failed: " + launchError).toJson();
            }

            return ToolResult.success()
                .put("project", projectName) //$NON-NLS-1$
                .put("applicationId", applicationId) //$NON-NLS-1$
                .put("launchConfiguration", configName) //$NON-NLS-1$
                .put("configurationType", LaunchConfigAccess.getConfigTypeId(matchingConfig)) //$NON-NLS-1$
                .put("autoCreatedConfiguration", autoCreatedConfig) //$NON-NLS-1$
                .put("attach", false) //$NON-NLS-1$
                .put("mode", "debug") //$NON-NLS-1$ //$NON-NLS-2$
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

    private String performLaunch(ILaunchConfiguration config)
    {
        final String[] launchError = {null};
        final boolean[] launchSuccess = {false};
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
        {
            display.syncExec(() -> {
                try
                {
                    config.launch(ILaunchManager.DEBUG_MODE, null);
                    launchSuccess[0] = true;
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
                config.launch(ILaunchManager.DEBUG_MODE, null);
                launchSuccess[0] = true;
            }
            catch (CoreException e)
            {
                Activator.logError("Failed to launch debug session", e); //$NON-NLS-1$
                launchError[0] = e.getMessage();
            }
        }
        return launchSuccess[0] ? null : (launchError[0] != null ? launchError[0] : "unknown failure"); //$NON-NLS-1$
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
