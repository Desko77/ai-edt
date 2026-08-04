/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;

import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.ops.BslModuleAccess.ModulePathResolution;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * Serves a BSL module's text back to the agent, either whole or a chosen line span, with each line
 * carried behind its own number.
 * <p>
 * There is no model here and no editor: the file is read straight off disk, so the tool answers even
 * for a module whose Xtext model is not built. The line-number prefix on every fenced line is what
 * sets this reader apart from the method readers, which fence the raw source untouched; keep it, an
 * agent that asked for a range reads the numbers back to orient itself.
 * </p>
 */
public class ModuleSourceReader
    implements IMcpTool
{
    /** The hard ceiling on how many lines one call will return. */
    private static final int MAX_LINES = 5000;

    @Override
    public String getName()
    {
        return "read_module_source"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Read the source of a BSL module from an EDT project, with line numbers attached. " //$NON-NLS-1$
            + "Reads the whole file or just a line range; capped at 5000 lines per call."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("modulePath", //$NON-NLS-1$
                "Path under src/, e.g. 'CommonModules/MyModule/Module.bsl' or " //$NON-NLS-1$
                    + "'Documents/SalesOrder/ObjectModule.bsl'; a module FQN such as " //$NON-NLS-1$
                    + "'CommonModule.MyModule' / 'Catalog.Products.ManagerModule' also works (required)", true) //$NON-NLS-1$
            .integerProperty("startLine", //$NON-NLS-1$
                "First line to read (1-based, inclusive). Omit to start from the top.") //$NON-NLS-1$
            .integerProperty("endLine", //$NON-NLS-1$
                "Last line to read (1-based, inclusive). Omit to read through the end.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        if (modulePath == null || modulePath.isEmpty())
        {
            return "module-source.md"; //$NON-NLS-1$
        }
        return "source-" + modulePath.replace('/', '-').replace('\\', '-').toLowerCase() + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        int startLine = JsonUtils.extractIntArgument(params, "startLine", -1); //$NON-NLS-1$
        int endLine = JsonUtils.extractIntArgument(params, "endLine", -1); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName parameter is required"; //$NON-NLS-1$
        }
        if (modulePath == null || modulePath.isEmpty())
        {
            return "Error: modulePath parameter is required, e.g. 'CommonModules/MyModule/Module.bsl'"; //$NON-NLS-1$
        }

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: " + ProjectResolver.describeNotFound(projectName); //$NON-NLS-1$
        }

        ModulePathResolution resolution = BslModuleAccess.resolveModulePath(project, modulePath);
        if (!resolution.isResolved())
        {
            return resolution.getHint();
        }
        modulePath = resolution.getPath();

        IFile file = project.getFile(new Path("src").append(modulePath)); //$NON-NLS-1$
        if (!file.exists())
        {
            return "Error: no such file: src/" + modulePath //$NON-NLS-1$
                + ". Expected a path like 'CommonModules/ModuleName/Module.bsl' or " //$NON-NLS-1$
                + "'Documents/DocName/ObjectModule.bsl'"; //$NON-NLS-1$
        }

        List<String> lines;
        try
        {
            lines = BslModuleAccess.readFileLines(file);
        }
        catch (Exception e)
        {
            return "Error: could not read file: " + e.getMessage(); //$NON-NLS-1$
        }

        int total = lines.size();
        if (total == 0)
        {
            return "## " + modulePath + "\n\n**Lines:** 0 (file is empty)\n\n```bsl\n```\n"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        int from = 1;
        int to = total;
        if (startLine > 0)
        {
            from = Math.max(1, Math.min(startLine, total));
        }
        if (endLine > 0)
        {
            to = Math.max(from, Math.min(endLine, total));
        }
        boolean truncated = false;
        if (to - from + 1 > MAX_LINES)
        {
            to = from + MAX_LINES - 1;
            truncated = true;
        }

        StringBuilder builder = new StringBuilder();
        builder.append("## ").append(modulePath).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        builder.append("**Lines:** ").append(from).append("-").append(to) //$NON-NLS-1$ //$NON-NLS-2$
            .append(" of ").append(total).append(" total"); //$NON-NLS-1$ //$NON-NLS-2$
        if (truncated)
        {
            builder.append(" (truncated to the 5000-line cap)"); //$NON-NLS-1$
        }
        builder.append("\n\n```bsl\n"); //$NON-NLS-1$
        for (int i = from - 1; i < to; i++)
        {
            builder.append(String.format("%d: %s\n", i + 1, lines.get(i))); //$NON-NLS-1$
        }
        builder.append("```\n"); //$NON-NLS-1$
        return builder.toString();
    }
}
