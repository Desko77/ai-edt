/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import ru.aiedt.mcp.server.support.WatchForCancel;
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
import org.eclipse.core.runtime.Path;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;
import ru.aiedt.mcp.server.support.QueryAntiPatternRules;
import ru.aiedt.mcp.server.support.UiSync;
import ru.aiedt.mcp.server.support.WalkNarrowing;

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

    private static final List<String> SCOPES = List.of(
        WalkNarrowing.PROJECT, WalkNarrowing.MODULE, WalkNarrowing.METHOD);

    private static final Pattern QUERY_TEXT_ASSIGN = Pattern.compile(
        "(\\w+\\.[Тт]екст|Запрос\\.Текст|Query\\.Text)\\s*=\\s*(\"[\\s\\S]*?[^\"]\")", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern LOOP_BLOCK = Pattern.compile(
        "(Для\\s+|Пока\\s+|For\\s+|While\\s+).*?(Цикл|Do)([\\s\\S]*?)(КонецЦикла|EndDo)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern QUERY_EXEC_IN_BSL = Pattern.compile(
        "(Запрос\\.Выполнить|Запрос\\.ВыполнитьПакет|\\.Выполнить\\(\\)|\\.Execute\\(\\))", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

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
            .stringProperty("scope", //$NON-NLS-1$
                "project | module | method. Absent, the selectors decide: moduleFqn is a module, " //$NON-NLS-1$
                    + "moduleFqn with methodName is a method, and nothing is the whole project. " //$NON-NLS-1$
                    + "scope=project rejects every selector. scope=module requires moduleFqn and " //$NON-NLS-1$
                    + "rejects methodName. scope=method requires both. An unknown module, method " //$NON-NLS-1$
                    + "or scope word is refused by name and the project is not scanned.") //$NON-NLS-1$
            .stringProperty("moduleFqn", //$NON-NLS-1$
                "Module FQN, for example CommonModule.Sales. Required for scope=module and " //$NON-NLS-1$
                    + "scope=method. Without scope it selects the module on its own.") //$NON-NLS-1$
            .stringProperty("methodName", //$NON-NLS-1$
                "Method inside moduleFqn. Required for scope=method. Refused when moduleFqn is " //$NON-NLS-1$
                    + "absent. Without scope, moduleFqn plus methodName selects that method.") //$NON-NLS-1$
            .stringProperty("severity_filter", "info | warning | error | all (default warning)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("rules", //$NON-NLS-1$
                "Comma-separated rule names (default all): SELECT_STAR, NO_WHERE_ON_LARGE_TABLE, " //$NON-NLS-1$
                    + "VIRTUAL_TABLE_PARAMS, CROSS_JOIN_NO_CONDITION, NESTED_QUERY_DEPTH, " //$NON-NLS-1$
                    + "SUBQUERY_IN_SELECT, QUERY_IN_LOOP") //$NON-NLS-1$
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
            return ProjectResolver.notFound(projectName).toJson();
        }
        WalkNarrowing.Decision decision = WalkNarrowing.decide(params, SCOPES,
            WalkNarrowing.Selectors.MODULE_AND_METHOD);
        if (decision.refused())
        {
            return ToolResult.error(decision.refusal()).toJson();
        }
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
                    return runScan(project, decision, severityFilter, format, enabledRules);
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

    /**
     * The scan itself, without the UI-thread hop {@link #execute} wraps it in.
     * <p>
     * The test runtime does not pump an SWT loop, so the suite calls this directly. Production
     * calls it from the UI thread. The area is the decision {@link #execute} already accepted.
     * </p>
     *
     * @param project the project
     * @param decision the accepted walk
     * @param severityFilter the severity floor
     * @param format json or markdown
     * @param enabledRules the rules to run, or <code>null</code> for all of them
     * @return the JSON answer
     */
    String runScan(IProject project, WalkNarrowing.Decision decision, String severityFilter,
        String format, Set<String> enabledRules) throws Exception
    {
        WalkNarrowing.MethodSpan span = null;
        List<IFile> bslFiles;
        if (WalkNarrowing.PROJECT.equals(decision.area()))
        {
            bslFiles = collectAllBsl(project);
        }
        else
        {
            BslModuleAccess.ModulePathResolution resolution =
                BslModuleAccess.resolveModulePath(project, decision.moduleFqn());
            if (!resolution.isResolved())
            {
                return ToolResult.error(resolution.getHint()).toJson();
            }
            IFile module = project.getFile(new Path("src").append(resolution.getPath())); //$NON-NLS-1$
            if (!module.exists())
            {
                return ToolResult.error("Module '" + decision.moduleFqn() + "' was not found.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (WalkNarrowing.METHOD.equals(decision.area()))
            {
                span = WalkNarrowing.locateMethod(readFile(module), decision.methodName());
                if (span == null)
                {
                    return ToolResult.error(
                        WalkNarrowing.methodNotFound(decision.methodName(), decision.moduleFqn())).toJson();
                }
            }
            bslFiles = java.util.Collections.singletonList(module);
        }
        String scope = decision.area();
        List<Map<String, Object>> findings = new ArrayList<>();
        int queriesAnalyzed = 0;
        // Read at the file boundary: stopping between files leaves the findings so far
        // whole, and stopping inside one would leave half a module's worth.
        WatchForCancel watch = WatchForCancel.begin();
        int modulesScanned = 0;
        for (IFile file : bslFiles)
        {
            if (watch.stopHere())
            {
                break;
            }
            String content = readFile(file);
            if (content == null)
            {
                continue;
            }
            // Counted after the read, not before it: a file that could not be opened
            // was not scanned, and reporting it as scanned hides that the rules never
            // ran against it.
            modulesScanned++;
            // QUERY_IN_LOOP - module-level pattern (BSL loop containing query.execute)
            if (isEnabled("QUERY_IN_LOOP", enabledRules)) //$NON-NLS-1$
            {
                detectQueryInLoop(file, content, findings, span);
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
                int line = lineAt(content, m.start());
                // A query that starts outside the named method is outside the walk, even though
                // the file was opened to reach the method that is inside it.
                if (span != null && !span.contains(line))
                {
                    continue;
                }
                queriesAnalyzed++;
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
        // What the scan got through, not what it was offered: after an early exit the
        // candidate count is the number of modules it did NOT read.
        stats.put("modulesScanned", modulesScanned); //$NON-NLS-1$
        stats.put("queriesAnalyzed", queriesAnalyzed); //$NON-NLS-1$
        stats.put("findings", findings.size()); //$NON-NLS-1$

        if ("markdown".equalsIgnoreCase(format)) //$NON-NLS-1$
        {
            return ToolResult.success()
                .put("scope", scope) //$NON-NLS-1$
                .put("statistics", stats) //$NON-NLS-1$
                .put("text", renderMarkdown(findings, stats, watch.note("files"))) //$NON-NLS-1$
                .put("cancelled", watch.note("files")) //$NON-NLS-1$
                .toJson();
        }
        return ToolResult.success()
            .put("scope", scope) //$NON-NLS-1$
            .put("statistics", stats) //$NON-NLS-1$
            .put("issues", findings) //$NON-NLS-1$
                .put("cancelled", watch.note("files")) //$NON-NLS-1$
            .toJson();
    }

    private void detectQueryInLoop(IFile file, String content, List<Map<String, Object>> findings,
        WalkNarrowing.MethodSpan span)
    {
        Matcher loopMatcher = LOOP_BLOCK.matcher(content);
        while (loopMatcher.find())
        {
            int line = lineAt(content, loopMatcher.start());
            if (span != null && !span.contains(line))
            {
                continue;
            }
            String loopBody = loopMatcher.group(3);
            if (loopBody != null && QUERY_EXEC_IN_BSL.matcher(loopBody).find())
            {
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

    private List<IFile> collectAllBsl(IProject project) throws Exception
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
        return all;
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
        Map<String, Object> stats, String cancelled)
    {
        StringBuilder sb = new StringBuilder("# Query Anti-Patterns Report\n\n"); //$NON-NLS-1$
        sb.append("**Modules scanned:** ").append(stats.get("modulesScanned")).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        sb.append("**Queries analyzed:** ").append(stats.get("queriesAnalyzed")).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        sb.append("**Findings:** ").append(stats.get("findings")).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (cancelled != null)
        {
            sb.append("> **").append(cancelled).append("**\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (findings.isEmpty())
        {
            // Only sayable when the scan finished. Stopped short, nothing found
            // means nothing was looked at, which is a different statement.
            sb.append(cancelled != null
                ? "Nothing had been found when the scan stopped.\n" //$NON-NLS-1$
                : "No anti-patterns detected.\n"); //$NON-NLS-1$
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
