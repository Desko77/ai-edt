/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * Stopping tracking without a runKey, and what it must not take with it.
 * <p>
 * A refused call never returns a runKey, which left the only exit addressed by something the
 * caller could not obtain. Addressing it by subject fixes that. The thing to get right is that a
 * finished result nobody has collected survives, because it carries the error text of a failed run
 * and that text is why a caller comes back for it.
 * </p>
 */
public class AnExitThatNeedsNoRunKeyTest
{
    private static final String SUBJECT = "AnExitThatNeedsNoRunKeyTest-project"; //$NON-NLS-1$

    /**
     * The shared registry, which is the only one a test can reach - the constructors are private.
     * <p>
     * Safe because every key and the subject below are unique to this test, and
     * {@code stopTrackingFor} touches nothing that does not carry that exact subject.
     * </p>
     */
    private static PendingWorkRegistry aRegistry(String unusedName)
    {
        return PendingWorkRegistry.UPDATE;
    }

    private static PendingWorkRegistry.PendingEntry aRunThatBlocks(PendingWorkRegistry registry,
        String runKey, CountDownLatch started, CountDownLatch release)
    {
        return registry.getOrStart(runKey, () -> {
            started.countDown();
            try
            {
                release.await(20, TimeUnit.SECONDS);
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
            }
            return "done"; //$NON-NLS-1$
        });
    }

    @Test
    public void aRunStillGoingStopsBeingTracked() throws Exception
    {
        PendingWorkRegistry registry = aRegistry("stop-tracking"); //$NON-NLS-1$
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try
        {
            PendingWorkRegistry.PendingEntry entry =
                aRunThatBlocks(registry, "exit-test-k1", started, release); //$NON-NLS-1$
            entry.subject = SUBJECT;
            assertTrue("the work has to be running before it can be detached", //$NON-NLS-1$
                started.await(20, TimeUnit.SECONDS));

            assertEquals("one run stops being tracked", 1, registry.stopTrackingFor(SUBJECT)); //$NON-NLS-1$
            assertEquals("and only once", 0, registry.stopTrackingFor(SUBJECT)); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
        }
    }

    @Test
    public void aFinishedResultNobodyCollectedIsLeftAlone() throws Exception
    {
        PendingWorkRegistry registry = aRegistry("keep-result"); //$NON-NLS-1$

        PendingWorkRegistry.PendingEntry entry =
            registry.getOrStart("exit-test-k2", () -> "the platform said no"); //$NON-NLS-1$ //$NON-NLS-2$
        entry.subject = SUBJECT;
        assertNotNull("let it finish", entry.await(20000)); //$NON-NLS-1$

        assertEquals("a finished result must not be swept away", 0, //$NON-NLS-1$
            registry.stopTrackingFor(SUBJECT));

        PendingWorkRegistry.PendingEntry again =
            registry.getOrStart("exit-test-k2", () -> "recomputed"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("and its text is still there to collect", "the platform said no", //$NON-NLS-1$ //$NON-NLS-2$
            again.await(20000));
    }

    @Test
    public void anotherSubjectIsNotTouched() throws Exception
    {
        PendingWorkRegistry registry = aRegistry("other-subject"); //$NON-NLS-1$
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try
        {
            PendingWorkRegistry.PendingEntry entry =
                aRunThatBlocks(registry, "exit-test-k3", started, release); //$NON-NLS-1$
            entry.subject = SUBJECT;
            assertTrue(started.await(20, TimeUnit.SECONDS));

            assertEquals("a different project stops nothing", 0, //$NON-NLS-1$
                registry.stopTrackingFor("AnExitThatNeedsNoRunKeyTest-elsewhere")); //$NON-NLS-1$
            assertEquals("and no subject at all stops nothing", 0, //$NON-NLS-1$
                registry.stopTrackingFor(null));
        }
        finally
        {
            release.countDown();
        }
    }

    @Test
    public void anUntaggedRunStaysUnaddressableExactlyAsBefore() throws Exception
    {
        PendingWorkRegistry registry = aRegistry("untagged"); //$NON-NLS-1$
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try
        {
            aRunThatBlocks(registry, "exit-test-k4", started, release); //$NON-NLS-1$
            assertTrue(started.await(20, TimeUnit.SECONDS));

            assertEquals("nothing tagged it, so nothing addresses it this way", 0, //$NON-NLS-1$
                registry.stopTrackingFor(SUBJECT));
        }
        finally
        {
            release.countDown();
        }
    }
}
