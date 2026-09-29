/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
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
 * A cancel in a domain without a stopper of its own: the flag of the call that started the run is
 * raised, the outcome says whether the work left, and a call with the same key does not start a
 * second copy while the first is still executing.
 */
public class ACancelStopsWhatReadsItsFlagTest
{
    private static final PendingWorkRegistry DOMAIN = PendingWorkRegistry.GENERIC;

    private final long defaultWait = PendingWorkRegistry.flagStopWaitMs;

    @After
    public void restoreTheWait()
    {
        PendingWorkRegistry.flagStopWaitMs = defaultWait;
    }

    private static String newKey()
    {
        return "cancel-flag-" + UUID.randomUUID(); //$NON-NLS-1$
    }

    /**
     * Starts a run under a call scope of its own, the way a tool call does.
     */
    private static PendingEntry startInACall(String key, Function<PendingEntry, String> work)
    {
        ToolCallScope.enter(ToolCallScope.forCancellation(new ToolCallScope.Cancellation()));
        try
        {
            return DOMAIN.getOrStart(key, work);
        }
        finally
        {
            ToolCallScope.exit();
        }
    }

    /**
     * Work that runs until its call's flag is raised, or for at most 20 seconds.
     */
    private static Function<PendingEntry, String> untilCancelled(CountDownLatch began, AtomicBoolean sawFlag)
    {
        return entry -> {
            began.countDown();
            ToolCallScope.Cancellation flag = ToolCallScope.current().cancellation();
            long end = System.currentTimeMillis() + 20_000L;
            while (!flag.isCancelled() && System.currentTimeMillis() < end)
            {
                sleep(10);
            }
            sawFlag.set(flag.isCancelled());
            return "done"; //$NON-NLS-1$
        };
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

    @Test
    public void aTaskCancelStopsWorkThatReadsTheFlag() throws Exception
    {
        String key = newKey();
        CountDownLatch began = new CountDownLatch(1);
        AtomicBoolean sawFlag = new AtomicBoolean();
        startInACall(key, untilCancelled(began, sawFlag));
        assertTrue(began.await(10, TimeUnit.SECONDS));

        assertEquals(StopOutcome.STOPPED, DOMAIN.cancelAndStop(key));
        assertTrue("the work read the raised flag", sawFlag.get()); //$NON-NLS-1$
    }

    @Test
    public void theToolsOwnCancelRaisesTheFlagToo() throws Exception
    {
        String key = newKey();
        CountDownLatch began = new CountDownLatch(1);
        AtomicBoolean sawFlag = new AtomicBoolean();
        startInACall(key, untilCancelled(began, sawFlag));
        assertTrue(began.await(10, TimeUnit.SECONDS));

        assertTrue(DOMAIN.cancel(key));
        long end = System.currentTimeMillis() + 10_000L;
        while (!sawFlag.get() && System.currentTimeMillis() < end)
        {
            sleep(10);
        }
        assertTrue("the work read the raised flag", sawFlag.get()); //$NON-NLS-1$
    }

    @Test
    public void workThatIgnoresTheFlagIsReportedRunningAndIsNotStartedTwice() throws Exception
    {
        PendingWorkRegistry.flagStopWaitMs = 200L;
        String key = newKey();
        CountDownLatch began = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        Function<PendingEntry, String> ignoresTheFlag = entry -> {
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
        startInACall(key, ignoresTheFlag);
        assertTrue(began.await(10, TimeUnit.SECONDS));

        assertEquals(StopOutcome.STILL_RUNNING, DOMAIN.cancelAndStop(key));

        PendingEntry again = startInACall(key, ignoresTheFlag);
        String answer = again.await(1000);
        assertTrue(answer, answer.contains("\"stillStopping\":true")); //$NON-NLS-1$
        assertEquals("no second copy while the first executes", 1, runs.get()); //$NON-NLS-1$

        release.countDown();
        long end = System.currentTimeMillis() + 10_000L;
        PendingEntry fresh = null;
        while (runs.get() < 2 && System.currentTimeMillis() < end)
        {
            fresh = startInACall(key, entry -> {
                runs.incrementAndGet();
                return "second"; //$NON-NLS-1$
            });
            sleep(20);
        }
        assertEquals("a call after the first copy left starts a run", 2, runs.get()); //$NON-NLS-1$
        assertEquals("second", fresh.await(5000)); //$NON-NLS-1$
        DOMAIN.remove(key);
    }

    @Test
    public void aFinishedRunHasNothingToStop()
    {
        String key = newKey();
        PendingEntry entry = startInACall(key, e -> "done"); //$NON-NLS-1$
        assertEquals("done", entry.await(5000)); //$NON-NLS-1$

        assertEquals(StopOutcome.NOTHING_TO_STOP, DOMAIN.cancelAndStop(key));
    }

    @Test
    public void aRunStartedOutsideACallIsReportedStillRunning() throws Exception
    {
        PendingWorkRegistry.flagStopWaitMs = 200L;
        String key = newKey();
        CountDownLatch began = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DOMAIN.getOrStart(key, entry -> {
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

        assertEquals(StopOutcome.STILL_RUNNING, DOMAIN.cancelAndStop(key));
        release.countDown();
    }
}
