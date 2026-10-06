/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * One restart of this EDT at a time.
 *
 * <p>A restart starts its watcher before the delay that defers the close, so two calls inside
 * one delay would each start a watcher - and once EDT closed, each would relaunch the same
 * workspace. The slot the first call holds is the whole difference between one relaunch and two
 * instances racing for one workspace lock.</p>
 *
 * <p>The slot is claimed in {@code execute} before the watcher starter runs and freed on every
 * path that leaves EDT alive; what a headless suite can drive is the slot's own contract, the
 * same seam {@code restartPreflight} is held through.</p>
 */
public class ASecondRestartDoesNotRaceTheFirstTest
{
    private static RestartEdtTool.RestartInFlight deferredBy(String action, long delayMs)
    {
        return new RestartEdtTool.RestartInFlight(action,
            System.currentTimeMillis() + delayMs);
    }

    /** A lone call finds the slot free and takes it. */
    @Test
    public void aLoneCallFindsTheSlotFree()
    {
        RestartEdtTool.RestartInFlight only = deferredBy("restart", 1000); //$NON-NLS-1$
        try
        {
            assertNull("nothing else holds the slot", RestartEdtTool.claimRestartSlot(only)); //$NON-NLS-1$
        }
        finally
        {
            RestartEdtTool.releaseRestartSlot(only);
        }
    }

    /**
     * A call inside the delay of a running restart is refused and starts nothing of its own: the
     * refusal names the running action and how long before it closes EDT, and no second watcher
     * is started by a refused call.
     */
    @Test
    public void aSecondCallInsideTheDelayIsRefusedAndStartsNothing()
    {
        RestartEdtTool.RestartInFlight first = deferredBy("restart", 60000); //$NON-NLS-1$
        assertNull("the slot was free", RestartEdtTool.claimRestartSlot(first)); //$NON-NLS-1$
        try
        {
            RestartEdtTool.RestartInFlight conflict =
                RestartEdtTool.claimRestartSlot(deferredBy("restart", 1000)); //$NON-NLS-1$
            assertNotNull("a second call while the first is deferred does not pass", conflict); //$NON-NLS-1$
            assertSame("the refusal speaks about the attempt already running", first, conflict); //$NON-NLS-1$
            String refusal = RestartEdtTool.inFlightRefusal(conflict);
            assertTrue(refusal, refusal.contains("restart")); //$NON-NLS-1$
            assertTrue(refusal + " - it names how long before the first acts", //$NON-NLS-1$
                refusal.matches("(?s).*~\\d+ms.*")); //$NON-NLS-1$
            assertTrue(refusal, refusal.contains("no watcher was started")); //$NON-NLS-1$
        }
        finally
        {
            RestartEdtTool.releaseRestartSlot(first);
        }
    }

    /** A shutdown arriving during a running restart meets the same refusal. */
    @Test
    public void aShutdownDuringARunningRestartMeetsTheSameRefusal()
    {
        RestartEdtTool.RestartInFlight first = deferredBy("restart", 60000); //$NON-NLS-1$
        assertNull(RestartEdtTool.claimRestartSlot(first));
        try
        {
            RestartEdtTool.RestartInFlight conflict =
                RestartEdtTool.claimRestartSlot(deferredBy("shutdown", 0)); //$NON-NLS-1$
            assertNotNull("a shutdown does not slip past a running restart", conflict); //$NON-NLS-1$
            assertSame(first, conflict);
            String refusal = RestartEdtTool.inFlightRefusal(conflict);
            assertTrue(refusal, refusal.contains("restart")); //$NON-NLS-1$
        }
        finally
        {
            RestartEdtTool.releaseRestartSlot(first);
        }
    }

    /** A restart refused before anything closed leaves the slot free for the next call. */
    @Test
    public void aRefusedAttemptFreesTheSlotForTheNextCall()
    {
        RestartEdtTool.RestartInFlight first = deferredBy("restart", 1000); //$NON-NLS-1$
        assertNull(RestartEdtTool.claimRestartSlot(first));
        // What execute does when the preflight refuses: the call is answered, nothing started,
        // and the slot is freed before the answer is sent.
        RestartEdtTool.releaseRestartSlot(first);
        RestartEdtTool.RestartInFlight second = deferredBy("restart", 1000); //$NON-NLS-1$
        try
        {
            assertNull("a refused attempt leaves the next call free to try", //$NON-NLS-1$
                RestartEdtTool.claimRestartSlot(second));
        }
        finally
        {
            RestartEdtTool.releaseRestartSlot(second);
        }
    }
}
