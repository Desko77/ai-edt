/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

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
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;

import ru.aiedt.mcp.server.Activator;

/**
 * Sequential pipeline that collects metrics for a project / subsystem:
 * objects, modules, methods, errors, tests, forms, debt indicators.
 * <p>
 * Learned the hard way: parallel collection is not feasible due
 * to workspace lock contention between BM read-tasks and IMarker API. The
 * pipeline runs sequentially. No cache between calls (stale-data risk); cache
 * scope is one collector instance.
 */
public final class ProjectMetricsCollector
{
    private static final Pattern PROC_PATTERN = Pattern
        .compile("^\\s*(Процедура|Функция|Procedure|Function)\\s+([\\w_]+)", Pattern.MULTILINE); //$NON-NLS-1$

    private static final Pattern BRANCH_PATTERN = Pattern
        .compile("\\b(Если|ИначеЕсли|Цикл|Попытка|If|ElsIf|For|While|Try|Case|Когда)\\b", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE);

    private static final Pattern YAXUNIT_PATTERN = Pattern.compile(
        "&YaxUnitTestSuite|&Test|РегистрацияТестов", Pattern.CASE_INSENSITIVE); //$NON-NLS-1$

    private final IProject project;
    private final long deadlineMillis;
    private final boolean includeDebtList;

    private int totalLoc;
    private int moduleCount;
    private int methodCount;
    private int totalComplexity;
    private int maxComplexity;
    private int complexHotMethods; // complexity > 15
    private int testModules;
    private int testMethods;
    private int errorCount;
    private int warningCount;
    private int infoCount;
    private int codeStyleCount;
    private final List<Map<String, Object>> debtItems = new ArrayList<>();
    private final List<String> unscannedModules = new ArrayList<>();
    private boolean partial;

    public ProjectMetricsCollector(IProject project, long timeoutSeconds, boolean includeDebtList)
    {
        this.project = project;
        long now = System.currentTimeMillis();
        long bound = timeoutSeconds <= 0 ? Long.MAX_VALUE : timeoutSeconds * 1000L;
        this.deadlineMillis = bound == Long.MAX_VALUE ? Long.MAX_VALUE : now + bound;
        this.includeDebtList = includeDebtList;
    }

    /**
     * Runs the BSL scan: walks every {@code .bsl} file in the project, counts
     * LOC, methods, complexity, debt indicators, test modules.
     */
    public void scanBsl(List<IFile> bslFiles)
    {
        if (bslFiles == null || bslFiles.isEmpty())
        {
            return;
        }
        for (IFile file : bslFiles)
        {
            if (System.currentTimeMillis() > deadlineMillis)
            {
                partial = true;
                unscannedModules.add(file.getFullPath().toString());
                continue;
            }
            try
            {
                scanBslFile(file);
                moduleCount++;
            }
            catch (Exception e)
            {
                Activator.logWarning("ProjectMetricsCollector: failed to scan " //$NON-NLS-1$
                    + file.getFullPath() + ": " + e.getMessage()); //$NON-NLS-1$
                unscannedModules.add(file.getFullPath().toString());
            }
        }
    }

