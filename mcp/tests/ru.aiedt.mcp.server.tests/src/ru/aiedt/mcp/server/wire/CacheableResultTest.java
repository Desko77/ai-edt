/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.aiedt.mcp.server.wire.jsonrpc.ToolsListResult;

/**
 * Pins what a cacheable answer promises, and what the tool catalogue promises in particular.
 */
public class CacheableResultTest
{
    /** The two scopes are the two words HTTP caching has used for this for thirty years. */
    @Test
    public void theScopesAreTheOnesCachesAlreadyUnderstand()
    {
        assertEquals("public", CacheableResult.SCOPE_PUBLIC); //$NON-NLS-1$
        assertEquals("private", CacheableResult.SCOPE_PRIVATE); //$NON-NLS-1$
    }

    /**
     * The catalogue keeps for a while, and only for the client that asked.
     * <p>
     * Private is the part worth pinning. The catalogue is not a fact about this software - it is a
     * fact about this workspace: which tools this EDT has, and which of them the presets leave
     * switched on. A shared cache handing one developer's catalogue to another would be handing
     * over the wrong answer, and where a preset restricts what may be called, a more permissive one
     * than that developer is meant to have.
     * </p>
     */
    @Test
    public void theCatalogueKeepsButIsNotSharedAround()
    {
        ToolsListResult catalogue = new ToolsListResult();

        assertTrue("a cacheable answer with no lifetime is not cacheable", //$NON-NLS-1$
            catalogue.getTtlMs() > 0L);
        assertEquals(CacheableResult.SCOPE_PRIVATE, catalogue.getCacheScope());
    }

    /**
     * The lifetime is bounded by how long a person will wait for a preference to take effect.
     * <p>
     * Nothing tells a client the catalogue changed, so whatever this number is, it is how long a
     * developer who switched a preset off can go on being offered the tool. An hour would be wrong
     * for that reason, and a couple of seconds would save nothing.
     * </p>
     */
    @Test
    public void theLifetimeIsBoundedByHowLongAPersonWouldWait()
    {
        long ttl = new ToolsListResult().getTtlMs();

        assertTrue("too short to save a client anything: " + ttl, ttl >= 60_000L); //$NON-NLS-1$
        assertTrue("a preset change would go unnoticed for too long: " + ttl, //$NON-NLS-1$
            ttl <= 10 * 60_000L);
    }
}
