/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import ru.aiedt.mcp.server.support.PendingWorkRegistry;

/**
 * What cancelling a scenario run has to be true of before its client exists.
 * <p>
 * Between the moment a run begins and the moment it starts the client there is nothing to destroy:
 * the process map is empty for that key, and completing the run's future reaches only work that
 * has not started yet. A cancel arriving there would report that nothing was running, and the
 * client would launch afterwards - against a live infobase, with nobody holding its key.
 * </p>
 * <p>
 * A run still waiting for a worker needs none of this, and that was measured rather than assumed:
 * a completed future makes the queued task return without ever calling its body.
 * </p>
 */
public class ARunIsStoppedBeforeItStartsTheClientTest
{
    private static final long PATIENCE_SEC = 30;

    @Test
    public void aRunCancelledBeforeItStartsTheClientLaunchesNothing() throws Exception
    {
        CountDownLatch begun = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch asked = new CountDownLatch(1);
        AtomicBoolean launched = new AtomicBoolean();
        String key = PendingWorkRegistry.computeRunKey("vanessa", //$NON-NLS-1$
            "begun-then-cancelled", String.valueOf(System.nanoTime())); //$NON-NLS-1$

        // Stands in for the run: it has begun, so its future no longer guards it, and it asks the
        // same question the job body asks before it builds or launches anything.
        PendingWorkRegistry.VANESSA.getOrStart(key, () -> {
            begun.countDown();
            try
            {
                cancelled.await(PATIENCE_SEC, TimeUnit.SECONDS);
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
            }
            String refused = VanessaTool.refusedBeforeLaunch(key);
            if (refused == null)
            {
                launched.set(true);
            }
            asked.countDown();
            return refused == null ? "{}" : refused; //$NON-NLS-1$
        });
        assertTrue("the run has to be executing for this to be the window under test", //$NON-NLS-1$
            begun.await(PATIENCE_SEC, TimeUnit.SECONDS));

        String answer = VanessaTool.cancelRun(key);
        cancelled.countDown();

        assertTrue("a run that has begun is a run to cancel: " + answer, //$NON-NLS-1$
            answer.contains("Cancelled")); //$NON-NLS-1$
        assertTrue("no client exists yet, and the answer claims no more than that: " + answer, //$NON-NLS-1$
            answer.contains("No client was found")); //$NON-NLS-1$
        assertTrue("a client that had already exited looks the same from there, and the " //$NON-NLS-1$
            + "answer says so rather than promising the infobase is untouched: " + answer, //$NON-NLS-1$
            answer.contains("not a promise that none ran")); //$NON-NLS-1$
        assertTrue("the run carries on - completing its future does not interrupt it", //$NON-NLS-1$
            asked.await(PATIENCE_SEC, TimeUnit.SECONDS));
        assertFalse("it carried on, read the mark, and launched nothing", launched.get()); //$NON-NLS-1$
        assertNull("the mark is spent, so a later run under this key is not refused by it", //$NON-NLS-1$
            VanessaTool.refusedBeforeLaunch(key));
    }

    @Test
    public void aCancelThatFindsNothingLeavesNoMarkBehind()
    {
        String unknown = PendingWorkRegistry.computeRunKey("vanessa", //$NON-NLS-1$
            "unknown", String.valueOf(System.nanoTime())); //$NON-NLS-1$

        String answer = VanessaTool.cancelRun(unknown);

        assertTrue("there is nothing under this key, and saying so is the answer: " + answer, //$NON-NLS-1$
            answer.contains("No run under runKey")); //$NON-NLS-1$
        assertNull("a mark left here would refuse a later run that was never cancelled", //$NON-NLS-1$
            VanessaTool.refusedBeforeLaunch(unknown));
    }

    @Test
    public void aRunWithNoKeyIsNeverRefused()
    {
        assertNull("a synchronous run carries no key and nobody can cancel it", //$NON-NLS-1$
            VanessaTool.refusedBeforeLaunch(null));
    }
}
