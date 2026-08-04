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
 * Creates a FILE infobase and registers it in EDT's infobase list, optionally
 * associating it to a project (which makes it appear in get_applications). The
 * physical creation runs the 1C:Enterprise thick client, so a resolvable
 * platform runtime is required.
 */
public class InfobaseCreator implements IMcpTool
{
    public static final String NAME = "create_infobase"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `infobase_admin` `operation=create_infobase`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Create a FILE infobase and register it in EDT's infobase list. Pass " //$NON-NLS-1$
            + "name and path (an empty directory for the .1CD); optionally platform " //$NON-NLS-1$
            + "(version, blank = latest), templateCf (a .cf/.cfe to load on create), and " //$NON-NLS-1$
            + "projectName to also associate it (the 'launch configuration' step that " //$NON-NLS-1$
            + "makes it show in get_applications). Requires a resolvable 1C:Enterprise " //$NON-NLS-1$
            + "platform runtime - the thick client performs the physical creation. Use " //$NON-NLS-1$
            + "delete_infobase to remove it."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("name", "Infobase name (required, unique in EDT's list).", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("path", //$NON-NLS-1$
                "Absolute path to the infobase directory (required) - where the .1CD is " //$NON-NLS-1$
                    + "created. Should be an empty/new directory.", true) //$NON-NLS-1$
            .stringProperty("platform", //$NON-NLS-1$
                "1C:Enterprise platform version (optional, blank = latest available).") //$NON-NLS-1$
            .stringProperty("templateCf", //$NON-NLS-1$
                "Optional path to a .cf / .cfe to load into the new infobase on create.") //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "Optional EDT project to associate the new infobase to (creates the " //$NON-NLS-1$
                    + "launch configuration so it appears in get_applications).") //$NON-NLS-1$
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
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String path = JsonUtils.extractStringArgument(params, "path"); //$NON-NLS-1$
        String platform = JsonUtils.extractStringArgument(params, "platform"); //$NON-NLS-1$
        String templateCf = JsonUtils.extractStringArgument(params, "templateCf"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$

        if (name == null || name.isEmpty())
        {
            return ToolResult.error("name is required").toJson(); //$NON-NLS-1$
        }
        if (path == null || path.isEmpty())
        {
            return ToolResult.error("path is required (an absolute directory for the .1CD)") //$NON-NLS-1$
                .toJson();
        }

        BmInfobaseLifecycleHelper.CreateResult r =
            BmInfobaseLifecycleHelper.createInfobase(name, path, platform, templateCf, projectName);

        if (!r.ok)
        {
            ToolResult err = ToolResult.error(r.error)
                .put("operation", NAME) //$NON-NLS-1$
                .put("name", name); //$NON-NLS-1$
            if (r.failureKind != null)
            {
                err.put(r.failureKind, Boolean.TRUE);
            }
            return err.toJson();
        }

        ToolResult ok = ToolResult.success()
            .put("operation", NAME) //$NON-NLS-1$
            .put("infobaseName", r.infobaseName) //$NON-NLS-1$
            .put("path", r.path) //$NON-NLS-1$
            .put("associated", r.associated); //$NON-NLS-1$
        if (r.uuid != null)
        {
            ok.put("uuid", r.uuid); //$NON-NLS-1$
        }
        if (r.applicationId != null)
        {
            ok.put("applicationId", r.applicationId); //$NON-NLS-1$
        }
        if (r.associateWarning != null)
        {
            ok.put("associateWarning", r.associateWarning); //$NON-NLS-1$
        }
        return ok.toJson();
    }
}
