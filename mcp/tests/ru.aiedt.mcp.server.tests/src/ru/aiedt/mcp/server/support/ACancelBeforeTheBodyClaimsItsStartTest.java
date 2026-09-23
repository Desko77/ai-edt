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

import org.junit.After;
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
    @After
    public void theGateGoes()
    {
        PendingWorkRegistry.beforeWorkClaim = null;
    }

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
        CountDownLatch pastTheGate = new CountDownLatch(1);
        PendingWorkRegistry.beforeWorkClaim = job -> {
            seen.set(job);
            entered.countDown();
            try
            {
                release.await(20, TimeUnit.SECONDS);
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
            }
            pastTheGate.countDown();
        };
        String key = "poll-claim-" + System.nanoTime(); //$NON-NLS-1$
        Thread starter = new Thread(() -> PendingWorkRegistry.REFERENCES.getOrStart(key, job -> {
            permitsWhenTheBodyStarted.set(permits.availablePermits());
            bodyRan.set(true);
            return "ran"; //$NON-NLS-1$
        }));
        starter.setDaemon(true);
        try
        {
            starter.start();
            assertTrue("the supplier never entered", entered.await(15, TimeUnit.SECONDS)); //$NON-NLS-1$
            PendingWorkRegistry.PendingEntry entry = seen.get();
            assertNotNull("the supplier entered without an entry", entry); //$NON-NLS-1$
            ToolRoad.Ticket ticket = new ToolRoad.Ticket(permits::release);
            assertTrue("the ticket was not the one handed to the entry", ticket.transferTo(entry)); //$NON-NLS-1$
            assertEquals(0, permits.availablePermits());
            assertTrue(entry.future.cancel(false));
            release.countDown();
            assertTrue(pastTheGate.await(10, TimeUnit.SECONDS));
            Thread.sleep(200L);
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
            PendingWorkRegistry.beforeWorkClaim = null;
            PendingWorkRegistry.REFERENCES.remove(key);
            starter.join(15_000L);
        }
    }
}
