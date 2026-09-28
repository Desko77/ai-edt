/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.MarkdownTableHelper;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * Counts the workspace's validation problems, once by severity across everything and once per project.
 * <p>
 * The overall table walks the severity enum, so its rows are in a fixed order; the per-project table
 * walks a hash map, so its rows are not. That is left as it is - a stable order would misrepresent how
 * the counts were gathered.
 * </p>
 */
public class ProblemSummaryReader
    implements IMcpTool
{
    @Override
    public String getName()
    {
        return "get_problem_summary"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `diagnostics` `operation=get_problem_summary`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Reports how many validation problems exist, tallied per project and by EDT severity " //$NON-NLS-1$
            + "tier (ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL)."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "Project to scope the count to; leave unset to cover the whole workspace") //$NON-NLS-1$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        return getProblemSummary(params.get("projectName")); //$NON-NLS-1$
    }

    /**
     * Counts the problems and renders both tables.
     * <p>
     * Public and static so callers outside the tool path can reuse it. Runs on the calling thread.
     * </p>
     *
     * @param projectName the project to count, or <code>null</code>/empty for the whole workspace
     * @return the markdown, or a {@code **Error:**} line
     */
    public static String getProblemSummary(String projectName)
    {
        try
        {
            Activator activator = Activator.getDefault();
            IMarkerManager markerManager = activator == null ? null : activator.getMarkerManager();
            if (markerManager == null)
            {
                return "**Error:** the IMarkerManager service is unavailable"; //$NON-NLS-1$
            }

            if (projectName != null && !projectName.isEmpty())
            {
                IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
                if (!project.exists())
                {
                    return "**Error:** " + ProjectResolver.describeNotFound(projectName); //$NON-NLS-1$
                }
            }

            Map<MarkerSeverity, Integer> grandTotals = new EnumMap<>(MarkerSeverity.class);
            for (MarkerSeverity severity : MarkerSeverity.values())
            {
                grandTotals.put(severity, 0);
            }
            Map<String, Map<MarkerSeverity, Integer>> byProject = new HashMap<>();

            String filterName = projectName;
            markerManager.markers().forEach(marker -> {
                IProject project = marker.getProject();
                if (project == null)
                {
                    return;
                }
                if (filterName != null && !filterName.isEmpty() && !project.getName().equals(filterName))
                {
                    return;
                }
                MarkerSeverity severity = marker.getSeverity();
                MarkerSeverity effective = severity != null ? severity : MarkerSeverity.NONE;
                grandTotals.merge(effective, 1, Integer::sum);
                byProject.computeIfAbsent(project.getName(), name -> seededCounts())
                    .merge(effective, 1, Integer::sum);
            });

            return render(grandTotals, byProject, projectName);
        }
        catch (Exception e)
        {
            return "**Error:** " + e.getMessage(); //$NON-NLS-1$
        }
    }

    /**
     * Renders the overall and per-project tables.
     * <p>
     * Package-visible so the wording can be driven by a test: when the count was scoped to one
     * project, the heading and the total row name that project - a page titled "Workspace Totals"
     * over one project's numbers reads as the whole workspace having that few problems.
     * </p>
     *
     * @param grandTotals the counts by severity across everything counted
     * @param byProject the counts by severity per project
     * @param projectName the project the count was scoped to, or <code>null</code>/empty for the
     *        whole workspace
     * @return the markdown
     */
    static String render(Map<MarkerSeverity, Integer> grandTotals,
        Map<String, Map<MarkerSeverity, Integer>> byProject, String projectName)
    {
        boolean scoped = projectName != null && !projectName.isEmpty();
        StringBuilder builder = new StringBuilder();
        builder.append("## Validation Problem Counts\n\n"); //$NON-NLS-1$

        builder.append("### ").append(scoped ? projectName + " Totals" : "Workspace Totals").append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        builder.append("| Severity | Occurrences |\n"); //$NON-NLS-1$
        builder.append("|----------|-------|\n"); //$NON-NLS-1$

        int grandTotal = 0;
        for (MarkerSeverity severity : MarkerSeverity.values())
        {
            int count = grandTotals.getOrDefault(severity, 0);
            grandTotal += count;
            if (severity == MarkerSeverity.NONE && count == 0)
            {
                continue;
            }
            builder.append("| ").append(severity.name()).append(" | ").append(count).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        builder.append("| **").append(scoped ? MarkdownTableHelper.escapeForTable(projectName) + " TOTAL" //$NON-NLS-1$ //$NON-NLS-2$
            : "GRAND TOTAL").append("** | **").append(grandTotal).append("** |\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        if (byProject.isEmpty())
        {
            builder.append("*Workspace is clean - no problems found.*\n"); //$NON-NLS-1$
            return builder.toString();
        }

        builder.append("### Per-Project Breakdown\n\n"); //$NON-NLS-1$
        builder.append("| Project | Errors | Blocker | Critical | Major | Minor | Trivial | Row Total |\n"); //$NON-NLS-1$
        builder.append("|---------|--------|---------|----------|-------|-------|---------|-------|\n"); //$NON-NLS-1$
        for (Map.Entry<String, Map<MarkerSeverity, Integer>> entry : byProject.entrySet())
        {
            Map<MarkerSeverity, Integer> counts = entry.getValue();
            int projectTotal = 0;
            for (int value : counts.values())
            {
                projectTotal += value;
            }
            builder.append("| ").append(MarkdownTableHelper.escapeForTable(entry.getKey())) //$NON-NLS-1$
                .append(" | ").append(counts.getOrDefault(MarkerSeverity.ERRORS, 0)) //$NON-NLS-1$
                .append(" | ").append(counts.getOrDefault(MarkerSeverity.BLOCKER, 0)) //$NON-NLS-1$
                .append(" | ").append(counts.getOrDefault(MarkerSeverity.CRITICAL, 0)) //$NON-NLS-1$
                .append(" | ").append(counts.getOrDefault(MarkerSeverity.MAJOR, 0)) //$NON-NLS-1$
                .append(" | ").append(counts.getOrDefault(MarkerSeverity.MINOR, 0)) //$NON-NLS-1$
                .append(" | ").append(counts.getOrDefault(MarkerSeverity.TRIVIAL, 0)) //$NON-NLS-1$
                .append(" | ").append(projectTotal) //$NON-NLS-1$
                .append(" |\n"); //$NON-NLS-1$
        }
        return builder.toString();
    }

    /**
     * Builds a counts map seeded with every severity at zero.
     *
     * @return the seeded map
     */
    private static Map<MarkerSeverity, Integer> seededCounts()
    {
        Map<MarkerSeverity, Integer> counts = new EnumMap<>(MarkerSeverity.class);
        for (MarkerSeverity severity : MarkerSeverity.values())
        {
            counts.put(severity, 0);
        }
        return counts;
    }
}
