/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.support.BmRouteMapHelper;
import ru.aiedt.mcp.server.support.BslScriptLanguage;
import ru.aiedt.mcp.server.support.HandlerStubPlacement;
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
     * (or bpFqn). Every handler the scheme names and the object module does not declare gets a
     * procedure in the object module; the scheme validator reports a handler without one.
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
            return ProjectResolver.notFound(projectName).toJson();
        }
        BmRouteMapHelper.WriteResult wr =
            BmRouteMapHelper.writeRouteMap(project, bpFqn, points, transitions, overwrite, dryRun);
        if (wr.error != null)
        {
            return ToolResult.error(wr.error).toJson();
        }
        String modulePath = BmRouteMapHelper.objectModulePath(bpFqn);
        if (modulePath == null)
        {
            modulePath = bpFqn + ".ObjectModule"; //$NON-NLS-1$
        }
        String moduleText = null;
        String unreadable = null;
        try
        {
            moduleText = BslModuleAccess.readModuleIfPresent(project, modulePath);
        }
        catch (Exception cannotRead)
        {
            unreadable = "the module " + modulePath + " exists and could not be read (" //$NON-NLS-1$ //$NON-NLS-2$
                + cannotRead.getMessage() + "), so no procedure was appended: the handlers it already " //$NON-NLS-1$
                + "declares cannot be told apart and would be written twice"; //$NON-NLS-1$
            Activator.logWarning("create_route_map: " + unreadable); //$NON-NLS-1$
        }
        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        Configuration config =
            configProvider != null ? configProvider.getConfiguration(project) : null;
        BslScriptLanguage language = BslScriptLanguage.of(config);
        HandlerStubs stubs = planHandlerStubs(wr.handlers, moduleText, language);
        if (dryRun)
        {
            ToolResult preview = ToolResult.success()
                .put("operation", "create_route_map") //$NON-NLS-1$ //$NON-NLS-2$
                .put("ownerFqn", bpFqn) //$NON-NLS-1$
                .put("dryRun", true) //$NON-NLS-1$
                .put("replaced", wr.replaced) //$NON-NLS-1$
                .put("pointCount", wr.pointCount) //$NON-NLS-1$
                .put("transitionCount", wr.transitionCount) //$NON-NLS-1$
                .put("points", wr.points) //$NON-NLS-1$
                .put("handlers", wr.handlers) //$NON-NLS-1$
                .put("stubsToWrite", stubs.names) //$NON-NLS-1$
                .put("stubsAlreadyPresent", stubs.alreadyPresent) //$NON-NLS-1$
                .put("previewXml", wr.xml) //$NON-NLS-1$
                .put("message", "Preview: generated Flowchart.scheme (no changes applied). " //$NON-NLS-1$
                    + "Run without dryRun to write it, then update_database to verify."); //$NON-NLS-1$
            if (stubs.error != null)
            {
                preview.put("stubWriteFailed", stubs.error); //$NON-NLS-1$
            }
            else if (unreadable != null)
            {
                preview.put("stubWriteFailed", unreadable); //$NON-NLS-1$
            }
            return preview.toJson();
        }
        String stubFailure = stubs.error != null ? stubs.error
            : stubs.names.isEmpty() ? null
            : unreadable != null ? unreadable
            : appendToModule(project, modulePath, moduleText, stubs.text.toString(), language);
        ToolResult result = ToolResult.success()
            .put("operation", "create_route_map") //$NON-NLS-1$ //$NON-NLS-2$
            .put("ownerFqn", bpFqn) //$NON-NLS-1$
            .put("written", wr.written) //$NON-NLS-1$
            .put("replaced", wr.replaced) //$NON-NLS-1$
            .put("pointCount", wr.pointCount) //$NON-NLS-1$
            .put("transitionCount", wr.transitionCount) //$NON-NLS-1$
            .put("points", wr.points) //$NON-NLS-1$
            .put("handlers", wr.handlers) //$NON-NLS-1$
            .put("stubsWritten", stubFailure == null ? stubs.names : new ArrayList<String>()) //$NON-NLS-1$
            .put("stubsAlreadyPresent", stubs.alreadyPresent) //$NON-NLS-1$
            .put("message", "Route map (Flowchart.scheme) written with " + wr.pointCount //$NON-NLS-1$
                + " point(s) and " + wr.transitionCount //$NON-NLS-1$
                + " transition(s)" //$NON-NLS-1$
                + (wr.replaced ? ", replacing the route map that was there" : "") //$NON-NLS-1$ //$NON-NLS-2$
                + (stubFailure != null
                    ? ". The handler procedures were NOT written to " + modulePath //$NON-NLS-1$
                    : "") //$NON-NLS-1$
                + ". Run get_project_errors then update_database to verify."); //$NON-NLS-1$
        if (stubFailure != null)
        {
            result.put("stubWriteFailed", stubFailure); //$NON-NLS-1$
        }
        return result.toJson();
    }

    /** The handler procedures a route map needs in the object module, and those already there. */
    static final class HandlerStubs
    {
        /** Handler names whose procedure is to be written, first spelling of each name. */
        final List<String> names = new ArrayList<>();
        /** Handler names the module already declares. */
        final List<String> alreadyPresent = new ArrayList<>();
        /** The procedures to append, one blank line apart. */
        final StringBuilder text = new StringBuilder();
        /** Why no procedure can be written, or null when the plan is sound. */
        String error;
    }

    /**
     * Decides which handler procedures a written route map still needs, in Russian.
     *
     * @param handlers the handlers written into the scheme, as {point, event, handler}
     * @param moduleText the object module as it stands, or null when there is none
     * @return the procedures to write and the names already declared
     */
    static HandlerStubs planHandlerStubs(List<Map<String, String>> handlers, String moduleText)
    {
        return planHandlerStubs(handlers, moduleText, BslScriptLanguage.RUSSIAN);
    }

    /**
     * Decides which handler procedures a written route map still needs.
     * <p>
     * A handler the module already declares is left alone, and a name given to several events is
     * written once - but only when those events hand their handler the same number of parameters.
     * A name shared by events of different arity is a name the platform cannot bind to one
     * procedure, so the plan refuses, naming both events and their signatures. Names are compared
     * without regard to case, as the platform binds them.
     * </p>
     *
     * @param handlers the handlers written into the scheme, as {point, event, handler}
     * @param moduleText the object module as it stands, or null when there is none
     * @param language the language the procedures' keywords are written in
     * @return the procedures to write and the names already declared
     */
    static HandlerStubs planHandlerStubs(List<Map<String, String>> handlers, String moduleText,
        BslScriptLanguage language)
    {
        HandlerStubs plan = new HandlerStubs();
        Map<String, String> firstSpellingByName = new LinkedHashMap<>();
        Map<String, String> firstEventByName = new LinkedHashMap<>();
        Map<String, Integer> arityByName = new HashMap<>();
        for (Map<String, String> entry : handlers)
        {
            String handler = entry.get("handler"); //$NON-NLS-1$
            String parameters = BmRouteMapHelper.handlerParameters(entry.get("event")); //$NON-NLS-1$
            if (handler == null || parameters == null)
            {
                continue;
            }
            String key = handler.toLowerCase(Locale.ROOT);
            Integer arity = Integer.valueOf(parameters.split(",").length); //$NON-NLS-1$
            Integer known = arityByName.get(key);
            if (known == null)
            {
                arityByName.put(key, arity);
                firstSpellingByName.put(key, handler);
                firstEventByName.put(key, entry.get("event")); //$NON-NLS-1$
            }
            else if (!known.equals(arity))
            {
                plan.error = "route handler '" + handler + "' answers events " //$NON-NLS-1$ //$NON-NLS-2$
                    + firstEventByName.get(key) + " (" //$NON-NLS-1$
                    + BmRouteMapHelper.handlerParameters(firstEventByName.get(key))
                    + ") and " + entry.get("event") + " (" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + parameters + "), whose handlers take different numbers of parameters; " //$NON-NLS-1$
                    + "give each event a handler of its own. Nothing was written to the module."; //$NON-NLS-1$
                return plan;
            }
        }
        for (Map.Entry<String, String> named : firstSpellingByName.entrySet())
        {
            String handler = named.getValue();
            if (moduleText != null && GenerateEventHandlersTool.declares(moduleText, handler))
            {
                plan.alreadyPresent.add(handler);
                continue;
            }
            String stub = BmRouteMapHelper.handlerStub(handler, firstEventByName.get(named.getKey()),
                language);
            if (stub == null)
            {
                continue;
            }
            plan.names.add(handler);
            plan.text.append(stub).append("\n\n"); //$NON-NLS-1$
        }
        return plan;
    }

    /**
     * Writes procedures into a module through the module writer, which creates the module when it
     * has no file yet.
     * <p>
     * The text goes where {@link HandlerStubPlacement} puts it: into the module's handler region,
     * into a new one, or into the whole shape when the module is empty.
     * </p>
     *
     * @param project the project that owns the module
     * @param modulePath the module path under src/, e.g. {@code BusinessProcesses/X/ObjectModule.bsl}
     * @param moduleText the module as it was read, or null when it has no file
     * @param text the procedures to write
     * @param language the language the module's directives are written in
     * @return the writer's refusal, or null when the text was written
     */
    private static String appendToModule(IProject project, String modulePath, String moduleText,
        String text, BslScriptLanguage language)
    {
        HandlerStubPlacement.Plan placement = HandlerStubPlacement.plan(moduleText, text, language);
        Map<String, String> writeParams = new LinkedHashMap<>();
        writeParams.put("projectName", project.getName()); //$NON-NLS-1$
        writeParams.put("modulePath", modulePath); //$NON-NLS-1$
        writeParams.put("mode", placement.insertBeforeLine == null //$NON-NLS-1$
            ? ModuleSourceWriter.MODE_APPEND : ModuleSourceWriter.MODE_INSERT_BEFORE);
        if (placement.insertBeforeLine != null)
        {
            writeParams.put("line", String.valueOf(placement.insertBeforeLine)); //$NON-NLS-1$
        }
        writeParams.put("content", placement.text); //$NON-NLS-1$
        ModuleSourceWriter writer = new ModuleSourceWriter();
        String answer = writer.execute(writeParams);
        if (answer == null)
        {
            return "the module writer answered nothing"; //$NON-NLS-1$
        }
        boolean refused = answer.contains("\"success\": false") || answer.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || answer.startsWith("Error:") || answer.startsWith("**Error"); //$NON-NLS-1$ //$NON-NLS-2$
        return refused ? answer : null;
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
            return ProjectResolver.notFound(projectName).toJson();
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
            return ProjectResolver.notFound(projectName).toJson();
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
