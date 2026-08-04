/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;
import ru.aiedt.mcp.server.support.SensitivePatternLibrary;
import ru.aiedt.mcp.server.support.UiSync;

/**
 * Scans the project for potential personal-data / secret leaks: attribute
 * names, hardcoded tokens, comment leaks, sensitive log records.
 */
public class SensitiveDataScanTool implements IMcpTool
{
    public static final String NAME = "sensitive_data_scan"; //$NON-NLS-1$

    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\""); //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `security_audit` `operation=sensitive_data_scan`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Scan project for sensitive-data findings: attribute names that match the " //$NON-NLS-1$
            + "personal-data dictionary (Password, Passport, СНИЛС, etc.), hardcoded secrets " //$NON-NLS-1$
            + "in BSL string literals (Bearer tokens, AWS keys, JWT, base64), email/phone " //$NON-NLS-1$
            + "leaks in comments, log records that may include sensitive fields. 4 check kinds."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project to work in", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("scope", "project | subsystem | module (default project)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("subsystemName", "Subsystem when scope=subsystem") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("moduleFqn", "Module when scope=module") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("checks", //$NON-NLS-1$
                "Comma-separated: ATTRIBUTE_NAME, HARDCODED_SECRET, COMMENT_LEAK, LOG_SENSITIVE") //$NON-NLS-1$
            .stringProperty("severity_filter", "info | warning | error | all (default warning)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("customPatterns", //$NON-NLS-1$
                "Comma-separated additional regex patterns for ATTRIBUTE_NAME") //$NON-NLS-1$
            .stringProperty("format", "json | markdown (default json)") //$NON-NLS-1$ //$NON-NLS-2$
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
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        try
        {
            return UiSync.call(() -> {
                try
                {
                    return runScan(project, params);
                }
                catch (Exception e)
                {
                    Activator.logError("sensitive_data_scan error", e); //$NON-NLS-1$
                    return ToolResult.error(e.getMessage()).toJson();
                }
            });
        }
        catch (UiSync.UiBusyException e)
        {
            return ToolResult.error(e.getMessage()).put("tag", e.tag()).toJson(); //$NON-NLS-1$
        }
    }

    private String runScan(IProject project, Map<String, String> params) throws Exception
    {
        Set<String> checks = parseChecks(JsonUtils.extractStringArgument(params, "checks")); //$NON-NLS-1$
        String severity = orDefault(JsonUtils.extractStringArgument(params, "severity_filter"), //$NON-NLS-1$
            "warning"); //$NON-NLS-1$
        if (!"all".equalsIgnoreCase(severity) && !"error".equalsIgnoreCase(severity) //$NON-NLS-1$ //$NON-NLS-2$
            && !"warning".equalsIgnoreCase(severity) && !"info".equalsIgnoreCase(severity)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return ToolResult.error(TextSuggest.invalidValue("severity_filter", severity, //$NON-NLS-1$
                java.util.Arrays.asList("error", "warning", "info", "all"))).toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        String format = orDefault(JsonUtils.extractStringArgument(params, "format"), "json"); //$NON-NLS-1$ //$NON-NLS-2$
        Set<Pattern> custom = parseCustomPatterns(
            JsonUtils.extractStringArgument(params, "customPatterns")); //$NON-NLS-1$
        List<Map<String, Object>> findings = new ArrayList<>();

        if (isEnabled("ATTRIBUTE_NAME", checks)) //$NON-NLS-1$
        {
            scanAttributes(project, custom, findings);
        }
        if (isEnabled("HARDCODED_SECRET", checks) //$NON-NLS-1$
            || isEnabled("COMMENT_LEAK", checks) //$NON-NLS-1$
            || isEnabled("LOG_SENSITIVE", checks)) //$NON-NLS-1$
        {
            scanBslFiles(project, checks, findings);
        }

        // Severity filter
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> f : findings)
        {
            String sev = (String) f.get("severity"); //$NON-NLS-1$
            if (sev == null || matchesSeverity(sev, severity))
            {
                filtered.add(f);
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("findings", filtered.size()); //$NON-NLS-1$
        if ("markdown".equalsIgnoreCase(format)) //$NON-NLS-1$
        {
            return ToolResult.success()
                .put("statistics", stats) //$NON-NLS-1$
                .put("text", renderMarkdown(filtered, stats)) //$NON-NLS-1$
                .toJson();
        }
        return ToolResult.success()
            .put("statistics", stats) //$NON-NLS-1$
            .put("findings", filtered) //$NON-NLS-1$
            .toJson();
    }

    private void scanAttributes(IProject project, Set<Pattern> custom,
        List<Map<String, Object>> findings)
    {
        IConfigurationProvider provider = Activator.getDefault().getConfigurationProvider();
        if (provider == null)
        {
            return;
        }
        Configuration config = provider.getConfiguration(project);
        if (config == null)
        {
            return;
        }
        for (java.lang.reflect.Method m : config.getClass().getMethods())
        {
            if (m.getParameterCount() != 0)
            {
                continue;
            }
            String name = m.getName();
            if (!name.startsWith("get") || "getClass".equals(name)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                continue;
            }
            if (!java.util.List.class.isAssignableFrom(m.getReturnType()))
            {
                continue;
            }
            try
            {
                Object value = m.invoke(config);
                if (value instanceof java.util.List)
                {
                    for (Object item : (java.util.List<?>) value)
                    {
                        if (item instanceof MdObject)
                        {
                            scanMdObjectAttributes((MdObject) item, custom, findings);
                        }
                    }
                }
            }
            catch (Throwable ignored)
            {
                // best-effort
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void scanMdObjectAttributes(MdObject obj, Set<Pattern> custom,
        List<Map<String, Object>> findings)
    {
        try
        {
            for (String getter : new String[] { "getAttributes", "getDimensions", //$NON-NLS-1$ //$NON-NLS-2$
                "getResources" }) //$NON-NLS-1$
            {
                java.lang.reflect.Method m;
                try
                {
                    m = obj.getClass().getMethod(getter);
                }
                catch (NoSuchMethodException nsme)
                {
                    continue;
                }
                Object value = m.invoke(obj);
                if (!(value instanceof java.util.List))
                {
                    continue;
                }
                for (Object attr : (java.util.List<Object>) value)
                {
                    if (!(attr instanceof MdObject))
                    {
                        continue;
                    }
                    String attrName = ((MdObject) attr).getName();
                    if (attrName == null)
                    {
                        continue;
                    }
                    if (SensitivePatternLibrary.isSensitiveName(attrName)
                        || matchesAnyCustom(attrName, custom))
                    {
                        Map<String, Object> finding = new LinkedHashMap<>();
                        finding.put("kind", "ATTRIBUTE_NAME"); //$NON-NLS-1$ //$NON-NLS-2$
                        finding.put("severity", "WARNING"); //$NON-NLS-1$ //$NON-NLS-2$
                        finding.put("ownerFqn", obj.eClass().getName() + "." + obj.getName()); //$NON-NLS-1$ //$NON-NLS-2$
                        finding.put("attribute", attrName); //$NON-NLS-1$
                        finding.put("message", //$NON-NLS-1$
                            "Attribute name '" + attrName + "' looks like sensitive data"); //$NON-NLS-1$ //$NON-NLS-2$
                        findings.add(finding);
                    }
                }
            }
        }
        catch (Throwable ignored)
        {
            // best-effort
        }
    }

    private boolean matchesAnyCustom(String name, Set<Pattern> custom)
    {
        if (custom == null || custom.isEmpty())
        {
            return false;
        }
        for (Pattern pattern : custom)
        {
            if (pattern.matcher(name).find())
            {
                return true;
            }
        }
        return false;
    }

    private void scanBslFiles(IProject project, Set<String> checks,
        List<Map<String, Object>> findings) throws Exception
    {
        org.eclipse.core.resources.IResourceVisitor visitor = resource -> {
            if (resource instanceof IFile && resource.getName().endsWith(".bsl")) //$NON-NLS-1$
            {
                scanBslFile((IFile) resource, checks, findings);
            }
            return true;
        };
        project.accept(visitor, IResource.DEPTH_INFINITE, IResource.NONE);
    }

    private void scanBslFile(IFile file, Set<String> checks, List<Map<String, Object>> findings)
    {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(file.getContents(), StandardCharsets.UTF_8)))
        {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null)
            {
                lineNumber++;
                if (isEnabled("HARDCODED_SECRET", checks)) //$NON-NLS-1$
                {
                    Matcher literalMatcher = STRING_LITERAL.matcher(line);
                    while (literalMatcher.find())
                    {
                        String literal = literalMatcher.group(1);
                        Pattern matched = SensitivePatternLibrary.matchSecret(literal);
                        if (matched != null)
                        {
                            Map<String, Object> finding = new LinkedHashMap<>();
                            finding.put("kind", "HARDCODED_SECRET"); //$NON-NLS-1$ //$NON-NLS-2$
                            finding.put("severity", "ERROR"); //$NON-NLS-1$ //$NON-NLS-2$
                            finding.put("file", file.getProjectRelativePath().toString()); //$NON-NLS-1$
                            finding.put("line", lineNumber); //$NON-NLS-1$
                            finding.put("pattern", matched.pattern()); //$NON-NLS-1$
                            finding.put("message", //$NON-NLS-1$
                                "Hardcoded secret-like literal detected"); //$NON-NLS-1$
                            findings.add(finding);
                        }
                    }
                }
                if (isEnabled("COMMENT_LEAK", checks)) //$NON-NLS-1$
                {
                    if (SensitivePatternLibrary.EMAIL_IN_COMMENT.matcher(line).find())
                    {
                        Map<String, Object> finding = new LinkedHashMap<>();
                        finding.put("kind", "COMMENT_LEAK"); //$NON-NLS-1$ //$NON-NLS-2$
                        finding.put("severity", "WARNING"); //$NON-NLS-1$ //$NON-NLS-2$
                        finding.put("subkind", "email"); //$NON-NLS-1$ //$NON-NLS-2$
                        finding.put("file", file.getProjectRelativePath().toString()); //$NON-NLS-1$
                        finding.put("line", lineNumber); //$NON-NLS-1$
                        finding.put("message", "Email address in BSL comment"); //$NON-NLS-1$ //$NON-NLS-2$
                        findings.add(finding);
                    }
                    if (SensitivePatternLibrary.PHONE_IN_COMMENT.matcher(line).find())
                    {
                        Map<String, Object> finding = new LinkedHashMap<>();
                        finding.put("kind", "COMMENT_LEAK"); //$NON-NLS-1$ //$NON-NLS-2$
                        finding.put("severity", "WARNING"); //$NON-NLS-1$ //$NON-NLS-2$
                        finding.put("subkind", "phone"); //$NON-NLS-1$ //$NON-NLS-2$
                        finding.put("file", file.getProjectRelativePath().toString()); //$NON-NLS-1$
                        finding.put("line", lineNumber); //$NON-NLS-1$
                        finding.put("message", "Phone number in BSL comment"); //$NON-NLS-1$ //$NON-NLS-2$
                        findings.add(finding);
                    }
                }
                if (isEnabled("LOG_SENSITIVE", checks)) //$NON-NLS-1$
                {
                    if (SensitivePatternLibrary.LOG_RECORD.matcher(line).find())
                    {
                        // Heuristic: check if the line contains a sensitive attribute name
                        for (String sensitive : SensitivePatternLibrary.SENSITIVE_NAMES)
                        {
                            if (line.toLowerCase().contains(sensitive))
                            {
                                Map<String, Object> finding = new LinkedHashMap<>();
                                finding.put("kind", "LOG_SENSITIVE"); //$NON-NLS-1$ //$NON-NLS-2$
                                finding.put("severity", "INFO"); //$NON-NLS-1$ //$NON-NLS-2$
                                finding.put("file", file.getProjectRelativePath().toString()); //$NON-NLS-1$
                                finding.put("line", lineNumber); //$NON-NLS-1$
                                finding.put("matchedTerm", sensitive); //$NON-NLS-1$
                                finding.put("message", //$NON-NLS-1$
                                    "Log record may include sensitive field '" + sensitive + "'"); //$NON-NLS-1$ //$NON-NLS-2$
                                findings.add(finding);
                                break;
                            }
                        }
                    }
                }
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Failed to scan BSL " + file.getFullPath() + ": " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage());
        }
    }

    private static boolean isEnabled(String check, Set<String> enabled)
    {
        return enabled == null || enabled.isEmpty() || enabled.contains(check);
    }

    private static Set<String> parseChecks(String raw)
    {
        if (raw == null || raw.isEmpty())
        {
            return null;
        }
        Set<String> set = new HashSet<>(Arrays.asList(raw.split("\\s*,\\s*"))); //$NON-NLS-1$
        set.removeIf(String::isEmpty);
        return set;
    }

    private static Set<Pattern> parseCustomPatterns(String raw)
    {
        if (raw == null || raw.isEmpty())
        {
            return null;
        }
        Set<Pattern> patterns = new java.util.LinkedHashSet<>();
        for (String s : raw.split("\\s*,\\s*")) //$NON-NLS-1$
        {
            if (s.isEmpty())
            {
                continue;
            }
            try
            {
                patterns.add(Pattern.compile(s, Pattern.CASE_INSENSITIVE));
            }
            catch (Exception ignored)
            {
                // skip invalid pattern
            }
        }
        return patterns;
    }

    private static boolean matchesSeverity(String sev, String filter)
    {
        if (filter == null || "all".equalsIgnoreCase(filter)) //$NON-NLS-1$
        {
            return true;
        }
        switch (filter.toLowerCase())
        {
            case "error": //$NON-NLS-1$
                return "ERROR".equals(sev); //$NON-NLS-1$
            case "warning": //$NON-NLS-1$
                return "ERROR".equals(sev) || "WARNING".equals(sev); //$NON-NLS-1$ //$NON-NLS-2$
            case "info": //$NON-NLS-1$
                return true;
            default:
                return true;
        }
    }

    private static String renderMarkdown(List<Map<String, Object>> findings,
        Map<String, Object> stats)
    {
        StringBuilder sb = new StringBuilder("# Sensitive data scan\n\n"); //$NON-NLS-1$
        sb.append("**Findings:** ").append(stats.get("findings")).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (findings.isEmpty())
        {
            sb.append("No sensitive data findings.\n"); //$NON-NLS-1$
            return sb.toString();
        }
        sb.append("| Kind | Severity | Location | Message |\n"); //$NON-NLS-1$
        sb.append("|---|---|---|---|\n"); //$NON-NLS-1$
        for (Map<String, Object> f : findings)
        {
            String location = f.containsKey("file") //$NON-NLS-1$
                ? f.get("file") + ":" + f.get("line") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                : (String) f.get("ownerFqn"); //$NON-NLS-1$
            sb.append("| ").append(f.get("kind")).append(" | ").append(f.get("severity")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                .append(" | ").append(location).append(" | ").append(f.get("message")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .append(" |\n"); //$NON-NLS-1$
        }
        return sb.toString();
    }

    private static String orDefault(String value, String fallback)
    {
        return value != null && !value.isEmpty() ? value : fallback;
    }
}
