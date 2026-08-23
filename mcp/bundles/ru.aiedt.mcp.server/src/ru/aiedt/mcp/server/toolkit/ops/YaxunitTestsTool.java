/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.LinkedHashMap;
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
import ru.aiedt.mcp.server.support.ToolGate;
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
 *   <li>{@code updateBeforeLaunch=true} (default) - syncs the infobase before
 *       launching, avoiding the "Update configuration?" modal blocking the
 *       headless run (uses {@link ru.aiedt.mcp.server.support.ApplicationUpdater})</li>
 *   <li>Pending JSON shape (run mode): {@code {status:Pending, runKey, reportDir,
 *       junitXml, hint}}</li>
 *   <li>0-tests hint: when JUnit XML reports zero suites/cases, the markdown
 *       body explains the three usual causes and points at {@code help=writing}</li>
 *   <li>Filter parity: extensions, modules, tests, suites, tags,
 *       contexts (Server/Client/ExternalConnection)</li>
 * </ul>
 *
 * <p>Implementation strategy: the tool delegates to the existing
 * {@link YaxunitTestRunner} / {@link YaxunitDebugRunner} which carry
 * the heavy lifting (launch tracking, JUnit parsing, report formatting).
 * The unified surface adds: help dispatch, mode routing, the optional
 * {@code updateBeforeLaunch} pre-step. Old tools remain registered as
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
            + "Filters: extensions, modules, tests, suites, tags, contexts (CSV). " //$NON-NLS-1$
            + "updateBeforeLaunch=true (default) auto-syncs the infobase before launching. " //$NON-NLS-1$
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
            .stringProperty("suites", "CSV: suite names.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("tags", "CSV: tag names.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("contexts", //$NON-NLS-1$
                "CSV: contexts (Server/Client/ExternalConnection).") //$NON-NLS-1$
            .stringProperty("timeoutSeconds", //$NON-NLS-1$
                "Polling window in seconds (default 60). Legacy alias: timeout.") //$NON-NLS-1$
            .stringProperty("updateBeforeLaunch", //$NON-NLS-1$
                "Default true. Set false to skip pre-launch infobase sync.")
            .booleanProperty("installYaxunit", //$NON-NLS-1$
                "Default false. When true, if the YAxUnit engine extension is not yet " //$NON-NLS-1$
                    + "installed in the infobase, its latest release is downloaded from " //$NON-NLS-1$
                    + "GitHub (bia-technologies/yaxunit) and installed as 'YAxUnit' before " //$NON-NLS-1$
                    + "running. When already installed nothing is downloaded. The install " //$NON-NLS-1$
                    + "outcome is reported in the response as 'installYaxunit'. Needs network " //$NON-NLS-1$
                    + "access to GitHub and a resolvable thick-client runtime + stored IB " //$NON-NLS-1$
                    + "credentials.") //$NON-NLS-1$
            .stringProperty("yaxunitRepo", //$NON-NLS-1$
                "GitHub repo to pull YAxUnit from for installYaxunit, as 'owner/repo'. " //$NON-NLS-1$
                    + "Default 'bia-technologies/yaxunit'.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
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

        // updateBeforeLaunch handling - delegate already triggers it when
        // applicable; here we just record the requested behavior for the
        // structured response. Default true matches the upstream surface.
        boolean updateBeforeLaunch = JsonUtils.extractBooleanArgument(params, "updateBeforeLaunch", true);
        if (updateBeforeLaunch)
        {
            // Inject into params so downstream tool can act on it.
            // Existing DebugSessionStarter already supports this flag; Run tool
            // gains it via a helper method in 1.40.
            Map<String, String> forwarded = new LinkedHashMap<>(params);
            forwarded.putIfAbsent("updateBeforeLaunch", "true");
            params = forwarded;
        }

        // installYaxunit pre-step: ensure the YAxUnit engine extension is in the
        // infobase before launching. Idempotent - if already present, nothing is
        // downloaded. A hard failure (bad repo, download failed, install failed)
        // aborts the launch with the error; the outcome otherwise is merged into the
        // test-run response as 'installYaxunit'.
        boolean installYaxunit = JsonUtils.extractBooleanArgument(params, "installYaxunit", false); //$NON-NLS-1$
        String installSummary = null;
        if (installYaxunit)
        {
            installSummary = ensureYaxunitInstalled(params);
            if (installSummary == null || installSummary.startsWith("ERROR:")) //$NON-NLS-1$
            {
                String msg = installSummary == null
                    ? "installYaxunit failed for an unknown reason" //$NON-NLS-1$
                    : installSummary.substring("ERROR:".length()); //$NON-NLS-1$
                return ToolResult.error(msg)
                    .put("operation", NAME) //$NON-NLS-1$
                    .put("installYaxunit", "failed") //$NON-NLS-1$ //$NON-NLS-2$
                    .toJson();
            }
        }

        String result;
        switch (mode)
        {
            case "run":
                result = new YaxunitTestRunner().execute(params);
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
        if (installSummary != null)
        {
            result = mergeStringField(result, "installYaxunit", installSummary); //$NON-NLS-1$
        }
        return asJsonEnvelope(result);
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
    private static String asJsonEnvelope(String result)
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
        return ToolResult.success()
            .put("operation", NAME) //$NON-NLS-1$
            .put("output", result) //$NON-NLS-1$
            .toJson();
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
     * Ensures the YAxUnit engine extension is installed in the project's infobase. When
     * already present, returns a short note and downloads nothing. Otherwise resolves the
     * latest {@code YAxUnit*.cfe} from the GitHub repo and installs it as {@code YAxUnit}.
     *
     * @return a human-readable outcome (never null on success), or {@code "ERROR:..."} on a
     *     hard failure that should abort the launch
     */
    private static String ensureYaxunitInstalled(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            // projectName may be derived downstream from launchConfigurationName; without it
            // we cannot target the infobase, so surface the requirement.
            // installYaxunit runs before the launch-config resolution the downstream tool
            // does, so it needs an explicit projectName to target the infobase. If only a
            // launchConfigurationName was given, ask for projectName too.
            return "ERROR:installYaxunit requires projectName (the infobase target). When " //$NON-NLS-1$
                + "using only launchConfigurationName, also pass projectName so the engine " //$NON-NLS-1$
                + "can be installed before the run."; //$NON-NLS-1$
        }
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        String repo = JsonUtils.extractStringArgument(params, "yaxunitRepo"); //$NON-NLS-1$
        if (repo == null || repo.trim().isEmpty())
        {
            repo = "bia-technologies/yaxunit"; //$NON-NLS-1$
        }

        // 1. Skip the download when the engine is already installed.
        try
        {
            BmInfobaseExtensionHelper.ListResult listed =
                BmInfobaseExtensionHelper.listExtensions(projectName, applicationId);
            if (listed.ok && listed.extensions != null)
            {
                for (String name : listed.extensions)
                {
                    if (name != null && "YAxUnit".equalsIgnoreCase(name.trim())) //$NON-NLS-1$
                    {
                        return "YAxUnit already installed - no download needed."; //$NON-NLS-1$
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

        // 2. Resolve the latest engine .cfe asset URL from GitHub.
        GitHubReleaseResolver.Asset asset;
        try
        {
            asset = GitHubReleaseResolver.resolveLatestCfe(repo, "YAxUnit"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            return "ERROR:Could not resolve the latest YAxUnit release from " + repo //$NON-NLS-1$
                + ": " + TextSuggest.safeMessage(e); //$NON-NLS-1$
        }
        if (asset == null)
        {
            return "ERROR:No YAxUnit*.cfe asset found in the latest release of " + repo //$NON-NLS-1$
                + " (the asset-name prefix 'YAxUnit' did not match)."; //$NON-NLS-1$
        }

        // 3. Install (also applies to the database). Idempotent: a repeat call updates in place.
        BmInfobaseExtensionHelper.InstallResult installed = BmInfobaseExtensionHelper.installExtension(
            projectName, applicationId, "YAxUnit", asset.url, true); //$NON-NLS-1$
        if (!installed.ok)
        {
            return "ERROR:Install of YAxUnit failed: " + installed.error; //$NON-NLS-1$
        }
        return "Installed " + asset.name + " from " + repo + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
