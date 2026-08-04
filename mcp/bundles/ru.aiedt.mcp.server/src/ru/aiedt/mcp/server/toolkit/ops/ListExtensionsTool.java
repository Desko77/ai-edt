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
import ru.aiedt.mcp.server.support.BmInfobaseExtensionHelper;

/**
 * Lists the configuration extensions installed in a project's infobase
 * (read-only). Runs the 1C:Enterprise thick client against the connected
 * infobase, so a resolvable platform runtime and valid stored credentials are
 * required.
 */
public class ListExtensionsTool implements IMcpTool
{
    public static final String NAME = "list_extension"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `extension_workshop` `operation=list_extension`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "List the configuration extensions installed in a project's infobase " //$NON-NLS-1$
            + "(read-only). Pass projectName; applicationId is optional when the project " //$NON-NLS-1$
            + "has one infobase application (see get_applications). Runs the 1C:Enterprise " //$NON-NLS-1$
            + "thick client against the connected infobase - requires a resolvable platform " //$NON-NLS-1$
            + "runtime and valid stored credentials (set_infobase_credentials). The infobase " //$NON-NLS-1$
            + "must not be locked by a running 1C client."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project to work in", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application (infobase) id. Optional when the project has a single " //$NON-NLS-1$
                    + "application; otherwise required (get_applications lists ids).") //$NON-NLS-1$
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
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$

        BmInfobaseExtensionHelper.ListResult r =
            BmInfobaseExtensionHelper.listExtensions(projectName, applicationId);

        if (!r.ok)
        {
            ToolResult err = ToolResult.error(r.error)
                .put("operation", NAME) //$NON-NLS-1$
                .put("projectName", projectName); //$NON-NLS-1$
            if (r.infobaseName != null)
            {
                err.put("infobaseName", r.infobaseName); //$NON-NLS-1$
            }
            if (r.failureKind != null)
            {
                err.put(r.failureKind, Boolean.TRUE);
            }
            return err.toJson();
        }

        return ToolResult.success()
            .put("operation", NAME) //$NON-NLS-1$
            .put("projectName", projectName) //$NON-NLS-1$
            .put("infobaseName", r.infobaseName) //$NON-NLS-1$
            .put("count", r.extensions != null ? r.extensions.size() : 0) //$NON-NLS-1$
            .put("extensions", r.extensions) //$NON-NLS-1$
            .toJson();
    }
}
