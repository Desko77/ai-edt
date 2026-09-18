/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.eclipse.debug.core.ILaunchConfiguration;
import org.junit.Test;

import ru.aiedt.mcp.server.support.LaunchConfigAccess;

/**
 * The startup string of one launch goes onto that launch's own configuration copy.
 *
 * <p>The environment hands {@code ATTR_STARTUP_OPTION} to the client it starts - the test runners
 * already pass their parameters the same way. A saved configuration must not carry it: the string
 * belongs to this one launch, the way the external object and the debug port do, so the method
 * writes a working copy and reuses one when given one. The signature and the attribute key are what
 * the other per-launch attributes keep, and they are what a wrong rewrite would break.
 */
public class AStartupStringRidesTheLaunchTest
{
    /**
     * The key the environment's launch delegate resolves, and the method that writes it onto the
     * launch's own copy.
     *
     * @throws Exception when the method is gone or its signature drifted
     */
    @Test
    public void theAttributeKeyAndItsWriterAreTheOnesTheEnvironmentReads()
        throws Exception
    {
        assertEquals("com._1c.g5.v8.dt.launching.core.ATTR_STARTUP_OPTION", //$NON-NLS-1$
            LaunchConfigAccess.ATTR_STARTUP_OPTION);

        Method method = LaunchConfigAccess.class.getDeclaredMethod("withStartupOption", //$NON-NLS-1$
            ILaunchConfiguration.class, String.class);
        assertEquals(ILaunchConfiguration.class, method.getReturnType());
        assertTrue(java.lang.reflect.Modifier.isStatic(method.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isPublic(method.getModifiers()));
    }

    /**
     * Both starters declare the argument in their schemas - four schemas cover the launch surface,
     * and an argument present in the code but missing from one of them is a contract the strict
     * client cannot call.
     */
    @Test
    public void everySchemaOfTheLaunchSurfaceAdvertisesTheArgument()
    {
        String debugger = new DebugSessionStarter().getInputSchema();
        assertTrue(debugger, debugger.contains("\"startupOption\"")); //$NON-NLS-1$ //$NON-NLS-2$
        String client = new ClientSessionStarter().getInputSchema();
        assertTrue(client, client.contains("\"startupOption\"")); //$NON-NLS-1$ //$NON-NLS-2$
        String facade = new LaunchDebuggerTool().getInputSchema();
        assertTrue(facade, facade.contains("\"startupOption\"")); //$NON-NLS-1$ //$NON-NLS-2$
        String infobase = new InfobaseAdminFacadeTool().getInputSchema();
        assertTrue(infobase, infobase.contains("\"startupOption\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The help no longer names a route that does not exist.
     */
    @Test
    public void theHelpPointsAtTheWorkshopThatActuallyImports()
    {
        String debugger = new DebugSessionStarter().getInputSchema();
        assertTrue(debugger.contains("external_object_workshop")); //$NON-NLS-1$
        assertTrue("the workshop is named, not the route that has no such operation", //$NON-NLS-1$
            !debugger.contains("config_io operation=import_external_object")); //$NON-NLS-1$
        assertNotNull(debugger);
    }
}
