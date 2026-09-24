/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.swt.widgets.Display;

import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.ApplicationUpdater;
import ru.aiedt.mcp.server.support.ClientLaunchMode;
import ru.aiedt.mcp.server.support.LaunchConfigAccess;
import ru.aiedt.mcp.server.support.ProjectResolver;
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
    private static final ReentrantLock LAUNCH_LOCK = LaunchConfigAccess.LAUNCH_LOCK;

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
            + "let it be resolved from projectName plus applicationId - when that pair has no " //$NON-NLS-1$
            + "configuration yet, one is created and saved. A configuration whose default run mode " //$NON-NLS-1$
            + "is the ordinary application starts in the thick client with " //$NON-NLS-1$
            + "/RunModeOrdinaryApplication among the infobase's additional launch parameters " //$NON-NLS-1$
            + "for this EDT session; " //$NON-NLS-1$
            + "clientType and runMode override the choice. Set updateBeforeLaunch=true to " //$NON-NLS-1$
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
            .stringProperty("startupOption", //$NON-NLS-1$
                "The /C startup string for the client that starts, written to this launch's own " //$NON-NLS-1$
                    + "configuration copy; the saved configuration is not changed. This is how an " //$NON-NLS-1$
                    + "external processor or report receives its parameters. Refused while the " //$NON-NLS-1$
                    + "configuration's client is already running - that one was not started with it.") //$NON-NLS-1$
            .stringProperty("clientType", //$NON-NLS-1$
                "The client to start: thin, thick or web. Omitted: the launch configuration's " //$NON-NLS-1$
                    + "own, thin for a configuration created here - and thick whenever the run " //$NON-NLS-1$
                    + "mode is ordinary, because the ordinary application opens in the thick " //$NON-NLS-1$
                    + "client only. Applied to this launch's own configuration copy; a " //$NON-NLS-1$
                    + "configuration created here is saved with it.") //$NON-NLS-1$
            .stringProperty("runMode", //$NON-NLS-1$
                "ordinary or managed. Omitted: the configuration's default run mode. Ordinary " //$NON-NLS-1$
                    + "puts /RunModeOrdinaryApplication among the infobase's additional launch " //$NON-NLS-1$
                    + "parameters on the reference EDT holds for this session, managed takes it " //$NON-NLS-1$
                    + "out; the infobase list on disk is not written. The answer says what changed.") //$NON-NLS-1$
            .stringProperty("waitForEndpoint", //$NON-NLS-1$
                "Wait for this URL to answer before reporting the client started - ready is any " //$NON-NLS-1$
                    + "final status below 500, at most five redirects. A client whose endpoint " //$NON-NLS-1$
                    + "never answers is NOT stopped; the refusal says so.") //$NON-NLS-1$
            .integerProperty("endpointTimeoutSeconds", //$NON-NLS-1$
                "How long to wait for waitForEndpoint, in seconds. Default 20, limit 50. Refused " //$NON-NLS-1$
                    + "without waitForEndpoint.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    /**
     * Names the database update this start runs before the client starts, so the road weighs the
     * call by it.
     * <p>
     * The update runs only when {@code updateBeforeLaunch} asks for it (default false, read exactly
     * as {@link #execute} reads it), and it is the work {@code update_database} is weighed for.
     * The client itself, which outlives the call, is deliberately not weighed.
     * </p>
     *
     * @param arguments the call arguments, as the client sent them; may be <code>null</code>
     * @return {@code update_database} when the call updates the infobase before starting,
     *         <code>null</code> otherwise
     */
    @Override
    public String routesTo(Map<String, String> arguments)
    {
        return JsonUtils.extractBooleanArgument(arguments, "updateBeforeLaunch", false) //$NON-NLS-1$
            ? "update_database" : null; //$NON-NLS-1$
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
            String startupOption = JsonUtils.extractStringArgument(params, "startupOption"); //$NON-NLS-1$
            if (startupOption != null && startupOption.trim().isEmpty())
            {
                startupOption = null;
            }
            String clientType = JsonUtils.extractStringArgument(params, "clientType"); //$NON-NLS-1$
            String runMode = JsonUtils.extractStringArgument(params, "runMode"); //$NON-NLS-1$
            String waitForEndpoint = JsonUtils.extractStringArgument(params, "waitForEndpoint"); //$NON-NLS-1$
            if (waitForEndpoint != null && waitForEndpoint.trim().isEmpty())
            {
                waitForEndpoint = null;
            }
            Integer endpointTimeout = JsonUtils.extractIntegerArgument(params, "endpointTimeoutSeconds"); //$NON-NLS-1$
            if (endpointTimeout != null && (endpointTimeout.intValue() <= 0 || endpointTimeout.intValue() > 50))
            {
                return ToolResult.error("endpointTimeoutSeconds must be in (0, 50]. Nothing was started.") //$NON-NLS-1$
                    .put("endpointTimeoutSeconds", endpointTimeout) //$NON-NLS-1$
                    .toJson();
            }
            if (endpointTimeout != null && waitForEndpoint == null)
            {
                return ToolResult.error("endpointTimeoutSeconds without waitForEndpoint names a " //$NON-NLS-1$
                    + "wait that never happens. Nothing was started.").toJson(); //$NON-NLS-1$
            }

            ILaunchManager launchManager = LaunchConfigAccess.getLaunchManager();
            if (launchManager == null)
            {
                return ToolResult.error("Error: the launch manager is not published as a service") //$NON-NLS-1$
                    .toJson();
            }

            boolean choiceGiven = clientType != null && !clientType.trim().isEmpty()
                || runMode != null && !runMode.trim().isEmpty();

            // The configuration is resolved under the lock: a second call for the same pair
            // waiting on the first must see the configuration the first one saved.
            LAUNCH_LOCK.lock();
            try
            {
                ILaunchConfiguration config =
                    LaunchConfigAccess.resolveLaunchConfig(launchManager, configName, projectName,
                        applicationId);
                boolean pairNamed = (configName == null || configName.isEmpty()) && projectName != null
                    && !projectName.isEmpty() && applicationId != null && !applicationId.isEmpty();
                if (config == null && !pairNamed)
                {
                    return ToolResult.error("No launch configuration matched. Give " //$NON-NLS-1$
                        + "launchConfigurationName, or projectName together with applicationId. " //$NON-NLS-1$
                        + "list_configurations shows what exists.").toJson(); //$NON-NLS-1$
                }

                // An attach configuration has no client to start - it joins a debug server somebody
                // else is running. Launching it here would produce a session with no process behind it.
                if (config != null && LaunchConfigAccess.isAttachConfig(config))
                {
                    return ToolResult.error("'" + config.getName() //$NON-NLS-1$
                        + "' attaches to a running debug server rather than starting a client. " //$NON-NLS-1$
                        + "Use launch_debugger action=launch for it.").toJson(); //$NON-NLS-1$
                }

                // The client is decided before anything is created or written: a contradiction in
                // the arguments must not leave a saved configuration behind.
                String configProject = config == null ? projectName
                    : LaunchConfigAccess.readAttribute(config, LaunchConfigAccess.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
                if (configProject.isEmpty() && projectName != null)
                {
                    configProject = projectName;
                }
                IProject project = configProject == null || configProject.isEmpty() ? null
                    : ProjectResolver.resolve(configProject);
                ClientLaunchMode mode = ClientLaunchMode.decide(clientType, runMode,
                    project == null ? null : ClientLaunchMode.projectRunMode(project), config != null,
                    config == null ? null : LaunchConfigAccess.getClientTypeIdFor(config));
                if (mode.refusal != null)
                {
                    return ToolResult.error(mode.refusal)
                        .put("configuration", config == null ? null : config.getName()) //$NON-NLS-1$
                        .put("nothingWasStarted", Boolean.TRUE) //$NON-NLS-1$
                        .toJson();
                }

                boolean created = false;
                if (config == null)
                {
                    if (project == null)
                    {
                        return ProjectResolver.notFound(projectName).toJson();
                    }
                    IApplication application = findApplication(project, applicationId);
                    if (application == null)
                    {
                        return ToolResult.error("No application found for: " + applicationId //$NON-NLS-1$
                            + ". Call get_applications to see the valid application IDs. Nothing " //$NON-NLS-1$
                            + "was created or started.").toJson(); //$NON-NLS-1$
                    }
                    ILaunchConfigurationType configType =
                        launchManager.getLaunchConfigurationType(LaunchConfigAccess.LAUNCH_CONFIG_TYPE_ID);
                    if (configType == null)
                    {
                        return ToolResult.error("No such launch configuration type: " //$NON-NLS-1$
                            + LaunchConfigAccess.LAUNCH_CONFIG_TYPE_ID).toJson();
                    }
                    config = LaunchConfigAccess.createRuntimeClientConfig(launchManager, configType,
                        project.getName(), applicationId, application.getName(), mode.clientTypeId);
                    created = true;
                    Activator.logInfo("Created a runtime-client launch configuration '" + config.getName() //$NON-NLS-1$
                        + "' for project '" + project.getName() + "', application '" + applicationId //$NON-NLS-1$ //$NON-NLS-2$
                        + "'."); //$NON-NLS-1$
                }
                return decideAndLaunch(launchManager, config, projectName, updateFirst, allowSecond,
                    startupOption, waitForEndpoint, endpointTimeout, mode, choiceGiven, created, project);
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
        String projectName, boolean updateFirst, boolean allowSecond, String startupOption,
        String waitForEndpoint, Integer endpointTimeout, ClientLaunchMode mode, boolean choiceGiven,
        boolean created, IProject project)
    {
        try
        {
            String resolvedAppId = LaunchConfigAccess.getApplicationIdFor(config);
            ILaunch running = findRunning(launchManager, resolvedAppId);
            if (running != null && !allowSecond)
            {
                // The client the caller named is an argument; a client decided from the
                // configuration's run mode is not - the running one was decided the same way.
                if (startupOption != null || waitForEndpoint != null || choiceGiven)
                {
                    // The running client was not started with this string, and success here would
                    // lose it. Stop the client or allow a second one - the string is not dropped.
                    return ToolResult.error("A client for this configuration is already running, " //$NON-NLS-1$
                        + "and it was not started with this startupOption. Stop it with " //$NON-NLS-1$
                        + "launch_debugger action=terminate, or pass allowSecondSession=true to " //$NON-NLS-1$
                        + "start another with it. Nothing was started.") //$NON-NLS-1$
                        .put("configuration", config.getName()) //$NON-NLS-1$
                        .put("applicationId", resolvedAppId) //$NON-NLS-1$
                        .put("alreadyRunning", true) //$NON-NLS-1$
                        .toJson();
                }
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

            IApplication application = findApplication(project, resolvedAppId);
            String flagState = application == null ? "not applied: the application was not resolved" //$NON-NLS-1$
                : ClientLaunchMode.reconcileFlag(application, mode.wantsOrdinaryFlag());

            // A configuration created here already carries the client; an existing one keeps its
            // own on disk and starts this launch with the decided one.
            String failure = launch(config, startupOption, created ? null : mode.clientTypeId);
            if (failure != null)
            {
                return ToolResult.error("Could not start the client: " + failure).toJson(); //$NON-NLS-1$
            }

            ToolResult success = result.put("started", true) //$NON-NLS-1$
                .put("mode", ILaunchManager.RUN_MODE) //$NON-NLS-1$
                .put("secondSession", running != null) //$NON-NLS-1$
                .put("startupOption", startupOption) //$NON-NLS-1$
                .put("autoCreatedConfiguration", created) //$NON-NLS-1$
                .put("clientType", mode.clientType) //$NON-NLS-1$
                .put("clientTypeSource", mode.clientTypeSource) //$NON-NLS-1$
                .put("runMode", mode.runMode) //$NON-NLS-1$
                .put("runModeSource", mode.runModeSource) //$NON-NLS-1$
                .put("runModeFlag", ClientLaunchMode.ORDINARY_FLAG) //$NON-NLS-1$
                .put("runModeFlagState", flagState) //$NON-NLS-1$
                .put("runModeFlagScope", ClientLaunchMode.FLAG_SCOPE) //$NON-NLS-1$
                .put("infobaseAdditionalParameters", //$NON-NLS-1$
                    application == null ? null : ClientLaunchMode.additionalParametersOf(application));
            return waitForEndpoint(waitForEndpoint, endpointTimeout, success);
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
     * Waits for the endpoint the client is supposed to open, when the caller asked for one. The
     * client stays whatever it is - a refusal here reports the wait, it does not kill the client.
     *
     * @param waitForEndpoint the URL to poll, or <code>null</code> to answer at once.
     * @param endpointTimeout the budget in seconds, or <code>null</code> for 20.
     * @param success the answer being built.
     * @return the JSON answer
     */
    private static String waitForEndpoint(String waitForEndpoint, Integer endpointTimeout,
        ToolResult success)
    {
        if (waitForEndpoint == null)
        {
            return success.toJson();
        }
        int seconds = endpointTimeout == null ? 20 : endpointTimeout.intValue();
        ru.aiedt.mcp.server.support.EndpointWaiter.Outcome outcome =
            ru.aiedt.mcp.server.support.EndpointWaiter.waitFor(waitForEndpoint, seconds * 1000L);
        success.put("endpointReady", outcome.ready); //$NON-NLS-1$
        success.put("endpointWaitedSeconds", Long.valueOf(outcome.waitedMs / 1000L)); //$NON-NLS-1$
        if (outcome.ready)
        {
            success.put("endpointHttpStatus", Integer.valueOf(outcome.httpStatus)); //$NON-NLS-1$
            return success.toJson();
        }
        return ToolResult.error("The endpoint " + waitForEndpoint + " never answered within " //$NON-NLS-1$
            + seconds + "s" + (outcome.lastProblem != null ? " (" + outcome.lastProblem + ")" : "") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + ". The client is NOT stopped - watch it with debug_status.") //$NON-NLS-1$
            .put("endpointReady", false) //$NON-NLS-1$
            .toJson();
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
     * @param startupOption the {@code /C} startup string for this launch, or <code>null</code>
     * @return <code>null</code> on success, otherwise the failure to report
     */
    private static String launch(ILaunchConfiguration config, String startupOption, String clientTypeId)
    {
        final String[] error = {null};
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
        {
            display.syncExec(() -> error[0] = launchDirectly(config, startupOption, clientTypeId));
        }
        else
        {
            error[0] = launchDirectly(config, startupOption, clientTypeId);
        }
        return error[0];
    }

    /**
     * The application of a project by id, or {@code null} when it cannot be resolved.
     *
     * @param project the project; {@code null} yields {@code null}
     * @param applicationId the application id; {@code null} yields {@code null}
     * @return the application, or {@code null}
     */
    private static IApplication findApplication(IProject project, String applicationId)
    {
        IApplicationManager appManager = Activator.getDefault().getApplicationManager();
        if (appManager == null || project == null || applicationId == null)
        {
            return null;
        }
        try
        {
            return appManager.getApplication(project, applicationId).orElse(null);
        }
        catch (ApplicationException e)
        {
            Activator.logWarning("Application " + applicationId + " of " + project.getName() //$NON-NLS-1$ //$NON-NLS-2$
                + " was not resolved: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * @param config the configuration to launch
     * @param startupOption the {@code /C} startup string for this launch, or <code>null</code>
     * @param clientTypeId the client for this launch, or <code>null</code> for the configuration's own
     * @return <code>null</code> on success, otherwise the message to report
     */
    private static String launchDirectly(ILaunchConfiguration config, String startupOption, String clientTypeId)
    {
        try
        {
            ILaunchConfiguration toLaunch = startupOption == null ? config
                : LaunchConfigAccess.withStartupOption(config, startupOption);
            if (clientTypeId != null)
            {
                toLaunch = LaunchConfigAccess.withClientType(toLaunch, clientTypeId);
            }
            toLaunch.launch(ILaunchManager.RUN_MODE, null);
            return null;
        }
        catch (CoreException e)
        {
            Activator.logError("Failed to start the 1C client", e); //$NON-NLS-1$
            return e.getMessage() != null ? e.getMessage() : "unknown failure"; //$NON-NLS-1$
        }
    }
}
