/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Opening an external object in a launched client needs the environment to be allowed to build it.
 *
 * <p>Measured on the stand 17.09: the launch answered success with externalObjectOpened, the client
 * started, and nothing was open in it. Read out of the environment afterwards -
 * RuntimeClientLaunchDelegate asks the dump service whether generation is enabled for that project
 * BEFORE it looks the object up, and the probe project carried
 * PREF_AUTO_EXT_OBJECT_DUMP_GENERATION=false. With the setting off the whole branch is skipped
 * without a word.
 *
 * <p>The switching itself needs a workspace and is checked on the stand; what is put to the test
 * here is that the argument exists and that the schema tells a caller what it is for.
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
    public void theSchemaSaysWhatHappensWithoutIt()
    {
        String schema = new DebugSessionStarter().getInputSchema();

        assertTrue("the caller is told the client would start empty",
            schema.contains("starts empty") || schema.contains("nothing open"));
    }

    @Test
    public void theFacadeOffersItToo()
    {
        assertTrue("the canonical route is the facade",
            new LaunchDebuggerTool().getInputSchema().contains("enableExternalObjectDump"));
    }
}
