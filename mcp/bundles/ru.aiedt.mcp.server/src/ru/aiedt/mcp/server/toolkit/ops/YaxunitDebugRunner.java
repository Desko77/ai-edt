/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;

import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.GsonHolder;
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
 * Launches YAXUnit tests in DEBUG mode so breakpoints set via {@code set_breakpoint} fire when the
 * test runs. Returns immediately after the launch is queued; the caller follows up with
 * {@code wait_for_break}.
 */
public final class YaxunitDebugRunner implements IMcpTool
{
    public static final String NAME = "debug_yaxunit_tests"; //$NON-NLS-1$

    private static final String DESC = "Back-compat alias of `yaxunit_tests` `mode=debug`; prefer the facade for new prompts. " //$NON-NLS-1$
        + "Starts YAXUnit tests in DEBUG mode so that breakpoints trigger. " //$NON-NLS-1$
        + "Returns as soon as the launch is queued - follow up with wait_for_break " //$NON-NLS-1$
        + "to block until a breakpoint is reached, then inspect state via get_variables / " //$NON-NLS-1$
        + "evaluate_expression / step / resume. " //$NON-NLS-1$
        + "Narrow the tests filter to a single test method to keep the cycle predictable. " //$NON-NLS-1$
        + "Requires an existing 1C launch configuration with YAXUnit installed in the target infobase."; //$NON-NLS-1$

