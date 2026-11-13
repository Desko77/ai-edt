/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Opening an external object in a launched client needs the environment to be allowed to build it.
 *
 * <p>Measured on the stand 17.09: the launch answered success with externalObjectOpened, the client
 * started, and nothing was open in it. That was the defect. The launch now refuses before a client
 * starts, with {@code Nothing was launched}, when the dump service answers and generation is off,
 * and also when the service is unreachable. Read out of the environment afterwards -
 * RuntimeClientLaunchDelegate asks the dump service whether generation is enabled for that project
 * BEFORE it looks the object up, and the probe project carried
 * PREF_AUTO_EXT_OBJECT_DUMP_GENERATION=false.
 *
 * <p>The switching itself needs a workspace and is checked on the stand; what is put to the test
 * here is that the argument exists and that the schema tells a caller what it is for: an avoided
 * empty client and the refusal that stands in its place.
 */
public class TheExternalObjectNeedsItsDumpTest
{
    @Test
    public void theSchemaOffersTheSwitch()
    {
        String schema = new DebugSessionStarter().getInputSchema();

        assertTrue("a caller builds its call from the schema",
            schema.contains("enableExternalObjectDump"));
    }

    @Test
    public void theSchemaSaysTheLaunchIsRefusedWithoutIt()
    {
        String schema = new DebugSessionStarter().getInputSchema();

        assertTrue("the caller is told the launch is refused",
            schema.contains("launch is refused"));
        assertTrue("and that nothing was launched",
            schema.contains("Nothing was launched"));
        assertTrue("the empty client is named as the avoided outcome",
            schema.contains("rather than starting a client with nothing open"));
        assertFalse("a client with nothing open is not the result",
            schema.contains("the client starts with nothing open"));
    }

    @Test
    public void theFacadeOffersItToo()
    {
        assertTrue("the canonical route is the facade",
            new LaunchDebuggerTool().getInputSchema().contains("enableExternalObjectDump"));
    }
}
