/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The closing of the last editor frees what its look at the file's lock took. The look -
 * {@code release()} probing the lock before it drops the registry entry - can take the lock
 * beside a thread that waits for it, and a taking without a giving back leaves the lock held
 * by a thread that is done with it, so every later state change of the file would wait
 * forever. Here a waiter sits in the lock's queue while the closing runs, which is the one
 * state in which the look takes the lock although it must keep the entry.
 */
public class ALastEditorsReleaseNeverLeavesTheLockHeldTest
{
    private static final String PROJECT = "AiEdtOformSessionReleaseLockProbe"; //$NON-NLS-1$

    private static SessionProbe probe;

    @BeforeClass
    public static void aProjectWithOneOrdinaryForm() throws Exception
    {
        probe = SessionProbe.with(PROJECT, SessionProbe.fixture("fixtures/item-form-v27.form"), "\r\n"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @AfterClass
    public static void theProjectGoes()
    {
        if (probe != null)
        {
            probe.dispose();
        }
    }

    /** The closing of the last editor gives the lock back even when a thread waits for it. */
    @Test
    public void theLastEditorsClosingFreesTheHoldItsProbeOfTheLockTook() throws Exception
    {
        FormEditingSession session = OpenFormSessions.open(probe.file());
        try
        {
            OpenFormSessions.SessionLock lock = OpenFormSessions.lockOf(probe.file());
            lock.lock();
            CountDownLatch taken = new CountDownLatch(1);
            CountDownLatch freed = new CountDownLatch(1);
            Thread waiter = new Thread(() -> {
                OpenFormSessions.SessionLock own = OpenFormSessions.lockOf(probe.file());
                own.lock();
                try
                {
                    taken.countDown();
                }
                finally
                {
                    own.unlock();
                    freed.countDown();
                }
            }, "oform-release-lock-waiter"); //$NON-NLS-1$
            waiter.setDaemon(true);
            try
            {
                waiter.start();
                assertTrue("the waiter never reached the lock's queue", queued(lock)); //$NON-NLS-1$

                OpenFormSessions.release(session);
                lock.unlock();

                assertTrue("another thread must be able to take the lock after the closing", //$NON-NLS-1$
                    taken.await(60, TimeUnit.SECONDS));
                assertTrue("the waiter holds nothing behind it", freed.await(60, TimeUnit.SECONDS)); //$NON-NLS-1$
                assertFalse(OpenFormSessions.isHeld(probe.file()));
                assertEquals(0, OpenFormSessions.editorsOf(probe.file()));
            }
            finally
            {
                waiter.join(60000);
                waiter.interrupt();
            }
        }
        finally
        {
            OpenFormSessions.release(session);
        }
    }

    /** Waits until a thread sits in the lock's queue. */
    private static boolean queued(OpenFormSessions.SessionLock lock) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (!lock.hasQueuedThreads())
        {
            if (System.currentTimeMillis() >= deadline)
            {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }
}
