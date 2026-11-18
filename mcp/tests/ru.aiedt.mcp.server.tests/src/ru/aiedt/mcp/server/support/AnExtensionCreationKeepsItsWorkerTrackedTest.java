/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.support;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** The creation domain must not forget a native call which has not returned. */
public class AnExtensionCreationKeepsItsWorkerTrackedTest
{
    @Test
    public void creationHasItsOwnRegisteredMutationDomain() throws Exception
    {
        PendingWorkRegistry domain = creationDomain();
        assertEquals("create_extension_project", domain.domain());
        assertTrue(PendingWorkRegistry.domains().contains(domain));
        assertNotSame(PendingWorkRegistry.GENERIC, domain);
        assertTrue(domain.stopsItsWork());
    }

    @Test
    public void expiredObservationDoesNotEvictAnAliveCreation() throws Exception
    {
        PendingWorkRegistry domain = creationDomain();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch leave = new CountDownLatch(1);
        String key = PendingWorkRegistry.computeRunKey("extension-test", Long.toString(System.nanoTime()));
        PendingWorkRegistry.PendingEntry entry = domain.getOrStart(key, () -> {
            entered.countDown();
            try
            {
                if (!leave.await(5, TimeUnit.SECONDS))
                {
                    return "deadline";
                }
                return "finished";
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
                return "interrupted";
            }
        });
        try
        {
            assertTrue("worker must enter bounded fixture", entered.await(2, TimeUnit.SECONDS));
            entry.beganAt = System.currentTimeMillis() - domain.abandonedTtlMs() - 1;
            domain.pruneExpired();
            assertSame("a live native operation must remain addressable", entry, domain.get(key));
            assertSame("an identical request must not create a second worker", entry,
                domain.getOrStart(key, () -> "duplicate"));
        }
        finally
        {
            leave.countDown();
            assertEquals("finished", entry.await(2000));
            domain.remove(key, entry);
        }
        assertNull(domain.get(key));
    }

    private static PendingWorkRegistry creationDomain() throws Exception
    {
        try
        {
            return (PendingWorkRegistry) PendingWorkRegistry.class.getField("EXTENSION_CREATE").get(null);
        }
        catch (NoSuchFieldException missing)
        {
            fail("Extension creation has no owned Pending mutation domain");
            throw missing;
        }
    }
}
