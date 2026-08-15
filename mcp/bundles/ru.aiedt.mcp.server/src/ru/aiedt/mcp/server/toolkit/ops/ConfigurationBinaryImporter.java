/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.BmBinaryImportHelper;
import ru.aiedt.mcp.server.support.ErrorTags;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Imports a {@code .cf} or {@code .cfe} into a new EDT project.
 * <p>
 * "Somebody sent me the extension as one file" had no answer inside the plugin: EDT imports from
 * Designer-XML and nothing else, so the only route was to leave for the Designer, load the binary
 * by hand, dump the XML and come back. This does those steps - through a staging infobase of its
 * own, removed afterwards - and hands the XML to the same import the XML path uses.
 * </p>
 *
 * @see BmBinaryImportHelper
 */
public class ConfigurationBinaryImporter implements IMcpTool
{
    public static final String NAME = "import_configuration_from_binary"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `config_io` `operation=import_configuration_from_binary`; " //$NON-NLS-1$
            + "prefer the facade for new prompts. Import a .cf (configuration) or .cfe " //$NON-NLS-1$
            + "(extension) delivered as a single binary file into a NEW EDT project. The " //$NON-NLS-1$
            + "binary is loaded into a throwaway staging infobase, dumped to Designer-XML and " //$NON-NLS-1$
            + "imported from there; the staging infobase is deleted afterwards. Rejects a " //$NON-NLS-1$
            + "pre-existing project name (non-destructive) and never touches an existing " //$NON-NLS-1$
            + "project's infobase. Runs the 1C:Enterprise thick client, so it needs a " //$NON-NLS-1$
            + "resolvable platform runtime; on a large configuration it takes minutes."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("binaryPath", //$NON-NLS-1$
                "Absolute path to the .cf or .cfe file to import (required).", true) //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "Name of the NEW EDT project to create (required; must not already exist).", true) //$NON-NLS-1$
            .stringProperty("platform", //$NON-NLS-1$
                "Platform version for the staging infobase (e.g. 8.3.24). Omit for the newest " //$NON-NLS-1$
                    + "installed. Must be able to read the binary: a file built by a newer " //$NON-NLS-1$
                    + "platform than the one used here will be refused by the Designer.") //$NON-NLS-1$
            .stringProperty("extensionName", //$NON-NLS-1$
                "For a .cfe: the name to load the extension under. Omit to take it from the " //$NON-NLS-1$
                    + "file name. Ignored for a .cf.") //$NON-NLS-1$
            .stringProperty("baseConfigurationPath", //$NON-NLS-1$
                "For a .cfe: a .cf to load into the staging infobase first, so the extension " //$NON-NLS-1$
                    + "has the configuration it borrows from. Omit to stage it against an empty " //$NON-NLS-1$
                    + "infobase, which works for an extension that adopts nothing.") //$NON-NLS-1$
            .stringProperty("keepXmlPath", //$NON-NLS-1$
                "Write the intermediate Designer-XML here and keep it, instead of using a " //$NON-NLS-1$
                    + "temporary directory that is deleted. The directory must be empty or " //$NON-NLS-1$
                    + "absent. Useful for inspecting what was actually staged.") //$NON-NLS-1$
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
        String binaryPath = JsonUtils.extractStringArgument(params, "binaryPath"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String platform = JsonUtils.extractStringArgument(params, "platform"); //$NON-NLS-1$
        String extensionName = JsonUtils.extractStringArgument(params, "extensionName"); //$NON-NLS-1$
        String basePath = JsonUtils.extractStringArgument(params, "baseConfigurationPath"); //$NON-NLS-1$
        String keepXmlPath = JsonUtils.extractStringArgument(params, "keepXmlPath"); //$NON-NLS-1$

        if (binaryPath == null || binaryPath.isEmpty())
        {
            return ToolResult.error("binaryPath is required (a .cf or .cfe file).").toJson(); //$NON-NLS-1$
        }
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required.").toJson(); //$NON-NLS-1$
        }

        Path binary;
        try
        {
            binary = Paths.get(binaryPath).toAbsolutePath().normalize();
        }
        catch (InvalidPathException e)
        {
            return ToolResult.error("binaryPath is not a valid file path: " + e.getMessage()) //$NON-NLS-1$
                .put(ErrorTags.INVALID_INPUT_PATH.wire(), true).toJson();
        }
        BmBinaryImportHelper.BinaryKind kind = BmBinaryImportHelper.kindOf(binary);
        if (kind == null)
        {
            return ToolResult.error("Expected a .cf or a .cfe, got: " + binary //$NON-NLS-1$
                + ". An .epf or .erf is an external object - import those with " //$NON-NLS-1$
                + "external_object_workshop. An XML dump goes through " //$NON-NLS-1$
                + "import_configuration_from_xml.").put(ErrorTags.INVALID_INPUT_PATH.wire(), true) //$NON-NLS-1$
                .toJson();
        }
        if (!Files.isRegularFile(binary))
        {
            return ToolResult.error("The file does not exist: " + binary) //$NON-NLS-1$
                .put(ErrorTags.INPUT_MISSING.wire(), true).toJson();
        }

        // Checked before the Designer runs, not after: staging a whole configuration takes minutes,
        // and finding out at the end that the name was taken wastes all of it.
        IProject existing = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (existing != null && existing.exists())
        {
            return ToolResult.error("A project under this name is already in the workspace: " //$NON-NLS-1$
                + projectName + ". This import creates a NEW project - pick a name that is not " //$NON-NLS-1$
                + "in use.").put(ErrorTags.ALREADY_EXISTS.wire(), true).toJson(); //$NON-NLS-1$
        }

