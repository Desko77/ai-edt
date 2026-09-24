/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import ru.aiedt.mcp.server.support.DumpInfoRebuilder.Abandoned;

/**
 * The budget a Designer export is waited for: a run that answers within it returns its own answer,
 * a run that outlasts it is abandoned with the same launch-boundary mechanics the dump-info
 * rebuild uses, and a cancellation raised while the run is going is answered as an abandonment
 * rather than waited out.
 */
public class TheExportWaitBudgetTest
{
    /**
     * A run that answers within its budget returns its own answer.
     */
    @Test
    public void aRunWithinItsBudgetReturnsItsAnswer() throws Exception
    {
        String answer = InfobaseObjectsExporter.runUnderBudget("the Designer export", 5000L, //$NON-NLS-1$
            () -> "done", null, null); //$NON-NLS-1$

        assertEquals("done", answer); //$NON-NLS-1$
    }

    /**
     * A run that outlasts its budget is abandoned, and the cleanup it deferred runs once the call
     * returns on its own - not before.
     */
    @Test
    public void aRunPastItsBudgetIsAbandonedAndCleansUpAfterTheReturn() throws Exception
    {
        CountDownLatch hold = new CountDownLatch(1);
        AtomicBoolean cleaned = new AtomicBoolean(false);
        CountDownLatch cleanedAt = new CountDownLatch(1);
        try
        {
            InfobaseObjectsExporter.runUnderBudget("the Designer export", 200L, () -> { //$NON-NLS-1$
                await(hold);
                return "late"; //$NON-NLS-1$
            }, null, null);
            fail("a run that outlasts its budget is abandoned"); //$NON-NLS-1$
        }
        catch (Abandoned abandoned)
        {
            assertTrue(abandoned.processStillRunning());
            abandoned.whenFinished(() -> {
                cleaned.set(true);
                cleanedAt.countDown();
            });
            assertFalse("cleanup waits until the call returns", cleaned.get()); //$NON-NLS-1$
            hold.countDown();
            assertTrue("cleanup runs once the call has returned", //$NON-NLS-1$
                cleanedAt.await(5, TimeUnit.SECONDS));
            assertTrue(cleaned.get());
        }
        finally
        {
            hold.countDown();
        }
    }

    /**
     * A cancellation raised while the run is still going is answered at once, as an abandonment:
     * the wait does not sit out the rest of the budget for a caller that has withdrawn.
     */
    @Test
    public void aCancellationWhileTheRunIsGoingAbandonsItAtOnce() throws Exception
    {
        CountDownLatch hold = new CountDownLatch(1);
        // The caller withdraws one second into a sixty-second budget: the supplier stands for the
        // flag rising while the run is going, without a second thread to raise it.
        long started = System.currentTimeMillis();
        java.util.function.BooleanSupplier withdrawn =
            () -> System.currentTimeMillis() - started > 1_000L;
        try
        {
            InfobaseObjectsExporter.runUnderBudget("the Designer export", 60_000L, () -> { //$NON-NLS-1$
                await(hold);
                return "late"; //$NON-NLS-1$
            }, null, withdrawn);
            fail("a cancelled run is abandoned"); //$NON-NLS-1$
        }
        catch (Abandoned abandoned)
        {
            assertTrue(abandoned.processStillRunning());
            assertTrue("the cancellation is answered long before the budget ends", //$NON-NLS-1$
                System.currentTimeMillis() - started < 30_000L);
            assertTrue(abandoned.getMessage().contains("cancelled")); //$NON-NLS-1$
        }
        finally
        {
            hold.countDown();
        }
    }

    /**
     * A launch boundary claimed by the abandonment before the worker crossed it starts no
     * Designer at all: the run is answered as not running, and nothing is waited for. The call
     * body honours the boundary the way the production launcher call does - it claims it right
     * before launching, and a claim somebody else took means no launch.
     */
    @Test
    public void aBoundaryClaimedByTheAbandonmentStartsNoDesigner() throws Exception
    {
        AtomicBoolean boundary = new AtomicBoolean(false);
        AtomicBoolean called = new AtomicBoolean(false);
        try
        {
            InfobaseObjectsExporter.runUnderBudget("the Designer export", 200L, () -> { //$NON-NLS-1$
                // Still on the way to the launch when the budget runs out: slow enough that the
                // wait gives up and claims the boundary first.
                Thread.sleep(500L);
                if (!boundary.compareAndSet(false, true))
                {
                    throw new InterruptedException("the run was abandoned before its launch"); //$NON-NLS-1$
                }
                called.set(true);
                return "late"; //$NON-NLS-1$
            }, boundary, null);
            fail("a run that outlasts its budget is abandoned"); //$NON-NLS-1$
        }
        catch (Abandoned abandoned)
        {
            assertFalse("a launch prevented at the boundary is waited for by nothing", //$NON-NLS-1$
                abandoned.processStillRunning());
        }
        assertFalse("the launch was never made", called.get()); //$NON-NLS-1$
    }

    private static void await(CountDownLatch latch)
    {
        while (latch.getCount() > 0)
        {
            try
            {
                latch.await();
            }
            catch (InterruptedException ignored)
            {
                Thread.interrupted();
            }
        }
    }
}
