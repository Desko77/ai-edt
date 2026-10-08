/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BmInfobaseExtensionHelper;
import ru.aiedt.mcp.server.support.ToolGate;
import ru.aiedt.mcp.server.support.GitHubReleaseResolver;
import ru.aiedt.mcp.server.support.TextSuggest;
import ru.aiedt.mcp.server.support.YaxunitHelp;

/**
 * 1.40 - Unified YAxUnit test runner. Replaces the legacy two-tool surface
 * ({@code run_yaxunit_tests} + {@code debug_yaxunit_tests}) with a single
 * {@code yaxunit_tests} entry point matching the unified API.
 * <p>
 * Modes:
 * <ul>
 *   <li>{@code mode=run} (default) - synchronous polling of an EDT runtime-client
 *       launch; returns Pending JSON when the timeout elapses; second call with
 *       same parameters fetches the JUnit report.</li>
 *   <li>{@code mode=debug} - launches in debug mode so that breakpoints set via
 *       {@code launch_debugger}/{@code set_breakpoint} fire normally; agent
 *       inspects state and resumes via the debug tools.</li>
 * </ul>
 *
 * <p>UX features:
 * <ul>
 *   <li>{@code help=topics|writing|assertions|setup|events|advanced} - returns
 *       a Markdown topic from {@link YaxunitHelp} without launching anything</li>
 *   <li>{@code updateBeforeLaunch=true} (default) - updates the infobase before
 *       launching, avoiding the "Update configuration?" modal blocking the
 *       headless run (uses {@link ru.aiedt.mcp.server.support.ApplicationUpdater}
 *       through {@code DebugSessionStarter}); an update that does not finish
 *       refuses the launch and nothing is started</li>
 *   <li>Pending JSON shape (run mode): {@code {status:Pending, runKey, reportDir,
 *       junitXml, hint}}</li>
 *   <li>0-tests hint: when JUnit XML reports zero suites/cases, the markdown
 *       body explains the three usual causes and points at {@code help=writing}</li>
 *   <li>Filters: extensions, modules, tests - the three the launch
 *       configuration's filter carries. A call naming suites, tags or contexts
 *       is refused, because the launch would otherwise run every test in the
 *       suite under a filter the caller believes is narrowing it</li>
 *   <li>{@code reuseRecent=true} - take the report of a run that finished
 *       within the last 5 minutes instead of starting a new one. Defaults to
 *       false: a second call after a delivered report runs the tests again, so
 *       an edit-and-rerun loop cannot read a stale report as its own result</li>
 *   <li>{@code installYaxunit=true} with {@code yaxunitUnsafeMode} (default true) - after the
 *       engine is in the infobase, its safe mode and unsafe action protection are read through
 *       the designer session, both lowered in one write when either is on, and confirmed by a
 *       read-back; a flag that stayed on is an error naming the actual value of each and the
 *       tests are not started. {@code yaxunitUnsafeMode=false} leaves the flags untouched. The
 *       step runs on the already-installed path as well, and the whole pre-step is gated on
 *       {@code install_extension}</li>
 * </ul>
 *
 * <p>Implementation strategy: the tool delegates to the existing
 * {@link YaxunitTestRunner} / {@link YaxunitDebugRunner} which carry
 * the heavy lifting (launch tracking, JUnit parsing, report formatting).
 * The unified surface adds: help dispatch and mode routing, and it forwards
 * {@code updateBeforeLaunch} and {@code reuseRecent} unchanged - both modes read
 * them themselves, so what the delegate decides is what happens. Old tools remain registered as
 * deprecated aliases until 2.0 to preserve skill compatibility.
 */
