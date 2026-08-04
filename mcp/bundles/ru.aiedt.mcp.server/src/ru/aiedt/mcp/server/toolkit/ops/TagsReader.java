/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.labels.MarkerManager;
import ru.aiedt.mcp.server.labels.model.Marker;
import ru.aiedt.mcp.server.labels.model.MarkerStore;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.ProjectStateGuard;

/**
 * Lists every marker defined in a project: name, color, description and how many objects carry it.
 */
public final class TagsReader implements IMcpTool
{
    public static final String NAME = "get_tags"; //$NON-NLS-1$

    private static final String DESC =
        "Back-compat alias of `workspace_marks` `operation=get_tags`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "List every marker defined in the project. " //$NON-NLS-1$
            + "Markers are custom labels used to organize metadata objects. " //$NON-NLS-1$
            + "Returns each marker's name, color, description, and how many objects carry it."; //$NON-NLS-1$

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
            .stringProperty("projectName", "Name of the EDT project (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName parameter is required").toJson(); //$NON-NLS-1$
        }

        String notReadyError = ProjectStateGuard.checkReadyOrError(projectName);
        if (notReadyError != null)
        {
            return ToolResult.error(notReadyError).toJson();
        }

        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        try
        {
            return getMarkers(project);
        }
        catch (Exception e)
        {
            Activator.logError("Failed to get markers for project: " + projectName, e); //$NON-NLS-1$
            return ToolResult.error("Could not get markers: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    private String getMarkers(IProject project)
    {
        MarkerManager markerService = MarkerManager.getInstance();
        MarkerStore storage = markerService.getMarkerStorage(project);
        List<Marker> markers = storage.getTags();
        if (markers.isEmpty())
        {
            return "This project has no markers defined: " + project.getName(); //$NON-NLS-1$
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Project Markers: " + project.getName() + "\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("| # | Name | Color | Description | Used By |\n"); //$NON-NLS-1$
        sb.append("|---|------|-------|-------------|--------|\n"); //$NON-NLS-1$
        int index = 1;
        for (Marker marker : markers)
        {
            int objectCount = storage.getObjectsByMarker(marker.getName()).size();
            String description = marker.getDescription();
            if (description == null || description.isEmpty())
            {
                description = "-"; //$NON-NLS-1$
            }
            else
            {
                description = description.replace("|", "\\|"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            sb.append("| " + (index++) + " | " + marker.getName() + " | " + marker.getColor() + " | " + description //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                + " | " + objectCount + " |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append("\n**Marker count:** " + markers.size()); //$NON-NLS-1$
        return sb.toString();
    }
}
