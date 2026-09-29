/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.support.BmHelpHelper;
import ru.aiedt.mcp.server.support.StandardCommandRegistry;

/**
 * Text a client receives names this product only: no other plugin, no fork version history.
 */
public class ClientTextNamesNoOtherProductTest
{
    private static final String[] FOREIGN = {"RSV", "EDT-MCP", "EDT MCP", "Status (1.4"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /**
     * Asserts that a text carries none of the foreign markers.
     *
     * @param where what the text is, for the failure message.
     * @param text the text a client receives
     */
    private static void assertNoForeignName(String where, String text)
    {
        assertNotNull(where, text);
        for (String name : FOREIGN)
        {
            assertFalse(where + " names '" + name + "': " + text, text.contains(name)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Calls edit_metadata help with an optional topic.
     *
     * @param topic the help topic, or <code>null</code> for the overview.
     * @return the answer text
     */
    private static String editMetadataHelp(String topic)
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        if (topic != null)
        {
            params.put("topic", topic); //$NON-NLS-1$
        }
        return new EditMetadataTool().execute(params);
    }

    /**
     * The overview of edit_metadata carries no fork status line.
     */
    @Test
    public void theEditMetadataOverviewNamesNoOtherProduct()
    {
        assertNoForeignName("edit_metadata help", editMetadataHelp(null)); //$NON-NLS-1$
    }

    /**
     * The per-role command interface operations describe themselves without another product's
     * release number.
     */
    @Test
    public void thePerRoleOperationsNameNoOtherProduct()
    {
        for (String op : new String[] {"set_subsystem_visibility", //$NON-NLS-1$
            "set_main_section_command_visibility", "set_subsystem_command_visibility"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            String help = editMetadataHelp(op);
            assertTrue(op + " help must describe the operation: " + help, help.contains(op)); //$NON-NLS-1$
            assertNoForeignName(op + " help", help); //$NON-NLS-1$
        }
    }

    /**
     * The refusal of a standard command on an owner that never renders it explains the platform,
     * not another product's release notes.
     */
    @Test
    public void theStandardCommandRefusalNamesNoOtherProduct()
    {
        String refusal = StandardCommandRegistry.checkOwnerKindCompatibility("CommonModule", "Post"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNoForeignName("standard command refusal", refusal); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("never renders")); //$NON-NLS-1$
    }

    /**
     * The call history describes this server by its own name.
     */
    @Test
    public void theHistoryDescriptionNamesThisServer()
    {
        String description = new McpHistoryReader().getDescription();
        assertNoForeignName("get_mcp_history description", description); //$NON-NLS-1$
        assertTrue(description, description.contains("AI-EDT")); //$NON-NLS-1$
    }

    /**
     * A help page written into a project names this product as its generator.
     */
    @Test
    public void aWrittenHelpPageNamesThisProductAsGenerator()
    {
        String html = BmHelpHelper.buildHelpHtml("text", "plain"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNoForeignName("help page", html); //$NON-NLS-1$
        assertTrue(html, html.contains("content=\"AI-EDT\"")); //$NON-NLS-1$
    }
}
