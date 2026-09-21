/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;

/**
 * What {@code tools/list} says about the class of each tool: whether it reads only, whether a call
 * can be repeated, whether it reaches beyond the machine.
 *
 * <p>The MCP {@code annotations} member of a tool carries four hints with defaults of their own -
 * {@code readOnlyHint} false, {@code destructiveHint} true, {@code idempotentHint} false,
 * {@code openWorldHint} true. Only a value that differs from its default is published, so a tool
 * whose class is the default carries no member at all, and the catalogue grows by a few bytes
 * per read rather than by a paragraph per tool.</p>
 *
 * <p>The class comes from the presets: the Read-only preset is the authority on what writes,
 * and a tool it switches off is a writer unless it sits in {@link #READS_INSIDE_WRITE_GROUPS} -
 * the reads that live in a group the preset takes off wholesale. A facade that gates its writes
 * by name ({@link IMcpTool#getGatedWriteNames}) is a writer while one of those names is enabled
 * and a reader once every one of them is off, so the hint follows the preset the workspace is
 * on.</p>
 *
 * <p>A writer is published with the spec's default {@code destructiveHint}: the server does not
 * tell an additive write from one that removes, so it does not claim to.</p>
 */
public final class ToolAnnotations
{
    /**
     * Tools the Read-only preset switches off with their group although they change nothing:
     * they read a live infobase, a debug session or the server's own history, and belong with
     * the group a preset that limits access to those things is expected to cover.
     */
    static final Set<String> READS_INSIDE_WRITE_GROUPS = Set.of(
        "get_applications", //$NON-NLS-1$
        "list_configurations", //$NON-NLS-1$
        "read_event_log", //$NON-NLS-1$
        "list_extension", //$NON-NLS-1$
        "list_breakpoints", //$NON-NLS-1$
        "debug_status", //$NON-NLS-1$
        "get_variables", //$NON-NLS-1$
        "get_profiling_results", //$NON-NLS-1$
        "code_template"); //$NON-NLS-1$

    /**
     * Tools that reach beyond the machine: they fetch from an update site, a URL or a GitHub
     * release. Everything else works against the IDE and the files beside it.
     */
    static final Set<String> OPEN_WORLD = Set.of(
        "self_upkeep", //$NON-NLS-1$
        "install_extension", //$NON-NLS-1$
        "extension_workshop", //$NON-NLS-1$
        "yaxunit_tests", //$NON-NLS-1$
        "run_yaxunit_tests", //$NON-NLS-1$
        "debug_yaxunit_tests", //$NON-NLS-1$
        "infobase_admin"); //$NON-NLS-1$

    private ToolAnnotations()
    {
        // static utility
    }

    /**
     * The annotations of a tool under the presets as they stand.
     *
     * @param tool the tool
     * @param registry the registry that says which gated write doors are enabled right now
     * @return the non-default hints, in a fixed order; empty when every hint is the default
     */
    public static Map<String, Object> of(IMcpTool tool, McpToolCatalog registry)
    {
        Map<String, Object> hints = new LinkedHashMap<>();
        if (!writes(tool, registry))
        {
            hints.put("readOnlyHint", Boolean.TRUE); //$NON-NLS-1$
            hints.put("idempotentHint", Boolean.TRUE); //$NON-NLS-1$
        }
        if (!OPEN_WORLD.contains(tool.getName()))
        {
            hints.put("openWorldHint", Boolean.FALSE); //$NON-NLS-1$
        }
        return hints;
    }

    /**
     * Whether a tool can change something right now.
     *
     * @param tool the tool
     * @param registry the registry that says which gated write doors are enabled
     * @return {@code true} for a writer
     */
    static boolean writes(IMcpTool tool, McpToolCatalog registry)
    {
        String name = tool.getName();
        Set<String> writers = ToolProfile.READ_ONLY.getDisabledTools();
        if (writers.contains(name) && !READS_INSIDE_WRITE_GROUPS.contains(name))
        {
            return true;
        }
        for (String door : tool.getGatedWriteNames())
        {
            if (registry.isToolEnabled(door))
            {
                return true;
            }
        }
        return false;
    }
}
