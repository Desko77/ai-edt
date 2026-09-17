/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.support.TimeoutArgs;

/**
 * The wait for a breakpoint is cut to what one request can carry, under every name it accepts.
 *
 * <p>Measured 16.09: <code>wait_for_break timeoutSeconds=300</code> came back as a transport
 * timeout rather than <code>hit:false</code>. The tool answered correctly and nobody was left to
 * hear it - a single request does not live five minutes. An argument that promises longer is a
 * promise the transport cannot keep, so the promise is what changed.
 *
 * <p>Four names reach this value, and a cap applied to one of them only would leave the other three
 * lying.
 */
public class TheWaitNeverOutlivesItsOwnRequestTest
{
    @Test
    public void theCanonicalNameIsCapped()
    {
        assertEquals(50, capped(args("timeoutSeconds", "300"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theMillisecondAliasIsCapped()
    {
        assertEquals(50, capped(args("timeoutMs", "300000"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theWaitSecondsAliasIsCapped()
    {
        assertEquals(50, capped(args("waitSeconds", "300"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theLegacyTimeoutAliasIsCapped()
    {
        assertEquals(50, capped(args("timeout", "300"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void whenTwoNamesDisagreeTheCanonicalOneWinsAndIsStillCapped()
    {
        Map<String, String> both = args("timeoutSeconds", "300"); //$NON-NLS-1$ //$NON-NLS-2$
        both.put("timeoutMs", "5000"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(50, capped(both));
    }

    @Test
    public void aShortRequestIsLeftAlone()
    {
        assertEquals(5, capped(args("timeoutSeconds", "5"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aCallWithNoTimeoutWaitsTheDefaultAndIsNotCalledShortened()
    {
        Map<String, String> none = new HashMap<>();

        assertEquals("the default is the cap, so a plain call does not promise more than it can keep", //$NON-NLS-1$
            50, capped(none));
        assertEquals("nothing was asked for, so nothing was shortened", //$NON-NLS-1$
            null, TimeoutArgs.requestedSeconds(none));
    }

    @Test
    public void whatWasAskedForIsStillReadableSoTheAnswerCanSayItWasCut()
    {
        assertEquals(Integer.valueOf(300), TimeoutArgs.requestedSeconds(args("waitSeconds", "300"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theSchemaSaysWhereTheCeilingIs()
    {
        String schema = new SuspendWaiter().getInputSchema();

        assertTrue("a caller plans its call from the schema", schema.contains("50")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static int capped(Map<String, String> params)
    {
        // Exactly what the tool does with its own constants - so a constant raised past what a
        // request can carry shows up here as red.
        return TimeoutArgs.readSeconds(params, SuspendWaiter.MAX_TIMEOUT, 1, SuspendWaiter.MAX_TIMEOUT);
    }

    private static Map<String, String> args(String name, String value)
    {
        Map<String, String> params = new HashMap<>();
        params.put(name, value);
        return params;
    }
}
