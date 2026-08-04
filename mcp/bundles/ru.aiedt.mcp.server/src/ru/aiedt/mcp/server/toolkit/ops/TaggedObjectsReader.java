/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

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
 * Lists metadata objects that carry any of a set of markers, one section per marker, with a summary and a
 * not-found section for marker names that match nothing.
 */
public final class TaggedObjectsReader implements IMcpTool
{
    public static final String NAME = "get_objects_by_tags"; //$NON-NLS-1$

    private static final String DESC =
        "Back-compat alias of `workspace_marks` `operation=get_objects_by_tags`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Look up metadata objects that carry particular markers. " //$NON-NLS-1$
            + "Reports every object matching at least one of the requested markers, " //$NON-NLS-1$
            + "together with each marker's description and the object FQNs."; //$NON-NLS-1$

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
            .stringProperty("projectName", "Name of the EDT project (mandatory)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty("tags", //$NON-NLS-1$
                "Marker names to filter on, e.g. ['Important', 'NeedsReview']. " //$NON-NLS-1$
                    + "Objects matching at least one listed marker are returned. Mandatory.") //$NON-NLS-1$
            .integerProperty("limit", "Upper bound on objects returned for each marker. Defaults to 100") //$NON-NLS-1$ //$NON-NLS-2$
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
        // The wire parameter is "tags" - the workspace_marks facade (operation=get_objects_by_tags)
        // and the standalone tool of that name both send "tags". "markers" is accepted as a synonym
        // for forward-compatibility with the canonical surface.
        String markersJson = JsonUtils.extractStringArgument(params, "tags"); //$NON-NLS-1$
        if (markersJson == null || markersJson.isEmpty())
        {
            markersJson = JsonUtils.extractStringArgument(params, "markers"); //$NON-NLS-1$
        }
        String limitStr = JsonUtils.extractStringArgument(params, "limit"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("A project name must be supplied").toJson(); //$NON-NLS-1$
        }

        String notReadyError = ProjectStateGuard.checkReadyOrError(projectName);
        if (notReadyError != null)
        {
            return ToolResult.error(notReadyError).toJson();
        }

        List<String> markerNames = parseMarkersList(markersJson);
        if (markerNames.isEmpty())
        {
            return ToolResult.error("A markers array must be supplied, for example: [\"Important\", \"NeedsReview\"]").toJson(); //$NON-NLS-1$
        }

        int limit = 100;
        if (limitStr != null && !limitStr.isEmpty())
        {
            try
            {
                limit = Math.min(Integer.parseInt(limitStr), 1000);
            }
            catch (NumberFormatException e)
            {
                // keep the default
            }
        }

        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        try
        {
            return getObjectsByMarkers(project, markerNames, limit);
        }
        catch (Exception e)
        {
            Activator.logError("Failed to look up objects by markers for project: " + projectName, e); //$NON-NLS-1$
            return ToolResult.error("Failed to look up objects by markers: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    private List<String> parseMarkersList(String markersJson)
    {
        List<String> result = new ArrayList<>();
        if (markersJson == null || markersJson.isEmpty())
        {
            return result;
        }
        try
        {
            JsonElement element = JsonParser.parseString(markersJson);
            if (element.isJsonArray())
            {
                JsonArray array = element.getAsJsonArray();
                for (JsonElement item : array)
                {
                    if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString())
                    {
                        result.add(item.getAsString());
                    }
                }
            }
        }
        catch (JsonParseException e)
        {
            Activator.logError("Failed to parse markers JSON: " + markersJson, e); //$NON-NLS-1$
        }
        return result;
    }

    private String getObjectsByMarkers(IProject project, List<String> markerNames, int limit)
    {
        MarkerManager markerService = MarkerManager.getInstance();
        MarkerStore storage = markerService.getMarkerStorage(project);
        StringBuilder sb = new StringBuilder();
        sb.append("# Metadata Objects Marked in Project: " + project.getName() + "\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        int totalObjects = 0;
        List<String> notFoundMarkers = new ArrayList<>();
        for (String markerName : markerNames)
        {
            Marker marker = storage.getMarkerByName(markerName);
            if (marker == null)
            {
                notFoundMarkers.add(markerName);
                continue;
            }
            Set<String> objects = storage.getObjectsByMarker(markerName);
            sb.append("## Marker - " + marker.getName() + "\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("- **Swatch:** " + marker.getColor() + "\n"); //$NON-NLS-1$ //$NON-NLS-2$
            String description = marker.getDescription();
            if (description != null && !description.isEmpty())
            {
                sb.append("- **What it means:** " + description + "\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            sb.append("- **Tagged objects:** " + objects.size() + "\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            if (objects.isEmpty())
            {
                sb.append("*No objects assigned to this marker*\n\n"); //$NON-NLS-1$
            }
            else
            {
                sb.append("| No. | Metadata Object FQN |\n"); //$NON-NLS-1$
                sb.append("|---|------------|\n"); //$NON-NLS-1$
                int count = 0;
                for (String fqn : objects)
                {
                    if (count >= limit)
                    {
                        sb.append("| ... | *" + (objects.size() - limit) + " more objects (limit reached)* |\n"); //$NON-NLS-1$ //$NON-NLS-2$
                        break;
                    }
                    sb.append("| " + (++count) + " | " + fqn + " |\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                sb.append("\n"); //$NON-NLS-1$
                totalObjects += Math.min(objects.size(), limit);
            }
        }
        if (!notFoundMarkers.isEmpty())
        {
            sb.append("## ⚠️ Unmatched marker names\n\n"); //$NON-NLS-1$
            for (String markerName : notFoundMarkers)
            {
                sb.append("- " + markerName + "\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            sb.append("\n"); //$NON-NLS-1$
        }
        sb.append("---\n"); //$NON-NLS-1$
        sb.append("**In total:** " + totalObjects + " objects spread over " //$NON-NLS-1$ //$NON-NLS-2$
            + (markerNames.size() - notFoundMarkers.size()) + " markers"); //$NON-NLS-1$
        return sb.toString();
    }
}
