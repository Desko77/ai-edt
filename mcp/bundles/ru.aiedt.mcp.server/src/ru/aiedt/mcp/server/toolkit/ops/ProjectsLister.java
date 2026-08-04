/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.resources.ResourcesPlugin;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.MarkdownTableHelper;
import ru.aiedt.mcp.server.support.ProjectStateGuard;

/**
 * Lists every project in the workspace as a markdown table, EDT's and not.
 * <p>
 * One row per project, in the workspace's own order, with no limit and no filter: this is the map a
 * user reads to find the name to pass to every other tool. A closed project shows what can be known
 * without opening it - its name, its state and its location - and leaves the EDT-only columns blank.
 * </p>
 */
public class ProjectsLister
    implements IMcpTool
{
    private static final String V8_CONFIGURATION_NATURE = "com._1c.g5.v8.dt.core.V8ConfigurationNature"; //$NON-NLS-1$

    private static final String V8_EXTENSION_NATURE = "com._1c.g5.v8.dt.core.V8ExtensionNature"; //$NON-NLS-1$

    private static final String V8_EXTERNAL_OBJECTS_NATURE = "com._1c.g5.v8.dt.core.V8ExternalObjectsNature"; //$NON-NLS-1$

    private static final String NONE = "-"; //$NON-NLS-1$

    private static final int MAX_NATURES_SHOWN = 3;

    @Override
    public String getName()
    {
        return "list_projects"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `project_admin` `operation=list_projects`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Enumerates every project in the workspace, with its name, path, type and natures"; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object().build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        return listProjects();
    }

    /**
     * Renders every workspace project.
     * <p>
     * Public and static so callers outside the tool path can reuse it. Runs on the calling thread.
     * </p>
     *
     * @return the markdown table, or an error line when the workspace could not be walked
     */
    public static String listProjects()
    {
        try
        {
            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();

            StringBuilder builder = new StringBuilder();
            builder.append("## Projects in This Workspace\n\n"); //$NON-NLS-1$
            builder.append("**Project count:** ").append(projects.length).append(" projects in the workspace\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

            if (projects.length == 0)
            {
                builder.append("*The workspace has no projects.*\n"); //$NON-NLS-1$
                return builder.toString();
            }

            builder.append("| Name | State | Path | Open | Is EDT Project | Natures |\n"); //$NON-NLS-1$
            builder.append("|------|-------|------|------|-------------|--------|\n"); //$NON-NLS-1$

            for (IProject project : projects)
            {
                appendRow(builder, project);
            }
            return builder.toString();
        }
        catch (Exception e)
        {
            Activator.logError("Failed to enumerate workspace projects", e); //$NON-NLS-1$
            return "**Error:** " + e.getMessage(); //$NON-NLS-1$
        }
    }

    /**
     * Appends one project's row.
     *
     * @param builder the table under construction
     * @param project the project to describe
     */
    private static void appendRow(StringBuilder builder, IProject project)
    {
        String stateValue = ProjectStateGuard.checkProjectState(project).getStateValue();

        IPath location = project.getLocation();
        String path = location != null ? location.toOSString() : ""; //$NON-NLS-1$

        boolean open = project.isOpen();
        String edtStatus = NONE;
        String naturesStr = NONE;

        if (open)
        {
            try
            {
                String[] natureIds = project.getDescription().getNatureIds();
                edtStatus = hasV8Nature(natureIds) ? "Yes" : "No"; //$NON-NLS-1$ //$NON-NLS-2$
                naturesStr = abbreviateNatures(natureIds);
            }
            catch (Exception e)
            {
                // Leave the EDT columns at "-": the project is open but would not describe itself.
                Activator.logError("Failed to read natures for project " + project.getName(), e); //$NON-NLS-1$
            }
        }

        builder.append("| ").append(MarkdownTableHelper.escapeForTable(project.getName())) //$NON-NLS-1$
            .append(" | ").append(stateValue) //$NON-NLS-1$
            .append(" | ").append(MarkdownTableHelper.escapeForTable(path)) //$NON-NLS-1$
            .append(" | ").append(open ? "Yes" : "No") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .append(" | ").append(edtStatus) //$NON-NLS-1$
            .append(" | ").append(MarkdownTableHelper.escapeForTable(naturesStr)) //$NON-NLS-1$
            .append(" |\n"); //$NON-NLS-1$
    }

    /**
     * Tells whether a project carries one of the three 1C natures.
     *
     * @param natureIds the project's nature ids
     * @return <code>true</code> when a configuration, extension or external-object nature is present
     */
    private static boolean hasV8Nature(String[] natureIds)
    {
        for (String nature : natureIds)
        {
            if (V8_CONFIGURATION_NATURE.equals(nature) || V8_EXTENSION_NATURE.equals(nature)
                || V8_EXTERNAL_OBJECTS_NATURE.equals(nature))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Abbreviates a project's natures to their last dot-segments, at most three of them.
     *
     * @param natureIds the nature ids
     * @return the abbreviated list, with a {@code ...+N} tail when more than three, or {@code -} when
     *         the project has no natures
     */
    private static String abbreviateNatures(String[] natureIds)
    {
        if (natureIds == null || natureIds.length == 0)
        {
            return NONE;
        }
        StringBuilder builder = new StringBuilder();
        int shown = Math.min(MAX_NATURES_SHOWN, natureIds.length);
        for (int i = 0; i < shown; i++)
        {
            if (i > 0)
            {
                builder.append(", "); //$NON-NLS-1$
            }
            builder.append(lastSegment(natureIds[i]));
        }
        if (natureIds.length > MAX_NATURES_SHOWN)
        {
            builder.append("...+").append(natureIds.length - MAX_NATURES_SHOWN); //$NON-NLS-1$
        }
        return builder.toString();
    }

    /**
     * Returns the part of a dotted id after its last dot.
     *
     * @param id the id
     * @return the last segment, or the id itself when it has no dot
     */
    private static String lastSegment(String id)
    {
        int dot = id.lastIndexOf('.');
        return dot >= 0 ? id.substring(dot + 1) : id;
    }
}
