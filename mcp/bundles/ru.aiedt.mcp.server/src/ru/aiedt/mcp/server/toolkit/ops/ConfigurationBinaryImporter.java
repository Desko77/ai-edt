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

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.BmBinaryImportHelper;
import ru.aiedt.mcp.server.support.ErrorTags;
import ru.aiedt.mcp.server.support.PendingWorkRegistry;
import ru.aiedt.mcp.server.support.TimeoutArgs;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.wire.GsonHolder;
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

    /** How long the call waits inline before handing back a runKey. */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** Clamp on {@code timeoutSeconds}, matching the other Pending-backed tools. */
    private static final int MIN_TIMEOUT_SECONDS = 5;

    private static final int MAX_TIMEOUT_SECONDS = 120;

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
            .integerProperty("timeoutSeconds", //$NON-NLS-1$
                "How long to wait inline before returning Pending with a runKey. Default 30, " //$NON-NLS-1$
                    + "clamped to 5-120. The work continues either way.") //$NON-NLS-1$
            .stringProperty("runKey", //$NON-NLS-1$
                "Resume an import that came back Pending. Pass the runKey from that reply and " //$NON-NLS-1$
                    + "nothing else - this is the only way to collect the result. Repeating the " //$NON-NLS-1$
                    + "original parameters starts a fresh submission instead, and is refused " //$NON-NLS-1$
                    + "once the project exists.") //$NON-NLS-1$
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
        String resumeKey = JsonUtils.extractStringArgument(params, "runKey"); //$NON-NLS-1$
        if (resumeKey != null && !resumeKey.isEmpty())
        {
            return resume(resumeKey, params);
        }

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

        // Checked here, created later. Making the directory in front of the dispatch leaked one
        // per poll: a second call with the same parameters finds the run already going, drops its
        // own lambda - and the lambda was the only thing that would have deleted the directory it
        // had just made.
        boolean keepXml = keepXmlPath != null && !keepXmlPath.isEmpty();
        Path keepDir = null;
        if (keepXml)
        {
            try
            {
                keepDir = Paths.get(keepXmlPath).toAbsolutePath().normalize();
            }
            catch (InvalidPathException e)
            {
                return ToolResult.error("keepXmlPath is not a valid path: " + e.getMessage()) //$NON-NLS-1$
                    .put(ErrorTags.OUTPUT_DIRECTORY_ERROR.wire(), true).toJson();
            }
            if (!isEmptyOrAbsent(keepDir))
            {
                return ToolResult.error("keepXmlPath must be an empty or absent directory: " //$NON-NLS-1$
                    + keepDir + ". Mixing this dump with what is already there would make the " //$NON-NLS-1$
                    + "result impossible to import and impossible to tell apart from a dump " //$NON-NLS-1$
                    + "that wrote nothing.").put(ErrorTags.OUTPUT_DIRECTORY_ERROR.wire(), true) //$NON-NLS-1$
                    .toJson();
            }
        }

        // Everything above is cheap and stays in front of the dispatch: a bad call must never
        // start a Designer, and a Pending reply for a call that was doomed anyway would hide the
        // reason behind a runKey. Everything below is the part that takes real time - measured at
        // 50 s for a 157 MB configuration, which is past any short client timeout - so it runs on
        // a worker and the call comes back with a key.
        final Path finalBinary = binary;
        final Path finalBase = base;
        final Path finalKeepDir = keepDir;
        final String finalProjectName = projectName;
        final String finalPlatform = platform;
        final String finalExtensionName = extensionName;
        final BmBinaryImportHelper.BinaryKind finalKind = kind;

        // Every option that changes what the run DOES is part of its identity. Keyed on the
        // project and the binary alone, a second call naming a different base configuration or a
        // different extension name would silently ride along on the first run and be told it
        // succeeded - having imported something else.
        String runKey = PendingWorkRegistry.computeRunKey(NAME, finalProjectName,
            finalBinary.toString(), finalPlatform == null ? "" : finalPlatform, //$NON-NLS-1$
            finalExtensionName == null ? "" : finalExtensionName, //$NON-NLS-1$
            finalBase == null ? "" : finalBase.toString(), //$NON-NLS-1$
            finalKeepDir == null ? "" : finalKeepDir.toString()); //$NON-NLS-1$
        long timeoutMs = TimeoutArgs.readSeconds(params, DEFAULT_TIMEOUT_SECONDS,
            MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS) * 1000L;

        PendingWorkRegistry registry = PendingWorkRegistry.IMPORT_BINARY;
        registry.pruneExpired();
        PendingWorkRegistry.PendingEntry prior = registry.get(runKey);
        if (prior != null && prior.isDone())
        {
            // A fresh submit of a finished-but-never-collected run must do the work again: the
            // workspace has moved on since, and replaying "the project was created" for a project
            // somebody has deleted in the meantime would be a lie with a timestamp on it.
            registry.remove(runKey);
        }
        PendingWorkRegistry.PendingEntry entry = registry.getOrStart(runKey,
            () -> stageAndImport(finalBinary, finalKind, finalProjectName, finalPlatform,
                finalExtensionName, finalBase, finalKeepDir));

        String done = entry.await(timeoutMs);
        if (done != null)
        {
            registry.remove(runKey);
            return done;
        }
        return ToolResult.success()
            .put("operation", NAME) //$NON-NLS-1$
            .put("status", "Pending") //$NON-NLS-1$ //$NON-NLS-2$
            .put("runKey", runKey) //$NON-NLS-1$
            .put("projectName", finalProjectName) //$NON-NLS-1$
            .put("binaryPath", finalBinary.toString()) //$NON-NLS-1$
            .put("elapsedMs", entry.elapsedMs()) //$NON-NLS-1$
            .put("waitedMs", timeoutMs) //$NON-NLS-1$
            .put("hint", "The import is still staging. Come back for it with runKey=\"" //$NON-NLS-1$ //$NON-NLS-2$
                + runKey + "\" - through the facade that is config_io " //$NON-NLS-1$
                + "operation=import_configuration_from_binary runKey=\"" + runKey + "\". Poll " //$NON-NLS-1$ //$NON-NLS-2$
                + "by the key, not by repeating the parameters: a repeat is a fresh submission " //$NON-NLS-1$
                + "and will be refused once the project exists. The project appears when the " //$NON-NLS-1$
                + "import finishes, whether or not anyone is still waiting.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Resumes an import that came back Pending.
     *
     * @param runKey the key from that reply
     * @param params the call's parameters, read for the wait
     * @return the finished result, or another Pending reply
     */
    private String resume(String runKey, Map<String, String> params)
    {
        PendingWorkRegistry registry = PendingWorkRegistry.IMPORT_BINARY;
        registry.pruneExpired();
        PendingWorkRegistry.PendingEntry entry = registry.get(runKey);
        if (entry == null)
        {
            return ToolResult.error("runKey not found - the import either finished and its " //$NON-NLS-1$
                + "result was already collected, or it was abandoned long enough to be evicted. " //$NON-NLS-1$
                + "Check whether the project is in the workspace before starting over: the work " //$NON-NLS-1$
                + "continues even when nobody waits for it.") //$NON-NLS-1$
                .put("operation", NAME) //$NON-NLS-1$
                .put("runKey", runKey) //$NON-NLS-1$
                .toJson();
        }
        long timeoutMs = TimeoutArgs.readSeconds(params, DEFAULT_TIMEOUT_SECONDS,
            MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS) * 1000L;
        String done = entry.await(timeoutMs);
        if (done != null)
        {
            registry.remove(runKey);
            return done;
        }
        return ToolResult.success()
            .put("operation", NAME) //$NON-NLS-1$
            .put("status", "Pending") //$NON-NLS-1$ //$NON-NLS-2$
            .put("runKey", runKey) //$NON-NLS-1$
            .put("elapsedMs", entry.elapsedMs()) //$NON-NLS-1$
            .put("waitedMs", timeoutMs) //$NON-NLS-1$
            .toJson();
    }

    /**
     * The part that takes time: stage the binary into an infobase, dump its XML, import that.
     *
     * @param binary the .cf or .cfe
     * @param kind which of the two it is
     * @param projectName the project to create
     * @param platform the platform version for the staging infobase, or <code>null</code>
     * @param extensionName the name to load an extension under, or <code>null</code>
     * @param base a .cf to seed the staging infobase with, or <code>null</code>
     * @param keepDir the caller's directory for the intermediate XML, or <code>null</code> for
     *            a temporary one that is deleted afterwards
     * @return the reply JSON
     */
    private String stageAndImport(Path binary, BmBinaryImportHelper.BinaryKind kind,
        String projectName, String platform, String extensionName, Path base, Path keepDir)
    {
        boolean keepXml = keepDir != null;
        Path xmlDir;
        try
        {
            xmlDir = keepXml ? keepDir : Files.createTempDirectory("aiedt-import-xml-"); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            return ToolResult.error("Cannot prepare the intermediate XML directory: " //$NON-NLS-1$
                + e.getMessage()).put(ErrorTags.OUTPUT_DIRECTORY_ERROR.wire(), true).toJson();
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

            // Asked of the import itself, not of the workspace. The XML import sets the project up
            // before it brings anything across, so the folder exists whether or not the import
            // then worked - reading success off "a project is there" would report an empty shell
            // as a finished job, which is the one answer worse than a failure.
            if (!succeeded(imported, projectName))
            {
                // Its answer already says exactly what went wrong and how to check it, so it
                // travels back unchanged rather than being replaced by a vaguer one of our own.
                // The dump behind it goes the way the caller asked: a temporary one is still
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
     * Whether the XML import actually imported something.
     * <p>
     * The import's own verdict is the answer, because it is the only party that knows whether the
     * sources arrived - it checks that itself and refuses a project it created but could not fill.
     * When its reply cannot be read, the same evidence it uses is checked here rather than assumed:
     * a project with sources under {@code src}.
     * </p>
     *
     * @param reply what the XML import answered
     * @param projectName the project it was told to create
     * @return <code>true</code> when there is a project with sources in it
     */
    private static boolean succeeded(String reply, String projectName)
    {
        try
        {
            Map<?, ?> parsed = GsonHolder.fromJson(reply, Map.class);
            Object success = parsed == null ? null : parsed.get("success"); //$NON-NLS-1$
            if (success instanceof Boolean)
            {
                return (Boolean)success;
            }
        }
        catch (RuntimeException e)
        {
            Activator.logWarning("the XML import's answer could not be read, falling back to " //$NON-NLS-1$
                + "inspecting the project: " + e.getMessage()); //$NON-NLS-1$
        }
        IProject created = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (created == null || !created.exists())
        {
            return false;
        }
        try
        {
            IFolder sources = created.getFolder("src"); //$NON-NLS-1$
            return sources.exists() && sources.members().length > 0;
        }
        catch (CoreException e)
        {
            return false;
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
        if (staged.stagingCreated && !staged.stagingRemoved)
        {
            // Said only about an infobase that exists. One that was cleaned up is an implementation
            // detail, and one that was never created is nothing at all - claiming either sends the
            // caller hunting for something that is not there, which is how a clear failure turns
            // into a confusing one.
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
