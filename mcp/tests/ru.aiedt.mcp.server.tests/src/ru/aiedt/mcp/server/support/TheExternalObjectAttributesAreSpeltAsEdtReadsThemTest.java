/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The attributes that make the debugged client open an external object are spelt the way EDT reads
 * them.
 * <p>
 * A launch attribute is a plain string key. Misspell one and nothing objects: the configuration
 * carries an attribute nobody reads, the client starts, opens nothing, and the answer still says the
 * debug session is running. That is a silent failure, so the three names are pinned here against
 * what {@code RuntimeClientLaunchDelegate} reads.
 * </p>
 * <p>
 * The trap worth naming: the sibling attributes of the same configuration live under
 * {@code launching.core} - CLIENT_TYPE, STARTUP_OPTION, the automated-testing ones - while these
 * three live under {@code debug.core}. Copying a neighbour's prefix is the mistake this catches.
 * </p>
 */
public class TheExternalObjectAttributesAreSpeltAsEdtReadsThemTest
{
    /** Read from com._1c.g5.v8.dt.launching.core, RuntimeClientLaunchDelegate. */
    private static final String PROJECT_KEY =
        "com._1c.g5.v8.dt.debug.core.ATTR_EXTERNAL_OBJECT_PROJECT_NAME"; //$NON-NLS-1$

    private static final String NAME_KEY =
        "com._1c.g5.v8.dt.debug.core.ATTR_EXTERNAL_OBJECT_NAME"; //$NON-NLS-1$

    private static final String TYPE_KEY =
        "com._1c.g5.v8.dt.debug.core.ATTR_EXTERNAL_OBJECT_TYPE"; //$NON-NLS-1$

    /** Each name is the one the environment reads, character for character. */
    @Test
    public void eachNameIsTheOneTheEnvironmentReads()
    {
        assertEquals(PROJECT_KEY, LaunchConfigAccess.ATTR_EXTERNAL_OBJECT_PROJECT_NAME);
        assertEquals(NAME_KEY, LaunchConfigAccess.ATTR_EXTERNAL_OBJECT_NAME);
        assertEquals(TYPE_KEY, LaunchConfigAccess.ATTR_EXTERNAL_OBJECT_TYPE);
    }

    /** They sit under debug.core, not under the launching.core of their neighbours. */
    @Test
    public void theyCarryTheDebugCorePrefixAndNotTheNeighboursOne()
    {
        for (String key : new String[] {LaunchConfigAccess.ATTR_EXTERNAL_OBJECT_PROJECT_NAME,
            LaunchConfigAccess.ATTR_EXTERNAL_OBJECT_NAME,
            LaunchConfigAccess.ATTR_EXTERNAL_OBJECT_TYPE})
        {
            assertTrue(key + " has to live under debug.core", //$NON-NLS-1$
                key.startsWith("com._1c.g5.v8.dt.debug.core.")); //$NON-NLS-1$
        }
        assertTrue("the neighbour this is contrasted with lives elsewhere", //$NON-NLS-1$
            LaunchConfigAccess.ATTR_CLIENT_TYPE.startsWith("com._1c.g5.v8.dt.launching.core.")); //$NON-NLS-1$
    }

    /** Three distinct keys: one collision would make two of them the same attribute. */
    @Test
    public void theThreeAreDistinct()
    {
        assertNotEquals(LaunchConfigAccess.ATTR_EXTERNAL_OBJECT_PROJECT_NAME,
            LaunchConfigAccess.ATTR_EXTERNAL_OBJECT_NAME);
        assertNotEquals(LaunchConfigAccess.ATTR_EXTERNAL_OBJECT_NAME,
            LaunchConfigAccess.ATTR_EXTERNAL_OBJECT_TYPE);
        assertNotEquals(LaunchConfigAccess.ATTR_EXTERNAL_OBJECT_PROJECT_NAME,
            LaunchConfigAccess.ATTR_EXTERNAL_OBJECT_TYPE);
    }
}
