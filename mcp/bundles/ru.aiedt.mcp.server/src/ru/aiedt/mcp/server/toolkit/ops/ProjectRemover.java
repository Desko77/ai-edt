/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;

/**
 * Deletes a project from the EDT workspace via the standard Eclipse
 * {@link IProject#delete(boolean, boolean, org.eclipse.core.runtime.IProgressMonitor)}
 * (EDT's resource listeners release the DT/BM state, the same path the Navigator's
 * Delete uses). Primarily for cleaning up throwaway / test projects an agent created
 * (e.g. via {@code extension_workshop create_extension_project}).
 *
 * <p>DESTRUCTIVE. {@code deleteContent=false} (default) only unregisters the project
 * from the workspace and KEEPS its files on disk - reversible by re-importing.
 * {@code deleteContent=true} also deletes the files from disk (irreversible).
 */
public class ProjectRemover implements IMcpTool
{
    public static final String NAME = "delete_project"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `project_admin` `operation=delete_project`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Delete a project from the EDT workspace (DESTRUCTIVE). " //$NON-NLS-1$
            + "deleteContent=false (default) unregisters it but KEEPS files on disk " //$NON-NLS-1$
            + "(reversible by re-import); deleteContent=true also deletes the files (irreversible). " //$NON-NLS-1$
            + "Use to clean up throwaway/test projects (e.g. created by create_extension_project)."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the project to delete (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("deleteContent", //$NON-NLS-1$
                "Also delete the project's files from disk (default false = keep files, " //$NON-NLS-1$
                    + "remove only from the workspace).") //$NON-NLS-1$
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
            return ToolResult.error(TextSuggest.missingParam("projectName", //$NON-NLS-1$
                "delete_project projectName=MyThrowawayExt [deleteContent=false]")).toJson(); //$NON-NLS-1$
        }
        boolean deleteContent = JsonUtils.extractBooleanArgument(params, "deleteContent", false); //$NON-NLS-1$

        // Resolve (handles suffix/fuzzy names); fall back to an exact workspace handle
        // so a closed project (which ProjectResolver may skip) can still be deleted.
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            IProject exact = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (exact.exists())
            {
                project = exact;
            }
        }
        if (project == null || !project.exists())
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        String resolvedName = project.getName();
        String path = project.getLocation() != null ? project.getLocation().toOSString() : null;
        try
        {
            project.delete(deleteContent, true, new NullProgressMonitor());
            Activator.logInfo("delete_project: " + resolvedName //$NON-NLS-1$
                + " (deleteContent=" + deleteContent + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            ToolResult res = ToolResult.success()
                .put("projectName", resolvedName) //$NON-NLS-1$
                .put("deleted", true) //$NON-NLS-1$
                .put("deleteContent", deleteContent) //$NON-NLS-1$
                .put("message", deleteContent //$NON-NLS-1$
                    ? "Project removed from the workspace and its files deleted from disk." //$NON-NLS-1$
                    : "Project removed from the workspace; files kept on disk (re-import to restore)."); //$NON-NLS-1$
            if (path != null)
            {
                res.put("path", path); //$NON-NLS-1$
            }
            return res.toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Failed to delete project " + resolvedName, e); //$NON-NLS-1$
            return ToolResult.error("Error: could not remove project '" + resolvedName + "': " //$NON-NLS-1$ //$NON-NLS-2$
                + TextSuggest.safeMessage(e)).toJson();
        }
    }
}
