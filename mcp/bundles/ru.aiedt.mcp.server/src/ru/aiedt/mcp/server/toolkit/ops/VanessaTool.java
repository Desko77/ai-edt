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

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.preference.IPreferenceStore;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.JUnitReportFormatter;
import ru.aiedt.mcp.server.support.JUnitRunOutcome;
import ru.aiedt.mcp.server.support.JUnitXmlReader;
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
 * sensitive - the full command line and the settings file are always logged so
 * they can be reconciled against the installed Vanessa build.
 */
public class VanessaTool implements IMcpTool
{
    public static final String NAME = "vanessa"; //$NON-NLS-1$

    private static final int DEFAULT_TIMEOUT_SEC = 300;
    private static final int MAX_TIMEOUT_SEC = 3600;
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
            + "connectionString (the 1C infobase connection string, e.g. File=...; or Srvr=...;Ref=...). " //$NON-NLS-1$
            + "Requires vanessa-automation.epf and the 1C thick client (1cv8.exe) configured in EDT " //$NON-NLS-1$
            + "preferences (download from github.com/Pr-Mex/vanessa-automation). Synchronous: raise " //$NON-NLS-1$
            + "timeoutSeconds for long suites."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("featurePath", //$NON-NLS-1$
                "Path to a .feature file or a directory of feature files (required). A relative " //$NON-NLS-1$
                    + "path is resolved against the project when projectName is given.", true) //$NON-NLS-1$
            .stringProperty("connectionString", //$NON-NLS-1$
                "1C infobase connection string (required), e.g. 'File=\"C:\\\\ib\";' or " //$NON-NLS-1$
                    + "'Srvr=\"host\";Ref=\"base\";'.", true) //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "Optional EDT project name - used to resolve a relative featurePath and as the " //$NON-NLS-1$
                    + "working directory.") //$NON-NLS-1$
            .integerProperty("timeoutSeconds", //$NON-NLS-1$
                "Max seconds to wait for the run (default 300, max 3600).") //$NON-NLS-1$
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
        if (connectionString == null || connectionString.trim().isEmpty())
        {
            return ToolResult.error("connectionString is required (the 1C infobase connection " //$NON-NLS-1$
                + "string, e.g. File=\"C:\\ib\"; ).").toJson(); //$NON-NLS-1$
        }
        String featurePathArg = JsonUtils.extractStringArgument(params, "featurePath"); //$NON-NLS-1$
        if (featurePathArg == null || featurePathArg.trim().isEmpty())
        {
            return ToolResult.error("featurePath is required (a .feature file or a directory).").toJson(); //$NON-NLS-1$
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

        File featurePath = resolveFeaturePath(featurePathArg.trim(), workingDir);
        if (!featurePath.exists())
        {
            return ToolResult.error("featurePath not found: " + featurePath.getAbsolutePath()).toJson(); //$NON-NLS-1$
        }

        boolean screenshots = JsonUtils.extractBooleanArgument(params, "screenshots", true); //$NON-NLS-1$
        boolean keepOpen = JsonUtils.extractBooleanArgument(params, "keepOpen", false); //$NON-NLS-1$
        int stepDelay = Math.max(0, JsonUtils.extractIntArgument(params, "stepDelaySeconds", 0)); //$NON-NLS-1$
        int timeoutSec = JsonUtils.extractIntArgument(params, "timeoutSeconds", DEFAULT_TIMEOUT_SEC); //$NON-NLS-1$
        if (timeoutSec <= 0)
        {
            timeoutSec = DEFAULT_TIMEOUT_SEC;
        }
        else if (timeoutSec > MAX_TIMEOUT_SEC)
        {
            timeoutSec = MAX_TIMEOUT_SEC;
        }

        Path outDir = null;
        try
        {
            outDir = Files.createTempDirectory("ai-edt-vanessa"); //$NON-NLS-1$
            File shotsDir = new File(outDir.toFile(), "screenshots"); //$NON-NLS-1$
            shotsDir.mkdirs();
            File junitFile = new File(outDir.toFile(), "junit.xml"); //$NON-NLS-1$
            File paramsFile = new File(outDir.toFile(), "VAParams.json"); //$NON-NLS-1$

            String vaParamsJson = buildVaParams(featurePath, junitFile, shotsDir, screenshots,
                keepOpen, stepDelay);
            writeUtf8Bom(paramsFile, vaParamsJson);

            File runDir = workingDir != null ? workingDir : outDir.toFile();
            List<String> command = buildCommand(exeFile, connectionString, epfFile, paramsFile);
            // The connectionString may carry Pwd="..." - never log it in the clear.
            Activator.logInfo("vanessa: launching " + redactSecrets(String.join(" ", command)) //$NON-NLS-1$ //$NON-NLS-2$
                + "\nVAParams.json:\n" + vaParamsJson); //$NON-NLS-1$

            ProcessResult pr = runVanessa(command, runDir, timeoutSec);
            Activator.logInfo("vanessa: exit=" + pr.exitCode + " timedOut=" + pr.timedOut //$NON-NLS-1$ //$NON-NLS-2$
                + " output:\n" + redactSecrets(pr.output)); //$NON-NLS-1$

            // A kept-open (or slow) run may have written the JUnit report before the process
            // was killed on timeout - prefer a real report over a bare timeout error.
            if (!junitFile.isFile())
            {
                if (pr.timedOut)
                {
                    return ToolResult.error("Vanessa run timed out after " + timeoutSec + "s" //$NON-NLS-1$ //$NON-NLS-2$
                        + (keepOpen ? " (keepOpen=true keeps 1C open, so it never exits - set keepOpen=false)." //$NON-NLS-1$
                            : ". Raise timeoutSeconds, or the run may be stuck on a 1C login/update dialog.") //$NON-NLS-1$
                        + " " + tail(pr.output)).toJson(); //$NON-NLS-1$
                }
                return ToolResult.error("Vanessa produced no JUnit report (exit " + pr.exitCode //$NON-NLS-1$
                    + "). The run may not have started (bad connectionString, an unadopted Vanessa " //$NON-NLS-1$
                    + "extension in the infobase, a login window, or Vanessa-version-specific launch " //$NON-NLS-1$
                    + "parameters - the launched command and VAParams.json are in the EDT .log). " //$NON-NLS-1$
                    + tail(pr.output)).toJson(); //$NON-NLS-1$
            }

            JUnitRunOutcome results = JUnitXmlReader.parse(junitFile);
            List<String> shots = collectScreenshots(shotsDir);

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
                .put("passed", results.isPassed()) //$NON-NLS-1$
                .put("summary", summary) //$NON-NLS-1$
                .put("total", results.getTotal()) //$NON-NLS-1$
                .put("failures", results.getFailures()) //$NON-NLS-1$
                .put("errors", results.getErrors()) //$NON-NLS-1$
                .put("skipped", results.getSkipped()) //$NON-NLS-1$
                .put("junitXmlPath", junitFile.getAbsolutePath()) //$NON-NLS-1$
                .put("screenshots", shots) //$NON-NLS-1$
                .put("markdown", JUnitReportFormatter.format(results)); //$NON-NLS-1$
            return ok.toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error in vanessa", e); //$NON-NLS-1$
            return ToolResult.error("Error running Vanessa: " + TextSuggest.safeMessage(e)).toJson(); //$NON-NLS-1$
        }
        // The output dir (junit.xml + screenshots) is intentionally NOT deleted: the
        // agent reads the returned screenshot paths. It is a temp dir the OS reclaims.
    }

    /** Resolves a relative featurePath against the project dir; leaves absolute paths as-is. */
    private static File resolveFeaturePath(String featurePathArg, File workingDir)
    {
        File f = new File(featurePathArg);
        if (!f.isAbsolute() && workingDir != null)
        {
            return new File(workingDir, featurePathArg);
        }
        return f;
    }

    /**
     * The Vanessa {@code VAParams.json} (Russian keys - Vanessa-version sensitive, logged
     * verbatim). Points Vanessa at the feature path, asks for a JUnit report and (optional)
     * failure screenshots, and closes the client when done so the poller detects exit.
     */
    private static String buildVaParams(File featurePath, File junitFile, File shotsDir,
        boolean screenshots, boolean keepOpen, int stepDelay)
    {
        JsonObject o = new JsonObject();
        // A directory of features, or the parent of a single .feature file.
        // getAbsoluteFile() first so getParentFile() is non-null even for a bare filename.
        File dir = featurePath.isDirectory() ? featurePath : featurePath.getAbsoluteFile().getParentFile();
        o.addProperty("КаталогФич", dir != null ? dir.getAbsolutePath() : featurePath.getAbsolutePath()); //$NON-NLS-1$
        if (featurePath.isFile())
        {
            o.addProperty("ФайлСценария", featurePath.getAbsolutePath()); //$NON-NLS-1$
        }
        o.addProperty("СохранятьРезультатыВФорматеJUnit", true); //$NON-NLS-1$
        o.addProperty("ПутьКФайлуРезультатовJUnit", junitFile.getAbsolutePath()); //$NON-NLS-1$
        o.addProperty("ДелатьСкриншотПриОшибке", screenshots); //$NON-NLS-1$
        o.addProperty("КаталогСохраненияСкриншотов", shotsDir.getAbsolutePath()); //$NON-NLS-1$
        o.addProperty("ЗакрыватьTestClientПослеПрогона", !keepOpen); //$NON-NLS-1$
        o.addProperty("ВыходИзПриложенияПослеЗапускаСценариев", !keepOpen); //$NON-NLS-1$
        if (stepDelay > 0)
        {
            o.addProperty("ПаузаМеждуШагами", stepDelay); //$NON-NLS-1$
        }
        return prettyJson(o);
    }

    /**
     * {@code 1cv8 ENTERPRISE /IBConnectionString "<conn>" /DisableStartupMessages
     * /Execute <epf> /C "StartFeaturePlayer;VAParams=<params>"} (thick client;
     * {@code /DisableStartupMessages} avoids the "update configuration?" modal).
     */
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
        String output = ""; //$NON-NLS-1$
    }

    /**
     * Launches the thick client, draining its merged stdout/stderr as cp1251 on a
     * daemon thread. On timeout, destroys the process tree (child 1C workers first).
     */
    private static ProcessResult runVanessa(List<String> command, File workingDir, int timeoutSec)
        throws Exception
    {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        if (workingDir != null && workingDir.isDirectory())
        {
            pb.directory(workingDir);
        }
        Process proc = pb.start();
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

    private static List<String> collectScreenshots(File shotsDir)
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
     * Masks the password out of anything that may carry a 1C connection string
     * ({@code Pwd="..."} or {@code Pwd=...}) before it is logged or returned.
     */
    private static String redactSecrets(String s)
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
