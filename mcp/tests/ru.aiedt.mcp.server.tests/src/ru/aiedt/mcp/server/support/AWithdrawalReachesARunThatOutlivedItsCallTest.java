/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/**
 * A withdrawal reaches a run that outlived the call that started it, and only from its own session.
 * <p>
 * A run that answers Pending goes on after its exchange is closed. The flag the work watches
 * belonged to that exchange's call, so a cancellation arriving afterwards found nothing to raise and
 * the work ran to the end. The entry now holds the flag together with the session and the request
 * that started the run, and the withdrawal is matched against those.
 * </p>
 * <p>
 * A withdrawal naming another session raises nothing: the run belongs to whoever started it, and a
 * second caller waiting on the same key wants the result rather than the end of it.
 * </p>
 */
public class AWithdrawalReachesARunThatOutlivedItsCallTest
{
    private static final String RUN_KEY = "test-run-withdrawal"; //$NON-NLS-1$

    @After
    public void forgetTheRun()
    {
        PendingWorkRegistry.GENERIC.remove(RUN_KEY);
    }

    /** Registers a finished run and gives it an owner, the way a Pending answer leaves one. */
    private static PendingWorkRegistry.PendingEntry runOwnedBy(String session, String request,
        ToolCallScope.Cancellation flag)
    {
        PendingWorkRegistry.PendingEntry entry =
            PendingWorkRegistry.GENERIC.getOrStart(RUN_KEY, e -> "done"); //$NON-NLS-1$
        entry.ownerSession = session;
        entry.ownerRequest = request;
        entry.cancellation = flag;
        return entry;
    }

    /** The session that started the run stops it, wherever the run is now running. */
    @Test
    public void theSessionThatStartedTheRunStopsIt()
    {
        ToolCallScope.Cancellation flag = new ToolCallScope.Cancellation();
        runOwnedBy("session-a", "17", flag); //$NON-NLS-1$ //$NON-NLS-2$

        String stopped = PendingWorkRegistry.withdrawOwnedRun("session-a", "17", //$NON-NLS-1$ //$NON-NLS-2$
            "withdrawn by the client"); //$NON-NLS-1$

        assertEquals(RUN_KEY, stopped);
        assertTrue("the work sees it through the flag it was given", flag.isCancelled()); //$NON-NLS-1$
        assertEquals("withdrawn by the client", flag.reason()); //$NON-NLS-1$
    }

    /** Another session naming the same request stops nothing. */
    @Test
    public void anotherSessionStopsNothing()
    {
        ToolCallScope.Cancellation flag = new ToolCallScope.Cancellation();
        runOwnedBy("session-a", "17", flag); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull("the run belongs to the session that started it", //$NON-NLS-1$
            PendingWorkRegistry.withdrawOwnedRun("session-b", "17", "withdrawn")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse("and its work is untouched", flag.isCancelled()); //$NON-NLS-1$
    }

    /** So does the owner naming a different request. */
    @Test
    public void theOwnerNamingAnotherRequestStopsNothing()
    {
        ToolCallScope.Cancellation flag = new ToolCallScope.Cancellation();
        runOwnedBy("session-a", "17", flag); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(PendingWorkRegistry.withdrawOwnedRun("session-a", "18", "withdrawn")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(flag.isCancelled());
    }

    /** A withdrawal missing either half raises nothing at all. */
    @Test
    public void aWithdrawalWithoutBothHalvesRaisesNothing()
    {
        ToolCallScope.Cancellation flag = new ToolCallScope.Cancellation();
        runOwnedBy("session-a", "17", flag); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(PendingWorkRegistry.withdrawOwnedRun(null, "17", "withdrawn")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(PendingWorkRegistry.withdrawOwnedRun("session-a", null, "withdrawn")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(flag.isCancelled());
    }
}
