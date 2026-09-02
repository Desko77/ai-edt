/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import ru.aiedt.mcp.server.support.PendingWorkRegistry;

/**
 * A scenario run can be cancelled from two places, and both have to reach the client.
 * <p>
 * Listing the domain in {@code domains()} turns its runs into tasks, and a task is cancelled
 * through the registry - which completes the tracking future and, on its own, reaches nothing that
 * has already begun. The task would report itself cancelled while the client carried on driving
 * the infobase. So the domain declares what stops its work, and the registry asks it.
 * </p>
 */
public class ACancelReachesTheClientHoweverItArrivesTest
{
    /**
     * Loads the tool, which is what installs the stopper.
     * <p>
     * A reference to {@code NAME} or {@code MAX_TIMEOUT_SEC} would not do it: both are compile-time
     * constants and the compiler writes their values into the caller, so the class is never
     * touched. A method call is.
     * </p>
     *
     * @return whatever the call reported, which is beside the point
     */
    private static boolean loadTheTool()
    {
        boolean stopped = VanessaTool.stopClient("no-run-under-this-key"); //$NON-NLS-1$
        // Stopping marks the key, and this key belongs to no run - so the mark is taken back.
        VanessaTool.refusedBeforeLaunch("no-run-under-this-key"); //$NON-NLS-1$
        return stopped;
    }

    @Test
    public void theScenarioDomainStopsItsOwnWork()
    {
        assertFalse("nothing is running under a key nobody used", loadTheTool()); //$NON-NLS-1$

        assertTrue("without this a task cancel leaves the client running", //$NON-NLS-1$
            PendingWorkRegistry.VANESSA.stopsItsWork());
    }

    @Test
    public void cancellingThroughTheRegistryAsksWhatStopsTheWork()
    {
        loadTheTool();
        AtomicReference<String> asked = new AtomicReference<>();
        try
        {
            PendingWorkRegistry.VANESSA.stopsWith(key -> {
                asked.set(key);
                return PendingWorkRegistry.StopOutcome.NOTHING_TO_STOP;
            });

            PendingWorkRegistry.VANESSA.cancel("a-key-nobody-registered"); //$NON-NLS-1$

            assertEquals("the registry has to ask, even for a key it does not know: the tool " //$NON-NLS-1$
                + "removes the entry before it cancels, and the client still has to go", //$NON-NLS-1$
                "a-key-nobody-registered", asked.get()); //$NON-NLS-1$
        }
        finally
        {
            PendingWorkRegistry.VANESSA.stopsWith(VanessaTool::stopTheClient);
        }
    }

    @Test
    public void theDomainKeepsAnEntryLongerThanItsLongestRun()
    {
        long kept = PendingWorkRegistry.VANESSA.abandonedTtlMs();
        long longestRun = VanessaTool.MAX_TIMEOUT_SEC * 1000L;

        assertTrue("a run still executing would lose its entry and its result: " + kept //$NON-NLS-1$
            + "ms kept against " + longestRun + "ms accepted", kept > longestRun); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void stoppingMarksARunWhoseClientDoesNotExistYet()
    {
        String key = "begun-but-not-launched-" + System.nanoTime(); //$NON-NLS-1$

        assertFalse("nothing is registered under it yet", VanessaTool.stopClient(key)); //$NON-NLS-1$

        assertNotNull("a cancel through the task interface reaches only this, so this is where " //$NON-NLS-1$
            + "the mark has to be written: without it the run launches its client afterwards", //$NON-NLS-1$
            VanessaTool.refusedBeforeLaunch(key));
    }

    @Test
    public void detachingDoesNotAskAgainForWorkTheCallerAlreadyStopped()
    {
        loadTheTool();
        AtomicReference<String> asked = new AtomicReference<>();
        try
        {
            PendingWorkRegistry.VANESSA.stopsWith(key -> {
                asked.set(key);
                return PendingWorkRegistry.StopOutcome.NOTHING_TO_STOP;
            });

            PendingWorkRegistry.VANESSA.detach("already-stopped"); //$NON-NLS-1$

            assertNull("the tool stops the client itself and then lets go; asking the stopper " //$NON-NLS-1$
                + "again finds nothing and leaves a mark that refuses the next run", //$NON-NLS-1$
                asked.get());

            PendingWorkRegistry.VANESSA.cancel("stop-this-one"); //$NON-NLS-1$

            assertEquals("cancelling is the call that asks", "stop-this-one", asked.get()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            PendingWorkRegistry.VANESSA.stopsWith(VanessaTool::stopTheClient);
        }
    }

    @Test
    public void stoppingSaysWhichOfTheThreeThingsHappened()
    {
        String key = "nothing-under-this-one-" + System.nanoTime(); //$NON-NLS-1$

        assertEquals("asking is not stopping, and nothing was here to stop", //$NON-NLS-1$
            PendingWorkRegistry.StopOutcome.NOTHING_TO_STOP, VanessaTool.stopTheClient(key));

        VanessaTool.refusedBeforeLaunch(key);
    }

    @Test
    public void aDomainWithNothingToStopSaysSo()
    {
        assertFalse("cancelling a read stops the waiting, and there is nothing else to stop", //$NON-NLS-1$
            PendingWorkRegistry.GENERIC.stopsItsWork());
    }
}
