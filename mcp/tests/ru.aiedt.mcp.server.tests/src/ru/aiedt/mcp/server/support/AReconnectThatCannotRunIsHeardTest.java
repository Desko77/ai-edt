/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.eclipse.core.resources.ResourcesPlugin;
import org.junit.Test;

/**
 * A reconnection that cannot run reaches the caller instead of returning quietly.
 * <p>
 * The reconnection step of the thick-client handshake used to log its own failure and
 * return, so an operation whose Designer run went fine answered success while the infobase
 * stayed disconnected in EDT. The failure is thrown now, with the instruction to reconnect
 * by hand in the text.
 * </p>
 * <p>
 * The context carries a project the runtime has no synchronization manager for - either the
 * service is missing, which {@code ServiceAccess.get} reports by throwing, or a manager
 * refuses the context's infobase. Both end in the same refusal, which is what this pins.
 * </p>
 */
public class AReconnectThatCannotRunIsHeardTest
{
    /** A reconnection that cannot run is thrown with the by-hand instruction in the text. */
    @Test
    public void aReconnectThatCannotRunIsHeard()
    {
        ThickClientLaunch.LauncherContext ctx = new ThickClientLaunch.LauncherContext();
        ctx.project = ResourcesPlugin.getWorkspace().getRoot()
            .getProject("aiedt-reconnect-refusal-probe"); //$NON-NLS-1$
        ctx.infobaseName = "the-base"; //$NON-NLS-1$

        try
        {
            BmInfobaseExtensionHelper.reconnectInfobase(ctx);
            fail("a reconnection that could not run has to reach the caller"); //$NON-NLS-1$
        }
        catch (Exception expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("could not reconnect")); //$NON-NLS-1$
            assertTrue(expected.getMessage(), expected.getMessage().contains("the-base")); //$NON-NLS-1$
        }
    }

    /** A context carrying no project names no connection to take back, so nothing is thrown. */
    @Test
    public void aContextWithoutAProjectReconnectsNothing() throws Exception
    {
        BmInfobaseExtensionHelper.reconnectInfobase(new ThickClientLaunch.LauncherContext());
    }
}
