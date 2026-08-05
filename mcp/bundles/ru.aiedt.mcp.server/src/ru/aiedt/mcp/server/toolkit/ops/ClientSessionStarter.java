/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.swt.widgets.Display;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.ApplicationUpdater;
import ru.aiedt.mcp.server.support.LaunchConfigAccess;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Starts a 1C:Enterprise client from an EDT launch configuration, without a debugger.
 * <p>
 * The alternative open to a caller without this is to assemble a <code>1cv8.exe</code> command line
 * by hand. That command line has to agree with what the IDE is configured to do - which runtime
 * version, which infobase, thin or thick client, which user - and when it drifts, the client comes
 * up against something other than what the caller meant. Launching the configuration itself removes
 * the question: whatever EDT would have started, this starts.
 * </p>
 * <p>
 * Debugging is deliberately not offered here. {@code launch_debugger} covers that, and a caller
 * that wanted a debugger and got a plain client would notice only when a breakpoint failed to fire.
 * Stopping is not offered either - {@code launch_debugger action=terminate} already ends any EDT
 * launch, this one included.
 * </p>
 */
public class ClientSessionStarter
    implements IMcpTool
{
    /** The tool name, also the operation name under {@code infobase_admin}. */
    public static final String NAME = "start_client"; //$NON-NLS-1$

    /**
     * Held across the whole decide-and-launch sequence.
     * <p>
     * Without it, two calls can both see no running client and both start one - the duplicate this
     * tool promises not to create. {@code DebugSessionStarter} guards the same window the same way.
     * </p>
     */
    private static final ReentrantLock LAUNCH_LOCK = new ReentrantLock();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Start a 1C:Enterprise client from an EDT launch configuration, without a debugger. " //$NON-NLS-1$
            + "Use this instead of building a 1cv8.exe command line: the client comes up with the " //$NON-NLS-1$
            + "runtime version, infobase, client type and user the IDE is configured for. Identify " //$NON-NLS-1$
            + "the configuration by launchConfigurationName (as returned by list_configurations), or " //$NON-NLS-1$
            + "let it be resolved from projectName plus applicationId. Set updateBeforeLaunch=true to " //$NON-NLS-1$
            + "bring the infobase up to date first. A configuration whose client is already running " //$NON-NLS-1$
            + "is reported rather than started twice; pass allowSecondSession=true to start another " //$NON-NLS-1$
            + "anyway. To debug instead, use launch_debugger action=launch; to stop a client, " //$NON-NLS-1$
            + "launch_debugger action=terminate."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("launchConfigurationName", //$NON-NLS-1$
                "Launch configuration to start, by name. Take it from list_configurations.") //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "Project to resolve the configuration from, when no name is given.") //$NON-NLS-1$
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application to resolve the configuration from, together with projectName.") //$NON-NLS-1$
            .booleanProperty("updateBeforeLaunch", //$NON-NLS-1$
                "Update the infobase before starting (default false).") //$NON-NLS-1$
            .booleanProperty("allowSecondSession", //$NON-NLS-1$
                "Start even when this configuration already has a client running (default false).") //$NON-NLS-1$
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
        try
        {
            String configName = JsonUtils.extractStringArgument(params, "launchConfigurationName"); //$NON-NLS-1$
            String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
            String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
            boolean updateFirst = JsonUtils.extractBooleanArgument(params, "updateBeforeLaunch", false); //$NON-NLS-1$
            boolean allowSecond = JsonUtils.extractBooleanArgument(params, "allowSecondSession", false); //$NON-NLS-1$

            ILaunchManager launchManager = LaunchConfigAccess.getLaunchManager();
            if (launchManager == null)
            {
                return ToolResult.error("Error: the launch manager is not published as a service") //$NON-NLS-1$
                    .toJson();
            }

            ILaunchConfiguration config =
                LaunchConfigAccess.resolveLaunchConfig(launchManager, configName, projectName,
                    applicationId);
            if (config == null)
            {
                return ToolResult.error("No launch configuration matched. Give " //$NON-NLS-1$
                    + "launchConfigurationName, or projectName together with applicationId. " //$NON-NLS-1$
                    + "list_configurations shows what exists.").toJson(); //$NON-NLS-1$
            }

            // An attach configuration has no client to start - it joins a debug server somebody else
            // is running. Launching it here would produce a session with no process behind it.
            if (LaunchConfigAccess.isAttachConfig(config))
            {
                return ToolResult.error("'" + config.getName() //$NON-NLS-1$
                    + "' attaches to a running debug server rather than starting a client. " //$NON-NLS-1$
                    + "Use launch_debugger action=launch for it.").toJson(); //$NON-NLS-1$
            }

            LAUNCH_LOCK.lock();
            try
            {
                return decideAndLaunch(launchManager, config, projectName, updateFirst, allowSecond);
            }
            finally
            {
                LAUNCH_LOCK.unlock();
            }
        }
        catch (Exception e)
        {
            Activator.logError("Could not start the 1C client", e); //$NON-NLS-1$
            return ToolResult.error("Error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * The part that must not interleave with another call: look for a running client, decide, and
     * launch.
     *
     * @param launchManager the launch manager
     * @param config the resolved configuration
     * @param projectName the requested project, used only as a fallback for the update
     * @param updateFirst whether to update the infobase before launching
     * @param allowSecond whether a second session is permitted
     * @return the JSON reply
     */
    private static String decideAndLaunch(ILaunchManager launchManager, ILaunchConfiguration config,
        String projectName, boolean updateFirst, boolean allowSecond)
    {
        try
        {
            String resolvedAppId = LaunchConfigAccess.getApplicationIdFor(config);
            ILaunch running = findRunning(launchManager, resolvedAppId);
            if (running != null && !allowSecond)
            {
                return ToolResult.success()
                    .put("started", false) //$NON-NLS-1$
                    .put("configuration", config.getName()) //$NON-NLS-1$
                    .put("applicationId", resolvedAppId) //$NON-NLS-1$
                    .put("alreadyRunning", true) //$NON-NLS-1$
                    .put("mode", running.getLaunchMode()) //$NON-NLS-1$
                    .put("hint", "A client for this configuration is already running. Pass " //$NON-NLS-1$ //$NON-NLS-2$
                        + "allowSecondSession=true to start another, or " //$NON-NLS-1$
                        + "launch_debugger action=terminate to stop the running one.") //$NON-NLS-1$
                    .toJson();
            }

            ToolResult result = ToolResult.success()
                .put("configuration", config.getName()) //$NON-NLS-1$
                .put("applicationId", resolvedAppId); //$NON-NLS-1$

            if (updateFirst)
            {
                // The project comes from the configuration that was actually resolved, not from the
                // request. A name given as launchConfigurationName wins the resolution, so a
                // projectName sent alongside it may belong to something else entirely - updating
                // that one would push a different project's configuration and still launch this one.
                String updateProject = LaunchConfigAccess.readAttribute(config,
                    LaunchConfigAccess.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
                if (updateProject.isEmpty() && projectName != null)
                {
                    updateProject = projectName;
                }
                ApplicationUpdater.Result update =
                    ApplicationUpdater.updateIfNeeded(updateProject, resolvedAppId);
                result.put("databaseUpdate", update.outcome.toString()); //$NON-NLS-1$
                if (!update.isUpToDate())
                {
                    // Anything short of "up to date" means the caller asked for a current infobase
                    // and would not be getting one. Starting anyway produces a client that
                    // misbehaves in ways that read as application bugs - a partial update or an
                    // update someone else is midway through is no safer here than an outright
                    // failure.
                    StringBuilder why = new StringBuilder();
                    why.append("The infobase is not up to date before launch (") //$NON-NLS-1$
                        .append(update.outcome).append(')'); //$NON-NLS-1$
                    if (update.errorMessage != null)
                    {
                        why.append(": ").append(update.errorMessage); //$NON-NLS-1$
                    }
                    if (update.hint != null)
                    {
                        why.append(' ').append(update.hint);
                    }
                    why.append(" Nothing was started. Retry with updateBeforeLaunch=false to start " //$NON-NLS-1$
                        + "against the infobase as it stands."); //$NON-NLS-1$
                    return ToolResult.error(why.toString()).toJson();
                }
            }

            String failure = launch(config);
            if (failure != null)
            {
                return ToolResult.error("Could not start the client: " + failure).toJson(); //$NON-NLS-1$
            }

            return result.put("started", true) //$NON-NLS-1$
                .put("mode", ILaunchManager.RUN_MODE) //$NON-NLS-1$
                .put("secondSession", running != null) //$NON-NLS-1$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Could not start the 1C client", e); //$NON-NLS-1$
            return ToolResult.error("Error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * The live launch for an application, if one is running.
     *
     * @param launchManager the launch manager
     * @param applicationId the application to look for; may be <code>null</code>
     * @return the running launch, or <code>null</code>
     */
    private static ILaunch findRunning(ILaunchManager launchManager, String applicationId)
    {
        if (applicationId == null || applicationId.isEmpty())
        {
            return null;
        }
        for (ILaunch launch : launchManager.getLaunches())
        {
            if (!launch.isTerminated()
                && applicationId.equals(LaunchConfigAccess.getApplicationIdFor(launch)))
            {
                return launch;
            }
        }
        return null;
    }

    /**
     * Runs the configuration, on the UI thread when there is one.
     * <p>
     * EDT's launch delegate can raise dialogs - a missing runtime, credentials it wants confirmed -
     * and doing that off the display thread throws instead of asking. The headless branch is for the
     * test runtime, where no display exists.
     * </p>
     *
     * @param config the configuration to launch
     * @return <code>null</code> on success, otherwise the failure to report
     */
    private static String launch(ILaunchConfiguration config)
    {
        final String[] error = {null};
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
        {
            display.syncExec(() -> error[0] = launchDirectly(config));
        }
        else
        {
            error[0] = launchDirectly(config);
        }
        return error[0];
    }

    /**
     * @param config the configuration to launch
     * @return <code>null</code> on success, otherwise the message to report
     */
    private static String launchDirectly(ILaunchConfiguration config)
    {
        try
        {
            config.launch(ILaunchManager.RUN_MODE, null);
            return null;
        }
        catch (CoreException e)
        {
            Activator.logError("Failed to start the 1C client", e); //$NON-NLS-1$
            return e.getMessage() != null ? e.getMessage() : "unknown failure"; //$NON-NLS-1$
        }
    }
}