    private static final AtomicLong LAUNCH_COUNTER = new AtomicLong(0);

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
            .stringProperty("launchConfigurationName", //$NON-NLS-1$
                "Runtime-client launch configuration name, matched exactly (preferred; obtain it from list_configurations)") //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "Name of the EDT project (needed only when launchConfigurationName is not given)") //$NON-NLS-1$
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application identifier as returned by get_applications (needed only when launchConfigurationName is not given)") //$NON-NLS-1$
            .stringProperty("extensions", //$NON-NLS-1$
                "Comma-separated extension names to restrict which tests are run") //$NON-NLS-1$
            .stringProperty("modules", //$NON-NLS-1$
                "Comma-separated module names to restrict which tests are run") //$NON-NLS-1$
            .stringProperty("tests", //$NON-NLS-1$
                "Comma-separated test names, each given as Module.Method (best practice: target exactly one test)") //$NON-NLS-1$
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
        String configName = JsonUtils.extractStringArgument(params, "launchConfigurationName"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        String extensions = JsonUtils.extractStringArgument(params, "extensions"); //$NON-NLS-1$
        String modules = JsonUtils.extractStringArgument(params, "modules"); //$NON-NLS-1$
        String tests = JsonUtils.extractStringArgument(params, "tests"); //$NON-NLS-1$

        boolean hasName = configName != null && !configName.isEmpty();
        if (!hasName)
        {
            if (projectName == null || projectName.isEmpty())
            {
                return ToolResult.error("projectName must be supplied (or provide launchConfigurationName instead)").toJson(); //$NON-NLS-1$
            }
            if (applicationId == null || applicationId.isEmpty())
            {
                return ToolResult.error("applicationId must be supplied (or provide launchConfigurationName instead)").toJson(); //$NON-NLS-1$
            }
        }

        try
        {
            ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
            if (launchManager == null)
            {
                return ToolResult.error("Launch manager is unavailable").toJson(); //$NON-NLS-1$
            }

            ILaunchConfiguration matchingConfig =
                LaunchConfigAccess.resolveLaunchConfig(launchManager, configName, projectName, applicationId);
            if (matchingConfig == null)
            {
                return ToolResult
                    .error(hasName ? "No launch configuration found named '" + configName + "'" //$NON-NLS-1$ //$NON-NLS-2$
                        : "No runtime-client launch configuration exists for project '" + projectName //$NON-NLS-1$
                            + "' with application '" + applicationId //$NON-NLS-1$
                            + "'. Run list_configurations to see the available options.") //$NON-NLS-1$
                    .toJson();
            }

            if (!LaunchConfigAccess.LAUNCH_CONFIG_TYPE_ID
                .equals(LaunchConfigAccess.getConfigTypeId(matchingConfig)))
            {
                return ToolResult.error("The launch configuration '" + matchingConfig.getName() //$NON-NLS-1$
                    + "' is not a runtime-client configuration - YAXUnit tests need one.").toJson(); //$NON-NLS-1$
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
                return ToolResult.error("The launch configuration '" + matchingConfig.getName() //$NON-NLS-1$
                    + "' has no project attribute configured").toJson(); //$NON-NLS-1$
            }

            String notReady = ProjectStateGuard.checkReadyOrError(projectName);
            if (notReady != null)
            {
                return ToolResult.error(notReady).toJson();
            }

            IProject project = ProjectResolver.resolve(projectName);
            if (project == null)
            {
                return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
            }

            IApplicationManager appManager = Activator.getDefault().getApplicationManager();
            if (appManager == null)
            {
                return ToolResult.error("IApplicationManager service is unavailable").toJson(); //$NON-NLS-1$
            }

            if (applicationId != null && !applicationId.isEmpty())
            {
                try
                {
                    Optional<IApplication> appOpt = appManager.getApplication(project, applicationId);
                    if (!appOpt.isPresent())
                    {
                        return ToolResult.error("No application found: " + applicationId).toJson(); //$NON-NLS-1$
                    }
                }
                catch (ApplicationException e)
                {
                    return ToolResult.error("Could not validate application: " + e.getMessage()).toJson(); //$NON-NLS-1$
                }
            }

            Path reportDir = Paths.get(System.getProperty("java.io.tmpdir"), //$NON-NLS-1$
                "ai-edt-yaxunit-debug", //$NON-NLS-1$
                projectName + "-" + System.currentTimeMillis() + "-" + LAUNCH_COUNTER.getAndIncrement()); //$NON-NLS-1$ //$NON-NLS-2$
            Files.createDirectories(reportDir);
            Path paramsFile = reportDir.resolve("xUnitParams.json"); //$NON-NLS-1$
            Path junitFile = reportDir.resolve("junit.xml"); //$NON-NLS-1$
            String paramsJson = buildParamsJson(junitFile.toString(), extensions, modules, tests);
            Files.write(paramsFile, paramsJson.getBytes(StandardCharsets.UTF_8));

            DebugSessionBook.get().ensureListenerRegistered();

            ILaunchConfigurationWorkingCopy workingCopy = matchingConfig.getWorkingCopy();
            String startupOption = "RunUnitTests=" + paramsFile.toString(); //$NON-NLS-1$
            workingCopy.setAttribute(LaunchConfigAccess.ATTR_STARTUP_OPTION, startupOption);

            Activator.logInfo("Starting YAXUnit debug-mode launch: config=" + matchingConfig.getName() //$NON-NLS-1$
                + ", startup=" + startupOption); //$NON-NLS-1$

            try
            {
                workingCopy.launch(ILaunchManager.DEBUG_MODE, new NullProgressMonitor());
            }
            catch (Exception ex)
            {
                Activator.logError("YAXUnit debug-mode launch failed", ex); //$NON-NLS-1$
                return ToolResult.error("The launch failed: " + ex.getMessage()).toJson(); //$NON-NLS-1$
            }

            return ToolResult.success().put("launched", true) //$NON-NLS-1$
                .put("projectName", projectName) //$NON-NLS-1$
                .put("applicationId", applicationId) //$NON-NLS-1$
                .put("reportDir", reportDir.toString()) //$NON-NLS-1$
                .put("junitXml", junitFile.toString()) //$NON-NLS-1$
                .put("nextStep", "call wait_for_break using the same applicationId") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Unhandled exception in debug_yaxunit_tests", e); //$NON-NLS-1$
            return ToolResult.error("Unhandled exception: " + TextSuggest.safeMessage(e)).toJson(); //$NON-NLS-1$
        }
    }

    private static String buildParamsJson(String reportPath, String extensions, String modules, String tests)
    {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("reportPath", reportPath); //$NON-NLS-1$
        p.put("reportFormat", "jUnit"); //$NON-NLS-1$ //$NON-NLS-2$
        p.put("closeAfterTests", Boolean.TRUE); //$NON-NLS-1$

        Map<String, Object> filter = new LinkedHashMap<>();
        boolean hasFilter = false;
        if (extensions != null && !extensions.isEmpty())
        {
            filter.put("extensions", split(extensions)); //$NON-NLS-1$
            hasFilter = true;
        }
        if (modules != null && !modules.isEmpty())
        {
            filter.put("modules", split(modules)); //$NON-NLS-1$
            hasFilter = true;
        }
        if (tests != null && !tests.isEmpty())
        {
            filter.put("tests", split(tests)); //$NON-NLS-1$
            hasFilter = true;
        }
        if (hasFilter)
        {
            p.put("filter", filter); //$NON-NLS-1$
        }
        return GsonHolder.toJson(p);
    }

    private static List<String> split(String csv)
    {
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) //$NON-NLS-1$
        {
            String t = s.trim();
            if (!t.isEmpty())
            {
                out.add(t);
            }
        }
        return out;
    }
}
