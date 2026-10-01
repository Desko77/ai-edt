/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Collects line-level profiling data from the EDT profiling core after a test run. Walks each profiling
 * result, groups its lines by module, applies an optional name filter and minimum call count, and clips
 * long source lines and per-module line counts.
 */
public final class ProfilingResultsReader implements IMcpTool
{
    private static final String NAME = "get_profiling_results"; //$NON-NLS-1$

    private static final String DESC = "Back-compat alias of `launch_debugger` `action=get_profiling_results`; prefer the facade for new prompts. " //$NON-NLS-1$
        + "Fetch the profiling (performance measurement) data collected during a debug session. " //$NON-NLS-1$
        + "Reports per-module, per-line figures: call count, timing, and percentage of total time. Module name can be filtered. " //$NON-NLS-1$
        + "Run this after start_profiling and the test itself."; //$NON-NLS-1$

    private static final String WIRING_BUNDLE = "com._1c.g5.wiring"; //$NON-NLS-1$
    private static final String PROFILING_CORE_BUNDLE = "com._1c.g5.v8.dt.profiling.core"; //$NON-NLS-1$

    private static final int MAX_LINES_PER_MODULE = 200;

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
            .stringProperty("moduleFilter", "Optional substring to match against module names") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("minFrequency", "Keep only lines called N times or more (defaults to 1)") //$NON-NLS-1$ //$NON-NLS-2$
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
        String moduleFilter = JsonUtils.extractStringArgument(params, "moduleFilter"); //$NON-NLS-1$
        int minFrequency = JsonUtils.extractIntArgument(params, "minFrequency", 1); //$NON-NLS-1$

