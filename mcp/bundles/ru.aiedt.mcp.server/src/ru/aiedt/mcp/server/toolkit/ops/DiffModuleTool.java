/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.YamlFrontMatter;
import ru.aiedt.mcp.server.support.GitDiffUtils;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;

/**
 * Tool for comparing BSL module with its previous VCS version.
 * Shows what changed: added/modified/removed methods and line-level diff.
 * Modes: summary (method-level overview), unified (git diff format), methods (per-method diff).
 * Critical for code review and change analysis.
 */
public class DiffModuleTool implements IMcpTool
{
    public static final String NAME = "diff_module"; //$NON-NLS-1$

    private static final String MODE_SUMMARY = "summary"; //$NON-NLS-1$
    private static final String MODE_UNIFIED = "unified"; //$NON-NLS-1$
    private static final String MODE_METHODS = "methods"; //$NON-NLS-1$

    /** LCS diff threshold - above this, use simple line-by-line comparison */
    private static final int LCS_THRESHOLD = 3000;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Compare BSL module with previous VCS version (git). " + //$NON-NLS-1$
            "Shows what changed: added/modified/removed methods and line-by-line diff. " + //$NON-NLS-1$
            "Modes: summary (method-level overview, default), " + //$NON-NLS-1$
            "unified (full diff in unified/git diff format), " + //$NON-NLS-1$
            "methods (individual diff per modified method). " + //$NON-NLS-1$
            "Critical for code review and change analysis. " + //$NON-NLS-1$
            "Specify modulePath or objectName + moduleType."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "Name of the EDT project to work in", true) //$NON-NLS-1$
            .stringProperty("modulePath", //$NON-NLS-1$
                "Path to module from src/ folder, e.g. " + //$NON-NLS-1$
                "'Documents/MyDoc/ObjectModule.bsl' or " + //$NON-NLS-1$
                "'CommonModules/MyModule/Module.bsl'; also accepts a module FQN like " + //$NON-NLS-1$
                "'CommonModule.MyModule' / 'Catalog.Products.ManagerModule'. " + //$NON-NLS-1$
                "Alternatively, supply objectName + moduleType.") //$NON-NLS-1$
            .stringProperty("objectName", //$NON-NLS-1$
                "The complete object name, e.g. 'Document.MyDoc', " + //$NON-NLS-1$
                "'DataProcessor.MyProcessor'. " + //$NON-NLS-1$
                "Russian names are also accepted (e.g. 'Документ.МойДок'). " + //$NON-NLS-1$
                "Alternative to modulePath.") //$NON-NLS-1$
            .stringProperty("moduleType", //$NON-NLS-1$
                "Type of module (paired with objectName): ObjectModule (default), " + //$NON-NLS-1$
                "ManagerModule, FormModule, CommandModule, RecordSetModule.") //$NON-NLS-1$
            .stringProperty("mode", //$NON-NLS-1$
                "Diff mode: 'summary' (method-level overview, default), " + //$NON-NLS-1$
                "'unified' (full diff in unified/git diff format), " + //$NON-NLS-1$
                "'methods' (individual diff per modified method).") //$NON-NLS-1$
            .integerProperty("contextLines", //$NON-NLS-1$
                "Number of context lines around changes for unified mode (default: 3)") //$NON-NLS-1$
            .stringProperty("formName", //$NON-NLS-1$
                "Name of the form, required when moduleType=FormModule " + //$NON-NLS-1$
                "(e.g. 'ItemForm').") //$NON-NLS-1$
            .stringProperty("commandName", //$NON-NLS-1$
                "Name of the command, required when moduleType=CommandModule " + //$NON-NLS-1$
                "(e.g. 'FillByTemplate').") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        if (modulePath != null && !modulePath.isEmpty())
        {
            String safeName = modulePath.replace("/", "-").replace("\\", "-").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            return "diff-" + safeName + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "diff-module.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        // 1. Extract parameters
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        String objectName = JsonUtils.extractStringArgument(params, "objectName"); //$NON-NLS-1$
        String moduleType = JsonUtils.extractStringArgument(params, "moduleType"); //$NON-NLS-1$
        String mode = JsonUtils.extractStringArgument(params, "mode"); //$NON-NLS-1$
        String formName = JsonUtils.extractStringArgument(params, "formName"); //$NON-NLS-1$
        String commandName = JsonUtils.extractStringArgument(params, "commandName"); //$NON-NLS-1$
        int contextLines = JsonUtils.extractIntArgument(params, "contextLines", 3); //$NON-NLS-1$

        // 2. Reject calls that are missing what they need
        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName is required"; //$NON-NLS-1$
        }

        // Default mode
        if (mode == null || mode.isEmpty())
        {
            mode = MODE_SUMMARY;
        }

        // Validate mode
        if (!MODE_SUMMARY.equals(mode) && !MODE_UNIFIED.equals(mode) && !MODE_METHODS.equals(mode))
        {
            return "Error: invalid mode '" + mode + "'. " + //$NON-NLS-1$ //$NON-NLS-2$
                "Allowed: summary, unified, methods"; //$NON-NLS-1$
        }

        // 3. Resolve modulePath
        if (modulePath == null || modulePath.isEmpty())
        {
            if (objectName == null || objectName.isEmpty())
            {
                return "Error: " + TextSuggest.missingParam("either modulePath or objectName", //$NON-NLS-1$ //$NON-NLS-2$
                    "modulePath='CommonModules/MyModule/Module.bsl' or objectName='Document.MyDoc'"); //$NON-NLS-1$
            }
            String resolved = resolveModulePath(objectName, moduleType, formName, commandName);
            if (resolved.startsWith("Error:")) //$NON-NLS-1$
            {
                return resolved;
            }
            modulePath = resolved;
        }

        // A caller who already passed a path gets it judged here, before the workspace is touched:
        // a traversal attempt has to be refused whether or not the named project happens to exist,
        // and a project error would otherwise be the only thing such a call ever heard back.
        if (modulePath.indexOf('/') >= 0 || modulePath.indexOf('\\') >= 0)
        {
            String unusable = rejectUnusableModulePath(modulePath);
            if (unusable != null)
            {
                return unusable;
            }
        }

        // 4. Validate project (needed for FQN resolution below)
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: " + ProjectResolver.describeNotFound(projectName); //$NON-NLS-1$
        }

        // Resolve modulePath: accept both a path from src/ and a module FQN (e.g. CommonModule.MyModule).
        // Done before the .bsl check below so an FQN like 'Catalog.Products.ManagerModule' is accepted.
        BslModuleAccess.ModulePathResolution resolution = BslModuleAccess.resolveModulePath(project, modulePath);
        if (!resolution.isResolved())
        {
            return resolution.getHint();
        }
        modulePath = resolution.getPath();

        String unusable = rejectUnusableModulePath(modulePath);
        if (unusable != null)
        {
            return unusable;
        }

        // 5. Get file
        IFile file = project.getFile(new Path("src").append(modulePath)); //$NON-NLS-1$
        if (!file.exists())
        {
            return "Error: File not found: src/" + modulePath; //$NON-NLS-1$
        }

        try
        {
            // 6. Read current file
            List<String> currentLines = BslModuleAccess.readFileLines(file);
            String currentContent = String.join("\n", currentLines); //$NON-NLS-1$

            // 7. Get previous version
            String previousContent = GitDiffUtils.getPreviousVersion(file, project);

            // 8. Handle no previous version (new file)
            if (previousContent == null)
            {
                return buildNewFileResponse(projectName, modulePath, currentLines, currentContent);
            }

            // 9. Handle identical content
            if (currentContent.equals(previousContent))
            {
                YamlFrontMatter fm = YamlFrontMatter.create()
                    .put("tool", NAME) //$NON-NLS-1$
                    .put("projectName", projectName) //$NON-NLS-1$
                    .put("modulePath", modulePath) //$NON-NLS-1$
                    .put("mode", mode) //$NON-NLS-1$
                    .put("hasChanges", false); //$NON-NLS-1$

                return fm.wrapContent(
                    "## Module Diff: " + modulePath + "\n\n" + //$NON-NLS-1$ //$NON-NLS-2$
                    "No changes detected. Module is identical to VCS version.\n"); //$NON-NLS-1$
            }

            // 10. Compute diff based on mode
            String[] previousLines = previousContent.split("\n", -1); //$NON-NLS-1$

            switch (mode)
            {
                case MODE_SUMMARY:
                    return buildSummaryDiff(projectName, modulePath, previousContent,
                        currentContent, previousLines, currentLines);

                case MODE_UNIFIED:
                    return buildUnifiedDiff(projectName, modulePath, previousLines,
                        currentLines.toArray(new String[0]), contextLines);

                case MODE_METHODS:
                    return buildMethodsDiff(projectName, modulePath, previousContent,
                        currentContent, previousLines, currentLines);

                default:
                    return buildSummaryDiff(projectName, modulePath, previousContent,
                        currentContent, previousLines, currentLines);
            }
        }
        catch (Exception e)
        {
            Activator.logError("Diff failed for module: " + modulePath, e); //$NON-NLS-1$
            return "Error: Diff failed: " + e.getMessage(); //$NON-NLS-1$
        }
    }

    // -- = --
    // Module path resolution (same logic as ModuleSourceWriter)
    // -- = --

    /**
     * Refuses a module path this tool cannot work with.
     * <p>
     * Applied twice: to a path the caller supplied, before the workspace is consulted, and again to
     * a path resolved from an FQN.
     * </p>
     *
     * @param path the src-relative module path
     * @return the refusal to hand back, or <code>null</code> when the path is usable
     */
    private static String rejectUnusableModulePath(String path)
    {
        if (path.contains("..")) //$NON-NLS-1$
        {
            return "Error: modulePath must not contain '..'"; //$NON-NLS-1$
        }
        if (!path.endsWith(".bsl")) //$NON-NLS-1$
        {
            return "Error: only .bsl module files can be compared"; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Turns an object plus a module kind into the path of that module under src/.
     */
    private String resolveModulePath(String objectName, String moduleType,
        String formName, String commandName)
    {
        int dotIndex = objectName.indexOf('.');
        if (dotIndex <= 0 || dotIndex >= objectName.length() - 1)
        {
            return "Error: objectName must follow the format 'Type.Name' " + //$NON-NLS-1$
                "for example Document.Invoice or CommonModule.Billing"; //$NON-NLS-1$
        }

        String typePart = objectName.substring(0, dotIndex);
        String namePart = objectName.substring(dotIndex + 1);

        String englishType = MetadataTypeCatalog.toEnglishSingular(typePart);
        if (englishType == null)
        {
            return "Error: unknown metadata type: " + typePart; //$NON-NLS-1$
        }

        String dirName = MetadataTypeCatalog.getDirectoryName(typePart);
        if (dirName == null)
        {
            return "Error: metadata type '" + typePart + "' carries no src folder"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        if (moduleType == null || moduleType.isEmpty())
        {
            if ("CommonModule".equals(englishType) //$NON-NLS-1$
                || "CommonForm".equals(englishType) //$NON-NLS-1$
                || "WebService".equals(englishType) //$NON-NLS-1$
                || "HTTPService".equals(englishType)) //$NON-NLS-1$
            {
                moduleType = "Module"; //$NON-NLS-1$
            }
            else if ("CommonCommand".equals(englishType)) //$NON-NLS-1$
            {
                moduleType = "CommandModule"; //$NON-NLS-1$
            }
            else
            {
                moduleType = "ObjectModule"; //$NON-NLS-1$
            }
        }

        switch (moduleType)
        {
            case "Module": //$NON-NLS-1$
                return dirName + "/" + namePart + "/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$

            case "ObjectModule": //$NON-NLS-1$
                return dirName + "/" + namePart + "/ObjectModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$

            case "ManagerModule": //$NON-NLS-1$
                return dirName + "/" + namePart + "/ManagerModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$

            case "RecordSetModule": //$NON-NLS-1$
                return dirName + "/" + namePart + "/RecordSetModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$

            case "FormModule": //$NON-NLS-1$
                if ("CommonForm".equals(englishType)) //$NON-NLS-1$
                {
                    return dirName + "/" + namePart + "/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (formName == null || formName.isEmpty())
                {
                    return "Error: formName must be provided when moduleType=FormModule"; //$NON-NLS-1$
                }
                return dirName + "/" + namePart + "/Forms/" + formName + "/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            case "CommandModule": //$NON-NLS-1$
                if ("CommonCommand".equals(englishType)) //$NON-NLS-1$
                {
                    return dirName + "/" + namePart + "/CommandModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (commandName == null || commandName.isEmpty())
                {
                    return "Error: commandName must be provided when moduleType=CommandModule"; //$NON-NLS-1$
                }
                return dirName + "/" + namePart + "/Commands/" + commandName + "/CommandModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            default:
                return "Error: unknown moduleType: " + moduleType + //$NON-NLS-1$
                    ". Allowed: ObjectModule, ManagerModule, FormModule, " + //$NON-NLS-1$
                    "CommandModule, RecordSetModule, Module"; //$NON-NLS-1$
        }
    }

    // -- = --
    // New file response
    // -- = --

    private String buildNewFileResponse(String projectName, String modulePath,
        List<String> currentLines, String currentContent)
    {
        List<MethodInfo> methods = parseMethods(currentContent);

        YamlFrontMatter fm = YamlFrontMatter.create()
            .put("tool", NAME) //$NON-NLS-1$
            .put("projectName", projectName) //$NON-NLS-1$
            .put("modulePath", modulePath) //$NON-NLS-1$
            .put("isNewFile", true) //$NON-NLS-1$
            .put("currentLines", currentLines.size()) //$NON-NLS-1$
            .put("addedMethodCount", methods.size()); //$NON-NLS-1$

        StringBuilder body = new StringBuilder();
        body.append("## Module Diff: ").append(modulePath).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        body.append("File is new (no previous version in VCS). All content is new.\n\n"); //$NON-NLS-1$

        if (!methods.isEmpty())
        {
            body.append("### Methods (").append(methods.size()).append(")\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            body.append("| Method | Type | Line | Export |\n"); //$NON-NLS-1$
            body.append("|--------|------|------|--------|\n"); //$NON-NLS-1$
            for (MethodInfo mi : methods)
            {
                body.append("| ").append(mi.name) //$NON-NLS-1$
                    .append(" | ").append(mi.type) //$NON-NLS-1$
                    .append(" | ").append(mi.startLine) //$NON-NLS-1$
                    .append(" | ").append(mi.isExport ? "Yes" : "No") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    .append(" |\n"); //$NON-NLS-1$
            }
        }

        return fm.wrapContent(body.toString());
    }

    // -- = --
    // Summary diff
    // -- = --

    private String buildSummaryDiff(String projectName, String modulePath,
        String previousContent, String currentContent,
        String[] previousLines, List<String> currentLines)
    {
        List<MethodInfo> oldMethods = parseMethods(previousContent);
        List<MethodInfo> newMethods = parseMethods(currentContent);

        // Added methods: in new but not in old
        List<MethodInfo> addedMethods = new ArrayList<>();
        for (MethodInfo nm : newMethods)
        {
            if (!containsMethod(oldMethods, nm.name))
            {
                addedMethods.add(nm);
            }
        }

        // Removed methods: in old but not in new
        List<MethodInfo> removedMethods = new ArrayList<>();
        for (MethodInfo om : oldMethods)
        {
            if (!containsMethod(newMethods, om.name))
            {
                removedMethods.add(om);
            }
        }

        // Modified methods: in both but body differs
        List<MethodModification> modifiedMethods = new ArrayList<>();
        for (MethodInfo nm : newMethods)
        {
            MethodInfo om = findMethod(oldMethods, nm.name);
            if (om != null && !nm.body.equals(om.body))
            {
                MethodModification mod = new MethodModification();
                mod.name = nm.name;
                mod.type = nm.type;
                mod.currentLine = nm.startLine;
                mod.previousLineCount = om.endLine - om.startLine + 1;
                mod.currentLineCount = nm.endLine - nm.startLine + 1;
                mod.lineDelta = mod.currentLineCount - mod.previousLineCount;
                modifiedMethods.add(mod);
            }
        }

        int unchangedCount = newMethods.size() - addedMethods.size() - modifiedMethods.size();
        boolean hasChanges = !addedMethods.isEmpty() || !removedMethods.isEmpty()
            || !modifiedMethods.isEmpty() || !previousContent.equals(currentContent);

        YamlFrontMatter fm = YamlFrontMatter.create()
            .put("tool", NAME) //$NON-NLS-1$
            .put("projectName", projectName) //$NON-NLS-1$
            .put("modulePath", modulePath) //$NON-NLS-1$
            .put("mode", MODE_SUMMARY) //$NON-NLS-1$
            .put("hasChanges", hasChanges) //$NON-NLS-1$
            .put("previousLines", previousLines.length) //$NON-NLS-1$
            .put("currentLines", currentLines.size()) //$NON-NLS-1$
            .put("addedMethodCount", addedMethods.size()) //$NON-NLS-1$
            .put("removedMethodCount", removedMethods.size()) //$NON-NLS-1$
            .put("modifiedMethodCount", modifiedMethods.size()) //$NON-NLS-1$
            .put("unchangedMethodCount", unchangedCount); //$NON-NLS-1$

        StringBuilder body = new StringBuilder();
        body.append("## Module Diff: ").append(modulePath).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        // Added methods
        body.append("### Added Methods (").append(addedMethods.size()).append(")\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (!addedMethods.isEmpty())
        {
            body.append("| Method | Type | Line | Lines |\n"); //$NON-NLS-1$
            body.append("|--------|------|------|-------|\n"); //$NON-NLS-1$
            for (MethodInfo mi : addedMethods)
            {
                body.append("| ").append(mi.name) //$NON-NLS-1$
                    .append(" | ").append(mi.type) //$NON-NLS-1$
                    .append(" | ").append(mi.startLine) //$NON-NLS-1$
                    .append(" | ").append(mi.endLine - mi.startLine + 1) //$NON-NLS-1$
                    .append(" |\n"); //$NON-NLS-1$
            }
            body.append("\n"); //$NON-NLS-1$
        }

        // Modified methods
        body.append("### Modified Methods (").append(modifiedMethods.size()).append(")\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (!modifiedMethods.isEmpty())
        {
            body.append("| Method | Type | Line | Previous Lines | Current Lines | Delta |\n"); //$NON-NLS-1$
            body.append("|--------|------|------|----------------|---------------|-------|\n"); //$NON-NLS-1$
            for (MethodModification mm : modifiedMethods)
            {
                String delta = mm.lineDelta >= 0
                    ? "+" + mm.lineDelta //$NON-NLS-1$
                    : String.valueOf(mm.lineDelta);
                body.append("| ").append(mm.name) //$NON-NLS-1$
                    .append(" | ").append(mm.type) //$NON-NLS-1$
                    .append(" | ").append(mm.currentLine) //$NON-NLS-1$
                    .append(" | ").append(mm.previousLineCount) //$NON-NLS-1$
                    .append(" | ").append(mm.currentLineCount) //$NON-NLS-1$
                    .append(" | ").append(delta) //$NON-NLS-1$
                    .append(" |\n"); //$NON-NLS-1$
            }
            body.append("\n"); //$NON-NLS-1$
        }

        // Removed methods
        body.append("### Removed Methods (").append(removedMethods.size()).append(")\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (!removedMethods.isEmpty())
        {
            body.append("| Method | Type | Lines |\n"); //$NON-NLS-1$
            body.append("|--------|------|-------|\n"); //$NON-NLS-1$
            for (MethodInfo mi : removedMethods)
            {
                body.append("| ").append(mi.name) //$NON-NLS-1$
                    .append(" | ").append(mi.type) //$NON-NLS-1$
                    .append(" | ").append(mi.endLine - mi.startLine + 1) //$NON-NLS-1$
                    .append(" |\n"); //$NON-NLS-1$
            }
            body.append("\n"); //$NON-NLS-1$
        }

        body.append("Unchanged methods: ").append(unchangedCount).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$

        // Check for changes outside methods
        if (addedMethods.isEmpty() && removedMethods.isEmpty() && modifiedMethods.isEmpty()
            && hasChanges)
        {
            body.append("\nChanges detected outside of methods ") //$NON-NLS-1$
                .append("(comments, regions, variable declarations, etc.)\n"); //$NON-NLS-1$
        }

        return fm.wrapContent(body.toString());
    }

    // -- = --
    // Unified diff
    // -- = --

    private String buildUnifiedDiff(String projectName, String modulePath,
        String[] oldLines, String[] newLines, int contextLines)
    {
        List<DiffLine> diffLines = computeDiff(oldLines, newLines);

        // Collect hunks
        List<List<DiffLine>> hunks = new ArrayList<>();
        List<DiffLine> currentHunk = new ArrayList<>();
        int lastChangeIdx = -contextLines - 1;

        for (int i = 0; i < diffLines.size(); i++)
        {
            DiffLine dl = diffLines.get(i);
            if (dl.type != DiffLine.Type.EQUAL)
            {
                // Start new hunk if gap > 2*contextLines
                if (i - lastChangeIdx > contextLines * 2 + 1 && !currentHunk.isEmpty())
                {
                    hunks.add(currentHunk);
                    currentHunk = new ArrayList<>();
                    // Add leading context for new hunk
                    int contextStart = Math.max(0, i - contextLines);
                    for (int c = contextStart; c < i; c++)
                    {
                        currentHunk.add(diffLines.get(c));
                    }
                }
                currentHunk.add(dl);
                lastChangeIdx = i;
            }
            else if (i - lastChangeIdx <= contextLines)
            {
                // Trailing context after a change
                currentHunk.add(dl);
            }
            else if (currentHunk.isEmpty())
            {
                // Check if there's a change coming within contextLines
                boolean upcoming = false;
                for (int peek = i + 1; peek < Math.min(i + contextLines + 1, diffLines.size()); peek++)
                {
                    if (diffLines.get(peek).type != DiffLine.Type.EQUAL)
                    {
                        upcoming = true;
                        break;
                    }
                }
                if (upcoming)
                {
                    currentHunk.add(dl);
                }
            }
        }
        if (!currentHunk.isEmpty())
        {
            hunks.add(currentHunk);
        }

        // Count total changes
        int totalAdded = 0;
        int totalRemoved = 0;
        for (DiffLine dl : diffLines)
        {
            if (dl.type == DiffLine.Type.ADDED)
            {
                totalAdded++;
            }
            else if (dl.type == DiffLine.Type.REMOVED)
            {
                totalRemoved++;
            }
        }

        boolean hasChanges = totalAdded > 0 || totalRemoved > 0;

        YamlFrontMatter fm = YamlFrontMatter.create()
            .put("tool", NAME) //$NON-NLS-1$
            .put("projectName", projectName) //$NON-NLS-1$
            .put("modulePath", modulePath) //$NON-NLS-1$
            .put("mode", MODE_UNIFIED) //$NON-NLS-1$
            .put("hasChanges", hasChanges) //$NON-NLS-1$
            .put("previousLines", oldLines.length) //$NON-NLS-1$
            .put("currentLines", newLines.length) //$NON-NLS-1$
            .put("addedLines", totalAdded) //$NON-NLS-1$
            .put("removedLines", totalRemoved) //$NON-NLS-1$
            .put("hunkCount", hunks.size()); //$NON-NLS-1$

        StringBuilder body = new StringBuilder();
        body.append("## Module Diff: ").append(modulePath).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        for (int h = 0; h < hunks.size(); h++)
        {
            List<DiffLine> hunk = hunks.get(h);
            // Determine line ranges
            int oldStart = Integer.MAX_VALUE;
            int oldEnd = 0;
            int newStart = Integer.MAX_VALUE;
            int newEnd = 0;
            for (DiffLine dl : hunk)
            {
                if (dl.oldLineNum > 0)
                {
                    oldStart = Math.min(oldStart, dl.oldLineNum);
                    oldEnd = Math.max(oldEnd, dl.oldLineNum);
                }
                if (dl.newLineNum > 0)
                {
                    newStart = Math.min(newStart, dl.newLineNum);
                    newEnd = Math.max(newEnd, dl.newLineNum);
                }
            }

            body.append("### Hunk ").append(h + 1); //$NON-NLS-1$
            if (oldStart < Integer.MAX_VALUE && newStart < Integer.MAX_VALUE)
            {
                body.append(" (old ").append(oldStart).append("-").append(oldEnd) //$NON-NLS-1$ //$NON-NLS-2$
                    .append(", new ").append(newStart).append("-").append(newEnd).append(")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            body.append("\n\n```diff\n"); //$NON-NLS-1$

            for (DiffLine dl : hunk)
            {
                switch (dl.type)
                {
                    case EQUAL:
                        body.append(" ").append(dl.text).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                        break;
                    case REMOVED:
                        body.append("-").append(dl.text).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                        break;
                    case ADDED:
                        body.append("+").append(dl.text).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                        break;
                }
            }
            body.append("```\n\n"); //$NON-NLS-1$
        }

        return fm.wrapContent(body.toString());
    }

    // -- = --
    // Methods diff
    // -- = --

    private String buildMethodsDiff(String projectName, String modulePath,
        String previousContent, String currentContent,
        String[] previousLines, List<String> currentLines)
    {
        List<MethodInfo> oldMethods = parseMethods(previousContent);
        List<MethodInfo> newMethods = parseMethods(currentContent);

        List<MethodDiff> diffs = new ArrayList<>();

        // Modified methods
        for (MethodInfo nm : newMethods)
        {
            MethodInfo om = findMethod(oldMethods, nm.name);
            if (om != null && !nm.body.equals(om.body))
            {
                String[] oldBody = om.body.split("\n", -1); //$NON-NLS-1$
                String[] newBody = nm.body.split("\n", -1); //$NON-NLS-1$
                List<DiffLine> bodyDiff = computeDiff(oldBody, newBody);

                MethodDiff md = new MethodDiff();
                md.name = nm.name;
                md.type = nm.type;
                md.status = "modified"; //$NON-NLS-1$
                md.currentLine = nm.startLine;
                md.diffLines = bodyDiff;
                diffs.add(md);
            }
        }

        // Added methods
        for (MethodInfo nm : newMethods)
        {
            if (!containsMethod(oldMethods, nm.name))
            {
                MethodDiff md = new MethodDiff();
                md.name = nm.name;
                md.type = nm.type;
                md.status = "added"; //$NON-NLS-1$
                md.currentLine = nm.startLine;
                md.lineCount = nm.endLine - nm.startLine + 1;
                md.isExport = nm.isExport;
                md.body = nm.body;
                diffs.add(md);
            }
        }

        // Removed methods
        for (MethodInfo om : oldMethods)
        {
            if (!containsMethod(newMethods, om.name))
            {
                MethodDiff md = new MethodDiff();
                md.name = om.name;
                md.type = om.type;
                md.status = "removed"; //$NON-NLS-1$
                md.lineCount = om.endLine - om.startLine + 1;
                md.body = om.body;
                diffs.add(md);
            }
        }

        YamlFrontMatter fm = YamlFrontMatter.create()
            .put("tool", NAME) //$NON-NLS-1$
            .put("projectName", projectName) //$NON-NLS-1$
            .put("modulePath", modulePath) //$NON-NLS-1$
            .put("mode", MODE_METHODS) //$NON-NLS-1$
            .put("hasChanges", !diffs.isEmpty()) //$NON-NLS-1$
            .put("totalChangedMethods", diffs.size()); //$NON-NLS-1$

        StringBuilder body = new StringBuilder();
        body.append("## Module Diff: ").append(modulePath).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        if (diffs.isEmpty())
        {
            body.append("No method-level changes detected.\n"); //$NON-NLS-1$
        }

        for (MethodDiff md : diffs)
        {
            body.append("### ").append(md.type).append(" ").append(md.name) //$NON-NLS-1$ //$NON-NLS-2$
                .append(" (").append(md.status).append(")\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

            if ("modified".equals(md.status) && md.diffLines != null) //$NON-NLS-1$
            {
                int added = 0;
                int removed = 0;
                body.append("```diff\n"); //$NON-NLS-1$
                for (DiffLine dl : md.diffLines)
                {
                    switch (dl.type)
                    {
                        case EQUAL:
                            body.append(" ").append(dl.text).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                            break;
                        case REMOVED:
                            body.append("-").append(dl.text).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                            removed++;
                            break;
                        case ADDED:
                            body.append("+").append(dl.text).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                            added++;
                            break;
                    }
                }
                body.append("```\n\n"); //$NON-NLS-1$
                body.append("Added lines: ").append(added) //$NON-NLS-1$
                    .append(", Removed lines: ").append(removed).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else if ("added".equals(md.status)) //$NON-NLS-1$
            {
                body.append("Line: ").append(md.currentLine) //$NON-NLS-1$
                    .append(", Lines: ").append(md.lineCount) //$NON-NLS-1$
                    .append(", Export: ").append(md.isExport ? "Yes" : "No") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    .append("\n\n"); //$NON-NLS-1$
                if (md.body != null)
                {
                    body.append("```bsl\n").append(md.body).append("```\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            else if ("removed".equals(md.status)) //$NON-NLS-1$
            {
                body.append("Lines: ").append(md.lineCount).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
                if (md.body != null)
                {
                    body.append("```bsl\n").append(md.body).append("```\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }

        return fm.wrapContent(body.toString());
    }

    // -- = --
    // Method parsing
    // -- = --

    /**
     * Parses methods from BSL source using regex patterns from BslModuleAccess.
     */
    private List<MethodInfo> parseMethods(String source)
    {
        List<MethodInfo> methods = new ArrayList<>();
        String[] lines = source.split("\n", -1); //$NON-NLS-1$

        MethodInfo current = null;
        StringBuilder bodyBuilder = new StringBuilder();

        for (int i = 0; i < lines.length; i++)
        {
            String line = lines[i];
            Matcher startMatcher = BslModuleAccess.METHOD_START_PATTERN.matcher(line);

            if (startMatcher.find())
            {
                current = new MethodInfo();
                current.name = startMatcher.group(1);
                // Determine type from keyword
                String lowerLine = line.toLowerCase();
                if (lowerLine.contains("function") || lowerLine.contains("\u0444\u0443\u043d\u043a\u0446\u0438\u044f")) //$NON-NLS-1$
                {
                    current.type = "Function"; //$NON-NLS-1$
                }
                else
                {
                    current.type = "Procedure"; //$NON-NLS-1$
                }
                current.startLine = i + 1;
                current.isExport = lowerLine.contains("export") //$NON-NLS-1$
                    || lowerLine.contains("\u044d\u043a\u0441\u043f\u043e\u0440\u0442"); //$NON-NLS-1$
                bodyBuilder = new StringBuilder();
                bodyBuilder.append(line).append("\n"); //$NON-NLS-1$
            }
            else if (current != null)
            {
                bodyBuilder.append(line).append("\n"); //$NON-NLS-1$
                if (BslModuleAccess.METHOD_END_PATTERN.matcher(line).find())
                {
                    current.endLine = i + 1;
                    current.body = bodyBuilder.toString();
                    methods.add(current);
                    current = null;
                }
            }
        }

        return methods;
    }

    // -- = --
    // Diff algorithm
    // -- = --

    /**
     * Computes line-level diff between old and new content.
     * Uses LCS-based algorithm for files under threshold, simple comparison for larger files.
     */
    private List<DiffLine> computeDiff(String[] oldLines, String[] newLines)
    {
        if (oldLines.length > LCS_THRESHOLD && newLines.length > LCS_THRESHOLD)
        {
            return computeSimpleDiff(oldLines, newLines);
        }

        // Standard LCS dynamic programming
        int m = oldLines.length;
        int n = newLines.length;
        int[][] dp = new int[m + 1][n + 1];

        for (int i = 1; i <= m; i++)
        {
            for (int j = 1; j <= n; j++)
            {
                if (oldLines[i - 1].equals(newLines[j - 1]))
                {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                }
                else
                {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }

        // Backtrack to produce diff lines
        List<DiffLine> reversed = new ArrayList<>();
        int i = m;
        int j = n;

        while (i > 0 || j > 0)
        {
            if (i > 0 && j > 0 && oldLines[i - 1].equals(newLines[j - 1]))
            {
                reversed.add(new DiffLine(DiffLine.Type.EQUAL, oldLines[i - 1], i, j));
                i--;
                j--;
            }
            else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j]))
            {
                reversed.add(new DiffLine(DiffLine.Type.ADDED, newLines[j - 1], -1, j));
                j--;
            }
            else if (i > 0)
            {
                reversed.add(new DiffLine(DiffLine.Type.REMOVED, oldLines[i - 1], i, -1));
                i--;
            }
        }

        // Reverse to get correct order
        List<DiffLine> result = new ArrayList<>(reversed.size());
        for (int k = reversed.size() - 1; k >= 0; k--)
        {
            result.add(reversed.get(k));
        }

        return result;
    }

    /**
     * Simple line-by-line comparison for very large files.
     * Falls back to comparing lines at same positions.
     */
    private List<DiffLine> computeSimpleDiff(String[] oldLines, String[] newLines)
    {
        List<DiffLine> result = new ArrayList<>();
        int commonLen = Math.min(oldLines.length, newLines.length);

        for (int i = 0; i < commonLen; i++)
        {
            if (oldLines[i].equals(newLines[i]))
            {
                result.add(new DiffLine(DiffLine.Type.EQUAL, oldLines[i], i + 1, i + 1));
            }
            else
            {
                result.add(new DiffLine(DiffLine.Type.REMOVED, oldLines[i], i + 1, -1));
                result.add(new DiffLine(DiffLine.Type.ADDED, newLines[i], -1, i + 1));
            }
        }

        // Remaining old lines
        for (int i = commonLen; i < oldLines.length; i++)
        {
            result.add(new DiffLine(DiffLine.Type.REMOVED, oldLines[i], i + 1, -1));
        }

        // Remaining new lines
        for (int i = commonLen; i < newLines.length; i++)
        {
            result.add(new DiffLine(DiffLine.Type.ADDED, newLines[i], -1, i + 1));
        }

        return result;
    }

    // -- = --
    // Helper methods
    // -- = --

    private boolean containsMethod(List<MethodInfo> methods, String name)
    {
        return findMethod(methods, name) != null;
    }

    private MethodInfo findMethod(List<MethodInfo> methods, String name)
    {
        for (MethodInfo mi : methods)
        {
            if (mi.name.equalsIgnoreCase(name))
            {
                return mi;
            }
        }
        return null;
    }

    // -- = --
    // Inner classes
    // -- = --

    /**
     * Represents a single line in a diff result.
     */
    private static class DiffLine
    {
        enum Type
        {
            EQUAL,
            ADDED,
            REMOVED
        }

        Type type;
        String text;
        int oldLineNum; // -1 if ADDED
        int newLineNum; // -1 if REMOVED

        DiffLine(Type type, String text, int oldLineNum, int newLineNum)
        {
            this.type = type;
            this.text = text;
            this.oldLineNum = oldLineNum;
            this.newLineNum = newLineNum;
        }
    }

    /**
     * Parsed method information.
     */
    private static class MethodInfo
    {
        String name;
        String type; // "Procedure" or "Function"
        int startLine;
        int endLine;
        boolean isExport;
        String body;
    }

    /**
     * Method modification info for summary mode.
     */
    private static class MethodModification
    {
        String name;
        String type;
        int currentLine;
        int previousLineCount;
        int currentLineCount;
        int lineDelta;
    }

    /**
     * Per-method diff info for methods mode.
     */
    private static class MethodDiff
    {
        String name;
        String type;
        String status; // "modified", "added", "removed"
        int currentLine;
        int lineCount;
        boolean isExport;
        String body;
        List<DiffLine> diffLines;
    }
}
