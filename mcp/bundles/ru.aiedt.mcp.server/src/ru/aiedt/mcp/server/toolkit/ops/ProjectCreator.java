/*
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Map;

import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BmConfigurationProjectHelper;
import ru.aiedt.mcp.server.support.ErrorTags;

/**
 * Creates a new base 1C:Configuration DT project (an empty configuration, NOT an
 * extension). Counterpart of {@code extension_workshop create_extension_project}
 * and {@code external_object_workshop create}: those create extension / external
 * object projects, this one creates the top-level configuration project a
 * configuration or extension develops against.
 */
public class ProjectCreator implements IMcpTool
{
    public static final String NAME = "create_project"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `project_admin` `operation=create_project`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Create a new BASE 1C:Configuration DT project (an empty configuration, NOT an " //$NON-NLS-1$
            + "extension). Pass projectName (the new project / Configuration name; must be free). " //$NON-NLS-1$
            + "version is optional (e.g. '8.3.21'); when omitted the latest runtime version this " //$NON-NLS-1$
            + "EDT installation supports is used. Writes the project manifest, the configuration " //$NON-NLS-1$
            + "nature and the root Configuration.mdo. For an extension use extension_workshop " //$NON-NLS-1$
            + "create_extension_project; for a .epf/.erf use external_object_workshop create."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "New base configuration / project name (required, must not already exist).", true) //$NON-NLS-1$
            .stringProperty("version", //$NON-NLS-1$
                "Platform runtime version 'major.minor.micro', e.g. '8.3.21'. Optional - defaults " //$NON-NLS-1$
                    + "to the latest version this EDT installation supports.") //$NON-NLS-1$
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
        String version = JsonUtils.extractStringArgument(params, "version"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }

        BmConfigurationProjectHelper.CreateResult r =
            BmConfigurationProjectHelper.createConfigurationProject(projectName, version);

        if (r.alreadyExists)
        {
            // Not an error: report the pre-existing state with the helper's hint.
            return ToolResult.success()
                .put("operation", NAME) //$NON-NLS-1$
                .put("projectName", r.createdProjectName) //$NON-NLS-1$
                .put("created", Boolean.FALSE) //$NON-NLS-1$
                .put(ErrorTags.ALREADY_EXISTS.wire(), Boolean.TRUE)
                .put("hint", r.hint) //$NON-NLS-1$
                .toJson();
        }

        if (!r.ok)
        {
            ToolResult err = ToolResult.error(r.error)
                .put("operation", NAME) //$NON-NLS-1$
                .put("projectName", projectName); //$NON-NLS-1$
            if (r.serviceNotFound)
            {
                err.put("serviceNotFound", Boolean.TRUE); //$NON-NLS-1$
            }
            if (r.hint != null)
            {
                err.put("hint", r.hint); //$NON-NLS-1$
            }
            return err.toJson();
        }

        return ToolResult.success()
            .put("operation", NAME) //$NON-NLS-1$
            .put("projectName", r.createdProjectName) //$NON-NLS-1$
            .put("created", Boolean.TRUE) //$NON-NLS-1$
            .put("version", r.version) //$NON-NLS-1$
            .toJson();
    }
}
