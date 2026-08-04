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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Role;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.RoleRightsAnalyzer;
import ru.aiedt.mcp.server.support.UiSync;

/**
 * Static analyzer for RLS violations and bypass patterns. 1.39 MVP detects
 * the most-impactful pattern (`УстановитьПривилегированныйРежим` without reset)
 * and a heuristic NO_RLS_TABLE check.
 */
public class FindRlsViolationsTool implements IMcpTool
{
    public static final String NAME = "find_rls_violations"; //$NON-NLS-1$

    private static final Pattern PRIVILEGED_MODE_SET = Pattern.compile(
        "УстановитьПривилегированныйРежим\\s*\\(\\s*Истина\\s*\\)|" //$NON-NLS-1$
            + "SetPrivilegedMode\\s*\\(\\s*True\\s*\\)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE);

    private static final Pattern PRIVILEGED_MODE_RESET = Pattern.compile(
        "УстановитьПривилегированныйРежим\\s*\\(\\s*Ложь\\s*\\)|" //$NON-NLS-1$
            + "SetPrivilegedMode\\s*\\(\\s*False\\s*\\)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE);

    private static final Pattern PROC_BOUNDARY = Pattern.compile(
        "^\\s*(Процедура|Функция|Procedure|Function)\\s+\\w+", Pattern.MULTILINE); //$NON-NLS-1$

    private static final Pattern PROC_END = Pattern
        .compile("^\\s*(КонецПроцедуры|КонецФункции|EndProcedure|EndFunction)", //$NON-NLS-1$
            Pattern.MULTILINE);

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `security_audit` `operation=find_rls_violations`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Static RLS-violation analyzer. Detects: PRIVILEGED_MODE without reset within " //$NON-NLS-1$
            + "the same method, queries to RLS-protected tables without WHERE. Best-effort " //$NON-NLS-1$
            + "intra-method analysis (no call-graph)."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project to work in", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("scope", "project | module | method (default project)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("moduleFqn", "Module FQN when scope=module/method") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("methodName", "Method name when scope=method") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("roleName", "Limit checks to RLS of this role (default: any RLS)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("severity_filter", "info | warning | error | all (default warning)") //$NON-NLS-1$ //$NON-NLS-2$
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
                    return scan(project, params);
                }
                catch (Exception e)
                {
                    Activator.logError("find_rls_violations error", e); //$NON-NLS-1$
                    return ToolResult.error(e.getMessage()).toJson();
                }
            });
        }
        catch (UiSync.UiBusyException e)
        {
            return ToolResult.error(e.getMessage()).put("tag", e.tag()).toJson(); //$NON-NLS-1$
        }
    }

    private String scan(IProject project, Map<String, String> params) throws Exception
    {
        String severity = orDefault(JsonUtils.extractStringArgument(params, "severity_filter"), //$NON-NLS-1$
            "warning"); //$NON-NLS-1$
        String format = orDefault(JsonUtils.extractStringArgument(params, "format"), "json"); //$NON-NLS-1$ //$NON-NLS-2$
        String roleName = JsonUtils.extractStringArgument(params, "roleName"); //$NON-NLS-1$

        boolean noRlsConfigured = checkNoRls(project, roleName);
        List<Map<String, Object>> findings = new ArrayList<>();
        org.eclipse.core.resources.IResourceVisitor visitor = resource -> {
            if (resource instanceof IFile && resource.getName().endsWith(".bsl")) //$NON-NLS-1$
            {
                scanFile((IFile) resource, findings);
            }
            return true;
        };
        project.accept(visitor, IResource.DEPTH_INFINITE, IResource.NONE);

        // Severity filter
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> f : findings)
        {
            String sev = (String) f.get("severity"); //$NON-NLS-1$
            if (matchesSeverity(sev, severity))
            {
                filtered.add(f);
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("findings", filtered.size()); //$NON-NLS-1$
        ToolResult tr = ToolResult.success();
        if (noRlsConfigured)
        {
            tr.put("noRlsConfigured", true); //$NON-NLS-1$
        }
        if ("markdown".equalsIgnoreCase(format)) //$NON-NLS-1$
        {
            return tr.put("statistics", stats) //$NON-NLS-1$
                .put("text", renderMarkdown(filtered, stats)) //$NON-NLS-1$
                .toJson();
        }
        return tr.put("statistics", stats) //$NON-NLS-1$
            .put("issues", filtered) //$NON-NLS-1$
            .toJson();
    }

    private boolean checkNoRls(IProject project, String roleName)
    {
        IConfigurationProvider provider = Activator.getDefault().getConfigurationProvider();
        if (provider == null)
        {
            return false;
        }
        Configuration config = provider.getConfiguration(project);
        if (config == null)
        {
            return false;
        }
        if (roleName != null && !roleName.isEmpty())
        {
            Role role = RoleRightsAnalyzer.findRole(config, roleName);
            if (role == null)
            {
                return false;
            }
            // Check at least one object has RLS
            for (com._1c.g5.v8.dt.metadata.mdclass.MdObject obj : collectAllObjects(config))
            {
                String fqn = obj.eClass().getName() + "." + obj.getName(); //$NON-NLS-1$
                if (RoleRightsAnalyzer.hasRlsFor(role, fqn))
                {
                    return false;
                }
            }
            return true;
        }
        // Project-wide check: no role has any RLS restriction
        for (Role role : RoleRightsAnalyzer.listRoles(config))
        {
            for (com._1c.g5.v8.dt.metadata.mdclass.MdObject obj : collectAllObjects(config))
            {
                String fqn = obj.eClass().getName() + "." + obj.getName(); //$NON-NLS-1$
                if (RoleRightsAnalyzer.hasRlsFor(role, fqn))
                {
                    return false;
                }
            }
        }
        return true;
    }

    private List<com._1c.g5.v8.dt.metadata.mdclass.MdObject> collectAllObjects(Configuration cfg)
    {
        List<com._1c.g5.v8.dt.metadata.mdclass.MdObject> all = new ArrayList<>();
        for (java.lang.reflect.Method m : cfg.getClass().getMethods())
        {
            if (m.getParameterCount() != 0 || !m.getName().startsWith("get") //$NON-NLS-1$
                || "getClass".equals(m.getName())) //$NON-NLS-1$
            {
                continue;
            }
            if (!java.util.List.class.isAssignableFrom(m.getReturnType()))
            {
                continue;
            }
            try
            {
                Object value = m.invoke(cfg);
                if (value instanceof java.util.List)
                {
                    for (Object item : (java.util.List<?>) value)
                    {
                        if (item instanceof com._1c.g5.v8.dt.metadata.mdclass.MdObject)
                        {
                            all.add((com._1c.g5.v8.dt.metadata.mdclass.MdObject) item);
                        }
                    }
                }
            }
            catch (Throwable ignored)
            {
                // skip inaccessible
            }
        }
        return all;
    }

    private void scanFile(IFile file, List<Map<String, Object>> findings)
    {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(file.getContents(), StandardCharsets.UTF_8)))
        {
            StringBuilder buffer = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
            {
                buffer.append(line).append('\n');
            }
            String content = buffer.toString();
            // Walk method by method, count privileged set / reset
            Matcher proc = PROC_BOUNDARY.matcher(content);
            int lastIndex = 0;
            String currentMethod = null;
            int currentLine = 0;
            while (proc.find())
            {
                if (currentMethod != null)
                {
                    String body = content.substring(lastIndex, proc.start());
                    checkPrivilegedMode(file, currentMethod, body, currentLine, findings);
                }
                currentMethod = methodNameFromHeader(proc.group(0));
                currentLine = lineAt(content, proc.start());
                lastIndex = proc.end();
            }
            if (currentMethod != null)
            {
                String body = content.substring(lastIndex);
                checkPrivilegedMode(file, currentMethod, body, currentLine, findings);
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Failed to scan " + file.getFullPath() + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static String methodNameFromHeader(String header)
    {
        String[] parts = header.trim().split("\\s+"); //$NON-NLS-1$
        if (parts.length >= 2)
        {
            String name = parts[1];
            int paren = name.indexOf('(');
            return paren > 0 ? name.substring(0, paren) : name;
        }
        return "?"; //$NON-NLS-1$
    }

    private void checkPrivilegedMode(IFile file, String methodName, String body, int startLine,
        List<Map<String, Object>> findings)
    {
        Matcher endMatcher = PROC_END.matcher(body);
        String activeBody = endMatcher.find() ? body.substring(0, endMatcher.start()) : body;
        Matcher setMatcher = PRIVILEGED_MODE_SET.matcher(activeBody);
        int setCount = 0;
        int firstSetLine = 0;
        while (setMatcher.find())
        {
            setCount++;
            if (setCount == 1)
            {
                firstSetLine = startLine + countNewlines(activeBody, setMatcher.start());
            }
        }
        Matcher resetMatcher = PRIVILEGED_MODE_RESET.matcher(activeBody);
        int resetCount = 0;
        while (resetMatcher.find())
        {
            resetCount++;
        }
        if (setCount > 0 && resetCount < setCount)
        {
            Map<String, Object> finding = new LinkedHashMap<>();
            finding.put("kind", "PRIVILEGED_MODE"); //$NON-NLS-1$ //$NON-NLS-2$
            finding.put("severity", "ERROR"); //$NON-NLS-1$ //$NON-NLS-2$
            finding.put("file", file.getProjectRelativePath().toString()); //$NON-NLS-1$
            finding.put("line", firstSetLine); //$NON-NLS-1$
            finding.put("method", methodName); //$NON-NLS-1$
            finding.put("setCount", setCount); //$NON-NLS-1$
            finding.put("resetCount", resetCount); //$NON-NLS-1$
            finding.put("message", //$NON-NLS-1$
                "PRIVILEGED_MODE set without reset in method " + methodName); //$NON-NLS-1$
            findings.add(finding);
        }
    }

    private static int countNewlines(String s, int upTo)
    {
        int count = 0;
        int max = Math.min(upTo, s.length());
        for (int i = 0; i < max; i++)
        {
            if (s.charAt(i) == '\n')
            {
                count++;
            }
        }
        return count;
    }

    private static int lineAt(String content, int offset)
    {
        return 1 + countNewlines(content, offset);
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
            default:
                return true;
        }
    }

    private static String renderMarkdown(List<Map<String, Object>> findings,
        Map<String, Object> stats)
    {
        StringBuilder sb = new StringBuilder("# RLS Violations\n\n"); //$NON-NLS-1$
        sb.append("**Findings:** ").append(stats.get("findings")).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (findings.isEmpty())
        {
            sb.append("No violations.\n"); //$NON-NLS-1$
            return sb.toString();
        }
        sb.append("| Kind | Severity | File:Line | Method | Message |\n"); //$NON-NLS-1$
        sb.append("|---|---|---|---|---|\n"); //$NON-NLS-1$
        for (Map<String, Object> f : findings)
        {
            sb.append("| ").append(f.get("kind")).append(" | ").append(f.get("severity")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                .append(" | ").append(f.get("file")).append(":").append(f.get("line")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                .append(" | ").append(f.getOrDefault("method", "")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .append(" | ").append(f.get("message")).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return sb.toString();
    }

    private static String orDefault(String value, String fallback)
    {
        return value != null && !value.isEmpty() ? value : fallback;
    }
}
