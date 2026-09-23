/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * Two writes of the infobase list wait for each other.
 *
 * <p>The guard brackets a write with a snapshot and a restore, and all three run under one lock.
 * Without it a second write snapshots while the first write's strip is still un-restored: the
 * second snapshot is empty, the second write protects nothing, and the first answer has already
 * claimed a restore the second write then strips again. The first thread below parks inside its
 * guarded region; the second can only snapshot after the first restore, so its snapshot has to
 * see the id put back. Without the lock the second snapshot lands in the park, while the store
 * is stripped, and the assertion fails.</p>
 */
public class TwoInfobaseListWritesWaitForEachOtherTest
{
    @Test
    public void theSecondWriteSnapshotsTheIdsTheFirstWriteRestored() throws Exception
    {
        FakeLaunchConfigurations fake = new FakeLaunchConfigurations();
        fake.add("m-one", "Run one", "project-one", "application-one"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        LaunchApplicationIds.Access access = LaunchConfigAccess.applicationIdAccess(fake.manager());
        CountDownLatch firstWriteStripped = new CountDownLatch(1);
        CountDownLatch secondSnapshotTaken = new CountDownLatch(1);
        AtomicReference<Map<String, LaunchApplicationIds.SnapshotEntry>> secondHeld =
            new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread first = new Thread(() -> {
            try
            {
                underWriteLock(() -> {
                    LaunchApplicationIds.SnapshotResult snapshot = LaunchApplicationIds.snapshot(access);
                    fake.stripApplicationIds();
                    firstWriteStripped.countDown();
                    await(secondSnapshotTaken);
                    LaunchApplicationIds.restore(access, snapshot.held);
                });
            }
            catch (Throwable t)
            {
                failure.compareAndSet(null, t);
            }
        });
        Thread second = new Thread(() -> {
            try
            {
                await(firstWriteStripped);
                underWriteLock(() -> {
                    secondHeld.set(LaunchApplicationIds.snapshot(access).held);
                    secondSnapshotTaken.countDown();
                });
            }
            catch (Throwable t)
            {
                failure.compareAndSet(null, t);
            }
        });
        first.start();
        second.start();
        first.join(10000);
        second.join(10000);

        assertNull(failure.get());
        assertFalse("the first write is done", first.isAlive()); //$NON-NLS-1$
        assertFalse("the second write is done", second.isAlive()); //$NON-NLS-1$
        assertNotNull(secondHeld.get());
        assertNotNull("the second snapshot sees the id the first write restored", //$NON-NLS-1$
            secondHeld.get().get("m-one")); //$NON-NLS-1$
        assertEquals("application-one", secondHeld.get().get("m-one").applicationId); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Runs one guarded write: the lock is what the product holds across snapshot, write, restore. */
    private static void underWriteLock(Runnable guarded)
    {
        LaunchApplicationIds.WRITE_LOCK.lock();
        try
        {
            guarded.run();
        }
        finally
        {
            LaunchApplicationIds.WRITE_LOCK.unlock();
        }
    }

    /** Waits briefly: with the lock held the awaited signal can only come after the release. */
    private static void await(CountDownLatch latch)
    {
        try
        {
            latch.await(2, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }
}