public class YaxunitTestsTool implements IMcpTool
{
    public static final String NAME = "yaxunit_tests"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Unified YAxUnit test runner. " //$NON-NLS-1$
            + "Pass mode=run|debug to switch between synchronous polling and " //$NON-NLS-1$
            + "breakpoint-aware debug. Pass help=<topic> to load built-in YAxUnit guidance " //$NON-NLS-1$
            + "(topics/writing/assertions/setup/events/advanced). " //$NON-NLS-1$
            + "Filters: extensions, modules, tests (CSV); the launch configuration's filter takes "
            + "those three only, so any other filter argument is refused rather than ignored. " //$NON-NLS-1$
            + "updateBeforeLaunch=true (default) updates the infobase before launching and refuses "
            + "the launch when the update does not finish. " //$NON-NLS-1$
            + "reuseRecent=true returns the report of a run finished within the last 5 minutes "
            + "instead of running the tests again (default false). " //$NON-NLS-1$
            + "installYaxunit=true installs the engine when needed and, by default, lowers and " //$NON-NLS-1$
            + "confirms its safe-mode and unsafe-action-protection flags before the run; set " //$NON-NLS-1$
            + "yaxunitUnsafeMode=false to leave both flags untouched. " //$NON-NLS-1$
            + "run_yaxunit_tests (mode=run) and debug_yaxunit_tests (mode=debug) are back-compat " //$NON-NLS-1$
            + "aliases of this facade; prefer it for new prompts."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("mode", "Mode: run (default) or debug.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("help", //$NON-NLS-1$
                "Help topic: topics, writing, assertions, setup, events, advanced. " //$NON-NLS-1$
                + "When set, other parameters are ignored.")
            .stringProperty("launchConfigurationName", "EDT Run Configuration name (preferred).") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("projectName", "Project name (alternative to launchConfigurationName).") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", "Application ID for the project.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("extensions", "CSV: extension names to run.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("modules", "CSV: common module names with tests.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("tests", "CSV: test FQNs (Module.Method).") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("timeoutSeconds", //$NON-NLS-1$
                "Polling window in seconds (default 60). Legacy alias: timeout.") //$NON-NLS-1$
            .booleanProperty("updateBeforeLaunch", //$NON-NLS-1$
                "Default true. Set false to skip pre-launch infobase sync. Under a preset that " //$NON-NLS-1$
                    + "disabled update_database an omitted argument launches without updating " //$NON-NLS-1$
                    + "(the answer carries databaseUpdate=SKIPPED_BY_PRESET); an " //$NON-NLS-1$
                    + "explicit true is refused.") //$NON-NLS-1$
            .booleanProperty("reuseRecent", //$NON-NLS-1$
                "Default false. Set true to take the report of a run that finished within the " //$NON-NLS-1$
                    + "last 5 minutes instead of running the tests again.") //$NON-NLS-1$
            .booleanProperty("installYaxunit", //$NON-NLS-1$
                "Default false. When true, if the YAxUnit engine extension is not yet " //$NON-NLS-1$
                    + "installed in the infobase, its latest release is downloaded from " //$NON-NLS-1$
                    + "GitHub (bia-technologies/yaxunit) and installed as 'YAxUnit' before " //$NON-NLS-1$
                    + "running. When already installed nothing is downloaded. The install " //$NON-NLS-1$
                    + "outcome is reported in the response as 'installYaxunit'. Needs network " //$NON-NLS-1$
                    + "access to GitHub and a resolvable thick-client runtime + stored IB " //$NON-NLS-1$
                    + "credentials.") //$NON-NLS-1$
            .booleanProperty("yaxunitUnsafeMode", //$NON-NLS-1$
                "Default true; read only with installYaxunit=true. On new and already-installed " //$NON-NLS-1$
                    + "paths, reads both YAxUnit safety flags, lowers both in one write when " //$NON-NLS-1$
                    + "either is on, and confirms them before launch. A mismatch aborts with " //$NON-NLS-1$
                    + "both actual values. False leaves the flags unread and unchanged.") //$NON-NLS-1$
            .stringProperty("yaxunitRepo", //$NON-NLS-1$
                "GitHub repo to pull YAxUnit from for installYaxunit, as 'owner/repo'. " //$NON-NLS-1$
                    + "Default 'bia-technologies/yaxunit'.") //$NON-NLS-1$
            .build();
    }

    /**
     * Where a call goes when no mode is named.
     * <p>
     * The selector here is {@code mode}, not {@code operation}, and leaving it out does not mean
     * nothing happens: the call runs the tests. A rule that read a missing selector as "no work"
     * would take the guard off exactly the call that launches a platform client.
     * </p>
     *
     * @param arguments the call arguments
     * @return the tool the call reaches
     */
    @Override
    public String routesTo(Map<String, String> arguments)
    {
        String mode = JsonUtils.extractStringArgument(arguments, "mode"); //$NON-NLS-1$
        if (mode != null && "debug".equals(mode.trim().toLowerCase(java.util.Locale.ROOT))) //$NON-NLS-1$
        {
            return "debug_yaxunit_tests"; //$NON-NLS-1$
        }
        return "run_yaxunit_tests"; //$NON-NLS-1$
    }

