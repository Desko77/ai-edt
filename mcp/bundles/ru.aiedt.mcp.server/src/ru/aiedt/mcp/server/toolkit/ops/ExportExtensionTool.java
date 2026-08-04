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
 * Exports a named configuration extension from a project's infobase to a
 * {@code .cfe} file on disk. Read-only on the infobase (it only writes the
 * output file), via the 1C:Enterprise thick client.
 */
public class ExportExtensionTool implements IMcpTool
{
    public static final String NAME = "export_extension"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `extension_workshop` `operation=export_extension`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Export a named configuration extension from a project's infobase to a " //$NON-NLS-1$
            + ".cfe file (read-only on the infobase). Pass projectName, extensionName " //$NON-NLS-1$
            + "(exact, from list_extension), and outputPath (the .cfe to write); " //$NON-NLS-1$
            + "applicationId is optional when the project has one infobase application. " //$NON-NLS-1$
            + "Runs the 1C:Enterprise thick client - requires a resolvable platform runtime, " //$NON-NLS-1$
            + "valid stored credentials (set_infobase_credentials), and the infobase not be " //$NON-NLS-1$
            + "locked by a running client."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project to work in", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("extensionName", //$NON-NLS-1$
                "Exact name of the extension to export (required; from list_extension).", true) //$NON-NLS-1$
            .stringProperty("outputPath", //$NON-NLS-1$
                "Absolute path to the .cfe file to write (required).", true) //$NON-NLS-1$
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
        String outputPath = JsonUtils.extractStringArgument(params, "outputPath"); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        if (extensionName == null || extensionName.isEmpty())
        {
            return ToolResult.error("extensionName is required (use list_extension to see " //$NON-NLS-1$
                + "installed names).").toJson(); //$NON-NLS-1$
        }
        if (outputPath == null || outputPath.isEmpty())
        {
            return ToolResult.error("outputPath is required (an absolute path to the .cfe " //$NON-NLS-1$
                + "file to write).").toJson(); //$NON-NLS-1$
        }

        BmInfobaseExtensionHelper.ExportResult r = BmInfobaseExtensionHelper.exportExtension(
            projectName, applicationId, extensionName, outputPath);

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
            .put("outputPath", outputPath) //$NON-NLS-1$
            .put("sizeBytes", r.sizeBytes) //$NON-NLS-1$
            .toJson();
    }
}
