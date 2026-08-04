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
import ru.aiedt.mcp.server.support.BmInfobaseLifecycleHelper;

/**
 * Associates an existing infobase to a project - the "launch configuration"
 * that makes the infobase appear in get_applications and become a target for
 * update_database / launch. In EDT there is no separate launch-config object
 * for this; the project-to-infobase association IS it.
 */
public class LaunchConfigCreator implements IMcpTool
{
    public static final String NAME = "create_launch_config"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `infobase_admin` `operation=create_launch_config`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Associate an existing infobase to a project - the 'launch " //$NON-NLS-1$
            + "configuration' step that makes the infobase show up in get_applications " //$NON-NLS-1$
            + "and become a target for update_database / launch. Pass projectName and " //$NON-NLS-1$
            + "infobaseName (the infobase must already exist in EDT's list - see " //$NON-NLS-1$
            + "create_infobase). The infobase is bound as not-synchronized, so " //$NON-NLS-1$
            + "get_applications will report that an update is required."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project to work in", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("infobaseName", //$NON-NLS-1$
                "Name of an existing infobase in EDT's list to associate (required).", true) //$NON-NLS-1$
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
        String infobaseName = JsonUtils.extractStringArgument(params, "infobaseName"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        if (infobaseName == null || infobaseName.isEmpty())
        {
            return ToolResult.error("infobaseName is required").toJson(); //$NON-NLS-1$
        }

        BmInfobaseLifecycleHelper.AssocResult r =
            BmInfobaseLifecycleHelper.associate(projectName, infobaseName);

        if (!r.ok)
        {
            ToolResult err = ToolResult.error(r.error)
                .put("operation", NAME) //$NON-NLS-1$
                .put("projectName", projectName) //$NON-NLS-1$
                .put("infobaseName", infobaseName); //$NON-NLS-1$
            if (r.failureKind != null)
            {
                err.put(r.failureKind, Boolean.TRUE);
            }
            return err.toJson();
        }

        ToolResult ok = ToolResult.success()
            .put("operation", NAME) //$NON-NLS-1$
            .put("projectName", projectName) //$NON-NLS-1$
            .put("infobaseName", infobaseName); //$NON-NLS-1$
        if (r.applicationId != null)
        {
            ok.put("applicationId", r.applicationId); //$NON-NLS-1$
        }
        return ok.toJson();
    }
}
