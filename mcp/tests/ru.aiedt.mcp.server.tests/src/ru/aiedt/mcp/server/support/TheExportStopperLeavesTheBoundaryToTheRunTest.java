/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * The export's stopper wakes the wait and leaves the launch boundary to the run: a stop that
 * arrives while the worker has not reached the boundary is answered as prevented, and the run
 * says its Designer run was not launched; a stop after the boundary is still a running process.
 */
public class TheExportStopperLeavesTheBoundaryToTheRunTest
{
    /** How long a test waits for the run. */
    private static final long PATIENCE_MS = 30_000L;

    /** A stop before the worker crossed the boundary is answered as prevented, and nothing ran. */
    @Test
    public void aStopBeforeTheBoundaryIsAnsweredAsPrevented() throws Exception
    {
        String runKey = "export-stop-before-" + System.nanoTime(); //$NON-NLS-1$
        AtomicBoolean launchClaim = new AtomicBoolean();
        CountDownLatch atTheLock = new CountDownLatch(1);
        CountDownLatch lockFree = new CountDownLatch(1);
        AtomicBoolean launched = new AtomicBoolean();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread run = new Thread(() -> {
            try
            {
                InfobaseObjectsExporter.runWhileLive(runKey, new InfobaseObjectsExporter.LiveRun(),
                    launchClaim, "the Designer export", PATIENCE_MS, () -> { //$NON-NLS-1$
                        atTheLock.countDown();
                        lockFree.await(PATIENCE_MS, TimeUnit.MILLISECONDS);
                        if (!launchClaim.compareAndSet(false, true))
                        {
                            return "not launched"; //$NON-NLS-1$
                        }
                        launched.set(true);
                        return "ran"; //$NON-NLS-1$
                    }, null);
            }
            catch (Throwable failed)
            {
                thrown.set(failed);
            }
        });
        run.start();
        try
        {
            assertTrue("the worker reached the lock", atTheLock.await(PATIENCE_MS, TimeUnit.MILLISECONDS)); //$NON-NLS-1$

            assertEquals(PendingWorkRegistry.StopOutcome.PREVENTED,
                InfobaseObjectsExporter.stopTheRun(runKey));
            run.join(PATIENCE_MS);
        }
        finally
        {
            lockFree.countDown();
        }

        assertTrue(String.valueOf(thrown.get()), thrown.get() instanceof DumpInfoRebuilder.Abandoned);
        DumpInfoRebuilder.Abandoned abandoned = (DumpInfoRebuilder.Abandoned)thrown.get();
        assertTrue(abandoned.getMessage(), abandoned.launchPrevented());
        assertTrue(abandoned.getMessage(), abandoned.getMessage().contains("that run was not launched")); //$NON-NLS-1$
        assertFalse("a stop is not a budget that ran out", abandoned.timedOut()); //$NON-NLS-1$
        assertFalse("no Designer run started", launched.get()); //$NON-NLS-1$
    }

    /** A stop after the worker crossed the boundary is answered as a process still running. */
    @Test
    public void aStopAfterTheBoundaryIsStillRunning() throws Exception
    {
        String runKey = "export-stop-after-" + System.nanoTime(); //$NON-NLS-1$
        AtomicBoolean launchClaim = new AtomicBoolean();
        CountDownLatch crossed = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread run = new Thread(() -> {
            try
            {
                InfobaseObjectsExporter.runWhileLive(runKey, new InfobaseObjectsExporter.LiveRun(),
                    launchClaim, "the Designer export", PATIENCE_MS, () -> { //$NON-NLS-1$
                        launchClaim.compareAndSet(false, true);
                        crossed.countDown();
                        finish.await(PATIENCE_MS, TimeUnit.MILLISECONDS);
                        return "ran"; //$NON-NLS-1$
                    }, null);
            }
            catch (Throwable failed)
            {
                thrown.set(failed);
            }
        });
        run.start();
        try
        {
            assertTrue("the worker crossed the boundary", crossed.await(PATIENCE_MS, TimeUnit.MILLISECONDS)); //$NON-NLS-1$

            assertEquals(PendingWorkRegistry.StopOutcome.STILL_RUNNING,
                InfobaseObjectsExporter.stopTheRun(runKey));
            run.join(PATIENCE_MS);
        }
        finally
        {
            finish.countDown();
        }

        assertTrue(String.valueOf(thrown.get()), thrown.get() instanceof DumpInfoRebuilder.Abandoned);
        DumpInfoRebuilder.Abandoned abandoned = (DumpInfoRebuilder.Abandoned)thrown.get();
        assertFalse(abandoned.getMessage(), abandoned.launchPrevented());
        assertTrue(abandoned.getMessage(), abandoned.getMessage().contains("while it was still running")); //$NON-NLS-1$
    }

    /** A budget that ran out is told apart from a stop. */
    @Test
    public void aBudgetThatRanOutIsMarkedAsTimedOut() throws Exception
    {
        CountDownLatch finish = new CountDownLatch(1);
        try
        {
            InfobaseObjectsExporter.runUnderBudget("the Designer export", 200L, () -> { //$NON-NLS-1$
                finish.await(PATIENCE_MS, TimeUnit.MILLISECONDS);
                return "ran"; //$NON-NLS-1$
            }, null, null);
            throw new AssertionError("the run was not abandoned"); //$NON-NLS-1$
        }
        catch (DumpInfoRebuilder.Abandoned abandoned)
        {
            assertTrue(abandoned.getMessage(), abandoned.timedOut());
            assertTrue(abandoned.getMessage(), abandoned.getMessage().contains("did not finish within")); //$NON-NLS-1$
        }
        finally
        {
            finish.countDown();
        }
    }
}
