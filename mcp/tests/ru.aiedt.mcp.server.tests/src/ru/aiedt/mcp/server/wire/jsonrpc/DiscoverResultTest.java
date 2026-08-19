/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire.jsonrpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

import ru.aiedt.mcp.server.wire.McpServerMeta;

/**
 * Pins what discovery tells a client about this server.
 * <p>
 * It is the first thing a modern client asks and, until it has an answer, the only thing it knows.
 * Every field here is therefore load-bearing in a way an ordinary result's fields are not: a
 * missing extension means a capability is never used, and a missing version means a client that
 * could have talked to this server decides it cannot.
 * </p>
 */
public class DiscoverResultTest
{
    /** Discovery is itself a finished answer, whatever it happens to be about. */
    @Test
    public void discoveryIsAFinishedAnswer()
    {
        assertEquals(McpServerMeta.RESULT_COMPLETE, sample().getResultType());
    }

    /** The versions come back in the order they were given, newest first. */
    @Test
    public void theVersionsKeepTheOrderTheyWereGivenIn()
    {
        assertEquals(Arrays.asList("2026-07-28", "2025-06-18"), sample().getSupportedVersions()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Tools are announced, and so is every extension this server implements.
     * <p>
     * An extension announced nowhere is an extension nobody uses: a client is required to declare
     * its own support per request, and it has no reason to declare support for something the server
     * never said it had.
     * </p>
     */
    @Test
    public void discoveryAnnouncesToolsAndTheExtensionsOnOffer()
    {
        DiscoverResult.Capabilities capabilities = sample().getCapabilities();

        assertNotNull("tools are what this server is for", capabilities.getTools()); //$NON-NLS-1$
        assertTrue("the tasks extension is implemented and must be advertised", //$NON-NLS-1$
            capabilities.getExtensions().containsKey(McpServerMeta.EXTENSION_TASKS));
    }

    /** An extension is announced by being listed; the value carries no settings. */
    @Test
    public void anExtensionIsAnnouncedByBeingThereRatherThanByItsValue()
    {
        Object value = sample().getCapabilities().getExtensions().get(McpServerMeta.EXTENSION_TASKS);

        assertTrue("the value should be an empty object, not a flag or a version", //$NON-NLS-1$
            value instanceof java.util.Map && ((java.util.Map<?, ?>)value).isEmpty());
    }

    /** The instructions and the cache hints are carried through as given. */
    @Test
    public void whatWasHandedInComesBackOut()
    {
        DiscoverResult result = sample();

        assertEquals("what this server is", result.getInstructions()); //$NON-NLS-1$
        assertEquals(3_600_000L, result.getTtlMs());
        assertEquals("private", result.getCacheScope()); //$NON-NLS-1$
    }

    private static DiscoverResult sample()
    {
        return new DiscoverResult(Arrays.asList("2026-07-28", "2025-06-18"), //$NON-NLS-1$ //$NON-NLS-2$
            "what this server is", 3_600_000L, "private"); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
