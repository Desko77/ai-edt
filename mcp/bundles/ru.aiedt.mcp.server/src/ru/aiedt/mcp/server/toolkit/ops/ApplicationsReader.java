/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.IApplicationType;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.ProjectStateGuard;

/**
 * Lists the applications - the infobase launch targets - a project can run against.
 * <p>
 * Each application carries the id the update and debug tools address it by, plus its name, type and
 * how far its infobase is from the project. The per-application update-state read is allowed to fail
 * on its own without sinking the rest: a single unreachable infobase is reported in its own row, not
 * as the whole call failing.
 * </p>
 */
public class ApplicationsReader
    implements IMcpTool
{
    @Override
    public String getName()
    {
        return "get_applications"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `infobase_admin` `operation=get_applications`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Lists a project's applications - the infobases it can run against. Returns each one's " //$NON-NLS-1$
            + "ID, name, type, and update state. The ID is what update_database and debug_launch need."; //$NON-NLS-1$
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
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName must be provided").toJson(); //$NON-NLS-1$
        }

        String stateError = ProjectStateGuard.checkReadyOrError(projectName);
        if (stateError != null)
        {
            return ToolResult.error(stateError).toJson();
        }

        return getApplications(projectName);
    }

    /**
     * Reads a project's applications and renders them.
     *
     * @param projectName the project name, already known to be non-empty
     * @return the JSON document
     */
    private static String getApplications(String projectName)
    {
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        Activator activator = Activator.getDefault();
        IApplicationManager applicationManager =
            activator == null ? null : activator.getApplicationManager();
        if (applicationManager == null)
        {
            return ToolResult.error("IApplicationManager service cannot be reached").toJson(); //$NON-NLS-1$
        }

        String name = project.getName();
        try
        {
            List<IApplication> applications = applicationManager.getApplications(project);
            if (applications == null || applications.isEmpty())
            {
                return ToolResult.success()
                    .put("project", name) //$NON-NLS-1$
                    .put("applications", new JsonArray()) //$NON-NLS-1$
                    .put("count", 0) //$NON-NLS-1$
                    .put("message", "The project has no applications") //$NON-NLS-1$ //$NON-NLS-2$
                    .toJson();
            }

            JsonArray array = new JsonArray();
            for (IApplication application : applications)
            {
                array.add(describe(application, applicationManager));
            }

            ToolResult result = ToolResult.success()
                .put("project", name) //$NON-NLS-1$
                .put("applications", array) //$NON-NLS-1$
                .put("count", array.size()); //$NON-NLS-1$

            try
            {
                applicationManager.getDefaultApplication(project)
                    .ifPresent(application -> result.put("defaultApplicationId", application.getId())); //$NON-NLS-1$
            }
            catch (ApplicationException e)
            {
                Activator.logError("Failed to resolve the default application for " + name, e); //$NON-NLS-1$
            }

            return result.toJson();
        }
        catch (ApplicationException e)
        {
            Activator.logError("Failed to list applications for " + name, e); //$NON-NLS-1$
            return ToolResult.error("Error: the application list could not be read: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Describes one application.
     *
     * @param application the application
     * @param applicationManager the manager, for the update state
     * @return the application as a JSON object
     */
    private static JsonObject describe(IApplication application, IApplicationManager applicationManager)
    {
        JsonObject object = new JsonObject();
        object.addProperty("id", application.getId()); //$NON-NLS-1$
        object.addProperty("name", application.getName()); //$NON-NLS-1$

        IApplicationType type = application.getType();
        if (type != null)
        {
            object.addProperty("type", type.getId()); //$NON-NLS-1$
        }

        try
        {
            ApplicationUpdateState state = applicationManager.getUpdateState(application);
            if (state != null)
            {
                object.addProperty("updateState", state.name()); //$NON-NLS-1$
                object.addProperty("updateStateDescription", describeUpdateState(state)); //$NON-NLS-1$
            }
        }
        catch (ApplicationException e)
        {
            object.addProperty("updateState", "ERROR"); //$NON-NLS-1$ //$NON-NLS-2$
            object.addProperty("updateStateError", e.getMessage()); //$NON-NLS-1$
            Activator.logError("Failed to read the update state of application " + application.getId(), e); //$NON-NLS-1$
        }

        application.getRequiredVersion()
            .ifPresent(version -> object.addProperty("requiredVersion", version)); //$NON-NLS-1$
        return object;
    }

    /**
     * Puts a human sentence to an update state.
     *
     * @param state the update state
     * @return the description
     */
    private static String describeUpdateState(ApplicationUpdateState state)
    {
        switch (state)
        {
        case UNKNOWN:
            return "State is unknown"; //$NON-NLS-1$
        case INCREMENTAL_UPDATE_REQUIRED:
            return "Needs an incremental update"; //$NON-NLS-1$
        case FULL_UPDATE_REQUIRED:
            return "Needs a full update"; //$NON-NLS-1$
        case UPDATED:
            return "Already up to date"; //$NON-NLS-1$
        case BEING_UPDATED:
            return "Update is in progress"; //$NON-NLS-1$
        default:
            return state.name();
        }
    }
}
