/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.support;

import static org.junit.Assert.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class TemplateMutationLockTest
{
    @Test public void sameCanonicalIdentityCannotReadUntilRefreshCompletes() throws Exception
    {
        TemplateMutationLock locks = new TemplateMutationLock();
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicInteger stored = new AtomicInteger();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Integer> second;
        try (TemplateMutationLock.Lease first = locks.acquire("project:42", 1000))
        {
            stored.set(1); // first mutation and serialization
            second = pool.submit(() -> {
                waiting.countDown();
                try (TemplateMutationLock.Lease lease = locks.acquire("project:42", 1000))
                {
                    return stored.incrementAndGet(); // next read/modify/persist
                }
            });
            assertTrue(waiting.await(1, TimeUnit.SECONDS));
            try { second.get(30, TimeUnit.MILLISECONDS); fail("read crossed persistence/refresh boundary"); }
            catch (TimeoutException expected) { }
            stored.set(2); // refresh/export acknowledgement still owned by first
        }
        try { assertEquals(Integer.valueOf(3), second.get(1, TimeUnit.SECONDS)); }
        finally { pool.shutdownNow(); }
        assertEquals(0, locks.entryCount());
    }

    @Test public void aSiblingTemplateCannotStartUntilTheOwnersExportIsAcknowledged() throws Exception
    {
        TemplateMutationLock locks = new TemplateMutationLock();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch attempting = new CountDownLatch(1);
        AtomicInteger exportedRevision = new AtomicInteger();
        Future<Integer> sibling;
        try (TemplateMutationLock.Lease first = locks.acquire("project:owner42", 1000))
        {
            sibling = pool.submit(() -> {
                attempting.countDown();
                try (TemplateMutationLock.Lease next = locks.acquire("project:owner42", 1000))
                { return exportedRevision.get(); }
            });
            assertTrue(attempting.await(1, TimeUnit.SECONDS));
            try { sibling.get(30, TimeUnit.MILLISECONDS); fail("sibling crossed shared owner export"); }
            catch (TimeoutException expected) { }
            exportedRevision.set(7);
        }
        try { assertEquals(Integer.valueOf(7), sibling.get(1, TimeUnit.SECONDS)); }
        finally { pool.shutdownNow(); }
        assertEquals(0, locks.entryCount());
    }

    @Test public void unrelatedTemplatesRemainIndependent() throws Exception
    {
        TemplateMutationLock locks = new TemplateMutationLock();
        try (TemplateMutationLock.Lease one = locks.acquire("project:1", 1000);
             TemplateMutationLock.Lease two = locks.acquire("project:2", 1))
        { assertEquals(2, locks.entryCount()); }
        assertEquals(0, locks.entryCount());
    }

    @Test public void timeoutAndInterruptedWaitReleaseTheirReferences() throws Exception
    {
        TemplateMutationLock locks = new TemplateMutationLock();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (TemplateMutationLock.Lease held = locks.acquire("project:1", 1000))
        {
            assertTrue(pool.submit(() -> {
                try { locks.acquire("project:1", 1); return false; }
                catch (TimeoutException expected) { return true; }
            }).get(1, TimeUnit.SECONDS));
            assertEquals(1, locks.entryCount());
        }
        finally { pool.shutdownNow(); }
        assertEquals(0, locks.entryCount());
    }
}
