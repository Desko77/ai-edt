/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * A debug port outside the range stops the call before anything is started or updated.
 *
 * <p>Measured on the stand 16.09: with another 1C:EDT holding the default debug port, the launch
 * failed inside the environment and there was no argument to move this one aside. The port is now an
 * argument, and a mistyped one must cost nothing - the same rule the external object follows.
 */
public class TheDebugPortIsCheckedBeforeAnythingStartsTest
{
    @Test
    public void aPortAboveTheRangeIsRefusedAndNothingRuns()
    {
        String answer = new DebugSessionStarter().execute(argsWithPort("70000")); //$NON-NLS-1$

        assertTrue("the refusal names the range", answer.contains("between 1 and 65535")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("nothing was launched or updated", //$NON-NLS-1$
            answer.contains("\"nothingWasLaunchedOrUpdated\":true")); //$NON-NLS-1$
        assertFalse("the refusal is not a success", answer.contains("\"success\":true")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aNegativePortIsRefusedToo()
    {
        String answer = new DebugSessionStarter().execute(argsWithPort("-1")); //$NON-NLS-1$

        assertTrue(answer.contains("between 1 and 65535")); //$NON-NLS-1$
        assertTrue(answer.contains("\"nothingWasLaunchedOrUpdated\":true")); //$NON-NLS-1$
    }

    @Test
    public void zeroMeansTheEnvironmentDecidesAndIsNotRefused()
    {
        String answer = new DebugSessionStarter().execute(argsWithPort("0")); //$NON-NLS-1$

        assertFalse("zero is the way to say 'as before', not a mistake", //$NON-NLS-1$
            answer.contains("between 1 and 65535")); //$NON-NLS-1$
    }

    @Test
    public void aPortInsideTheRangeGetsPastTheCheck()
    {
        String answer = new DebugSessionStarter().execute(argsWithPort("1560")); //$NON-NLS-1$

        assertFalse(answer.contains("between 1 and 65535")); //$NON-NLS-1$
    }

    @Test
    public void theRefusalNamesThePortItRefused()
    {
        String answer = new DebugSessionStarter().execute(argsWithPort("99999")); //$NON-NLS-1$

        assertTrue(answer.contains("\"debugServerPort\":99999")); //$NON-NLS-1$
    }

    @Test
    public void theSchemaDeclaresThePort()
    {
        String schema = new DebugSessionStarter().getInputSchema();

        assertTrue("a client builds its call from the schema", schema.contains("debugServerPort")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the schema says what an Attach does with it", //$NON-NLS-1$
            schema.contains("Attach")); //$NON-NLS-1$
    }

    @Test
    public void theAttributeIsSpeltAsTheEnvironmentReadsIt()
    {
        assertEquals("com._1c.g5.v8.dt.debug.core.ATTR_DEBUG_SERVER_PORT", //$NON-NLS-1$
            ru.aiedt.mcp.server.support.LaunchConfigAccess.ATTR_DEBUG_SERVER_PORT);
    }

    private static Map<String, String> argsWithPort(String port)
    {
        Map<String, String> params = new HashMap<>();
        params.put("debugServerPort", port); //$NON-NLS-1$
        return params;
    }
}
