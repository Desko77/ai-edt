/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;

import ru.aiedt.mcp.server.settings.ToolParamSettings;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.MarkdownTableHelper;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * Lists the task markers - the TODO, FIXME and kin - the workspace holds.
 * <p>
 * Two marker channels are read, the core one and Xtext's, in that order and only up to the limit. Each
 * task is tagged by the keyword its message opens with, and filtered by project, path fragment and
 * priority as asked.
 * </p>
 */
public class TasksReader
    implements IMcpTool
{
    private static final String TASK_MARKER_TYPE = "org.eclipse.core.resources.taskmarker"; //$NON-NLS-1$

    private static final String XTEXT_TASK_MARKER_TYPE = "org.eclipse.xtext.ui.task"; //$NON-NLS-1$

    private static final int LIMIT_MIN = 1;

    private static final int LIMIT_MAX = 1000;

    private static final int DEFAULT_LIMIT = 100;

    @Override
    public String getName()
    {
        return "get_tasks"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `workspace_marks` `operation=get_tasks`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "List workspace tasks such as TODO and FIXME markers. Returns each task's message, file " //$NON-NLS-1$
            + "path, line number, and priority."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Restrict results to this project (optional)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("filePath", "Keep only paths containing this substring (optional)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("priority", "Keep only this priority: high, normal, low (optional)") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("limit", "Cap on the number of results (default: 100, max: 1000)") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String filePath = JsonUtils.extractStringArgument(params, "filePath"); //$NON-NLS-1$
        String priority = JsonUtils.extractStringArgument(params, "priority"); //$NON-NLS-1$

        int defaultLimit =
            ToolParamSettings.getInstance().getParameterValue("get_tasks", "limit", DEFAULT_LIMIT); //$NON-NLS-1$ //$NON-NLS-2$
        int limit = JsonUtils.extractIntArgument(params, "limit", defaultLimit); //$NON-NLS-1$
        limit = Math.max(LIMIT_MIN, Math.min(LIMIT_MAX, limit));

        return getTasks(projectName, filePath, priority, limit);
    }

    /**
     * Collects and renders the task markers.
     * <p>
     * Public and static so callers outside the tool path can reuse it. Runs on the calling thread.
     * </p>
     *
     * @param projectName the project to look in, or <code>null</code>/empty for the whole workspace
     * @param filePath a path fragment to keep, or <code>null</code>/empty for any
     * @param priority a priority to keep - high, normal or low - or anything else for any
     * @param limit the most tasks to return, already clamped
     * @return the markdown table, or a {@code **Error:**} line
     */
    public static String getTasks(String projectName, String filePath, String priority, int limit)
    {
        try
        {
            Integer priorityFilter = parsePriority(priority);

            IProject[] projects;
            if (projectName != null && !projectName.isEmpty())
            {
                IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
                if (!project.exists())
                {
                    return "**Error:** " + ProjectResolver.describeNotFound(projectName); //$NON-NLS-1$
                }
                projects = new IProject[] {project};
            }
            else
            {
                projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            }

            List<TaskInfo> tasks = new ArrayList<>();
            for (IProject project : projects)
            {
                if (!project.isOpen())
                {
                    continue;
                }
                collect(project, TASK_MARKER_TYPE, tasks, limit, priorityFilter, filePath);
                if (tasks.size() >= limit)
                {
                    break;
                }
                collect(project, XTEXT_TASK_MARKER_TYPE, tasks, limit, priorityFilter, filePath);
                if (tasks.size() >= limit)
                {
                    break;
                }
            }

            return render(tasks, limit);
        }
        catch (Exception e)
        {
            return "**Error:** " + e.getMessage(); //$NON-NLS-1$
        }
    }

    /**
     * Collects one marker type from a project, up to the shared limit.
     *
     * @param project the project
     * @param markerType the marker type id
     * @param tasks the accumulator
     * @param limit the most to hold
     * @param priorityFilter the priority to keep, or <code>null</code> for any
     * @param filePath the path fragment to keep, or <code>null</code>/empty for any
     * @throws Exception if the markers cannot be read
     */
    private static void collect(IProject project, String markerType, List<TaskInfo> tasks, int limit,
        Integer priorityFilter, String filePath) throws Exception
    {
        if (tasks.size() >= limit)
        {
            return;
        }
        IMarker[] markers = project.findMarkers(markerType, true, IResource.DEPTH_INFINITE);
        for (IMarker marker : markers)
        {
            if (tasks.size() >= limit)
            {
                return;
            }
            int markerPriority = marker.getAttribute(IMarker.PRIORITY, IMarker.PRIORITY_NORMAL);
            if (priorityFilter != null && markerPriority != priorityFilter.intValue())
            {
                continue;
            }
            String path = marker.getResource().getFullPath().toString();
            if (filePath != null && !filePath.isEmpty()
                && !path.toLowerCase(Locale.ROOT).contains(filePath.toLowerCase(Locale.ROOT)))
            {
                continue;
            }
            String message = marker.getAttribute(IMarker.MESSAGE, ""); //$NON-NLS-1$
            int line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
            tasks.add(new TaskInfo(taskType(message), priorityName(markerPriority), message, path, line));
        }
    }

    /**
     * Renders the collected tasks.
     *
     * @param tasks the tasks
     * @param limit the limit they were gathered under
     * @return the markdown
     */
    private static String render(List<TaskInfo> tasks, int limit)
    {
        StringBuilder builder = new StringBuilder();
        builder.append("## Workspace Tasks\n\n"); //$NON-NLS-1$
        builder.append("**Found:** ").append(tasks.size()).append(" tasks"); //$NON-NLS-1$ //$NON-NLS-2$
        if (tasks.size() >= limit)
        {
            builder.append(" (limit: ").append(limit).append(", more available)"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        builder.append("\n\n"); //$NON-NLS-1$

        if (tasks.isEmpty())
        {
            builder.append("*Nothing found.*\n"); //$NON-NLS-1$
            return builder.toString();
        }

        builder.append("| Kind | Urgency | Message | Path | Line |\n"); //$NON-NLS-1$
        builder.append("|------|----------|---------|------|------|\n"); //$NON-NLS-1$
        for (TaskInfo task : tasks)
        {
            builder.append("| ").append(MarkdownTableHelper.escapeForTable(task.type)) //$NON-NLS-1$
                .append(" | ").append(MarkdownTableHelper.escapeForTable(task.priority)) //$NON-NLS-1$
                .append(" | ").append(MarkdownTableHelper.escapeForTable(task.message)) //$NON-NLS-1$
                .append(" | ").append(MarkdownTableHelper.escapeForTable(task.path)) //$NON-NLS-1$
                .append(" | ").append(task.line) //$NON-NLS-1$
                .append(" |\n"); //$NON-NLS-1$
        }
        return builder.toString();
    }

    /**
     * Maps a priority filter word to its marker priority.
     *
     * @param priority the filter argument; may be <code>null</code>
     * @return the marker priority, or <code>null</code> for anything that is not high, normal or low
     */
    private static Integer parsePriority(String priority)
    {
        if (priority == null)
        {
            return null;
        }
        switch (priority.toLowerCase(Locale.ROOT))
        {
        case "high": //$NON-NLS-1$
            return Integer.valueOf(IMarker.PRIORITY_HIGH);
        case "normal": //$NON-NLS-1$
            return Integer.valueOf(IMarker.PRIORITY_NORMAL);
        case "low": //$NON-NLS-1$
            return Integer.valueOf(IMarker.PRIORITY_LOW);
        default:
            return null;
        }
    }

    /**
     * Names a marker priority.
     *
     * @param priority the marker priority
     * @return {@code high}, {@code low}, or {@code normal} for anything else
     */
    private static String priorityName(int priority)
    {
        if (priority == IMarker.PRIORITY_HIGH)
        {
            return "high"; //$NON-NLS-1$
        }
        if (priority == IMarker.PRIORITY_LOW)
        {
            return "low"; //$NON-NLS-1$
        }
        return "normal"; //$NON-NLS-1$
    }

    /**
     * Tags a task by the keyword its message opens with.
     *
     * @param message the marker message
     * @return TODO, FIXME, XXX, HACK, or TASK when none is present
     */
    private static String taskType(String message)
    {
        String upper = message.toUpperCase(Locale.ROOT);
        if (upper.contains("TODO")) //$NON-NLS-1$
        {
            return "TODO"; //$NON-NLS-1$
        }
        if (upper.contains("FIXME")) //$NON-NLS-1$
        {
            return "FIXME"; //$NON-NLS-1$
        }
        if (upper.contains("XXX")) //$NON-NLS-1$
        {
            return "XXX"; //$NON-NLS-1$
        }
        if (upper.contains("HACK")) //$NON-NLS-1$
        {
            return "HACK"; //$NON-NLS-1$
        }
        return "TASK"; //$NON-NLS-1$
    }

    /** One task marker, flattened to what the table shows. */
    private static final class TaskInfo
    {
        private final String type;

        private final String priority;

        private final String message;

        private final String path;

        private final int line;

        TaskInfo(String type, String priority, String message, String path, int line)
        {
            this.type = type;
            this.priority = priority;
            this.message = message;
            this.path = path;
            this.line = line;
        }
    }
}
