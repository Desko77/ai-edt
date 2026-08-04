/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.support.BmRouteMapHelper;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * BusinessProcess route-map operations (create_route_map / get_route_map / remove_route_map),
 * extracted from {@link EditMetadataTool} as the first cluster of the god-class split (Inc4).
 * The handlers are thin: they parse parameters with the shared EditMetadataTool helpers and
 * delegate every BM mutation to {@link BmRouteMapHelper}.
 */
final class RouteMapOps
{
    /**
     * create_route_map - draws a BusinessProcess route map (Flowchart.scheme) from a JSON list of
     * points and transitions. Honors dryRun and overwrite. Pass the BusinessProcess FQN as ownerFqn
     * (or bpFqn).
     *
     * @param params the tool parameters
     * @return the JSON result document
     */
    String opCreateRouteMap(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String bpFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        if (bpFqn == null || bpFqn.isEmpty())
        {
            bpFqn = JsonUtils.extractStringArgument(params, "bpFqn"); //$NON-NLS-1$
        }
        boolean overwrite = JsonUtils.extractBooleanArgument(params, "overwrite", false); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(bpFqn, "ownerFqn"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        String pointsRaw = JsonUtils.extractStringArgument(params, "points"); //$NON-NLS-1$
        if (pointsRaw == null || pointsRaw.trim().isEmpty())
        {
            return ToolResult.error("create_route_map requires a 'points' JSON array, e.g. " //$NON-NLS-1$
                + "[{\"type\":\"Start\",\"name\":\"Старт\"}," //$NON-NLS-1$
                + "{\"type\":\"Action\",\"name\":\"Выполнить\"}," //$NON-NLS-1$
                + "{\"type\":\"Completion\",\"name\":\"Завершение\"}]").toJson(); //$NON-NLS-1$
        }
        List<Map<String, String>> points = EditMetadataTool.parseStructArray(pointsRaw);
        if (points.isEmpty())
        {
            return ToolResult.error(
                "'points' must be a non-empty JSON array of {type,name} objects").toJson(); //$NON-NLS-1$
        }
        int rawPointCount = EditMetadataTool.jsonArrayLength(pointsRaw);
        if (rawPointCount > points.size())
        {
            return ToolResult.error("Some 'points' entries could not be parsed (" //$NON-NLS-1$
                + points.size() + " of " + rawPointCount //$NON-NLS-1$
                + " were valid JSON objects); each point must look like {\"type\":\"Start\",\"name\":\"X\"}") //$NON-NLS-1$
                .toJson();
        }
        String transitionsRaw = JsonUtils.extractStringArgument(params, "transitions"); //$NON-NLS-1$
        List<Map<String, String>> transitions =
            transitionsRaw != null && !transitionsRaw.trim().isEmpty()
                ? EditMetadataTool.parseStructArray(transitionsRaw) : new ArrayList<>();
        int rawTransitionCount = EditMetadataTool.jsonArrayLength(transitionsRaw);
        if (rawTransitionCount > transitions.size())
        {
            return ToolResult.error("Some 'transitions' entries could not be parsed (" //$NON-NLS-1$
                + transitions.size() + " of " + rawTransitionCount //$NON-NLS-1$
                + " were valid JSON objects); each transition must look like {\"from\":\"A\",\"to\":\"B\"}") //$NON-NLS-1$
                .toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        BmRouteMapHelper.WriteResult wr =
            BmRouteMapHelper.writeRouteMap(project, bpFqn, points, transitions, overwrite, dryRun);
        if (wr.error != null)
        {
            return ToolResult.error(wr.error).toJson();
        }
        if (dryRun)
        {
            return ToolResult.success()
                .put("operation", "create_route_map") //$NON-NLS-1$ //$NON-NLS-2$
                .put("ownerFqn", bpFqn) //$NON-NLS-1$
                .put("dryRun", true) //$NON-NLS-1$
                .put("pointCount", wr.pointCount) //$NON-NLS-1$
                .put("transitionCount", wr.transitionCount) //$NON-NLS-1$
                .put("previewXml", wr.xml) //$NON-NLS-1$
                .put("message", "Preview: generated Flowchart.scheme (no changes applied). " //$NON-NLS-1$
                    + "Run without dryRun to write it, then update_database to verify.") //$NON-NLS-1$
                .toJson();
        }
        return ToolResult.success()
            .put("operation", "create_route_map") //$NON-NLS-1$ //$NON-NLS-2$
            .put("ownerFqn", bpFqn) //$NON-NLS-1$
            .put("written", wr.written) //$NON-NLS-1$
            .put("pointCount", wr.pointCount) //$NON-NLS-1$
            .put("transitionCount", wr.transitionCount) //$NON-NLS-1$
            .put("message", "Route map (Flowchart.scheme) written with " + wr.pointCount //$NON-NLS-1$
                + " point(s) and " + wr.transitionCount //$NON-NLS-1$
                + " transition(s). Run get_project_errors then update_database to verify.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * get_route_map - reads a BusinessProcess route map (Flowchart.scheme) into a JSON tree of
     * points (type / name / event handlers) and transitions (from -> to). Read-only. Pass the
     * BusinessProcess FQN as ownerFqn (or bpFqn).
     *
     * @param params the tool parameters
     * @return the JSON result document
     */
    String opGetRouteMap(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String bpFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        if (bpFqn == null || bpFqn.isEmpty())
        {
            bpFqn = JsonUtils.extractStringArgument(params, "bpFqn"); //$NON-NLS-1$
        }
        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(bpFqn, "ownerFqn"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        BmRouteMapHelper.RouteMap rm = BmRouteMapHelper.readRouteMap(project, bpFqn);
        if (rm.error != null)
        {
            return ToolResult.error(rm.error).toJson();
        }
        if (!rm.exists)
        {
            return ToolResult.success()
                .put("operation", "get_route_map") //$NON-NLS-1$ //$NON-NLS-2$
                .put("ownerFqn", bpFqn) //$NON-NLS-1$
                .put("routeMapExists", false) //$NON-NLS-1$
                .put("message", "No Flowchart.scheme found for " + bpFqn //$NON-NLS-1$
                    + " (the route map is empty / not yet drawn).") //$NON-NLS-1$
                .toJson();
        }
        return ToolResult.success()
            .put("operation", "get_route_map") //$NON-NLS-1$ //$NON-NLS-2$
            .put("ownerFqn", bpFqn) //$NON-NLS-1$
            .put("routeMapExists", true) //$NON-NLS-1$
            .put("pointCount", rm.points.size()) //$NON-NLS-1$
            .put("transitionCount", rm.transitions.size()) //$NON-NLS-1$
            .put("points", rm.points) //$NON-NLS-1$
            .put("transitions", rm.transitions) //$NON-NLS-1$
            .toJson();
    }

    /**
     * remove_route_map - deletes a BusinessProcess route map (Flowchart.scheme), clearing the drawn
     * graph. Honors dryRun. Pass the BusinessProcess FQN as ownerFqn (or bpFqn).
     *
     * @param params the tool parameters
     * @return the JSON result document
     */
    String opRemoveRouteMap(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String bpFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        if (bpFqn == null || bpFqn.isEmpty())
        {
            bpFqn = JsonUtils.extractStringArgument(params, "bpFqn"); //$NON-NLS-1$
        }
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(bpFqn, "ownerFqn"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        BmRouteMapHelper.RouteMap rm = BmRouteMapHelper.readRouteMap(project, bpFqn);
        if (rm.error != null)
        {
            return ToolResult.error(rm.error).toJson();
        }
        if (dryRun)
        {
            return ToolResult.success()
                .put("operation", "remove_route_map") //$NON-NLS-1$ //$NON-NLS-2$
                .put("ownerFqn", bpFqn) //$NON-NLS-1$
                .put("dryRun", true) //$NON-NLS-1$
                .put("message", rm.exists //$NON-NLS-1$
                    ? "Preview: would delete Flowchart.scheme (no changes applied)." //$NON-NLS-1$
                    : "Preview: no Flowchart.scheme to delete.") //$NON-NLS-1$
                .toJson();
        }
        String removeErr = BmRouteMapHelper.removeRouteMap(project, bpFqn);
        if (removeErr != null)
        {
            return ToolResult.error(removeErr).toJson();
        }
        return ToolResult.success()
            .put("operation", "remove_route_map") //$NON-NLS-1$ //$NON-NLS-2$
            .put("ownerFqn", bpFqn) //$NON-NLS-1$
            .put("removed", rm.exists) //$NON-NLS-1$
            .put("message", rm.exists ? "Route map (Flowchart.scheme) removed." //$NON-NLS-1$
                : "No Flowchart.scheme to remove.") //$NON-NLS-1$
            .toJson();
    }
}