    /**
     * The install door of the {@code installYaxunit} pre-step.
     * <p>
     * The facade reads and runs under Debug &amp; Test, and so do both of its folded runner names -
     * yet the pre-step installs an engine extension through the same write
     * {@code install_extension} performs, and that name is one the test preset disables. The step
     * asks {@code ToolGate.gateIfPresetDisabled} about it before its first write, which is what
     * keeps the preset's promise here.
     * </p>
     *
     * @return the standalone name gating this tool's install pre-step
     */
    @Override
    public List<String> getGatedWriteNames()
    {
        return List.of("install_extension"); //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        return executeWithInstallSteps(params, PRODUCTION_STEPS);
    }

    /**
     * The call with the install pre-step's doors given from outside.
     * <p>
     * The doors decide nothing - the probe, the order the steps run in and what a flags failure
     * does to the launch all live in here - so a test can hold that part without an infobase,
     * while the production doors keep doing the real work.
     * </p>
     *
     * @param params the call arguments
     * @param steps the doors the install pre-step goes through
     * @return the answer of this tool
     */
    String executeWithInstallSteps(Map<String, String> params, InstallSteps steps)
    {
        // Help dispatch first - other parameters ignored when help is set
        String helpTopic = JsonUtils.extractStringArgument(params, "help"); //$NON-NLS-1$
        if (helpTopic != null && !helpTopic.isEmpty())
        {
            return renderHelp(helpTopic);
        }

        // Mode dispatch
        String mode = JsonUtils.extractStringArgument(params, "mode"); //$NON-NLS-1$
        if (mode == null || mode.isEmpty())
        {
            mode = "run";
        }
        mode = mode.toLowerCase().trim();
        // Validate mode BEFORE any mutating pre-step (installYaxunit) so a malformed
        // mode does not download/install the engine and only then get rejected.
        if (!"run".equals(mode) && !"debug".equals(mode)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return ToolResult.error("Unknown mode: '" + mode + "'. Use 'run' or 'debug'.") //$NON-NLS-1$ //$NON-NLS-2$
                .put("operation", NAME) //$NON-NLS-1$
                .toJson();
        }

        // Preset gate, for BOTH modes and before the install pre-step. Each mode reaches a tool of
        // its own - debug_yaxunit_tests through the debugger, run_yaxunit_tests through the runner -
        // and neither is named after the mode, so the mode is translated before the preset is asked.
        // Gate on preset membership rather than gateOrNull, so this stays correct once the standalone
        // aliases are retired and the names live only in the group table.
        //
        // Placed here rather than at the dispatch below because the install pre-step writes to the
        // infobase: gating after it would let a switched-off call install or update the engine and
        // only then be refused, which is a write the preset exists to prevent.
        String folded = "debug".equals(mode) //$NON-NLS-1$
            ? YaxunitDebugRunner.NAME : YaxunitTestRunner.NAME;
        String presetGate = ToolGate.gateIfPresetDisabled(folded);
        if (presetGate != null)
        {
            return ToolResult.error(presetGate).put("operation", NAME).put("mode", mode).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // updateBeforeLaunch is passed through as it arrived: both delegates read it themselves
        // with the same default, and the update happens inside the runner that launches, not here.

        // installYaxunit pre-step: ensure the YAxUnit engine extension is in the
        // infobase before launching. Idempotent - if already present, nothing is
        // downloaded. A hard failure (bad repo, download failed, install failed)
        // aborts the launch with the error; the outcome otherwise is merged into the
        // test-run response as 'installYaxunit'.
        boolean installYaxunit = JsonUtils.extractBooleanArgument(params, "installYaxunit", false); //$NON-NLS-1$
        boolean unsafeMode = JsonUtils.extractBooleanArgument(params, "yaxunitUnsafeMode", true); //$NON-NLS-1$
        String installSummary = null;
        if (installYaxunit)
        {
            // The pre-step writes the infobase through the very call install_extension
            // makes, and the preset gate above checks only the runner names - which a
            // test preset keeps on. First action of the branch, then: ask the install
            // door, so a preset that blocks writing refuses before the infobase is
            // touched instead of after the engine is already in it.
            String installGate = ToolGate.gateIfPresetDisabled("install_extension"); //$NON-NLS-1$
            if (installGate != null)
            {
                return ToolResult.error(installGate)
                    .put("operation", NAME) //$NON-NLS-1$
                    .put("installYaxunit", "blocked by preset") //$NON-NLS-1$ //$NON-NLS-2$
                    .toJson();
            }
            InstallOutcome installed = ensureYaxunitInstalled(params, unsafeMode, steps);
            if (!installed.isOk())
            {
                // The flags as the last read saw them travel with the refusal: a caller has to
                // see WHICH flag stayed on, not only that one did - the two are lowered by
                // different hands in the Configurator.
                ToolResult refusal = ToolResult.error(installed.error)
                    .put("operation", NAME) //$NON-NLS-1$
                    .put("installYaxunit", "failed"); //$NON-NLS-1$ //$NON-NLS-2$
                if (installed.flags != null)
                {
                    refusal.put("safeMode", BmInfobaseExtensionHelper.flagWord(installed.flags.safeMode)); //$NON-NLS-1$
                    refusal.put("unsafeActionProtection", //$NON-NLS-1$
                        BmInfobaseExtensionHelper.flagWord(installed.flags.unsafeActionProtection));
                }
                return refusal.toJson();
            }
            installSummary = installed.summary;
        }

        String result;
        switch (mode)
        {
            case "run":
                // The runner's own text. The envelope below wraps it; the alias wraps the same
                // text in its execute.
                result = new YaxunitTestRunner().dispatch(params);
                break;
            case "debug":
                result = new YaxunitDebugRunner().execute(params);
                break;
            default:
                return ToolResult.error("Unknown mode: '" + mode //$NON-NLS-1$
                    + "'. Use 'run' or 'debug'.")
                    .put("operation", NAME)
                    .toJson();
        }
        // Decide whether the delegate answered with success or an error before adding the install
        // summary. Prepending the summary to runner markdown would hide its leading **Error:** and
        // turn a failed launch into success:true merely because the pre-step itself succeeded.
        result = asJsonEnvelope(result);
        if (installSummary != null)
        {
            result = mergeStringField(result, "installYaxunit", installSummary); //$NON-NLS-1$
        }
        return result;
    }

    /**
     * Puts a delegate's answer into this tool's declared shape.
     * <p>
     * <b>The runners answer markdown and this tool declares {@link ResponseType#JSON}.</b> Handed
     * over unchanged the router parses markdown as JSON and the caller gets
     * MalformedJsonException instead of an answer. Measured: {@code yaxunit_tests} called with no
     * arguments failed that way.
     * </p>
     * <p>
     * Nothing working is disturbed by wrapping: the markdown paths did not reach a caller at all
     * before, and the paths that already answer a JSON object are passed through untouched. The
     * envelope is the one {@link #renderHelp} has always used, whose javadoc states the intent -
     * markdown inside JSON so clients can consume both.
     * </p>
     *
     * @param result what the delegate returned.
     * @return the same JSON object, or the text wrapped in one
     */
    static String asJsonEnvelope(String result)
    {
        if (result == null || result.isEmpty())
        {
            return result;
        }
        try
        {
            JsonElement parsed = JsonParser.parseString(result);
            if (parsed != null && parsed.isJsonObject())
            {
                return result;
            }
        }
        catch (Exception notJson)
        {
            // Markdown, which is the ordinary case for the runners. Fall through and wrap it.
        }
        if (result.stripLeading().startsWith(RUNNER_ERROR))
        {
            // The runners report a refusal as markdown beginning with this, and wrapping every
            // non-JSON answer as a success would hand a structured client success:true over a run
            // that never started - a bare call with no projectName among them. The envelope has to
            // carry the outcome, not only the text.
            return ToolResult.error(result)
                .put("operation", NAME) //$NON-NLS-1$
                .toJson();
        }
        ToolResult answered = ToolResult.success()
            .put("operation", NAME) //$NON-NLS-1$
            .put("output", result); //$NON-NLS-1$
        if (YaxunitTestRunner.isCachedAnswer(result))
        {
            // The runner answers markdown, so the one place it can say "this report is a previous
            // run's" is the text. Said again as a field, because a client reading fields rather
            // than prose would otherwise read a reused report as this call's result.
            answered.put("cached", true); //$NON-NLS-1$
        }
        if (YaxunitTestRunner.isUpdateSkippedAnswer(result))
        {
            // The pending answer is markdown too, and the preset-dropped update it names in its
            // text is said again as the same field pair a finished answer carries - set by the
            // same putDatabaseUpdate, so the two answers cannot drift apart.
            DebugSessionStarter.putDatabaseUpdate(answered, null, true);
        }
        return answered.toJson();
    }

    /**
     * How {@code YaxunitTestRunner} opens a refusal.
     * <p>
     * Only the run-mode runner answers markdown; {@code YaxunitDebugRunner} declares JSON and
     * builds JSON, so it passes through the check above untouched.
     * </p>
     */
    private static final String RUNNER_ERROR = "**Error:**"; //$NON-NLS-1$

    /**
     * The outcome of the install pre-step.
     */
    static final class InstallOutcome
    {
        /** The success summary merged into the test-run response; {@code null} on failure. */
        final String summary;

        /** The failure text; {@code null} on success. */
        final String error;

        /** The engine's safety flags as the last read saw them, when the flags step ran. */
        final BmInfobaseExtensionHelper.ExtensionFlags flags;

        InstallOutcome(String summary, String error, BmInfobaseExtensionHelper.ExtensionFlags flags)
        {
            this.summary = summary;
            this.error = error;
            this.flags = flags;
        }

        /**
         * A success carrying its summary.
         *
         * @param summary what the response tells about the install
         * @return the outcome
         */
        static InstallOutcome done(String summary)
        {
            return new InstallOutcome(summary, null, null);
        }

        /**
         * A failure carrying its text and whatever the last read of the flags saw.
         *
         * @param error why the pre-step did not get through
         * @param flags the flags as last read; may be {@code null}
         * @return the outcome
         */
        static InstallOutcome failed(String error, BmInfobaseExtensionHelper.ExtensionFlags flags)
        {
            return new InstallOutcome(null, error, flags);
        }

        /**
         * @return whether the pre-step got through
         */
        boolean isOk()
        {
            return error == null;
        }
    }

    /**
     * The external steps the install pre-step is made of, one door each.
     * <p>
     * The pre-step's own logic - the already-installed probe, the order the steps run in, what a
     * flags failure does to the launch - is decided in {@link #ensureYaxunitInstalled}; what the
     * doors do against a live infobase lives behind them. The seams are what lets a test hold the
     * order without one, the same way the pre-launch update step is held.
     * </p>
     */
    interface InstallSteps
    {
        /**
         * Lists the extension names the infobase holds.
         *
         * @param projectName the project that owns the infobase
         * @param applicationId the infobase application id; may be {@code null}
         * @return the listing
         */
        BmInfobaseExtensionHelper.ListResult listExtensions(String projectName, String applicationId);

        /**
         * Resolves the latest engine {@code .cfe} from the repo and installs it.
         *
         * @param projectName the project that owns the infobase
         * @param applicationId the infobase application id; may be {@code null}
         * @param repo the GitHub repo to pull from, {@code owner/repo}
         * @return the install outcome
         */
        InstallOutcome installEngine(String projectName, String applicationId, String repo);

        /**
         * Reads the engine extension's safety flags, lowers both in one write when either is on,
         * and confirms them by reading back.
         *
         * @param projectName the project that owns the infobase
         * @param applicationId the infobase application id; may be {@code null}
         * @return the flags outcome
         */
        BmInfobaseExtensionHelper.ExtensionFlagsResult lowerEngineFlags(String projectName,
            String applicationId);
    }

    /**
     * The production steps: each door is the helper that performs it against a live infobase.
     */
    private static final InstallSteps PRODUCTION_STEPS = new InstallSteps()
    {
        @Override
        public BmInfobaseExtensionHelper.ListResult listExtensions(String projectName,
            String applicationId)
        {
            return BmInfobaseExtensionHelper.listExtensions(projectName, applicationId);
        }

        @Override
        public InstallOutcome installEngine(String projectName, String applicationId, String repo)
        {
            GitHubReleaseResolver.Asset asset;
            try
            {
                asset = GitHubReleaseResolver.resolveLatestCfe(repo, "YAxUnit"); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                return InstallOutcome.failed("Could not resolve the latest YAxUnit release from " //$NON-NLS-1$
                    + repo + ": " + TextSuggest.safeMessage(e), null); //$NON-NLS-1$
            }
            if (asset == null)
            {
                return InstallOutcome.failed("No YAxUnit*.cfe asset found in the latest release of " //$NON-NLS-1$
                    + repo + " (the asset-name prefix 'YAxUnit' did not match).", null); //$NON-NLS-1$
            }
            // Install and apply to the database. Idempotent: a repeat call updates in place.
            BmInfobaseExtensionHelper.InstallResult installed = BmInfobaseExtensionHelper
                .installExtension(projectName, applicationId, "YAxUnit", asset.url, true); //$NON-NLS-1$
            if (!installed.ok)
            {
                return InstallOutcome.failed("Install of YAxUnit failed: " + installed.error, null); //$NON-NLS-1$
            }
            return InstallOutcome.done("Installed " + asset.name + " from " + repo + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        @Override
        public BmInfobaseExtensionHelper.ExtensionFlagsResult lowerEngineFlags(String projectName,
            String applicationId)
        {
            return BmInfobaseExtensionHelper.ensureExtensionUnsafeFlags(projectName, applicationId,
                "YAxUnit"); //$NON-NLS-1$
        }
    };

    /**
     * Ensures the YAxUnit engine extension is installed in the project's infobase and, when
     * {@code unsafeMode} is on, that its two safety flags are off.
     * <p>
     * When the engine is already present nothing is downloaded - but the flags are still read and,
     * when either is on, lowered and confirmed, on the same terms as after a fresh install: the
     * engine executes no tests while either flag is on, whatever installed it. The flags step runs
     * after the install (which reconnects the infobase and its designer session); a flags failure
     * is a failure of the whole pre-step, so the launch it precedes does not start.
     * </p>
     *
     * @param params the call arguments
     * @param unsafeMode whether the engine's safety flags are read, lowered and confirmed
     * @param steps the external doors the pre-step goes through
     * @return the outcome, its summary or its error never both
     */
    static InstallOutcome ensureYaxunitInstalled(Map<String, String> params, boolean unsafeMode,
        InstallSteps steps)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            // projectName may be derived downstream from launchConfigurationName; without it
            // we cannot target the infobase, so surface the requirement.
            // installYaxunit runs before the launch-config resolution the downstream tool
            // does, so it needs an explicit projectName to target the infobase. If only a
            // launchConfigurationName was given, ask for projectName too.
            return InstallOutcome.failed("installYaxunit requires projectName (the infobase " //$NON-NLS-1$
                + "target). When using only launchConfigurationName, also pass projectName so " //$NON-NLS-1$
                + "the engine can be installed before the run.", null); //$NON-NLS-1$
        }
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        String repo = JsonUtils.extractStringArgument(params, "yaxunitRepo"); //$NON-NLS-1$
        if (repo == null || repo.trim().isEmpty())
        {
            repo = "bia-technologies/yaxunit"; //$NON-NLS-1$
        }

        // 1. Skip the download when the engine is already installed. Only the probe is guarded:
        // the flags step below is not part of it, and a failure there must not be read as a
        // probe that failed - that would download and reinstall an engine that is in place.
        boolean alreadyInstalled = false;
        try
        {
            BmInfobaseExtensionHelper.ListResult listed =
                steps.listExtensions(projectName, applicationId);
            if (listed.ok && listed.extensions != null)
            {
                for (String name : listed.extensions)
                {
                    if (name != null && "YAxUnit".equalsIgnoreCase(name.trim())) //$NON-NLS-1$
                    {
                        alreadyInstalled = true;
                    }
                }
            }
            // list failed (IB locked, no credentials, etc.): fall through and attempt the
            // install - installExtension updates an existing extension in place, so a
            // redundant install is safe.
        }
        catch (Exception e)
        {
            Activator.logWarning("installYaxunit: listExtensions probe failed, attempting " //$NON-NLS-1$
                + "install anyway: " + TextSuggest.safeMessage(e)); //$NON-NLS-1$
        }
        if (alreadyInstalled)
        {
            return withUnsafeMode("YAxUnit already installed - no download needed.", //$NON-NLS-1$
                unsafeMode, projectName, applicationId, steps);
        }

        // 2. Install (also applies to the database), then see to the flags.
        InstallOutcome installed = steps.installEngine(projectName, applicationId, repo);
        if (!installed.isOk())
        {
            return installed;
        }
        return withUnsafeMode(installed.summary, unsafeMode, projectName, applicationId, steps);
    }

    /**
     * Runs the flags step of the pre-step when {@code unsafeMode} asks for it and appends what it
     * confirmed to the summary.
     *
     * @param summary the summary the install itself produced
     * @param unsafeMode whether the flags step runs
     * @param projectName the project that owns the infobase
     * @param applicationId the infobase application id; may be {@code null}
     * @param steps the external doors the pre-step goes through
     * @return the outcome; a flags failure carries the flags as the last read saw them
     */
    private static InstallOutcome withUnsafeMode(String summary, boolean unsafeMode,
        String projectName, String applicationId, InstallSteps steps)
    {
        if (!unsafeMode)
        {
            // The flags are neither read nor written: the engine keeps whatever it has, and
            // the summary says nothing about flags it did not look at.
            return InstallOutcome.done(summary);
        }
        BmInfobaseExtensionHelper.ExtensionFlagsResult flags;
        try
        {
            flags = steps.lowerEngineFlags(projectName, applicationId);
        }
        catch (RuntimeException failure)
        {
            // The step answers its own refusals; what it throws is a failure nobody confirmed the
            // flags after, so the tests do not launch and the answer names it.
            return InstallOutcome.failed("the extension safety flags could not be read or lowered: " //$NON-NLS-1$
                + TextSuggest.safeMessage(failure), null);
        }
        if (!flags.ok)
        {
            return InstallOutcome.failed(flags.error, flags.flags);
        }
        String base = summary.endsWith(".") ? summary.substring(0, summary.length() - 1) //$NON-NLS-1$
            : summary;
        return InstallOutcome.done(base + "; safe mode off; unsafe action protection off."); //$NON-NLS-1$
    }

    /**
     * Merges a single string field into a JSON response string. When the response is not a
     * JSON object (or parsing fails) the original is returned unchanged so a merge glitch
     * never corrupts the underlying tool output.
     */
    private static String mergeStringField(String jsonResponse, String key, String value)
    {
        // YaxunitTestRunner answers Markdown (not a JSON object) for pending/completed
        // runs, so a JSON-only merge would silently drop the install outcome. When the
        // response is a JSON object, add the field; otherwise prepend a labelled line so
        // the install outcome is always surfaced (never let a merge glitch mask the result).
        if (jsonResponse == null || jsonResponse.isEmpty())
        {
            return jsonResponse;
        }
        try
        {
            JsonElement parsed = JsonParser.parseString(jsonResponse);
            if (parsed != null && parsed.isJsonObject())
            {
                JsonObject obj = parsed.getAsJsonObject();
                obj.addProperty(key, value);
                return obj.toString();
            }
        }
        catch (Exception ignored)
        {
            // Not JSON - fall through to the text prepend.
        }
        return key + ": " + value + "\n\n" + jsonResponse; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Renders a help topic via {@link YaxunitHelp}. Returns markdown wrapped
     * in a JSON envelope so MCP clients can consume both formats.
     */
    private String renderHelp(String topic)
    {
        String body = YaxunitHelp.getTopic(topic);
        if (body == null)
        {
            return ToolResult.error("Unknown help topic: '" + topic + "'.")
                .put("operation", NAME)
                .put("availableTopics", YaxunitHelp.availableTopics())
                .put("hint", "Use yaxunit_tests help=topics for the list of available topics.")
                .toJson();
        }
        return ToolResult.success()
            .put("operation", NAME)
            .put("status", "Help")
            .put("topic", topic.toLowerCase().trim())
            .put("body", body)
            .put("availableTopics", YaxunitHelp.availableTopics())
            .toJson();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }
}
