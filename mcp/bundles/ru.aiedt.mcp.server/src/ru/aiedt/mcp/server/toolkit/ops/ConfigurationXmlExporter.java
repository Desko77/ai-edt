/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.support.ErrorTags;
import ru.aiedt.mcp.server.support.TextSuggest;
import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Exports a whole EDT configuration project to the platform Designer-XML format
 * ({@code DumpConfigToFiles} layout) on disk - for VCS / exchange workflows.
 *
 * <p>Distinct from {@code export_object} (which builds an {@code .epf}/{@code .erf}).
 * Backed by the EDT CLI API
 * {@code com._1c.g5.v8.dt.cli.api.workspace.IExportConfigurationFilesApi#exportProject(String, Path)},
 * obtained as an OSGi service and invoked reflectively (no compile dependency).
 * Synchronous - for a large configuration this can run a while.
 */
public class ConfigurationXmlExporter implements IMcpTool
{
    public static final String NAME = "export_configuration_to_xml"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `config_io` `operation=export_configuration_to_xml`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Export a whole configuration project to platform Designer-XML files " //$NON-NLS-1$
            + "(DumpConfigToFiles format) at outputPath - for VCS / exchange. Distinct from " //$NON-NLS-1$
            + "export_object (.epf/.erf). Synchronous; overwrites the output directory contents. " //$NON-NLS-1$
            + "Requires the EDT plugin com._1c.g5.v8.dt.cli.api."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "EDT configuration project name (required).", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("outputPath", //$NON-NLS-1$
                "Absolute path to the output directory for the XML dump (required, created " //$NON-NLS-1$
                    + "if missing; existing contents are overwritten).", true) //$NON-NLS-1$
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
        String outputPath = JsonUtils.extractStringArgument(params, "outputPath"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        if (outputPath == null || outputPath.isEmpty())
        {
            return ToolResult.error("outputPath is required").toJson(); //$NON-NLS-1$
        }

        Path out;
        try
        {
            out = Paths.get(outputPath).toAbsolutePath().normalize();
        }
        catch (Exception e)
        {
            return ToolResult.error("Invalid outputPath: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        if (Files.exists(out) && !Files.isDirectory(out))
        {
            return ToolResult.error("outputPath already points at a file, so nothing can be written under it: " + out).toJson(); //$NON-NLS-1$
        }
        try
        {
            Files.createDirectories(out);
        }
        catch (Exception e)
        {
            return ToolResult.error("Cannot create outputPath: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        boolean outsideWorkspace = isOutsideWorkspace(out);
        if (outsideWorkspace)
        {
            Activator.logWarning("export_configuration_to_xml: outputPath is outside the " //$NON-NLS-1$
                + "workspace: " + out); //$NON-NLS-1$
        }

        Object api = Activator.getDefault().getExportConfigurationFilesApi();
        if (api == null)
        {
            return ToolResult.error("IExportConfigurationFilesApi is not available - the EDT " //$NON-NLS-1$
                + "plugin com._1c.g5.v8.dt.cli.api is not installed on this build.") //$NON-NLS-1$
                .put(ErrorTags.CLI_API_NOT_FOUND.wire(), true)
                .toJson();
        }

        try
        {
            Method exportProject = api.getClass().getMethod("exportProject", //$NON-NLS-1$
                String.class, Path.class);
            exportProject.invoke(api, projectName, out);
        }
        catch (InvocationTargetException ite)
        {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            Activator.logError("export_configuration_to_xml failed", cause); //$NON-NLS-1$
            return ToolResult.error("Export failed: " + TextSuggest.safeMessage(cause)).toJson(); //$NON-NLS-1$
        }
        catch (NoSuchMethodException | IllegalAccessException e)
        {
            return ToolResult.error("CLI API mismatch (exportProject(String, Path) not found): " //$NON-NLS-1$
                + e.getMessage()).put(ErrorTags.CLI_API_NOT_FOUND.wire(), true).toJson();
        }
        catch (Exception e)
        {
            Activator.logError("export_configuration_to_xml failed", e); //$NON-NLS-1$
            return ToolResult.error("Export failed: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }

        ToolResult tool = ToolResult.success()
            .put("operation", "export_configuration_to_xml") //$NON-NLS-1$ //$NON-NLS-2$
            .put("projectName", projectName) //$NON-NLS-1$
            .put("outputPath", out.toString()); //$NON-NLS-1$
        if (outsideWorkspace)
        {
            tool.put("outsideWorkspace", true); //$NON-NLS-1$
        }
        return tool.toJson();
    }

    private static boolean isOutsideWorkspace(Path p)
    {
        try
        {
            IPath wsLoc = ResourcesPlugin.getWorkspace().getRoot().getLocation();
            if (wsLoc == null)
            {
                return false;
            }
            Path ws = Paths.get(wsLoc.toOSString()).toAbsolutePath().normalize();
            return !p.startsWith(ws);
        }
        catch (Exception e)
        {
            return false;
        }
    }
}
