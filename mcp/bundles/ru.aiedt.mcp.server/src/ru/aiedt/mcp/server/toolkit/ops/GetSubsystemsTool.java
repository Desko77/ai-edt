/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.EMap;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.UiSync;

/**
 * Read-only tool that lists configuration subsystems as a hierarchy, with the
 * detail {@code get_metadata_objects} (flat name list) does not expose: the
 * command-interface flag, the member objects ({@code content}) and the nested
 * child subsystems.
 *
 * <p>The on-disk {@code CommandInterface.cmi} presence is surfaced as a NEUTRAL
 * fact, not an error: many valid stock subsystems carry
 * {@code includeInCommandInterface=true} and no {@code .cmi} file yet load fine
 * (EDT keeps a prebuilt index). The condition matters only for freshly added
 * extension subsystems at incremental export (see the export-pitfall note in the
 * project docs), so the agent decides relevance - this tool only reports.
 */
public class GetSubsystemsTool implements IMcpTool
{
    public static final String NAME = "list_subsystems"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `project_admin` `operation=list_subsystems`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "List configuration subsystems as a hierarchy with their command-interface " //$NON-NLS-1$
            + "flag, content (member object FQNs) and child subsystems - detail that " //$NON-NLS-1$
            + "get_metadata_objects (a flat name list) does not expose. Per subsystem: " //$NON-NLS-1$
            + "synonym, includeInCommandInterface, whether an on-disk CommandInterface.cmi " //$NON-NLS-1$
            + "exists (a NEUTRAL fact - many valid stock subsystems omit it), content " //$NON-NLS-1$
            + "object count and nested subsystems. Set includeContent=true to list the " //$NON-NLS-1$
            + "member object FQNs."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "Name of the EDT project to work in", true) //$NON-NLS-1$
            .stringProperty("nameFilter", //$NON-NLS-1$
                "Partial name match filter (case-insensitive). A top-level subsystem is " //$NON-NLS-1$
                    + "kept when it OR any descendant matches (the whole subtree is shown).") //$NON-NLS-1$
            .booleanProperty("includeContent", //$NON-NLS-1$
                "List each subsystem's content object FQNs. Can be large on big configs - " //$NON-NLS-1$
                    + "default false (object counts only).") //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "Maximum number of subsystems rendered across the whole tree (bounds the " //$NON-NLS-1$
                    + "response on large configs). Default 500.") //$NON-NLS-1$
            .stringProperty("language", //$NON-NLS-1$
                "Language code for synonyms (e.g. 'en', 'ru'). Default: first available.") //$NON-NLS-1$
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
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName != null && !projectName.isEmpty())
        {
            return "subsystems-" + projectName.toLowerCase() + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "subsystems.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName is required"; //$NON-NLS-1$
        }
        String nameFilter = JsonUtils.extractStringArgument(params, "nameFilter"); //$NON-NLS-1$
        boolean includeContent = JsonUtils.extractBooleanArgument(params, "includeContent", false); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$
        int limit = JsonUtils.extractIntArgument(params, "limit", 500); //$NON-NLS-1$
        limit = Math.min(Math.max(1, limit), 5000);
        final int maxRendered = limit;

        try
        {
            return UiSync.call(() -> listInternal(projectName, nameFilter, includeContent, language, maxRendered));
        }
        catch (Exception e)
        {
            Activator.logError("Error: the subsystem tree could not be read", e); //$NON-NLS-1$
            return "Error: " + e.getMessage(); //$NON-NLS-1$
        }
    }

    /**
     * Builds the markdown subsystem tree. Runs on the UI thread (model read).
     */
    private String listInternal(String projectName, String nameFilter,
        boolean includeContent, String language, int maxRendered)
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: " + ProjectResolver.describeNotFound(projectName); //$NON-NLS-1$
        }
        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            return "Error: no configuration provider is available"; //$NON-NLS-1$
        }
        Configuration config = configProvider.getConfiguration(project);
        if (config == null)
        {
            return "Error: failed to obtain configuration for project: " + projectName //$NON-NLS-1$
                + " (external data processor / report projects have no subsystems)"; //$NON-NLS-1$
        }

        EList<Subsystem> top = config.getSubsystems();
        StringBuilder header = new StringBuilder();
        header.append("# Subsystems: ").append(projectName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (top == null || top.isEmpty())
        {
            header.append("_No subsystems in this configuration._\n"); //$NON-NLS-1$
            return header.toString();
        }

        StringBuilder body = new StringBuilder();
        int[] rendered = { 0 };
        boolean[] truncated = { false };
        int shownTop = 0;
        for (Subsystem ss : top)
        {
            if (rendered[0] >= maxRendered)
            {
                truncated[0] = true;
                break;
            }
            if (nameFilter != null && !nameFilter.isEmpty() && !subtreeMatches(ss, nameFilter))
            {
                continue;
            }
            renderSubsystem(ss, project, "Subsystems/" + ss.getName(), //$NON-NLS-1$
                0, includeContent, language, body, rendered, maxRendered, truncated);
            shownTop++;
        }

        header.append("Top-level: ").append(shownTop).append(" shown"); //$NON-NLS-1$ //$NON-NLS-2$
        if (nameFilter != null && !nameFilter.isEmpty())
        {
            header.append(" (filter '").append(nameFilter).append("')"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        header.append(" / ").append(top.size()).append(" total; ") //$NON-NLS-1$ //$NON-NLS-2$
            .append(rendered[0]).append(" subsystems in the rendered tree.\n\n"); //$NON-NLS-1$
        header.append("Legend: CI = includeInCommandInterface; cmi = CommandInterface.cmi " //$NON-NLS-1$
            + "file present (neutral - stock subsystems often omit it).\n\n"); //$NON-NLS-1$
        if (shownTop == 0)
        {
            header.append("_No subsystem matched the filter._\n"); //$NON-NLS-1$
            return header.toString();
        }
        if (truncated[0])
        {
            header.append("> Output truncated at the ").append(maxRendered) //$NON-NLS-1$
                .append(" subsystem limit - raise `limit` to see the rest.\n\n"); //$NON-NLS-1$
        }
        return header.append(body).toString();
    }

    /**
     * Appends one subsystem (and its subtree) to {@code out}. {@code folderRel} is
     * the project-relative folder path under {@code src/} where this subsystem's
     * sources live ({@code Subsystems/<a>/Subsystems/<b>/...}), used to probe the
     * {@code CommandInterface.cmi} file.
     */
    private void renderSubsystem(Subsystem ss, IProject project, String folderRel,
        int depth, boolean includeContent, String language, StringBuilder out, int[] rendered,
        int maxRendered, boolean[] truncated)
    {
        if (rendered[0] >= maxRendered)
        {
            truncated[0] = true;
            return;
        }
        rendered[0]++;
        String indent = "  ".repeat(depth); //$NON-NLS-1$
        String name = ss.getName();
        if (name == null || name.isEmpty())
        {
            name = "<unnamed>"; //$NON-NLS-1$
        }
        EList<MdObject> content = ss.getContent();
        EList<Subsystem> children = ss.getSubsystems();
        int contentCount = content == null ? 0 : content.size();
        int childCount = children == null ? 0 : children.size();

        out.append(indent).append("- **").append(name).append("**"); //$NON-NLS-1$ //$NON-NLS-2$
        String synonym = synonymOf(ss, language);
        if (synonym != null && !synonym.isEmpty() && !synonym.equals(name))
        {
            out.append(" (").append(synonym).append(")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        out.append(" [CI ").append(ss.isIncludeInCommandInterface() ? "on" : "off").append("]") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .append(" [cmi ").append(cmiFileExists(project, folderRel) ? "yes" : "no").append("]") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .append(" - ").append(contentCount).append(contentCount == 1 ? " object" : " objects"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (childCount > 0)
        {
            out.append(", ").append(childCount).append(childCount == 1 ? " subsystem" : " subsystems"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        out.append("\n"); //$NON-NLS-1$

        if (includeContent && contentCount > 0)
        {
            for (MdObject m : content)
            {
                if (m != null && m.getName() != null)
                {
                    out.append(indent).append("    - ").append(fqnOf(m)).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        if (children != null)
        {
            for (Subsystem child : children)
            {
                if (rendered[0] >= maxRendered)
                {
                    truncated[0] = true;
                    break;
                }
                renderSubsystem(child, project, folderRel + "/Subsystems/" + child.getName(), //$NON-NLS-1$
                    depth + 1, includeContent, language, out, rendered, maxRendered, truncated);
            }
        }
    }

    /** True when this subsystem or any descendant name contains {@code filter}. */
    private static boolean subtreeMatches(Subsystem ss, String filter)
    {
        String low = filter.toLowerCase();
        if (ss.getName() != null && ss.getName().toLowerCase().contains(low))
        {
            return true;
        }
        EList<Subsystem> children = ss.getSubsystems();
        if (children != null)
        {
            for (Subsystem child : children)
            {
                if (subtreeMatches(child, filter))
                {
                    return true;
                }
            }
        }
        return false;
    }

    /** Synonym for the requested language, else the first available, else null. */
    private static String synonymOf(MdObject mdo, String language)
    {
        EMap<String, String> syn = mdo.getSynonym();
        if (syn == null || syn.isEmpty())
        {
            return null;
        }
        if (language != null && !language.isEmpty())
        {
            String v = syn.get(language);
            if (v != null && !v.isEmpty())
            {
                return v;
            }
        }
        for (String v : syn.values())
        {
            if (v != null && !v.isEmpty())
            {
                return v;
            }
        }
        return null;
    }

    /** FQN of a content member, e.g. {@code Catalog.Goods}. */
    private static String fqnOf(MdObject m)
    {
        return m.eClass().getName() + "." + m.getName(); //$NON-NLS-1$
    }

    /**
     * True when {@code src/<folderRel>/CommandInterface.cmi} exists on disk.
     * Reflection-free file probe; returns false on any miss.
     */
    private static boolean cmiFileExists(IProject project, String folderRel)
    {
        try
        {
            if (project.getLocation() == null)
            {
                return false;
            }
            Path p = project.getLocation().toFile().toPath()
                .resolve("src") //$NON-NLS-1$
                .resolve(folderRel.replace('/', File.separatorChar))
                .resolve("CommandInterface.cmi"); //$NON-NLS-1$
            return Files.exists(p);
        }
        catch (Exception e)
        {
            return false;
        }
    }
}
