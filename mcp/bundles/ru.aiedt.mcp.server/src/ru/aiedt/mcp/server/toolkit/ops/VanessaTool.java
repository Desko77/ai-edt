/*
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.preference.IPreferenceStore;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.support.AllureResultReader;
import ru.aiedt.mcp.server.support.ErrorTags;
import ru.aiedt.mcp.server.support.InfobaseAddress;
import ru.aiedt.mcp.server.support.InfobaseIdentity;
import ru.aiedt.mcp.server.support.MonopolyLock;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.FailureScreenshots;
import ru.aiedt.mcp.server.support.JUnitReportFormatter;
import ru.aiedt.mcp.server.support.JUnitRunOutcome;
import ru.aiedt.mcp.server.support.JUnitXmlReader;
import ru.aiedt.mcp.server.support.PendingWorkRegistry;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;
import com.google.gson.JsonObject;

/**
 * Scenario UI testing of a 1C configuration via <b>Vanessa Automation</b>: plays
 * Gherkin feature files ("Дано / Когда / Тогда") against a running infobase and
 * reports which scenario step failed and why. Complements {@code yaxunit_tests}
 * (which checks code from the inside) by driving the program from the outside -
 * opening forms, clicking buttons, filling fields.
 *
 * <p><b>External process, like {@code code_review}</b>: the user points
 * {@link PrefKeys#PREF_VANESSA_EPF} at {@code vanessa-automation.epf}
 * and {@link PrefKeys#PREF_VANESSA_1C_EXE} at the 1C thick client
 * ({@code 1cv8.exe}); this tool launches
 * {@code 1cv8 ENTERPRISE /IBConnectionString ... /Execute <epf> /C "StartFeaturePlayer;VAParams=<json>"},
 * waits for the run to finish, parses the JUnit XML Vanessa writes, and returns
 * scenario counts + failure details + failure screenshots. When either path is
 * not configured it returns a setup hint instead of failing hard.
 *
 * <p><b>Tier-1 (synchronous)</b>: the run blocks up to {@code timeoutSeconds};
 * a very long suite should raise the timeout. The Vanessa launch parameters
 * (the {@code /C} command and the {@code VAParams.json} keys) are Vanessa-version
 * sensitive - the command line and the key names of the settings file are logged so they can
 * be reconciled against the installed Vanessa build. Values are not: one may carry a secret.
 */
public class VanessaTool implements IMcpTool
{
    public static final String NAME = "vanessa"; //$NON-NLS-1$

    private static final int DEFAULT_TIMEOUT_SEC = 300;
    static final int MAX_TIMEOUT_SEC = 3600;
    private static final int OUTPUT_TAIL = 3000;

