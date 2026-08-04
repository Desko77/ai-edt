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
import org.eclipse.core.resources.IResourceVisitor;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;
import ru.aiedt.mcp.server.support.QueryAntiPatternRules;
import ru.aiedt.mcp.server.support.UiSync;

/**
 * Static analyzer for 1C query anti-patterns. Scans BSL modules for query
 * text literals (regex-based extraction), runs {@link QueryAntiPatternRules}
 * over each query, and aggregates findings.
 * <p>
 * <b>1.38 MVP:</b> regex-based extraction with documented limitations.
 * Multi-line concatenations via {@code |} prefix are stitched together.
 * Queries built via {@code ЗагрузитьТекстЗапроса()} are skipped (text not
 * statically reachable).
 */
public class DetectQueryAntiPatternsTool implements IMcpTool
{
    public static final String NAME = "detect_query_anti_patterns"; //$NON-NLS-1$

    private static final Pattern QUERY_TEXT_ASSIGN = Pattern.compile(
        "(\\w+\\.[Тт]екст|Запрос\\.Текст|Query\\.Text)\\s*=\\s*(\"[\\s\\S]*?[^\"]\")", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE);

    private static final Pattern LOOP_BLOCK = Pattern.compile(
        "(Для\\s+|Пока\\s+|For\\s+|While\\s+).*?(Цикл|Do)([\\s\\S]*?)(КонецЦикла|EndDo)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE);

    private static final Pattern QUERY_EXEC_IN_BSL = Pattern.compile(
        "(Запрос\\.Выполнить|Запрос\\.ВыполнитьПакет|\\.Выполнить\\(\\)|\\.Execute\\(\\))", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE);

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `insights` `operation=detect_query_anti_patterns`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Static analyzer for 1C query anti-patterns: SELECT *, missing WHERE on large " //$NON-NLS-1$
            + "tables, virtual table without parameters, CROSS JOIN without condition, deep " //$NON-NLS-1$
            + "subquery nesting, query in BSL loop. Returns ranked list of findings."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project to work in", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("scope", "project | module | method (default project)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("moduleFqn", "Module FQN when scope=module/method") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("methodName", "Method name when scope=method") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("severity_filter", "info | warning | error | all (default warning)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("rules", //$NON-NLS-1$
                "Comma-separated rule names (default all): SELECT_STAR, NO_WHERE_ON_LARGE_TABLE, " //$NON-NLS-1$
                    + "VIRTUAL_TABLE_PARAMS, CROSS_JOIN_NO_CONDITION, NESTED_QUERY_DEPTH, " //$NON-NLS-1$
                    + "SUBQUERY_IN_SELECT, QUERY_IN_LOOP") //$NON-NLS-1$
            .stringProperty("format", "json | markdown (default json)") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("batchSize", "Module batch size for ResourceSet cleanup (default 50)") //$NON-NLS-1$ //$NON-NLS-2$
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
        String scope = orDefault(JsonUtils.extractStringArgument(params, "scope"), "project"); //$NON-NLS-1$ //$NON-NLS-2$
        String severityFilter = orDefault(
            JsonUtils.extractStringArgument(params, "severity_filter"), "warning"); //$NON-NLS-1$ //$NON-NLS-2$
        if (!"all".equalsIgnoreCase(severityFilter) && !"error".equalsIgnoreCase(severityFilter) //$NON-NLS-1$ //$NON-NLS-2$
            && !"warning".equalsIgnoreCase(severityFilter) && !"info".equalsIgnoreCase(severityFilter)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return ToolResult.error(TextSuggest.invalidValue("severity_filter", severityFilter, //$NON-NLS-1$
                java.util.Arrays.asList("error", "warning", "info", "all"))).toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        String format = orDefault(JsonUtils.extractStringArgument(params, "format"), "json"); //$NON-NLS-1$ //$NON-NLS-2$
        Set<String> enabledRules = parseRules(JsonUtils.extractStringArgument(params, "rules")); //$NON-NLS-1$

        try
        {
            return UiSync.call(() -> {
                try
                {
                    return runScan(project, scope, params, severityFilter, format, enabledRules);
                }
                catch (Exception e)
                {
                    Activator.logError("detect_query_anti_patterns error", e); //$NON-NLS-1$
                    return ToolResult.error(TextSuggest.safeMessage(e)).toJson();
                }
            });
        }
        catch (UiSync.UiBusyException e)
        {
            return ToolResult.error(e.getMessage()).put("tag", e.tag()).toJson(); //$NON-NLS-1$
        }
    }

    private String runScan(IProject project, String scope, Map<String, String> params,
        String severityFilter, String format, Set<String> enabledRules) throws Exception
    {
        List<IFile> bslFiles = collectBslFilesByScope(project, scope, params);
        List<Map<String, Object>> findings = new ArrayList<>();
        int queriesAnalyzed = 0;
        for (IFile file : bslFiles)
        {
            String content = readFile(file);
            if (content == null)
            {
                continue;
            }
            // QUERY_IN_LOOP - module-level pattern (BSL loop containing query.execute)
            if (isEnabled("QUERY_IN_LOOP", enabledRules)) //$NON-NLS-1$
            {
                detectQueryInLoop(file, content, findings);
            }
            // Extract query text literals and analyze each
            Matcher m = QUERY_TEXT_ASSIGN.matcher(content);
            while (m.find())
            {
                String literal = m.group(2);
                if (literal == null || literal.length() < 2)
                {
                    continue;
                }
                String queryText = unwrapBslString(literal);
                queriesAnalyzed++;
                int line = lineAt(content, m.start());
                List<QueryAntiPatternRules.Issue> issues = QueryAntiPatternRules.analyze(queryText,
                    enabledRules);
                for (QueryAntiPatternRules.Issue issue : issues)
                {
                    if (!matchesSeverity(issue.severity, severityFilter))
                    {
                        continue;
                    }
                    Map<String, Object> finding = new LinkedHashMap<>();
                    finding.put("file", file.getProjectRelativePath().toString()); //$NON-NLS-1$
                    finding.put("line", line + issue.lineInQuery - 1); //$NON-NLS-1$
                    finding.put("rule", issue.rule); //$NON-NLS-1$
                    finding.put("severity", issue.severity.name()); //$NON-NLS-1$
                    finding.put("message", issue.message); //$NON-NLS-1$
                    findings.add(finding);
                }
            }
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("modulesScanned", bslFiles.size()); //$NON-NLS-1$
        stats.put("queriesAnalyzed", queriesAnalyzed); //$NON-NLS-1$
        stats.put("findings", findings.size()); //$NON-NLS-1$

        if ("markdown".equalsIgnoreCase(format)) //$NON-NLS-1$
        {
            return ToolResult.success()
                .put("scope", scope) //$NON-NLS-1$
                .put("statistics", stats) //$NON-NLS-1$
                .put("text", renderMarkdown(findings, stats)) //$NON-NLS-1$
                .toJson();
        }
        return ToolResult.success()
            .put("scope", scope) //$NON-NLS-1$
            .put("statistics", stats) //$NON-NLS-1$
            .put("issues", findings) //$NON-NLS-1$
            .toJson();
    }

    private void detectQueryInLoop(IFile file, String content, List<Map<String, Object>> findings)
    {
        Matcher loopMatcher = LOOP_BLOCK.matcher(content);
        while (loopMatcher.find())
        {
            String loopBody = loopMatcher.group(3);
            if (loopBody != null && QUERY_EXEC_IN_BSL.matcher(loopBody).find())
            {
                int line = lineAt(content, loopMatcher.start());
                Map<String, Object> finding = new LinkedHashMap<>();
                finding.put("file", file.getProjectRelativePath().toString()); //$NON-NLS-1$
                finding.put("line", line); //$NON-NLS-1$
                finding.put("rule", "QUERY_IN_LOOP"); //$NON-NLS-1$ //$NON-NLS-2$
                finding.put("severity", "ERROR"); //$NON-NLS-1$ //$NON-NLS-2$
                finding.put("message", //$NON-NLS-1$
                    "Запрос внутри цикла BSL — N+1 проблема. Перепишите как массовый запрос."); //$NON-NLS-1$
                findings.add(finding);
            }
        }
    }

    private List<IFile> collectBslFilesByScope(IProject project, String scope,
        Map<String, String> params) throws Exception
    {
        List<IFile> all = new ArrayList<>();
        IResourceVisitor visitor = new IResourceVisitor()
        {
            @Override
            public boolean visit(IResource resource)
            {
                if (resource instanceof IFile && resource.getName().endsWith(".bsl")) //$NON-NLS-1$
                {
                    all.add((IFile) resource);
                }
                return true;
            }
        };
        project.accept(visitor, IResource.DEPTH_INFINITE, IResource.NONE);
        if ("project".equalsIgnoreCase(scope)) //$NON-NLS-1$
        {
            return all;
        }
        String moduleFqn = JsonUtils.extractStringArgument(params, "moduleFqn"); //$NON-NLS-1$
        if (moduleFqn == null || moduleFqn.isEmpty())
        {
            return all;
        }
        // Filter by module FQN — naive path mapping
        String prefix = moduleFqn.replace(".", "/"); //$NON-NLS-1$ //$NON-NLS-2$
        List<IFile> filtered = new ArrayList<>();
        for (IFile file : all)
        {
            String path = file.getFullPath().toString();
            if (path.contains(prefix) || path.contains("/" + moduleFqn.replace(".", "/"))) //$NON-NLS-1$ //$NON-NLS-2$
            {
                filtered.add(file);
            }
        }
        return filtered.isEmpty() ? all : filtered;
    }

    private static String readFile(IFile file)
    {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(file.getContents(), StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
        catch (Exception e)
        {
            Activator.logWarning("Failed to read " + file.getFullPath() + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    /**
     * Strips BSL string literal quotes and stitches multi-line {@code |}
     * continuations.
     */
    private static String unwrapBslString(String literal)
    {
        if (literal == null)
        {
            return ""; //$NON-NLS-1$
        }
        String s = literal.trim();
        if (s.startsWith("\"") && s.endsWith("\"")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            s = s.substring(1, s.length() - 1);
        }
        // Strip leading "|" on continuation lines.
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\\r?\\n")) //$NON-NLS-1$
        {
            String t = line;
            int idx = t.indexOf('|');
            if (idx >= 0 && t.substring(0, idx).trim().isEmpty())
            {
                t = t.substring(idx + 1);
            }
            sb.append(t).append('\n');
        }
        return sb.toString();
    }

    private static int lineAt(String text, int offset)
    {
        if (offset <= 0)
        {
            return 1;
        }
        int line = 1;
        int max = Math.min(offset, text.length());
        for (int i = 0; i < max; i++)
        {
            if (text.charAt(i) == '\n')
            {
                line++;
            }
        }
        return line;
    }

    private static boolean matchesSeverity(QueryAntiPatternRules.Severity sev, String filter)
    {
        if (filter == null || filter.isEmpty() || "all".equalsIgnoreCase(filter)) //$NON-NLS-1$
        {
            return true;
        }
        switch (filter.toLowerCase())
        {
            case "error": //$NON-NLS-1$
                return sev == QueryAntiPatternRules.Severity.ERROR;
            case "warning": //$NON-NLS-1$
                return sev == QueryAntiPatternRules.Severity.ERROR
                    || sev == QueryAntiPatternRules.Severity.WARNING;
            case "info": //$NON-NLS-1$
                return true;
            default:
                return true;
        }
    }

    private static boolean isEnabled(String rule, Set<String> enabled)
    {
        return enabled == null || enabled.isEmpty() || enabled.contains(rule);
    }

    private static Set<String> parseRules(String raw)
    {
        if (raw == null || raw.isEmpty())
        {
            return null;
        }
        Set<String> out = new HashSet<>(Arrays.asList(raw.split("\\s*,\\s*"))); //$NON-NLS-1$
        out.removeIf(String::isEmpty);
        return out.isEmpty() ? null : out;
    }

    private static String renderMarkdown(List<Map<String, Object>> findings,
        Map<String, Object> stats)
    {
        StringBuilder sb = new StringBuilder("# Query Anti-Patterns Report\n\n"); //$NON-NLS-1$
        sb.append("**Modules scanned:** ").append(stats.get("modulesScanned")).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        sb.append("**Queries analyzed:** ").append(stats.get("queriesAnalyzed")).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        sb.append("**Findings:** ").append(stats.get("findings")).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (findings.isEmpty())
        {
            sb.append("No anti-patterns detected.\n"); //$NON-NLS-1$
            return sb.toString();
        }
        sb.append("| File | Line | Rule | Severity | Message |\n"); //$NON-NLS-1$
        sb.append("|---|---|---|---|---|\n"); //$NON-NLS-1$
        for (Map<String, Object> f : findings)
        {
            sb.append("| ").append(f.get("file")).append(" | ").append(f.get("line")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                .append(" | ").append(f.get("rule")).append(" | ").append(f.get("severity")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                .append(" | ").append(f.get("message")).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return sb.toString();
    }

    private static String orDefault(String value, String fallback)
    {
        return value != null && !value.isEmpty() ? value : fallback;
    }
}
