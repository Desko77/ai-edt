/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
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
 * path that leaves EDT alive - a refusal, a vetoed close, a failed close - while a close that
 * succeeded holds it to the end of the process. What a headless suite can drive is the slot's
 * own contract, the same seam {@code restartPreflight} is held through and {@code
 * runDeferredClose} settles.</p>
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

    /**
     * A close that succeeded keeps the slot held: the JVM has accepted the shutdown but not
     * finished it, and a call in that window meets the same refusal as before the close instead
     * of a free slot and a second watcher for the one exit.
     */
    @Test
    public void aSuccessfulCloseHoldsTheSlotUntilTheProcessEnds()
    {
        RestartEdtTool.RestartInFlight first = deferredBy("restart", 0); //$NON-NLS-1$
        assertNull(RestartEdtTool.claimRestartSlot(first));
        RestartEdtTool.RestartInFlight second = deferredBy("restart", 1000); //$NON-NLS-1$
        try
        {
            RestartEdtTool.runDeferredClose(first, null, false, true, () -> true);
            RestartEdtTool.RestartInFlight conflict = RestartEdtTool.claimRestartSlot(second);
            assertNotNull("a call after a successful close does not pass", conflict); //$NON-NLS-1$
            assertSame("the refusal speaks about the attempt whose close succeeded", first, //$NON-NLS-1$
                conflict);
            String refusal = RestartEdtTool.inFlightRefusal(conflict);
            assertTrue(refusal, refusal.contains("no watcher was started")); //$NON-NLS-1$
        }
        finally
        {
            // The process these tests run in does not end with the attempt; whichever of the two
            // holds the slot, give it back - identity makes each release a no-op for the other.
            RestartEdtTool.releaseRestartSlot(second);
            RestartEdtTool.releaseRestartSlot(first);
        }
    }

    /** A close the workbench vetoed leaves EDT alive: the slot is freed and the watcher stopped. */
    @Test
    public void aVetoedCloseFreesTheSlotAndStopsTheWatcher()
    {
        RestartEdtTool.RestartInFlight first = deferredBy("restart", 0); //$NON-NLS-1$
        assertNull(RestartEdtTool.claimRestartSlot(first));
        RecordingWatcher watcher = new RecordingWatcher();
        RestartEdtTool.runDeferredClose(first, watcher, false, true, () -> false);
        assertEquals("a vetoed close stops the watcher it started", 1, watcher.destroyed); //$NON-NLS-1$
        RestartEdtTool.RestartInFlight second = deferredBy("restart", 1000); //$NON-NLS-1$
        try
        {
            assertNull("a vetoed close leaves the next call free to try", //$NON-NLS-1$
                RestartEdtTool.claimRestartSlot(second));
        }
        finally
        {
            RestartEdtTool.releaseRestartSlot(second);
        }
    }

    /** A close that threw leaves EDT alive as well: the slot is freed and the watcher stopped. */
    @Test
    public void aFailedCloseFreesTheSlotAndStopsTheWatcher()
    {
        RestartEdtTool.RestartInFlight first = deferredBy("restart", 0); //$NON-NLS-1$
        assertNull(RestartEdtTool.claimRestartSlot(first));
        RecordingWatcher watcher = new RecordingWatcher();
        RestartEdtTool.runDeferredClose(first, watcher, false, true, () -> {
            throw new IllegalStateException("the workbench close broke"); //$NON-NLS-1$
        });
        assertEquals("a failed close stops the watcher it started", 1, watcher.destroyed); //$NON-NLS-1$
        RestartEdtTool.RestartInFlight second = deferredBy("restart", 1000); //$NON-NLS-1$
        try
        {
            assertNull("a failed close leaves the next call free to try", //$NON-NLS-1$
                RestartEdtTool.claimRestartSlot(second));
        }
        finally
        {
            RestartEdtTool.releaseRestartSlot(second);
        }
    }

    /** A stand-in watcher that counts the times it was told to go away. */
    private static final class RecordingWatcher extends Process
    {
        int destroyed;

        @Override
        public java.io.OutputStream getOutputStream()
        {
            return java.io.OutputStream.nullOutputStream();
        }

        @Override
        public java.io.InputStream getInputStream()
        {
            return java.io.InputStream.nullInputStream();
        }

        @Override
        public java.io.InputStream getErrorStream()
        {
            return java.io.InputStream.nullInputStream();
        }

        @Override
        public int waitFor()
        {
            return 0;
        }

        @Override
        public int exitValue()
        {
            // Still running: destroyQuietly must not confuse the stand-in for a dead process.
            throw new IllegalThreadStateException();
        }

        @Override
        public void destroy()
        {
            destroyed++;
        }
    }
}
