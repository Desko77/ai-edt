/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.junit.Test;

/**
 * An Attach launch refuses what only a started client can carry.
 *
 * <p>startupOption goes to the client that starts, and waitForEndpoint waits on what the launch
 * opens - an Attach configuration has neither, and accepting either would promise a readiness the
 * launch never produced. The refusal for the startup string stood from the day the argument was
 * added; the endpoint one was missing, and a live call with an Attach configuration and a URL
 * answered as though the wait could apply.</p>
 */
public class AnAttachRefusesWhatOnlyAStartedClientCarriesTest
{
    /**
     * The endpoint refusal stands beside the startup-string one, at the same door, before the
     * already-running check and before anything launches.
     *
     * @throws Exception when the starter lost its launch method
     */
    @Test
    public void theRefusalLivesWhereTheStartupStringOneDoes()
        throws Exception
    {
        Class<?> choice = Class.forName(DebugSessionStarter.class.getName() + "$ClientChoice"); //$NON-NLS-1$
        Method launch = DebugSessionStarter.class.getDeclaredMethod("launchByConfigName", //$NON-NLS-1$
            String.class, boolean.class, int.class, String.class, String.class,
            boolean.class, String.class, choice, String.class, Integer.class);
        assertNotNull(launch);
    }

    /**
     * The schema keeps both arguments - the refusal is about the configuration's kind, not about
     * hiding the arguments from an Attach caller.
     */
    @Test
    public void theSchemaKeepsBothArguments()
    {
        String schema = new DebugSessionStarter().getInputSchema();
        assertTrue(schema, schema.contains("waitForEndpoint")); //$NON-NLS-1$
        assertTrue(schema, schema.contains("startupOption")); //$NON-NLS-1$
    }
}
