/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.After;
import org.junit.Test;

import ru.aiedt.mcp.server.support.PendingWorkRegistry.PendingEntry;
import ru.aiedt.mcp.server.support.PendingWorkRegistry.StopOutcome;

/**
 * A cancel through the registry reaches the work, and the key it was asked for is not handed to a
 * second copy of it.
 * <p>
 * Two facts, and the second is the one a dropped entry alone breaks. The key is computed from the
 * arguments, so a call with the same arguments arrives at the same key: while the first body is
 * still executing, a second one must not be dispatched under it, and the domain must report that
 * something was there to stop rather than that nothing was.
 * </p>
 * <p>
 * Nothing here registers a stopper: the five domains carry the ones production declares.
 * </p>
 */
public class ACancelKeepsTheRunFromStartingTwiceTest
{
    private final long defaultWait = PendingWorkRegistry.flagStopWaitMs;

    @After
    public void restoreTheWait()
    {
        PendingWorkRegistry.flagStopWaitMs = defaultWait;
    }

    private static String newKey()
    {
        return "cancel-twice-" + UUID.randomUUID(); //$NON-NLS-1$
    }

    /**
     * Starts a run under a call scope of its own, the way a tool call does.
     *
     * @param domain the registry the run belongs to
     * @param key the run key
     * @param work the body
     * @return the entry the call was dispatched on
     */
    private static PendingEntry startInACall(PendingWorkRegistry domain, String key,
        Function<PendingEntry, String> work)
    {
        ToolCallScope.enter(ToolCallScope.forCancellation(new ToolCallScope.Cancellation()));
        try
        {
            return domain.getOrStart(key, work);
        }
        finally
        {
            ToolCallScope.exit();
        }
    }