        try
        {
            Bundle wiringBundle = Platform.getBundle(WIRING_BUNDLE);
            if (wiringBundle == null)
            {
                return ToolResult.error("Could not locate the wiring bundle").toJson(); //$NON-NLS-1$
            }

            Bundle profilingBundle = Platform.getBundle(PROFILING_CORE_BUNDLE);
            if (profilingBundle == null)
            {
                return ToolResult.error("Could not locate the profiling core bundle").toJson(); //$NON-NLS-1$
            }

            Class<?> serviceAccessClass = wiringBundle.loadClass("com._1c.g5.wiring.ServiceAccess"); //$NON-NLS-1$
            Class<?> profilingServiceClass = profilingBundle
                .loadClass("com._1c.g5.v8.dt.profiling.core.IProfilingService"); //$NON-NLS-1$

            Method getMethod = serviceAccessClass.getMethod("get", Class.class); //$NON-NLS-1$
            Object profilingService = getMethod.invoke(null, profilingServiceClass);
            if (profilingService == null)
            {
                return ToolResult.error("IProfilingService is unavailable; the profiling bundle may not be active") //$NON-NLS-1$
                    .toJson();
            }

            Method getResults = profilingServiceClass.getMethod("getResults"); //$NON-NLS-1$
            List<?> results = (List<?>)getResults.invoke(profilingService);

            if (results == null || results.isEmpty())
            {
                return ToolResult.success().put("count", 0) //$NON-NLS-1$
                    .put("message", //$NON-NLS-1$
                        "No profiling data is available yet. Confirm start_profiling was called before " //$NON-NLS-1$
                            + "the test ran.") //$NON-NLS-1$
                    .toJson();
            }

            Class<?> profilingResultClass = profilingBundle
                .loadClass("com._1c.g5.v8.dt.profiling.core.IProfilingResult"); //$NON-NLS-1$
            Class<?> lineResultClass = profilingBundle
                .loadClass("com._1c.g5.v8.dt.profiling.core.ILineProfilingResult"); //$NON-NLS-1$
            Class<?> timeHolderClass = profilingBundle
                .loadClass("com._1c.g5.v8.dt.profiling.core.IProfilingTimeHolder"); //$NON-NLS-1$

            Method getResultName = profilingResultClass.getMethod("getName"); //$NON-NLS-1$
            Method getTotalDurability = profilingResultClass.getMethod("getTotalDurability"); //$NON-NLS-1$
            // The interface declares getProfilingResults; the name that stood here was this tool's
            // own MCP operation name, which no Java method has ever answered to. It threw only
            // after the "any results at all" check above, so the tool worked precisely while there
            // was nothing to report and failed the moment there was.
            Method getProfilingResults = profilingResultClass.getMethod("getProfilingResults"); //$NON-NLS-1$
            Method getLineNo = lineResultClass.getMethod("getLineNo"); //$NON-NLS-1$
            Method getFrequency = lineResultClass.getMethod("getFrequency"); //$NON-NLS-1$
            Method getModuleName = lineResultClass.getMethod("getModuleName"); //$NON-NLS-1$
            Method getLine = lineResultClass.getMethod("getLine"); //$NON-NLS-1$
            Method getPercentage = lineResultClass.getMethod("getPercentage"); //$NON-NLS-1$
            Method getMethodSignature = lineResultClass.getMethod("getMethodSignature"); //$NON-NLS-1$
            Method getDurability = timeHolderClass.getMethod("getDurability"); //$NON-NLS-1$
            Method getPureDurability = timeHolderClass.getMethod("getPureDurability"); //$NON-NLS-1$

            List<Map<String, Object>> resultSummaries = new ArrayList<>();

            for (Object result : results)
            {
                Map<String, Object> summary = new LinkedHashMap<>();
                String name = (String)getResultName.invoke(result);
                double totalDur = ((Number)getTotalDurability.invoke(result)).doubleValue();
                summary.put("name", name); //$NON-NLS-1$
                summary.put("totalDurability", Math.round(totalDur * 1000.0) / 1000.0); //$NON-NLS-1$

                List<?> lineResults = (List<?>)getProfilingResults.invoke(result);
                if (lineResults == null)
                {
                    summary.put("lines", 0); //$NON-NLS-1$
                    resultSummaries.add(summary);
                    continue;
                }

                Map<String, List<Map<String, Object>>> moduleGroups = new LinkedHashMap<>();

                for (Object lr : lineResults)
                {
                    long freq = ((Number)getFrequency.invoke(lr)).longValue();
                    if (freq < minFrequency)
                    {
                        continue;
                    }

                    String modName = (String)getModuleName.invoke(lr);
                    if (modName == null)
                    {
                        modName = "?"; //$NON-NLS-1$
                    }

                    if (moduleFilter != null && !moduleFilter.isEmpty()
                        && !modName.toLowerCase(Locale.ROOT).contains(moduleFilter.toLowerCase(Locale.ROOT)))
                    {
                        continue;
                    }

                    List<Map<String, Object>> lines = moduleGroups.computeIfAbsent(modName, k -> new ArrayList<>());

                    Map<String, Object> lineInfo = new LinkedHashMap<>();
                    lineInfo.put("line", getLineNo.invoke(lr)); //$NON-NLS-1$
                    lineInfo.put("calls", freq); //$NON-NLS-1$
                    lineInfo.put("pct", //$NON-NLS-1$
                        Math.round(((Number)getPercentage.invoke(lr)).doubleValue() * 100.0) / 100.0);
                    lineInfo.put("dur", //$NON-NLS-1$
                        Math.round(((Number)getDurability.invoke(lr)).doubleValue() * 1000.0) / 1000.0);
                    lineInfo.put("pureDur", //$NON-NLS-1$
                        Math.round(((Number)getPureDurability.invoke(lr)).doubleValue() * 1000.0) / 1000.0);

                    String code = (String)getLine.invoke(lr);
                    if (code != null && code.length() > 120)
                    {
                        code = code.substring(0, 120) + "..."; //$NON-NLS-1$
                    }
                    lineInfo.put("code", code); //$NON-NLS-1$
                    lineInfo.put("method", getMethodSignature.invoke(lr)); //$NON-NLS-1$

                    lines.add(lineInfo);
                }

                int totalLines = 0;
                int droppedLines = 0;
                for (Map.Entry<String, List<Map<String, Object>>> group : moduleGroups.entrySet())
                {
                    List<Map<String, Object>> moduleLines = group.getValue();
                    totalLines += moduleLines.size();
                    List<Map<String, Object>> kept = clipLinesByWeight(moduleLines, MAX_LINES_PER_MODULE);
                    droppedLines += moduleLines.size() - kept.size();
                    group.setValue(kept);
                }

                summary.put("totalLines", totalLines); //$NON-NLS-1$
                summary.put("droppedLines", droppedLines); //$NON-NLS-1$
                summary.put("moduleCount", moduleGroups.size()); //$NON-NLS-1$
                summary.put("modules", moduleGroups); //$NON-NLS-1$
                resultSummaries.add(summary);
            }

            return ToolResult.success().put("count", resultSummaries.size()) //$NON-NLS-1$
                .put("results", resultSummaries).toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("get_profiling_results failed", e); //$NON-NLS-1$
            return ToolResult.error("Failed: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Keeps the heaviest lines of one module when there are more than the cap allows.
     * <p>
     * Weight is the line's total time, ties broken by the call count. Cutting by arrival order
     * would keep whichever lines the profiler happened to list first, and the caller would read
     * the beginning of the module instead of its hot spots, with nothing in the answer saying so.
     * </p>
     *
     * @param lines the module's line entries, each carrying "dur" and "calls" numbers
     * @param max how many entries to keep
     * @return the kept entries, heaviest first; the input list itself when it fits
     */
    static List<Map<String, Object>> clipLinesByWeight(List<Map<String, Object>> lines, int max)
    {
        if (lines.size() <= max)
        {
            return lines;
        }
        List<Map<String, Object>> sorted = new ArrayList<>(lines);
        sorted.sort((left, right) ->
        {
            int byTime = Double.compare(weightOf(right, "dur"), weightOf(left, "dur")); //$NON-NLS-1$
            return byTime != 0 ? byTime
                : Double.compare(weightOf(right, "calls"), weightOf(left, "calls")); //$NON-NLS-1$
        });
        return new ArrayList<>(sorted.subList(0, max));
    }

    /**
     * Reads a numeric entry of a line map, absent or non-numeric counting as zero.
     *
     * @param line the line entry
     * @param key the entry to read
     * @return the entry's value as a double
     */
    private static double weightOf(Map<String, Object> line, String key)
    {
        Object value = line.get(key);
        return value instanceof Number ? ((Number)value).doubleValue() : 0.0;
    }
}
