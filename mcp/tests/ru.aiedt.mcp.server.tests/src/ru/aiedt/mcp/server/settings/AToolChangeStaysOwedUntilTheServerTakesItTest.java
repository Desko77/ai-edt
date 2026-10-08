/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import ru.aiedt.mcp.server.settings.PendingRestart.Action;

/**
 * What the preference page remembers about a tool selection the running server has not been given.
 * <p>
 * The tools tab writes the store as its own OK runs, so the next look at "have the tools changed"
 * answers no whatever happened to the restart. Without a debt kept across the two, a restart that
 * failed would be forgotten and the page would close over a server the failure had stopped.
 * </p>
 * <p>
 * A stopped server is the case the debt alone cannot answer: one the user stopped is owed nothing,
 * because a server that comes up later reads the store for itself, while one a failed restart left
 * stopped is owed a start - that failure took away the only server running the saved selection.
 * </p>
 */
public class AToolChangeStaysOwedUntilTheServerTakesItTest
{
    /** A save that changed the tools is answered by a restart while the server is running. */
    @Test
    public void aRunningServerTakesASavedToolChangeAsARestart()
    {
        assertEquals("the server is on the selection from before the save", Action.RESTART, //$NON-NLS-1$
            new PendingRestart().actionFor(true, true));
    }

    /** A restart that did not return is asked for again by the save that follows it. */
    @Test
    public void aRestartThatDidNotReturnIsAskedForAgain()
    {
        PendingRestart owed = new PendingRestart();
        owed.refused();

        assertEquals("a save with nothing new in it owes the restart that failed", Action.RESTART, //$NON-NLS-1$
            owed.actionFor(false, true));
    }

    /** A server a failed restart left stopped is brought back up by the next OK. */
    @Test
    public void aServerAFailedRestartLeftStoppedIsStartedByTheNextSave()
    {
        PendingRestart owed = new PendingRestart();
        owed.refused();

        assertEquals("nothing is listening on the selection that was saved", Action.START, //$NON-NLS-1$
            owed.actionFor(false, false));
    }

    /** A server the user stopped is left stopped: nothing is owed to one that is not running. */
    @Test
    public void aServerStoppedOnPurposeIsNotStartedByASave()
    {
        assertEquals("a stopped server reads the store when it comes up", Action.NOTHING, //$NON-NLS-1$
            new PendingRestart().actionFor(true, false));
    }

    /** A start that returned settles the debt, so an unchanged save asks for nothing. */
    @Test
    public void aStartThatReturnedSettlesIt()
    {
        PendingRestart owed = new PendingRestart();
        owed.refused();

        owed.applied();

        assertEquals("a server that took the save is owed nothing", Action.NOTHING, //$NON-NLS-1$
            owed.actionFor(false, false));
    }
}