    private static void sleep(long ms)
    {
        try
        {
            Thread.sleep(ms);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A body that enters a latch, counts its entry, and waits to be let go.
     *
     * @param began counted down when the body starts
     * @param release awaited before the body returns
     * @param runs counts how many bodies were entered
     * @return the body
     */
    private static Function<PendingEntry, String> countingBody(CountDownLatch began,
        CountDownLatch release, AtomicInteger runs)
    {
        return entry -> {
            runs.incrementAndGet();
            began.countDown();
            try
            {
                release.await(20, TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            return "done"; //$NON-NLS-1$
        };
    }

    /**
     * A cancelled run holds its key, and a call under it starts nothing while the body runs.
     *
     * @param domain the flag-reading domain to run the scenario in
     */
    private void aCancelledRunIsNotStartedTwice(PendingWorkRegistry domain) throws Exception
    {
        PendingWorkRegistry.flagStopWaitMs = 200L;
        String key = newKey();
        CountDownLatch began = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        Function<PendingEntry, String> body = countingBody(began, release, runs);
        startInACall(domain, key, body);
        assertTrue("the first body is running", began.await(10, TimeUnit.SECONDS)); //$NON-NLS-1$

        StopOutcome stopped = domain.cancelAndStop(key);
        assertTrue("a cancel has to reach the running work, not only the waiting: " + stopped, //$NON-NLS-1$
            stopped != StopOutcome.NOTHING_TO_STOP);

        PendingEntry again = startInACall(domain, key, body);
        String answer = again.await(1000);
        assertNotNull("the repeat is answered at once rather than run: " + answer, answer); //$NON-NLS-1$
        assertTrue(answer, answer.contains("\"stillStopping\":true")); //$NON-NLS-1$
        assertEquals("the key is held until the body returns, so no second copy runs", //$NON-NLS-1$
            1, runs.get());

        release.countDown();
        sleep(300L);
        assertEquals("and the second call never became a run of its own", 1, runs.get()); //$NON-NLS-1$
        domain.remove(key);
    }

    /** The generic analysis tools: a cancelled scan holds its key while it stops. */
    @Test
    public void aCancelledRunIsNotStartedTwiceWhileItStops() throws Exception
    {
        aCancelledRunIsNotStartedTwice(PendingWorkRegistry.GENERIC);
    }

    /** The reference walk reads the same flag, so a cancelled walk behaves the same way. */
    @Test
    public void aCancelledReferenceWalkIsNotStartedTwiceWhileItStops() throws Exception
    {
        aCancelledRunIsNotStartedTwice(PendingWorkRegistry.REFERENCES);
    }

    /** Every domain whose cancel reaches the work says so, without a test registering anything. */
    @Test
    public void theFiveDomainsSayTheirCancelsReachTheWork()
    {
        assertTrue("GENERIC", PendingWorkRegistry.GENERIC.stopsItsWork()); //$NON-NLS-1$
        assertTrue("REFERENCES", PendingWorkRegistry.REFERENCES.stopsItsWork()); //$NON-NLS-1$
        assertTrue("UPDATE", PendingWorkRegistry.UPDATE.stopsItsWork()); //$NON-NLS-1$
        assertTrue("EXPORT", PendingWorkRegistry.EXPORT.stopsItsWork()); //$NON-NLS-1$
        assertTrue("IMPORT_BINARY", PendingWorkRegistry.IMPORT_BINARY.stopsItsWork()); //$NON-NLS-1$
    }

    /**
     * A cancel that arrives before the boundary keeps the body from passing it.
     * <p>
     * STOPPED is answered only once the body has left. At the moment of the call below the body is
     * still executing - parked before its boundary - so the outcome says it was told to stop and
     * had not stopped, and the flag is what keeps the launch from starting all the same.
     * </p>
     */
    @Test
    public void aCancelBeforeTheBoundaryKeepsTheBodyFromLaunching() throws Exception
    {
        PendingWorkRegistry.flagStopWaitMs = 200L;
        String key = newKey();
        CountDownLatch began = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch past = new CountDownLatch(1);
        AtomicBoolean launched = new AtomicBoolean();
        startInACall(PendingWorkRegistry.IMPORT_BINARY, key, entry -> {
            began.countDown();
            try
            {
                go.await(20, TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            launched.set(entry.claimTheLaunch());
            past.countDown();
            return "done"; //$NON-NLS-1$
        });
        try
        {
            assertTrue("the body is running and has not launched", began.await(10, TimeUnit.SECONDS)); //$NON-NLS-1$

            assertEquals("the outcome names a body that is still executing", //$NON-NLS-1$
                StopOutcome.STILL_RUNNING, PendingWorkRegistry.IMPORT_BINARY.cancelAndStop(key));

            go.countDown();
            assertTrue("the body reached its boundary", past.await(10, TimeUnit.SECONDS)); //$NON-NLS-1$
            assertFalse("nothing launched after the cancel", launched.get()); //$NON-NLS-1$

            // And once the body has left, nothing of the run is left to stop.
            long end = System.currentTimeMillis() + 5_000L;
            StopOutcome after = null;
            while (System.currentTimeMillis() < end)
            {
                after = PendingWorkRegistry.IMPORT_BINARY.cancelAndStop(key);
                if (after == StopOutcome.NOTHING_TO_STOP)
                {
                    break;
                }
                sleep(50);
            }
            assertEquals("a body that has left leaves nothing to stop", //$NON-NLS-1$
                StopOutcome.NOTHING_TO_STOP, after);
        }
        finally
        {
            go.countDown();
            PendingWorkRegistry.IMPORT_BINARY.remove(key);
        }
    }

    /** A cancel that arrives after the boundary says the work is still running. */
    @Test
    public void aCancelAfterTheBoundarySaysTheWorkIsStillRunning() throws Exception
    {
        PendingWorkRegistry.flagStopWaitMs = 200L;
        String key = newKey();
        CountDownLatch began = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean launched = new AtomicBoolean();
        startInACall(PendingWorkRegistry.IMPORT_BINARY, key, entry -> {
            launched.set(entry.claimTheLaunch());
            began.countDown();
            try
            {
                release.await(20, TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            return "done"; //$NON-NLS-1$
        });
        assertTrue(began.await(10, TimeUnit.SECONDS));
        assertTrue("the body passed the boundary", launched.get()); //$NON-NLS-1$

        assertEquals("past the boundary there is no promise to kill the process", //$NON-NLS-1$
            StopOutcome.STILL_RUNNING, PendingWorkRegistry.IMPORT_BINARY.cancelAndStop(key));

        release.countDown();
        PendingWorkRegistry.IMPORT_BINARY.remove(key);
    }
}
