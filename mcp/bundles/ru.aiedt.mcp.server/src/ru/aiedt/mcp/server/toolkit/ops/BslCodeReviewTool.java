/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.preference.IPreferenceStore;

import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Runs deterministic BSL static analysis (code smells, complexity, naming, ...)
 * via the open-source <b>BSL Language Server</b> (1c-syntax) as an EXTERNAL
 * process, and returns its findings (file / line / rule / severity / message).
 *
 * <p>Complements {@code get_project_errors} (EDT's own semantic + metadata
 * checks) and {@code ask_1c_ai} (LLM review): this is a rule-based linter with
 * ~186 diagnostics and exact line coordinates - a different class of signal.
 *
 * <p><b>External process, not embedded</b>: bsl-language-server is a ~100 MB
 * Spring-Boot/Guice fat-jar with no stable in-process API; embedding it in this
 * OSGi bundle would collide with EDT's own Guice and bloat the plugin. Instead
 * the user installs the {@code -exec.jar} once and points
 * {@link PrefKeys#PREF_BSL_LS_JAR} at it; this tool spawns
 * {@code java -jar <jar> --analyze -s <src> -r json -o <tmp> -q} (on the configured
 * JRE, defaulting to EDT's own), parses the {@code bsl-json.json} report, and cleans
 * up. When the jar is
 * not configured the tool returns a download/setup hint instead of failing hard.
 */
public class BslCodeReviewTool implements IMcpTool
{
    public static final String NAME = "code_review"; //$NON-NLS-1$

    private static final int DEFAULT_TIMEOUT_SEC = 180;
    private static final int MAX_TIMEOUT_SEC = 1800;
    private static final int DEFAULT_LIMIT = 500;
    private static final int STDERR_TAIL = 2000;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Deterministic BSL static analysis (code smells, complexity, naming, ~186 rules) " //$NON-NLS-1$
            + "via the external open-source BSL Language Server (1c-syntax). Returns findings with " //$NON-NLS-1$
            + "file, line, rule id, severity and message. Complements get_project_errors (EDT " //$NON-NLS-1$
            + "semantic/metadata checks) and ask_1c_ai (LLM review). Scope to a single module " //$NON-NLS-1$
            + "(modulePath) or a method / line-range (method, startLine, endLine) to review just " //$NON-NLS-1$
            + "one place. Requires the bsl-language-server " //$NON-NLS-1$
            + "exec jar configured in EDT preferences (download from github.com/1c-syntax/bsl-language-server)."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "EDT project name to analyze (required).", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("modules", //$NON-NLS-1$
                "Optional comma-separated filter: keep only findings whose file path contains one of " //$NON-NLS-1$
                + "these substrings (e.g. 'CommonModules/Common', 'Catalogs/Products'). Omit to scan all.") //$NON-NLS-1$
            .stringProperty("modulePath", //$NON-NLS-1$
                "Optional scope to a SINGLE module: a path from src/ " //$NON-NLS-1$
                + "(e.g. 'CommonModules/Common/Module.bsl') or a module FQN " //$NON-NLS-1$
                + "(e.g. 'CommonModule.Common', 'Catalog.Products.ObjectModule'). Only findings for " //$NON-NLS-1$
                + "this module are returned. Analysis still covers the project; this filters output.") //$NON-NLS-1$
            .stringProperty("method", //$NON-NLS-1$
                "Optional scope (requires modulePath): keep only findings inside this method/procedure; " //$NON-NLS-1$
                + "its 1-based line span is resolved from the module AST. If it cannot be resolved, " //$NON-NLS-1$
                + "pass startLine/endLine instead.") //$NON-NLS-1$
            .integerProperty("startLine", //$NON-NLS-1$
                "Optional scope: keep only findings on or after this 1-based line (within modulePath if " //$NON-NLS-1$
                + "set). Ignored when 'method' is given.") //$NON-NLS-1$
            .integerProperty("endLine", //$NON-NLS-1$
                "Optional scope: keep only findings on or before this 1-based line. Ignored when " //$NON-NLS-1$
                + "'method' is given.") //$NON-NLS-1$
            .stringProperty("severity", //$NON-NLS-1$
                "Optional severity filter (substring, case-insensitive) on the rule severity " //$NON-NLS-1$
                + "(e.g. 'error', 'security'). Omit for all.") //$NON-NLS-1$
            .integerProperty("limit", "Maximum number of findings returned (default 500).") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("timeoutSeconds", //$NON-NLS-1$
                "Max seconds to wait for the analyzer (default 180, max 1800).") //$NON-NLS-1$
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
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }

        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        String jar = store.getString(PrefKeys.PREF_BSL_LS_JAR);
        if (jar == null || jar.trim().isEmpty())
        {
            return ToolResult.error("BSL Language Server jar is not configured. Download the " //$NON-NLS-1$
                + "'-exec.jar' from https://github.com/1c-syntax/bsl-language-server/releases and set " //$NON-NLS-1$
                + "its path in EDT -> Window -> Preferences -> AI-EDT (PREF_BSL_LS_JAR).").toJson(); //$NON-NLS-1$
        }
        jar = jar.trim();
        File jarFile = new File(jar);
        if (!jarFile.isFile())
        {
            return ToolResult.error("Configured BSL Language Server jar not found: " + jar).toJson(); //$NON-NLS-1$
        }

        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        File srcDir = resolveSrcDir(project);
        if (srcDir == null || !srcDir.isDirectory())
        {
            return ToolResult.error("Project source folder not found on disk for " + projectName).toJson(); //$NON-NLS-1$
        }

        String modulesCsv = JsonUtils.extractStringArgument(params, "modules"); //$NON-NLS-1$
        String severityFilter = JsonUtils.extractStringArgument(params, "severity"); //$NON-NLS-1$
        int limit = JsonUtils.extractIntArgument(params, "limit", DEFAULT_LIMIT); //$NON-NLS-1$
        if (limit <= 0)
        {
            limit = DEFAULT_LIMIT;
        }
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
            outDir = Files.createTempDirectory("ai-edt-codereview"); //$NON-NLS-1$
            String javaExe = resolveJavaExe(store);
            ProcessResult pr = runAnalyzer(jarFile, srcDir, outDir.toFile(), timeoutSec, javaExe);
            if (pr.timedOut)
            {
                return ToolResult.error("BSL Language Server analysis timed out after " + timeoutSec //$NON-NLS-1$
                    + "s. Raise timeoutSeconds or narrow the project. " + tail(pr.output)).toJson(); //$NON-NLS-1$
            }

            File report = findReport(outDir.toFile());
            if (report == null)
            {
                // Most common actionable failure: BSL-LS needs a newer Java than the one used.
                if (pr.output != null && pr.output.contains("UnsupportedClassVersionError")) //$NON-NLS-1$
                {
                    return ToolResult.error("BSL Language Server requires a newer Java than the one used (" //$NON-NLS-1$
                        + javaExe + "). Recent BSL-LS (1.0.0+) needs Java 21, but EDT runs on Java 17. Either " //$NON-NLS-1$
                        + "set the BSL Language Server Java path to a Java 21+ in EDT -> Window -> Preferences " //$NON-NLS-1$
                        + "-> AI-EDT ('BSL Language Server Java'), or use a Java-17-compatible " //$NON-NLS-1$
                        + "BSL-LS (0.x).").toJson(); //$NON-NLS-1$
                }
                return ToolResult.error("BSL Language Server produced no JSON report (exit " //$NON-NLS-1$
                    + pr.exitCode + "). " + tail(pr.output)).toJson(); //$NON-NLS-1$
            }

            List<Map<String, Object>> findings = parseReport(report, srcDir);

            String modLower = modulesCsv != null && !modulesCsv.isEmpty()
                ? modulesCsv.toLowerCase(Locale.ROOT) : null;
            String sevLower = severityFilter != null && !severityFilter.isEmpty()
                ? severityFilter.toLowerCase(Locale.ROOT) : null;
            if (modLower != null || sevLower != null)
            {
                findings = filter(findings, modLower, sevLower);
            }

            // Scope to a single module and/or a method / line-range (additive to the
            // module/severity filters). The analyzer scanned the whole project src; this
            // narrows the RETURNED findings so a caller can review one module or method
            // without wading through the project.
            String scopeModulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
            String scopeMethod = JsonUtils.extractStringArgument(params, "method"); //$NON-NLS-1$
            int scopeStart = JsonUtils.extractIntArgument(params, "startLine", 0); //$NON-NLS-1$
            int scopeEnd = JsonUtils.extractIntArgument(params, "endLine", 0); //$NON-NLS-1$
            Map<String, Object> appliedScope = null;
            boolean hasScope = (scopeModulePath != null && !scopeModulePath.isEmpty())
                || (scopeMethod != null && !scopeMethod.isEmpty())
                || scopeStart > 0 || scopeEnd > 0;
            if (hasScope)
            {
                ScopeResult sc = applyScope(project, findings, scopeModulePath, scopeMethod,
                    scopeStart, scopeEnd);
                if (sc.error != null)
                {
                    return ToolResult.error("code_review scope: " + sc.error).toJson(); //$NON-NLS-1$
                }
                findings = sc.findings;
                appliedScope = sc.scope;
            }

            findings.sort(Comparator
                .comparing((Map<String, Object> f) -> String.valueOf(f.get("file"))) //$NON-NLS-1$
                .thenComparingInt(f -> toInt(f.get("line")))); //$NON-NLS-1$

            int total = findings.size();
            boolean truncated = total > limit;
            if (truncated)
            {
                findings = new ArrayList<>(findings.subList(0, limit));
            }

            ToolResult resp = ToolResult.success()
                .put("project", projectName) //$NON-NLS-1$
                .put("findingsCount", total) //$NON-NLS-1$
                .put("truncated", truncated); //$NON-NLS-1$
            if (appliedScope != null)
            {
                resp.put("scope", appliedScope); //$NON-NLS-1$
            }
            return resp.put("findings", findings).toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Error in code_review", e); //$NON-NLS-1$
            return ToolResult.error("Error running BSL Language Server: " + TextSuggest.safeMessage(e)).toJson(); //$NON-NLS-1$
        }
        finally
        {
            if (outDir != null)
            {
                deleteRecursively(outDir.toFile());
            }
        }
    }

    /** Project {@code src/} dir on disk, or the project root when there is no {@code src/}. */
    private static File resolveSrcDir(IProject project)
    {
        IFolder src = project.getFolder("src"); //$NON-NLS-1$
        if (src != null && src.exists() && src.getLocation() != null)
        {
            return src.getLocation().toFile();
        }
        return project.getLocation() != null ? project.getLocation().toFile() : null;
    }

    /**
     * The java executable used to run BSL-LS: the configured {@code PREF_BSL_LS_JAVA}
     * when set and valid, otherwise the JRE EDT itself runs on ({@code java.home}).
     * Configurable because BSL-LS 1.0.0+ needs Java 21 while EDT 2026.1 runs on Java 17.
     */
    private static String resolveJavaExe(IPreferenceStore store)
    {
        if (store != null)
        {
            String cfg = store.getString(PrefKeys.PREF_BSL_LS_JAVA);
            if (cfg != null && !cfg.trim().isEmpty())
            {
                File f = new File(cfg.trim());
                if (f.isFile())
                {
                    return f.getAbsolutePath();
                }
                Activator.logWarning("code_review: configured BSL Language Server Java path is not a " //$NON-NLS-1$
                    + "file, falling back to EDT's JRE: " + cfg); //$NON-NLS-1$
            }
        }
        String home = System.getProperty("java.home"); //$NON-NLS-1$
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        File java = new File(home, "bin/java" + (win ? ".exe" : "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return java.isFile() ? java.getAbsolutePath() : "java"; //$NON-NLS-1$
    }

    private static final class ProcessResult
    {
        int exitCode = -1;
        boolean timedOut;
        String output = ""; //$NON-NLS-1$
    }

    /**
     * Spawns {@code java -jar <jar> --analyze -s <src> -r json -o <out> -q},
     * draining the merged stdout/stderr on a daemon thread so a chatty analyzer
     * cannot deadlock the wait. Destroys the process on timeout.
     */
    private static ProcessResult runAnalyzer(File jar, File srcDir, File outDir, int timeoutSec,
        String javaExe) throws Exception
    {
        ProcessBuilder pb = new ProcessBuilder(
            javaExe, "-jar", jar.getAbsolutePath(), //$NON-NLS-1$
            "--analyze", //$NON-NLS-1$
            "-s", srcDir.getAbsolutePath(), //$NON-NLS-1$
            "-r", "json", //$NON-NLS-1$ //$NON-NLS-2$
            "-o", outDir.getAbsolutePath(), //$NON-NLS-1$
            "-q"); //$NON-NLS-1$
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        StringBuilder out = new StringBuilder();
        Thread drain = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8)))
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
        }, "code-review-drain"); //$NON-NLS-1$
        drain.setDaemon(true);
        drain.start();

        ProcessResult pr = new ProcessResult();
        if (!proc.waitFor(timeoutSec, TimeUnit.SECONDS))
        {
            proc.destroyForcibly();
            pr.timedOut = true;
        }
        else
        {
            pr.exitCode = proc.exitValue();
        }
        // If the drain does not finish in 2s it stays alive as a daemon (harmless - it
        // is killed at JVM exit); pr.output then loses only bytes read after this window.
        drain.join(2000);
        synchronized (out)
        {
            pr.output = out.toString();
        }
        return pr;
    }

    /** The json reporter writes {@code bsl-json.json}; fall back to any *.json in the out dir. */
    private static File findReport(File outDir)
    {
        File named = new File(outDir, "bsl-json.json"); //$NON-NLS-1$
        if (named.isFile())
        {
            return named;
        }
        File[] jsons = outDir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".json")); //$NON-NLS-1$
        if (jsons == null || jsons.length == 0)
        {
            return null;
        }
        java.util.Arrays.sort(jsons, Comparator.comparing(File::getName)); // deterministic pick
        return jsons[0];
    }

    /**
     * Parses the BSL-LS json report defensively. The report is an
     * {@code AnalysisInfo}: a top-level object whose {@code fileinfos} array (or a
     * bare array) holds per-file entries, each with a {@code path}/{@code sourceFile}
     * and a {@code diagnostics} array of LSP diagnostics (range.start.line 0-based,
     * code, severity, message). Missing fields are tolerated.
     */
    private static List<Map<String, Object>> parseReport(File report, File srcDir) throws Exception
    {
        List<Map<String, Object>> findings = new ArrayList<>();
        String json = new String(Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8);
        JsonElement root = JsonParser.parseString(json);

        JsonArray fileInfos = null;
        if (root.isJsonArray())
        {
            fileInfos = root.getAsJsonArray();
        }
        else if (root.isJsonObject())
        {
            JsonObject ro = root.getAsJsonObject();
            for (String key : new String[] { "fileinfos", "fileInfos", "files" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                if (ro.has(key) && ro.get(key).isJsonArray())
                {
                    fileInfos = ro.getAsJsonArray(key);
                    break;
                }
            }
        }
        if (fileInfos == null)
        {
            return findings;
        }

        String basePath = srcDir.getAbsolutePath().replace('\\', '/');
        for (JsonElement fe : fileInfos)
        {
            if (!fe.isJsonObject())
            {
                continue;
            }
            JsonObject fo = fe.getAsJsonObject();
            String path = firstString(fo, "path", "sourceFile", "uri", "file"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            String relPath = relativize(path, basePath);
            JsonArray diags = fo.has("diagnostics") && fo.get("diagnostics").isJsonArray() //$NON-NLS-1$ //$NON-NLS-2$
                ? fo.getAsJsonArray("diagnostics") : null; //$NON-NLS-1$
            if (diags == null)
            {
                continue;
            }
            for (JsonElement de : diags)
            {
                if (!de.isJsonObject())
                {
                    continue;
                }
                JsonObject d = de.getAsJsonObject();
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("file", relPath); //$NON-NLS-1$
                f.put("line", diagnosticLine(d)); //$NON-NLS-1$
                f.put("rule", diagnosticCode(d)); //$NON-NLS-1$
                f.put("severity", optString(d, "severity")); //$NON-NLS-1$ //$NON-NLS-2$
                f.put("message", optString(d, "message")); //$NON-NLS-1$ //$NON-NLS-2$
                findings.add(f);
            }
        }
        return findings;
    }

    /** LSP range.start.line is 0-based; report it 1-based. */
    private static int diagnosticLine(JsonObject d)
    {
        if (!d.has("range") || !d.get("range").isJsonObject()) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return 0;
        }
        JsonElement startEl = d.getAsJsonObject("range").get("start"); //$NON-NLS-1$ //$NON-NLS-2$
        if (startEl == null || !startEl.isJsonObject())
        {
            return 0;
        }
        JsonObject start = startEl.getAsJsonObject();
        if (start.has("line") && start.get("line").isJsonPrimitive()) //$NON-NLS-1$ //$NON-NLS-2$
        {
            try
            {
                return start.get("line").getAsInt() + 1; //$NON-NLS-1$
            }
            catch (NumberFormatException ignored)
            {
                // line was a non-numeric primitive - report unknown
            }
        }
        return 0;
    }

    /** {@code code} is a string, or an object with a {@code value} (LSP CodeDescription). */
    private static String diagnosticCode(JsonObject d)
    {
        if (!d.has("code")) //$NON-NLS-1$
        {
            return ""; //$NON-NLS-1$
        }
        JsonElement c = d.get("code"); //$NON-NLS-1$
        if (c.isJsonPrimitive())
        {
            return c.getAsString();
        }
        if (c.isJsonObject() && c.getAsJsonObject().has("value")) //$NON-NLS-1$
        {
            return c.getAsJsonObject().get("value").getAsString(); //$NON-NLS-1$
        }
        return c.toString();
    }

    private static String firstString(JsonObject o, String... keys)
    {
        for (String k : keys)
        {
            if (o.has(k) && o.get(k).isJsonPrimitive())
            {
                return o.get(k).getAsString();
            }
        }
        return ""; //$NON-NLS-1$
    }

    private static String optString(JsonObject o, String key)
    {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : ""; //$NON-NLS-1$
    }

    /** Make an absolute/uri path project-relative for compact, stable output. */
    private static String relativize(String path, String basePath)
    {
        if (path == null || path.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        String p = path.replace('\\', '/');
        if (p.startsWith("file:")) //$NON-NLS-1$
        {
            p = p.replaceFirst("^file:/+", "/"); //$NON-NLS-1$ //$NON-NLS-2$
            p = p.replaceFirst("^/([A-Za-z]:)", "$1"); // Windows: /C:/... -> C:/... //$NON-NLS-1$ //$NON-NLS-2$
        }
        int idx = p.toLowerCase(Locale.ROOT).indexOf(basePath.toLowerCase(Locale.ROOT));
        if (idx >= 0)
        {
            String rel = p.substring(idx + basePath.length());
            return rel.startsWith("/") ? rel.substring(1) : rel; //$NON-NLS-1$
        }
        return p;
    }

    private static List<Map<String, Object>> filter(List<Map<String, Object>> in,
        String modLower, String sevLower)
    {
        List<String> mods = new ArrayList<>();
        if (modLower != null)
        {
            for (String m : modLower.split(",")) //$NON-NLS-1$
            {
                String t = m.trim();
                if (!t.isEmpty())
                {
                    mods.add(t);
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> f : in)
        {
            if (sevLower != null
                && !String.valueOf(f.get("severity")).toLowerCase(Locale.ROOT).contains(sevLower)) //$NON-NLS-1$
            {
                continue;
            }
            if (!mods.isEmpty())
            {
                String file = String.valueOf(f.get("file")).toLowerCase(Locale.ROOT); //$NON-NLS-1$
                boolean hit = false;
                for (String m : mods)
                {
                    if (file.contains(m))
                    {
                        hit = true;
                        break;
                    }
                }
                if (!hit)
                {
                    continue;
                }
            }
            out.add(f);
        }
        return out;
    }

    /** Result of {@link #applyScope}: narrowed findings + the applied scope descriptor, or an error. */
    private static final class ScopeResult
    {
        List<Map<String, Object>> findings;
        Map<String, Object> scope;
        String error;
    }

    /**
     * Narrows findings to a single module ({@code modulePath}) and/or a method /
     * line-range. The analyzer already scanned the whole project; this filters the
     * RETURNED findings so a caller can review one module or method. {@code modulePath}
     * accepts a path from src/ or a module FQN; {@code method} (needs modulePath)
     * resolves its 1-based line span from the module AST; {@code startLine}/{@code endLine}
     * give an explicit range (ignored when {@code method} is set). On a resolution failure
     * the result carries a clear {@code error} rather than silently returning everything.
     */
    private static ScopeResult applyScope(IProject project, List<Map<String, Object>> in,
        String modulePath, String method, int startLine, int endLine)
    {
        ScopeResult res = new ScopeResult();
        Map<String, Object> scope = new LinkedHashMap<>();
        List<Map<String, Object>> out = in;

        String resolvedMp = null;
        if (modulePath != null && !modulePath.isEmpty())
        {
            BslModuleAccess.ModulePathResolution mr =
                BslModuleAccess.resolveModulePath(project, modulePath);
            if (!mr.isResolved())
            {
                res.error = "modulePath could not be resolved: " + modulePath //$NON-NLS-1$
                    + (mr.getHint() != null ? " - " + mr.getHint() : ""); //$NON-NLS-1$ //$NON-NLS-2$
                return res;
            }
            resolvedMp = mr.getPath();
            String mpNorm = normPath(resolvedMp);
            scope.put("modulePath", resolvedMp); //$NON-NLS-1$
            // Prefer EXACT path matches. Fall back to suffix matches only when there is no
            // exact hit; if a partial modulePath suffix-matches more than one DISTINCT module
            // (e.g. "Products/Module.bsl" hitting both a common module and an object form
            // module), that is ambiguous - fail loudly instead of silently merging two
            // modules' findings under one scope.
            List<Map<String, Object>> exact = new ArrayList<>();
            List<Map<String, Object>> loose = new ArrayList<>();
            java.util.Set<String> looseFiles = new java.util.LinkedHashSet<>();
            for (Map<String, Object> f : out)
            {
                String fileNorm = normPath(String.valueOf(f.get("file"))); //$NON-NLS-1$
                if (fileNorm.equals(mpNorm))
                {
                    exact.add(f);
                }
                else if (fileNorm.endsWith("/" + mpNorm) || mpNorm.endsWith("/" + fileNorm)) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    loose.add(f);
                    looseFiles.add(fileNorm);
                }
            }
            if (!exact.isEmpty())
            {
                out = exact;
            }
            else if (looseFiles.size() > 1)
            {
                res.error = "modulePath '" + modulePath + "' is ambiguous - it suffix-matches " //$NON-NLS-1$ //$NON-NLS-2$
                    + looseFiles.size() + " different modules: " + looseFiles //$NON-NLS-1$
                    + ". Pass the full path from src/ or a module FQN."; //$NON-NLS-1$
                return res;
            }
            else
            {
                out = loose;
            }
        }

        int lo = startLine > 0 ? startLine : 0;
        int hi = endLine > 0 ? endLine : 0;
        if (method != null && !method.isEmpty())
        {
            if (resolvedMp == null)
            {
                res.error = "method scope requires modulePath."; //$NON-NLS-1$
                return res;
            }
            try
            {
                Module module = BslModuleAccess.loadModule(project, resolvedMp);
                Method m = BslModuleAccess.findMethod(module, method);
                if (m == null)
                {
                    res.error = "method '" + method + "' not found in " + resolvedMp //$NON-NLS-1$ //$NON-NLS-2$
                        + ". Check the name, or pass startLine/endLine explicitly."; //$NON-NLS-1$
                    return res;
                }
                lo = BslModuleAccess.getStartLine(m);
                hi = BslModuleAccess.getEndLine(m);
                scope.put("method", method); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                res.error = "could not resolve method '" + method + "' in " + resolvedMp //$NON-NLS-1$ //$NON-NLS-2$
                    + ": " + TextSuggest.safeMessage(e) //$NON-NLS-1$
                    + ". Pass startLine/endLine explicitly instead."; //$NON-NLS-1$
                return res;
            }
        }

        if (lo > 0 || hi > 0)
        {
            final int flo = lo > 0 ? lo : Integer.MIN_VALUE;
            final int fhi = hi > 0 ? hi : Integer.MAX_VALUE;
            List<Map<String, Object>> byLine = new ArrayList<>();
            int droppedNoLine = 0;
            for (Map<String, Object> f : out)
            {
                int ln = toInt(f.get("line")); //$NON-NLS-1$
                if (ln <= 0)
                {
                    droppedNoLine++; // file-level finding with no attributable line - not in any range
                    continue;
                }
                if (ln >= flo && ln <= fhi)
                {
                    byLine.add(f);
                }
            }
            out = byLine;
            if (lo > 0)
            {
                scope.put("startLine", lo); //$NON-NLS-1$
            }
            if (hi > 0)
            {
                scope.put("endLine", hi); //$NON-NLS-1$
            }
            if (droppedNoLine > 0)
            {
                // Transparency: line-range scoping excludes findings the analyzer could not
                // attribute to a line (reported so the caller knows they were not considered).
                scope.put("droppedNoLine", droppedNoLine); //$NON-NLS-1$
            }
        }

        res.findings = out;
        res.scope = scope;
        return res;
    }

    /** Normalize a path for comparison: forward slashes, lowercase, no leading slash. */
    private static String normPath(String p)
    {
        if (p == null)
        {
            return ""; //$NON-NLS-1$
        }
        String n = p.replace('\\', '/').toLowerCase(Locale.ROOT);
        return n.startsWith("/") ? n.substring(1) : n; //$NON-NLS-1$
    }

    private static int toInt(Object o)
    {
        if (o instanceof Number)
        {
            return ((Number) o).intValue();
        }
        try
        {
            return Integer.parseInt(String.valueOf(o));
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }

    private static String tail(String s)
    {
        if (s == null || s.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        String t = s.length() > STDERR_TAIL ? s.substring(s.length() - STDERR_TAIL) : s;
        return "Output tail: " + t.trim(); //$NON-NLS-1$
    }

    private static void deleteRecursively(File f)
    {
        if (f == null || !f.exists())
        {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null)
        {
            for (File k : kids)
            {
                deleteRecursively(k);
            }
        }
        // best-effort cleanup of a temp dir
        if (!f.delete())
        {
            f.deleteOnExit();
        }
    }
}
