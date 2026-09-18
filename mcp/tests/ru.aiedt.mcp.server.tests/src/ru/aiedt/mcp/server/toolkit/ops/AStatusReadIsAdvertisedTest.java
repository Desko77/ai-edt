/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The status read is advertised on both surfaces that start an update.
 *
 * <p>Measured on the stand 15.09: a caller asking "is an update still going" had no way to ask -
 * a call without a runKey starts a run. The read exists now, and a strict client must see it on
 * both schemas the update is launched through, or it sees the run and not the way to look at it.
 */
public class AStatusReadIsAdvertisedTest
{
    /**
     * Both schemas the update is launched through advertise the read.
     */
    @Test
    public void bothSchemasAdvertiseIt()
    {
        String standalone = new DatabaseUpdater().getInputSchema();
        assertTrue(standalone, standalone.contains("statusOnly")); //$NON-NLS-1$
        String facade = new InfobaseAdminFacadeTool().getInputSchema();
        assertTrue(facade, facade.contains("statusOnly")); //$NON-NLS-1$
    }
}
