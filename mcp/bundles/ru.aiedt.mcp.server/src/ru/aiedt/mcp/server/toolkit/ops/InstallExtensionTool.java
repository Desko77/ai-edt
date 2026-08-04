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
 * Installs a {@code .cfe} file into a project's infobase AS a configuration
 * extension (mutates the infobase), via the 1C:Enterprise thick client. This is
 * the deploy counterpart of {@code export_extension}; {@code installYaxunit} is
 * this tool pointed at a YAxUnit {@code .cfe} with {@code extensionName=YAxUnit}.
 */
public class InstallExtensionTool implements IMcpTool
{
    public static final String NAME = "install_extension"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `extension_workshop` `operation=install_extension`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Install a .cfe file into a project's infobase AS a configuration extension " //$NON-NLS-1$
            + "(MUTATES the infobase). Pass projectName, extensionName (the name to register " //$NON-NLS-1$
            + "the extension under), and inputPath (the .cfe to load); applicationId is " //$NON-NLS-1$
            + "optional when the project has one infobase application. updateDatabase (default " //$NON-NLS-1$
            + "true) also applies the extension to the database (/UpdateDBCfg -Extension). " //$NON-NLS-1$
            + "To install YAxUnit, point inputPath at a YAxUnit .cfe with extensionName=YAxUnit. " //$NON-NLS-1$
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
                "Name to register the extension under in the infobase (required).", true) //$NON-NLS-1$
            .stringProperty("inputPath", //$NON-NLS-1$
                "Absolute path to the .cfe file to install, an http(s):// URL, or a GitHub " //$NON-NLS-1$
                    + "repo source 'github:owner/repo' / 'gh:owner/repo' (optionally " //$NON-NLS-1$
                    + "'#assetNamePrefix' to pick one of several .cfe assets - the LATEST " //$NON-NLS-1$
                    + "release is used). URL/GitHub sources are downloaded to a temp file " //$NON-NLS-1$
                    + "and cleaned up after install. Required.", true) //$NON-NLS-1$
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application (infobase) id. Optional when the project has a single " //$NON-NLS-1$
                    + "application; otherwise required (get_applications lists ids).") //$NON-NLS-1$
            .booleanProperty("updateDatabase", //$NON-NLS-1$
                "Also apply the extension to the database (/UpdateDBCfg -Extension). " //$NON-NLS-1$
                    + "Default true.") //$NON-NLS-1$
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
        String inputPath = JsonUtils.extractStringArgument(params, "inputPath"); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        boolean updateDatabase = JsonUtils.extractBooleanArgument(params, "updateDatabase", true); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        if (extensionName == null || extensionName.isEmpty())
        {
            return ToolResult.error("extensionName is required (the name to register the " //$NON-NLS-1$
                + "extension under in the infobase).").toJson(); //$NON-NLS-1$
        }
        if (inputPath == null || inputPath.isEmpty())
        {
            return ToolResult.error("inputPath is required (an absolute path to the .cfe " //$NON-NLS-1$
                + "file to install).").toJson(); //$NON-NLS-1$
        }

        BmInfobaseExtensionHelper.InstallResult r = BmInfobaseExtensionHelper.installExtension(
            projectName, applicationId, extensionName, inputPath, updateDatabase);

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
            if (r.designerLog != null)
            {
                err.put("designerLog", r.designerLog); //$NON-NLS-1$
            }
            return err.toJson();
        }

        ToolResult ok = ToolResult.success()
            .put("operation", NAME) //$NON-NLS-1$
            .put("projectName", projectName) //$NON-NLS-1$
            .put("infobaseName", r.infobaseName) //$NON-NLS-1$
            .put("extensionName", extensionName) //$NON-NLS-1$
            .put("inputPath", inputPath) //$NON-NLS-1$
            .put("databaseUpdated", r.databaseUpdated); //$NON-NLS-1$
        if (r.designerLog != null)
        {
            ok.put("designerLog", r.designerLog); //$NON-NLS-1$
        }
        return ok.toJson();
    }
}
