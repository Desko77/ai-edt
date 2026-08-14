/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;

/**
 * Guards the tool-registration path: a name is registered once, and the single list the server
 * registers from never declares a name twice.
 * <p>
 * These are the two ends of one latent bug. {@link McpToolCatalog#register(IMcpTool)} used to let a
 * second tool overwrite the first under a shared name, so a rename collision would hide a tool with no
 * signal; and nothing checked that the server's own declared list was collision-free to begin with.
 * The first test pins the registry's contract, the second pins the list that feeds it.
 * </p>
 */
public class ToolRegistrationTest
{
    /**
     * A minimal tool that touches nothing - no Activator, no EDT service - so the registry can be
     * exercised headlessly. Two of these under one name is the collision the registry must catch.
     *
     * @param name the name it answers to
     * @return a throwaway tool with that name
     */
    private static IMcpTool stub(final String name)
    {
        return new IMcpTool()
        {
            @Override
            public String getName()
            {
                return name;
            }

            @Override
            public String getDescription()
            {
                return "stub"; //$NON-NLS-1$
            }

            @Override
            public String getInputSchema()
            {
                return "{}"; //$NON-NLS-1$
            }

            @Override
            public String execute(Map<String, String> params)
            {
                return ""; //$NON-NLS-1$
            }
        };
    }

    @Test
    public void registerRejectsADuplicateName()
    {
        McpToolCatalog registry = McpToolCatalog.getInstance();
        registry.clear();
        try
        {
            IMcpTool first = stub("dup_name"); //$NON-NLS-1$
            IMcpTool second = stub("dup_name"); //$NON-NLS-1$

            registry.register(first);
            registry.register(second);

            // The collision keeps the first registration and drops the second - it never silently
            // overwrites: exactly one tool answers to the name, and it is the one registered first.
            assertEquals(1, registry.getToolCount());
            assertSame(first, registry.getTool("dup_name")); //$NON-NLS-1$
        }
        finally
        {
            registry.clear();
        }
    }

    @Test
    public void theDeclaredToolListHasNoDuplicateNames()
    {
        List<IMcpTool> declared = McpHttpEndpoint.declareAllTools();

        Set<String> seen = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (IMcpTool tool : declared)
        {
            if (!seen.add(tool.getName()))
            {
                duplicates.add(tool.getName());
            }
        }

        assertTrue("the server declares these tool names more than once; the registry would keep the "
            + "first and silently drop the rest: " + duplicates, duplicates.isEmpty());

        // Tripwire on the surface size: this list is the single place tools are declared, so a change
        // here is a deliberate add or drop, updated on purpose, not an accident.
        assertEquals(124, declared.size());
    }
}
