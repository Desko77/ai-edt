/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

/**
 * Verifies the mutation lock actually serialises: a second thread cannot hold it while a first does,
 * a single thread may re-enter it, and it always frees again. This is what makes a batch of edits
 * uninterruptible by another edit.
 */
public class MetadataMutationLockTest
{
    @Test
    public void acquireReleaseTogglesHeld()
    {
        assertFalse(MetadataMutationLock.isHeld());
        MetadataMutationLock.acquire();
        try
        {
            assertTrue(MetadataMutationLock.isHeld());
        }
        finally
        {
            MetadataMutationLock.release();
        }
        assertFalse(MetadataMutationLock.isHeld());
    }

    @Test
    public void reentrantOnTheSameThread()
    {
        MetadataMutationLock.acquire();
        MetadataMutationLock.acquire();
        try
        {
            assertTrue(MetadataMutationLock.isHeld());
        }
        finally
        {
            MetadataMutationLock.release();
            MetadataMutationLock.release();
        }
        assertFalse(MetadataMutationLock.isHeld());
    }

    @Test
    public void aSecondThreadWaitsUntilTheFirstReleases() throws Exception
    {
        CountDownLatch firstHolds = new CountDownLatch(1);
        CountDownLatch allowFirstRelease = new CountDownLatch(1);
        AtomicBoolean secondAcquired = new AtomicBoolean(false);
        CountDownLatch secondDone = new CountDownLatch(1);

        Thread first = new Thread(() ->
        {
            MetadataMutationLock.acquire();
            try
            {
                firstHolds.countDown();
                allowFirstRelease.await(5, TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            finally
            {
                MetadataMutationLock.release();
            }
        });
        Thread second = new Thread(() ->
        {
            MetadataMutationLock.acquire();
            try
            {
                secondAcquired.set(true);
                secondDone.countDown();
            }
            finally
            {
                MetadataMutationLock.release();
            }
        });

        first.start();
        assertTrue("first must take the lock", firstHolds.await(5, TimeUnit.SECONDS)); //$NON-NLS-1$
        second.start();
        Thread.sleep(200); // give the second thread a chance to (wrongly) proceed
        assertFalse("the second thread must not hold the lock while the first does", //$NON-NLS-1$
            secondAcquired.get());

        allowFirstRelease.countDown();
        assertTrue("the second thread acquires once the first releases", //$NON-NLS-1$
            secondDone.await(5, TimeUnit.SECONDS));
        first.join(2000);
        second.join(2000);
        assertFalse(MetadataMutationLock.isHeld());
    }
}
