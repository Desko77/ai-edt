/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * An entry belongs to one run, and both its removal and its age have to know that.
 * <p>
 * Removing by key alone removed whatever held the key at that moment, which after a collection is
 * a different run: the caller that collected a finished result deleted the entry a second caller
 * had just started, and that run then executed with nothing tracking it - nothing to poll, nothing
 * to cancel.
 * </p>
 * <p>
 * Age had the same shape of mistake. It was counted from submission, so a run that waited behind a
 * long one was called abandoned while it was still executing.
 * </p>
 */
public class AnEntryBelongsToOneRunTest
{
    private static String freshKey(String what)
    {
        return PendingWorkRegistry.computeRunKey(what, String.valueOf(System.nanoTime()));
    }

    @Test
    public void removingAnEntryThatIsNoLongerThereChangesNothing() throws Exception
    {
        PendingWorkRegistry domain = PendingWorkRegistry.GENERIC;
        String key = freshKey("removed-by-identity"); //$NON-NLS-1$
        PendingWorkRegistry.PendingEntry first = domain.getOrStart(key, () -> "{}"); //$NON-NLS-1$
        assertNotNull(first.await(10_000L));

        // The first caller collects, and a second run starts under the same key.
        assertTrue("the entry it read is the entry it removes", domain.remove(key, first)); //$NON-NLS-1$
        PendingWorkRegistry.PendingEntry second = domain.getOrStart(key, () -> "{}"); //$NON-NLS-1$
        assertNotSame("a new run, not the one already collected", first, second); //$NON-NLS-1$

        assertFalse("a late removal of the old entry must not take the new run's", //$NON-NLS-1$
            domain.remove(key, first));
        assertNotNull("the new run is still tracked", domain.get(key)); //$NON-NLS-1$

        domain.remove(key, second);
    }

    @Test
    public void removingNothingIsRefusedRatherThanGuessed()
    {
        assertFalse(PendingWorkRegistry.GENERIC.remove(null, null));
        assertFalse(PendingWorkRegistry.GENERIC.remove(freshKey("nothing"), null)); //$NON-NLS-1$
    }

    @Test
    public void ageIsCountedFromWhenTheWorkBegan() throws Exception
    {
        String key = freshKey("age-from-execution"); //$NON-NLS-1$
        PendingWorkRegistry.PendingEntry entry =
            PendingWorkRegistry.GENERIC.getOrStart(key, () -> "{}"); //$NON-NLS-1$
        assertNotNull(entry.await(10_000L));

        assertTrue("the work ran, so it recorded when it began", entry.beganAt > 0); //$NON-NLS-1$
        assertTrue("it cannot have begun before it was submitted", //$NON-NLS-1$
            entry.beganAt >= entry.startedAt);

        PendingWorkRegistry.GENERIC.remove(key, entry);
    }

    @Test
    public void aDomainIsBusyOnlyWhileWorkIsUnfinished() throws Exception
    {
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch running = new java.util.concurrent.CountDownLatch(1);
        String key = freshKey("busy-while-unfinished"); //$NON-NLS-1$
        PendingWorkRegistry.PendingEntry entry;
        try
        {
            entry = PendingWorkRegistry.VANESSA.getOrStart(key, () -> {
                running.countDown();
                try
                {
                    hold.await(20, java.util.concurrent.TimeUnit.SECONDS);
                }
                catch (InterruptedException interrupted)
                {
                    Thread.currentThread().interrupt();
                }
                return "{}"; //$NON-NLS-1$
            });
            assertTrue(running.await(20, java.util.concurrent.TimeUnit.SECONDS));

            assertTrue("work that has not finished is what busy means", //$NON-NLS-1$
                PendingWorkRegistry.VANESSA.isBusy());
        }
        finally
        {
            hold.countDown();
        }

        assertNotNull(entry.await(20_000L));
        PendingWorkRegistry.VANESSA.remove(key, entry);
        assertFalse("a finished run does not hold the domain", //$NON-NLS-1$
            PendingWorkRegistry.VANESSA.isBusy());
    }

}