    private void scanBslFile(IFile file) throws CoreException, java.io.IOException
    {
        StringBuilder buffer = new StringBuilder();
        int loc = 0;
        boolean isTestModule = false;
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(file.getContents(), StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("//")) //$NON-NLS-1$
                {
                    loc++;
                }
                buffer.append(line).append('\n');
                if (!isTestModule && YAXUNIT_PATTERN.matcher(line).find())
                {
                    isTestModule = true;
                }
            }
        }
        totalLoc += loc;
        if (isTestModule)
        {
            testModules++;
        }
        analyzeMethods(file, buffer.toString(), isTestModule);
    }

    private void analyzeMethods(IFile file, String content, boolean isTestModule)
    {
        Matcher matcher = PROC_PATTERN.matcher(content);
        int lastIndex = 0;
        String lastName = null;
        int lastLineStart = 0;
        while (matcher.find())
        {
            if (lastName != null)
            {
                String body = content.substring(lastIndex, matcher.start());
                processMethod(file, lastName, body, lastLineStart, isTestModule);
            }
            lastName = matcher.group(2);
            lastIndex = matcher.end();
            lastLineStart = countLines(content, matcher.start());
        }
        if (lastName != null)
        {
            String body = content.substring(lastIndex);
            processMethod(file, lastName, body, lastLineStart, isTestModule);
        }
    }

    private static int countLines(String content, int upTo)
    {
        int count = 1;
        for (int i = 0; i < upTo && i < content.length(); i++)
        {
            if (content.charAt(i) == '\n')
            {
                count++;
            }
        }
        return count;
    }

    private void processMethod(IFile file, String name, String body, int startLine,
        boolean isTestModule)
    {
        methodCount++;
        if (isTestModule && name.toLowerCase().contains("test")) //$NON-NLS-1$
        {
            testMethods++;
        }
        int complexity = 1; // base path
        Matcher br = BRANCH_PATTERN.matcher(body);
        while (br.find())
        {
            complexity++;
        }
        totalComplexity += complexity;
        if (complexity > maxComplexity)
        {
            maxComplexity = complexity;
        }
        int methodLoc = 0;
        int paramCount = countParams(body);
        for (int i = 0; i < body.length(); i++)
        {
            if (body.charAt(i) == '\n')
            {
                methodLoc++;
            }
        }
        if (complexity > 15)
        {
            complexHotMethods++;
            if (includeDebtList)
            {
                debtItems.add(debtItem(file, startLine, "highComplexity", //$NON-NLS-1$
                    "method " + name + " complexity=" + complexity)); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        if (methodLoc > 100 && includeDebtList)
        {
            debtItems.add(debtItem(file, startLine, "longMethod", //$NON-NLS-1$
                "method " + name + " loc=" + methodLoc)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (paramCount > 7 && includeDebtList)
        {
            debtItems.add(debtItem(file, startLine, "tooManyParameters", //$NON-NLS-1$
                "method " + name + " params=" + paramCount)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static int countParams(String body)
    {
        int open = body.indexOf('(');
        if (open < 0)
        {
            return 0;
        }
        int close = body.indexOf(')', open);
        if (close <= open + 1)
        {
            return 0;
        }
        String inside = body.substring(open + 1, close);
        if (inside.trim().isEmpty())
        {
            return 0;
        }
        return inside.split(",").length; //$NON-NLS-1$
    }

    private Map<String, Object> debtItem(IFile file, int line, String kind, String description)
    {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("file", file.getProjectRelativePath().toString()); //$NON-NLS-1$
        item.put("line", line); //$NON-NLS-1$
        item.put("kind", kind); //$NON-NLS-1$
        item.put("description", description); //$NON-NLS-1$
        return item;
    }

    /**
     * Collects EDT marker-based metrics (errors / warnings / info / code style).
     */
    public void scanMarkers()
    {
        try
        {
            IMarker[] markers = project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
            for (IMarker marker : markers)
            {
                int severity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_WARNING);
                String type = marker.getType() != null ? marker.getType() : ""; //$NON-NLS-1$
                if (type.contains("codestyle") || type.contains("style")) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    codeStyleCount++;
                    continue;
                }
                switch (severity)
                {
                    case IMarker.SEVERITY_ERROR:
                        errorCount++;
                        break;
                    case IMarker.SEVERITY_WARNING:
                        warningCount++;
                        break;
                    case IMarker.SEVERITY_INFO:
                        infoCount++;
                        break;
                    default:
                        infoCount++;
                        break;
                }
            }
        }
        catch (CoreException e)
        {
            Activator.logWarning("ProjectMetricsCollector: marker scan failed: " //$NON-NLS-1$
                + e.getMessage());
        }
    }

    /**
     * Renders metrics as a structured map suitable for ToolResult.
     */
    public Map<String, Object> toMetrics(Map<String, Integer> objectsByType, int formCount,
        int formItemsTotal, int formsLargerThan100)
    {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("partial", partial); //$NON-NLS-1$

        Map<String, Object> objects = new LinkedHashMap<>();
        if (objectsByType != null)
        {
            objects.putAll(objectsByType);
        }
        metrics.put("objects", objects); //$NON-NLS-1$

        Map<String, Object> modules = new LinkedHashMap<>();
        modules.put("count", moduleCount); //$NON-NLS-1$
        modules.put("totalLoc", totalLoc); //$NON-NLS-1$
        modules.put("avgLoc", moduleCount == 0 ? 0 : totalLoc / moduleCount); //$NON-NLS-1$
        metrics.put("modules", modules); //$NON-NLS-1$

        Map<String, Object> methods = new LinkedHashMap<>();
        methods.put("count", methodCount); //$NON-NLS-1$
        methods.put("avgComplexity", methodCount == 0 ? 0 : totalComplexity / methodCount); //$NON-NLS-1$
        methods.put("maxComplexity", maxComplexity); //$NON-NLS-1$
        methods.put("complexHotMethods", complexHotMethods); //$NON-NLS-1$
        metrics.put("methods", methods); //$NON-NLS-1$

        Map<String, Object> errors = new LinkedHashMap<>();
        errors.put("error", errorCount); //$NON-NLS-1$
        errors.put("warning", warningCount); //$NON-NLS-1$
        errors.put("info", infoCount); //$NON-NLS-1$
        errors.put("codeStyle", codeStyleCount); //$NON-NLS-1$
        metrics.put("errors", errors); //$NON-NLS-1$

        Map<String, Object> tests = new LinkedHashMap<>();
        tests.put("modules", testModules); //$NON-NLS-1$
        tests.put("methods", testMethods); //$NON-NLS-1$
        tests.put("yaxunitDetected", testModules > 0); //$NON-NLS-1$
        metrics.put("tests", tests); //$NON-NLS-1$

        Map<String, Object> forms = new LinkedHashMap<>();
        forms.put("count", formCount); //$NON-NLS-1$
        forms.put("totalItems", formItemsTotal); //$NON-NLS-1$
        forms.put("avgItems", formCount == 0 ? 0 : formItemsTotal / formCount); //$NON-NLS-1$
        forms.put("largeFormsOver100Items", formsLargerThan100); //$NON-NLS-1$
        metrics.put("forms", forms); //$NON-NLS-1$

        Map<String, Object> debt = new LinkedHashMap<>();
        debt.put("count", debtItems.size()); //$NON-NLS-1$
        if (includeDebtList)
        {
            debt.put("items", debtItems); //$NON-NLS-1$
        }
        metrics.put("debt", debt); //$NON-NLS-1$

        if (!unscannedModules.isEmpty())
        {
            metrics.put("unscannedModules", unscannedModules); //$NON-NLS-1$
        }
        return metrics;
    }

    public boolean isPartial()
    {
        return partial;
    }
}
