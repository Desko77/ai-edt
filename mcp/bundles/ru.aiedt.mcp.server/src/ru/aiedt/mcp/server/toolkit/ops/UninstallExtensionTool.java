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
 * Uninstalls (removes) a named configuration extension from a project's
 * infobase. DESTRUCTIVE - it mutates the connected infobase via the 1C:Enterprise
 * thick client. An explicit extensionName is required (no accidental delete-all).
 */
public class UninstallExtensionTool implements IMcpTool
{
    public static final String NAME = "uninstall_extension"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `extension_workshop` `operation=uninstall_extension`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Uninstall a named configuration extension from a project's infobase " //$NON-NLS-1$
            + "(DESTRUCTIVE - removes it from the connected infobase). Pass projectName and " //$NON-NLS-1$
            + "extensionName (exact name, as reported by list_extension); applicationId is " //$NON-NLS-1$
            + "optional when the project has one infobase application. Runs the 1C:Enterprise " //$NON-NLS-1$
            + "thick client - requires a resolvable platform runtime, valid stored credentials " //$NON-NLS-1$
            + "(set_infobase_credentials), and the infobase not be locked by a running client."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project to work in", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("extensionName", //$NON-NLS-1$
                "Exact name of the extension to remove (required; from list_extension).", true) //$NON-NLS-1$
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
        String extensionName = JsonUtils.extractStringArgument(params, "extensionName"); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        if (extensionName == null || extensionName.isEmpty())
        {
            return ToolResult.error("extensionName is required (the exact extension name to " //$NON-NLS-1$
                + "remove; use list_extension to see installed names).").toJson(); //$NON-NLS-1$
        }

        BmInfobaseExtensionHelper.DeleteResult r =
            BmInfobaseExtensionHelper.uninstallExtension(projectName, applicationId, extensionName);

        if (!r.ok)
        {
            ToolResult err = ToolResult.error(r.error)
                .put("operation", NAME) //$NON-NLS-1$
                .put("projectName", projectName) //$NON-NLS-1$
                .put("extensionName", extensionName); //$NON-NLS-1$
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
            .put("extensionName", extensionName) //$NON-NLS-1$
            .put("removed", Boolean.TRUE) //$NON-NLS-1$
            .toJson();
    }
}
