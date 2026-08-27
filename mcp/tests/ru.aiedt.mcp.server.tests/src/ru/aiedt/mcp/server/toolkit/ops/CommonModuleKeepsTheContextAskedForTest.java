/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Which execution context a new common module is created with.
 * <p>
 * {@code create_object objectType=CommonModule} used to accept only server, global and privileged.
 * External connection and Client (ordinary application) could not be asked for at all, and setting
 * them afterwards through {@code set_object_property} failed - so a module created through the tool
 * carried Server alone, which fails the standard for a server module and leaves the project with a
 * BLOCKER nothing in the tool could clear.
 * </p>
 * <p>
 * With the contexts accepted, the Server default has to stop being unconditional: a caller who asks
 * for External connection alone must get that alone. Server still defaults on when no context is
 * named at all, because the common-module-type check requires one and Global is not one.
 * </p>
 */
public class CommonModuleKeepsTheContextAskedForTest
{
    @Test
    public void noContextNamedAtAllStillGetsServer()
    {
        assertTrue("common-module-type requires a context, so one is chosen", //$NON-NLS-1$
            ObjectOps.serverFlagToWrite(null, null, null, null));
    }

    @Test
    public void globalOnlyStillGetsServerBecauseGlobalIsNotAContext()
    {
        // global is not among the arguments here precisely because it is not a context: a
        // global-only request names no context and therefore takes the default.
        assertTrue("global alone leaves the module contextless without this", //$NON-NLS-1$
            ObjectOps.serverFlagToWrite(null, null, null, null));
    }

    @Test
    public void anExplicitServerWins()
    {
        assertTrue(ObjectOps.serverFlagToWrite(Boolean.TRUE, null, null, null));
        assertFalse("an explicit false is an answer, not an absence", //$NON-NLS-1$
            ObjectOps.serverFlagToWrite(Boolean.FALSE, null, null, null));
    }

    @Test
    public void anotherContextSuppressesTheServerDefault()
    {
        assertFalse("external connection alone is a context; server is not added on top", //$NON-NLS-1$
            ObjectOps.serverFlagToWrite(null, Boolean.TRUE, null, null));
        assertFalse("client (ordinary application) alone is a context", //$NON-NLS-1$
            ObjectOps.serverFlagToWrite(null, null, Boolean.TRUE, null));
        assertFalse("server call alone is a context", //$NON-NLS-1$
            ObjectOps.serverFlagToWrite(null, null, null, Boolean.TRUE));
    }

    @Test
    public void aContextTurnedOffDoesNotSuppressTheDefault()
    {
        // externalConnection=false leaves the module with no context at all, and a module with none
        // fails common-module-type. Only a context turned ON stands in for Server.
        assertTrue("a context named false is not a context the module has", //$NON-NLS-1$
            ObjectOps.serverFlagToWrite(null, Boolean.FALSE, null, null));
    }

    @Test
    public void serverAndAnotherContextTogetherAreBothKept()
    {
        assertTrue("an explicit server survives beside another context", //$NON-NLS-1$
            ObjectOps.serverFlagToWrite(Boolean.TRUE, Boolean.TRUE, null, null));
    }
}
