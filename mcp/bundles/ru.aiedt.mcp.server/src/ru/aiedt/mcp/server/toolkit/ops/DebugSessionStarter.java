/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.swt.widgets.Display;

import com._1c.g5.v8.dt.platform.services.core.dump.IExternalObjectDumpSupport;
import com._1c.g5.wiring.ServiceAccess;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.ModalDialogWatch;
import ru.aiedt.mcp.server.support.PendingEnvelope;
import ru.aiedt.mcp.server.support.PendingExecutor;
import ru.aiedt.mcp.server.support.PendingWorkRegistry;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.DebugSessionBook;
import ru.aiedt.mcp.server.support.LaunchConfigAccess;
import ru.aiedt.mcp.server.support.BmExternalObjectDumpHelper;
import ru.aiedt.mcp.server.support.ApplicationUpdater;
import ru.aiedt.mcp.server.support.ClientLaunchMode;
import ru.aiedt.mcp.server.support.DumpInfoProbe;
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
        + "for that project/application pair (reported back as autoCreatedConfiguration). " //$NON-NLS-1$
        + "A configuration whose default run mode is the ordinary application starts in the thick " //$NON-NLS-1$
        + "client with /RunModeOrdinaryApplication among the infobase's additional launch " //$NON-NLS-1$
        + "parameters for this EDT session; clientType and runMode override the choice."; //$NON-NLS-1$

    /**
     * Coarse guard closing the launch TOCTOU windows (inbox row 45): the
     * already-running check ({@code findActiveTarget} -> preflight ->
     * {@code performLaunch}) and the find/create/launch path were non-atomic, so
     * two near-simultaneous launches for the same target could both miss the
     * registering target / both create a persisted configuration. MCP execute is
     * normally sequential, so this lock is uncontended in practice - it is a
     * safety net that serializes the whole check-and-launch unit. Debug launches
     * are inherently exclusive (one live session per target), so a global lock is
     * preferable to a per-key map (simpler, no leak surface). The lock is the one
     * the plain client start takes too, because both put the run-mode flag on the
     * infobase reference before launching.
     */
    private static final java.util.concurrent.locks.ReentrantLock LAUNCH_LOCK = LaunchConfigAccess.LAUNCH_LOCK;

    /**
     * How long a poll of a handed-over launch waits inline, in milliseconds.
     * <p>
     * A caller that has answered the dialog polls and usually collects at once; one whose launch is
     * still going gets a fresh Pending answer rather than a request held open longer than a client
     * keeps one.
     * </p>
     */
    private static final long LAUNCH_RESUME_WAIT_MS = 30_000L;

    /**
     * The launches handed to a {@code runKey} whose continuation has not finished yet, by the
     * application they start.
     * <p>
     * A handed-over launch keeps running after the call that made it has returned its Pending
     * envelope, and {@link #LAUNCH_LOCK} goes with that call. Without this reservation the
     * already-running check sees no target - the first client has not registered one yet - and a
     * second launch of the same application starts a second client, so answering the dialog that
     * held the first open starts two. The reservation's life is the continuation's: it is placed
     * before the run is dispatched and leaves in the run body's own {@code finally} - after the
     * launch has returned, after the wait for the debug target's registration is over and after
     * the caller's answer was built. Nothing else releases it: not the launch call's return, which
     * the continuation outlives while it waits on the target, and not the registry's tracking of
     * the run, which a cancel drops while the launch is still parked on a question.
     * </p>
     */
    private static final Map<String, InFlightLaunch> LAUNCHES_IN_FLIGHT =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A handed-over launch reserved by the application it starts.
     * <p>
     * The launch itself is kept, not only the key: a run cancelled before its body claimed its
     * start never enters the body's {@code finally}, so that reservation is released by a reader
     * once the launch it would have waited on has returned - the only liveness signal left when no
     * continuation ran.
     * </p>
     */
    static final class InFlightLaunch
    {
        /** The key the Pending envelope carried. */
        final String runKey;

        /** The launch the reservation was placed for. */
        final LaunchUnderWay launch;

        /**
         * Whether the run's body never ran, so no {@code finally} will release the reservation.
         * Written by the work-exit door when it settles with the reservation still held.
         */
        volatile boolean bodyNeverRan;

        /**
         * @param runKey the key the caller polls
         * @param launch the launch still under way
         */
        InFlightLaunch(String runKey, LaunchUnderWay launch)
        {
            this.runKey = runKey;
            this.launch = launch;
        }
    }

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
                    + "through external_object_workshop operation=import_external_object. Omit to " //$NON-NLS-1$
                    + "open nothing.") //$NON-NLS-1$
            .stringProperty("externalObjectProject", //$NON-NLS-1$
                "The external-object project holding externalObjectName. Omit when the project has " //$NON-NLS-1$
                    + "one object and its name is unambiguous in the workspace.") //$NON-NLS-1$
            .stringProperty("startupOption", //$NON-NLS-1$
                "The /C startup string for the client that starts, written to this launch's own " //$NON-NLS-1$
                    + "configuration copy; the saved configuration is not changed. This is how an " //$NON-NLS-1$
                    + "opened external object receives its parameters. An Attach configuration " //$NON-NLS-1$
                    + "starts no client, so the argument is refused there.") //$NON-NLS-1$
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
                "Wait for this URL to answer before reporting the launch ready - how you know the " //$NON-NLS-1$
                    + "opened external object is up, not merely the process. A GET; ready is any " //$NON-NLS-1$
                    + "final status below 500, redirects followed (at most five). The launch is " //$NON-NLS-1$
                    + "NOT stopped when the endpoint never answers: the refusal says so and the " //$NON-NLS-1$
                    + "client is watched with debug_status.") //$NON-NLS-1$
            .integerProperty("endpointTimeoutSeconds", //$NON-NLS-1$
                "How long to wait for waitForEndpoint, in seconds. Default 20, limit 50 - one " //$NON-NLS-1$
                    + "request does not outlive that. Refused without waitForEndpoint.") //$NON-NLS-1$
            .stringProperty("runKey", //$NON-NLS-1$
                "Resume a launch that answered Pending: the launch was held by a dialog or outlived " //$NON-NLS-1$
                    + "its wait, and this key collects what became of it.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    /**
     * Names the database update this launch runs before the client starts, so the road weighs the
     * call by it.
     * <p>
     * The update runs unless {@code updateBeforeLaunch} opts out (default true, read exactly as
     * {@link #execute} reads it), and it is the work {@code update_database} is weighed for. The
     * route cannot tell an Attach configuration from a runtime client, so a launch that names an
     * Attach configuration is weighed although the update is skipped there.
     * </p>
     *
     * @param arguments the call arguments, as the client sent them; may be <code>null</code>
     * @return {@code update_database} when the call updates the infobase before launching,
     *         <code>null</code> when it opts out
     */
    @Override
    public String routesTo(Map<String, String> arguments)
    {
        return JsonUtils.extractBooleanArgument(arguments, "updateBeforeLaunch", true) //$NON-NLS-1$
            ? "update_database" : null; //$NON-NLS-1$
    }

    /**
     * The run this call resumes in {@code domain}, when that domain holds the launches this tool
     * hands over.
     *
     * @param domain the registry domain the key was found in
     * @param operation the operation argument, or <code>null</code> when the call names none
     * @return {@link #NAME} for this tool's own launches, <code>null</code> otherwise
     */
    @Override
    public String resumes(String domain, String operation)
    {
        return PendingWorkRegistry.DEBUG_LAUNCH.domain().equals(domain) ? NAME : null;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String resumeKey = JsonUtils.extractStringArgument(params, "runKey"); //$NON-NLS-1$
        if (resumeKey != null && !resumeKey.isEmpty())
        {
            return PendingExecutor.resume(PendingWorkRegistry.DEBUG_LAUNCH, NAME, resumeKey,
                LAUNCH_RESUME_WAIT_MS, null);
        }
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
            return ToolResult.error("endpointTimeoutSeconds must be in (0, 50] - the same limit a " //$NON-NLS-1$
                + "single request may not outlive. Nothing was launched.") //$NON-NLS-1$
                .put("endpointTimeoutSeconds", endpointTimeout) //$NON-NLS-1$
                .put("nothingWasLaunchedOrUpdated", Boolean.TRUE) //$NON-NLS-1$
                .toJson();
        }
        if (endpointTimeout != null && waitForEndpoint == null)
        {
            return ToolResult.error("endpointTimeoutSeconds without waitForEndpoint names a wait " //$NON-NLS-1$
                + "that never happens - pass waitForEndpoint with it, or drop it. Nothing was " //$NON-NLS-1$
                + "launched.") //$NON-NLS-1$
                .put("nothingWasLaunchedOrUpdated", Boolean.TRUE) //$NON-NLS-1$
                .toJson();
        }

        if (configName != null && !configName.isEmpty())
        {
            return launchByConfigName(configName, updateBeforeLaunch, debugServerPort,
                externalObjectProject, externalObjectName, enableDump, startupOption,
                new ClientChoice(clientType, runMode), waitForEndpoint, endpointTimeout);
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
            externalObjectProject, externalObjectName, debugServerPort, enableDump, startupOption,
            new ClientChoice(clientType, runMode), waitForEndpoint, endpointTimeout);
    }

    /** The caller's clientType and runMode, as given. */
    private static final class ClientChoice
    {
        final String clientType;

        final String runMode;

        ClientChoice(String clientType, String runMode)
        {
            this.clientType = clientType == null || clientType.trim().isEmpty() ? null : clientType;
            this.runMode = runMode == null || runMode.trim().isEmpty() ? null : runMode;
        }

        boolean given()
        {
            return clientType != null || runMode != null;
        }
    }

    /**
     * Puts the decided client and run mode on the answer.
     *
     * @param result the answer
     * @param mode the decision
     * @param flagState what {@link ClientLaunchMode#reconcileFlag} did, or {@code null}
     * @param application the application, for its infobase's parameters; may be {@code null}
     */
    private static void describeClient(ToolResult result, ClientLaunchMode mode, String flagState,
        IApplication application)
    {
        result.put("clientType", mode.clientType) //$NON-NLS-1$
            .put("clientTypeSource", mode.clientTypeSource) //$NON-NLS-1$
            .put("runMode", mode.runMode) //$NON-NLS-1$
            .put("runModeSource", mode.runModeSource); //$NON-NLS-1$
        if (flagState != null)
        {
            result.put("runModeFlag", ClientLaunchMode.ORDINARY_FLAG) //$NON-NLS-1$
                .put("runModeFlagState", flagState) //$NON-NLS-1$
                .put("runModeFlagScope", ClientLaunchMode.FLAG_SCOPE) //$NON-NLS-1$
                .put("infobaseAdditionalParameters", //$NON-NLS-1$
                    application == null ? null : ClientLaunchMode.additionalParametersOf(application));
        }
    }

    private String launchByConfigName(String configName, boolean updateBeforeLaunch,
        int debugServerPort, String externalObjectProject, String externalObjectName,
        boolean enableDump, String startupOption, ClientChoice choice, String waitForEndpoint,
        Integer endpointTimeout)
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

            // An Attach configuration starts no client, so there is nobody to hand the startup
            // string to: accepting it here would lose it in silence.
            if (isAttach && startupOption != null)
            {
                return ToolResult.error("An Attach configuration starts no client, and " //$NON-NLS-1$
                    + "startupOption goes to the client that starts. Nothing was launched.") //$NON-NLS-1$
                    .put("launchConfiguration", config.getName()) //$NON-NLS-1$
                    .put("attach", true) //$NON-NLS-1$
                    .put("nothingWasLaunchedOrUpdated", Boolean.TRUE) //$NON-NLS-1$
                    .toJson();
            }
            if (isAttach && choice.given())
            {
                return ToolResult.error("An Attach configuration starts no client, and clientType " //$NON-NLS-1$
                    + "and runMode describe the client that starts. Nothing was launched.") //$NON-NLS-1$
                    .put("launchConfiguration", config.getName()) //$NON-NLS-1$
                    .put("attach", true) //$NON-NLS-1$
                    .put("nothingWasLaunchedOrUpdated", Boolean.TRUE) //$NON-NLS-1$
                    .toJson();
            }
            // The same for the endpoint: attach waits on a server that is already up, and a URL
            // the caller names would be polled while nothing the call started opened it. Answering
            // endpointReady would promise a readiness this launch never produced.
            if (isAttach && waitForEndpoint != null)
            {
                return ToolResult.error("An Attach configuration starts no client and opens no " //$NON-NLS-1$
                    + "endpoint, and waitForEndpoint waits on what the launch opens. Nothing was " //$NON-NLS-1$
                    + "launched.") //$NON-NLS-1$
                    .put("launchConfiguration", config.getName()) //$NON-NLS-1$
                    .put("attach", true) //$NON-NLS-1$
                    .put("nothingWasLaunchedOrUpdated", Boolean.TRUE) //$NON-NLS-1$
                    .toJson();
            }

            // A launch of this application still in flight answers with its key: the first client
            // has registered no target yet, and a second launch would start a second client.
            String inFlightRunKey = inFlightLaunchRunKey(effectiveAppId);
            if (inFlightRunKey != null)
            {
                return launchInFlightRefusal(effectiveAppId, inFlightRunKey);
            }

            // The external object is resolved BEFORE the already-running check: a running session
            // was started without these arguments, and answering success would lose them.
            if (effectiveAppId != null && DebugSessionBook.findActiveTarget(effectiveAppId) != null)
            {
                if (startupOption != null || externalObjectName != null && !externalObjectName.isEmpty()
                    || waitForEndpoint != null || choice.given())
                {
                    return ToolResult.error("A debug session for this application is already " //$NON-NLS-1$
                        + "running, and it was not started with these arguments. Stop it with " //$NON-NLS-1$
                        + "launch_debugger action=terminate, then launch again. Nothing was " //$NON-NLS-1$
                        + "launched or updated.") //$NON-NLS-1$
                        .put("launchConfiguration", config.getName()) //$NON-NLS-1$
                        .put("applicationId", effectiveAppId) //$NON-NLS-1$
                        .put("alreadyRunning", true) //$NON-NLS-1$
                        .put("nothingWasLaunchedOrUpdated", Boolean.TRUE) //$NON-NLS-1$
                        .toJson();
                }
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

            String openedObject = null;
            String objectProjectName = null;
            String openedObjectClassName = null;
            if (!isAttach && externalObjectName != null && !externalObjectName.isEmpty())
            {
                IProject objectProject = externalObjectProject == null || externalObjectProject.isEmpty()
                    ? (configProject == null || configProject.isEmpty() ? null
                        : ProjectResolver.resolve(configProject))
                    : ProjectResolver.resolve(externalObjectProject);
                if (objectProject == null)
                {
                    return ToolResult.error(externalObjectProject == null || externalObjectProject.isEmpty()
                        ? ProjectResolver.describeNotFound(configProject)
                        : ProjectResolver.describeNotFound(externalObjectProject))
                        .put("nothingWasLaunchedOrUpdated", Boolean.TRUE) //$NON-NLS-1$
                        .toJson();
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

            // The client is decided before anything is written: a contradiction in the arguments
            // must not cost an infobase update.
            ClientLaunchMode mode = null;
            IProject configuredProject = configProject == null || configProject.isEmpty() ? null
                : ProjectResolver.resolve(configProject);
            IApplication application = null;
            if (!isAttach)
            {
                mode = ClientLaunchMode.decide(choice.clientType, choice.runMode,
                    configuredProject == null ? null : ClientLaunchMode.projectRunMode(configuredProject),
                    true, LaunchConfigAccess.getClientTypeIdFor(config));
                if (mode.refusal != null)
                {
                    return ToolResult.error(mode.refusal)
                        .put("launchConfiguration", config.getName()) //$NON-NLS-1$
                        .put("nothingWasLaunchedOrUpdated", Boolean.TRUE) //$NON-NLS-1$
                        .toJson();
                }
                application = findApplication(configuredProject, effectiveAppId);
            }

            ApplicationUpdater.Result databaseUpdate = null;
            if (!isAttach && updateBeforeLaunch && configProject != null && !configProject.isEmpty())
            {
                String notReady = ProjectStateGuard.checkReadyOrError(configProject);
                if (notReady != null)
                {
                    return ToolResult.error(notReady).toJson();
                }
                databaseUpdate = updateDatabaseIfNeeded(configProject, effectiveAppId);
                String refusal = preLaunchRefusal(databaseUpdate);
                if (refusal != null)
                {
                    return ToolResult.error(refusal)
                        .put("launchConfiguration", config.getName()) //$NON-NLS-1$
                        .put("applicationId", effectiveAppId) //$NON-NLS-1$
                        .put("databaseUpdate", databaseUpdate.outcome.toString()) //$NON-NLS-1$
                        .put("databaseState", stateName(databaseUpdate)) //$NON-NLS-1$
                        .toJson();
                }
            }

            ILaunchConfiguration toLaunch = config;
            String flagState = null;
            if (!isAttach)
            {
                if (openedObject != null)
                {
                    toLaunch = LaunchConfigAccess.openingExternalObject(toLaunch,
                        objectProjectName, openedObject, openedObjectClassName);
                }
                if (debugServerPort > 0)
                {
                    toLaunch = LaunchConfigAccess.listeningOnDebugPort(toLaunch, debugServerPort);
                }
                if (startupOption != null)
                {
                    toLaunch = LaunchConfigAccess.withStartupOption(toLaunch, startupOption);
                }
                if (mode.clientTypeId != null)
                {
                    toLaunch = LaunchConfigAccess.withClientType(toLaunch, mode.clientTypeId);
                }
                flagState = application == null ? "not applied: the application was not resolved" //$NON-NLS-1$
                    : ClientLaunchMode.reconcileFlag(application, mode.wantsOrdinaryFlag());
            }

            // Copies for the answer below: it runs a second time, on the run's own thread, when
            // the launch is handed over and settles after the call has answered Pending.
            final ClientLaunchMode decidedMode = mode;
            final String appliedFlagState = flagState;
            final IApplication resolvedApplication = application;
            final String openedObjectName = openedObject;
            final ApplicationUpdater.Result updateDone = databaseUpdate;
            Function<LaunchOutcome, String> settledAnswer = settled ->
            {
                if (!settled.started)
                {
                    return refusalFor(settled, "Could not launch the debug session").toJson(); //$NON-NLS-1$
                }
                ToolResult result = ToolResult.success()
                    .put("launchConfiguration", config.getName()) //$NON-NLS-1$
                    .put("configurationType", typeId) //$NON-NLS-1$
                    .put("attach", isAttach) //$NON-NLS-1$
                    .put("mode", "debug"); //$NON-NLS-1$ //$NON-NLS-2$
                if (updateDone != null)
                {
                    result.put("databaseUpdate", updateDone.outcome.toString()); //$NON-NLS-1$
                }
                if (decidedMode != null)
                {
                    describeClient(result, decidedMode, appliedFlagState, resolvedApplication);
                }
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
                if (openedObjectName != null)
                {
                    result.put("externalObjectOpened", openedObjectName); //$NON-NLS-1$
                }
                if (startupOption != null)
                {
                    result.put("startupOption", startupOption); //$NON-NLS-1$
                }
                String endpointNote = waitForEndpoint(waitForEndpoint, endpointTimeout, result);
                if (endpointNote != null)
                {
                    return endpointNote;
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
            };
            LaunchOutcome outcome = performLaunch(toLaunch, isAttach, effectiveAppId, settledAnswer);
            if (outcome.pendingAnswer != null)
            {
                return outcome.pendingAnswer;
            }
            return settledAnswer.apply(outcome);
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

    private String launchDebug(String projectName, String applicationId,
        boolean updateBeforeLaunch, String externalObjectProject, String externalObjectName,
        int debugServerPort, boolean enableDump, String startupOption, ClientChoice choice,
        String waitForEndpoint, Integer endpointTimeout)
    {
        LAUNCH_LOCK.lock();
        try
        {
            IProject project = ProjectResolver.resolve(projectName);
            if (project == null)
            {
                return ProjectResolver.notFound(projectName).toJson();
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

            // A launch of this application still in flight answers with its key: the first client
            // has registered no target yet, and a second launch would start a second client.
            String inFlightRunKey = inFlightLaunchRunKey(applicationId);
            if (inFlightRunKey != null)
            {
                return launchInFlightRefusal(applicationId, inFlightRunKey);
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
                    return ProjectResolver.notFound(externalObjectProject).toJson();
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

            // The launch configuration is looked up before the update so the client can be
            // decided first: a contradiction in the arguments must not cost an infobase update.
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
            ILaunchConfiguration existingConfig =
                LaunchConfigAccess.findLaunchConfig(launchManager, configType, projectName, applicationId);
            ClientLaunchMode mode = ClientLaunchMode.decide(choice.clientType, choice.runMode,
                ClientLaunchMode.projectRunMode(project), existingConfig != null,
                existingConfig == null ? null : LaunchConfigAccess.getClientTypeIdFor(existingConfig));
            if (mode.refusal != null)
            {
                return ToolResult.error(mode.refusal)
                    .put("project", projectName) //$NON-NLS-1$
                    .put("applicationId", applicationId) //$NON-NLS-1$
                    .put("nothingWasLaunchedOrUpdated", Boolean.TRUE) //$NON-NLS-1$
                    .toJson();
            }

            // Before the update and before a configuration is created: a session that is already
            // running answers without touching the infobase or the launch configurations.
            // Best-effort: EDT registers the debug target asynchronously, so a target launched a
            // moment ago may not be visible yet - LAUNCH_LOCK closes the concurrent window.
            if (applicationId != null && DebugSessionBook.findActiveTarget(applicationId) != null)
            {
                String runningConfig = existingConfig != null ? existingConfig.getName() : null;
                // A running session was started without these arguments; answering success would
                // lose them. The fix is to stop the session, not to drop the arguments.
                if (startupOption != null || openedObject != null || waitForEndpoint != null || choice.given())
                {
                    return ToolResult.error("A debug session for this application is already " //$NON-NLS-1$
                        + "running, and it was not started with these arguments. Stop it with " //$NON-NLS-1$
                        + "launch_debugger action=terminate, then launch again. Nothing was " //$NON-NLS-1$
                        + "launched or updated.") //$NON-NLS-1$
                        .put("project", projectName) //$NON-NLS-1$
                        .put("applicationId", applicationId) //$NON-NLS-1$
                        .put("launchConfiguration", runningConfig) //$NON-NLS-1$
                        .put("alreadyRunning", true) //$NON-NLS-1$
                        .put("nothingWasLaunchedOrUpdated", Boolean.TRUE) //$NON-NLS-1$
                        .toJson();
                }
                return ToolResult.success()
                    .put("project", projectName) //$NON-NLS-1$
                    .put("applicationId", applicationId) //$NON-NLS-1$
                    .put("launchConfiguration", runningConfig) //$NON-NLS-1$
                    .put("alreadyRunning", true) //$NON-NLS-1$
                    .put("attach", false) //$NON-NLS-1$
                    .put("mode", "debug") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("message", "A debug session for this application is already running - launch skipped.") //$NON-NLS-1$
                    .toJson();
            }

            ApplicationUpdater.Result databaseUpdate = null;
            if (updateBeforeLaunch && appManager != null && application != null)
            {
                databaseUpdate = updateDatabase(appManager, application);
                String refusal = preLaunchRefusal(databaseUpdate);
                if (refusal != null)
                {
                    return ToolResult.error(refusal)
                        .put("project", projectName) //$NON-NLS-1$
                        .put("applicationId", applicationId) //$NON-NLS-1$
                        .put("databaseUpdate", databaseUpdate.outcome.toString()) //$NON-NLS-1$
                        .put("databaseState", stateName(databaseUpdate)) //$NON-NLS-1$
                        .toJson();
                }
            }

            ILaunchConfiguration matchingConfig = existingConfig;
            boolean autoCreatedConfig = false;
            if (matchingConfig == null)
            {
                try
                {
                    matchingConfig = LaunchConfigAccess.createRuntimeClientConfig(launchManager, configType,
                        projectName, applicationId, applicationName, mode.clientTypeId);
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

            if (startupOption != null)
            {
                matchingConfig = LaunchConfigAccess.withStartupOption(matchingConfig, startupOption);
            }
            // A configuration created here already carries the client; an existing one keeps its
            // own on disk and starts this launch with the decided one.
            if (!autoCreatedConfig && mode.clientTypeId != null)
            {
                matchingConfig = LaunchConfigAccess.withClientType(matchingConfig, mode.clientTypeId);
            }
            String flagState = application == null ? "not applied: the application was not resolved" //$NON-NLS-1$
                : ClientLaunchMode.reconcileFlag(application, mode.wantsOrdinaryFlag());

            // Copies for the answer below: it runs a second time, on the run's own thread, when
            // the launch is handed over and settles after the call has answered Pending.
            final String launchedConfigTypeId = LaunchConfigAccess.getConfigTypeId(matchingConfig);
            final boolean createdConfig = autoCreatedConfig;
            final String openedObjectName = openedObject;
            final ApplicationUpdater.Result updateDone = databaseUpdate;
            final IApplication resolvedApplication = application;
            Function<LaunchOutcome, String> settledAnswer = settled ->
            {
                if (!settled.started)
                {
                    return refusalFor(settled, "Debug session launch failed") //$NON-NLS-1$
                        .put("project", projectName) //$NON-NLS-1$
                        .put("applicationId", applicationId) //$NON-NLS-1$
                        .put("launchConfiguration", configName) //$NON-NLS-1$
                        .toJson();
                }
                ToolResult successResult = ToolResult.success()
                    .put("project", projectName) //$NON-NLS-1$
                    .put("applicationId", applicationId) //$NON-NLS-1$
                    .put("launchConfiguration", configName) //$NON-NLS-1$
                    .put("configurationType", launchedConfigTypeId) //$NON-NLS-1$
                    .put("autoCreatedConfiguration", createdConfig) //$NON-NLS-1$
                    .put("attach", false) //$NON-NLS-1$
                    .put("mode", "debug") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("externalObjectOpened", openedObjectName) //$NON-NLS-1$
                    .put("startupOption", startupOption) //$NON-NLS-1$
                    .put("debugServerPort", debugServerPort > 0 ? Integer.valueOf(debugServerPort) : null) //$NON-NLS-1$
                    .put("message", createdConfig //$NON-NLS-1$
                        ? "Debug session is now running (a launch configuration was auto-created for it)"
                        : "Debug session is now running");
                if (updateDone != null)
                {
                    successResult.put("databaseUpdate", updateDone.outcome.toString()); //$NON-NLS-1$
                }
                describeClient(successResult, mode, flagState, resolvedApplication);
                String endpointNote = waitForEndpoint(waitForEndpoint, endpointTimeout, successResult);
                if (endpointNote != null)
                {
                    return endpointNote;
                }
                return successResult.toJson();
            };
            LaunchOutcome outcome = performLaunch(matchingConfig, false, applicationId, settledAnswer);
            if (outcome.pendingAnswer != null)
            {
                return outcome.pendingAnswer;
            }
            return settledAnswer.apply(outcome);
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

    /**
     * Waits for the endpoint a launched client is supposed to open, when the caller asked for one.
     * <p>
     * The launch stays whatever it is: a client that never gets there is reported, not killed -
     * the caller watches it through debug_status. The answer carries what the wait saw:
     * {@code endpointReady}, {@code endpointWaitedSeconds}, {@code endpointHttpStatus} on success,
     * or the last problem when nothing answered in the budget.
     * </p>
     *
     * @param waitForEndpoint the URL to poll, or <code>null</code> to skip the wait.
     * @param endpointTimeout the budget in seconds, or <code>null</code> for the default of 20.
     * @param result the answer being built.
     * @return a refusal JSON when the endpoint never answered, or <code>null</code>
     */
    private static String waitForEndpoint(String waitForEndpoint, Integer endpointTimeout,
        ToolResult result)
    {
        if (waitForEndpoint == null)
        {
            return null;
        }
        int seconds = endpointTimeout == null ? 20 : endpointTimeout.intValue();
        ru.aiedt.mcp.server.support.EndpointWaiter.Outcome outcome =
            ru.aiedt.mcp.server.support.EndpointWaiter.waitFor(waitForEndpoint, seconds * 1000L);
        result.put("endpointReady", outcome.ready); //$NON-NLS-1$
        result.put("endpointWaitedSeconds", Long.valueOf(outcome.waitedMs / 1000L)); //$NON-NLS-1$
        result.put("endpointAsked", outcome.asked); //$NON-NLS-1$
        if (outcome.ready)
        {
            result.put("endpointHttpStatus", Integer.valueOf(outcome.httpStatus)); //$NON-NLS-1$
            return null;
        }
        ToolResult refusal = ToolResult.error("The endpoint " + waitForEndpoint //$NON-NLS-1$
            + " never answered within " + seconds + "s" //$NON-NLS-1$
            + (outcome.lastProblem != null ? " (" + outcome.lastProblem + ")" : "") //$NON-NLS-1$ //$NON-NLS-2$
            + ". The client itself is NOT stopped - it may still come up; watch it with " //$NON-NLS-1$
            + "debug_status.") //$NON-NLS-1$
            .put("endpointReady", false) //$NON-NLS-1$
            .put("endpointWaitedSeconds", Long.valueOf(outcome.waitedMs / 1000L)); //$NON-NLS-1$
        return refusal.toJson();
    }

    /**
     * The pre-launch update of a launch by configuration name. An attach-mode application, a
     * project that does not resolve and a missing application manager skip the update.
     * <p>
     * Shared with the YAXUnit launches, which were declaring this step and running none of it:
     * a launch that updates the infobase has to do it the one way, or the two ways drift.
     * </p>
     *
     * @param projectName the project the launch configuration names
     * @param applicationId the application the configuration launches
     * @return the update outcome, or {@code null} when no update applies
     */
    static ApplicationUpdater.Result updateDatabaseIfNeeded(String projectName, String applicationId)
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
        return updateResolvedApplication(appManager, project, applicationId);
    }

    /**
     * Resolves the application a launch names and brings its infobase up to date. An application
     * that is not registered, or that the manager fails to resolve, is an outcome the launch is
     * refused on, not a reason to skip the update.
     *
     * @param appManager the application manager
     * @param project the project the application belongs to
     * @param applicationId the application id
     * @return the update outcome; {@code APPLICATION_NOT_FOUND} or {@code FAILED} when the
     *     application does not resolve
     */
    static ApplicationUpdater.Result updateResolvedApplication(IApplicationManager appManager,
        IProject project, String applicationId)
    {
        String projectName = project == null ? null : project.getName();
        try
        {
            Optional<IApplication> appOpt = appManager.getApplication(project, applicationId);
            if (!appOpt.isPresent())
            {
                return ApplicationUpdater.Result.appNotFound(applicationId, projectName);
            }
            return updateDatabase(appManager, appOpt.get());
        }
        catch (ApplicationException e)
        {
            Activator.logError("Failed to resolve the application for a pre-launch database update", e); //$NON-NLS-1$
            return ApplicationUpdater.Result.failed("Could not resolve application '" + applicationId //$NON-NLS-1$
                + "': " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * The pre-launch update of a resolved application, with the stored dump-info reading.
     *
     * @param appManager the application manager
     * @param application the application
     * @return the update outcome
     */
    private static ApplicationUpdater.Result updateDatabase(IApplicationManager appManager,
        IApplication application)
    {
        return updateDatabase(appManager, application, DatabaseUpdater.dumpInfoOf(application));
    }

    /**
     * The pre-launch update. A foreign dump-info format stops before the manager is asked to check
     * or to load; otherwise {@link ApplicationUpdater} loads the changes and reads the state the
     * infobase is left in.
     *
     * @param appManager the application manager
     * @param application the application
     * @param dumpInfo the stored dump-info reading, or {@code null} when there is nothing to compare
     * @return the update outcome
     */
    static ApplicationUpdater.Result updateDatabase(IApplicationManager appManager,
        IApplication application, DumpInfoProbe.Reading dumpInfo)
    {
        return ApplicationUpdater.updateIfNeeded(appManager, application, dumpInfo);
    }

    /**
     * Decides whether a launch goes on after its pre-launch update. Only an infobase that is up to
     * date, or an update that did not apply, lets the client start: an update another process is
     * running, one that ended in any state other than updated, an unresolved application and a
     * failed load all refuse the launch.
     *
     * @param update the update outcome, or {@code null} when no update was attempted
     * @return the refusal sentence, or {@code null} when the launch may go on
     */
    static String preLaunchRefusal(ApplicationUpdater.Result update)
    {
        if (update == null || update.isUpToDate() || update.outcome == ApplicationUpdater.Outcome.SKIPPED)
        {
            return null;
        }
        StringBuilder why = new StringBuilder("The infobase is not up to date before launch (") //$NON-NLS-1$
            .append(update.outcome);
        if (update.stateAfter != null)
        {
            why.append(", state ").append(update.stateAfter); //$NON-NLS-1$
        }
        why.append(')');
        if (update.errorMessage != null)
        {
            why.append(": ").append(update.errorMessage); //$NON-NLS-1$
        }
        else if (update.hint != null)
        {
            why.append(": ").append(update.hint); //$NON-NLS-1$
        }
        why.append(" Nothing was launched. Retry with updateBeforeLaunch=false to launch against " //$NON-NLS-1$
            + "the infobase as it stands."); //$NON-NLS-1$
        return why.toString();
    }

    /**
     * The state an update left the infobase in, for the answer.
     *
     * @param update the update outcome
     * @return the state name, or {@code null} when the outcome carries none
     */
    private static String stateName(ApplicationUpdater.Result update)
    {
        return update.stateAfter == null ? null : update.stateAfter.name();
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

        /**
         * The whole answer to return instead of this outcome, for a launch handed to a
         * {@code runKey}: the caller's own answer-building does not apply to work that is still
         * running, so the envelope built where the hand-over happened is the answer itself.
         */
        final String pendingAnswer;

        LaunchOutcome(boolean started, String refusal, String observed,
            List<Map<String, Object>> dialogsSeen)
        {
            this(started, refusal, observed, dialogsSeen, null);
        }

        LaunchOutcome(boolean started, String refusal, String observed,
            List<Map<String, Object>> dialogsSeen, String pendingAnswer)
        {
            this.started = started;
            this.refusal = refusal;
            this.observed = observed;
            this.dialogsSeen = dialogsSeen;
            this.pendingAnswer = pendingAnswer;
        }

        static LaunchOutcome ok()
        {
            return new LaunchOutcome(true, null, "running", java.util.Collections.emptyList()); //$NON-NLS-1$
        }

        /**
         * @param answer the Pending answer built for a launch that outlived its inline wait
         * @return the outcome that carries it as the answer to return
         */
        static LaunchOutcome handOver(String answer)
        {
            return new LaunchOutcome(false, "the launch is still running", "pending", null, answer); //$NON-NLS-1$ //$NON-NLS-2$
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
     * <p>
     * The launch is posted to the UI thread and the calling thread waits on it with a window, rather
     * than blocking inside the launch until it returns: a modal question the environment opens
     * inside the launch - an update asking whether to go ahead - holds the launch open for as long
     * as nobody answers it, and a caller parked inside {@code syncExec} sees neither the question
     * nor anything else. Within the window the workbench's modals are compared with what was up
     * before the launch; a question that appeared since is answered at once as a Pending envelope
     * naming the dialog, and the launch keeps running under a {@code runKey} the caller polls once
     * the dialog is answered. A launch that returns within the window is answered exactly as it was
     * before the window existed.
     * </p>
     *
     * @param config the configuration to launch.
     * @param isAttach whether it attaches rather than starts a client.
     * @param applicationId the application the launch starts, held as an in-flight reservation
     *            when the launch is handed over; may be <code>null</code> when unknown
     * @param answer how the call answers a settled launch, started or refused; a launch that
     *            outlives the wait runs the same answer from its continuation
     * @return the outcome; {@link LaunchOutcome#started} false carries the refusal, and a
     *         non-null {@link LaunchOutcome#pendingAnswer} carries the whole answer to return
     */
    private LaunchOutcome performLaunch(ILaunchConfiguration config, boolean isAttach,
        String applicationId, Function<LaunchOutcome, String> answer)
    {
        List<Map<String, Object>> dialogsBefore = ModalDialogWatch.current().getDialogs();
        LaunchUnderWay launch = startLaunch(config);
        long deadline = System.currentTimeMillis() + LAUNCH_WAIT_MS;
        while (true)
        {
            boolean dialogUp = !newDialogs(dialogsBefore,
                ModalDialogWatch.current().getDialogs()).isEmpty();
            LaunchWaitChoice choice = decideLaunchWait(launch.hasReturned(),
                System.currentTimeMillis() >= deadline, dialogUp);
            switch (choice)
            {
            case ANSWER_THE_LAUNCH:
                return outcomeOfLaunch(launch, isAttach, dialogsBefore);
            case ANSWER_BLOCKED_BY_DIALOG:
            case ANSWER_PENDING:
                return LaunchOutcome.handOver(pendingLaunchAnswer(launch, isAttach, dialogsBefore,
                    applicationId, answer));
            default:
                break;
            }
            try
            {
                Thread.sleep(LAUNCH_STEP_MS);
            }
            catch (InterruptedException stopped)
            {
                Thread.currentThread().interrupt();
                return new LaunchOutcome(false, "the wait for the launch was interrupted", //$NON-NLS-1$
                    "interrupted", null); //$NON-NLS-1$
            }
        }
    }

    /**
     * How long the calling thread gives the launch to come back on its own, in milliseconds.
     * <p>
     * A launch that neither returns nor opens a question within this window is handed to a
     * {@code runKey}: the request a client holds does not outlive much more than this, while the
     * launch itself may go on for as long as the client it starts takes to appear.
     * </p>
     */
    static final long LAUNCH_WAIT_MS = 10_000L;

    /** How often the launch and the workbench's modals are re-read while waiting, in milliseconds. */
    static final long LAUNCH_STEP_MS = 250L;

    /** What the bounded launch wait does next. */
    enum LaunchWaitChoice
    {
        /** Nothing new: read both again after a step. */
        KEEP_WAITING,

        /** The launch returned: answer what it came to. */
        ANSWER_THE_LAUNCH,

        /** A modal question opened since the launch began: answer the Pending envelope now. */
        ANSWER_BLOCKED_BY_DIALOG,

        /** The window is over with the launch still open: answer the Pending envelope now. */
        ANSWER_PENDING
    }

    /**
     * Decides the bounded launch wait from plain values, so the decision is testable with no
     * display, no launch and no clock.
     *
     * @param launchReturned whether the launch call has come back
     * @param deadlineReached whether the wait window is over
     * @param newDialogUp whether a modal opened since the launch began
     * @return what the wait does next
     */
    static LaunchWaitChoice decideLaunchWait(boolean launchReturned, boolean deadlineReached,
        boolean newDialogUp)
    {
        if (launchReturned)
        {
            return LaunchWaitChoice.ANSWER_THE_LAUNCH;
        }
        if (newDialogUp)
        {
            return LaunchWaitChoice.ANSWER_BLOCKED_BY_DIALOG;
        }
        if (deadlineReached)
        {
            return LaunchWaitChoice.ANSWER_PENDING;
        }
        return LaunchWaitChoice.KEEP_WAITING;
    }

    /**
     * A launch that has been started and may still be running.
     * <p>
     * The seam between the wait, which reads the launch without holding it, and the continuation a
     * Pending answer leaves behind, which blocks until the launch returns and renders what it came
     * to.
     * </p>
     */
    interface LaunchUnderWay
    {
        /**
         * @return whether the launch call has come back
         */
        boolean hasReturned();

        /**
         * @return the launch it created; valid once {@link #hasReturned()} is true
         */
        ILaunch launch();

        /**
         * @return why the launch call failed, or <code>null</code> when it did not; valid once
         *         {@link #hasReturned()} is true
         */
        String error();

        /**
         * Blocks until the launch call comes back.
         *
         * @throws InterruptedException if the wait is interrupted
         */
        void awaitReturn() throws InterruptedException;
    }

    /**
     * Starts the launch wherever the environment can run it.
     * <p>
     * With a display the launch is posted to the UI thread - the thread the platform's launch
     * delegate expects to be called on - and the calling thread is free to watch for dialogs.
     * Without one there is no UI thread to hold a modal, so the launch runs inline and has already
     * returned by the time this method answers.
     * </p>
     *
     * @param config the configuration to launch
     * @return the launch under way
     */
    private static LaunchUnderWay startLaunch(ILaunchConfiguration config)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
        {
            String[] error = {null};
            ILaunch[] launched = {null};
            try
            {
                launched[0] = config.launch(ILaunchManager.DEBUG_MODE, null);
            }
            catch (Exception e)
            {
                Activator.logError("Failed to launch debug session", e); //$NON-NLS-1$
                error[0] = e.getMessage();
            }
            return new FinishedLaunch(launched[0], error[0]);
        }

        CountDownLatch returned = new CountDownLatch(1);
        String[] error = {null};
        ILaunch[] launched = {null};
        display.asyncExec(() -> {
            try
            {
                launched[0] = config.launch(ILaunchManager.DEBUG_MODE, null);
            }
            catch (Exception e)
            {
                Activator.logError("Failed to launch debug session", e); //$NON-NLS-1$
                error[0] = e.getMessage();
            }
            finally
            {
                returned.countDown();
            }
        });
        return new PostedLaunch(returned, launched, error);
    }

    /** A launch that ran inline and has already returned. */
    private static final class FinishedLaunch implements LaunchUnderWay
    {
        private final ILaunch launch;

        private final String error;

        FinishedLaunch(ILaunch launch, String error)
        {
            this.launch = launch;
            this.error = error;
        }

        @Override
        public boolean hasReturned()
        {
            return true;
        }

        @Override
        public ILaunch launch()
        {
            return launch;
        }

        @Override
        public String error()
        {
            return error;
        }

        @Override
        public void awaitReturn()
        {
            // already back
        }
    }

    /** A launch posted to the UI thread, read through the latch its completion counts down. */
    private static final class PostedLaunch implements LaunchUnderWay
    {
        private final CountDownLatch returned;

        private final ILaunch[] launched;

        private final String[] error;

        PostedLaunch(CountDownLatch returned, ILaunch[] launched, String[] error)
        {
            this.returned = returned;
            this.launched = launched;
            this.error = error;
        }

        @Override
        public boolean hasReturned()
        {
            return returned.getCount() == 0;
        }

        @Override
        public ILaunch launch()
        {
            return launched[0];
        }

        @Override
        public String error()
        {
            return error[0];
        }

        @Override
        public void awaitReturn() throws InterruptedException
        {
            returned.await();
        }
    }

    /**
     * What a returned launch came to, observed the way the synchronous path always observed it.
     *
     * @param launch the launch that has returned
     * @param isAttach whether the configuration attaches rather than starts a client
     * @param dialogsBefore what was open before the launch, for the refusals' dialog aside
     * @return the outcome
     */
    private static LaunchOutcome outcomeOfLaunch(LaunchUnderWay launch, boolean isAttach,
        List<Map<String, Object>> dialogsBefore)
    {
        LaunchOutcome outcome;
        if (launch.error() != null)
        {
            outcome = new LaunchOutcome(false, launch.error(), "threw", null); //$NON-NLS-1$
        }
        else if (launch.launch() == null)
        {
            outcome = new LaunchOutcome(false, "the environment created no launch", //$NON-NLS-1$
                "notCreated", null); //$NON-NLS-1$
        }
        else
        {
            outcome = watchLaunch(launch.launch(), isAttach);
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
     * Hands a launch that outlived its inline wait to a {@code runKey} and answers the Pending
     * envelope the caller polls that key with.
     * <p>
     * The envelope is the one every long operation answers with, so a modal that is holding the
     * launch is named in it - title, message, buttons - together with the key. Nothing here presses
     * a button: which answer the environment's question gets is a person's decision or an explicit
     * {@code answer_dialog} call, never the launch's own.
     * </p>
     * <p>
     * The application is reserved as in flight BEFORE the run is dispatched, and the reservation
     * leaves in the run body's own {@code finally}: the body can settle before this method returns,
     * and a reservation written after that would never be released. That finally runs after the
     * launch has returned, after the wait for the debug target's registration is over and after
     * the caller's answer was built - the reservation covers the whole continuation, not only the
     * launch call. A run cancelled before its body claimed its start never enters that finally,
     * so the work-exit door marks such a reservation instead, and a reader releases it once the
     * launch it would have waited on has returned.
     * </p>
     *
     * @param launch the launch that has not returned
     * @param isAttach whether the configuration attaches rather than starts a client
     * @param dialogsBefore what was open before the launch
     * @param applicationId the application the launch starts, held as in flight until it settles;
     *            <code>null</code> or empty reserves nothing
     * @param answer how the original call answers a settled launch, started or refused; the run
     *            body runs it once the launch returns
     * @return the Pending answer for the caller
     */
    static String pendingLaunchAnswer(LaunchUnderWay launch, boolean isAttach,
        List<Map<String, Object>> dialogsBefore, String applicationId,
        Function<LaunchOutcome, String> answer)
    {
        String runKey = PendingWorkRegistry.computeRunKey(NAME,
            String.valueOf(System.nanoTime()));
        boolean reserveApplication = applicationId != null && !applicationId.isEmpty();
        InFlightLaunch reservation = reserveApplication ? new InFlightLaunch(runKey, launch) : null;
        if (reservation != null)
        {
            LAUNCHES_IN_FLIGHT.put(applicationId, reservation);
        }
        PendingWorkRegistry.PendingEntry entry =
            PendingWorkRegistry.DEBUG_LAUNCH.getOrStart(runKey,
                ongoing ->
                {
                    try
                    {
                        return awaitLaunchOutcome(launch, isAttach, dialogsBefore, answer);
                    }
                    finally
                    {
                        if (reservation != null)
                        {
                            LAUNCHES_IN_FLIGHT.remove(applicationId, reservation);
                        }
                    }
                });
        if (reservation != null)
        {
            // The body's own finally runs before this door settles, so a reservation still held
            // here means the body never ran: the run was cancelled before it claimed its start.
            // The launch it was to wait on may still be parked on a question, and the reservation
            // holds until that launch has returned - the liveness a reader then sweeps on.
            entry.attachWorkExit(() ->
            {
                if (LAUNCHES_IN_FLIGHT.get(applicationId) == reservation)
                {
                    reservation.bodyNeverRan = true;
                }
            });
        }
        if (entry.startedBy == null)
        {
            entry.startedBy = NAME;
        }
        return PendingEnvelope.mark(ToolResult.success()
            .put("operation", NAME) //$NON-NLS-1$
            .put("status", "Pending") //$NON-NLS-1$ //$NON-NLS-2$
            .put("runKey", runKey) //$NON-NLS-1$
            .put("elapsedMs", entry.elapsedMs()) //$NON-NLS-1$
            .put("waitedMs", Long.valueOf(LAUNCH_WAIT_MS)) //$NON-NLS-1$
            .put("note", "The launch is still running. When a modal question holds it open, read " //$NON-NLS-1$
                + "the dialogs this answer names and answer one with answer_dialog or in EDT; " //$NON-NLS-1$
                + "this plugin presses nothing itself. Nothing is lost: the launch keeps running " //$NON-NLS-1$
                + "and the answer of what it came to is collected with this runKey.")
            .put("hint", "Call this tool again with runKey=\"" + runKey //$NON-NLS-1$
                + "\" once the dialog is answered (or to keep waiting).")) //$NON-NLS-1$
            .toJson();
    }

    /**
     * What a handed-over launch came to, rendered for the caller that polls its {@code runKey}.
     * <p>
     * The answer is the one the original call would have given, handed in as a function: the
     * continuation runs the same response and the same readiness logic - the endpoint wait
     * included - so a caller that asked for {@code waitForEndpoint} is not told the operation is
     * done while the endpoint it named has yet to answer.
     * </p>
     *
     * @param launch the launch that has not returned
     * @param isAttach whether the configuration attaches rather than starts a client
     * @param dialogsBefore what was open before the launch
     * @param answer how the original call answers a settled launch, started or refused
     * @return the answer of what the launch came to
     */
    static String awaitLaunchOutcome(LaunchUnderWay launch, boolean isAttach,
        List<Map<String, Object>> dialogsBefore, Function<LaunchOutcome, String> answer)
    {
        try
        {
            launch.awaitReturn();
        }
        catch (InterruptedException stopped)
        {
            Thread.currentThread().interrupt();
            return ToolResult.error("The wait for the handed-over launch was interrupted") //$NON-NLS-1$
                .put("operation", NAME) //$NON-NLS-1$
                .toJson();
        }
        return answer.apply(outcomeOfLaunch(launch, isAttach, dialogsBefore));
    }

    /**
     * The {@code runKey} of the launch still in flight for an application, when there is one.
     * <p>
     * In flight means the run's continuation has not finished. The launch it waits on may already
     * have returned - the continuation then waits on the debug target's registration, and the
     * reservation holds through that wait, so a launch call's return releases nothing here. The
     * one reservation a reader releases is an orphaned one: its run was cancelled before the body
     * claimed its start, no continuation will wait on anything, and once that launch has returned
     * there is nothing left the reservation protects. Whether the key can still be polled is a
     * separate question, answered where the refusal is built: a cancelled run loses its registry
     * entry while its launch is still parked, and a key nothing tracks answers
     * {@code runKey not found}.
     * </p>
     *
     * @param applicationId the application a new launch is being considered for
     * @return the runKey of the in-flight launch, or <code>null</code> when no launch of it is in
     *         flight
     */
    static String inFlightLaunchRunKey(String applicationId)
    {
        if (applicationId == null || applicationId.isEmpty())
        {
            return null;
        }
        InFlightLaunch inFlight = LAUNCHES_IN_FLIGHT.get(applicationId);
        if (inFlight == null)
        {
            return null;
        }
        if (inFlight.bodyNeverRan && inFlight.launch.hasReturned())
        {
            LAUNCHES_IN_FLIGHT.remove(applicationId, inFlight);
            return null;
        }
        return inFlight.runKey;
    }

    /**
     * The refusal a launch call gets while an earlier launch of the same application is still in
     * flight, saying what the caller can actually do next.
     * <p>
     * Whether the key can be polled is read at refusal time, not remembered from when the run was
     * started: a cancelled run loses its registry entry while its launch is still parked on a
     * question, and sending the caller to poll a key nothing holds answers {@code runKey not
     * found} forever. With the entry still tracked the refusal names the key; without it the
     * refusal says the launch is still going and to retry once it has settled.
     * </p>
     *
     * @param applicationId the application the in-flight launch starts
     * @param runKey the key the in-flight run was started under
     * @return the refusal, ready to return
     */
    static String launchInFlightRefusal(String applicationId, String runKey)
    {
        if (runKey != null && PendingWorkRegistry.DEBUG_LAUNCH.get(runKey) != null)
        {
            return launchInFlightAnswer(applicationId, runKey);
        }
        return ToolResult
            .error("A launch of this application is still in flight: an earlier call handed it " //$NON-NLS-1$
                + "to a run while a modal question or the wait window held it open, and that run " //$NON-NLS-1$
                + "is no longer tracked, so there is no runKey to poll. The launch itself is " //$NON-NLS-1$
                + "still going - answer the question it waits on in EDT or wait for it to " //$NON-NLS-1$
                + "settle, then launch again. Nothing was launched.") //$NON-NLS-1$
            .put("applicationId", applicationId) //$NON-NLS-1$
            .put("launchInFlight", true) //$NON-NLS-1$
            .put("hint", "Answer the dialog the launch waits on in EDT or wait for it to settle, " //$NON-NLS-1$
                + "then call this tool again.") //$NON-NLS-1$
            .put("nothingWasLaunchedOrUpdated", Boolean.TRUE) //$NON-NLS-1$
            .toJson();
    }

    /**
     * The refusal a launch call gets while an earlier launch of the same application is still in
     * flight under a {@code runKey}.
     * <p>
     * The earlier launch has not registered its debug target yet, so the already-running check
     * cannot see it; what it came to is not known until somebody answers what holds it open, and a
     * second client started beside the first is exactly what the reservation exists to prevent.
     * The answer names the key to poll instead.
     * </p>
     *
     * @param applicationId the application the in-flight launch starts
     * @param runKey the key its Pending envelope carried
     * @return the refusal, ready to return
     */
    static String launchInFlightAnswer(String applicationId, String runKey)
    {
        return ToolResult
            .error("A launch of this application is still in flight: an earlier call handed it " //$NON-NLS-1$
                + "to a runKey while a modal question or the wait window held it open, and what it " //$NON-NLS-1$
                + "came to is not known yet. Poll that key instead of starting a second client - " //$NON-NLS-1$
                + "answering the question one of them waits on could start two. Nothing was " //$NON-NLS-1$
                + "launched.") //$NON-NLS-1$
            .put("applicationId", applicationId) //$NON-NLS-1$
            .put("launchInFlight", true) //$NON-NLS-1$
            .put("runKey", runKey) //$NON-NLS-1$
            .put("hint", "Call this tool again with runKey=\"" + runKey //$NON-NLS-1$ //$NON-NLS-2$
                + "\" to collect the outcome.") //$NON-NLS-1$
            .put("nothingWasLaunchedOrUpdated", Boolean.TRUE) //$NON-NLS-1$
            .toJson();
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
