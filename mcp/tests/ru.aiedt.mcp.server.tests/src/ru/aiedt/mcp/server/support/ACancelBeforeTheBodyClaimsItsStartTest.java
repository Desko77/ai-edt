/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * A cancel that arrives after the supplier has been entered and before it has claimed the start
 * does not run the body, and does not return a permit while a body is still running.
 * <p>
 * The old settlement decided "the work never began" under the lock, dropped the lock, and then
 * returned the permit. The supplier could claim the start in that gap and run with the permit
 * already back.
 * </p>
 */
public class ACancelBeforeTheBodyClaimsItsStartTest
{
    @Test
    public void aCancelBeforeTheSupplierClaimsDoesNotRunTheBody() throws Exception
    {
        AtomicBoolean bodyRan = new AtomicBoolean();
        AtomicInteger permitsWhenTheBodyStarted = new AtomicInteger(-1);
        AtomicReference<PendingWorkRegistry.PendingEntry> seen = new AtomicReference<>();
        Semaphore permits = new Semaphore(1);
        assertTrue(permits.tryAcquire());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // One private worker lets a queued marker prove the supplier has passed its claim,
        // including the path where cancellation prevents the body (and its finally) entirely.
        var constructor = PendingWorkRegistry.class.getDeclaredConstructor(String.class, String.class, int.class);
        constructor.setAccessible(true);
        PendingWorkRegistry registry = constructor.newInstance("claim-test", "claim-test", 1);
        var executorField = PendingWorkRegistry.class.getDeclaredField("executor");
        executorField.setAccessible(true);
        java.util.concurrent.ExecutorService executor =
            (java.util.concurrent.ExecutorService)executorField.get(registry);
        String key = "poll-claim-" + System.nanoTime(); //$NON-NLS-1$
        java.util.function.Consumer<PendingWorkRegistry.PendingEntry> previous = PendingWorkRegistry.beforeWorkClaim;
        PendingWorkRegistry.beforeWorkClaim = job -> {
            if (!key.equals(job.runKey))
            {
                if (previous != null) { previous.accept(job); }
                return;
            }
            seen.set(job);
            entered.countDown();
            try
            {
                assertTrue("the claim gate was not released", release.await(20, TimeUnit.SECONDS));
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
            }
        };
        Thread starter = new Thread(() -> registry.getOrStart(key, job -> {
            permitsWhenTheBodyStarted.set(permits.availablePermits());
            bodyRan.set(true);
            return "ran"; //$NON-NLS-1$
        }));
        starter.setDaemon(true);
        try
        {
            starter.start();
            assertTrue("the supplier never entered", entered.await(15, TimeUnit.SECONDS)); //$NON-NLS-1$
            // Entering the hook publishes the entry before supplyAsync's return is assigned to
            // entry.future. Wait for getOrStart to finish publishing, without opening the gate.
            starter.join(15_000L);
            assertFalse("the starter did not publish the future", starter.isAlive());
            PendingWorkRegistry.PendingEntry entry = seen.get();
            assertNotNull("the supplier entered without an entry", entry); //$NON-NLS-1$
            assertNotNull("the future was not published", entry.future);
            ToolRoad.Ticket ticket = new ToolRoad.Ticket(permits::release);
            assertTrue("the ticket was not the one handed to the entry", ticket.transferTo(entry)); //$NON-NLS-1$
            assertEquals(0, permits.availablePermits());
            assertTrue(entry.future.cancel(false));
            release.countDown();
            executor.submit(() -> { }).get(10, TimeUnit.SECONDS);
            if (bodyRan.get())
            {
                assertEquals("the permit came back while the body was still to run", //$NON-NLS-1$
                    0, permitsWhenTheBodyStarted.get());
            }
            assertFalse("a cancel before the body claimed its start still ran the body", //$NON-NLS-1$
                bodyRan.get());
            assertEquals("the permit was not returned once the body was not going to run", //$NON-NLS-1$
                1, permits.availablePermits());
        }
        finally
        {
            release.countDown();
            try
            {
                starter.join(15_000L);
                executor.shutdown();
                assertTrue("the private worker did not leave", executor.awaitTermination(15, TimeUnit.SECONDS));
                assertFalse("the starter did not leave", starter.isAlive());
            }
            finally
            {
                executor.shutdownNow();
                PendingWorkRegistry.beforeWorkClaim = previous;
                registry.remove(key);
            }
        }
    }
}