    /** 1C on Windows writes its console output in the OEM/ANSI Russian codepage. */
    private static final Charset CP1251 = charset("windows-1251"); //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Scenario UI testing via Vanessa Automation: plays Gherkin feature files against a " //$NON-NLS-1$
            + "running infobase and reports which scenario step failed and why (+ failure " //$NON-NLS-1$
            + "screenshots). Complements yaxunit_tests (code from the inside) by driving the UI " //$NON-NLS-1$
            + "from the outside. Pass featurePath (a .feature file or a directory of them) and " //$NON-NLS-1$
            + "projectName - the infobase the project is bound to is the one played against, " //$NON-NLS-1$
            + "or connectionString to name another. " //$NON-NLS-1$
            + "Requires vanessa-automation.epf and the 1C thick client (1cv8.exe) configured in EDT " //$NON-NLS-1$
            + "preferences (download from github.com/Pr-Mex/vanessa-automation). Waits for the run " //$NON-NLS-1$
            + "and answers when it ends; async=true answers with a runKey instead, which comes " //$NON-NLS-1$
            + "back for the result and also cancels the run. Raise timeoutSeconds for long suites."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("featurePath", //$NON-NLS-1$
                "Path to a .feature file or a directory of feature files. Required to start a run " //$NON-NLS-1$
                    + "unless scenarioText is given; a call that carries a runKey takes neither. A " //$NON-NLS-1$
                    + "relative path is resolved against the project when projectName is given.") //$NON-NLS-1$
            .stringProperty("scenarioText", //$NON-NLS-1$
                "The scenario itself in place of a file: Gherkin text written to the run's own " //$NON-NLS-1$
                    + "temporary directory and played from there. For a caller that reaches this " //$NON-NLS-1$
                    + "server over MCP and has no way to put a file on the machine - a form in a " //$NON-NLS-1$
                    + "running 1C is photographed by a scenario that opens it and captures. The " //$NON-NLS-1$
                    + "step wording is Vanessa's own and differs between its versions, so it is " //$NON-NLS-1$
                    + "yours to give, the same way the vanessaParams names are. Given together " //$NON-NLS-1$
                    + "with featurePath, both are refused.") //$NON-NLS-1$
            .stringProperty("formToOpen", //$NON-NLS-1$
                "The form to open and photograph, in the words the opening step expects - a " //$NON-NLS-1$
                    + "common form's name, a catalog's FQN, whatever the step takes. The scenario " //$NON-NLS-1$
                    + "is composed from it, so neither featurePath nor scenarioText is passed " //$NON-NLS-1$
                    + "with it. The snapshot arrives among the run's screenshots.") //$NON-NLS-1$
            .stringProperty("openStep", //$NON-NLS-1$
                "The step that opens the form, with {form} where the name goes. Defaults to " //$NON-NLS-1$
                    + "opening a common form. A list form, an object form and an extension's " //$NON-NLS-1$
                    + "form are opened by different words, and the words belong to Vanessa and " //$NON-NLS-1$
                    + "differ between its versions, so pass the one your library uses.") //$NON-NLS-1$
            .stringProperty("startStep", //$NON-NLS-1$
                "The step that gets a client to work in. Defaults to launching TestClient or " //$NON-NLS-1$
                    + "attaching to one already running.") //$NON-NLS-1$
            .stringProperty("connectionString", //$NON-NLS-1$
                "1C infobase connection string, e.g. 'File=\"C:\\\\ib\";' or " //$NON-NLS-1$
                    + "'Srvr=\"host\";Ref=\"base\";'. Omitted, the infobase the named project is " //$NON-NLS-1$
                    + "bound to is used - EDT knows it already. A server infobase has to be named " //$NON-NLS-1$
                    + "here. A Pwd is refused: it would reach the " //$NON-NLS-1$
                    + "client as a command-line argument, readable by every process on the machine. " //$NON-NLS-1$
                    + "Use an infobase that needs no password, or one that accepts the operating " //$NON-NLS-1$
                    + "system's authentication. A call that carries a runKey does not take " //$NON-NLS-1$
                    + "it.") //$NON-NLS-1$
            .booleanProperty("async", //$NON-NLS-1$
                "Hand back a runKey instead of waiting out the run. Come back with that runKey " //$NON-NLS-1$
                    + "for the result, or with runKey and cancel=true to stop the client.") //$NON-NLS-1$
            .stringProperty("runKey", //$NON-NLS-1$
                "The key from a Pending reply: comes back for that run rather than starting one.") //$NON-NLS-1$
            .booleanProperty("cancel", //$NON-NLS-1$
                "With runKey: stops the client and its worker processes. What the scenarios " //$NON-NLS-1$
                    + "already wrote to the infobase stays written.") //$NON-NLS-1$
            .integerProperty("waitSeconds", //$NON-NLS-1$
                "With runKey: how long to wait this time before answering Pending again.") //$NON-NLS-1$
            .objectProperty("vanessaParams", //$NON-NLS-1$
                "Optional JSON object of Vanessa parameters to add to VAParams.json, for filtering " //$NON-NLS-1$
                    + "by tag or by scenario name among other things. The names are Vanessa's own " //$NON-NLS-1$
                    + "and differ between its versions, so they are yours to give; it ignores one " //$NON-NLS-1$
                    + "it does not know. The few this tool reads its result back from are " //$NON-NLS-1$
                    + "refused.") //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "Optional EDT project name - used to resolve a relative featurePath and as the " //$NON-NLS-1$
                    + "working directory.") //$NON-NLS-1$
            .integerProperty("timeoutSeconds", //$NON-NLS-1$
                "Max seconds to wait for the run (default 300, max 3600).") //$NON-NLS-1$
            .stringProperty("infobaseUser", //$NON-NLS-1$
                "Name of the 1C user the run signs in as. A base with users defined meets a " //$NON-NLS-1$
                    + "client that names none with a login window, and the run then waits out " //$NON-NLS-1$
                    + "its whole deadline. A password cannot be passed here.") //$NON-NLS-1$
            .booleanProperty("testClient", //$NON-NLS-1$
                "Name a test client in VAParams for the start step to launch (default false). " //$NON-NLS-1$
                    + "Measured on one stand: with the block present Vanessa writes no report " //$NON-NLS-1$
                    + "at all, for any scenario; without it the same scenarios play.") //$NON-NLS-1$
            .integerProperty("testClientPort", //$NON-NLS-1$
                "Port the test client listens on (default 48010). Name another when a second " //$NON-NLS-1$
                    + "run or another EDT already holds it.") //$NON-NLS-1$
            .booleanProperty("screenshots", //$NON-NLS-1$
                "Capture a screenshot on step failure (default true).") //$NON-NLS-1$
            .booleanProperty("keepOpen", //$NON-NLS-1$
                "Leave the 1C client open after the run to watch it (default false). Note: a kept-open " //$NON-NLS-1$
                    + "client will time out here since it never exits.") //$NON-NLS-1$
            .integerProperty("stepDelaySeconds", //$NON-NLS-1$
                "Slow each step down by N seconds to watch the run live (default 0).") //$NON-NLS-1$
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
        String runKey = JsonUtils.extractStringArgument(params, "runKey"); //$NON-NLS-1$
        if (runKey != null && !runKey.isEmpty())
        {
            // A key means the caller is coming back for a run, not starting one.
            if (JsonUtils.extractBooleanArgument(params, "cancel", false)) //$NON-NLS-1$
            {
                return cancelRun(runKey);
            }
            return collect(runKey, params);
        }
        return start(params);
    }

    /**
     * How long a run is waited for before the caller is given a key instead.
     * <p>
     * Long enough that a run over a handful of scenarios simply answers, short enough that nobody
     * sits on a connection through a suite.
     * </p>
     */
    private static final long ASYNC_FIRST_WAIT_MS = 20_000L;

    /**
     * The longest one poll may wait. A caller asking for more is answered Pending sooner and can
     * poll again; holding an HTTP handler for longer serves nobody.
     */
    private static final int MAX_POLL_WAIT_SEC = 120;

    /**
     * Comes back for a run that was still playing.
     *
     * @param runKey the key from the Pending reply.
     * @param params the call, read for how long to wait this time.
     * @return the result, or another Pending reply
     */
    private String collect(String runKey, Map<String, String> params)
    {
        PendingWorkRegistry registry = PendingWorkRegistry.VANESSA;
        registry.pruneExpired();
        PendingWorkRegistry.PendingEntry entry = registry.get(runKey);
        if (entry == null)
        {
            return ToolResult.error("runKey not found - the run finished and its result was " //$NON-NLS-1$
                + "already collected, it was cancelled, or it was abandoned long enough to be " //$NON-NLS-1$
                + "dropped. Whatever the scenarios reached before that stays written: read the " //$NON-NLS-1$
                + "infobase, not this answer, to find out what they did.").toJson(); //$NON-NLS-1$
        }
        long wait = Math.max(1000L, Math.min(MAX_POLL_WAIT_SEC,
            JsonUtils.extractIntArgument(params, "waitSeconds", 20)) * 1000L); //$NON-NLS-1$
        String done = entry.await(wait);
        if (done != null)
        {
            registry.remove(runKey, entry);
            return done;
        }
        return ToolResult.success()
            .put("operation", NAME) //$NON-NLS-1$
            .put("status", "Pending") //$NON-NLS-1$ //$NON-NLS-2$
            .put(ru.aiedt.mcp.server.support.PendingEnvelope.MARK, true)
            .put("runKey", runKey) //$NON-NLS-1$
            .put("elapsedMs", entry.elapsedMs()) //$NON-NLS-1$
            .put("hint", "Still playing. Come back with the same runKey, or stop it with " //$NON-NLS-1$ //$NON-NLS-2$
                + "cancel=true.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Runs the scenarios, waiting for them or handing back a key to come back with.
     *
     * @param params the call's arguments.
     * @return the result, or a Pending reply
     */
    private String start(Map<String, String> params)
    {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        String epf = trimmed(store.getString(PrefKeys.PREF_VANESSA_EPF));
        String exe = trimmed(store.getString(PrefKeys.PREF_VANESSA_1C_EXE));
        if (epf == null || exe == null)
        {
            return ToolResult.error("vanessa is not configured. Set BOTH the Vanessa Automation .epf " //$NON-NLS-1$
                + "path and the 1C thick-client (1cv8.exe) path in EDT -> Window -> Preferences -> EDT " //$NON-NLS-1$
                + "MCP Server. Download vanessa-automation.epf from " //$NON-NLS-1$
                + "github.com/Pr-Mex/vanessa-automation. The run also needs a Vanessa-ready infobase " //$NON-NLS-1$
                + "reachable by the given connectionString.").toJson(); //$NON-NLS-1$
        }
        File epfFile = new File(epf);
        if (!epfFile.isFile())
        {
            return ToolResult.error("Configured Vanessa Automation .epf not found: " + epf).toJson(); //$NON-NLS-1$
        }
        File exeFile = new File(exe);
        if (!exeFile.isFile())
        {
            return ToolResult.error("Configured 1C thick client not found: " + exe).toJson(); //$NON-NLS-1$
        }

        String connectionString = JsonUtils.extractStringArgument(params, "connectionString"); //$NON-NLS-1$
        String infobaseFrom = null;
        InfobaseAddress.Address infobaseAddress = null;
        if (connectionString == null || connectionString.trim().isEmpty())
        {
            // The caller has EDT open, and EDT already knows which infobase the project belongs to
            // - it is the one update_database writes into. Making them type it again is asking for
            // what the environment holds, and a typed string can name a different infobase.
            String projectForInfobase = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
            IProject project = projectForInfobase == null || projectForInfobase.trim().isEmpty()
                ? null : ProjectResolver.resolve(projectForInfobase);
            InfobaseAddress.Address address = InfobaseAddress.ofProject(project);
            if (address.found())
            {
                connectionString = address.connectionString();
                infobaseFrom = address.name();
                infobaseAddress = address;
            }
        }
        if (connectionString == null || connectionString.trim().isEmpty())
        {
            return ToolResult.error("The infobase is not named. Pass projectName and the " //$NON-NLS-1$
                + "infobase the project is bound to is used, or connectionString to name one " //$NON-NLS-1$
                + "directly. A project with no infobase application, or one bound to a server " //$NON-NLS-1$
                + "infobase, has to be named directly.").toJson(); //$NON-NLS-1$
        }
        // Appended to the string, not passed as /N: the test client the start step launches is
        // given its own PathToInfobase, and a string carries the user into both.
        connectionString = namingTheUser(connectionString,
            JsonUtils.extractStringArgument(params, "infobaseUser")); //$NON-NLS-1$
        String secretRefusal = whyASecretCannotBePassed(connectionString);
        if (secretRefusal != null)
        {
            return ToolResult.error(secretRefusal).toJson();
        }
        String[] paramsRefusal = new String[1];
        JsonObject extraVaParams =
            extraParams(JsonUtils.extractStringArgument(params, "vanessaParams"), paramsRefusal); //$NON-NLS-1$
        if (paramsRefusal[0] != null)
        {
            return ToolResult.error(paramsRefusal[0]).toJson();
        }
        String featurePathArg = JsonUtils.extractStringArgument(params, "featurePath"); //$NON-NLS-1$
        String scenarioText = JsonUtils.extractStringArgument(params, "scenarioText"); //$NON-NLS-1$
        String formToOpen = JsonUtils.extractStringArgument(params, "formToOpen"); //$NON-NLS-1$
        boolean hasPath = featurePathArg != null && !featurePathArg.trim().isEmpty();
        boolean hasText = scenarioText != null && !scenarioText.trim().isEmpty();
        boolean hasForm = formToOpen != null && !formToOpen.trim().isEmpty();
        String badlyNamed = whyTheScenarioIsNotNamed(hasPath, hasText, hasForm);
        if (badlyNamed != null)
        {
            return ToolResult.error(badlyNamed).toJson();
        }
        if (hasForm)
        {
            String openStep = JsonUtils.extractStringArgument(params, "openStep"); //$NON-NLS-1$
            String startStep = JsonUtils.extractStringArgument(params, "startStep"); //$NON-NLS-1$
            String badlyFormed = whyTheFormCannotBeNamed(formToOpen, openStep, startStep);
            if (badlyFormed != null)
            {
                return ToolResult.error(badlyFormed).toJson();
            }
            scenarioText = scenarioForForm(formToOpen.trim(), startStep, openStep);
            hasText = true;
        }

        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        File workingDir = null;
        if (projectName != null && !projectName.isEmpty())
        {
            IProject project = ProjectResolver.resolve(projectName);
            if (project == null)
            {
                return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
            }
            if (project.getLocation() != null)
            {
                workingDir = project.getLocation().toFile();
            }
        }

        File featurePath = null;
        if (hasPath)
        {
            featurePath = resolveFeaturePath(featurePathArg.trim(), workingDir);
            if (!featurePath.exists())
            {
                return ToolResult.error("featurePath not found: " //$NON-NLS-1$
                    + featurePath.getAbsolutePath()).toJson();
            }
        }
        final String composedScenario = hasText ? scenarioText : null;
        final InfobaseAddress.Address settledAddress = infobaseAddress;
        final String settledInfobase = infobaseFrom;
        final String settledConnection = connectionString;

        boolean screenshots = JsonUtils.extractBooleanArgument(params, "screenshots", true); //$NON-NLS-1$
        boolean keepOpen = JsonUtils.extractBooleanArgument(params, "keepOpen", false); //$NON-NLS-1$
        int stepDelay = Math.max(0, JsonUtils.extractIntArgument(params, "stepDelaySeconds", 0)); //$NON-NLS-1$
        int timeoutSec = JsonUtils.extractIntArgument(params, "timeoutSeconds", DEFAULT_TIMEOUT_SEC); //$NON-NLS-1$
        Integer namedPort = JsonUtils.extractIntegerArgument(params, "testClientPort"); //$NON-NLS-1$
        if (namedPort == null && params != null && params.containsKey("testClientPort")) //$NON-NLS-1$
        {
            return ToolResult.error("testClientPort is not a whole number a port can be: " //$NON-NLS-1$
                + "a port is 1 to " + HIGHEST_PORT + ". Read as a number it cannot be, the " //$NON-NLS-1$ //$NON-NLS-2$
                + "run would have started on the default port instead of the one named.").toJson(); //$NON-NLS-1$
        }
        final boolean settledWantsTestClient =
            JsonUtils.extractBooleanArgument(params, "testClient", false); //$NON-NLS-1$
        final int settledClientPort = namedPort != null ? namedPort.intValue() : TEST_CLIENT_PORT;
        String portRefusal = whyThePortCannotBeUsed(settledClientPort);
        if (portRefusal != null)
        {
            return ToolResult.error(portRefusal).toJson();
        }
        if (timeoutSec <= 0)
        {
            timeoutSec = DEFAULT_TIMEOUT_SEC;
        }
        else if (timeoutSec > MAX_TIMEOUT_SEC)
        {
            timeoutSec = MAX_TIMEOUT_SEC;
        }

        // Everything the run needs is settled by now, so it can be handed to a job as it is.
        // Settled copies, because a job closes over what it is given and the timeout above is
        // clamped after it is read.
        final int settledTimeout = timeoutSec;
        final File settledExe = exeFile;
        final File settledEpf = epfFile;
        final File settledFeature = featurePath;
        // One key per run, not one per set of arguments. Coalescing belongs to reads whose
        // result can be handed to a second caller; a run drives a client against an infobase, so
        // two identical calls are two runs - and the second is refused below while the first goes.
        // Sharing a key let a second call take over the first caller's, and made the key stale
        // whenever a setting the run reads - the configured processor, for one - changed under it.
        String jobKey = PendingWorkRegistry.computeRunKey(NAME,
            java.util.UUID.randomUUID().toString());
        boolean async = JsonUtils.extractBooleanArgument(params, "async", false); //$NON-NLS-1$
        File runDirForJob = workingDir;
        if (!async)
        {
            // No key: the caller is holding the connection and is never handed one, and an async
            // run with these same arguments owns this key. Registering both under it would let a
            // cancel meant for that run destroy this client instead.
            return play(settledExe, settledEpf, settledConnection, settledFeature, composedScenario,
                screenshots, keepOpen, stepDelay, settledTimeout, settledClientPort,
                extraVaParams, runDirForJob, null, settledInfobase, settledAddress,
                settledWantsTestClient);
        }
        PendingWorkRegistry registry = PendingWorkRegistry.VANESSA;
        registry.pruneExpired();
        PendingWorkRegistry.PendingEntry entry;
        synchronized (ADMISSION)
        {
            // Read and submit as one decision. Apart, two callers arriving together both find the
            // domain idle, and the second is queued behind a run that may take an hour instead of
            // being refused.
            java.util.List<String> going = registry.unfinishedKeys();
            if (!going.isEmpty())
            {
                return ToolResult.error(alreadyGoing(going)).toJson();
            }
            entry = registry.getOrStart(jobKey,
                () -> play(settledExe, settledEpf, settledConnection, settledFeature,
                    composedScenario, screenshots, keepOpen, stepDelay, settledTimeout,
                    settledClientPort, extraVaParams, runDirForJob, jobKey, settledInfobase,
                    settledAddress, settledWantsTestClient));
        }
        String done = entry.await(ASYNC_FIRST_WAIT_MS);
        if (done != null)
        {
            registry.remove(jobKey, entry);
            return done;
        }
        return ToolResult.success()
            .put("operation", NAME) //$NON-NLS-1$
            .put("status", "Pending") //$NON-NLS-1$ //$NON-NLS-2$
            .put(ru.aiedt.mcp.server.support.PendingEnvelope.MARK, true)
            .put("runKey", jobKey) //$NON-NLS-1$
            .put("elapsedMs", entry.elapsedMs()) //$NON-NLS-1$
            .put("hint", "The scenarios are still playing. Come back with runKey=\"" //$NON-NLS-1$ //$NON-NLS-2$
                + jobKey + "\", or stop it with that key and cancel=true. Poll by the key: " //$NON-NLS-1$
                + "repeating the arguments does not find this run, and is refused while it " //$NON-NLS-1$
                + "goes.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Plays the scenarios and reads what they left behind.
     *
     * @param exeFile the client.
     * @param epfFile the Vanessa data processor it runs.
     * @param connectionString the infobase.
     * @param featurePath the scenarios, when they are a file that already exists.
     * @param composedScenario the scenarios as text, written into this run's own directory;
     *            exactly one of the two is given.
     * @param screenshots whether to capture one when a step fails.
     * @param keepOpen whether to leave the client running afterwards.
     * @param stepDelay the pause between steps, in seconds.
     * @param timeoutSec how long to wait for the run.
     * @param clientPort the port the test client listens on.
     * @param extraVaParams what the caller added to the Vanessa document.
     * @param workingDir where to run.
     * @param jobKey the key this run is cancelled by.
     * @param infobaseName the infobase this resolved from the project, or <code>null</code>
     *            when the caller named the connection string itself.
     * @param infobaseAddress the infobase EDT holds, as it was resolved once, or
     *            <code>null</code> when the caller named the connection string itself.
     * @param withTestClient whether to name a test client for the start step to launch.
     * @return the answer
     */
    private String play(File exeFile, File epfFile, String connectionString, File featurePath,
        String composedScenario, boolean screenshots, boolean keepOpen, int stepDelay,
        int timeoutSec, int clientPort, JsonObject extraVaParams, File workingDir, String jobKey,
        String infobaseName, InfobaseAddress.Address infobaseAddress, boolean withTestClient)
    {
        String refused = refusedBeforeLaunch(jobKey);
        if (refused != null)
        {
            return refused;
        }
        boolean mine;
        try
        {
            mine = THE_CLIENT.tryAcquire(WAIT_FOR_THE_CLIENT_SEC, TimeUnit.SECONDS);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            return ToolResult.error("Interrupted while waiting for the running scenario run to " //$NON-NLS-1$
                + "finish.").toJson(); //$NON-NLS-1$
        }
        if (!mine)
        {
            return ToolResult.error(
                alreadyGoing(PendingWorkRegistry.VANESSA.unfinishedKeys())).toJson();
        }
        Path outDir = null;
        // Deleted when the run is over, whatever ended it. The directory itself is kept - the
        // caller reads the report and the screenshots out of it - and a scenario typing a
        // password would otherwise sit there beside them for as long as the directory does.
        File composedFile = null;
        String leftBehind = null;
        // Once the client is launched it may be reading the scenario, and an interrupt throws
        // out of the wait without stopping it. Raised by the launch itself, at the one point a
        // process exists: a missing executable and a denied start both throw on the way there,
        // and neither leaves anything holding the file.
        AtomicBoolean clientLaunched = new AtomicBoolean(false);
        try
        {
            outDir = Files.createTempDirectory("ai-edt-vanessa"); //$NON-NLS-1$
            File shotsDir = new File(outDir.toFile(), "screenshots"); //$NON-NLS-1$
            shotsDir.mkdirs();
            File junitFile = new File(outDir.toFile(), "junit.xml"); //$NON-NLS-1$
            File paramsFile = new File(outDir.toFile(), "VAParams.json"); //$NON-NLS-1$
            File playing = scenarioFileFor(outDir.toFile(), featurePath);
            if (playing != featurePath)
            {
                // Recorded before the write, not after: a write that throws halfway leaves a
                // partial scenario, and nothing would be tracking it to remove.
                composedFile = playing;
                writeUtf8Bom(playing, composedScenario);
            }

            String vaParamsJson = buildVaParams(playing, junitFile, shotsDir, screenshots,
                keepOpen, stepDelay, connectionString, clientPort, timeoutSec, withTestClient,
                extraVaParams);
            writeUtf8Bom(paramsFile, vaParamsJson);

            File runDir = workingDir != null ? workingDir : outDir.toFile();
            List<String> command = buildCommand(exeFile, connectionString, epfFile, paramsFile);
            // The connectionString may carry Pwd="..." - never log it in the clear.
            Activator.logInfo("vanessa: launching " + redactSecrets(String.join(" ", command)) //$NON-NLS-1$ //$NON-NLS-2$
                + "\nVAParams.json keys: " + keysOf(vaParamsJson)); //$NON-NLS-1$

            ProcessResult pr;
            String heldBack = null;
            // A file infobase admits one owner. While EDT holds it, the client starts and never
            // connects: the process lives out its whole deadline without one event reaching the
            // infobase log, and the answer reads as a run that timed out rather than one that
            // never began. The claim keeps the other thick-client operations out of an infobase
            // that is standing released.
            String subject = infobaseAddress == null || infobaseAddress.infobase() == null
                ? null : InfobaseIdentity.of(infobaseAddress.infobase());
            try (MonopolyLock.Claim claim = subject == null ? null
                : MonopolyLock.claim(subject, "vanessa")) //$NON-NLS-1$
            {
                if (claim != null && !claim.granted())
                {
                    return ToolResult.error(claim.refusal())
                        .put("failureKind", ErrorTags.BUSY.wire()).toJson(); //$NON-NLS-1$
                }
                try (InfobaseAddress.Hold hold = InfobaseAddress.release(infobaseAddress))
                {
                    heldBack = hold.why();
                    pr = runVanessa(command, runDir, timeoutSec, jobKey, clientLaunched);
                }
            }
            // Removed here rather than in the finally: every answer below is built before a
            // finally runs, and the paths that need this most are the ones that go wrong.
            leftBehind = removeComposed(composedFile);
            composedFile = null;
            if (pr.cancelledBeforeLaunch)
            {
                return ToolResult.success()
                    .put("operation", NAME) //$NON-NLS-1$
                    .put("status", "Cancelled") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("runKey", jobKey) //$NON-NLS-1$
                    .put("clientStopped", false) //$NON-NLS-1$
                    .put("message", "The run was cancelled while it was preparing. No client " //$NON-NLS-1$
                        + "was started and the infobase was not touched by it.") //$NON-NLS-1$
                    .toJson();
            }
            Activator.logInfo("vanessa: exit=" + pr.exitCode + " timedOut=" + pr.timedOut //$NON-NLS-1$ //$NON-NLS-2$
                + " output:\n" + redactSecrets(pr.output)); //$NON-NLS-1$

            // A kept-open (or slow) run may have written the report before the process was
            // killed on timeout - prefer a real report over a bare timeout error.
            File resultDir = junitFile.getAbsoluteFile().getParentFile();
            boolean vanessaReported = AllureResultReader.resultsIn(resultDir).length > 0;
            if (!vanessaReported && !junitFile.isFile())
            {
                if (pr.timedOut)
                {
                    return ToolResult.error("Vanessa run timed out after " + timeoutSec + "s" //$NON-NLS-1$ //$NON-NLS-2$
                        + (keepOpen ? " (keepOpen=true keeps 1C open, so it never exits - set keepOpen=false)." //$NON-NLS-1$
                            : ". Raise timeoutSeconds, or the run may be stuck on a 1C login/update dialog.") //$NON-NLS-1$
                        // Named before the deadline is blamed: a client that never got the
                        // infobase spends the whole deadline and looks exactly like a slow run.
                        + (heldBack == null ? "" : " " + heldBack + ".") //$NON-NLS-1$ //$NON-NLS-2$
                        + " " + tail(pr.output))
                        .put("composedScenarioLeftBehind", leftBehind) //$NON-NLS-1$
                        .put("infobaseNotReleased", heldBack).toJson(); //$NON-NLS-1$
                }
                String incomplete = whatTheDistributionIsMissing(epfFile);
                return ToolResult.error("Vanessa produced no JUnit report (exit " + pr.exitCode //$NON-NLS-1$
                    + "). The run may not have started (bad connectionString, an unadopted Vanessa " //$NON-NLS-1$
                    + "extension in the infobase, a login window, or Vanessa-version-specific launch " //$NON-NLS-1$
                    + "parameters - the launched command and the key names of VAParams.json " //$NON-NLS-1$
                    + "are in the EDT .log). " //$NON-NLS-1$
                    + (incomplete == null ? "" : incomplete + " ") //$NON-NLS-1$
                    + tail(pr.output))
                    .put("composedScenarioLeftBehind", leftBehind).toJson(); //$NON-NLS-1$
            }

            JUnitRunOutcome results = vanessaReported
                ? AllureResultReader.parse(resultDir) : JUnitXmlReader.parse(junitFile);
            List<String> shots = collectScreenshots(shotsDir);
            java.util.Map<String, String> pathByName = new java.util.LinkedHashMap<>();
            for (String path : shots)
            {
                pathByName.put(new File(path).getName(), path);
            }
            List<JUnitRunOutcome.TestCase> broken = new ArrayList<>(results.getFailureDetails());
            broken.addAll(results.getErrorDetails());
            FailureScreenshots attributed =
                FailureScreenshots.attribute(broken, new ArrayList<>(pathByName.keySet()));

            String summary = results.getTotal() + " scenario steps, " + results.getPassed() //$NON-NLS-1$
                + " passed, " + results.getFailures() + " failed, " + results.getErrors() //$NON-NLS-1$ //$NON-NLS-2$
                + " errored, " + results.getSkipped() + " skipped"; //$NON-NLS-1$ //$NON-NLS-2$
            if (results.getTotal() == 0)
            {
                summary += " - WARNING: 0 scenarios ran (check featurePath, that the Vanessa " //$NON-NLS-1$
                    + "extension is adopted in the infobase, and any tag filter)"; //$NON-NLS-1$
            }

            ToolResult ok = ToolResult.success()
                .put("operation", NAME) //$NON-NLS-1$
                // Named when this worked it out from the project rather than being told: a run
                // against the wrong infobase looks exactly like a run against the right one.
                .put("infobase", infobaseName) //$NON-NLS-1$
                .put("passed", results.isPassed()) //$NON-NLS-1$
                .put("summary", summary) //$NON-NLS-1$
                .put("total", results.getTotal()) //$NON-NLS-1$
                .put("failures", results.getFailures()) //$NON-NLS-1$
                .put("errors", results.getErrors()) //$NON-NLS-1$
                .put("skipped", results.getSkipped()) //$NON-NLS-1$
                .put("junitXmlPath", junitFile.getAbsolutePath()) //$NON-NLS-1$
                .put("screenshots", shots) //$NON-NLS-1$
                .put("composedScenarioLeftBehind", leftBehind) //$NON-NLS-1$
                .put("screenshotsByStep", attributed.byStep()) //$NON-NLS-1$
                .put("screenshotsNotAttributed", attributed.unattributed()) //$NON-NLS-1$
                .put("markdown", JUnitReportFormatter.format(results) //$NON-NLS-1$
                    + attributed.toMarkdown(pathByName));
            return ok.toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error in vanessa", e); //$NON-NLS-1$
            String stillHere = scenarioAfterFailure(clientLaunched.get(), leftBehind, composedFile);
            composedFile = null;
            return ToolResult.error("Error running Vanessa: " + TextSuggest.safeMessage(e)) //$NON-NLS-1$
                .put("composedScenarioLeftBehind", stillHere).toJson(); //$NON-NLS-1$
        }
        finally
        {
            String stillThere = removeComposed(composedFile);
            if (stillThere != null)
            {
                Activator.logWarning("vanessa: the composed scenario could not be removed: " //$NON-NLS-1$
                    + stillThere);
            }
            THE_CLIENT.release();
            if (jobKey != null)
            {
                // The run is over however it ended. A cancel arriving now has nothing to stop and
                // must say so, rather than read a mark left behind by this one.
                CANCELLED.remove(jobKey);
            }
        }
        // The output dir (junit.xml + screenshots) is intentionally NOT deleted: the
        // agent reads the returned screenshot paths. It is a temp dir the OS reclaims.
    }

    /**
     * Why neither way of naming the scenario will do, when neither will.
     * <p>
     * Both given is refused rather than resolved by precedence: nothing in the call says which was
     * meant, and running the wrong one drives a client against a live infobase.
     * </p>
     *
     * @param hasPath whether a feature path was given.
     * @param hasText whether the scenario arrived as text.
     * @return the refusal, or {@code null} when exactly one of them was given
     */
    static String whyTheScenarioIsNotNamed(boolean hasPath, boolean hasText)
    {
        return whyTheScenarioIsNotNamed(hasPath, hasText, false);
    }

    /**
     * Why this call does not say what to play, or <code>null</code> when it does.
     *
     * @param hasPath whether a file or directory was named.
     * @param hasText whether the scenario itself was given.
     * @param hasForm whether a form to open was named, from which a scenario is composed.
     * @return the refusal, or <code>null</code>
     */
    static String whyTheScenarioIsNotNamed(boolean hasPath, boolean hasText, boolean hasForm)
    {
        int named = (hasPath ? 1 : 0) + (hasText ? 1 : 0) + (hasForm ? 1 : 0);
        if (named > 1)
        {
            return "featurePath, scenarioText and formToOpen each name what to play, and only " //$NON-NLS-1$
                + "one of them can be it. Pass the path to a file that exists, the scenario text " //$NON-NLS-1$
                + "to be written for this run, or the form to open and snapshot."; //$NON-NLS-1$
        }
        if (named == 0)
        {
            return "featurePath is required (a .feature file or a directory), or scenarioText " //$NON-NLS-1$
                + "with the scenario itself, or formToOpen with the form to open and snapshot."; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Why a form cannot be put into a scenario, or <code>null</code> when it can.
     * <p>
     * The name goes into a Gherkin step, and a step is one line with the name usually in quotes. A
     * name carrying a quote or a line break composes a scenario Vanessa reads as something else -
     * and the run then fails somewhere far from the cause, or worse, succeeds having done the
     * wrong thing.
     * </p>
     *
     * @param form the form the caller named.
     * @param openStep the wording, or <code>null</code> for the default.
     * @return the refusal, or <code>null</code>
     */
    static String whyTheFormCannotBeNamed(String form, String openStep)
    {
        return whyTheFormCannotBeNamed(form, openStep, null);
    }

    /**
     * Why a form cannot be put into a scenario, or <code>null</code> when it can.
     *
     * @param form the form the caller named.
     * @param openStep the wording that opens it, or <code>null</code> for the default.
     * @param startStep the wording that gets a client, or <code>null</code> for the default.
     * @return the refusal, or <code>null</code>
     */
    static String whyTheFormCannotBeNamed(String form, String openStep, String startStep)
    {
        if (startStep != null && (startStep.indexOf('\n') >= 0 || startStep.indexOf('\r') >= 0))
        {
            return "startStep is one step and therefore one line."; //$NON-NLS-1$
        }
        if (form == null || form.trim().isEmpty())
        {
            return "formToOpen is empty. Name the form the opening step expects."; //$NON-NLS-1$
        }
        if (form.indexOf('"') >= 0 || form.indexOf('\n') >= 0 || form.indexOf('\r') >= 0)
        {
            return "formToOpen carries a quote or a line break, and the name goes into one line " //$NON-NLS-1$
                + "of a scenario. Pass the name alone, or write the whole scenario in " //$NON-NLS-1$
                + "scenarioText."; //$NON-NLS-1$
        }
        String wording = openStep == null || openStep.trim().isEmpty() ? OPEN_STEP : openStep;
        if (!wording.contains("{form}")) //$NON-NLS-1$
        {
            return "openStep does not say where the form name goes. Put {form} in it, as in " //$NON-NLS-1$
                + OPEN_STEP + "."; //$NON-NLS-1$
        }
        if (wording.indexOf('\n') >= 0 || wording.indexOf('\r') >= 0)
        {
            return "openStep is one step and therefore one line."; //$NON-NLS-1$
        }
        return null;
    }

    /** Folders the multi-file distribution loads from beside its own file. */
    private static final String[] COMPANIONS = { "locales", "lib" }; //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * What a Vanessa distribution is missing beside its own file, or <code>null</code>.
     * <p>
     * Vanessa ships two ways. The single-file build carries everything; the ordinary one loads
     * companion processors from {@code locales} and {@code lib} next to itself and, when they are
     * not there, opens a modal naming a file nobody asked about and produces no report. The run
     * then spends its whole time budget waiting on a window, and the answer is a list of guesses -
     * none of which is the true one.
     * </p>
     * <p>
     * Only said when it is certainly true: a folder that is absent or empty beside a file whose
     * name does not mark it as the single-file build.
     * </p>
     *
     * @param epf the configured data processor.
     * @return the sentence to add to a failure, or <code>null</code> when nothing is missing
     */
    static String whatTheDistributionIsMissing(File epf)
    {
        if (epf == null || epf.getName().toLowerCase().contains("-single")) //$NON-NLS-1$
        {
            return null;
        }
        File beside = epf.getParentFile();
        if (beside == null)
        {
            return null;
        }
        List<String> missing = new ArrayList<>();
        for (String companion : COMPANIONS)
        {
            File folder = new File(beside, companion);
            String[] inside = folder.list();
            if (!folder.isDirectory() || inside == null || inside.length == 0)
            {
                missing.add(companion);
            }
        }
        if (missing.isEmpty())
        {
            return null;
        }
        return "This distribution is incomplete: " + String.join(" and ", missing) //$NON-NLS-1$ //$NON-NLS-2$
            + " beside " + epf.getName() + (missing.size() == 1 ? " is" : " are") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " missing or empty, and Vanessa loads companion processors from there before it " //$NON-NLS-1$
            + "runs anything. Point the preference at the single-file build instead."; //$NON-NLS-1$
    }

    /** How the scenario opens a form when the caller does not say otherwise. */
    static final String OPEN_STEP = "Я открываю общую форму \"{form}\""; //$NON-NLS-1$

    /** How the scenario gets a client to work in when the caller does not say otherwise. */
    static final String START_STEP =
        "Я запускаю сценарий открытия TestClient или подключаю уже существующий"; //$NON-NLS-1$

    /**
     * A scenario that opens one form and has it photographed.
     * <p>
     * The snapshot is not a step. Vanessa takes one before and after the step that follows the
     * {@code @screenshot} tag, writing them where {@code КаталогСохраненияСкриншотов} points - which
     * this tool already sets for every run, and which the report reader already groups by step.
     * </p>
     * <p>
     * Both step wordings are arguments with a default rather than text built into this file. They
     * belong to Vanessa and differ between its versions and between kinds of form: a list form and
     * an object form are opened by different words, and one wording nailed down here would fit one
     * of them. The same reasoning gave {@code vanessaParams} its open shape.
     * </p>
     *
     * @param form what to put where the wording says {@code {form}}.
     * @param startStep how to get a client, or <code>null</code> for {@link #START_STEP}.
     * @param openStep how to open the form, or <code>null</code> for {@link #OPEN_STEP}.
     * @return the scenario text
     */
    static String scenarioForForm(String form, String startStep, String openStep)
    {
        String opening = openStep == null || openStep.trim().isEmpty() ? OPEN_STEP : openStep;
        String starting = startStep == null || startStep.trim().isEmpty() ? START_STEP : startStep;
        return "#language: ru\n\n" //$NON-NLS-1$
            + "Функционал: Снимок формы\n\n" //$NON-NLS-1$
            + "Контекст:\n" //$NON-NLS-1$
            + "    Дано " + starting + "\n\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "Сценарий: Снимок формы " + form + "\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "    @screenshot\n" //$NON-NLS-1$
            + "    Когда " + opening.replace("{form}", form) + "\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "    И Я закрываю все окна клиентского приложения\n"; //$NON-NLS-1$
    }

    /**
     * The connection string with the user named in it.
     * <p>
     * In the string rather than as {@code /N}: the test client the start step launches is given
     * its own {@code PathToInfobase}, and a string carries the user into both clients where the
     * argument would reach only one.
     * </p>
     *
     * @param connectionString the infobase.
     * @param user the 1C user, or <code>null</code> when the caller named none.
     * @return the string, unchanged when there is no user to name
     */
    static String namingTheUser(String connectionString, String user)
    {
        if (connectionString == null || user == null || user.trim().isEmpty())
        {
            return connectionString;
        }
        String said = connectionString.trim();
        if (!said.endsWith(";")) //$NON-NLS-1$
        {
            said = said + ";"; //$NON-NLS-1$
        }
        return said + "Usr=\"" + user.trim() + "\";"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Where this run's scenario lives.
     * <p>
     * The file the caller named, or - when the scenario arrived as text - the place in the run's
     * own directory it will be written to, beside the report and the screenshots. Naming the place
     * without writing it is deliberate: the caller records the path first, so a write that fails
     * halfway still leaves something to remove.
     * </p>
     *
     * @param outDir the run's own directory.
     * @param featurePath the file the caller named, or {@code null} when the scenario is text.
     * @return the file to play; the same object when the caller named one
     */
    static File scenarioFileFor(File outDir, File featurePath)
    {
        return featurePath != null ? featurePath : new File(outDir, "composed.feature"); //$NON-NLS-1$
    }

    /**
     * Removes a scenario this run composed.
     *
     * @param composed the file, or {@code null} when the caller named their own.
     * @return the path when the file is still there afterwards, or {@code null} when it is gone
     */
    static String removeComposed(File composed)
    {
        if (composed == null || !composed.exists())
        {
            return null;
        }
        if (composed.delete() || !composed.exists())
        {
            return null;
        }
        // Held by the client that has not fully exited, or by a scanner. Said out loud rather than
        // swallowed: the directory is kept for the report, so a scenario that typed a password
        // stays beside it until somebody removes it.
        return composed.getAbsolutePath();
    }

    /** Resolves a relative featurePath against the project dir; leaves absolute paths as-is. */
    static File resolveFeaturePath(String featurePathArg, File workingDir)
    {
        File f = new File(featurePathArg);
        if (!f.isAbsolute() && workingDir != null)
        {
            return new File(workingDir, featurePathArg);
        }
        return f;
    }

    /**
     * The Vanessa {@code VAParams.json} (Russian keys - Vanessa-version sensitive). Points
     * Vanessa at the feature path, names the test client the start step launches, asks for a
     * JUnit report and (optional) failure screenshots, and closes the client when done so the
     * poller detects exit. Only the key names of the result are logged: a value may carry a
     * secret.
     *
     * @param featurePath the scenarios, a file or a directory of them.
     * @param junitFile where Vanessa writes the report this run is read from.
     * @param shotsDir where Vanessa writes failure screenshots.
     * @param screenshots whether to capture one when a step fails.
     * @param keepOpen whether to leave the client running afterwards.
     * @param stepDelay the pause between steps, in seconds; zero leaves the key out.
     * @param connectionString the infobase the test client opens.
     * @param clientPort the port the test client listens on.
     * @param clientTimeoutSec the whole budget of the run; the client share of it is taken by
     *            {@link #clientWaitWithin(int)}.
     * @param withTestClient whether to name a test client for the start step to launch.
     * @param extra what the caller added to the Vanessa document, merged last.
     * @return the document, ready to be written
     */
    static String buildVaParams(File featurePath, File junitFile, File shotsDir,
        boolean screenshots, boolean keepOpen, int stepDelay, String connectionString,
        int clientPort, int clientTimeoutSec, boolean withTestClient, JsonObject extra)
    {
        JsonObject o = new JsonObject();
        // Measured on this stand: with the block present Vanessa writes no report at all, for
        // any scenario, including one whose only step is a three second wait. Without it the
        // same scenarios play and a failing step is reported by name. It is therefore off
        // unless the caller asks for it.
        // The step that starts TestClient has no client to start without this block: the run
        // answers "Тип не определен (ТестируемаяГруппаФормы)" with an empty client type and PID 0,
        // because the UI-testing types exist only once a client runs under the test manager.
        if (withTestClient)
        {
            o.add("TestClient", //$NON-NLS-1$
                testClient(connectionString, clientPort, clientWaitWithin(clientTimeoutSec)));
        }
        // Without this Vanessa opens its own window and waits there. Every run then spends its
        // whole time budget on a form nobody is looking at, ends killed, and writes no report -
        // which reads exactly like a scenario that never started.
        o.addProperty("ВыполнитьСценарии", true); //$NON-NLS-1$
        // A directory of features, or the parent of a single .feature file.
        // getAbsoluteFile() first so getParentFile() is non-null even for a bare filename.
        File dir = featurePath.isDirectory() ? featurePath : featurePath.getAbsoluteFile().getParentFile();
        o.addProperty("КаталогФич", dir != null ? dir.getAbsolutePath() : featurePath.getAbsolutePath()); //$NON-NLS-1$
        if (featurePath.isFile())
        {
            o.addProperty("ФайлСценария", featurePath.getAbsolutePath()); //$NON-NLS-1$
        }
        // Vanessa has no JUnit parameters of its own: its documented keys for a machine
        // readable result are the Allure pair, and the xml it writes there is what a JUnit
        // reader consumes. Asked under the names below, it writes nothing and reports nothing,
        // which reads as a run that produced no result.
        o.addProperty("ДелатьОтчетВФорматеАллюр", true); //$NON-NLS-1$
        o.addProperty("КаталогOutputAllureБазовый", //$NON-NLS-1$
            junitFile.getAbsoluteFile().getParentFile().getAbsolutePath());
        o.addProperty("ДелатьСкриншотПриОшибке", screenshots); //$NON-NLS-1$
        o.addProperty("КаталогСохраненияСкриншотов", shotsDir.getAbsolutePath()); //$NON-NLS-1$
        o.addProperty("ЗакрыватьTestClientПослеПрогона", !keepOpen); //$NON-NLS-1$
        o.addProperty("ВыходИзПриложенияПослеЗапускаСценариев", !keepOpen); //$NON-NLS-1$
        if (stepDelay > 0)
        {
            o.addProperty("ПаузаМеждуШагами", stepDelay); //$NON-NLS-1$
        }
        if (extra != null)
        {
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : extra.entrySet())
            {
                o.add(e.getKey(), e.getValue());
            }
        }
        return prettyJson(o);
    }

    /** The port the test client listens on when the caller names none. */
    static final int TEST_CLIENT_PORT = 48010;

    /**
     * The longest the run waits for the test client to answer. A client that has not come up
     * within this is not coming, and without a ceiling a long suite would spend its whole
     * budget waiting for one that never will.
     */
    static final int TEST_CLIENT_WAIT_CEILING_SEC = 600;

    /**
     * Why the test client cannot be told to listen on the given port.
     * <p>
     * Vanessa writes the number down as it is given, and a client told to listen on a number
     * that is not a port simply does not listen - the start step then fails for a reason that
     * names neither the port nor this tool.
     * </p>
     *
     * @param port the port the caller named.
     * @return the refusal, or <code>null</code> when the port can be used
     */
    static String whyThePortCannotBeUsed(int port)
    {
        if (port >= 1 && port <= HIGHEST_PORT)
        {
            return null;
        }
        return "testClientPort is " + port + ", which is not a port: a port is 1 to " //$NON-NLS-1$ //$NON-NLS-2$
            + HIGHEST_PORT + ". Vanessa writes the number down as given, and a client told to " //$NON-NLS-1$
            + "listen on it does not listen."; //$NON-NLS-1$
    }

    /** The highest port a client can be told to listen on. */
    static final int HIGHEST_PORT = 65535;

    /** The most that is held back for Vanessa to write its report and exit. */
    private static final int REPORT_RESERVE_CEILING_SEC = 60;

    /** The share of a small budget held back, when a whole minute would be most of it. */
    private static final int RESERVE_SHARE = 3;

    /**
     * Seconds Vanessa waits for the test client, out of the run's own budget.
     * <p>
     * Kept under the budget so Vanessa reaches its own timeout, writes the report and exits
     * before this tool kills the process, and under a ceiling so a long suite does not spend
     * all of its time on a client that is not coming. What is held back is a third of the
     * budget or a minute, whichever is smaller, so a short run keeps a reserve too.
     * </p>
     *
     * @param budgetSec the whole budget of the run.
     * @return the seconds to wait for the client
     */
    static int clientWaitWithin(int budgetSec)
    {
        // A whole minute is most of a small budget, so what is held back is a share of it
        // until the budget is large enough for the minute to be the smaller of the two.
        int reserve = Math.min(REPORT_RESERVE_CEILING_SEC,
            Math.max(1, budgetSec / RESERVE_SHARE));
        return Math.max(1, Math.min(budgetSec - reserve, TEST_CLIENT_WAIT_CEILING_SEC));
    }

    /**
     * The {@code TestClient} block of VAParams: which infobase the client opens, on which port and
     * as which client type.
     *
     * @param connectionString the infobase the test client opens.
     * @param clientPort the port the client listens on.
     * @param clientTimeoutSec seconds Vanessa waits for the client to answer. Taken from the
     *            run's own budget up to {@link #TEST_CLIENT_WAIT_CEILING_SEC}.
     * @return the block, holding one client
     */
    static JsonObject testClient(String connectionString, int clientPort, int clientTimeoutSec)
    {
        JsonObject client = new JsonObject();
        client.addProperty("Name", "AiEdt"); //$NON-NLS-1$ //$NON-NLS-2$
        client.addProperty("PathToInfobase", connectionString); //$NON-NLS-1$
        client.addProperty("PortTestClient", clientPort); //$NON-NLS-1$
        // Vanessa spells this key with that capital I. Correcting it leaves the key unread.
        client.addProperty("AddItionalParameters", ""); //$NON-NLS-1$ //$NON-NLS-2$
        client.addProperty("ClientType", "Thin"); //$NON-NLS-1$ //$NON-NLS-2$
        client.addProperty("ComputerName", "localhost"); //$NON-NLS-1$ //$NON-NLS-2$
        com.google.gson.JsonArray clients = new com.google.gson.JsonArray();
        clients.add(client);
        JsonObject block = new JsonObject();
        block.addProperty("runtestclientwithmaximizedwindow", true); //$NON-NLS-1$
        block.addProperty("testclienttimeout", clientTimeoutSec); //$NON-NLS-1$
        block.add("datatestclients", clients); //$NON-NLS-1$
        return block;
    }

    /**
     * {@code 1cv8 ENTERPRISE /IBConnectionString "<conn>" /DisableStartupMessages
     * /Execute <epf> /C "StartFeaturePlayer;VAParams=<params>"} (thick client;
     * {@code /DisableStartupMessages} avoids the "update configuration?" modal).
     */
    /**
     * The parameters this tool sets because it reads the result back from where they point.
     * <p>
     * Moving the report or the screenshot directory would leave the run reading an empty file and
     * reporting that nothing failed; leaving the client open would leave the run waiting for an
     * exit that never comes. A caller asking for one of these is told so rather than obeyed.
     * </p>
     */
    private static final java.util.Set<String> OURS_TO_SET = lowerCased(
        "СохранятьРезультатыВФорматеJUnit", "ПутьКФайлуРезультатовJUnit", //$NON-NLS-1$ //$NON-NLS-2$
        "КаталогСохраненияСкриншотов", "ЗакрыватьTestClientПослеПрогона", //$NON-NLS-1$ //$NON-NLS-2$
        "ВыходИзПриложенияПослеЗапускаСценариев", //$NON-NLS-1$
        // Turned off, Vanessa opens its window and waits there: the run spends its whole budget on
        // a form nobody is looking at and writes no report.
        "ВыполнитьСценарии", //$NON-NLS-1$
        // The passthrough takes values and lists of them, so it cannot carry the object this block
        // needs; what it can carry is a value that replaces the block with something the start step
        // cannot use. Its port and its deadline are arguments of this tool instead.
        "TestClient", //$NON-NLS-1$
        // These come from arguments of this tool. Letting the passthrough set them too would mean
        // the later one silently wins, and the caller who passed screenshots=true would be told it
        // ran with screenshots while it did not.
        "КаталогФич", "ФайлСценария", "ДелатьСкриншотПриОшибке", "ПаузаМеждуШагами"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /**
     * Field names a connection string carries a password under that no rule would catch.
     * <p>
     * The platform names most of them after the word - Pwd, DBPwd, SPwd - and one after neither:
     * WSP, the web-server password. Four were found one at a time, each after the previous list
     * looked complete, which is why the rule below asks what a name READS like and this set only
     * holds what the rule cannot see.
     * </p>
     */
    private static final java.util.Set<String> SECRET_FIELDS =
        lowerCased("WSP", "/P"); //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * The given names, lower-cased, as an unmodifiable set.
     *
     * @param names the names.
     * @return them, ready to be compared against a lower-cased name
     */
    private static java.util.Set<String> lowerCased(String... names)
    {
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        for (String name : names)
        {
            set.add(name.toLowerCase(java.util.Locale.ROOT));
        }
        return java.util.Collections.unmodifiableSet(set);
    }

    /**
     * Splits a connection string into its fields, leaving a semicolon inside quotes alone.
     * <p>
     * A path may carry one - {@code File="C:\\Bases\\archive;old"} - and treating it as a
     * separator turned an innocent call into a refusal.
     * </p>
     *
     * @param connectionString the string.
     * @return its fields
     */
    static java.util.List<String> fieldsOf(String connectionString)
    {
        java.util.List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < connectionString.length(); i++)
        {
            char c = connectionString.charAt(i);
            if (c == '"')
            {
                quoted = !quoted;
                continue;
            }
            if (c == ';' && !quoted)
            {
                fields.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        fields.add(current.toString());
        return fields;
    }

    /**
     * Reads the parameters a caller wants added to the Vanessa document.
     * <p>
     * Vanessa's parameter names are Russian and differ between its versions, and it ignores a name
     * it does not know. So the names are the caller's to give: naming them here would mean writing
     * a key nobody could check, and a run that quietly ignored it would still report success.
     * </p>
     *
     * @param raw the JSON object the caller passed, or <code>null</code>.
     * @param refusal filled with why the call cannot proceed, when it cannot.
     * @return the parameters to add, or <code>null</code> when there are none or one is refused
     */
    static JsonObject extraParams(String raw, String[] refusal)
    {
        if (raw == null || raw.trim().isEmpty())
        {
            return null;
        }
        com.google.gson.JsonElement parsed;
        try
        {
            parsed = com.google.gson.JsonParser.parseString(raw.trim());
        }
        catch (RuntimeException notJson)
        {
            refusal[0] = "vanessaParams is not JSON: " //$NON-NLS-1$
                + (notJson.getMessage() != null ? notJson.getMessage()
                    : notJson.getClass().getSimpleName());
            return null;
        }
        if (!parsed.isJsonObject())
        {
            refusal[0] = "vanessaParams takes an object of Vanessa parameter names to values, " //$NON-NLS-1$
                + "for example {\"ТегиСценариев\":\"smoke\"}"; //$NON-NLS-1$
            return null;
        }
        JsonObject given = parsed.getAsJsonObject();
        for (String key : given.keySet())
        {
            // Without a locale, lower-casing turns I into a dotless letter where the machine is
            // set to Turkish, and a protected name stops matching.
            if (OURS_TO_SET.contains(key.toLowerCase(java.util.Locale.ROOT)))
            {
                refusal[0] = "'" + key + "' is set by this tool, from its own " //$NON-NLS-1$ //$NON-NLS-2$
                    + "arguments. Passing it here as well would leave the answer describing a " //$NON-NLS-1$
                    + "run that did not happen the way it says."; //$NON-NLS-1$
                return null;
            }
            if (!isAValueOrAListOfThem(given.get(key)))
            {
                // Vanessa reads its parameters as values, so anything else arrives as a shape it
                // cannot use - and it ignores what it cannot use, leaving the run unfiltered and
                // reported as a success. A list was checked for being a list without its items
                // being looked at, which let a list of objects through.
                refusal[0] = "'" + key + "' is given something other than a value or a list of " //$NON-NLS-1$ //$NON-NLS-2$
                    + "them; that is what a Vanessa parameter takes."; //$NON-NLS-1$
                return null;
            }
        }
        return given;
    }

    /**
     * Whether this is something Vanessa can read as a parameter.
     *
     * @param value what the caller gave for one parameter.
     * @return true when it is a value, or a list of values
     */
    private static boolean isAValueOrAListOfThem(com.google.gson.JsonElement value)
    {
        if (value == null || value.isJsonNull() || value.isJsonPrimitive())
        {
            return true;
        }
        if (!value.isJsonArray())
        {
            return false;
        }
        for (com.google.gson.JsonElement item : value.getAsJsonArray())
        {
            if (item != null && !item.isJsonNull() && !item.isJsonPrimitive())
            {
                return false;
            }
        }
        return true;
    }

    /**
     * The names in a JSON document, without their values.
     * <p>
     * The document is worth logging - which parameters a run went out with is the first thing
     * anyone asks after a failure - and its values are not: a caller may put a password into a
     * parameter of their own, and this file is written where the log can reach it.
     * </p>
     *
     * @param json the document.
     * @return its keys, comma separated
     */
    static String keysOf(String json)
    {
        try
        {
            com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(json);
            if (!parsed.isJsonObject())
            {
                return "(not an object)"; //$NON-NLS-1$
            }
            return String.join(", ", parsed.getAsJsonObject().keySet()); //$NON-NLS-1$
        }
        catch (RuntimeException notJson)
        {
            return "(unreadable)"; //$NON-NLS-1$
        }
    }

    private static List<String> buildCommand(File exe, String connectionString, File epf,
        File paramsFile)
    {
        List<String> c = new ArrayList<>();
        c.add(exe.getAbsolutePath());
        c.add("ENTERPRISE"); //$NON-NLS-1$
        c.add("/IBConnectionString"); //$NON-NLS-1$
        c.add(connectionString);
        c.add("/DisableStartupMessages"); //$NON-NLS-1$
        c.add("/Execute"); //$NON-NLS-1$
        c.add(epf.getAbsolutePath());
        c.add("/C"); //$NON-NLS-1$
        c.add("StartFeaturePlayer;VAParams=" + paramsFile.getAbsolutePath()); //$NON-NLS-1$
        return c;
    }

    private static final class ProcessResult
    {
        int exitCode = -1;
        boolean timedOut;
        boolean cancelledBeforeLaunch;
        String output = ""; //$NON-NLS-1$
    }

    /**
     * The client of each running scenario run, by the key its caller polls with.
     * <p>
     * Held only while the process lives. Cancelling a run means stopping the client, and nothing
     * else in this plugin can reach it once the call that started it has returned.
     * </p>
     */
    private static final java.util.Map<String, Process> RUNNING =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Keys whose run has been asked to stop.
     * <p>
     * A run that has begun but has not yet started its client is reachable by nothing else: the
     * process map is still empty for it, and completing its future reaches only work that has not
     * started - measured, and the reason a queued run needs no mark. Without this one, a cancel in
     * that window would answer that nothing was running and the client would launch afterwards.
     * </p>
     */
    private static final java.util.Map<String, Long> CANCELLED =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * How long a mark is kept when nothing reads it.
     * <p>
     * A run reads its mark and takes it. One cancelled before it began never reads anything, and
     * its key - unique to that run - is never used again, so the mark would be kept for the life
     * of the server. Nothing can still be waiting to read a mark older than the longest run: the
     * only run that could is one queued behind another, and it starts within one run's length.
     * </p>
     */
    private static final long MARK_LIFETIME_MS = (MAX_TIMEOUT_SEC + 60) * 1000L;

    /**
     * Drops marks no run can still be waiting to read.
     */
    private static void forgetOldMarks()
    {
        long now = System.currentTimeMillis();
        CANCELLED.entrySet().removeIf(mark -> now - mark.getValue() > MARK_LIFETIME_MS);
    }

    /**
     * The right to be the scenario client.
     * <p>
     * The domain's executor takes one run at a time, but hands overflow back to the submitting
     * thread rather than refusing it, so a full queue would put a second client on the same
     * infobase. Two clients playing scenarios there would be reading each other's work, so the
     * second one waits briefly and is refused rather than launched.
     * </p>
     * <p>
     * One permit for every run, not one per infobase: runs against different bases do not conflict
     * with each other, and taking them together would need a wider executor as well.
     * </p>
     */
    private static final java.util.concurrent.Semaphore THE_CLIENT =
        new java.util.concurrent.Semaphore(1);

    /** How long a second run waits for the first before it is refused. */
    private static final long WAIT_FOR_THE_CLIENT_SEC = 5;

    /**
     * Why this run was not started, naming what is holding the place.
     * <p>
     * The key is named because a run owns its own and its caller may never have received it - it
     * went away while waiting - leaving a run that holds the domain and that nobody could name.
     * Whoever reads this refusal can.
     * </p>
     *
     * @param going the keys of the runs already under way.
     * @return the refusal text
     */
    private static String alreadyGoing(java.util.List<String> going)
    {
        StringBuilder why = new StringBuilder("Another scenario run is in progress. Runs are "); //$NON-NLS-1$
        why.append("taken one at a time, whichever infobase they name, so this one was not "); //$NON-NLS-1$
        why.append("started. Wait for it to finish"); //$NON-NLS-1$
        if (going.isEmpty())
        {
            why.append(", or stop it with its runKey and cancel=true."); //$NON-NLS-1$
            return why.toString();
        }
        why.append(", or stop it with cancel=true and runKey="); //$NON-NLS-1$
        for (int i = 0; i < going.size(); i++)
        {
            why.append(i == 0 ? "" : ", ").append('"').append(going.get(i)).append('"'); //$NON-NLS-1$
        }
        why.append('.');
        return why.toString();
    }

    /** Guards reading that the domain is idle and submitting the run that makes it busy. */
    private static final Object ADMISSION = new Object();

    /**
     * Guards spawning the client and registering it, against a cancel looking for it.
     * <p>
     * Without it a cancel can write its mark, find nothing registered, and report that no client
     * was running while {@code ProcessBuilder.start} is in the middle of spawning one.
     * </p>
     */
    private static final Object LAUNCHING = new Object();

    static
    {
        PendingWorkRegistry.VANESSA.stopsWith(VanessaTool::stopTheClient);
    }

    /**
     * The answer a run gives when it was told to stop before it launched anything.
     * <p>
     * Reading the mark consumes it: a later run under the same key is a run of its own, and a mark
     * left behind by this one would refuse it for no reason.
     * </p>
     *
     * @param jobKey the run's key; a run nobody can cancel has none.
     * @return the refusal, or {@code null} when this run may go ahead
     */
    // A mark set for a run that never began is never read here, and outlives it. That costs the
    // next call under the same key one refusal, which says so and clears the mark. Clearing it
    // early instead would trade that for a client launched after a cancel reported success, and
    // between a visible retry and an untracked client the retry is the one to keep.
    static String refusedBeforeLaunch(String jobKey)
    {
        if (jobKey == null || CANCELLED.remove(jobKey) == null)
        {
            return null;
        }
        return ToolResult.success()
            .put("operation", NAME) //$NON-NLS-1$
            .put("status", "Cancelled") //$NON-NLS-1$ //$NON-NLS-2$
            .put("runKey", jobKey) //$NON-NLS-1$
            .put("clientStopped", false) //$NON-NLS-1$
            .put("message", "The run was cancelled before the client started. Nothing was " //$NON-NLS-1$
                + "launched and the infobase was not touched by it. The cancellation is spent: " //$NON-NLS-1$
                + "call again to run these scenarios.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Stops a running scenario run.
     * <p>
     * The client is destroyed with its children first, the same order the timeout uses: the worker
     * processes 1C starts outlive their parent otherwise. Then the entry is dropped, so a later
     * poll on that key says the run is gone rather than waiting for a result nobody will produce.
     * </p>
     *
     * @param runKey the key from the Pending reply.
     * @return what happened, for the caller
     */
    static String cancelRun(String runKey)
    {
        PendingWorkRegistry registry = PendingWorkRegistry.VANESSA;
        PendingWorkRegistry.PendingEntry entry = registry.get(runKey);
        if (entry != null && entry.isDone())
        {
            // It already finished. Cancelling it would throw away the one thing worth having, so
            // the answer is what the run produced. Done and readable are a moment apart, and when
            // the result is not there yet this falls through and cancels as usual.
            String produced = entry.await(1000L);
            if (produced != null)
            {
                registry.remove(runKey, entry);
                return produced;
            }
        }
        PendingWorkRegistry.StopOutcome stopping = stopTheClient(runKey);
        boolean stopped = stopping != PendingWorkRegistry.StopOutcome.NOTHING_TO_STOP;
        boolean tracked = registry.detach(runKey);
        if (!stopped && !tracked)
        {
            CANCELLED.remove(runKey);
            return ToolResult.error("No run under runKey \"" + runKey + "\" - it finished and its " //$NON-NLS-1$ //$NON-NLS-2$
                + "result was collected, it was cancelled already, or it was abandoned long " //$NON-NLS-1$
                + "enough to be dropped.").toJson(); //$NON-NLS-1$
        }
        return ToolResult.success()
            .put("operation", NAME) //$NON-NLS-1$
            .put("status", "Cancelled") //$NON-NLS-1$ //$NON-NLS-2$
            .put("runKey", runKey) //$NON-NLS-1$
            .put("clientStopped", stopping == PendingWorkRegistry.StopOutcome.STOPPED) //$NON-NLS-1$
            .put("message", messageFor(stopping)) //$NON-NLS-1$
            .toJson();
    }

    /** How long a cancel waits for the client to go before it says it has not. */
    private static final long STOP_WAIT_SEC = 5;

    /**
     * Stops one run's client and waits to see whether it went.
     * <p>
     * {@code destroyForcibly} asks; it does not wait, and a worker may refuse. Reporting the ask
     * as the outcome told a caller this run was done with the infobase while a client of it was
     * still writing there.
     * </p>
     *
     * @param runKey the run's key.
     * @return which of the three things happened
     */
    static PendingWorkRegistry.StopOutcome stopTheClient(String runKey)
    {
        if (runKey == null)
        {
            return PendingWorkRegistry.StopOutcome.NOTHING_TO_STOP;
        }
        Process running;
        synchronized (LAUNCHING)
        {
            // The mark and the lookup happen where a launch cannot fall between them: either the
            // run has not spawned anything and reads this mark, or it has registered its client
            // and the lookup finds it. Written here rather than in one caller, because both ways
            // of cancelling go through this.
            forgetOldMarks();
            CANCELLED.put(runKey, Long.valueOf(System.currentTimeMillis()));
            running = RUNNING.remove(runKey);
        }
        if (running == null)
        {
            return PendingWorkRegistry.StopOutcome.NOTHING_TO_STOP;
        }
        // Taken before anything is destroyed: afterwards the parent lists no children.
        java.util.List<ProcessHandle> workers =
            running.descendants().collect(java.util.stream.Collectors.toList());
        workers.forEach(ProcessHandle::destroyForcibly);
        running.destroyForcibly();
        boolean gone;
        try
        {
            gone = running.waitFor(STOP_WAIT_SEC, TimeUnit.SECONDS);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            gone = !running.isAlive();
        }
        for (ProcessHandle worker : workers)
        {
            gone = gone && !worker.isAlive();
        }
        return gone ? PendingWorkRegistry.StopOutcome.STOPPED
            : PendingWorkRegistry.StopOutcome.STILL_RUNNING;
    }

    /**
     * What a cancel is told, according to what stopping actually came to.
     *
     * @param stopping what happened.
     * @return the sentence for the caller
     */
    private static String messageFor(PendingWorkRegistry.StopOutcome stopping)
    {
        if (stopping == PendingWorkRegistry.StopOutcome.STOPPED)
        {
            return "The client and its worker processes are gone. Whatever the scenarios had " //$NON-NLS-1$
                + "already written to the infobase stays written - stopping a run is not " //$NON-NLS-1$
                + "undoing it."; //$NON-NLS-1$
        }
        if (stopping == PendingWorkRegistry.StopOutcome.STILL_RUNNING)
        {
            return "The client was told to stop and had not gone " + STOP_WAIT_SEC + " seconds " //$NON-NLS-1$ //$NON-NLS-2$
                + "later. It or one of its worker processes may still be running against the " //$NON-NLS-1$
                + "infobase; check the machine's process list."; //$NON-NLS-1$
        }
        return "No client was found under this key, and the run will not start one. That is not " //$NON-NLS-1$
            + "a promise that none ran: a client that had already exited looks the same from " //$NON-NLS-1$
            + "here. Read the infobase to find out what the scenarios did."; //$NON-NLS-1$
    }

    /**
     * Stops one run's client, if it still has one.
     * <p>
     * Children first, the order the timeout uses: the worker processes 1C starts outlive their
     * parent otherwise. Installed as the domain's stopper, so a cancel arriving through the task
     * interface stops the same client a cancel through this tool would - and marks the run the
     * same way, so a client that has not registered yet is stopped too.
     * </p>
     *
     * @param runKey the run's key.
     * @return whether there was a client to stop; {@link #stopTheClient} says whether it went
     */
    static boolean stopClient(String runKey)
    {
        return stopTheClient(runKey) != PendingWorkRegistry.StopOutcome.NOTHING_TO_STOP;
    }

    /**
     * Launches the thick client, draining its merged stdout/stderr as cp1251 on a daemon thread.
     * On timeout, destroys the process tree (child 1C workers first). Registers the client under
     * the run's key while it lives, so a cancel from another call can reach it.
     *
     * @param command the client and its arguments.
     * @param workingDir where to run.
     * @param timeoutSec how long to wait for the client.
     * @param runKey the key this run is cancelled by; null when nobody can cancel it.
     * @param launched raised once a process exists, so a caller can tell a client that may be
     *            reading its scenario from a launch that never happened.
     * @return how the client ended and what it printed
     * @throws Exception when the client cannot be launched or the wait is interrupted
     */
    private static ProcessResult runVanessa(List<String> command, File workingDir, int timeoutSec,
        String runKey, AtomicBoolean launched) throws Exception
    {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        if (workingDir != null && workingDir.isDirectory())
        {
            pb.directory(workingDir);
        }
        Process proc;
        synchronized (LAUNCHING)
        {
            if (runKey != null && CANCELLED.containsKey(runKey))
            {
                // Cancelled before anything was spawned, and nothing will be.
                ProcessResult stopped = new ProcessResult();
                stopped.cancelledBeforeLaunch = true;
                return stopped;
            }
            proc = pb.start();
            launched.set(true);
            if (runKey != null)
            {
                // Remembered only while it runs: this process is what stopClient goes looking for,
                // whether the cancel came through this tool or through the registry. Registered
                // under the same monitor the mark is written under, so a cancel arriving now
                // waits and then finds it.
                RUNNING.put(runKey, proc);
            }
        }
        // The thick client reads no stdin; close it so nothing can block on it.
        try (OutputStream in = proc.getOutputStream())
        {
            // just closing
        }
        catch (Exception ignored)
        {
            // stdin already closed
        }

        StringBuilder out = new StringBuilder();
        Thread drain = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), CP1251)))
            {
                String line;
                while ((line = r.readLine()) != null)
                {
                    synchronized (out)
                    {
                        if (out.length() < 100000)
                        {
                            out.append(line).append('\n');
                        }
                    }
                }
            }
            catch (Exception ignored)
            {
                // process output closed
            }
        }, "vanessa-drain"); //$NON-NLS-1$
        drain.setDaemon(true);
        drain.start();

        ProcessResult pr = new ProcessResult();
        try
        {
            if (!proc.waitFor(timeoutSec, TimeUnit.SECONDS))
            {
                proc.descendants().forEach(ProcessHandle::destroyForcibly);
                proc.destroyForcibly();
                pr.timedOut = true;
            }
            else
            {
                pr.exitCode = proc.exitValue();
            }
            drain.join(2000);
            synchronized (out)
            {
                pr.output = out.toString();
            }
            return pr;
        }
        finally
        {
            // Whatever ended this - a finish, a deadline, a cancel, an interrupt - the client is
            // gone before the caller takes the infobase back. EDT reconnecting while a client
            // still has the file open is the state the release exists to avoid.
            stopAndAwait(proc);
            // However this ended - finished, timed out, or cancelled from another call - the
            // handle goes. Leaving it would let a later cancel destroy a process that is no longer
            // this run, because the operating system gives the number back.
            if (runKey != null)
            {
                RUNNING.remove(runKey, proc);
            }
        }
    }

    /**
     * The result file of a run: the one this asked for, or the xml Vanessa wrote beside it.
     * <p>
     * Vanessa names the files it writes itself, so the run directory is searched when the
     * expected name is not there. The newest is taken: a directory of this run holds only
     * what this run put in it.
     * </p>
     *
     * @param asked the file this run asked Vanessa for.
     * @return that file when it exists, otherwise the newest xml beside it, otherwise the
     *         file that was asked for
     */
    static File theResultOf(File asked)
    {
        if (asked == null || asked.isFile())
        {
            return asked;
        }
        File dir = asked.getAbsoluteFile().getParentFile();
        File[] xml = dir == null ? null
            : dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".xml")); //$NON-NLS-1$
        if (xml == null || xml.length == 0)
        {
            return asked;
        }
        File newest = xml[0];
        for (File one : xml)
        {
            if (one.lastModified() > newest.lastModified())
            {
                newest = one;
            }
        }
        return newest;
    }

    /** Seconds to wait for a stopped client to actually be gone. */
    private static final int PROCESS_DEATH_WAIT_SEC = 20;

    /**
     * Stops the client and waits for it to be gone.
     * <p>
     * {@code destroyForcibly} only asks. The process is still there until the operating system
     * says otherwise, and a caller that takes the infobase back on the strength of the ask alone
     * hands EDT a file another process still has open.
     * </p>
     *
     * @param proc the client.
     */
    private static void stopAndAwait(Process proc)
    {
        if (!proc.isAlive())
        {
            return;
        }
        proc.descendants().forEach(ProcessHandle::destroyForcibly);
        proc.destroyForcibly();
        try
        {
            proc.waitFor(PROCESS_DEATH_WAIT_SEC, TimeUnit.SECONDS);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
        }
    }

    static List<String> collectScreenshots(File shotsDir)
    {
        List<String> shots = new ArrayList<>();
        File[] files = shotsDir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".png")); //$NON-NLS-1$
        if (files != null)
        {
            java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
            for (File f : files)
            {
                shots.add(f.getAbsolutePath());
            }
        }
        return shots;
    }

    private static void writeUtf8Bom(File file, String content) throws Exception
    {
        try (OutputStream os = Files.newOutputStream(file.toPath()))
        {
            os.write(0xEF);
            os.write(0xBB);
            os.write(0xBF);
            os.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String prettyJson(JsonObject o)
    {
        return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(o);
    }

    private static String trimmed(String s)
    {
        if (s == null)
        {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String tail(String s)
    {
        if (s == null || s.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        // 1C may echo the connection string (with its password) in its console output.
        String r = redactSecrets(s);
        String t = r.length() > OUTPUT_TAIL ? r.substring(r.length() - OUTPUT_TAIL) : r;
        return "Output tail: " + t.trim(); //$NON-NLS-1$
    }

    /**
     * What becomes of a composed scenario when the run ends in an exception.
     * <p>
     * A launched client may be reading the file: an interrupt - the endpoint shutting its executor
     * down, for one - throws out of the wait without stopping it, and a synchronous run registers
     * no key, so nothing here can stop that process either. Then the file stays and the answer
     * names it, because breaking a live run to tidy up is the worse of the two.
     * </p>
     * <p>
     * Nothing holds it before the launch, and a launch can fail before there is anything to hold
     * it with - a missing executable, a start the operating system refuses. A scenario carries
     * whatever the caller composed, so one nobody is reading is taken away rather than left in the
     * run directory.
     * </p>
     *
     * @param clientLaunched whether a process was actually started.
     * @param leftBehind a path an earlier removal already reported, or <code>null</code>.
     * @param composed the composed scenario, or <code>null</code> when the caller named its own.
     * @return the path still on disk, or <code>null</code> when nothing was left
     */
    static String scenarioAfterFailure(boolean clientLaunched, String leftBehind, File composed)
    {
        if (!clientLaunched)
        {
            return removeComposed(composed);
        }
        if (leftBehind != null)
        {
            return leftBehind;
        }
        return composed != null && composed.exists() ? composed.getAbsolutePath() : null;
    }

    /**
     * Whether a connection string carries a secret, and why that cannot be accepted.
     * <p>
     * The connection string reaches the client as a command-line argument, and a command line is
     * readable by every process on the machine - through the task list, through
     * {@code Win32_Process}. The masking applied elsewhere covers this plugin's log and its answer;
     * it does not reach the process the operating system has already started.
     * </p>
     * <p>
     * The environment's own launch configuration does carry credentials, but it cannot run an
     * external data processor - its 26 attributes include a startup option and no equivalent of
     * {@code /Execute} - so a scenario run cannot go through it. What remains is not to take the
     * secret: a base reached without a password, or one that accepts the operating system's own
     * authentication.
     * </p>
     *
     * @param connectionString what the caller passed.
     * @return the refusal, or <code>null</code> when nothing secret was passed
     */
    static String whyASecretCannotBePassed(String connectionString)
    {
        if (!carriesASecret(connectionString))
        {
            return null;
        }
        return "The connection string carries a password, and it is not accepted: it would reach " //$NON-NLS-1$
            + "the client as a command-line argument, where every process on this machine can read " //$NON-NLS-1$
            + "it. Run against an infobase that needs no password, or one that accepts the " //$NON-NLS-1$
            + "operating system's authentication, and leave Pwd out of the connection string."; //$NON-NLS-1$
    }

    /**
     * Whether a connection string names a password.
     * <p>
     * Both spellings the platform accepts are looked for - the quoted one and the bare one - in any
     * case, and the value is not read: what is being decided is whether a secret is present, and
     * reading it would put it somewhere.
     * </p>
     *
     * @param connectionString what the caller passed; <code>null</code> carries nothing.
     * @return true when a password is named
     */
    static boolean carriesASecret(String connectionString)
    {
        if (connectionString == null || connectionString.isEmpty())
        {
            return false;
        }
        for (String field : fieldsOf(connectionString))
        {
            int equals = field.indexOf('=');
            String name = (equals < 0 ? field : field.substring(0, equals)).trim();
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            // /P takes its value joined to it rather than after an equals sign, so the name alone
            // is never seen: the field reads /Psecret.
            if (lower.startsWith("/p")) //$NON-NLS-1$
            {
                return true;
            }
            if (SECRET_FIELDS.contains(lower) || lower.contains("pwd") //$NON-NLS-1$
                || lower.contains("pass")) //$NON-NLS-1$
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Masks the password out of anything that may carry a 1C connection string
     * ({@code Pwd="..."} or {@code Pwd=...}) before it is logged or returned.
     */
    static String redactSecrets(String s)
    {
        if (s == null || s.isEmpty())
        {
            return s;
        }
        String r = s.replaceAll("(?i)(Pwd\\s*=\\s*\")[^\"]*\"", "$1***\""); //$NON-NLS-1$ //$NON-NLS-2$
        r = r.replaceAll("(?i)(Pwd\\s*=\\s*)([^\";\\s]+)", "$1***"); //$NON-NLS-1$ //$NON-NLS-2$
        return r;
    }

    private static Charset charset(String name)
    {
        try
        {
            return Charset.forName(name);
        }
        catch (Exception e)
        {
            return StandardCharsets.UTF_8;
        }
    }
}
