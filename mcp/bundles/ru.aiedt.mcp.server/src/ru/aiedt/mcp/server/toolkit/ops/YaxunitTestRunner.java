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

/**
 * Runs YAXUnit tests for a 1C:Enterprise project. Launches the runtime client with the
 * {@code RunUnitTests} startup parameter, polls until the launch terminates or the polling window
 * expires, then parses the JUnit XML report into Markdown. Non-blocking: a launch still running at
 * timeout returns {@code **Pending**} and the caller re-invokes with the same arguments.
 */
public final class YaxunitTestRunner
    implements IMcpTool
{
    public static final String NAME = "run_yaxunit_tests"; //$NON-NLS-1$

    private static final int DEFAULT_TIMEOUT = 60;

    private static final int POLL_INTERVAL_MS = 1000;

    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private static final Map<String, ILaunch> ACTIVE_LAUNCHES = new ConcurrentHashMap<>();

    private static final AtomicBoolean LISTENER_REGISTERED = new AtomicBoolean(false);

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `yaxunit_tests` `mode=run`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Executes the YAXUnit test suite for a 1C:Enterprise project. " //$NON-NLS-1$
            + "Starts the application with the RunUnitTests parameter, then polls " //$NON-NLS-1$
            + "for up to `timeoutSeconds` seconds (60 by default) until it finishes, returning the outcome as a JUnit Markdown report. " //$NON-NLS-1$
            + "If the launch has not completed once the polling window closes, the response is " //$NON-NLS-1$
            + "**Pending** - invoke this tool again with the same arguments to keep waiting and "
            + "pick up the result once the launch finishes. The launch itself is not aborted on timeout. " //$NON-NLS-1$
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
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
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
        int timeout = TimeoutArgs.readSeconds(params, DEFAULT_TIMEOUT, 1, 0);

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
        return runTests(configName, projectName, applicationId, extensions, modules, tests, timeout);
    }

    private String runTests(String configName, String projectName, String applicationId, String extensions,
        String modules, String tests, int timeout)
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

            ILaunch existing = ACTIVE_LAUNCHES.get(runKey);
            if (existing != null)
            {
                if (existing.isTerminated())
                {
                    ACTIVE_LAUNCHES.remove(runKey);
                    File junitXml = findJunitXml(reportDir);
                    if (junitXml != null)
                    {
                        return readResults(junitXml);
                    }
                    return "**Error:** The previous launch finished, but no JUnit XML report was found in " + reportDir //$NON-NLS-1$
                        + ". Confirm the YAXUnit extension is installed."; //$NON-NLS-1$
                }
                String pollResult = pollLaunch(existing, reportDir, timeout, runKey);
                if (pollResult != null)
                {
                    return pollResult;
                }
                return buildPendingMessage(reportDir);
            }

            File cached = findJunitXml(reportDir);
            if (cached != null && (System.currentTimeMillis() - cached.lastModified()) < CACHE_TTL_MS)
            {
                Activator.logInfo("Serving cached YAXUnit results from " + cached); //$NON-NLS-1$
                return readResults(cached);
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
                }
            }

            String pollResult = pollLaunch(launch, reportDir, timeout, runKey);
            if (pollResult != null)
            {
                return pollResult;
            }
            return buildPendingMessage(reportDir);
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

    private String pollLaunch(ILaunch launch, Path reportDir, int timeoutSec, String runKey)
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
        return readResults(junitXml);
    }

    private String readResults(File junitXml)
    {
        try
        {
            JUnitRunOutcome results = JUnitXmlReader.parse(junitXml);
            String markdown = JUnitReportFormatter.format(results);
            if (results.getTotal() == 0)
            {
                markdown += "\n\n> **No tests were executed.** Check: the YAXUnit extension is Active in the infobase" //$NON-NLS-1$
                    + " (Configuration > Extensions); the test suite / module / tags filter matches existing" //$NON-NLS-1$
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
                return markdown + "\n---\n*Complete report written to:* `" + reportFile + "`\n"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            return markdown;
        }
        catch (Exception e)
        {
            Activator.logError("Failed to parse JUnit XML: " + junitXml, e); //$NON-NLS-1$
            return "**Error:** Could not parse the test results: " + e.getMessage(); //$NON-NLS-1$
        }
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

    private String buildPendingMessage(Path reportDir)
    {
        return "**Pending:** YAXUnit tests are still in progress.\n\nReport directory: `" + reportDir //$NON-NLS-1$
            + "`\n\nCall `run_yaxunit_tests` again with the same arguments to keep waiting and retrieve" //$NON-NLS-1$
            + " the JUnit XML once the launch is done.\n"; //$NON-NLS-1$
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
