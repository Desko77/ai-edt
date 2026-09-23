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
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * Two writes of the infobase list wait for each other.
 *
 * <p>The guard brackets a write with a snapshot and a restore, and all three run under one lock,
 * which every infobase-list write takes through
 * {@link LaunchApplicationIds#underWriteLock(LaunchApplicationIds.Access, LaunchApplicationIds.GuardedWrite)}.
 * Without it a second write snapshots while the first write's strip is still un-restored: the
 * second snapshot is empty, the second write protects nothing, and the first answer has already
 * claimed a restore the second write then strips again. The first thread below parks inside its
 * guarded region; the second can only snapshot after the first restore, so its snapshot has to
 * see the id put back. Without the lock the second snapshot lands in the park, while the store is
 * stripped, and the assertion fails.</p>
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
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        CountDownLatch secondWriteDone = new CountDownLatch(1);
        AtomicReference<Map<String, LaunchApplicationIds.SnapshotEntry>> secondHeld =
            new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread first = new Thread(() -> {
            try
            {
                // The operation's path: the snapshot, the write and the restore, all under the
                // one lock the operations take.
                LaunchApplicationIds.underWriteLock(access, snapshot -> {
                    fake.stripApplicationIds();
                    firstWriteStripped.countDown();
                    await(releaseFirstWrite, failure);
                    LaunchApplicationIds.restore(access, snapshot.held);
                    return null;
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
                LaunchApplicationIds.underWriteLock(access, snapshot -> {
                    secondHeld.set(snapshot.held);
                    return null;
                });
                secondWriteDone.countDown();
            }
            catch (Throwable t)
            {
                failure.compareAndSet(null, t);
            }
        });
        first.start();
        assertTrue("the first write reached its guarded region", //$NON-NLS-1$
            firstWriteStripped.await(2, TimeUnit.SECONDS));
        second.start();
        assertFalse("the second write waits for the first to finish", //$NON-NLS-1$
            secondWriteDone.await(500, TimeUnit.MILLISECONDS));
        releaseFirstWrite.countDown();
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

    /** Waits for the signal; a timeout is a failure - the signal is owed, not hoped for. */
    private static void await(CountDownLatch latch, AtomicReference<Throwable> failure)
    {
        try
        {
            if (!latch.await(2, TimeUnit.SECONDS))
            {
                failure.compareAndSet(null, new AssertionError("the first write was never released")); //$NON-NLS-1$
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            failure.compareAndSet(null, e);
        }
    }
}
