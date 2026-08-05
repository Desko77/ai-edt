/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Locale;
import java.util.Map;

import ru.aiedt.mcp.server.support.BmInfobaseExtensionHelper;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Turns a binary external data processor or report into Designer-XML.
 * <p>
 * This closes the gap between "a customer sent one file" and an EDT project. The plugin could
 * already build a project out of Designer-XML, but nothing produced that XML from a binary, so the
 * file had to go through the Designer by hand before any tool here could touch it. With this, the
 * two steps compose: unpack the binary, then hand the directory to
 * {@code import_configuration_from_xml}.
 * </p>
 * <p>
 * Only {@code .epf} and {@code .erf} are handled. A {@code .cf} or {@code .cfe} is a whole
 * configuration and the platform has no direct file-to-XML conversion for one - it has to be loaded
 * into an infobase first, which overwrites whatever that infobase holds. That is not something to
 * do as a side effect of a request to read a file, so it is refused here with an explanation rather
 * than done quietly.
 * </p>
 */
public class ExternalBinaryUnpacker
    implements IMcpTool
{
    /** The tool name, also the operation name under {@code config_io}. */
    public static final String NAME = "unpack_external_binary"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Convert a binary external data processor or report (.epf / .erf) into " //$NON-NLS-1$
            + "Designer-XML, so it can then be turned into an EDT project with " //$NON-NLS-1$
            + "import_configuration_from_xml. Use this when a file arrives from outside and there " //$NON-NLS-1$
            + "is no project for it yet. The infobase named by projectName / applicationId only " //$NON-NLS-1$
            + "hosts the Designer process - its configuration is not read or changed. Whole " //$NON-NLS-1$
            + "configurations (.cf) and extensions (.cfe) are NOT accepted: converting one means " //$NON-NLS-1$
            + "loading it into an infobase and overwriting what is there, so use install_extension " //$NON-NLS-1$
            + "or a scratch infobase deliberately instead."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "Project whose platform runtime and infobase host the Designer process.", true) //$NON-NLS-1$
            .stringProperty("sourcePath", //$NON-NLS-1$
                "Absolute path to the .epf or .erf file to convert.", true) //$NON-NLS-1$
            .stringProperty("targetPath", //$NON-NLS-1$
                "Absolute path to the directory the XML is written into. Created when missing; " //$NON-NLS-1$
                    + "hand this same path to import_configuration_from_xml afterwards.", true) //$NON-NLS-1$
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application to take the infobase from; omit to use the project's own.") //$NON-NLS-1$
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
        String sourcePath = JsonUtils.extractStringArgument(params, "sourcePath"); //$NON-NLS-1$
        String targetPath = JsonUtils.extractStringArgument(params, "targetPath"); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        if (sourcePath == null || sourcePath.isEmpty())
        {
            return ToolResult.error("sourcePath is required").toJson(); //$NON-NLS-1$
        }
        if (targetPath == null || targetPath.isEmpty())
        {
            return ToolResult.error("targetPath is required").toJson(); //$NON-NLS-1$
        }

        String refusal = refuseWholeConfiguration(sourcePath);
        if (refusal != null)
        {
            return ToolResult.error(refusal).toJson();
        }

        BmInfobaseExtensionHelper.ExportResult result = BmInfobaseExtensionHelper
            .convertExternalToXml(projectName, applicationId, sourcePath, targetPath);
        if (!result.ok)
        {
            ToolResult error = ToolResult.error(result.error != null ? result.error
                : "The conversion did not report a reason for failing."); //$NON-NLS-1$
            if (result.failureKind != null)
            {
                error.put(result.failureKind, true);
            }
            return error.toJson();
        }

        return ToolResult.success()
            .put("sourcePath", sourcePath) //$NON-NLS-1$
            .put("targetPath", targetPath) //$NON-NLS-1$
            .put("infobase", result.infobaseName != null ? result.infobaseName : "") //$NON-NLS-1$ //$NON-NLS-2$
            .put("nextStep", "import_configuration_from_xml with importPath=" + targetPath //$NON-NLS-1$ //$NON-NLS-2$
                + " and a new projectName") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Refuses a whole configuration or extension file, saying why.
     *
     * @param sourcePath the requested file
     * @return the refusal, or <code>null</code> when the file is one this tool handles
     */
    private static String refuseWholeConfiguration(String sourcePath)
    {
        String lower = sourcePath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".cf") || lower.endsWith(".cfe")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return "'" + sourcePath + "' is a whole configuration or extension. The platform " //$NON-NLS-1$ //$NON-NLS-2$
                + "cannot convert one to XML from the file alone - it has to be loaded into an " //$NON-NLS-1$
                + "infobase first, replacing that infobase's configuration. Doing that silently " //$NON-NLS-1$
                + "as part of reading a file would be destructive, so it is not done here. To " //$NON-NLS-1$
                + "deploy an extension into an infobase, use install_extension; to get one into " //$NON-NLS-1$
                + "a project, load it into an infobase you are willing to overwrite and export " //$NON-NLS-1$
                + "XML from there."; //$NON-NLS-1$
        }
        if (!lower.endsWith(".epf") && !lower.endsWith(".erf")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return "'" + sourcePath + "' is not an external object. This converts .epf and .erf " //$NON-NLS-1$ //$NON-NLS-2$
                + "files only."; //$NON-NLS-1$
        }
        return null;
    }
}
