/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchListener;
import org.eclipse.debug.core.ILaunchManager;

import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.ApplicationUpdater;
import ru.aiedt.mcp.server.wire.GsonHolder;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.support.TimeoutArgs;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.JUnitReportFormatter;
import ru.aiedt.mcp.server.support.JUnitRunOutcome;
import ru.aiedt.mcp.server.support.JUnitXmlReader;
import ru.aiedt.mcp.server.support.LaunchConfigAccess;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.ProjectStateGuard;
import ru.aiedt.mcp.server.support.RunReceipts;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Runs YAXUnit tests for a 1C:Enterprise project. Updates the infobase unless the call turns that
 * off, launches the runtime client with the {@code RunUnitTests} startup parameter, polls until the
 * launch terminates or the polling window expires, then parses the JUnit XML report. The alias
 * answers JSON on every branch: a refusal, a run still in progress, and a finished result. The
 * markdown report rides in {@code output}. Non-blocking: a launch still running at timeout returns
 * that JSON with {@code **Pending**} in {@code output}, and the caller re-invokes with the same
 * arguments - the next call picks up that run's report rather than starting another, and a call
 * after a report was handed over starts a new run unless it asked for the recent report with
 * {@code reuseRecent=true}.
 */
public final class YaxunitTestRunner
    implements IMcpTool
{
    public static final String NAME = "run_yaxunit_tests"; //$NON-NLS-1$

    private static final int DEFAULT_TIMEOUT = 60;

    private static final int POLL_INTERVAL_MS = 1000;

    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    /**
     * How long a report of a finished run stays readable for a caller that is still waiting on it.
     * <p>
     * A run started here reports {@code **Pending**} while it lasts, and the report is written
     * after the caller has been answered. The caller that comes back for it is picking up the run
     * it started, not asking for a new one, so that case is answered from the report. Any other
     * call for a run whose report was already handed over starts the tests again - which is what
     * an edit-and-rerun loop means by calling the tool a second time.
     * </p>
     */
    private static final ConcurrentHashMap<String, Boolean> UNDELIVERED_RUNS = new ConcurrentHashMap<>();

    /**
     * Parked by a test so two deliveries reach the claim together. Production leaves it idle, and
     * the claim that follows it is what decides which delivery files the receipt.
     */
    static volatile Runnable beforeClaim = () -> {
        // idle
    };

    /**
     * Files one run receipt. Production writes into the plugin state location; a test writes into
     * a directory of its own.
     */
    @FunctionalInterface
    interface ReceiptWriter
    {
        /**
         * Files one receipt.
         *
         * @param fields the receipt fields
         * @return the outcome of the write
         */
        RunReceipts.Outcome write(Map<String, Object> fields);
    }

    /**
     * The literal a cached answer marks itself with. The facade reads it back to state
     * {@code cached:true} as a field of its own envelope.
     */
    static final String CACHED_MARK = "cached: true"; //$NON-NLS-1$

    /**
     * The literal a run-mode answer marks itself with when the preset dropped the update the call
     * never named. The facade reads it back to state the same {@code databaseUpdate} field a
     * finished answer carries, so a client reading fields rather than prose is told either way.
     */
    static final String UPDATE_SKIPPED_MARK = "databaseUpdate=" //$NON-NLS-1$
        + DebugSessionStarter.DATABASE_UPDATE_SKIPPED_BY_PRESET;

    private static final Map<String, ILaunch> ACTIVE_LAUNCHES = new ConcurrentHashMap<>();

    private static final AtomicBoolean LISTENER_REGISTERED = new AtomicBoolean(false);

    /** The tool a run receipt is filed under: the facade every run-mode call comes through. */
    private static final String RECEIPT_TOOL = YaxunitTestsTool.NAME;

    /**
     * What a finished run is receipted under: the project the launch resolved to, the filters that
     * chose what ran, and whether the preset dropped the unnamed update - the one place the skip
     * marker is set down, from which every answer of the run takes it: the report handed over
     * immediately, the report picked up later, and the pending text waiting for both. Carried
     * rather than kept in a field - the instance registered as the back-compat alias serves
     * concurrent calls.
     */
    static final class RunContext
    {
        final String projectName;

        final String extensions;

        final String modules;

        final String tests;

        /** Whether the preset dropped the update the call never named; the answers say so. */
        final boolean skippedByPreset;

        /**
         * Records the project, the filters a receipt names the run by, and the update decision
         * its answers carry.
         *
         * @param projectName the project the launch resolved to
         * @param extensions the extensions filter, or <code>null</code> when the call named none
         * @param modules the modules filter, or <code>null</code> when the call named none
         * @param tests the tests filter, or <code>null</code> when the call named none
         * @param skippedByPreset whether the preset dropped the unnamed update of the call the run
         *            answers
         */
        RunContext(String projectName, String extensions, String modules, String tests,
            boolean skippedByPreset)
        {
            this.projectName = projectName;
            this.extensions = extensions;
            this.modules = modules;
            this.tests = tests;
            this.skippedByPreset = skippedByPreset;
        }
    }

    /**
     * Remembers a run whose report has not been handed over. The launch that starts the run is
     * the caller; a test uses it to stage the same state.
     *
     * @param runKey the run
     */
    static void noteUndelivered(String runKey)
    {
        UNDELIVERED_RUNS.put(runKey, Boolean.TRUE);
    }

    /**
     * Forgets a run staged by {@link #noteUndelivered}, so a test leaves the map as it found it.
     *
     * @param runKey the run
     */
    static void forgetUndelivered(String runKey)
    {
        UNDELIVERED_RUNS.remove(runKey);
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    /**
     * What a caller is told this alias does, including that every branch answers JSON.
     *
     * @return the tool description
     */
    @Override
    public String getDescription()
    {
        return "Back-compat alias of `yaxunit_tests` `mode=run`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Executes the YAXUnit test suite for a 1C:Enterprise project. " //$NON-NLS-1$
            + "Starts the application with the RunUnitTests parameter, then polls " //$NON-NLS-1$
            + "for up to `timeoutSeconds` seconds (60 by default) until it finishes, returning a JSON " //$NON-NLS-1$
            + "object whose output is the JUnit Markdown report. " //$NON-NLS-1$
            + "If the launch has not completed once the polling window closes, the response is " //$NON-NLS-1$
            + "JSON whose output opens with **Pending** - invoke this tool again with the same arguments to keep waiting and " //$NON-NLS-1$
            + "pick up the result once the launch finishes. The launch itself is not aborted on timeout. " //$NON-NLS-1$
            + "The infobase is updated before the launch unless updateBeforeLaunch=false; an update "
            + "that does not finish refuses the launch and nothing is started. Under a preset that "
            + "disabled update_database an omitted updateBeforeLaunch launches without updating "
            + "(every answer of the run carries databaseUpdate=SKIPPED_BY_PRESET). "
            + "A report handed over for a run already collected is not reused: the tests run again. "
            + "Pass reuseRecent=true to take a report written within the last 5 minutes instead. "
            + "A complete Markdown report is also saved to report.md alongside junit.xml. " //$NON-NLS-1$
            + "Requires an existing launch configuration and the YAXUnit extension installed in the infobase."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("launchConfigurationName", //$NON-NLS-1$
                "Precise name of the EDT runtime-client launch configuration (preferred source: list_configurations)") //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "Name of the EDT project (required when launchConfigurationName is not supplied)") //$NON-NLS-1$
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application identifier returned by get_applications (required when launchConfigurationName is not supplied)") //$NON-NLS-1$
            .stringProperty("extensions", //$NON-NLS-1$
                "Comma-separated list of extension names used to restrict which tests run") //$NON-NLS-1$
            .stringProperty("modules", //$NON-NLS-1$
                "Comma-separated list of module names used to restrict which tests run") //$NON-NLS-1$
            .stringProperty("tests", //$NON-NLS-1$
                "Comma-separated list of test names, given as Module.Method") //$NON-NLS-1$
            .integerProperty("timeoutSeconds", //$NON-NLS-1$
                "Length of the polling window in seconds (60 by default; legacy aliases: timeout, " //$NON-NLS-1$
                    + "timeoutMs). If it expires, the result is Pending - call again to keep waiting.") //$NON-NLS-1$
            .booleanProperty("updateBeforeLaunch", //$NON-NLS-1$
                "Default true. The infobase is updated before the launch; an update that does not " //$NON-NLS-1$
                    + "finish refuses the launch. Set false to launch against the infobase as it " //$NON-NLS-1$
                    + "stands. Under a preset that disabled update_database an omitted argument " //$NON-NLS-1$
                    + "launches without updating (every answer of the run carries " //$NON-NLS-1$
                    + "databaseUpdate=SKIPPED_BY_PRESET); an explicit true is refused.") //$NON-NLS-1$
            .booleanProperty("reuseRecent", //$NON-NLS-1$
                "Default false. Set true to take the report of a run that finished within the last " //$NON-NLS-1$
                    + "5 minutes instead of running the tests again.") //$NON-NLS-1$
            .build();
    }

    /**
     * The alias answers JSON on every branch: a refusal, a run still in progress, and a result.
     *
     * @return {@link ResponseType#JSON}
     */
    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    /**
     * Runs the call and returns JSON. A refusal and a run still in progress go through the same
     * envelope the facade uses; a finished run is already that JSON and passes through it.
     *
     * @param params the call arguments
     * @return the JSON answer
     */
    @Override
    public String execute(Map<String, String> params)
    {
        return publish(dispatch(params));
    }

    /**
     * The alias's answer: the same JSON envelope the facade wraps a delegate's text in.
     *
     * @param raw markdown the run produced, or a JSON object it already built
     * @return a JSON object
     */
    static String publish(String raw)
    {
        return YaxunitTestsTool.asJsonEnvelope(raw);
    }

    /**
     * Runs the call and returns the runner's own text: markdown for a refusal or a run still in
     * progress, and the JSON object of a finished run. The facade wraps this; {@link #execute}
     * publishes it through the same wrap, so the facade's own answer stays the one it builds.
     *
     * @param params the call arguments
     * @return the unwrapped answer
     */
    String dispatch(Map<String, String> params)
    {
        String configName = JsonUtils.extractStringArgument(params, "launchConfigurationName"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        String extensions = JsonUtils.extractStringArgument(params, "extensions"); //$NON-NLS-1$
        String modules = JsonUtils.extractStringArgument(params, "modules"); //$NON-NLS-1$
        String tests = JsonUtils.extractStringArgument(params, "tests"); //$NON-NLS-1$
        int timeout = TimeoutArgs.readSeconds(params, DEFAULT_TIMEOUT, 1, 0);

        String unsupported = unsupportedFilter(params);
        if (unsupported != null)
        {
            return unsupported;
        }

        boolean hasName = configName != null && !configName.isEmpty();
        if (!hasName)
        {
            if (projectName == null || projectName.isEmpty())
            {
                return "**Error:** projectName must be supplied (or provide launchConfigurationName instead)"; //$NON-NLS-1$
            }
            if (applicationId == null || applicationId.isEmpty())
            {
                return "**Error:** applicationId must be supplied (or provide launchConfigurationName instead). " //$NON-NLS-1$
                    + "Look it up via get_applications or list_configurations."; //$NON-NLS-1$
            }
        }

        ensureLaunchListenerRegistered();
        purgeTerminatedLaunches();
        return runTests(configName, projectName, applicationId, extensions, modules, tests, timeout,
            updateBeforeLaunch(params), reuseRecent(params));
    }

    /**
     * The decision about the infobase update the call's {@code updateBeforeLaunch} argument names,
     * read the way every launching path reads it.
     *
     * @param params the call arguments.
     * @return the decision
     */
    static DebugSessionStarter.LaunchUpdate updateBeforeLaunch(Map<String, String> params)
    {
        return DebugSessionStarter.launchUpdate(
            JsonUtils.extractBooleanArgumentNullable(params, "updateBeforeLaunch"), true); //$NON-NLS-1$
    }

    /**
     * Whether the call asked for the report of a recent run instead of running the tests.
     *
     * @param params the call arguments.
     * @return the flag, {@code false} when the call does not name it
     */
    static boolean reuseRecent(Map<String, String> params)
    {
        return JsonUtils.extractBooleanArgument(params, "reuseRecent", false); //$NON-NLS-1$
    }

    /**
     * Whether a report lying in the run's directory answers this call.
     * <p>
     * Three things decide it and each is needed: a report has to be there at all; a call that asked
     * for a recent report takes one written within {@link #CACHE_TTL_MS}; and a call that asked for
     * nothing takes only the report of a run it started and whose result was never handed over -
     * the pickup after {@code **Pending**}. Everything else starts a new run, which is what a
     * second call after a delivered report means.
     * </p>
     *
     * @param there whether a report exists in the run's directory.
     * @param recent whether it was written within the cache window.
     * @param uncollected whether its run was started here and its report never handed over.
     * @param reuseRecent whether the call asked for a recent report.
     * @return whether the call is answered from that report
     */
    static boolean servesFromCache(boolean there, boolean recent, boolean uncollected,
        boolean reuseRecent)
    {
        if (!there)
        {
            return false;
        }
        return reuseRecent ? recent : uncollected;
    }

    /**
     * The refusal a call naming a filter this tool does not apply gets.
     * <p>
     * {@code suites}, {@code tags} and {@code contexts} were described and documented as filters
     * long after the launch configuration stopped carrying them. A call with one of them reached
     * the launch, ran every test in the suite and answered success - a filter the caller believes
     * is narrowing the run and is not. Refused instead, so the caller learns which filters exist
     * rather than reading a full run as a filtered one.
     * </p>
     *
     * @param params the call arguments.
     * @return the refusal, or <code>null</code> when the call names none of them
     */
    static String unsupportedFilter(Map<String, String> params)
    {
        List<String> named = new ArrayList<>();
        for (String key : new String[] {"suites", "tags", "contexts"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            String value = JsonUtils.extractStringArgument(params, key);
            if (value != null && !value.trim().isEmpty())
            {
                named.add(key);
            }
        }
        if (named.isEmpty())
        {
            return null;
        }
        return "**Error:** the filter argument " + String.join(", ", named) //$NON-NLS-1$
            + " is not applied by this tool: the YAXUnit launch configuration takes extensions, " //$NON-NLS-1$
            + "modules and tests only, and a call carrying any other filter would run every test " //$NON-NLS-1$
            + "in the suite. Remove the argument, or narrow the run with extensions / modules / tests."; //$NON-NLS-1$
    }

    /**
     * The pre-launch update of the infobase, and the reason a launch must not start without one.
     * <p>
     * The update is the same step a debug or client launch runs - {@link DebugSessionStarter}
     * decides whether it applies and what the outcome means - and the step itself is a parameter so
     * that the decision, the default and the refusal can be exercised without an infobase.
     * </p>
     *
     * @param update the decision the call's {@code updateBeforeLaunch} argument resolved to; the
     *            callers read it with the default {@code true}.
     * @param projectName the project the launch belongs to.
     * @param applicationId the application the launch starts.
     * @param updateStep the update itself.
     * @return the sentence refusing the launch, or <code>null</code> when it may go on
     */
    static String preLaunchUpdateRefusal(DebugSessionStarter.LaunchUpdate update, String projectName,
        String applicationId, BiFunction<String, String, ApplicationUpdater.Result> updateStep)
    {
        if (update.refusal != null)
        {
            return update.refusal;
        }
        if (!update.update)
        {
            return null;
        }
        return DebugSessionStarter.preLaunchRefusal(updateStep.apply(projectName, applicationId));
    }

    private String runTests(String configName, String projectName, String applicationId, String extensions,
        String modules, String tests, int timeout, DebugSessionStarter.LaunchUpdate update,
        boolean reuseRecent)
    {
        try
        {
            DebugPlugin debugPlugin = DebugPlugin.getDefault();
            if (debugPlugin == null)
            {
                return "**Error:** The launch manager is unavailable (the EDT debug runtime is shutting down)"; //$NON-NLS-1$
            }
            ILaunchManager launchManager = debugPlugin.getLaunchManager();
            if (launchManager == null)
            {
                return "**Error:** The launch manager is unavailable"; //$NON-NLS-1$
            }

            ILaunchConfiguration matchingConfig =
                LaunchConfigAccess.resolveLaunchConfig(launchManager, configName, projectName, applicationId);
            if (matchingConfig == null)
            {
                if (configName != null && !configName.isEmpty())
                {
                    return "**Error:** No launch configuration found matching '" + configName //$NON-NLS-1$
                        + "'. Check list_configurations for what's available."; //$NON-NLS-1$
                }
                return buildNoConfigError(launchManager,
                    launchManager.getLaunchConfigurationType(LaunchConfigAccess.LAUNCH_CONFIG_TYPE_ID),
                    projectName, applicationId);
            }

            if (!LaunchConfigAccess.LAUNCH_CONFIG_TYPE_ID
                .equals(LaunchConfigAccess.getConfigTypeId(matchingConfig)))
            {
                return "**Error:** The launch configuration '" + matchingConfig.getName() //$NON-NLS-1$
                    + "' is not a runtime-client configuration - YAXUnit tests need one.";
            }

            String effectiveProject =
                LaunchConfigAccess.readAttribute(matchingConfig, LaunchConfigAccess.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
            String effectiveAppId =
                LaunchConfigAccess.readAttribute(matchingConfig, LaunchConfigAccess.ATTR_APPLICATION_ID, ""); //$NON-NLS-1$
            if (projectName == null || projectName.isEmpty())
            {
                projectName = effectiveProject;
            }
            if (applicationId == null || applicationId.isEmpty())
            {
                applicationId = effectiveAppId;
            }

            if (projectName == null || projectName.isEmpty())
            {
                return "**Error:** The launch configuration '" + matchingConfig.getName() //$NON-NLS-1$
                    + "' has no project attribute configured"; //$NON-NLS-1$
            }

            String notReadyError = ProjectStateGuard.checkReadyOrError(projectName);
            if (notReadyError != null)
            {
                return "**Error:** " + notReadyError; //$NON-NLS-1$
            }

            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project == null || !project.exists())
            {
                return "**Error:** " + ProjectResolver.describeNotFound(projectName); //$NON-NLS-1$
            }
            if (!project.isOpen())
            {
                return "**Error:** The project is closed: " + projectName; //$NON-NLS-1$
            }

            IApplicationManager appManager = Activator.getDefault().getApplicationManager();
            if (appManager == null)
            {
                return "**Error:** The IApplicationManager service is unavailable"; //$NON-NLS-1$
            }

            if (applicationId != null && !applicationId.isEmpty())
            {
                try
                {
                    Optional<IApplication> appOpt = appManager.getApplication(project, applicationId);
                    if (!appOpt.isPresent())
                    {
                        return "**Error:** No application found: " + applicationId //$NON-NLS-1$
                            + ". Run get_applications to list valid application IDs."; //$NON-NLS-1$
                    }
                }
                catch (ApplicationException e)
                {
                    Activator.logError("Failed to check application", e); //$NON-NLS-1$
                    return "**Error:** Could not validate application: " + applicationId + " (" //$NON-NLS-1$ //$NON-NLS-2$
                        + e.getMessage() + ")"; //$NON-NLS-1$
                }
            }

            String runKey = matchingConfig.getName() + ":" //$NON-NLS-1$
                + sha1(safe(extensions) + "|" + safe(modules) + "|" + safe(tests)); //$NON-NLS-1$ //$NON-NLS-2$
            Path reportDir = stableReportDir(runKey);
            RunContext runContext = new RunContext(projectName, extensions, modules, tests,
                update.skippedByPreset);

            ILaunch existing = ACTIVE_LAUNCHES.get(runKey);
            if (existing != null)
            {
                if (existing.isTerminated())
                {
                    ACTIVE_LAUNCHES.remove(runKey);
                    File junitXml = findJunitXml(reportDir);
                    if (junitXml != null)
                    {
                        return handOverFinishedLaunch(runKey, junitXml, runContext, RunReceipts::write);
                    }
                    return "**Error:** The previous launch finished, but no JUnit XML report was found in " + reportDir //$NON-NLS-1$
                        + ". Confirm the YAXUnit extension is installed."; //$NON-NLS-1$
                }
                String pollResult = pollLaunch(existing, reportDir, timeout, runKey, runContext);
                if (pollResult != null)
                {
                    return pollResult;
                }
                return buildPendingMessage(reportDir, update.skippedByPreset);
            }

            File cached = findJunitXml(reportDir);
            boolean recent = cached != null
                && (System.currentTimeMillis() - cached.lastModified()) < CACHE_TTL_MS;
            boolean uncollected = UNDELIVERED_RUNS.containsKey(runKey);
            if (servesFromCache(cached != null, recent, uncollected, reuseRecent))
            {
                // Two different calls arrive here and they must not be answered the same way. One
                // is picking up the report of a run it started - the run answered Pending and its
                // report was never handed over, whatever its age. The other is asking for the same
                // tests again after reading a report; it starts a new run unless it said
                // reuseRecent=true, because reading a stale report as the result of an edit-and-
                // rerun loop is exactly the defect this mark exists to prevent.
                Activator.logInfo("Serving the report of the finished YAXUnit run for " + runKey //$NON-NLS-1$
                    + (reuseRecent ? " (reuseRecent=true)" : " (uncollected)") //$NON-NLS-1$ //$NON-NLS-2$
                    + " from " + cached); //$NON-NLS-1$
                return handOverCached(runKey, cached, reuseRecent, runContext, RunReceipts::write);
            }

            // Only a launch needs an infobase that is up to date: a call answered from a report
            // that is already there starts nothing. Run outside the launch lock, which is held for
            // the launch itself and would otherwise be held for the length of an update.
            String updateRefusal = preLaunchUpdateRefusal(update, projectName,
                applicationId, DebugSessionStarter::updateDatabaseIfNeeded);
            if (updateRefusal != null)
            {
                Activator.logInfo("Refusing the YAXUnit launch for " + runKey + ": " + updateRefusal); //$NON-NLS-1$
                return "**Error:** " + updateRefusal; //$NON-NLS-1$
            }

            ILaunch launch;
            synchronized (ACTIVE_LAUNCHES)
            {
                ILaunch concurrent = ACTIVE_LAUNCHES.get(runKey);
                if (concurrent != null && !concurrent.isTerminated())
                {
                    Activator.logInfo("Reusing the active YAXUnit launch for runKey=" + runKey); //$NON-NLS-1$
                    launch = concurrent;
                }
                else
                {
                    if (concurrent != null)
                    {
                        ACTIVE_LAUNCHES.remove(runKey);
                    }
                    cleanupTempDir(reportDir);
                    Files.createDirectories(reportDir);
                    Path paramsFile = reportDir.resolve("xUnitParams.json"); //$NON-NLS-1$
                    String paramsJson = buildParamsJson(reportDir.resolve("junit.xml").toString(), //$NON-NLS-1$
                        extensions, modules, tests);
                    Files.write(paramsFile, paramsJson.getBytes(StandardCharsets.UTF_8));
                    Activator.logInfo("Wrote YAXUnit params to: " + paramsFile); //$NON-NLS-1$
                    ILaunchConfigurationWorkingCopy workingCopy = matchingConfig.getWorkingCopy();
                    String startupOption = "RunUnitTests=" + paramsFile.toString(); //$NON-NLS-1$
                    workingCopy.setAttribute(LaunchConfigAccess.ATTR_STARTUP_OPTION, startupOption);
                    Activator.logInfo("Starting YAXUnit test launch: config=" + matchingConfig.getName() //$NON-NLS-1$
                        + ", startup=" + startupOption); //$NON-NLS-1$
                    launch = workingCopy.launch(ILaunchManager.RUN_MODE, new NullProgressMonitor());
                    ACTIVE_LAUNCHES.put(runKey, launch);
                    // A run started here whose report nobody has read yet. This is what makes the
                    // next call a pickup rather than a request for a new run, and it is cleared
                    // the moment the report is handed over.
                    noteUndelivered(runKey);
                }
            }

            String pollResult = pollLaunch(launch, reportDir, timeout, runKey, runContext);
            if (pollResult != null)
            {
                return pollResult;
            }
            return buildPendingMessage(reportDir, update.skippedByPreset);
        }
        catch (CoreException e)
        {
            Activator.logError("Failed to run YAXUnit tests", e); //$NON-NLS-1$
            return "**Error:** The launch failed: " + e.getMessage(); //$NON-NLS-1$
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return "**Error:** Test execution was interrupted while waiting"; //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Unexpected failure while running YAXUnit tests", e); //$NON-NLS-1$
            return "**Error:** " + e.getMessage(); //$NON-NLS-1$
        }
    }

    private String pollLaunch(ILaunch launch, Path reportDir, int timeoutSec, String runKey,
        RunContext runContext)
        throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + (timeoutSec * 1000L);
        while (!launch.isTerminated())
        {
            if (System.currentTimeMillis() > deadline)
            {
                return null;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        ACTIVE_LAUNCHES.remove(runKey);
        Activator.logInfo("YAXUnit tests finished for " + runKey); //$NON-NLS-1$
        File junitXml = findJunitXml(reportDir);
        if (junitXml == null)
        {
            return "**Error:** No JUnit XML report was found in " + reportDir //$NON-NLS-1$
                + ". The run produced no report - most likely the test module failed to compile or failed to register." //$NON-NLS-1$
                + " Typical causes and fixes: (1) the YAXUnit extension is not Active in the infobase" //$NON-NLS-1$
                + " (Configuration > Extensions); (2) a stale build, or a duplicate / already-defined" //$NON-NLS-1$
                + " procedure after editing a test module - rebuild (clean_project) and run again;" //$NON-NLS-1$
                + " (3) the test module has compile errors - verify with get_project_errors."; //$NON-NLS-1$
        }
        return handOverFinishedLaunch(runKey, junitXml, runContext, RunReceipts::write);
    }

    /**
     * The answer once an active launch has terminated and its report is on disk. The first
     * delivery of a run this server started is not marked cached; that decision is the claim
     * inside {@link #deliver}.
     *
     * @param runKey the run the report belongs to
     * @param junitXml the report
     * @param runContext the project and the filters the run was chosen by
     * @param receipts where the receipt is written
     * @return the answer
     */
    static String handOverFinishedLaunch(String runKey, File junitXml, RunContext runContext,
        ReceiptWriter receipts)
    {
        return deliver(runKey, junitXml, false, junitXml.lastModified(), runContext, receipts);
    }

    /**
     * The answer when the report is read from the run's directory after the launch has left the
     * active set. A run still waiting to be handed over is not marked cached, even when the call
     * asked for a recent report; a repeat of a result already handed over is.
     *
     * @param runKey the run the report belongs to
     * @param junitXml the report
     * @param reuseRecent whether the call asked for a recent report
     * @param runContext the project and the filters the run was chosen by
     * @param receipts where the receipt is written
     * @return the answer
     */
    static String handOverCached(String runKey, File junitXml, boolean reuseRecent, RunContext runContext,
        ReceiptWriter receipts)
    {
        return deliver(runKey, junitXml, reuseRecent, junitXml.lastModified(), runContext, receipts);
    }

    /**
     * Hands a report over. The call whose {@link #UNDELIVERED_RUNS} removal returns non-null is
     * the first delivery: it files the receipt and does not mark the answer cached. A call that
     * finds the run already taken is a repeat, marks {@code cached}, and files nothing. The check
     * that used to decide this before the removal is not consulted: two callers can both have
     * seen the key, and only the removal orders them.
     *
     * @param runKey the run the report belongs to
     * @param junitXml the report
     * @param reuseRecent whether the call asked for a recent report; used only when this call did
     *            not claim the run
     * @param reportTime when the report was written, in milliseconds since the epoch
     * @param runContext the project and the filters the run was chosen by
     * @param receipts where the receipt is written
     * @return the answer
     */
    private static String deliver(String runKey, File junitXml, boolean reuseRecent, long reportTime,
        RunContext runContext, ReceiptWriter receipts)
    {
        beforeClaim.run();
        boolean claimed = UNDELIVERED_RUNS.remove(runKey) != null;
        String cacheMark = claimed ? null : cacheMark(reuseRecent, reportTime);
        return readResults(junitXml, cacheMark, runContext, claimed, receipts);
    }

    /**
     * The line a report of an earlier run carries, naming the run it belongs to.
     * <p>
     * A reused report read as this call's own result is the whole reason the mark exists: the
     * counts look like a fresh run's and nothing in them says otherwise. The time is the report's
     * own - when its run finished - and the two ways of reaching a reused report are named apart,
     * because one of them is a pickup the caller asked for and the other is the cache it named.
     * </p>
     *
     * @param reuseRecent whether the caller asked for a recent report.
     * @param reportTime when the report was written, in milliseconds since the epoch.
     * @return the markdown line
     */
    static String cacheMark(boolean reuseRecent, long reportTime)
    {
        return "\n---\n" + CACHED_MARK + " - the report of the run that finished " //$NON-NLS-1$ //$NON-NLS-2$
            + java.time.Instant.ofEpochMilli(reportTime)
            + (reuseRecent
                ? ", taken because the call asked for a recent report." //$NON-NLS-1$
                : ", started by an earlier call whose result was never handed over.") //$NON-NLS-1$
            + " This call started no tests.\n"; //$NON-NLS-1$
    }

    /**
     * Whether an answer is the report of an earlier run.
     *
     * @param result the answer.
     * @return whether it carries the mark
     */
    static boolean isCachedAnswer(String result)
    {
        return result != null && result.contains(CACHED_MARK);
    }

    /**
     * Reads the JUnit report of a finished run and answers the outcome, filing the run's receipt
     * when asked to.
     * <p>
     * The answer is the JSON object the facade's envelope passes through untouched: the markdown
     * report in {@code output}, the run's counters beside it, {@code cached} for the report of an
     * earlier run, {@code databaseUpdate} for the update the preset dropped off the call the run
     * answers, and {@code receiptPath} naming the receipt on disk - or {@code receiptError}
     * saying why there is none, with the result fields still in place. A report that cannot be
     * parsed at all is no result, stays markdown and files no receipt.
     * </p>
     *
     * @param junitXml the report the run left behind
     * @param cacheMark the line saying the report is a previous run's, or <code>null</code>
     * @param runContext the project, the filters the run was chosen by, and whether the preset
     *            dropped its unnamed update
     * @param fileReceipt whether to file the run's receipt - the call that claimed the run
     * @param receipts where the receipt is written
     * @return the answer
     */
    private static String readResults(File junitXml, String cacheMark, RunContext runContext,
        boolean fileReceipt, ReceiptWriter receipts)
    {
        try
        {
            JUnitRunOutcome results = JUnitXmlReader.parse(junitXml);
            String markdown = JUnitReportFormatter.format(results);
            if (cacheMark != null)
            {
                markdown += cacheMark;
            }
            if (results.getTotal() == 0)
            {
                markdown += "\n\n> **No tests were executed.** Check: the YAXUnit extension is Active in the infobase" //$NON-NLS-1$
                    + " (Configuration > Extensions); the extensions / modules / tests filter matches existing" //$NON-NLS-1$
                    + " tests; and, if a test module was just edited, that it compiles (get_project_errors)" //$NON-NLS-1$
                    + " and the project was rebuilt (clean_project).\n"; //$NON-NLS-1$
            }
            Path reportFile = junitXml.toPath().resolveSibling("report.md"); //$NON-NLS-1$
            boolean reportWritten = false;
            try
            {
                Files.write(reportFile, markdown.getBytes(StandardCharsets.UTF_8));
                reportWritten = Files.exists(reportFile);
            }
            catch (IOException io)
            {
                Activator.logError("Could not write the Markdown report to " + reportFile, io); //$NON-NLS-1$
            }
            if (reportWritten)
            {
                markdown += "\n---\n*Complete report written to:* `" + reportFile + "`\n"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            String reportPath = reportWritten ? reportFile.toString() : junitXml.getAbsolutePath();
            ToolResult answer = ToolResult.success()
                .put("operation", RECEIPT_TOOL) //$NON-NLS-1$
                .put("output", markdown) //$NON-NLS-1$
                .put("total", results.getTotal()) //$NON-NLS-1$
                .put("passed", results.getPassed()) //$NON-NLS-1$
                .put("failures", results.getFailures()) //$NON-NLS-1$
                .put("errors", results.getErrors()) //$NON-NLS-1$
                .put("skipped", results.getSkipped()) //$NON-NLS-1$
                .put("reportPath", reportPath); //$NON-NLS-1$
            // The one place a report-carrying answer gets the skip marker: both the run that
            // finishes inside the call and the report picked up by runKey pass through here, and
            // the field pair is the same putDatabaseUpdate the debug-mode answer goes through.
            DebugSessionStarter.putDatabaseUpdate(answer, null, runContext.skippedByPreset);
            if (cacheMark != null)
            {
                answer.put("cached", true); //$NON-NLS-1$
            }
            if (fileReceipt)
            {
                RunReceipts.Outcome receipt = writeReceipt(runContext, results, reportPath, receipts);
                answer.put("receiptPath", receipt.path == null ? null : receipt.path.toString()) //$NON-NLS-1$
                    .put("receiptError", receipt.error); //$NON-NLS-1$
            }
            return answer.toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Failed to parse JUnit XML: " + junitXml, e); //$NON-NLS-1$
            return "**Error:** Could not parse the test results: " + e.getMessage(); //$NON-NLS-1$
        }
    }

    /**
     * Files the receipt of a run that reached a result. The mode is this runner's own: the
     * debug-mode sibling answers through another tool and files no receipt here.
     *
     * @param runContext the project and the filters the run was chosen by
     * @param results what the JUnit report said
     * @param reportPath the report the caller is pointed at
     * @param receipts where the receipt is written
     * @return the outcome of the write - the file, or the reason there is none
     */
    private static RunReceipts.Outcome writeReceipt(RunContext runContext, JUnitRunOutcome results,
        String reportPath, ReceiptWriter receipts)
    {
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("mode", "run"); //$NON-NLS-1$ //$NON-NLS-2$
        if (runContext.extensions != null && !runContext.extensions.isEmpty())
        {
            filters.put("extensions", runContext.extensions); //$NON-NLS-1$
        }
        if (runContext.modules != null && !runContext.modules.isEmpty())
        {
            filters.put("modules", runContext.modules); //$NON-NLS-1$
        }
        if (runContext.tests != null && !runContext.tests.isEmpty())
        {
            filters.put("tests", runContext.tests); //$NON-NLS-1$
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("tool", RECEIPT_TOOL); //$NON-NLS-1$
        fields.put("projectName", runContext.projectName); //$NON-NLS-1$
        fields.put("filters", filters); //$NON-NLS-1$
        fields.put("total", results.getTotal()); //$NON-NLS-1$
        fields.put("passed", results.getPassed()); //$NON-NLS-1$
        fields.put("failures", results.getFailures()); //$NON-NLS-1$
        fields.put("errors", results.getErrors()); //$NON-NLS-1$
        fields.put("skipped", results.getSkipped()); //$NON-NLS-1$
        fields.put("reportPath", reportPath); //$NON-NLS-1$
        return receipts.write(fields);
    }

    private static void ensureLaunchListenerRegistered()
    {
        if (LISTENER_REGISTERED.compareAndSet(false, true))
        {
            DebugPlugin debugPlugin = DebugPlugin.getDefault();
            if (debugPlugin == null)
            {
                LISTENER_REGISTERED.set(false);
                return;
            }
            ILaunchManager launchManager = debugPlugin.getLaunchManager();
            if (launchManager == null)
            {
                LISTENER_REGISTERED.set(false);
                return;
            }
            launchManager.addLaunchListener(new ILaunchListener()
            {
                @Override
                public void launchAdded(ILaunch launch)
                {
                    // Intentionally empty: a launch being added tells us nothing about one already running.
                }

                @Override
                public void launchChanged(ILaunch launch)
                {
                    if (launch != null && launch.isTerminated())
                    {
                        evict(launch);
                    }
                }

                @Override
                public void launchRemoved(ILaunch launch)
                {
                    evict(launch);
                }
            });
            Activator.logInfo("Registered the YAXUnit launch listener"); //$NON-NLS-1$
        }
    }

    private static void evict(ILaunch launch)
    {
        if (launch == null)
        {
            return;
        }
        ACTIVE_LAUNCHES.entrySet().removeIf(e -> e.getValue() == launch);
    }

    private static void purgeTerminatedLaunches()
    {
        ACTIVE_LAUNCHES.entrySet().removeIf(e -> {
            ILaunch l = e.getValue();
            return l == null || l.isTerminated();
        });
    }

    /**
     * The text a run still in progress answers with, before the alias wraps it as JSON. A run
     * launched without the update the preset dropped says so, in the same words every answer of
     * the run carries.
     *
     * @param reportDir the directory the report will be written to
     * @param skippedByPreset whether the preset dropped the update the call never named
     * @return the pending text
     */
    static String pendingText(Path reportDir, boolean skippedByPreset)
    {
        String text = "**Pending:** YAXUnit tests are still in progress.\n\nReport directory: `" //$NON-NLS-1$
            + reportDir + "`\n\n"; //$NON-NLS-1$
        if (skippedByPreset)
        {
            text += updateSkipNote() + "\n\n"; //$NON-NLS-1$
        }
        return text + "Call `run_yaxunit_tests` again with the same arguments to keep waiting and retrieve" //$NON-NLS-1$
            + " the JUnit XML once the launch is done.\n"; //$NON-NLS-1$
    }

    /**
     * The line a run-mode answer carries in its text when the preset dropped the update the call
     * never named: the marker the answers name in {@code databaseUpdate}, and the note that goes
     * with it - both from the place the debug-mode answer takes them.
     *
     * @return the skip line
     */
    static String updateSkipNote()
    {
        return UPDATE_SKIPPED_MARK + " - " + DebugSessionStarter.databaseUpdateNoteText(); //$NON-NLS-1$
    }

    /**
     * Whether an answer's text carries the preset-dropped update marker.
     *
     * @param result the answer.
     * @return whether it carries the mark
     */
    static boolean isUpdateSkippedAnswer(String result)
    {
        return result != null && result.contains(UPDATE_SKIPPED_MARK);
    }

    /**
     * The pending branch's own text. {@link #execute} wraps it; the facade wraps the same text
     * when it calls {@link #dispatch}.
     *
     * @param reportDir the directory the report will be written to
     * @param skippedByPreset whether the preset dropped the update the call never named
     * @return the pending text
     */
    private String buildPendingMessage(Path reportDir, boolean skippedByPreset)
    {
        return pendingText(reportDir, skippedByPreset);
    }

    private Path stableReportDir(String runKey)
    {
        String safeKey = runKey.replaceAll("[^a-zA-Z0-9_.-]", "_"); //$NON-NLS-1$ //$NON-NLS-2$
        String uniqueSuffix = sha1Full(runKey);
        int maxSafeKeyLength = Math.max(0, 80 - uniqueSuffix.length() - 1);
        if (safeKey.length() > maxSafeKeyLength)
        {
            safeKey = safeKey.substring(0, maxSafeKeyLength);
        }
        String dirName = safeKey.isEmpty() ? uniqueSuffix : safeKey + "_" + uniqueSuffix; //$NON-NLS-1$
        return Paths.get(System.getProperty("java.io.tmpdir"), "ai-edt-yaxunit", dirName); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String sha1Full(String input)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-1"); //$NON-NLS-1$
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest)
            {
                hex.append(String.format("%02x", b)); //$NON-NLS-1$
            }
            return hex.toString();
        }
        catch (Exception e)
        {
            return Integer.toHexString(input.hashCode());
        }
    }

    private String sha1(String input)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-1"); //$NON-NLS-1$
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6 && i < digest.length; i++)
            {
                hex.append(String.format("%02x", digest[i])); //$NON-NLS-1$
            }
            return hex.toString();
        }
        catch (Exception e)
        {
            return Integer.toHexString(input.hashCode());
        }
    }

    private String safe(String s)
    {
        return s == null ? "" : s; //$NON-NLS-1$
    }

    private String buildParamsJson(String reportPath, String extensions, String modules, String tests)
    {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("reportPath", reportPath); //$NON-NLS-1$
        params.put("reportFormat", "jUnit"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("closeAfterTests", Boolean.TRUE); //$NON-NLS-1$

        Map<String, Object> filter = new LinkedHashMap<>();
        boolean hasFilter = false;
        if (extensions != null && !extensions.isEmpty())
        {
            filter.put("extensions", splitToList(extensions)); //$NON-NLS-1$
            hasFilter = true;
        }
        if (modules != null && !modules.isEmpty())
        {
            filter.put("modules", splitToList(modules)); //$NON-NLS-1$
            hasFilter = true;
        }
        if (tests != null && !tests.isEmpty())
        {
            filter.put("tests", splitToList(tests)); //$NON-NLS-1$
            hasFilter = true;
        }
        if (hasFilter)
        {
            params.put("filter", filter); //$NON-NLS-1$
        }
        return GsonHolder.toJson(params);
    }

    private List<String> splitToList(String value)
    {
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) //$NON-NLS-1$
        {
            String trimmed = part.trim();
            if (!trimmed.isEmpty())
            {
                result.add(trimmed);
            }
        }
        return result;
    }

    private String buildNoConfigError(ILaunchManager launchManager, ILaunchConfigurationType configType,
        String projectName, String applicationId)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("**Error:** Found no launch configuration for project '"); //$NON-NLS-1$
        sb.append(projectName);
        sb.append("' with application '"); //$NON-NLS-1$
        sb.append(applicationId);
        sb.append("'.\n\n"); //$NON-NLS-1$
        sb.append("Set up a launch configuration in EDT first " //$NON-NLS-1$
            + "(Run > Run Configurations > 1C:Enterprise Runtime Client).\n\n"); //$NON-NLS-1$
        ILaunchConfiguration[] allConfigs = LaunchConfigAccess.getAllRuntimeClientConfigs(launchManager, configType);
        if (allConfigs.length > 0)
        {
            sb.append("Existing launch configurations:\n\n"); //$NON-NLS-1$
            sb.append("| Launch Config | Project | App ID |\n"); //$NON-NLS-1$
            sb.append("|------|---------|----------------|\n"); //$NON-NLS-1$
            for (ILaunchConfiguration config : allConfigs)
            {
                sb.append("| "); //$NON-NLS-1$
                sb.append(config.getName());
                sb.append(" | "); //$NON-NLS-1$
                sb.append(LaunchConfigAccess.readAttribute(config, LaunchConfigAccess.ATTR_PROJECT_NAME, "")); //$NON-NLS-1$
                sb.append(" | "); //$NON-NLS-1$
                sb.append(LaunchConfigAccess.readAttribute(config, LaunchConfigAccess.ATTR_APPLICATION_ID, "")); //$NON-NLS-1$
                sb.append(" |\n"); //$NON-NLS-1$
            }
        }
        return sb.toString();
    }

    private File findJunitXml(Path tempDir)
    {
        if (tempDir == null || !Files.exists(tempDir))
        {
            return null;
        }
        String[] candidates = {"junit.xml", "report.xml", "test-report.xml"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (String name : candidates)
        {
            File f = tempDir.resolve(name).toFile();
            if (f.exists() && f.length() > 0)
            {
                return f;
            }
        }
        File[] xmlFiles = tempDir.toFile().listFiles((dir, name) -> name.endsWith(".xml")); //$NON-NLS-1$
        if (xmlFiles != null && xmlFiles.length > 0)
        {
            return xmlFiles[0];
        }
        return null;
    }

    private void cleanupTempDir(Path tempDir)
    {
        if (tempDir == null || !Files.exists(tempDir))
        {
            return;
        }
        try (Stream<Path> stream = Files.walk(tempDir))
        {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try
                {
                    Files.delete(p);
                }
                catch (IOException ex)
                {
                    Activator.logError("Could not delete " + p, ex); //$NON-NLS-1$
                }
            });
        }
        catch (IOException e)
        {
            Activator.logError("Could not clean up the temporary directory: " + tempDir, e); //$NON-NLS-1$
        }
    }
}
