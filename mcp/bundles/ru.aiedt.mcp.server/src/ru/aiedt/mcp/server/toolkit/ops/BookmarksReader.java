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
 * Lists the bookmark markers the workspace holds, one row per bookmark.
 * <p>
 * The same project and path filtering as the task listing, over the single bookmark marker type. A
 * closed project is passed over, an unknown named project reported.
 * </p>
 */
public class BookmarksReader
    implements IMcpTool
{
    private static final String BOOKMARK_MARKER_TYPE = "org.eclipse.core.resources.bookmark"; //$NON-NLS-1$

    private static final int LIMIT_MIN = 1;

    private static final int LIMIT_MAX = 1000;

    private static final int DEFAULT_LIMIT = 100;

    @Override
    public String getName()
    {
        return "get_bookmarks"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `workspace_marks` `operation=get_bookmarks`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Lists the workspace's bookmarks: the bookmark text, the file it sits in, and the line number."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Restrict results to this project (optional)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("filePath", "Keep only bookmarks whose path contains this substring (optional)") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("limit", "Cap on how many rows come back (default: 100, max: 1000)") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String filePath = JsonUtils.extractStringArgument(params, "filePath"); //$NON-NLS-1$

        int defaultLimit =
            ToolParamSettings.getInstance().getParameterValue("get_bookmarks", "limit", DEFAULT_LIMIT); //$NON-NLS-1$ //$NON-NLS-2$
        int limit = JsonUtils.extractIntArgument(params, "limit", defaultLimit); //$NON-NLS-1$
        limit = Math.max(LIMIT_MIN, Math.min(LIMIT_MAX, limit));

        return getBookmarks(projectName, filePath, limit);
    }

    /**
     * Collects and renders the bookmark markers.
     * <p>
     * Public and static so callers outside the tool path can reuse it. Runs on the calling thread.
     * </p>
     *
     * @param projectName the project to look in, or <code>null</code>/empty for the whole workspace
     * @param filePath a path fragment to keep, or <code>null</code>/empty for any
     * @param limit the most bookmarks to return, already clamped
     * @return the markdown table, or a {@code **Error:**} line
     */
    public static String getBookmarks(String projectName, String filePath, int limit)
    {
        try
        {
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

            List<BookmarkInfo> bookmarks = new ArrayList<>();
            for (IProject project : projects)
            {
                if (!project.isOpen())
                {
                    continue;
                }
                collect(project, bookmarks, limit, filePath);
                if (bookmarks.size() >= limit)
                {
                    break;
                }
            }

            return render(bookmarks, limit);
        }
        catch (Exception e)
        {
            return "**Error:** " + e.getMessage(); //$NON-NLS-1$
        }
    }

    /**
     * Collects a project's bookmarks, up to the limit.
     *
     * @param project the project
     * @param bookmarks the accumulator
     * @param limit the most to hold
     * @param filePath the path fragment to keep, or <code>null</code>/empty for any
     * @throws Exception if the markers cannot be read
     */
    private static void collect(IProject project, List<BookmarkInfo> bookmarks, int limit, String filePath)
        throws Exception
    {
        IMarker[] markers = project.findMarkers(BOOKMARK_MARKER_TYPE, true, IResource.DEPTH_INFINITE);
        for (IMarker marker : markers)
        {
            if (bookmarks.size() >= limit)
            {
                return;
            }
            String path = marker.getResource().getFullPath().toString();
            if (filePath != null && !filePath.isEmpty()
                && !path.toLowerCase(Locale.ROOT).contains(filePath.toLowerCase(Locale.ROOT)))
            {
                continue;
            }
            String message = marker.getAttribute(IMarker.MESSAGE, ""); //$NON-NLS-1$
            int line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
            bookmarks.add(new BookmarkInfo(project.getName(), message, path, line));
        }
    }

    /**
     * Renders the collected bookmarks.
     *
     * @param bookmarks the bookmarks
     * @param limit the limit they were gathered under
     * @return the markdown
     */
    private static String render(List<BookmarkInfo> bookmarks, int limit)
    {
        StringBuilder builder = new StringBuilder();
        builder.append("## Workspace Bookmarks\n\n"); //$NON-NLS-1$
        builder.append("**Found:** ").append(bookmarks.size()).append(" bookmarks"); //$NON-NLS-1$ //$NON-NLS-2$
        if (bookmarks.size() >= limit)
        {
            builder.append(" (limit: ").append(limit).append(", more available)"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        builder.append("\n\n"); //$NON-NLS-1$

        if (bookmarks.isEmpty())
        {
            builder.append("*No bookmarks.*\n"); //$NON-NLS-1$
            return builder.toString();
        }

        builder.append("| Project | Text | File | Line |\n"); //$NON-NLS-1$
        builder.append("|---------|---------|------|------|\n"); //$NON-NLS-1$
        for (BookmarkInfo bookmark : bookmarks)
        {
            builder.append("| ").append(MarkdownTableHelper.escapeForTable(bookmark.project)) //$NON-NLS-1$
                .append(" | ").append(MarkdownTableHelper.escapeForTable(bookmark.message)) //$NON-NLS-1$
                .append(" | ").append(MarkdownTableHelper.escapeForTable(bookmark.path)) //$NON-NLS-1$
                .append(" | ").append(bookmark.line) //$NON-NLS-1$
                .append(" |\n"); //$NON-NLS-1$
        }
        return builder.toString();
    }

    /** One bookmark marker, flattened to what the table shows. */
    private static final class BookmarkInfo
    {
        private final String project;

        private final String message;

        private final String path;

        private final int line;

        BookmarkInfo(String project, String message, String path, int line)
        {
            this.project = project;
            this.message = message;
            this.path = path;
            this.line = line;
        }
    }
}
