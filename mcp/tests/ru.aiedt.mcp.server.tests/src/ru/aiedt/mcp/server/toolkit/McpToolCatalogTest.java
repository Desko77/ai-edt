/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Collection;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link McpToolCatalog}. Covers the registration table itself: singleton identity,
 * registration rules (drop nulls, keep the first on a name clash), lookup, the unmodifiable snapshot, the
 * count, and {@code clear}. Enablement filtering ({@code getEnabledTools}, {@code isToolEnabled})
 * depends on {@code ToolSettingsStore} and the Eclipse runtime, so it is not exercised here.
 */
public class McpToolCatalogTest
{
    private McpToolCatalog registry;

    @Before
    public void resetTable()
    {
        registry = McpToolCatalog.getInstance();
        registry.clear();
    }

    @After
    public void cleanTable()
    {
        registry.clear();
    }

    @Test
    public void getInstanceAlwaysReturnsSameObject()
    {
        assertSame(McpToolCatalog.getInstance(), McpToolCatalog.getInstance());
    }

    @Test
    public void registerStoresToolUnderItsName()
    {
        IMcpTool probe = probe("alpha");
        registry.register(probe);
        assertSame(probe, registry.getTool("alpha"));
        assertEquals(1, registry.getToolCount());
    }

    @Test
    public void registerIgnoresNullTool()
    {
        registry.register(null);
        assertEquals(0, registry.getToolCount());
    }

    @Test
    public void registerDropsToolWhoseNameIsNull()
    {
        registry.register(probe(null));
        assertEquals(0, registry.getToolCount());
        assertNull(registry.getTool(null));
    }

    @Test
    public void secondRegistrationUnderSameNameKeepsTheFirst()
    {
        Probe first = probe("shared");
        first.label = "first";
        Probe second = probe("shared");
        second.label = "second";

        registry.register(first);
        registry.register(second);

        assertEquals(1, registry.getToolCount());
        // A name clash keeps the FIRST registration (and logs an error); it does not overwrite -
        // a rename collision must surface as a missing tool, never silently replace another.
        assertEquals("first", registry.getTool("shared").getDescription());
    }

    @Test
    public void registerAccceptsManyDistinctNames()
    {
        registry.register(probe("a"));
        registry.register(probe("b"));
        registry.register(probe("c"));
        assertEquals(3, registry.getToolCount());
    }

    @Test
    public void getToolReturnsNullForUnknownName()
    {
        assertNull(registry.getTool("does-not-exist"));
    }

    @Test
    public void getToolReturnsNullForNullNameRatherThanThrowing()
    {
        assertNull(registry.getTool(null));
    }

    @Test
    public void unregisterRemovesNamedTool()
    {
        registry.register(probe("removable"));
        registry.unregister("removable");
        assertEquals(0, registry.getToolCount());
        assertNull(registry.getTool("removable"));
    }

    @Test
    public void unregisterIgnoresNullName()
    {
        registry.register(probe("kept"));
        registry.unregister(null);
        assertEquals(1, registry.getToolCount());
    }

    @Test
    public void unregisterOfUnknownNameIsHarmless()
    {
        registry.register(probe("x"));
        registry.unregister("never-registered");
        assertEquals(1, registry.getToolCount());
    }

    @Test
    public void getAllToolsIsEmptyBeforeAnythingRegisters()
    {
        Collection<IMcpTool> snapshot = registry.getAllTools();
        assertNotNull(snapshot);
        assertTrue(snapshot.isEmpty());
    }

    @Test
    public void getAllToolsReflectsEveryRegisteredTool()
    {
        registry.register(probe("a"));
        registry.register(probe("b"));
        assertEquals(2, registry.getAllTools().size());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void getAllToolsCannotBeMutated()
    {
        registry.register(probe("a"));
        registry.getAllTools().add(probe("intruder"));
    }

    @Test
    public void getToolCountIsZeroOnAnEmptyTable()
    {
        assertEquals(0, registry.getToolCount());
    }

    @Test
    public void getToolCountFollowsRegisterAndClear()
    {
        registry.register(probe("a"));
        registry.register(probe("b"));
        assertEquals(2, registry.getToolCount());
        registry.clear();
        assertEquals(0, registry.getToolCount());
    }

    @Test
    public void clearWipesEveryEntry()
    {
        registry.register(probe("x"));
        registry.register(probe("y"));
        registry.clear();
        assertEquals(0, registry.getToolCount());
        assertNull(registry.getTool("x"));
        assertNull(registry.getTool("y"));
    }

    /**
     * Minimal {@link IMcpTool} whose name and description are settable, so each probe can register
     * under a distinct identity and a test can tell two same-named probes apart by their description.
     */
    private static final class Probe implements IMcpTool
    {
        private final String name;

        String label = "probe";

        Probe(String name)
        {
            this.name = name;
        }

        @Override
        public String getName()
        {
            return name;
        }

        @Override
        public String getDescription()
        {
            return label;
        }

        @Override
        public String getInputSchema()
        {
            return "{\"type\":\"object\"}";
        }

        @Override
        public String execute(Map<String, String> params)
        {
            return "{}";
        }
    }

    private static Probe probe(String name)
    {
        return new Probe(name);
    }
}