        Path base = null;
        if (basePath != null && !basePath.isEmpty())
        {
            try
            {
                base = Paths.get(basePath).toAbsolutePath().normalize();
            }
            catch (InvalidPathException e)
            {
                return ToolResult.error("baseConfigurationPath is not a valid file path: " //$NON-NLS-1$
                    + e.getMessage()).put(ErrorTags.INVALID_INPUT_PATH.wire(), true).toJson();
            }
            if (!Files.isRegularFile(base))
            {
                return ToolResult.error("The base configuration does not exist: " + base) //$NON-NLS-1$
                    .put(ErrorTags.INPUT_MISSING.wire(), true).toJson();
            }
        }

        Path xmlDir;
        boolean keepXml = keepXmlPath != null && !keepXmlPath.isEmpty();
        try
        {
            xmlDir = keepXml ? Paths.get(keepXmlPath).toAbsolutePath().normalize()
                : Files.createTempDirectory("aiedt-import-xml-"); //$NON-NLS-1$
        }
        catch (InvalidPathException | IOException e)
        {
            return ToolResult.error("Cannot prepare the intermediate XML directory: " //$NON-NLS-1$
                + e.getMessage()).put(ErrorTags.OUTPUT_DIRECTORY_ERROR.wire(), true).toJson();
        }
        if (keepXml && !isEmptyOrAbsent(xmlDir))
        {
            return ToolResult.error("keepXmlPath must be an empty or absent directory: " + xmlDir //$NON-NLS-1$
                + ". Mixing this dump with what is already there would make the result " //$NON-NLS-1$
                + "impossible to import and impossible to tell apart from a dump that wrote " //$NON-NLS-1$
                + "nothing.").put(ErrorTags.OUTPUT_DIRECTORY_ERROR.wire(), true).toJson(); //$NON-NLS-1$
        }

        try
        {
            BmBinaryImportHelper.XmlResult staged =
                BmBinaryImportHelper.toXml(binary, platform, extensionName, base, xmlDir);
            if (!staged.ok)
            {
                ToolResult err = ToolResult.error(staged.error)
                    .put("operation", NAME) //$NON-NLS-1$
                    .put("binaryPath", binary.toString()) //$NON-NLS-1$
                    .put("stage", "staging"); //$NON-NLS-1$ //$NON-NLS-2$
                if (staged.failureKind != null)
                {
                    err.put(staged.failureKind, Boolean.TRUE);
                }
                addStagingFacts(err, staged);
                return err.toJson();
            }

            Map<String, String> importParams = new LinkedHashMap<>();
            importParams.put("importPath", xmlDir.toString()); //$NON-NLS-1$
            importParams.put("projectName", projectName); //$NON-NLS-1$
            String imported = new ConfigurationXmlImporter().execute(importParams);

            IProject created = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (created == null || !created.exists())
            {
                // The XML import already says exactly what went wrong and how to check it, so its
                // answer travels back unchanged rather than being replaced by a vaguer one of our
                // own. The dump behind it goes the way the caller asked: a temporary one is still
                // deleted, because a path in the system temp directory that nobody was told about
                // is litter, not evidence. Pass keepXmlPath to look at what was staged.
                return imported;
            }

            ToolResult ok = ToolResult.success()
                .put("operation", NAME) //$NON-NLS-1$
                .put("projectName", projectName) //$NON-NLS-1$
                .put("binaryPath", binary.toString()) //$NON-NLS-1$
                .put("kind", kind == BmBinaryImportHelper.BinaryKind.EXTENSION //$NON-NLS-1$
                    ? "extension" : "configuration"); //$NON-NLS-1$ //$NON-NLS-2$
            if (staged.extensionName != null)
            {
                ok.put("extensionName", staged.extensionName); //$NON-NLS-1$
            }
            if (keepXml)
            {
                ok.put("xmlPath", xmlDir.toString()); //$NON-NLS-1$
            }
            addStagingFacts(ok, staged);
            return ok.toJson();
        }
        finally
        {
            if (!keepXml)
            {
                deleteTree(xmlDir);
            }
        }
    }

    /**
     * Adds what the caller may have to act on about the staging infobase.
     *
     * @param result the reply being built
     * @param staged what staging produced
     */
    private static void addStagingFacts(ToolResult result, BmBinaryImportHelper.XmlResult staged)
    {
        if (staged.stagingInfobaseName != null && !staged.stagingRemoved)
        {
            // Only said when it is still there. A staging infobase that was cleaned up is an
            // implementation detail; one that survived is something to go and delete.
            result.put("stagingInfobaseLeftBehind", staged.stagingInfobaseName); //$NON-NLS-1$
        }
        if (staged.designerLog != null)
        {
            result.put("designerLog", staged.designerLog); //$NON-NLS-1$
        }
    }

    /**
     * Whether a path is an empty directory or is not there at all.
     *
     * @param dir the directory
     * @return <code>true</code> when nothing would be overwritten
     */
    private static boolean isEmptyOrAbsent(Path dir)
    {
        if (!Files.exists(dir))
        {
            return true;
        }
        if (!Files.isDirectory(dir))
        {
            return false;
        }
        try (java.util.stream.Stream<Path> entries = Files.list(dir))
        {
            return entries.findAny().isEmpty();
        }
        catch (IOException e)
        {
            return false;
        }
    }

    /**
     * Deletes a directory and everything under it, best effort.
     *
     * @param root the directory
     */
    private static void deleteTree(Path root)
    {
        if (root == null || !Files.exists(root))
        {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(root))
        {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try
                {
                    Files.deleteIfExists(path);
                }
                catch (IOException ignored)
                {
                    // Temp is temp; a file still held open is not worth failing a good import for.
                }
            });
        }
        catch (IOException e)
        {
            Activator.logWarning("intermediate XML at " + root + " was left behind: " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage());
        }
    }
}
