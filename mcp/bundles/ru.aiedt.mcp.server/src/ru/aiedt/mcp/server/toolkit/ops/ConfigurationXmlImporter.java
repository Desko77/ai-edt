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

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.support.ErrorTags;
import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Imports a configuration from platform Designer-XML files into a NEW EDT project.
 *
 * <p>Backed by the EDT CLI API
 * {@code com._1c.g5.v8.dt.cli.api.workspace.IImportConfigurationFilesApi#importProject(Path, String, String, String)},
 * obtained as an OSGi service and invoked reflectively (no compile dependency).
 * Not destructive to existing projects - a pre-existing project name is rejected.
 * Synchronous; for a large configuration this can run a while. Requires the
 * source XML's platform version to be installed in EDT.
 */
public class ConfigurationXmlImporter implements IMcpTool
{
    public static final String NAME = "import_configuration_from_xml"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `config_io` `operation=import_configuration_from_xml`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Import a configuration from platform Designer-XML files (DumpConfigToFiles " //$NON-NLS-1$
            + "format) into a NEW EDT project named projectName. Rejects a pre-existing project " //$NON-NLS-1$
            + "name (non-destructive). Synchronous; needs the source platform version installed " //$NON-NLS-1$
            + "in EDT. Requires the EDT plugin com._1c.g5.v8.dt.cli.api."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("importPath", //$NON-NLS-1$
                "Absolute path to the directory of Designer-XML files to import (required).", true) //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "Name of the NEW EDT project to create (required; must not already exist).", true) //$NON-NLS-1$
            .stringProperty("projectNature", //$NON-NLS-1$
                "EDT nature id, or omit to auto-detect (e.g. " //$NON-NLS-1$
                    + "com._1c.g5.v8.dt.core.V8ConfigurationNature).") //$NON-NLS-1$
            .stringProperty("xmlVersion", //$NON-NLS-1$
                "Platform XML format version (e.g. 8.3.20), or omit to auto-detect.") //$NON-NLS-1$
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
        String importPath = JsonUtils.extractStringArgument(params, "importPath"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String projectNature = JsonUtils.extractStringArgument(params, "projectNature"); //$NON-NLS-1$
        String xmlVersion = JsonUtils.extractStringArgument(params, "xmlVersion"); //$NON-NLS-1$
        if (importPath == null || importPath.isEmpty())
        {
            return ToolResult.error("importPath is required").toJson(); //$NON-NLS-1$
        }
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        // Empty optional strings -> null so the API auto-detects.
        if (projectNature != null && projectNature.isEmpty())
        {
            projectNature = null;
        }
        if (xmlVersion != null && xmlVersion.isEmpty())
        {
            xmlVersion = null;
        }

        Path in;
        try
        {
            in = Paths.get(importPath).toAbsolutePath().normalize();
        }
        catch (Exception e)
        {
            return ToolResult.error("Invalid importPath: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        if (!Files.exists(in))
        {
            return ToolResult.error("No such folder to import from: " + in).toJson(); //$NON-NLS-1$
        }
        if (!Files.isDirectory(in))
        {
            return ToolResult.error("importPath must point at a folder, not a file: " + in).toJson(); //$NON-NLS-1$
        }
        IProject existing = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (existing != null && existing.exists())
        {
            return ToolResult.error("A project under this name is already in the workspace: " + projectName //$NON-NLS-1$
                + ". Import creates a NEW project - pick a name that is not in use.").toJson(); //$NON-NLS-1$
        }

        Object api = Activator.getDefault().getImportConfigurationFilesApi();
        if (api == null)
        {
            return ToolResult.error("IImportConfigurationFilesApi is not available - the EDT " //$NON-NLS-1$
                + "plugin com._1c.g5.v8.dt.cli.api is not installed on this build.") //$NON-NLS-1$
                .put(ErrorTags.CLI_API_NOT_FOUND.wire(), true)
                .toJson();
        }

        try
        {
            Method importProject = api.getClass().getMethod("importProject", //$NON-NLS-1$
                Path.class, String.class, String.class, String.class);
            importProject.invoke(api, in, projectName, projectNature, xmlVersion);
        }
        catch (InvocationTargetException ite)
        {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            Activator.logError("import_configuration_from_xml failed", cause); //$NON-NLS-1$
            return ToolResult.error("Import failed: " + cause.getMessage()).toJson(); //$NON-NLS-1$
        }
        catch (NoSuchMethodException | IllegalAccessException e)
        {
            return ToolResult.error("CLI API mismatch (importProject(Path,String,String,String) " //$NON-NLS-1$
                + "not found): " + e.getMessage()).put(ErrorTags.CLI_API_NOT_FOUND.wire(), true).toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("import_configuration_from_xml failed", e); //$NON-NLS-1$
            return ToolResult.error("Import failed: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }

        // importProject returns void, so "it did not throw" is not evidence that a
        // project appeared. It can decline the sources and return normally, and
        // reporting success then sends the caller looking for an object that was
        // never created - with nothing in the reply to suggest where it went.
        IProject imported = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (imported == null || !imported.exists())
        {
            return ToolResult.error("The import reported no error but no project '" + projectName //$NON-NLS-1$
                + "' appeared in the workspace. The sources at " + in //$NON-NLS-1$
                + " were most likely not recognised as a configuration, an extension or an " //$NON-NLS-1$
                + "external-object folder. Check that the directory is the root of a Designer " //$NON-NLS-1$
                + "XML dump, and pass projectNature explicitly if auto-detection is the problem.") //$NON-NLS-1$
                .toJson();
        }

        // The CLI import leaves the new project without triggering EDT's project
        // lifecycle (setRefreshProject(false)), so other tools see no DtProject
        // until it is reopened. Kick the lifecycle: close -> open -> refresh.
        String lifecycleNote = null;
        try
        {
            IProject created = imported;
            if (created.exists())
            {
                NullProgressMonitor mon = new NullProgressMonitor();
                if (created.isOpen())
                {
                    created.close(mon);
                }
                created.open(mon);
                created.refreshLocal(IResource.DEPTH_INFINITE, mon);
            }
        }
        catch (Exception e)
        {
            lifecycleNote = "imported, but project reopen/refresh failed (open it manually): " //$NON-NLS-1$
                + e.getMessage();
            Activator.logWarning("import_configuration_from_xml: " + lifecycleNote); //$NON-NLS-1$
        }

        // Existence is not enough. The CLI sets the project up before it runs the
        // import operation, so the folder is there whether or not anything landed
        // in it - measured on the 2026.x implementation, where setUpProject returns
        // the IProject ahead of createImportOperation. Sources are what the caller
        // came for, so sources are what gets checked.
        if (!hasImportedSources(imported))
        {
            return ToolResult.error("Project '" + projectName + "' was created but no sources " //$NON-NLS-1$ //$NON-NLS-2$
                + "were imported into it - its src folder is missing or empty. The import " //$NON-NLS-1$
                + "operation accepted the call and brought nothing across, which usually means " //$NON-NLS-1$
                + "the files at " + in + " are not a Designer XML dump of a configuration, an " //$NON-NLS-1$ //$NON-NLS-2$
                + "extension or an external object. Delete the empty project before retrying.") //$NON-NLS-1$
                .put("projectName", projectName) //$NON-NLS-1$
                .put("importPath", in.toString()) //$NON-NLS-1$
                .toJson();
        }

        ToolResult tool = ToolResult.success()
            .put("operation", "import_configuration_from_xml") //$NON-NLS-1$ //$NON-NLS-2$
            .put("projectName", projectName) //$NON-NLS-1$
            .put("importPath", in.toString()); //$NON-NLS-1$
        if (lifecycleNote != null)
        {
            tool.put("lifecycleNote", lifecycleNote); //$NON-NLS-1$
        }
        return tool.toJson();
    }

    /**
     * Tells whether an imported project actually received sources. An EDT project
     * keeps them under {@code src}; a project the CLI set up and then imported
     * nothing into has that folder missing or empty.
     *
     * @param project the project the import was told to create
     * @return true when the project holds at least one source entry
     */
    private static boolean hasImportedSources(IProject project)
    {
        try
        {
            IFolder src = project.getFolder("src"); //$NON-NLS-1$
            return src.exists() && src.members().length > 0;
        }
        catch (CoreException e)
        {
            // Unreadable is not provably empty, so do not turn a readable project
            // into a failure on the strength of a filesystem hiccup.
            Activator.logWarning("import_configuration_from_xml: cannot inspect src of " //$NON-NLS-1$
                + project.getName() + ": " + e.getMessage()); //$NON-NLS-1$
            return true;
        }
    }
}
