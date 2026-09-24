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

    /**
     * Polls a scenario run this tool started.
     *
     * @param domain the registry domain the key was found in
     * @param operation unused; the tool names none
     * @return {@code vanessa} when the key is in the scenario registry, or {@code null}
     */
    @Override
    public String resumes(String domain, String operation)
    {
        return PendingWorkRegistry.VANESSA.domain().equals(domain) ? NAME : null;
    }

    @Override
    public String getDescription()
    {
        return "Scenario UI testing via Vanessa Automation: plays Gherkin feature files against a " //$NON-NLS-1$
            + "running infobase and reports which scenario step failed and why (+ failure " //$NON-NLS-1$
            + "screenshots). Complements yaxunit_tests (code from the inside) by driving the UI " //$NON-NLS-1$
            + "from the outside. Pass featurePath (a .feature file or a directory of them) and " //$NON-NLS-1$
            + "projectName - the infobase the project is bound to is the one played against, " //$NON-NLS-1$
            + "or connectionString to name another. Or compose the action right here: listKind " //$NON-NLS-1$
            + "with listName opens the list, column with columnValue goes to the row, buttonTitle " //$NON-NLS-1$
            + "or buttonName presses the form's button, and the window it opens is waited for - " //$NON-NLS-1$
            + "windowWaitSeconds - and captured. " //$NON-NLS-1$
            + "On a list action and on formToOpen, testManager and testClient are turned on when " //$NON-NLS-1$
            + "left out, and false is refused: the start step then activates the client that " //$NON-NLS-1$
            + "opens the infobase of the run. " //$NON-NLS-1$
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
            .stringProperty("listKind", //$NON-NLS-1$
                "Metadata kind of the list the scenario opens, one of: catalog, document, " //$NON-NLS-1$
                    + "documentJournal, chartOfCharacteristicTypes, chartOfAccounts, " //$NON-NLS-1$
                    + "chartOfCalculationTypes, informationRegister, accumulationRegister, " //$NON-NLS-1$
                    + "accountingRegister, calculationRegister.") //$NON-NLS-1$
            .stringProperty("listName", //$NON-NLS-1$
                "Name of the metadata object whose list form the scenario opens.") //$NON-NLS-1$
            .stringProperty("tableName", //$NON-NLS-1$
                "Name of the list's table on the form the steps address (default Список).") //$NON-NLS-1$
            .stringProperty("column", //$NON-NLS-1$
                "Caption of the column the row to act on is found by.") //$NON-NLS-1$
            .stringProperty("columnValue", //$NON-NLS-1$
                "Value of that column; an empty string is a value and is looked for as empty.") //$NON-NLS-1$
            .stringProperty("whenSeveral", //$NON-NLS-1$
                "When several rows carry the value: unique (default) demands exactly one before " //$NON-NLS-1$
                    + "moving, first moves to the first of them.") //$NON-NLS-1$
            .stringProperty("buttonTitle", //$NON-NLS-1$
                "Title of the form's button to press - exactly one of buttonTitle and buttonName.") //$NON-NLS-1$
            .stringProperty("buttonName", //$NON-NLS-1$
                "Name of the form's button to press - exactly one of buttonTitle and buttonName.") //$NON-NLS-1$
            .stringProperty("windowTitle", //$NON-NLS-1$
                "Title of the window the button is expected to open; without it the run waits " //$NON-NLS-1$
                    + "for a window whose title differs from the one before the click.") //$NON-NLS-1$
            .integerProperty("windowWaitSeconds", //$NON-NLS-1$
                "Seconds the step that waits for the window waits (default 10).") //$NON-NLS-1$
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
            .booleanProperty("testManager", //$NON-NLS-1$
                "Start the client as a test manager (default false). The UI-testing types a " //$NON-NLS-1$
                    + "form-driving scenario needs exist only in a client started this way; " //$NON-NLS-1$
                    + "without it such a step answers Тип не определен. " //$NON-NLS-1$
                    + "On a list action and on formToOpen, left out means on, and false is " //$NON-NLS-1$
                    + "refused.") //$NON-NLS-1$
            .booleanProperty("testClient", //$NON-NLS-1$
                "Name a test client in VAParams for the start step to launch (default false). " //$NON-NLS-1$
                    + "The step that starts TestClient has no client to start without it and " //$NON-NLS-1$
                    + "answers with an empty client type and PID 0. " //$NON-NLS-1$
                    + "On a list action and on formToOpen, left out means on, and false is " //$NON-NLS-1$
                    + "refused.") //$NON-NLS-1$
            .integerProperty("testClientPort", //$NON-NLS-1$
                "Port the test client listens on (default 48010). Name another when a second " //$NON-NLS-1$
                    + "run or another EDT already holds it.") //$NON-NLS-1$
            .booleanProperty("screenshots", //$NON-NLS-1$
                "Capture a screenshot on step failure (default true).") //$NON-NLS-1$
            .booleanProperty("keepOpen", //$NON-NLS-1$
                "Leave the 1C client open after the run to watch it (default false). Note: a kept-open " //$NON-NLS-1$
                    + "client will time out here since it never exits.") //$NON-NLS-1$
 //$NON-NLS-1$
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
        if (params != null && params.containsKey("stepDelaySeconds")) //$NON-NLS-1$
        {
            return ToolResult.error("stepDelaySeconds names no Vanessa parameter: the run would " //$NON-NLS-1$
                + "have played at full speed while the answer described a slowed one. Watch a " //$NON-NLS-1$
                + "run through keepOpen instead.").toJson(); //$NON-NLS-1$
        }
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
        // The list arguments are a fourth way of naming the scenario. Counted with the other
        // three, a call that brings only them reaches the composition below; counted afterwards,
        // that call has named nothing and is refused before any of it runs.
        boolean hasList = anyListArgumentGiven(params);
        String badlyNamed = whyTheScenarioIsNotNamed(hasPath, hasText, hasForm, hasList);
        if (badlyNamed != null)
        {
            return ToolResult.error(badlyNamed).toJson();
        }
        String openStep = JsonUtils.extractStringArgument(params, "openStep"); //$NON-NLS-1$
        String startStep = JsonUtils.extractStringArgument(params, "startStep"); //$NON-NLS-1$
        if (hasList)
        {
            String badlyMixed = whyTheListWayIsNotTheOnlyOne(hasPath, hasText, hasForm, openStep);
            if (badlyMixed != null)
            {
                return ToolResult.error(badlyMixed).toJson();
            }
            String notOneLine = whyStartStepIsNotOneLine(startStep);
            if (notOneLine != null)
            {
                return ToolResult.error(notOneLine).toJson();
            }
        }
        if (hasForm)
        {
            String badlyFormed = whyTheFormCannotBeNamed(formToOpen, openStep, startStep);
            if (badlyFormed != null)
            {
                return ToolResult.error(badlyFormed).toJson();
            }
            scenarioText = scenarioForForm(formToOpen.trim(), startStep, openStep);
            hasText = true;
        }
        ListActionArgs listAction = null;
        if (hasList)
        {
            String[] refused = new String[1];
            listAction = ListActionArgs.read(params, refused);
            if (refused[0] != null)
            {
                return ToolResult.error(refused[0]).toJson();
            }
            scenarioText = listAction.scenario(startStep);
            hasText = true;
        }

        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        File workingDir = null;
        if (projectName != null && !projectName.isEmpty())
        {
            IProject project = ProjectResolver.resolve(projectName);
            if (project == null)
            {
                return ProjectResolver.notFound(projectName).toJson();
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
        if (listAction != null)
        {
            String captureOff = whyTheCaptureCannotBeOff(screenshots);
            if (captureOff != null)
            {
                return ToolResult.error(captureOff).toJson();
            }
        }
        boolean keepOpen = JsonUtils.extractBooleanArgument(params, "keepOpen", false); //$NON-NLS-1$
        int timeoutSec = JsonUtils.extractIntArgument(params, "timeoutSeconds", DEFAULT_TIMEOUT_SEC); //$NON-NLS-1$
        Integer namedPort = JsonUtils.extractIntegerArgument(params, "testClientPort"); //$NON-NLS-1$
        if (namedPort == null && params != null && params.containsKey("testClientPort")) //$NON-NLS-1$
        {
            return ToolResult.error("testClientPort is not a whole number a port can be: " //$NON-NLS-1$
                + "a port is 1 to " + HIGHEST_PORT + ". Read as a number it cannot be, the " //$NON-NLS-1$ //$NON-NLS-2$
                + "run would have started on the default port instead of the one named.").toJson(); //$NON-NLS-1$
        }
        Boolean askedManager = JsonUtils.extractBooleanArgumentNullable(params, "testManager"); //$NON-NLS-1$
        Boolean askedClient = JsonUtils.extractBooleanArgumentNullable(params, "testClient"); //$NON-NLS-1$
        // A list action and formToOpen both open a form. The step that starts TestClient has
        // nothing to start unless this run is a test manager and names the client, so an omitted
        // argument is turned on and an explicit false is refused before anything is launched.
        // A file or a text the caller wrote keeps the old default: off unless they ask.
        boolean drivesForm = drivesAForm(listAction != null, hasForm);
        if (drivesForm)
        {
            String clientRefusal = whyAFormDrivingRunRefusesTheClient(askedManager, askedClient);
            if (clientRefusal != null)
            {
                return ToolResult.error(clientRefusal).toJson();
            }
        }
        final boolean settledWantsManager = wantsTheClientTheFormNeeds(drivesForm, askedManager);
        final boolean settledWantsTestClient = wantsTheClientTheFormNeeds(drivesForm, askedClient);
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
        if (listAction != null && listAction.waitSeconds > timeoutSec)
        {
            return ToolResult.error("windowWaitSeconds is " + listAction.waitSeconds //$NON-NLS-1$
                + ", above the run's own timeoutSeconds of " + timeoutSec //$NON-NLS-1$
                + ". The run would be killed before the step gave up waiting, and the answer " //$NON-NLS-1$
                + "would read as a stuck run rather than a window that never opened. Raise " //$NON-NLS-1$
                + "timeoutSeconds or lower windowWaitSeconds.").toJson(); //$NON-NLS-1$
        }

        // Everything the run needs is settled by now, so it can be handed to a job as it is.
        // Settled copies, because a job closes over what it is given and the timeout above is
        // clamped after it is read.
        final int settledTimeout = timeoutSec;
        final File settledExe = exeFile;
        final File settledEpf = epfFile;
        final File settledFeature = featurePath;
        // The keys each composing branch sets for itself: the screenshot pair wherever the tag
        // stands, and the asynchronous-step ceiling on the branch whose step carries a wait.
        final JsonObject settledOurs = listAction != null ? listAction.ourKeys()
            : (hasForm ? screenshotKeys() : null);
        final JsonObject settledSought = listAction != null ? listAction.sought() : null;
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
                screenshots, keepOpen, settledTimeout, settledClientPort,
                extraVaParams, settledOurs, settledSought, runDirForJob, null, settledInfobase,
                settledAddress, settledWantsTestClient, settledWantsManager);
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
                    composedScenario, screenshots, keepOpen, settledTimeout,
                    settledClientPort, extraVaParams, settledOurs, settledSought, runDirForJob,
                    jobKey, settledInfobase, settledAddress, settledWantsTestClient,
                    settledWantsManager));
            // The name a poll of this run arrives under, so a live key exempts only this tool's
            // own resumption path from the heavy gates.
            entry.startedBy = NAME;
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
     * @param timeoutSec how long to wait for the run.
     * @param clientPort the port the test client listens on.
     * @param extraVaParams what the caller added to the Vanessa document.
     * @param oursVaParams the keys this tool sets on the branch the call took, or
     *            <code>null</code> on a branch that composes no scenario of its own.
     * @param sought what the composed scenario was after, named back in the answer, or
     *            <code>null</code> on a branch that composes none.
     * @param workingDir where to run.
     * @param jobKey the key this run is cancelled by.
     * @param infobaseName the infobase this resolved from the project, or <code>null</code>
     *            when the caller named the connection string itself.
     * @param infobaseAddress the infobase EDT holds, as it was resolved once, or
     *            <code>null</code> when the caller named the connection string itself.
     * @param withTestClient whether to name a test client for the start step to launch.
     * @param asTestManager whether the client is started as a test manager.
     * @return the answer
     */
    private String play(File exeFile, File epfFile, String connectionString, File featurePath,
        String composedScenario, boolean screenshots, boolean keepOpen,
        int timeoutSec, int clientPort, JsonObject extraVaParams, JsonObject oursVaParams,
        JsonObject sought, File workingDir, String jobKey,
        String infobaseName, InfobaseAddress.Address infobaseAddress, boolean withTestClient,
        boolean asTestManager)
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
                keepOpen, connectionString, clientPort, timeoutSec, withTestClient,
                extraVaParams, oursVaParams);
            writeUtf8Bom(paramsFile, vaParamsJson);

            File runDir = workingDir != null ? workingDir : outDir.toFile();
            List<String> command =
                buildCommand(exeFile, connectionString, epfFile, paramsFile, asTestManager);
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
            if (sought != null)
            {
                ok.put("sought", sought); //$NON-NLS-1$
                if (!results.isPassed())
                {
                    // A scenario that failed is a run that happened, not a call that went wrong:
                    // what was sought and what was on screen belong in the answer the same way.
                    String onScreen = onScreenOf(firstFailureOf(results));
                    ok.put("onScreen", onScreen); //$NON-NLS-1$
                    if (onScreen.isEmpty())
                    {
                        ok.put("onScreenNote", ON_SCREEN_EMPTY_NOTE); //$NON-NLS-1$
                    }
                }
            }
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
     * Why this call does not say what to play, when the list arguments are not part of the question.
     *
     * @param hasPath whether a file or directory was named.
     * @param hasText whether the scenario itself was given.
     * @param hasForm whether a form to open was named, from which a scenario is composed.
     * @return the refusal, or <code>null</code>
     * @see #whyTheScenarioIsNotNamed(boolean, boolean, boolean, boolean)
     */
    static String whyTheScenarioIsNotNamed(boolean hasPath, boolean hasText, boolean hasForm)
    {
        return whyTheScenarioIsNotNamed(hasPath, hasText, hasForm, false);
    }

    /**
     * Why this call does not say what to play, or <code>null</code> when it does.
     * <p>
     * Four ways name a scenario, and a call carries exactly one of them: a file, the scenario
     * text, a form to open, or the list arguments that compose an action in a list. None of them,
     * and there is nothing to play; more than one, and the call does not say which was meant.
     * </p>
     *
     * @param hasPath whether a file or directory was named.
     * @param hasText whether the scenario itself was given.
     * @param hasForm whether a form to open was named, from which a scenario is composed.
     * @param hasList whether any list argument is present, which composes a scenario of its own.
     * @return the refusal, or <code>null</code>
     */
    static String whyTheScenarioIsNotNamed(boolean hasPath, boolean hasText, boolean hasForm,
        boolean hasList)
    {
        int named = (hasPath ? 1 : 0) + (hasText ? 1 : 0) + (hasForm ? 1 : 0) + (hasList ? 1 : 0);
        if (named > 1)
        {
            return "featurePath, scenarioText, formToOpen and the list arguments each name what " //$NON-NLS-1$
                + "to play, and only one of them can be it. Pass the path to a file that exists, " //$NON-NLS-1$
                + "the scenario text to be written for this run, the form to open and snapshot, " //$NON-NLS-1$
                + "or the list arguments."; //$NON-NLS-1$
        }
        if (named == 0)
        {
            return "featurePath is required (a .feature file or a directory), or scenarioText " //$NON-NLS-1$
                + "with the scenario itself, or formToOpen with the form to open and snapshot, or " //$NON-NLS-1$
                + "the list arguments (listKind, listName, column, columnValue and a button) to " //$NON-NLS-1$
                + "open a list, go to a row and capture what a button opened."; //$NON-NLS-1$
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
     * {@code @screenshot} tag, writing them where {@code КаталогOutputСкриншоты} points - which
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
     * The words each metadata kind's list is opened by, with {@code {list}} where the name goes.
     * <p>
     * Vanessa has no single "open a list" step: each kind is opened by its own wording, and the
     * words belong to Vanessa and differ between its versions. The map holds the spellings of the
     * step library this tool was verified against, one entry per kind it can open.
     * </p>
     */
    static final java.util.Map<String, String> LIST_OPEN_STEPS = listOpenSteps();

    /**
     * Fills {@link #LIST_OPEN_STEPS}.
     *
     * @return the kind to wording map, unmodifiable
     */
    private static java.util.Map<String, String> listOpenSteps()
    {
        java.util.Map<String, String> steps = new java.util.LinkedHashMap<>();
        steps.put("catalog", "Я открываю основную форму списка справочника \"{list}\""); //$NON-NLS-1$ //$NON-NLS-2$
        steps.put("document", "Я открываю основную форму списка документа \"{list}\""); //$NON-NLS-1$ //$NON-NLS-2$
        steps.put("documentJournal", "Я открываю основную форму журнала документов \"{list}\""); //$NON-NLS-1$ //$NON-NLS-2$
        steps.put("chartOfCharacteristicTypes", //$NON-NLS-1$
            "Я открываю основную форму списка плана видов характеристик \"{list}\""); //$NON-NLS-1$
        steps.put("chartOfAccounts", "Я открываю основную форму списка плана счетов \"{list}\""); //$NON-NLS-1$ //$NON-NLS-2$
        steps.put("chartOfCalculationTypes", //$NON-NLS-1$
            "Я открываю основную форму списка плана видов расчета \"{list}\""); //$NON-NLS-1$
        steps.put("informationRegister", //$NON-NLS-1$
            "Я открываю основную форму списка регистра сведений \"{list}\""); //$NON-NLS-1$
        steps.put("accumulationRegister", //$NON-NLS-1$
            "Я открываю основную форму списка регистра накопления \"{list}\""); //$NON-NLS-1$
        steps.put("accountingRegister", //$NON-NLS-1$
            "Я открываю основную форму списка регистра бухгалтерии \"{list}\""); //$NON-NLS-1$
        steps.put("calculationRegister", //$NON-NLS-1$
            "Я открываю основную форму списка регистра расчета \"{list}\""); //$NON-NLS-1$
        return java.util.Collections.unmodifiableMap(steps);
    }

    /** The names of the arguments that compose the list action; present means the branch is taken. */
    private static final String[] LIST_ARGUMENTS = {
        "listKind", "listName", "tableName", "column", "columnValue", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "whenSeveral", "buttonTitle", "buttonName", "windowTitle", "windowWaitSeconds"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    /**
     * Whether the call carries any of the list arguments, and so asks for the action they compose.
     * <p>
     * Asked by presence and not by validity: an empty {@code listKind} still names the branch, and
     * the refusal it earns is the one about the branch it asked for.
     * </p>
     *
     * @param params the call's arguments.
     * @return true when at least one list argument is present
     */
    static boolean anyListArgumentGiven(Map<String, String> params)
    {
        if (params == null)
        {
            return false;
        }
        for (String name : LIST_ARGUMENTS)
        {
            if (params.containsKey(name))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Why the list arguments cannot share the call with another way of naming what to play, or
     * <code>null</code> when they are alone.
     * <p>
     * One call, one way: the list arguments compose a scenario of their own, and a call that also
     * named a file, a text or a form says nothing about which of the two it meant to play - the
     * same refusal the older pairings earn. {@code openStep} is refused for the narrower reason
     * that it names one way of opening a form while {@code listKind} names another, and the
     * composed scenario opens the list by its own words.
     * </p>
     *
     * @param hasPath whether a feature path was given.
     * @param hasText whether the scenario itself was given.
     * @param hasForm whether a form to open was given.
     * @param openStep the wording the caller gave for opening, or <code>null</code>.
     * @return the refusal, or <code>null</code>
     */
    static String whyTheListWayIsNotTheOnlyOne(boolean hasPath, boolean hasText, boolean hasForm,
        String openStep)
    {
        if (hasPath || hasText || hasForm)
        {
            return "The list arguments (listKind, listName and the rest) compose the scenario on " //$NON-NLS-1$
                + "their own, and featurePath, scenarioText and formToOpen each name what to play " //$NON-NLS-1$
                + "as well. Pass one way: the list arguments, or one of those."; //$NON-NLS-1$
        }
        if (openStep != null && !openStep.trim().isEmpty())
        {
            return "openStep names one way of opening a form and listKind names another: the " //$NON-NLS-1$
                + "scenario composed from the list arguments opens the list by its own words. " //$NON-NLS-1$
                + "Leave openStep out, or write the whole scenario in scenarioText."; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Why the step that gets a client cannot span lines, or <code>null</code> when it is one line.
     *
     * @param startStep the wording the caller gave, or <code>null</code>.
     * @return the refusal, or <code>null</code>
     */
    static String whyStartStepIsNotOneLine(String startStep)
    {
        if (startStep != null && (startStep.indexOf('\n') >= 0 || startStep.indexOf('\r') >= 0))
        {
            return "startStep is one step and therefore one line."; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Why the capture cannot be switched off on the list action, or <code>null</code> when it is on.
     * <p>
     * The frame of the window the button opened is the point of the call, and the frame is taken
     * by the {@code @screenshot} tag - which captures only while the run's screenshots are on. A
     * caller switching them off would be answered with an empty screenshots list and no word about
     * why, which reads as a capture that failed.
     * </p>
     *
     * @param screenshots whether the call asked for screenshots.
     * @return the refusal, or <code>null</code>
     */
    static String whyTheCaptureCannotBeOff(boolean screenshots)
    {
        if (screenshots)
        {
            return null;
        }
        return "screenshots=false leaves nothing for the @screenshot tag to capture with: the " //$NON-NLS-1$
            + "step after the tag is photographed only while the run's screenshots are on. Leave " //$NON-NLS-1$
            + "the argument out or pass true - the frame of the window the button opened is the " //$NON-NLS-1$
            + "point of this call."; //$NON-NLS-1$
    }

    /**
     * Whether this call opens a form, so the step that starts TestClient has to have a client.
     * <p>
     * A list action and {@code formToOpen} both compose a scenario that begins with that step and
     * then opens a form. A file or a text the caller wrote does not, and keeps the old default.
     * </p>
     *
     * @param hasListAction whether the call carried the list arguments
     * @param hasForm whether the call named {@code formToOpen}
     * @return whether the run drives a form
     */
    static boolean drivesAForm(boolean hasListAction, boolean hasForm)
    {
        return hasListAction || hasForm;
    }

    /**
     * Why a form-driving run cannot start, or <code>null</code> when it can.
     * <p>
     * An omitted argument is not a refusal: the run turns it on. An explicit {@code false} is,
     * because the step that starts TestClient then has no client to start. The UI-testing types
     * exist only under a test manager, and the start step launches the client this tool names.
     * </p>
     *
     * @param testManager what the caller passed, or <code>null</code> when left out
     * @param testClient what the caller passed, or <code>null</code> when left out
     * @return the refusal, or <code>null</code>
     */
    static String whyAFormDrivingRunRefusesTheClient(Boolean testManager, Boolean testClient)
    {
        boolean managerOff = Boolean.FALSE.equals(testManager);
        boolean clientOff = Boolean.FALSE.equals(testClient);
        if (!managerOff && !clientOff)
        {
            return null;
        }
        String which;
        if (managerOff && clientOff)
        {
            which = "testManager and testClient are false"; //$NON-NLS-1$
        }
        else if (managerOff)
        {
            which = "testManager is false"; //$NON-NLS-1$
        }
        else
        {
            which = "testClient is false"; //$NON-NLS-1$
        }
        return which + ". A list action and formToOpen open a form, and the step that starts " //$NON-NLS-1$
            + "TestClient has no client to start without both: the UI-testing types exist only " //$NON-NLS-1$
            + "under a test manager, and the start step launches the client this tool names, " //$NON-NLS-1$
            + "the one that opens the infobase of the run. Leave either argument out and it is " //$NON-NLS-1$
            + "turned on."; //$NON-NLS-1$
    }

    /**
     * Whether the run starts as a test manager, or names a test client.
     * <p>
     * On a form-driving call an omitted argument is on. An explicit false stays false, so a
     * refusal that was skipped cannot be turned into a run that claims the opposite. On any other
     * call the argument is off unless the caller asked.
     * </p>
     *
     * @param drivesForm whether this call opens a form
     * @param asked what the caller passed, or <code>null</code> when left out
     * @return whether the flag is on for the run
     */
    static boolean wantsTheClientTheFormNeeds(boolean drivesForm, Boolean asked)
    {
        if (Boolean.TRUE.equals(asked))
        {
            return true;
        }
        if (Boolean.FALSE.equals(asked))
        {
            return false;
        }
        return drivesForm;
    }

    /**
     * The two keys without which the {@code @screenshot} tag captures nothing.
     * <p>
     * Vanessa photographs the step that follows the tag only when its add-in is attached or an
     * external command is named, and a command would name a program this tool has no reason to
     * assume is installed on the machine. The add-in pair is what remains, and it is written on
     * every branch that composes a scenario carrying the tag.
     * </p>
     *
     * @return the keys, ready to be merged into the run's document
     */
    static JsonObject screenshotKeys()
    {
        JsonObject o = new JsonObject();
        o.addProperty("ИспользоватьКомпонентуVanessaExt", true); //$NON-NLS-1$
        o.addProperty("ИспользоватьВнешнююКомпонентуДляСкриншотов", true); //$NON-NLS-1$
        return o;
    }

    /**
     * The window the failing step's own text names, or an empty string when it names none.
     * <p>
     * Two step families name one: a button that was not found writes {@code ТекущееОкно=} followed
     * by the active window's title, and a window that never opened is reported as
     * {@code Текущее окно <%3>.} - the title inside angle brackets, then the full stop that closes
     * the sentence. The brackets and that stop belong to Vanessa's sentence and are not part of
     * the title. Everything else Vanessa writes about a failure names no window at all, and an
     * empty string is what that honestly reads as - a title guessed at from anywhere else would
     * put a window on screen the run never saw.
     * </p>
     *
     * @param failureText the message of the failing step, as the report carries it.
     * @return the window title, or an empty string
     */
    static String onScreenOf(String failureText)
    {
        if (failureText == null || failureText.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        int buttonWindow = failureText.indexOf("ТекущееОкно="); //$NON-NLS-1$
        if (buttonWindow >= 0)
        {
            return restOfTheLine(failureText, buttonWindow + "ТекущееОкно=".length()); //$NON-NLS-1$
        }
        int waitedWindow = failureText.indexOf("Текущее окно "); //$NON-NLS-1$
        if (waitedWindow >= 0)
        {
            return titleInsideTheSentenceBrackets(
                restOfTheLine(failureText, waitedWindow + "Текущее окно ".length())); //$NON-NLS-1$
        }
        return ""; //$NON-NLS-1$
    }

    /**
     * The title Vanessa wrapped as {@code <%3>}, or the text unchanged when it is not wrapped.
     * <p>
     * The waiting step's refusal puts the active window's title between angle brackets. Those
     * brackets are the sentence's, the same shape as the count step's {@code <%1>}, and a caller
     * reading them back would be told the window is named {@code <Реализация товаров>} when its
     * title is {@code Реализация товаров}.
     * </p>
     *
     * @param value the remainder of the sentence, already without the closing full stop.
     * @return the title
     */
    private static String titleInsideTheSentenceBrackets(String value)
    {
        if (value.length() >= 2 && value.charAt(0) == '<' && value.charAt(value.length() - 1) == '>')
        {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    /**
     * The text from a position to the end of its line, without the full stop that closes the
     * sentence it was read out of.
     * <p>
     * Vanessa ends the waiting step's refusal with a full stop after the title it names; the stop
     * closes Vanessa's sentence and is not part of the title. A title that genuinely ends in one
     * loses it, which is the cheaper of the two readings.
     * </p>
     *
     * @param text the text to cut from.
     * @param from where the wanted part starts.
     * @return the rest of that line, trimmed
     */
    private static String restOfTheLine(String text, int from)
    {
        int end = text.indexOf('\n', from);
        int cr = text.indexOf('\r', from);
        if (cr >= 0 && (end < 0 || cr < end))
        {
            end = cr;
        }
        String value = (end < 0 ? text.substring(from) : text.substring(from, end)).trim();
        if (value.endsWith(".")) //$NON-NLS-1$
        {
            return value.substring(0, value.length() - 1).trim();
        }
        return value;
    }

    /**
     * The message of the first step that did not pass, or <code>null</code> when the report names
     * none.
     *
     * @param results the parsed report.
     * @return that message, or <code>null</code>
     */
    static String firstFailureOf(JUnitRunOutcome results)
    {
        if (!results.getFailureDetails().isEmpty())
        {
            return results.getFailureDetails().get(0).message;
        }
        if (!results.getErrorDetails().isEmpty())
        {
            return results.getErrorDetails().get(0).message;
        }
        return null;
    }

    /** Said beside an empty onScreen, so an honest empty is not read as a missing read. */
    static final String ON_SCREEN_EMPTY_NOTE =
        "The failing step's own text names no window, and no other is guessed at: onScreen is " //$NON-NLS-1$
            + "empty because Vanessa wrote nothing to read one from."; //$NON-NLS-1$

    /** Seconds the window-waiting step waits when the caller names none. */
    static final int DEFAULT_WINDOW_WAIT_SEC = 10;

    /**
     * The arguments of the list action, settled and validated as one thing.
     * <p>
     * Ten arguments that only mean anything together are read once, refused once and composed
     * once, rather than threaded through the caller a parameter at a time. What they settle into:
     * the scenario, the {@code sought} the answer names back, and the Vanessa keys the branch
     * needs for its capture.
     * </p>
     */
    static final class ListActionArgs
    {
        final String listKind;
        final String listName;
        final String tableName;
        final String column;
        final String columnValue;
        final boolean requireOne;
        final String buttonName;
        final String buttonTitle;
        final String windowTitle;
        final int waitSeconds;

        ListActionArgs(String listKind, String listName, String tableName, String column,
            String columnValue, boolean requireOne, String buttonName, String buttonTitle,
            String windowTitle, int waitSeconds)
        {
            this.listKind = listKind;
            this.listName = listName;
            this.tableName = tableName;
            this.column = column;
            this.columnValue = columnValue;
            this.requireOne = requireOne;
            this.buttonName = buttonName;
            this.buttonTitle = buttonTitle;
            this.windowTitle = windowTitle;
            this.waitSeconds = waitSeconds;
        }

        /**
         * Reads and settles the list arguments, or fills the refusal when they do not make a
         * scenario.
         * <p>
         * Every value lands inside a step's quotes - double quotes in most steps, single quotes in
         * a Gherkin table row - so a value carrying the quote it is wrapped in, or a line break,
         * is refused rather than composed into a scenario Vanessa would read as something else.
         * {@code column} and {@code columnValue} also land in a table cell, and a Gherkin table
         * splits that cell on every vertical bar, so a bar is refused there too. The one value
         * read exactly as given is {@code columnValue}: an empty string is a value there, looked
         * for as empty.
         * </p>
         *
         * @param params the call's arguments.
         * @param refusal filled with why the call cannot proceed, when it cannot.
         * @return the settled arguments, or <code>null</code> with the refusal filled
         */
        static ListActionArgs read(Map<String, String> params, String[] refusal)
        {
            String listKind = JsonUtils.extractStringArgument(params, "listKind"); //$NON-NLS-1$
            if (listKind == null || listKind.trim().isEmpty())
            {
                refusal[0] = "listKind is empty. Name the metadata kind of the list the scenario " //$NON-NLS-1$
                    + "opens: " + String.join(", ", LIST_OPEN_STEPS.keySet()) + "."; //$NON-NLS-1$ //$NON-NLS-2$
                return null;
            }
            listKind = listKind.trim();
            if (!LIST_OPEN_STEPS.containsKey(listKind))
            {
                refusal[0] = "listKind '" + listKind + "' names no metadata kind this tool can " //$NON-NLS-1$ //$NON-NLS-2$
                    + "open a list of: " + String.join(", ", LIST_OPEN_STEPS.keySet()) + "."; //$NON-NLS-1$ //$NON-NLS-2$
                return null;
            }
            String listName = JsonUtils.extractStringArgument(params, "listName"); //$NON-NLS-1$
            if (listName == null || listName.trim().isEmpty())
            {
                refusal[0] = "listName is empty. Name the metadata object whose list the " //$NON-NLS-1$
                    + "scenario opens."; //$NON-NLS-1$
                return null;
            }
            listName = listName.trim();
            String namedBadly = whyItCannotGoIntoAStep("listName", listName, false, '"'); //$NON-NLS-1$
            if (namedBadly != null)
            {
                refusal[0] = namedBadly;
                return null;
            }
            String tableName = JsonUtils.extractStringArgument(params, "tableName"); //$NON-NLS-1$
            if (tableName == null)
            {
                tableName = "Список"; //$NON-NLS-1$
            }
            else if (tableName.trim().isEmpty())
            {
                refusal[0] = "tableName is an empty string. The steps address the list's table by " //$NON-NLS-1$
                    + "name, and an empty one addresses nothing - leave the argument out and the " //$NON-NLS-1$
                    + "usual Список is used."; //$NON-NLS-1$
                return null;
            }
            else
            {
                tableName = tableName.trim();
                String tableBadly = whyItCannotGoIntoAStep("tableName", tableName, false, '"'); //$NON-NLS-1$
                if (tableBadly != null)
                {
                    refusal[0] = tableBadly;
                    return null;
                }
            }
            String column = JsonUtils.extractStringArgument(params, "column"); //$NON-NLS-1$
            if (column == null || column.trim().isEmpty())
            {
                refusal[0] = "column is empty. Name the caption of the column the row to act on " //$NON-NLS-1$
                    + "is found by."; //$NON-NLS-1$
                return null;
            }
            column = column.trim();
            // Both quote kinds: the column stands inside a Gherkin table row in single quotes and
            // inside the counting step's own double quotes. The bar is the table's own separator.
            String columnBadly = whyItCannotGoIntoAStep("column", column, true, '"', '\''); //$NON-NLS-1$
            if (columnBadly != null)
            {
                refusal[0] = columnBadly;
                return null;
            }
            String columnValue = JsonUtils.extractStringArgument(params, "columnValue"); //$NON-NLS-1$
            if (columnValue == null)
            {
                refusal[0] = "columnValue is missing. Name the value of the column the row is " //$NON-NLS-1$
                    + "found by - an empty string is a value and is looked for as empty, but the " //$NON-NLS-1$
                    + "argument has to be there to carry it."; //$NON-NLS-1$
                return null;
            }
            String valueBadly = whyItCannotGoIntoAStep("columnValue", columnValue, true, '"', '\''); //$NON-NLS-1$
            if (valueBadly != null)
            {
                refusal[0] = valueBadly;
                return null;
            }
            String whenSeveral = JsonUtils.extractStringArgument(params, "whenSeveral"); //$NON-NLS-1$
            boolean requireOne = true;
            if (whenSeveral != null && !whenSeveral.trim().isEmpty())
            {
                String asked = whenSeveral.trim();
                if ("first".equals(asked)) //$NON-NLS-1$
                {
                    requireOne = false;
                }
                else if (!"unique".equals(asked)) //$NON-NLS-1$
                {
                    refusal[0] = "whenSeveral '" + asked + "' is neither unique nor first. unique " //$NON-NLS-1$ //$NON-NLS-2$
                        + "(the default) demands exactly one matching row before moving to it; " //$NON-NLS-1$
                        + "first moves to the first match Vanessa meets and says nothing about " //$NON-NLS-1$
                        + "how many there were."; //$NON-NLS-1$
                    return null;
                }
            }
            String buttonName = JsonUtils.extractStringArgument(params, "buttonName"); //$NON-NLS-1$
            String buttonTitle = JsonUtils.extractStringArgument(params, "buttonTitle"); //$NON-NLS-1$
            boolean nameGiven = buttonName != null && !buttonName.trim().isEmpty();
            boolean titleGiven = buttonTitle != null && !buttonTitle.trim().isEmpty();
            if (nameGiven && titleGiven)
            {
                refusal[0] = "buttonTitle and buttonName both name the button to press. Pass one " //$NON-NLS-1$
                    + "of them: the scenario presses by title or by name, never both."; //$NON-NLS-1$
                return null;
            }
            if (!nameGiven && !titleGiven)
            {
                refusal[0] = "Neither buttonTitle nor buttonName names the button the scenario " //$NON-NLS-1$
                    + "presses. Pass one of them."; //$NON-NLS-1$
                return null;
            }
            if (nameGiven)
            {
                buttonName = buttonName.trim();
                buttonTitle = null;
                String nameBadly = whyItCannotGoIntoAStep("buttonName", buttonName, false, '\''); //$NON-NLS-1$
                if (nameBadly != null)
                {
                    refusal[0] = nameBadly;
                    return null;
                }
            }
            else
            {
                buttonTitle = buttonTitle.trim();
                buttonName = null;
                String titleBadly = whyItCannotGoIntoAStep("buttonTitle", buttonTitle, false, '"'); //$NON-NLS-1$
                if (titleBadly != null)
                {
                    refusal[0] = titleBadly;
                    return null;
                }
            }
            String windowTitle = JsonUtils.extractStringArgument(params, "windowTitle"); //$NON-NLS-1$
            if (windowTitle != null && !windowTitle.trim().isEmpty())
            {
                windowTitle = windowTitle.trim();
                String windowBadly = whyItCannotGoIntoAStep("windowTitle", windowTitle, false, '"'); //$NON-NLS-1$
                if (windowBadly != null)
                {
                    refusal[0] = windowBadly;
                    return null;
                }
            }
            else
            {
                windowTitle = null;
            }
            Integer named = JsonUtils.extractIntegerArgument(params, "windowWaitSeconds"); //$NON-NLS-1$
            if (named == null && params != null && params.containsKey("windowWaitSeconds")) //$NON-NLS-1$
            {
                refusal[0] = "windowWaitSeconds is not a whole number of seconds, and the " //$NON-NLS-1$
                    + "window-waiting step carries exactly that many in its text. Vanessa would " //$NON-NLS-1$
                    + "read whatever it was given, or nothing, and wait for a time nobody asked " //$NON-NLS-1$
                    + "for."; //$NON-NLS-1$
                return null;
            }
            int waitSeconds = named != null ? named.intValue() : DEFAULT_WINDOW_WAIT_SEC;
            if (waitSeconds < 1)
            {
                refusal[0] = "windowWaitSeconds is " + waitSeconds + ", and a window cannot be " //$NON-NLS-1$ //$NON-NLS-2$
                    + "waited for less than a second."; //$NON-NLS-1$
                return null;
            }
            return new ListActionArgs(listKind, listName, tableName, column, columnValue,
                requireOne, buttonName, buttonTitle, windowTitle, waitSeconds);
        }

        /**
         * Why a value cannot go into the step it is destined for, or <code>null</code> when it can.
         *
         * @param argument the argument the value came under, for the refusal to name.
         * @param value the value as it will be substituted.
         * @param inATableCell whether the value is written into a Gherkin table cell, which splits
         *            on every vertical bar.
         * @param quotes the quote characters the steps wrap this value in.
         * @return the refusal, or <code>null</code>
         */
        private static String whyItCannotGoIntoAStep(String argument, String value,
            boolean inATableCell, char... quotes)
        {
            for (char quote : quotes)
            {
                if (value.indexOf(quote) >= 0)
                {
                    return argument + " carries a quote, and the value goes inside that very " //$NON-NLS-1$
                        + "quote in a step. Pass the value alone, or write the whole scenario in " //$NON-NLS-1$
                        + "scenarioText."; //$NON-NLS-1$
                }
            }
            if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            {
                return argument + " spans lines, and a step is one line of a scenario. Pass the " //$NON-NLS-1$
                    + "value alone, or write the whole scenario in scenarioText."; //$NON-NLS-1$
            }
            if (inATableCell && value.indexOf('|') >= 0)
            {
                return argument + " carries a vertical bar, and the value goes into a cell of a " //$NON-NLS-1$
                    + "Gherkin table, which splits the row on every bar. The step would look for " //$NON-NLS-1$
                    + "something other than the value that was passed. Pass the value alone, or " //$NON-NLS-1$
                    + "write the whole scenario in scenarioText."; //$NON-NLS-1$
            }
            return null;
        }

        /**
         * The scenario the arguments compose.
         * <p>
         * Every step is the wording of Vanessa's own step library, verbatim; what this method adds
         * is the order and the tag. The row is demanded to be the only one before the move unless
         * {@code whenSeveral=first} said otherwise, the button is pressed by name or by title, and
         * the waiting step carries the {@code @screenshot} tag so the opened window is captured -
         * after the step when it passed, before it when it failed.
         * </p>
         *
         * @param startStep how to get a client, or <code>null</code> for {@link #START_STEP}.
         * @return the scenario text
         */
        String scenario(String startStep)
        {
            String starting = startStep == null || startStep.trim().isEmpty()
                ? START_STEP : startStep.trim();
            StringBuilder s = new StringBuilder();
            s.append("#language: ru\n\n"); //$NON-NLS-1$
            s.append("Функционал: Снимок после действия\n\n"); //$NON-NLS-1$
            s.append("Контекст:\n"); //$NON-NLS-1$
            s.append("    Дано ").append(starting).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            s.append("Сценарий: Действие в списке ").append(listName).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            s.append("    Когда ") //$NON-NLS-1$
                .append(LIST_OPEN_STEPS.get(listKind).replace("{list}", listName)).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            if (windowTitle == null)
            {
                // The remembered title is what the different-from wait compares against; with a
                // named window there is nothing to remember.
                s.append("    И я запоминаю заголовок текущего окна как \"ОкноДо\"\n"); //$NON-NLS-1$
            }
            if (requireOne)
            {
                s.append("    И в таблице \"").append(tableName).append("\" 1 строк, у которых ") //$NON-NLS-1$ //$NON-NLS-2$
                    .append("колонка \"").append(column).append("\" \"Равно\" \"") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(columnValue).append("\"\n"); //$NON-NLS-1$
            }
            s.append("    И в таблице \"").append(tableName).append("\" я перехожу к строке\n"); //$NON-NLS-1$ //$NON-NLS-2$
            s.append("        | '").append(column).append("' |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            s.append("        | '").append(columnValue).append("' |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            if (buttonName != null)
            {
                s.append("    И я нажимаю на кнопку с именем '").append(buttonName).append("'\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
            {
                s.append("    И я нажимаю на кнопку \"").append(buttonTitle).append("\"\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            s.append("    @screenshot\n"); //$NON-NLS-1$
            if (windowTitle != null)
            {
                s.append("    И я жду открытия окна \"").append(windowTitle) //$NON-NLS-1$
                    .append("\" в течение ").append(waitSeconds).append(" секунд\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
            {
                s.append("    И я жду открытия окна отличного от \"$ОкноДо$\" в течение ") //$NON-NLS-1$
                    .append(waitSeconds).append(" секунд\n"); //$NON-NLS-1$
            }
            s.append("    И Я закрываю все окна клиентского приложения\n"); //$NON-NLS-1$
            return s.toString();
        }

        /**
         * What the answer names back as sought.
         * <p>
         * The button appears as the one value it was given - the title or the name - and the
         * window as either its named title or the remembered title it had to differ from.
         * </p>
         *
         * @return the sought object, ready for the answer
         */
        JsonObject sought()
        {
            JsonObject o = new JsonObject();
            o.addProperty("listKind", listKind); //$NON-NLS-1$
            o.addProperty("listName", listName); //$NON-NLS-1$
            o.addProperty("tableName", tableName); //$NON-NLS-1$
            o.addProperty("column", column); //$NON-NLS-1$
            o.addProperty("columnValue", columnValue); //$NON-NLS-1$
            o.addProperty("button", buttonName != null ? buttonName : buttonTitle); //$NON-NLS-1$
            if (windowTitle != null)
            {
                o.addProperty("windowTitle", windowTitle); //$NON-NLS-1$
            }
            else
            {
                o.addProperty("differentFrom", "$ОкноДо$"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            o.addProperty("windowWaitSeconds", waitSeconds); //$NON-NLS-1$
            return o;
        }

        /**
         * The Vanessa keys this branch of the call sets for itself.
         * <p>
         * The screenshot pair, and the asynchronous-step ceiling set to the same number the
         * waiting step carries in its text: Vanessa waits the greater of the key and the step's
         * own seconds, so a document from anywhere else with a larger key would hold the wait
         * above what the caller asked for.
         * </p>
         *
         * @return the keys, ready to be merged into the run's document
         */
        JsonObject ourKeys()
        {
            JsonObject o = screenshotKeys();
            o.addProperty("ТаймаутДляАсинхронныхШагов", waitSeconds); //$NON-NLS-1$
            return o;
        }
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
     * @param connectionString the infobase the test client opens.
     * @param clientPort the port the test client listens on.
     * @param clientTimeoutSec the whole budget of the run; the client share of it is taken by
     *            {@link #clientWaitWithin(int)}.
     * @param withTestClient whether to name a test client for the start step to launch.
     * @param extra what the caller added to the Vanessa document, merged last.
     * @return the document, ready to be written
     */
    static String buildVaParams(File featurePath, File junitFile, File shotsDir,
        boolean screenshots, boolean keepOpen, String connectionString,
        int clientPort, int clientTimeoutSec, boolean withTestClient, JsonObject extra)
    {
        return buildVaParams(featurePath, junitFile, shotsDir, screenshots, keepOpen,
            connectionString, clientPort, clientTimeoutSec, withTestClient, extra, null);
    }

    /**
     * The Vanessa {@code VAParams.json}, with the keys of this call's own branch.
     *
     * @param featurePath the scenarios, a file or a directory of them.
     * @param junitFile where Vanessa writes the report this run is read from.
     * @param shotsDir where Vanessa writes failure screenshots.
     * @param screenshots whether to capture one when a step fails.
     * @param keepOpen whether to leave the client running afterwards.
     * @param connectionString the infobase the test client opens.
     * @param clientPort the port the test client listens on.
     * @param clientTimeoutSec the whole budget of the run.
     * @param withTestClient whether to name a test client for the start step to launch.
     * @param extra what the caller added to the Vanessa document, merged last.
     * @param ours the keys this tool sets on the branch the call took - the screenshot pair
     *            wherever it composes a scenario with the {@code @screenshot} tag, and the
     *            asynchronous-step ceiling on the branch whose step carries a wait - or
     *            <code>null</code> on a branch that composes none.
     * @return the document, ready to be written
     */
    static String buildVaParams(File featurePath, File junitFile, File shotsDir,
        boolean screenshots, boolean keepOpen, String connectionString,
        int clientPort, int clientTimeoutSec, boolean withTestClient, JsonObject extra,
        JsonObject ours)
    {
        JsonObject o = new JsonObject();
        // The step that starts TestClient has no client to start without this block: the run
        // answers "Тип не определен (ТестируемаяГруппаФормы)" with an empty client type and PID 0,
        // because the UI-testing types exist only once a client runs under the test manager.
        if (withTestClient)
        {
            o.add("TestClient", //$NON-NLS-1$
                testClient(connectionString, clientPort, clientWaitWithin(clientTimeoutSec)));
            // datatestclients only adds a row. The start step launches the current row, which
            // stays "Этот клиент" unless КлиентыТестирования names the row and activates it.
            // The command-line runner reads that key after the settings load, by the Russian
            // name, and the client type is stored as given - "Thin" would launch the thick client.
            o.add("КлиентыТестирования", //$NON-NLS-1$
                clientTheStartStepActivates(connectionString, clientPort));
        }
        // Without this Vanessa opens its own window and waits there. Every run then spends its
        // whole time budget on a form nobody is looking at, ends killed, and writes no report -
        // which reads exactly like a scenario that never started.
        o.addProperty("ВыполнитьСценарии", true); //$NON-NLS-1$
        // A directory of features, or the parent of a single .feature file.
        // getAbsoluteFile() first so getParentFile() is non-null even for a bare filename.
        File dir = featurePath.isDirectory() ? featurePath : featurePath.getAbsoluteFile().getParentFile();
        o.addProperty("КаталогФич", dir != null ? dir.getAbsolutePath() : featurePath.getAbsolutePath()); //$NON-NLS-1$
        if (!featurePath.isDirectory())
        {
            // The directory is what Vanessa loads from, so the file has to be named separately -
            // otherwise every other .feature sitting beside it plays too. Asked as isDirectory
            // and not as isFile, this decision and the one above cannot disagree about a path
            // that changed underneath them.
            com.google.gson.JsonArray only = new com.google.gson.JsonArray();
            only.add(featurePath.getAbsolutePath());
            o.add("СписокФичДляВыполнения", only); //$NON-NLS-1$
        }
        // Vanessa has no JUnit parameters of its own: its documented keys for a machine
        // readable result are the Allure pair, and the xml it writes there is what a JUnit
        // reader consumes. Asked under the names below, it writes nothing and reports nothing,
        // which reads as a run that produced no result.
        o.addProperty("ДелатьОтчетВФорматеАллюр", true); //$NON-NLS-1$
        o.addProperty("КаталогOutputAllureБазовый", //$NON-NLS-1$
            junitFile.getAbsoluteFile().getParentFile().getAbsolutePath());
        // The names Vanessa documents. Under any others it writes no screenshots and says
        // nothing, exactly as it wrote no report while the JUnit names were used.
        o.addProperty("ДелатьСкриншотПриВозникновенииОшибки", screenshots); //$NON-NLS-1$
        o.addProperty("КаталогOutputСкриншоты", shotsDir.getAbsolutePath()); //$NON-NLS-1$
        // The names Vanessa documents. Under any others the run ends when Vanessa itself
        // decides to, and the test client it started is left behind.
        o.addProperty("ЗакрытьTestClientПослеЗапускаСценариев", !keepOpen); //$NON-NLS-1$
        o.addProperty("ЗавершитьРаботуСистемы", !keepOpen); //$NON-NLS-1$
        if (ours != null)
        {
            // Merged before the caller's own, though the passthrough is barred from carrying these
            // names anyway: the order is a statement about whose keys these are, not a defence.
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : ours.entrySet())
            {
                o.add(e.getKey(), e.getValue());
            }
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
     * The profile name both client tables carry, so they name one row.
     * <p>
     * {@code datatestclients} merges by this name, and {@code КлиентыТестирования} finds the same
     * row and makes it the current one. A second name would be a second client the start step
     * never launches.
     * </p>
     */
    static final String TEST_CLIENT_PROFILE = "AiEdt"; //$NON-NLS-1$

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
        client.addProperty("Name", TEST_CLIENT_PROFILE); //$NON-NLS-1$
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
     * The row the start step launches: the same profile as {@code datatestclients}, made current.
     * <p>
     * Vanessa 1.2.042.19 reads {@code КлиентыТестирования} by that name
     * ({@code ПолучитьЗначениеПараметра} matches the key or its upper case, not the English
     * alias) and requires {@code Имя}, {@code ТипКлиента}, {@code ПутьКИнфобазе},
     * {@code ДопПараметры} and {@code ИмяКомпьютера}. {@code АктивизироватьСтроку} defaults to
     * true and sets the current row, which is the row
     * {@code ЯЗапускаюСценарийОткрытияTestClientИлиПодключаюУжеСуществующий} launches.
     * {@code ТипКлиента} is stored as given, so it is {@code Тонкий} and not {@code Thin}.
     * </p>
     *
     * @param connectionString the infobase the client opens.
     * @param clientPort the port the client listens on.
     * @return one row, ready to be the value of {@code КлиентыТестирования}
     */
    static com.google.gson.JsonArray clientTheStartStepActivates(String connectionString,
        int clientPort)
    {
        JsonObject row = new JsonObject();
        row.addProperty("Имя", TEST_CLIENT_PROFILE); //$NON-NLS-1$
        row.addProperty("ПутьКИнфобазе", connectionString); //$NON-NLS-1$
        row.addProperty("ПортЗапускаТестКлиента", clientPort); //$NON-NLS-1$
        row.addProperty("ДопПараметры", ""); //$NON-NLS-1$ //$NON-NLS-2$
        row.addProperty("ТипКлиента", "Тонкий"); //$NON-NLS-1$ //$NON-NLS-2$
        row.addProperty("ИмяКомпьютера", "localhost"); //$NON-NLS-1$ //$NON-NLS-2$
        row.addProperty("АктивизироватьСтроку", true); //$NON-NLS-1$
        com.google.gson.JsonArray rows = new com.google.gson.JsonArray();
        rows.add(row);
        return rows;
    }

    /**
     * The parameters this tool sets because it reads the result back from where they point.
     * <p>
     * Moving the report or the screenshot directory would leave the run reading an empty file and
     * reporting that nothing failed; leaving the client open would leave the run waiting for an
     * exit that never comes. A caller asking for one of these is told so rather than obeyed.
     * </p>
     */
    static final java.util.Set<String> OURS_TO_SET = lowerCased(
        "ДелатьОтчетВФорматеАллюр", "КаталогOutputAllureБазовый", //$NON-NLS-1$ //$NON-NLS-2$
        "ЗакрытьTestClientПослеЗапускаСценариев", "ЗавершитьРаботуСистемы", //$NON-NLS-1$ //$NON-NLS-2$
        // Turned off, Vanessa opens its window and waits there: the run spends its whole budget on
        // a form nobody is looking at and writes no report.
        "ВыполнитьСценарии", //$NON-NLS-1$
        // The passthrough takes values and lists of them, so it cannot carry the object this block
        // needs; what it can carry is a value that replaces the block with something the start step
        // cannot use. Its port and its deadline are arguments of this tool instead.
        "TestClient", //$NON-NLS-1$
        // The row the start step launches. A passthrough replacing it would leave the current
        // row on "Этот клиент" while the answer still said the run opened the project's base.
        "КлиентыТестирования", //$NON-NLS-1$
        // These come from arguments of this tool. Letting the passthrough set them too would mean
        // the later one silently wins, and the caller who passed screenshots=true would be told it
        // ran with screenshots while it did not.
        "КаталогФич", "ДелатьСкриншотПриВозникновенииОшибки", //$NON-NLS-1$ //$NON-NLS-2$
        "КаталогOutputСкриншоты", "СписокФичДляВыполнения", //$NON-NLS-1$ //$NON-NLS-2$
        // The screenshot pair and the asynchronous-step ceiling, set by the branches that compose
        // a scenario of their own. Letting the passthrough carry any of them would raise the
        // ceiling above the seconds the caller named, or turn the capture off while the answer
        // still promised a frame.
        "ИспользоватьКомпонентуVanessaExt", "ИспользоватьВнешнююКомпонентуДляСкриншотов", //$NON-NLS-1$ //$NON-NLS-2$
        "ТаймаутДляАсинхронныхШагов"); //$NON-NLS-1$

    /**
     * The English names Vanessa's name table gives keys this tool sets, lower-cased.
     * <p>
     * {@code ТаблицаИменНоваяСтрока} reads {@code useaddin} as
     * {@code ИспользоватьКомпонентуVanessaExt}, {@code useaddinforscreencapture} as
     * {@code ИспользоватьВнешнююКомпонентуДляСкриншотов}, {@code timeoutforasynchronoussteps}
     * as {@code ТаймаутДляАсинхронныхШагов}, and {@code testclienttable} as
     * {@code КлиентыТестирования}. They are not keys this tool writes - the document carries the
     * Russian names - so they do not belong in {@link #OURS_TO_SET}, whose census is the keys
     * that were written. A passthrough carrying one is merged after those keys. The settings
     * loader then skips {@code клиентытестирования}, so the English name would be dropped and
     * the current row would stay whatever it was.
     * </p>
     */
    static final java.util.Set<String> OURS_BY_ENGLISH_NAME = lowerCased(
        "useaddin", "useaddinforscreencapture", "timeoutforasynchronoussteps", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "testclienttable"); //$NON-NLS-1$

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
            // set to Turkish, and a protected name stops matching. The English names are the same
            // settings under the names Vanessa's own table gives them.
            String lower = key.toLowerCase(java.util.Locale.ROOT);
            if (OURS_TO_SET.contains(lower) || OURS_BY_ENGLISH_NAME.contains(lower))
            {
                refusal[0] = "'" + key + "' is set by this tool, from its own " //$NON-NLS-1$ //$NON-NLS-2$
                    + "arguments. Passing it here as well would leave the answer describing a " //$NON-NLS-1$
                    + "run that did not happen the way it says."; //$NON-NLS-1$
                return null;
            }
            if (readsLikeASecret(key))
            {
                // The refusal on a password covered the connection string and stopped there, while
                // everything here is written into VAParams.json on disk and lives as long as the
                // run directory does. A value cannot be told from a secret by looking at it, so
                // the name is what is asked - the same rule the connection string is held to.
                refusal[0] = "'" + key + "' reads like a password, and vanessaParams is written " //$NON-NLS-1$ //$NON-NLS-2$
                    + "to VAParams.json on disk. Use an infobase that needs no password, or one " //$NON-NLS-1$
                    + "that accepts the operating system's authentication."; //$NON-NLS-1$
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
     * Whether a parameter name reads like a password.
     * <p>
     * Asked of the name and not of the value: a secret looks like any other text, and a rule that
     * guessed from values would refuse ordinary parameters while letting a plainly named one
     * through.
     * </p>
     *
     * @param name the parameter name.
     * @return true when the name reads like one that carries a secret
     */
    private static boolean readsLikeASecret(String name)
    {
        if (name == null)
        {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return SECRET_FIELDS.contains(lower) || lower.contains("pwd") //$NON-NLS-1$
            || lower.contains("pass") || lower.contains("пароль"); //$NON-NLS-1$ //$NON-NLS-2$
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

    /**
     * {@code 1cv8 ENTERPRISE /IBConnectionString "<conn>" /DisableStartupMessages
     * /Execute <epf> /C "StartFeaturePlayer;VAParams=<params>"} (thick client;
     * {@code /DisableStartupMessages} avoids the "update configuration?" modal).
     */
    private static List<String> buildCommand(File exe, String connectionString, File epf,
        File paramsFile, boolean asTestManager)
    {
        List<String> c = new ArrayList<>();
        c.add(exe.getAbsolutePath());
        c.add("ENTERPRISE"); //$NON-NLS-1$
        c.add("/IBConnectionString"); //$NON-NLS-1$
        c.add(connectionString);
        c.add("/DisableStartupMessages"); //$NON-NLS-1$
        if (asTestManager)
        {
            // The UI-testing types exist only in a client started this way. Without it a step
            // that drives a form answers "Тип не определен" and the scenario stops there.
            c.add("/TESTMANAGER"); //$NON-NLS-1$
        }
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
            // No key to name: the run holding the slot either finished between the check and this
            // message, or is a synchronous call that was never handed one. cancel takes a runKey
            // and nothing else, so advising it here would send the caller after a key that does
            // not exist.
            why.append(". The run holding the slot carries no runKey - it was started " //$NON-NLS-1$
                + "synchronously, or it finished while this answer was being written - so " //$NON-NLS-1$
                + "cancel cannot address it. Try again; a synchronous run ends with its " //$NON-NLS-1$
                + "client."); //$NON-NLS-1$
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
