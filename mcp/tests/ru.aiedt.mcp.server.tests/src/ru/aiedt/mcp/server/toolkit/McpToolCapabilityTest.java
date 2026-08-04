/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins the capability-id seam: a tool whose wire name differs from its frozen capability id, plus its
 * aliases, resolves under every callable name yet counts as one tool, and two tools cannot share a
 * capability id. This is the property that lets a future version rename a wire name or add an alias
 * without re-enabling a tool a preset switched off - the gate keys on the capability id, which the
 * rename never touches.
 */
public class McpToolCapabilityTest
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
    public void aliasResolvesToTheSameToolAsTheWireName()
    {
        RenamedProbe probe = renamed("bsl_write", "write_module_source", "write_module_source");
        registry.register(probe);

        assertSame(probe, registry.getTool("bsl_write"));
        assertSame(probe, registry.getTool("write_module_source"));
    }

    @Test
    public void aRenamedToolCountsOnceEvenWithAliases()
    {
        registry.register(renamed("bsl_write", "write_module_source", "write_module_source"));
        assertEquals(1, registry.getToolCount());

        Collection<IMcpTool> all = registry.getAllTools();
        assertEquals(1, all.size());
    }

    @Test
    public void anAliasAlsoAdvertisedAsAnotherToolsWireNameKeepsTheFirstBinding()
    {
        // Two tools fight over the callable name "shared": the first is the wire name of tool A, the
        // second is an alias of tool B. The first binding wins; tool B is still reachable by its own
        // wire name and its other aliases, just not by the clashing name.
        RenamedProbe a = renamed("shared", "cap-a");
        RenamedProbe b = renamed("b-wire", "cap-b", "shared");

        registry.register(a);
        registry.register(b);

        // "shared" still resolves to A (first binding kept).
        assertSame(a, registry.getTool("shared"));
        // B is reachable by its own wire name and by any non-clashing alias.
        assertSame(b, registry.getTool("b-wire"));
    }

    @Test
    public void twoToolsSharingACapabilityIdKeepTheFirst()
    {
        RenamedProbe first = renamed("new-wire", "frozen-cap");
        RenamedProbe second = renamed("other-wire", "frozen-cap");

        registry.register(first);
        registry.register(second);

        // The capability id is the identity, so the second registration is a duplicate and ignored.
        assertEquals(1, registry.getToolCount());
        assertSame(first, registry.getTool("new-wire"));
        // The second tool's wire name was never bound, because the capability clashed first.
        assertNull(registry.getTool("other-wire"));
    }

    @Test
    public void aSecondToolClaimingAnExistingPrimaryWireNameIsRejectedEntirely()
    {
        // Two tools, distinct capability ids, but the same primary wire name. The second must not be
        // stored under its capability - if it were, tools/list would advertise it under a name that
        // resolves to the first tool, a ghost entry. The whole second registration is rejected.
        RenamedProbe first = renamed("shared-wire", "cap-a");
        RenamedProbe second = renamed("shared-wire", "cap-b");

        registry.register(first);
        registry.register(second);

        assertEquals(1, registry.getToolCount());
        assertSame(first, registry.getTool("shared-wire"));
        // The second capability was never stored, so it is not advertised either.
        Collection<IMcpTool> enabled = registry.getEnabledTools();
        assertEquals(1, enabled.size());
    }

    @Test
    public void aToolWithNoAliasesResolvesUnderItsWireNameAlone()
    {
        // The default capability id is getName, so a plain tool behaves exactly as before the seam:
        // one callable name, one capability id, the same string.
        registry.register(probe("plain"));

        assertSame(probe("plain").getClass(), registry.getTool("plain").getClass());
        assertEquals(1, registry.getToolCount());
    }

    @Test
    public void unregisterByAliasRemovesTheWholeTool()
    {
        RenamedProbe probe = renamed("bsl_write", "write_module_source", "write_module_source");
        registry.register(probe);

        registry.unregister("write_module_source");

        assertEquals(0, registry.getToolCount());
        assertNull(registry.getTool("bsl_write"));
        assertNull(registry.getTool("write_module_source"));
    }

    @Test
    public void enabledToolsDoesNotDoubleCountARenamedTool()
    {
        // getEnabledTools reads the live preference store, which is null headless, so the disabled and
        // unlisted sets are empty and every capability is listed. The point of this test is that a
        // renamed tool with an alias appears ONCE - capability-keyed dedup, not name-keyed.
        registry.register(renamed("bsl_write", "write_module_source", "write_module_source"));
        registry.register(probe("plain"));

        Collection<IMcpTool> enabled = registry.getEnabledTools();
        assertEquals(2, enabled.size());
        assertTrue(enabled.stream().anyMatch(t -> "bsl_write".equals(t.getName()))); //$NON-NLS-1$
        assertTrue(enabled.stream().anyMatch(t -> "plain".equals(t.getName()))); //$NON-NLS-1$
    }

    private static IMcpTool probe(String name)
    {
        return new PlainProbe(name);
    }

    private static RenamedProbe renamed(String wireName, String capabilityId, String... aliases)
    {
        return new RenamedProbe(wireName, capabilityId, aliases);
    }

    /** A tool that uses the default capability id (its wire name) and has no aliases. */
    private static final class PlainProbe implements IMcpTool
    {
        private final String name;

        PlainProbe(String name)
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
            return name;
        }

        @Override
        public String getInputSchema()
        {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public String execute(Map<String, String> params)
        {
            return "{}"; //$NON-NLS-1$
        }
    }

    /** A tool whose wire name, capability id and aliases are all distinct, to exercise the seam. */
    private static final class RenamedProbe implements IMcpTool
    {
        private final String wireName;

        private final String capabilityId;

        private final List<String> aliases;

        RenamedProbe(String wireName, String capabilityId, String... aliases)
        {
            this.wireName = wireName;
            this.capabilityId = capabilityId;
            this.aliases = aliases.length == 0 ? Collections.emptyList() : Arrays.asList(aliases);
        }

        @Override
        public String getName()
        {
            return wireName;
        }

        @Override
        public String getCapabilityId()
        {
            return capabilityId;
        }

        @Override
        public List<String> getAliases()
        {
            return aliases;
        }

        @Override
        public String getDescription()
        {
            return wireName;
        }

        @Override
        public String getInputSchema()
        {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public String execute(Map<String, String> params)
        {
            return "{}"; //$NON-NLS-1$
        }
    }
}
