/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * What the user does to the server by hand after a failed restart decides what the next OK does.
 */
public class AStopByHandSettlesAFailedRestartTest
{
    /** A failed restart alone leaves a stopped server to be started by the next OK. */
    @Test
    public void aFailedRestartIsRetriedByTheNextOk()
    {
        PendingRestart debt = new PendingRestart();
        debt.refused();

        assertEquals(PendingRestart.Action.START, debt.actionFor(false, false));
    }

    /** A stop by hand after the failure is a choice: the next OK leaves the server stopped. */
    @Test
    public void aStopByHandIsNotUndoneByTheNextOk()
    {
        PendingRestart debt = new PendingRestart();
        debt.refused();
        debt.stoppedByHand();

        assertEquals(PendingRestart.Action.NOTHING, debt.actionFor(false, false));
    }

    /** The change left unapplied stays owed after a stop by hand and goes in with a running server. */
    @Test
    public void theUnappliedChangeStaysOwedAfterAStopByHand()
    {
        PendingRestart debt = new PendingRestart();
        debt.refused();
        debt.stoppedByHand();

        assertEquals(PendingRestart.Action.RESTART, debt.actionFor(false, true));
    }

    /** A start by hand applied what was owed: nothing is left for the next OK. */
    @Test
    public void aStartByHandClearsTheDebt()
    {
        PendingRestart debt = new PendingRestart();
        debt.refused();
        debt.startedByHand();

        assertEquals(PendingRestart.Action.NOTHING, debt.actionFor(false, true));
        assertEquals(PendingRestart.Action.NOTHING, debt.actionFor(false, false));
    }
}
