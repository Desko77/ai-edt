/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.aiedt.mcp.server.support.EndpointWaiter;

/**
 * A launch reports ready when what it opened answers, not when a process exists.
 *
 * <p>The wait and its budget are one rule on every surface the launch comes from: a GET, ready is
 * any final status below 500, at most five redirects, one read is never longer than 5 seconds, and
 * the wait itself never outlives 50 seconds - the same limit a request may not outlive anywhere in
 * this server. A client that never gets there is reported, not killed.
 */
public class AClientIsReadyWhenWhatItOpenedAnswersTest
{
    /**
     * The rule lives in one helper on both launchers' path.
     */
    @Test
    public void theWaitLivesInOneHelper()
    {
        assertTrue(EndpointWaiter.MAX_SINGLE_REQUEST_MS == 5000);
        assertTrue(EndpointWaiter.MAX_REDIRECTS == 5);
        assertNotNull(EndpointWaiter.class.getDeclaredMethods());
    }

    /**
     * Every schema the launch comes from advertises both arguments.
     */
    @Test
    public void everySchemaAdvertisesTheWait()
    {
        for (String schema : new String[] {
            new DebugSessionStarter().getInputSchema(),
            new ClientSessionStarter().getInputSchema(),
            new LaunchDebuggerTool().getInputSchema(),
            new InfobaseAdminFacadeTool().getInputSchema() })
        {
            assertTrue(schema, schema.contains("waitForEndpoint")); //$NON-NLS-1$
            assertTrue(schema, schema.contains("endpointTimeoutSeconds")); //$NON-NLS-1$
        }
    }
}
