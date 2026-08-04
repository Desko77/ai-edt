/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.settings.ToolCategory;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;

/**
 * Reconciles the tools the server actually registers against the group table.
 * <p>
 * The groups are the only lever the preference page and the presets have: a tool that belongs to no
 * group has no checkbox, no preset can switch it off, and "Read-only" does not mean read-only. This
 * had already happened - 28 tools, among them {@code delete_infobase}, {@code delete_project} and
 * {@code import_configuration_from_xml}, were exposed under every preset - and nothing noticed,
 * because nothing compared the two lists. This test is that comparison.
 * </p>
 */
public class ToolCategoryCoverageTest
{
    private McpToolCatalog registry;

    @Before
    public void setUp()
    {
        registry = McpToolCatalog.getInstance();
        new McpHttpEndpoint().registerTools();
    }

    @After
    public void tearDown()
    {
        registry.clear();
    }

    @Test
    public void everyRegisteredToolBelongsToAGroup()
    {
        List<String> ungrouped = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            if (ToolCategory.getGroupForTool(tool.getName()) == null)
            {
                ungrouped.add(tool.getName());
            }
        }
        ungrouped.sort(null);

        assertTrue("These tools are exposed to agents but belong to no group, so no preference and "
            + "no preset can switch them off. Add each one to the group that matches what it can do "
            + "- and put anything destructive where a safe preset will disable it: " + ungrouped,
            ungrouped.isEmpty());
    }

    @Test
    public void everyGroupedNameIsARealTool()
    {
        Collection<IMcpTool> tools = registry.getAllTools();
        List<String> registered = new ArrayList<>();
        for (IMcpTool tool : tools)
        {
            registered.add(tool.getName());
        }

        List<String> phantom = new ArrayList<>();
        for (ToolCategory group : ToolCategory.values())
        {
            for (String name : group.getToolNames())
            {
                if (!registered.contains(name))
                {
                    phantom.add(name + " (" + group.name() + ")");
                }
            }
        }
        phantom.sort(null);

        assertTrue("These names are listed in a group but no such tool is registered - a rename or "
            + "a removal left them behind, and they now clutter the preference page and pad the "
            + "presets with names that mean nothing: " + phantom, phantom.isEmpty());
    }

    @Test
    public void noToolIsInTwoGroups()
    {
        Map<String, List<String>> owners = new TreeMap<>();
        for (ToolCategory group : ToolCategory.values())
        {
            for (String name : group.getToolNames())
            {
                owners.computeIfAbsent(name, key -> new ArrayList<>()).add(group.name());
            }
        }

        List<String> shared = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : owners.entrySet())
        {
            if (entry.getValue().size() > 1)
            {
                shared.add(entry.getKey() + " -> " + entry.getValue());
            }
        }

        assertTrue("A tool in two groups gets two checkboxes that disagree, and a preset that "
            + "disables one group leaves it enabled through the other: " + shared, shared.isEmpty());
    }
}
