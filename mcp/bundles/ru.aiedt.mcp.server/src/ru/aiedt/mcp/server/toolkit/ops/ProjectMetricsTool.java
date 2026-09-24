/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import ru.aiedt.mcp.server.support.WatchForCancel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectMetricsCollector;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.SubsystemMembership;
import ru.aiedt.mcp.server.support.UiSync;
import ru.aiedt.mcp.server.support.WalkNarrowing;

/**
 * Project-wide metrics: objects / modules / methods / errors / tests / forms /
 * debt indicators. Sequential pipeline (not parallel - avoids workspace lock
 * contention with BM read tasks vs IMarker API).
 */
public class ProjectMetricsTool implements IMcpTool
{
    public static final String NAME = "project_metrics"; //$NON-NLS-1$

    private static final List<String> SCOPES = List.of(WalkNarrowing.PROJECT, WalkNarrowing.SUBSYSTEM);

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `insights` `operation=project_metrics`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Project-wide metrics: objects by type, modules LOC, methods cyclomatic complexity, " //$NON-NLS-1$
            + "EDT errors by severity, YAXUnit tests, forms, debt indicators (long methods, " //$NON-NLS-1$
            + "high complexity, too many parameters). Sequential pipeline. Returns partial=true " //$NON-NLS-1$
            + "if timeoutSeconds (default 60) is reached."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project to work in", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("scope", //$NON-NLS-1$
                "project | subsystem, and absent, subsystemName selects the subsystem.") //$NON-NLS-1$
            .stringProperty("subsystemName", //$NON-NLS-1$
                "Subsystem name when the walk is a subsystem.") //$NON-NLS-1$
            .stringProperty("format", "json | markdown (default json)") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("includeDebtList", //$NON-NLS-1$
                "Include detailed debt items list with file:line. Default false.") //$NON-NLS-1$
            .integerProperty("timeoutSeconds", "Timeout cap (default 60)") //$NON-NLS-1$ //$NON-NLS-2$
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
        boolean includeDebtList = JsonUtils.extractBooleanArgument(params, "includeDebtList", //$NON-NLS-1$
            false);
        int timeoutSeconds = parseInt(params, "timeoutSeconds", 60); //$NON-NLS-1$
        String format = orDefault(JsonUtils.extractStringArgument(params, "format"), "json"); //$NON-NLS-1$ //$NON-NLS-2$
        WalkNarrowing.Decision decision = WalkNarrowing.decide(params, SCOPES,
            WalkNarrowing.Selectors.SUBSYSTEM_ONLY);
        if (decision.refused())
        {
            return ToolResult.error(decision.refusal()).toJson();
        }

        try
        {
            return UiSync.call(() -> {
                try
                {
                    return collect(project, decision, includeDebtList, timeoutSeconds, format);
                }
                catch (Exception e)
                {
                    Activator.logError("project_metrics error", e); //$NON-NLS-1$
                    return ToolResult.error(e.getMessage()).toJson();
                }
            });
        }
        catch (UiSync.UiBusyException e)
        {
            return ToolResult.error(e.getMessage()).put("tag", e.tag()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * The collection itself, without the UI-thread hop {@link #execute} wraps it in.
     *
     * @param project the project
     * @param decision the accepted walk
     * @param includeDebtList whether debt items are listed
     * @param timeoutSeconds the deadline
     * @param format json or markdown
     * @return the JSON answer
     */
    String collect(IProject project, WalkNarrowing.Decision decision, boolean includeDebtList,
        int timeoutSeconds, String format) throws Exception
    {
        SubsystemMembership resolved = null;
        if (WalkNarrowing.SUBSYSTEM.equals(decision.area()))
        {
            resolved = SubsystemMembership.resolve(project,
                SubsystemMembership.configurationOf(project), decision.subsystemName());
            if (resolved.refused())
            {
                return ToolResult.error(resolved.refusal()).toJson();
            }
        }
        final SubsystemMembership membership = resolved;
        Predicate<IResource> inComposition = membership == null ? null
            : resource -> membership.coversPath(resource.getProjectRelativePath().toString());
        // One watch for the whole call. The two walks that read files - modules and
        // forms - both ask it; the reflection over the configuration between them is
        // a handful of calls and is left alone.
        WatchForCancel watch = WatchForCancel.begin();
        ProjectMetricsCollector collector = new ProjectMetricsCollector(project, timeoutSeconds,
            includeDebtList);
        String scope = decision.area();

        // Four steps, each asked before it starts: a stop during the module scan must not be
        // followed by the marker, metadata and form work the operator asked to end. A step that
        // does not run is then absent from the answer rather than present as zero - toMetrics
        // reads these nulls and leaves a gap, because a zero is what a project with no errors
        // and no forms reports.
        List<IFile> bslFiles = limit(collectBslFiles(project), membership);
        collector.scanBsl(bslFiles, watch);

        boolean markersScanned = !watch.raised();
        if (markersScanned)
        {
            collector.scanMarkers(inComposition);
        }
        Map<String, Integer> objectsByType = watch.raised() ? null : objectsByType(project, membership);
        ProjectMetricsCollector.FormCounts forms = null;
        if (!watch.raised())
        {
            FormStats measured = collectFormStats(project, watch, membership);
            forms = new ProjectMetricsCollector.FormCounts();
            forms.count = measured.formCount;
            forms.totalItems = measured.totalItems;
            forms.largerThan100 = measured.largeFormsOver100;
        }

        if (watch.stopped())
        {
            collector.markPartial();
        }
        Map<String, Object> metrics = collector.toMetrics(objectsByType, forms, markersScanned);

        if ("markdown".equalsIgnoreCase(format)) //$NON-NLS-1$
        {
            return composition(ToolResult.success(), membership)
                .put("scope", scope) //$NON-NLS-1$
                .put("format", "markdown") //$NON-NLS-1$ //$NON-NLS-2$
                .put("text", renderMarkdown(metrics, watch.note("files"))) //$NON-NLS-1$ //$NON-NLS-2$
                .put("cancelled", watch.note("files")) //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }
        ToolResult tr = composition(ToolResult.success(), membership).put("scope", scope) //$NON-NLS-1$
            .put("cancelled", watch.note("files")); //$NON-NLS-1$
        for (Map.Entry<String, Object> entry : metrics.entrySet())
        {
            tr.put(entry.getKey(), entry.getValue());
        }
        return tr.toJson();
    }

    private List<IFile> collectBslFiles(IProject project) throws Exception
    {
        List<IFile> files = new ArrayList<>();
        IResourceVisitor visitor = new IResourceVisitor()
        {
            @Override
            public boolean visit(IResource resource)
            {
                if (resource instanceof IFile && resource.getName().endsWith(".bsl")) //$NON-NLS-1$
                {
                    files.add((IFile) resource);
                }
                return true;
            }
        };
        project.accept(visitor, IResource.DEPTH_INFINITE, IResource.NONE);
        return files;
    }

    private List<IFile> limit(List<IFile> files, SubsystemMembership membership)
    {
        if (membership == null)
        {
            return files;
        }
        List<IFile> kept = new ArrayList<>();
        for (IFile file : files)
        {
            if (membership.coversPath(file.getProjectRelativePath().toString()))
            {
                kept.add(file);
            }
        }
        return kept;
    }

    private Map<String, Integer> objectsByType(IProject project, SubsystemMembership membership)
    {
        if (membership == null)
        {
            return collectObjectsByType(project);
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String fqn : membership.objectFqns())
        {
            String type = typeKey(fqn);
            if (type == null)
            {
                continue;
            }
            Integer soFar = result.get(type);
            result.put(type, Integer.valueOf(soFar == null ? 1 : soFar.intValue() + 1));
        }
        return result;
    }

    private static String typeKey(String fqn)
    {
        int dot = fqn.indexOf('.');
        String typeName = dot < 0 ? fqn : fqn.substring(0, dot);
        MetadataTypeCatalog.MetadataTypeInfo type = MetadataTypeCatalog.resolve(typeName);
        return type == null ? typeName : type.getEnglishPlural();
    }

    private static ToolResult composition(ToolResult result, SubsystemMembership membership)
    {
        if (membership == null)
        {
            return result;
        }
        return result.put("subsystemName", membership.subsystemName()) //$NON-NLS-1$
            .put("nestedSubsystemsIncluded", true) //$NON-NLS-1$
            .put("compositionSize", membership.compositionSize()) //$NON-NLS-1$
            .put("composition", membership.compositionNote()); //$NON-NLS-1$
    }

    private Map<String, Integer> collectObjectsByType(IProject project)
    {
        Map<String, Integer> result = new LinkedHashMap<>();
        IConfigurationProvider provider = Activator.getDefault().getConfigurationProvider();
        if (provider == null)
        {
            return result;
        }
        Configuration configuration = provider.getConfiguration(project);
        if (configuration == null)
        {
            return result;
        }
        for (java.lang.reflect.Method m : configuration.getClass().getMethods())
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
                Object value = m.invoke(configuration);
                if (value instanceof java.util.List)
                {
                    java.util.List<?> list = (java.util.List<?>) value;
                    if (list.isEmpty())
                    {
                        continue;
                    }
                    int mdCount = 0;
                    for (Object item : list)
                    {
                        if (item instanceof MdObject)
                        {
                            mdCount++;
                        }
                    }
                    if (mdCount > 0)
                    {
                        String type = name.substring(3); // strip "get"
                        result.put(type, mdCount);
                    }
                }
            }
            catch (Throwable ignored)
            {
                // best-effort scan
            }
        }
        return result;
    }

    private FormStats collectFormStats(IProject project, WatchForCancel watch,
        SubsystemMembership membership) throws Exception
    {
        FormStats stats = new FormStats();
        IResourceVisitor visitor = new IResourceVisitor()
        {
            @Override
            public boolean visit(IResource resource)
            {
                if (resource instanceof IFile && resource.getName().endsWith(".form") //$NON-NLS-1$
                    && (membership == null || membership.coversPath(
                        resource.getProjectRelativePath().toString())))
                {
                    if (watch.stopHere())
                    {
                        // Thrown, not answered false: false skips this resource and
                        // leaves the rest of the project to walk.
                        throw new org.eclipse.core.runtime.OperationCanceledException();
                    }
                    stats.formCount++;
                    int items = countFormItems((IFile) resource);
                    stats.totalItems += items;
                    if (items > 100)
                    {
                        stats.largeFormsOver100++;
                    }
                }
                return true;
            }
        };
        try
        {
            project.accept(visitor, IResource.DEPTH_INFINITE, IResource.NONE);
        }
        catch (org.eclipse.core.runtime.OperationCanceledException stopped)
        {
            // The counts gathered so far are kept, and the answer says they are
            // part of the project rather than all of it.
        }
        return stats;
    }

    private static int countFormItems(IFile file)
    {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(file.getContents(), java.nio.charset.StandardCharsets.UTF_8)))
        {
            int items = 0;
            String line;
            while ((line = reader.readLine()) != null)
            {
                if (line.contains("<items") || line.contains("<children")) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    items++;
                }
            }
            return items;
        }
        catch (Exception e)
        {
            return 0;
        }
    }

    private static String renderMarkdown(Map<String, Object> metrics, String cancelled)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("# Project metrics\n\n"); //$NON-NLS-1$
        if (cancelled != null)
        {
            sb.append("> **").append(cancelled).append("**\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (Boolean.TRUE.equals(metrics.get("partial"))) //$NON-NLS-1$
        {
            // Neither the cause nor the phase is named: the deadline and the operator both set it,
            // and either can stop any of the four steps. The line above says which when it was the
            // operator.
            sb.append("> **partial=true** - the scan did not finish; every count below is a"
                + " floor\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        appendMapAsTable(sb, "Objects", (Map<?, ?>) metrics.get("objects")); //$NON-NLS-1$ //$NON-NLS-2$
        appendMapAsTable(sb, "Modules", (Map<?, ?>) metrics.get("modules")); //$NON-NLS-1$ //$NON-NLS-2$
        appendMapAsTable(sb, "Methods", (Map<?, ?>) metrics.get("methods")); //$NON-NLS-1$ //$NON-NLS-2$
        appendMapAsTable(sb, "Errors", (Map<?, ?>) metrics.get("errors")); //$NON-NLS-1$ //$NON-NLS-2$
        appendMapAsTable(sb, "Tests", (Map<?, ?>) metrics.get("tests")); //$NON-NLS-1$ //$NON-NLS-2$
        appendMapAsTable(sb, "Forms", (Map<?, ?>) metrics.get("forms")); //$NON-NLS-1$ //$NON-NLS-2$
        appendMapAsTable(sb, "Debt", (Map<?, ?>) metrics.get("debt")); //$NON-NLS-1$ //$NON-NLS-2$
        return sb.toString();
    }

    private static void appendMapAsTable(StringBuilder sb, String title, Map<?, ?> data)
    {
        if (data == null || data.isEmpty())
        {
            return;
        }
        sb.append("## ").append(title).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("| Key | Value |\n|---|---|\n"); //$NON-NLS-1$
        for (Map.Entry<?, ?> entry : data.entrySet())
        {
            Object key = entry.getKey();
            Object value = entry.getValue();
            String valueStr = value == null ? "" //$NON-NLS-1$
                : value instanceof java.util.List ? ("[" + ((java.util.List<?>) value).size() + " items]") //$NON-NLS-1$ //$NON-NLS-2$
                    : value.toString();
            sb.append("| ").append(key).append(" | ").append(valueStr).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        sb.append("\n"); //$NON-NLS-1$
    }

    private static class FormStats
    {
        int formCount;
        int totalItems;
        int largeFormsOver100;
    }

    // ---- helpers -----------------------------------------------------------

    private static String orDefault(String value, String fallback)
    {
        return value != null && !value.isEmpty() ? value : fallback;
    }

    private static int parseInt(Map<String, String> params, String key, int fallback)
    {
        String s = JsonUtils.extractStringArgument(params, key);
        if (s == null || s.isEmpty())
        {
            return fallback;
        }
        try
        {
            return Integer.parseInt(s.trim());
        }
        catch (NumberFormatException nfe)
        {
            return fallback;
        }
    }
}
