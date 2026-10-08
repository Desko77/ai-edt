/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * What the preference page remembers about a tool selection the running server has not been given.
 * <p>
 * The tools tab writes the store as its own OK runs, so the next look at "have the tools changed"
 * answers no whatever happened to the restart. Without a debt kept across the two, a restart that
 * failed would be forgotten and the page would close over a server the failure had stopped.
 * </p>
 */
public class AToolChangeStaysOwedUntilTheServerTakesItTest
{
    /** A restart that did not happen is asked for again by the save that follows it. */
    @Test
    public void aFailedRestartIsAskedForAgainOnTheNextSave()
    {
        PendingRestart owed = new PendingRestart();

        assertTrue("the save that changed the tools owes a restart", owed.due(true)); //$NON-NLS-1$
        owed.refused();

        assertTrue("and a save with nothing new in it owes the same one", owed.due(false)); //$NON-NLS-1$
    }

    /** A restart that returned settles it, so an unchanged save asks for nothing. */
    @Test
    public void aRestartThatReturnedSettlesIt()
    {
        PendingRestart owed = new PendingRestart();
        owed.refused();

        owed.applied();

        assertFalse("a server that took the save is owed nothing", owed.due(false)); //$NON-NLS-1$
    }
}
