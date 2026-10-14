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
import java.util.function.LongSupplier;
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
import org.eclipse.debug.core.model.IProcess;

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
import ru.aiedt.mcp.server.support.ModalDialogWatch;
import ru.aiedt.mcp.server.support.PendingEnvelope;
import ru.aiedt.mcp.server.support.PendingExecutor;
import ru.aiedt.mcp.server.support.PendingWorkRegistry;
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
 * that JSON with {@code **Pending**} in {@code output} and a {@code runKey}; polling the key picks
 * up that run's report rather than starting another. A call after a report was handed over starts
 * a new run unless it asked for the recent report with {@code reuseRecent=true}.
 */
public final class YaxunitTestRunner
    implements IMcpTool
{
    public static final String NAME = "run_yaxunit_tests"; //$NON-NLS-1$

    /** The whole-call budget a run-mode call gets when it names none, in seconds. */
    static final int DEFAULT_TIMEOUT_SECONDS = 60;

    static final long MAX_RESPONSE_ASSEMBLY_RESERVE_MS = 5_000L;

    /**
     * A poll deadline that never arrives. The continuation a Pending answer leaves behind polls
     * the client it tracked for as long as that client runs: the deadline bounds that call's
     * answer, not the run.
     */
    static final long NO_DEADLINE = Long.MAX_VALUE;

    static final long CLIENT_START_WAIT_MS = 2_000L;

    private static final long CLIENT_START_STEP_MS = 100L;

    private static final String CALL_STARTED_AT = "__yaxunitCallStartedAt"; //$NON-NLS-1$

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
     * The update decision each run started here was launched under, by runKey. An answer of a run
     * states what ITS launch did, and the call that comes back for the answer is not the call that
     * launched: it may name {@code updateBeforeLaunch=false} explicitly or arrive after the preset
     * changed, and neither rewrites what the launch did. Recorded where the launch and the
     * undelivered mark are recorded, and kept past delivery because {@code reuseRecent=true} can
     * ask for a report that was already handed over. A run this server did not launch has no entry
     * and its answers state nothing about the update: there is no launch decision to read, and the
     * picking call's own would be a claim about a launch it did not make.
     */
    private static final ConcurrentHashMap<String, Boolean> LAUNCH_SKIP_DECISIONS =
        new ConcurrentHashMap<>();

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

    private static final Map<String, Path> RUN_REPORT_DIRS = new ConcurrentHashMap<>();

    private static final Map<String, RunContext> RUN_CONTEXTS = new ConcurrentHashMap<>();

    private static final Map<String, String> PENDING_LAUNCHES = new ConcurrentHashMap<>();

    private static final Map<String, Path> PENDING_REPORT_DIRS = new ConcurrentHashMap<>();

    private static final Map<String, Boolean> PENDING_SKIP_DECISIONS = new ConcurrentHashMap<>();

    /**
     * Launches a call of this runner started whose client has not become visible yet, by runKey.
     * <p>
     * The window between the launch call and its first visible consequence - a tracked client
     * process, a handed-over runKey or a failed run - is invisible to {@link #ACTIVE_LAUNCHES}
     * and {@link #PENDING_LAUNCHES}, and a second call of the same tests passes both checks
     * inside it: it would wipe the report directory the standing launch still reads its
     * parameters from and start a second client against one infobase. The mark is published
     * before the launch call runs, under the launch lock, and leaves when the launch becomes
     * visible: the process is tracked, the run is handed over or the run is forgotten.
     * </p>
     */
    private static final Map<String, Object> LAUNCHES_IN_FLIGHT = new ConcurrentHashMap<>();

    private static final AtomicBoolean LISTENER_REGISTERED = new AtomicBoolean(false);

    /** A replaceable sleep seam for launch lifecycle tests. */
    @FunctionalInterface
    interface Sleeper
    {
        /**
         * @param millis how long to wait
         * @throws InterruptedException when the wait is interrupted
         */
        void sleep(long millis) throws InterruptedException;
    }

    /** The tool a run receipt is filed under: the facade every run-mode call comes through. */
    private static final String RECEIPT_TOOL = YaxunitTestsTool.NAME;

    /**
     * What a finished run is receipted under: the project the launch resolved to, the filters that
     * chose what ran, and whether the preset dropped the unnamed update - the one place the skip
     * marker is set down, from which every answer of the run takes it: the report handed over
     * immediately, the report picked up later, and the pending text waiting for both. The decision
     * is the launch's own: for an answer of a run the answering call did not start it comes from
     * {@link #runContextOf}, which reads where the launch recorded it. Carried rather than kept in
     * a field - the instance registered as the back-compat alias serves concurrent calls.
     */
    static final class RunContext
    {
        final String projectName;

        final String extensions;

        final String modules;

        final String tests;

        /** Whether the preset dropped the unnamed update of the launch this run answers. */
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
     * The run context every answer of a run is built from: its update decision is the one the
     * launch recorded, not the answering call's own. A call that picks up an active launch or an
     * undelivered report, one that asks for a recent report again, and a call answering the launch
     * it just started (the decision of which the store holds by then) all read the same place, so
     * the answer cannot drift from the run it answers.
     *
     * @param runKey the run being answered
     * @param projectName the project the launch resolved to
     * @param extensions the extensions filter, or <code>null</code> when the call named none
     * @param modules the modules filter, or <code>null</code> when the call named none
     * @param tests the tests filter, or <code>null</code> when the call named none
     * @return the run context the answer carries
     */
    static RunContext runContextOf(String runKey, String projectName, String extensions,
        String modules, String tests)
    {
        return new RunContext(projectName, extensions, modules, tests,
            Boolean.TRUE.equals(LAUNCH_SKIP_DECISIONS.get(runKey)));
    }

    /**
     * Records the update decision a run was launched under, beside the launch and the undelivered
     * mark written at the same moment.
     *
     * @param runKey the run
     * @param skippedByPreset whether the preset dropped the unnamed update of the launching call
     */
    static void noteLaunchSkipDecision(String runKey, boolean skippedByPreset)
    {
        LAUNCH_SKIP_DECISIONS.put(runKey, Boolean.valueOf(skippedByPreset));
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
            + "Starts the application with RunUnitTests and keeps the entire call, including " //$NON-NLS-1$
            + "preparation, within `timeoutSeconds` (60 by default), returning a JSON " //$NON-NLS-1$
            + "object whose output is the JUnit Markdown report. " //$NON-NLS-1$
            + "A launch blocked by an EDT dialog or still running at the deadline returns Pending " //$NON-NLS-1$
            + "with a runKey; the client keeps running, and polling that key retrieves its report. " //$NON-NLS-1$
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
                "EDT runtime-client configuration name (see list_configurations)") //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project; required without launchConfigurationName") //$NON-NLS-1$
            .stringProperty("applicationId", //$NON-NLS-1$
                "get_applications id; required without launchConfigurationName") //$NON-NLS-1$
            .stringProperty("extensions", //$NON-NLS-1$
                "Comma-separated list of extension names used to restrict which tests run") //$NON-NLS-1$
            .stringProperty("modules", //$NON-NLS-1$
                "Comma-separated list of module names used to restrict which tests run") //$NON-NLS-1$
            .stringProperty("tests", //$NON-NLS-1$
                "Comma-separated list of test names, given as Module.Method") //$NON-NLS-1$
            .integerProperty("timeoutSeconds", //$NON-NLS-1$
                "Deadline for the entire call in seconds (default 60; aliases: timeout, timeoutMs). " //$NON-NLS-1$
                    + "With the client running it answers Pending with a runKey; before that, an " //$NON-NLS-1$
                    + "error naming the step.") //$NON-NLS-1$
            .stringProperty("runKey", "Poll a pending launch and retrieve its report when ready.") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("updateBeforeLaunch", //$NON-NLS-1$
                "Default true. The infobase is updated before the launch; an update that does not " //$NON-NLS-1$
                    + "finish refuses the launch. Set false to launch against the infobase as it " //$NON-NLS-1$
                    + "stands. Under a preset that disabled update_database an omitted argument " //$NON-NLS-1$
                    + "launches without updating (answers carry " //$NON-NLS-1$
                    + "databaseUpdate=SKIPPED_BY_PRESET); an explicit true is refused.") //$NON-NLS-1$
            .booleanProperty("reuseRecent", //$NON-NLS-1$
                "Default false. Set true to take the report of a run finished within the last " //$NON-NLS-1$
                    + "5 minutes instead of running again.") //$NON-NLS-1$
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
     * The run this call resumes in {@code domain}, when that domain holds this runner's handed-over
     * launches.
     * <p>
     * The entries carry the facade's name as their starter whether the call came through the
     * facade or through this alias, so the declaration returns that name: without it a poll of a
     * live {@code runKey} counted as a fresh heavy call and each one took a permit of the
     * heavy-tool limit while only waiting on work already accounted for.
     * </p>
     *
     * @param domain the registry domain the key was found in
     * @param operation the operation argument; may be {@code null}
     * @return {@link YaxunitTestsTool#NAME} for this runner's handed-over launches,
     *         <code>null</code> otherwise
     */
    @Override
    public String resumes(String domain, String operation)
    {
        return PendingWorkRegistry.YAXUNIT_LAUNCH.domain().equals(domain) ? YaxunitTestsTool.NAME
            : null;
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
        return publish(dispatch(withCallStart(params, System.currentTimeMillis())));
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
        String resumeKey = JsonUtils.extractStringArgument(params, "runKey"); //$NON-NLS-1$
        if (resumeKey != null && !resumeKey.isEmpty())
        {
            if (PendingWorkRegistry.YAXUNIT_LAUNCH.get(resumeKey) != null)
            {
                return PendingExecutor.resume(PendingWorkRegistry.YAXUNIT_LAUNCH,
                    YaxunitTestsTool.NAME, resumeKey, 1_000L,
                    pending -> putPendingContext(pending, resumeKey,
                        java.util.Collections.emptyList()));
            }
            int resumeTimeout = TimeoutArgs.readSeconds(params, DEFAULT_TIMEOUT_SECONDS, 1, 0);
            long resumeStartedAt = callStartedAt(params, System::currentTimeMillis);
            String resumed;
            try
            {
                resumed = resumeRun(resumeKey, pollingDeadline(resumeStartedAt, resumeTimeout),
                    System::currentTimeMillis, Thread::sleep);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return "**Error:** Test execution was interrupted while waiting"; //$NON-NLS-1$
            }
            if (resumed != null)
            {
                return resumed;
            }
            return PendingExecutor.resume(PendingWorkRegistry.YAXUNIT_LAUNCH,
                YaxunitTestsTool.NAME, resumeKey, 1_000L,
                pending -> putPendingContext(pending, resumeKey, java.util.Collections.emptyList()));
        }
        String configName = JsonUtils.extractStringArgument(params, "launchConfigurationName"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        String extensions = JsonUtils.extractStringArgument(params, "extensions"); //$NON-NLS-1$
        String modules = JsonUtils.extractStringArgument(params, "modules"); //$NON-NLS-1$
        String tests = JsonUtils.extractStringArgument(params, "tests"); //$NON-NLS-1$
        int timeout = TimeoutArgs.readSeconds(params, DEFAULT_TIMEOUT_SECONDS, 1, 0);
        long startedAt = callStartedAt(params, System::currentTimeMillis);

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
            startedAt,
            updateBeforeLaunch(params), reuseRecent(params));
    }

    /**
     * Carries the facade's entry time through installation and into the runner.
     *
     * @param params the caller's arguments
     * @param startedAt the facade entry time
     * @return a copy carrying that time
     */
    static Map<String, String> withCallStart(Map<String, String> params, long startedAt)
    {
        Map<String, String> stamped = new LinkedHashMap<>(params);
        stamped.putIfAbsent(CALL_STARTED_AT, Long.toString(startedAt));
        return stamped;
    }

    /**
     * Reads the entry time, falling back to the supplied clock for the standalone alias.
     *
     * @param params the call arguments
     * @param clock the current-time source
     * @return the call entry time
     */
    static long callStartedAt(Map<String, String> params, LongSupplier clock)
    {
        String value = params.get(CALL_STARTED_AT);
        if (value != null)
        {
            try
            {
                return Long.parseLong(value);
            }
            catch (NumberFormatException invalid)
            {
                // An internal value cannot make a public call fail; use this entry time instead.
            }
        }
        return clock.getAsLong();
    }

    /**
     * The instant polling must stop so the remainder is available to assemble the answer.
     *
     * @param startedAt the call entry time
     * @param timeoutSeconds the complete call budget
     * @return the polling deadline
     */
    static long pollingDeadline(long startedAt, int timeoutSeconds)
    {
        long timeoutMs = timeoutSeconds * 1000L;
        long reserve = Math.min(MAX_RESPONSE_ASSEMBLY_RESERVE_MS, timeoutMs / 10L);
        return startedAt + timeoutMs - reserve;
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
        String modules, String tests, int timeout, long startedAt,
        DebugSessionStarter.LaunchUpdate update,
        boolean reuseRecent)
    {
        long deadline = startedAt + timeout * 1000L;
        long pollDeadline = pollingDeadline(startedAt, timeout);
        try
        {
            if (System.currentTimeMillis() >= deadline)
            {
                return deadlineBeforeClient(null);
            }
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

            // The pickup checks run again after every wait for another call's launch, while the
            // report cache and the infobase update are prepared once: a call that waited joins a
            // launch that has become visible, and only a launch that failed leaves the maps for
            // this call to start its own.
            boolean launchPrepared = false;
            while (true)
            {
                String pendingLaunch = PENDING_LAUNCHES.get(runKey);
                if (pendingLaunch != null)
                {
                    return PendingExecutor.resume(PendingWorkRegistry.YAXUNIT_LAUNCH,
                        YaxunitTestsTool.NAME, pendingLaunch, 1_000L,
                        pending -> putPendingContext(pending, pendingLaunch,
                            java.util.Collections.emptyList()));
                }

                ILaunch existing = ACTIVE_LAUNCHES.get(runKey);
                if (existing != null)
                {
                    // The answers of a run this call did not start carry the run's own launch-time
                    // decision, not this call's: a pickup may name updateBeforeLaunch=false explicitly
                    // or arrive after the preset changed, and neither rewrites what the launch did.
                    RunContext runContext = runContextOf(runKey, projectName, extensions, modules, tests);
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
                    return pollLaunch(existing, reportDir, pollDeadline, runKey,
                        runContext, System::currentTimeMillis, Thread::sleep);
                }

                if (!launchPrepared)
                {
                    launchPrepared = true;
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
                        // Whether this is the pickup of a run still waiting for its report or a recent one
                        // asked for again, the report belongs to the run that wrote it, and so does the
                        // update decision its answer carries.
                        RunContext runContext = runContextOf(runKey, projectName, extensions, modules, tests);
                        return handOverCached(runKey, cached, reuseRecent, runContext, RunReceipts::write);
                    }

                    // Only a launch needs an infobase that is up to date: a call answered from a report
                    // that is already there starts nothing. Run outside the launch lock, which is held for
                    // the launch itself and would otherwise be held for the length of an update.
                    if (System.currentTimeMillis() >= pollDeadline)
                    {
                        return deadlineBeforeClient(null);
                    }
                    String updateRefusal = preLaunchUpdateRefusal(update, projectName,
                        applicationId, DebugSessionStarter::updateDatabaseIfNeeded);
                    if (updateRefusal != null)
                    {
                        Activator.logInfo("Refusing the YAXUnit launch for " + runKey + ": " + updateRefusal); //$NON-NLS-1$
                        return "**Error:** " + updateRefusal; //$NON-NLS-1$
                    }

                    if (System.currentTimeMillis() >= pollDeadline)
                    {
                        return deadlineBeforeClient("the pre-launch infobase update"); //$NON-NLS-1$
                    }
                }

                ILaunch launch = null;
                DebugSessionStarter.LaunchUnderWay launchUnderWay = null;
                List<Map<String, Object>> dialogsBefore = java.util.Collections.emptyList();
                boolean startedElsewhere = false;
                Object launchToken = null;
                synchronized (ACTIVE_LAUNCHES)
                {
                    ILaunch concurrent = ACTIVE_LAUNCHES.get(runKey);
                    if (concurrent != null && !concurrent.isTerminated())
                    {
                        Activator.logInfo("Reusing the active YAXUnit launch for runKey=" + runKey); //$NON-NLS-1$
                        launch = concurrent;
                    }
                    else if (!launchSlotFree(runKey))
                    {
                        startedElsewhere = true;
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
                        // Published before the launch call runs, still under the lock: a second
                        // call of these tests finds the mark inside the same lock and waits
                        // instead of wiping this launch's report directory and starting a
                        // second client.
                        launchToken = publishLaunchUnderWay(runKey);
                        dialogsBefore = ModalDialogWatch.current().getDialogs();
                        launchUnderWay = DebugSessionStarter.startLaunch(workingCopy,
                            ILaunchManager.RUN_MODE, new NullProgressMonitor());
                        // The decision every answer of this run carries, put where the pickups read
                        // it: a later call's own arguments and preset must not speak for the launch.
                        // Recorded before the launch becomes visible, because a concurrent call finds
                        // the launch without taking this lock and reads the decision straight away.
                        noteLaunchSkipDecision(runKey, update.skippedByPreset);
                        // A run started here whose report nobody has read yet. This is what makes the
                        // next call a pickup rather than a request for a new run, and it is cleared
                        // the moment the report is handed over.
                        noteUndelivered(runKey);
                    }
                }

                if (startedElsewhere)
                {
                    // Another call started this runKey's launch moments ago and its client has not
                    // become visible. Wait for that launch to become visible and join it; only a
                    // launch that failed leaves this call starting its own.
                    if (!awaitLaunchVisible(runKey, pollDeadline, System::currentTimeMillis,
                        Thread::sleep))
                    {
                        return deadlineBeforeClient("another call starting these tests"); //$NON-NLS-1$
                    }
                    continue;
                }

                // The launch this call started wrote its decision to the store above; one it joined
                // holds the decision its starting call recorded. Either way the answer carries the
                // run's own.
                RunContext runContext = runContextOf(runKey, projectName, extensions, modules, tests);
                if (launchUnderWay != null)
                {
                    try
                    {
                        return waitForLaunch(launchUnderWay, dialogsBefore, reportDir, pollDeadline,
                            runKey, runContext);
                    }
                    finally
                    {
                        // The visibility transitions clear the mark themselves; this removes what
                        // remains when the wait left without any of them running, and only this
                        // call's own mark - never one a later start put under the same key.
                        forgetLaunchUnderWay(runKey, launchToken);
                    }
                }
                return pollLaunch(launch, reportDir, pollDeadline, runKey, runContext,
                    System::currentTimeMillis, Thread::sleep);
            }
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

    private String waitForLaunch(DebugSessionStarter.LaunchUnderWay launch,
        List<Map<String, Object>> dialogsBefore, Path reportDir, long pollDeadline,
        String runKey, RunContext runContext)
        throws InterruptedException
    {
        long waitDeadline = Math.min(pollDeadline,
            System.currentTimeMillis() + DebugSessionStarter.LAUNCH_WAIT_MS);
        while (!launch.hasReturned())
        {
            List<Map<String, Object>> dialogs = DebugSessionStarter.newDialogs(dialogsBefore,
                ModalDialogWatch.current().getDialogs());
            if (!dialogs.isEmpty() || System.currentTimeMillis() >= waitDeadline)
            {
                return handOverLaunch(launch, dialogsBefore, dialogs, reportDir, runKey, runContext);
            }
            Thread.sleep(DebugSessionStarter.LAUNCH_STEP_MS);
        }
        return awaitLaunchAndPoll(launch, dialogsBefore, java.util.Collections.emptyList(),
            reportDir, pollDeadline, runKey, runContext, System::currentTimeMillis,
            Thread::sleep);
    }

    /**
     * Hands a launch blocked inside EDT to a runKey without pressing its dialog.
     * <p>
     * The run is tracked to its end under the key this envelope names: the continuation waits for
     * the launch call to come back however long the dialog stands open, then for the client and
     * its report, with no deadline of the call that handed it over - that call's deadline bounded
     * its answer, not the run.
     * </p>
     *
     * @param launch the launch standing inside EDT
     * @param dialogsBefore the workbench's modals as they were before the launch
     * @param dialogsSeen the modals that appeared since and hold the launch
     * @param reportDir where the run's report appears
     * @param runKey the run the launch belongs to
     * @param runContext the run's launch-time context
     * @return the Pending envelope naming the key the report is collected by
     */
    static String handOverLaunch(DebugSessionStarter.LaunchUnderWay launch,
        List<Map<String, Object>> dialogsBefore, List<Map<String, Object>> dialogsSeen,
        Path reportDir, String runKey, RunContext runContext)
    {
        String pendingKey = PendingWorkRegistry.computeRunKey(YaxunitTestsTool.NAME,
            Long.toString(System.nanoTime()));
        PENDING_LAUNCHES.put(runKey, pendingKey);
        // The launch is visible as a handed-over run from this point, so a call that waited on
        // the in-flight mark finds the pending key and resumes this run's entry.
        LAUNCHES_IN_FLIGHT.remove(runKey);
        PENDING_REPORT_DIRS.put(pendingKey, reportDir);
        PENDING_SKIP_DECISIONS.put(pendingKey, Boolean.valueOf(runContext.skippedByPreset));
        PendingWorkRegistry.PendingEntry entry = PendingWorkRegistry.YAXUNIT_LAUNCH.getOrStart(
            pendingKey, ongoing ->
            {
                try
                {
                    return awaitLaunchAndPoll(launch, dialogsBefore, dialogsSeen, reportDir,
                        NO_DEADLINE, runKey, runContext, System::currentTimeMillis,
                        Thread::sleep);
                }
                catch (InterruptedException stopped)
                {
                    Thread.currentThread().interrupt();
                    return ToolResult.error("The wait for the YAXUnit launch was interrupted") //$NON-NLS-1$
                        .put("operation", YaxunitTestsTool.NAME) //$NON-NLS-1$
                        .toJson();
                }
                finally
                {
                    PENDING_LAUNCHES.remove(runKey, pendingKey);
                    forgetPendingContext(pendingKey);
                }
            });
        entry.startedBy = YaxunitTestsTool.NAME;
        entry.attachWorkExit(() ->
        {
            PENDING_LAUNCHES.remove(runKey, pendingKey);
            forgetPendingContext(pendingKey);
        });
        return PendingExecutor.buildPendingJson(YaxunitTestsTool.NAME, pendingKey, entry,
            DebugSessionStarter.LAUNCH_WAIT_MS,
            pending -> putPendingContext(pending, pendingKey, dialogsSeen));
    }

    /**
     * Adds the launch-specific fields to every initial or repeated Pending envelope.
     */
    private static void putPendingContext(ToolResult pending, String pendingKey,
        List<Map<String, Object>> dialogs)
    {
        Path reportDir = PENDING_REPORT_DIRS.get(pendingKey);
        if (reportDir != null)
        {
            pending.put("reportDir", reportDir.toString()); //$NON-NLS-1$
        }
        DebugSessionStarter.putDatabaseUpdate(pending, null,
            Boolean.TRUE.equals(PENDING_SKIP_DECISIONS.get(pendingKey)));
        if (dialogs != null && !dialogs.isEmpty())
        {
            pending.put("dialogs", dialogs) //$NON-NLS-1$
                .put("blockedByDialog", Boolean.TRUE); //$NON-NLS-1$
        }
        pending.put("hint", "Answer an EDT dialog only with answer_dialog, then poll this runKey."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Removes the context retained only while a launch can answer Pending. */
    private static void forgetPendingContext(String pendingKey)
    {
        PENDING_REPORT_DIRS.remove(pendingKey);
        PENDING_SKIP_DECISIONS.remove(pendingKey);
    }

    /**
     * Completes a returned launch, refusing one that created no client process.
     * <p>
     * The wait for the launch call to come back has no deadline: the launch stands inside EDT for
     * as long as its modal holds it, and giving up on the first call's clock would orphan the
     * client that starts once the dialog is answered. The client's start window runs its full
     * length from the launch's return, not clipped to what remains of a call's polling budget.
     * A {@code pollDeadline} of {@link #NO_DEADLINE} polls the client to its end and returns its
     * report - the continuation a Pending answer leaves behind runs that way.
     * </p>
     *
     * @param launch the launch call
     * @param dialogsBefore the workbench's modals as they were before the launch
     * @param knownDialogs the modals an earlier wait already saw holding this launch
     * @param reportDir where the run's report appears
     * @param pollDeadline when this call's polling window closes, or {@link #NO_DEADLINE} to poll
     *            the client to its end
     * @param runKey the run the launch belongs to
     * @param runContext the run's launch-time context
     * @param clock the current-time source
     * @param sleeper the wait seam
     * @return the run answer
     * @throws InterruptedException when a wait is interrupted
     */
    static String awaitLaunchAndPoll(DebugSessionStarter.LaunchUnderWay launch,
        List<Map<String, Object>> dialogsBefore, List<Map<String, Object>> knownDialogs,
        Path reportDir, long pollDeadline, String runKey, RunContext runContext,
        LongSupplier clock, Sleeper sleeper)
        throws InterruptedException
    {
        while (!launch.hasReturned())
        {
            sleeper.sleep(DebugSessionStarter.LAUNCH_STEP_MS);
        }
        List<Map<String, Object>> observedDialogs = DebugSessionStarter.newDialogs(dialogsBefore,
            ModalDialogWatch.current().getDialogs());
        if (observedDialogs.isEmpty())
        {
            observedDialogs = knownDialogs;
        }
        if (launch.error() != null)
        {
            forgetFailedRun(runKey);
            return clientNotLaunched("the launch call failed: " + launch.error(), //$NON-NLS-1$
                observedDialogs);
        }
        ILaunch created = launch.launch();
        if (created == null)
        {
            forgetFailedRun(runKey);
            return clientNotLaunched("EDT returned no launch", observedDialogs); //$NON-NLS-1$
        }
        long appearanceDeadline = clock.getAsLong() + CLIENT_START_WAIT_MS;
        if (!awaitAnyProcess(created, appearanceDeadline, clock, sleeper))
        {
            forgetFailedRun(runKey);
            return clientNotLaunched("no client process appeared within the start window", //$NON-NLS-1$
                observedDialogs);
        }
        rememberActiveLaunch(runKey, created);
        return pollLaunch(created, reportDir, pollDeadline, runKey, runContext, clock,
            sleeper);
    }

    /**
     * Waits briefly for the process the launch is supposed to create.
     */
    static boolean awaitAnyProcess(ILaunch launch, long deadline, LongSupplier clock,
        Sleeper sleeper) throws InterruptedException
    {
        while (true)
        {
            IProcess[] processes = launch.getProcesses();
            if (processes != null && processes.length > 0)
            {
                return true;
            }
            long remaining = deadline - clock.getAsLong();
            if (launch.isTerminated() || remaining <= 0)
            {
                return false;
            }
            sleeper.sleep(Math.min(CLIENT_START_STEP_MS, remaining));
        }
    }

    /** Removes every trace of a launch that never produced a client. */
    static void forgetFailedRun(String runKey)
    {
        ACTIVE_LAUNCHES.remove(runKey);
        RUN_REPORT_DIRS.remove(runKey);
        RUN_CONTEXTS.remove(runKey);
        UNDELIVERED_RUNS.remove(runKey);
        LAUNCH_SKIP_DECISIONS.remove(runKey);
        LAUNCHES_IN_FLIGHT.remove(runKey);
    }

    /**
     * Tracks a launch only after its client process has been observed. Tracking it is one of the
     * visibility transitions that end the in-flight mark, so a call waiting on that mark can now
     * find the client and join it.
     *
     * @param runKey the run the launch belongs to
     * @param launch the launch whose client was observed
     */
    static void rememberActiveLaunch(String runKey, ILaunch launch)
    {
        ACTIVE_LAUNCHES.put(runKey, launch);
        LAUNCHES_IN_FLIGHT.remove(runKey);
    }

    /**
     * Reads the tracked launch for lifecycle tests and concurrent pickups.
     */
    static ILaunch activeLaunch(String runKey)
    {
        return ACTIVE_LAUNCHES.get(runKey);
    }

    /** Builds the named refusal for a launch that produced no client. */
    static String clientNotLaunched(String reason, List<Map<String, Object>> dialogs)
    {
        ToolResult refusal = ToolResult.error("The YAXUnit client was not launched: " + reason) //$NON-NLS-1$
            .put("operation", YaxunitTestsTool.NAME) //$NON-NLS-1$
            .put("clientLaunched", Boolean.FALSE); //$NON-NLS-1$
        if (dialogs != null && !dialogs.isEmpty())
        {
            refusal.put("dialogTitle", dialogs.get(0).get("title")) //$NON-NLS-1$ //$NON-NLS-2$
                .put("dialogs", dialogs); //$NON-NLS-1$
        }
        return refusal.toJson();
    }

    /**
     * Continues a launch identified only by the run key returned in a Pending answer.
     *
     * @param runKey the pending run
     * @param pollDeadline when this call stops polling
     * @param clock the clock
     * @param sleeper the wait seam
     * @return the run answer, or <code>null</code> when the key is not one of this runner's
     * @throws InterruptedException when polling is interrupted
     */
    static String resumeRun(String runKey, long pollDeadline, LongSupplier clock,
        Sleeper sleeper) throws InterruptedException
    {
        Path reportDir = RUN_REPORT_DIRS.get(runKey);
        RunContext runContext = RUN_CONTEXTS.get(runKey);
        if (reportDir == null || runContext == null)
        {
            return null;
        }
        ILaunch launch = ACTIVE_LAUNCHES.get(runKey);
        if (launch != null && !launch.isTerminated())
        {
            return pollLaunch(launch, reportDir, pollDeadline, runKey, runContext,
                clock, sleeper);
        }
        ACTIVE_LAUNCHES.remove(runKey);
        File junitXml = awaitReportFile(reportDir, clock, sleeper);
        if (junitXml != null)
        {
            return handOverFinishedLaunch(runKey, junitXml, runContext, RunReceipts::write);
        }
        forgetFailedRun(runKey);
        return "**Error:** The previous launch finished, but no JUnit XML report was found in " //$NON-NLS-1$
            + reportDir + ". Confirm the YAXUnit extension is installed."; //$NON-NLS-1$
    }

    /**
     * Waits briefly for the report of a launch that has just terminated: the client writes it on
     * its way out, and a poll that reads the directory in the same moment races that write.
     *
     * @param reportDir where the report appears
     * @param clock the current-time source
     * @param sleeper the wait seam
     * @return the report file, or <code>null</code> when none appeared within the window
     * @throws InterruptedException when the wait is interrupted
     */
    static File awaitReportFile(Path reportDir, LongSupplier clock, Sleeper sleeper)
        throws InterruptedException
    {
        long deadline = clock.getAsLong() + CLIENT_START_WAIT_MS;
        while (true)
        {
            File junitXml = findJunitXml(reportDir);
            if (junitXml != null)
            {
                return junitXml;
            }
            long remaining = deadline - clock.getAsLong();
            if (remaining <= 0)
            {
                return null;
            }
            sleeper.sleep(Math.min(CLIENT_START_STEP_MS, remaining));
        }
    }

    /**
     * Polls a tracked launch until it terminates or this call's polling window closes, then
     * answers its report.
     *
     * @param launch the client launch
     * @param reportDir where the run's report appears
     * @param pollDeadline when this call's polling window closes, or {@link #NO_DEADLINE} to
     *            poll the client to its end
     * @param runKey the run the launch belongs to
     * @param runContext the run's launch-time context
     * @param clock the current-time source
     * @param sleeper the wait seam
     * @return the run answer
     * @throws InterruptedException when polling is interrupted
     */
    static String pollLaunch(ILaunch launch, Path reportDir, long pollDeadline,
        String runKey, RunContext runContext, LongSupplier clock, Sleeper sleeper)
        throws InterruptedException
    {
        RUN_REPORT_DIRS.put(runKey, reportDir);
        RUN_CONTEXTS.put(runKey, runContext);
        while (!launch.isTerminated())
        {
            long remaining = pollDeadline - clock.getAsLong();
            if (remaining <= 0)
            {
                return deadlinePending(launch, reportDir, runKey, runContext);
            }
            sleeper.sleep(Math.min(POLL_INTERVAL_MS, remaining));
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
     * Builds the non-terminal answer returned when this call's polling window closes.
     *
     * @param launch the client launch that remains active
     * @param reportDir where the report will appear
     * @param runKey the key that retrieves the run
     * @param runContext the run's launch-time context
     * @return a Pending answer that leaves the launch untouched
     */
    static String deadlinePending(ILaunch launch, Path reportDir, String runKey,
        RunContext runContext)
    {
        IProcess live = firstLiveProcess(launch);
        ToolResult pending = PendingEnvelope.mark(ToolResult.success()
            .put("operation", YaxunitTestsTool.NAME) //$NON-NLS-1$
            .put("status", "Pending") //$NON-NLS-1$ //$NON-NLS-2$
            .put("runKey", runKey) //$NON-NLS-1$
            .put("reportDir", reportDir.toString()) //$NON-NLS-1$
            .put("deadlineExpired", Boolean.TRUE) //$NON-NLS-1$
            .put("clientStillRunning", Boolean.TRUE) //$NON-NLS-1$
            .put("output", pendingText(reportDir, runContext.skippedByPreset)) //$NON-NLS-1$
            .put("hint", "Poll this runKey to retrieve the report; the client keeps running.")); //$NON-NLS-1$ //$NON-NLS-2$
        if (live != null)
        {
            pending.put("processId", processIdentity(live)); //$NON-NLS-1$
        }
        DebugSessionStarter.putDatabaseUpdate(pending, null, runContext.skippedByPreset);
        return pending.toJson();
    }

    /**
     * Finds a client process that has not terminated yet.
     */
    private static IProcess firstLiveProcess(ILaunch launch)
    {
        IProcess[] processes = launch.getProcesses();
        if (processes == null)
        {
            return null;
        }
        for (IProcess process : processes)
        {
            if (process != null && !process.isTerminated())
            {
                return process;
            }
        }
        return null;
    }

    /**
     * Names a surviving process with Eclipse's process id, falling back to its label.
     */
    private static String processIdentity(IProcess process)
    {
        if (process == null)
        {
            return null;
        }
        String id = process.getAttribute(IProcess.ATTR_PROCESS_ID);
        return id == null || id.isEmpty() ? process.getLabel() : id;
    }

    /**
     * Refuses a call whose budget ran out before any client existed.
     *
     * @param step what consumed the budget, or <code>null</code> when the call does not know
     * @return the refusal
     */
    static String deadlineBeforeClient(String step)
    {
        return ToolResult.error("The YAXUnit call deadline expired before the client was launched" //$NON-NLS-1$
            + (step == null ? "." : " - the time went to " + step + '.')) //$NON-NLS-1$ //$NON-NLS-2$
            .put("operation", YaxunitTestsTool.NAME) //$NON-NLS-1$
            .put("deadlineExpired", Boolean.TRUE) //$NON-NLS-1$
            .put("clientLaunched", Boolean.FALSE) //$NON-NLS-1$
            .toJson();
    }

    /**
     * Publishes a launch as under way, before the launch call runs.
     *
     * @param runKey the run the launch belongs to
     * @return the token that ends this publication and no other
     */
    static Object publishLaunchUnderWay(String runKey)
    {
        Object token = new Object();
        LAUNCHES_IN_FLIGHT.put(runKey, token);
        return token;
    }

    /**
     * Ends the publication of a launch as under way, removing only the mark this call put.
     *
     * @param runKey the run the launch belongs to
     * @param token the token {@link #publishLaunchUnderWay} returned
     */
    static void forgetLaunchUnderWay(String runKey, Object token)
    {
        LAUNCHES_IN_FLIGHT.remove(runKey, token);
    }

    /**
     * Whether a call holding the launch lock may start this runKey's launch: no live client of it
     * is tracked and no launch of it is under way.
     *
     * @param runKey the run a call wants to launch
     * @return whether the launch slot is free
     */
    static boolean launchSlotFree(String runKey)
    {
        ILaunch concurrent = ACTIVE_LAUNCHES.get(runKey);
        return (concurrent == null || concurrent.isTerminated())
            && !LAUNCHES_IN_FLIGHT.containsKey(runKey);
    }

    /**
     * Waits for another call's launch of these tests to become visible - tracked, handed over or
     * failed - so the waiting call can join it or start cleanly once it is gone.
     *
     * @param runKey the run whose launch is under way
     * @param deadline when the wait gives up, in milliseconds since the epoch
     * @param clock the current-time source
     * @param sleeper the wait seam
     * @return whether the launch became visible before the deadline
     * @throws InterruptedException when the wait is interrupted
     */
    static boolean awaitLaunchVisible(String runKey, long deadline, LongSupplier clock,
        Sleeper sleeper) throws InterruptedException
    {
        while (LAUNCHES_IN_FLIGHT.containsKey(runKey))
        {
            long remaining = deadline - clock.getAsLong();
            if (remaining <= 0)
            {
                return false;
            }
            sleeper.sleep(Math.min(DebugSessionStarter.LAUNCH_STEP_MS, remaining));
        }
        return true;
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
        String answer = readResults(junitXml, cacheMark, runContext, claimed, receipts);
        RUN_REPORT_DIRS.remove(runKey);
        RUN_CONTEXTS.remove(runKey);
        return answer;
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
     * Builds the legacy text envelope used by the alias contract tests.
     *
     * @param reportDir the report directory
     * @param skippedByPreset whether the launch skipped its unnamed update
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
        return text + "Poll the returned `runKey` to retrieve the report.\n"; //$NON-NLS-1$
    }

    /**
     * The marker read by the facade when it wraps the legacy pending text.
     *
     * @return the update skip marker and note
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

    private static File findJunitXml(Path tempDir)
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
