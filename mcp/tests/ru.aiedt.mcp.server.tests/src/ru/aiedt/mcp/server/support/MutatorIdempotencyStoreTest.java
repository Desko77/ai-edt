/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.Test;

/**
 * Verifies the at-most-once contract: a repeat replays without re-running, a
 * concurrent duplicate coalesces, a failure is evicted so a retry re-runs, the
 * owner's throw propagates, a slow duplicate times out with a retry note, and the
 * TTL prune drops a cached success.
 */
public class MutatorIdempotencyStoreTest
{
    private static final String OK = "{\"success\":true}"; //$NON-NLS-1$

    @Test
    public void secondCallSameKeyReplaysWithoutRerunning()
    {
        MutatorIdempotencyStore store = new MutatorIdempotencyStore();
        AtomicInteger runs = new AtomicInteger();
        Supplier<String> work = () -> { runs.incrementAndGet(); return OK; };
        String r1 = store.call("k1", work, 1000L); //$NON-NLS-1$
        String r2 = store.call("k1", work, 1000L); //$NON-NLS-1$
        assertEquals(1, runs.get());
        assertEquals(r1, r2);
    }

    @Test
    public void differentKeysRunIndependently()
    {
        MutatorIdempotencyStore store = new MutatorIdempotencyStore();
        AtomicInteger runs = new AtomicInteger();
        Supplier<String> work = () -> { runs.incrementAndGet(); return OK; };
        store.call("k1", work, 1000L); //$NON-NLS-1$
        store.call("k2", work, 1000L); //$NON-NLS-1$
        assertEquals(2, runs.get());
    }

    @Test
    public void failedResultIsEvictedSoRetryReruns()
    {
        MutatorIdempotencyStore store = new MutatorIdempotencyStore();
        AtomicInteger runs = new AtomicInteger();
        Supplier<String> work = () -> { runs.incrementAndGet(); return "{\"success\":false}"; }; //$NON-NLS-1$
        store.call("k1", work, 1000L); //$NON-NLS-1$
        store.call("k1", work, 1000L); //$NON-NLS-1$
        assertEquals(2, runs.get());
    }

    @Test
    public void errorPrefixResultIsEvicted()
    {
        MutatorIdempotencyStore store = new MutatorIdempotencyStore();
        AtomicInteger runs = new AtomicInteger();
        Supplier<String> work = () -> { runs.incrementAndGet(); return "Error: oldSource not found"; }; //$NON-NLS-1$
        store.call("k1", work, 1000L); //$NON-NLS-1$
        store.call("k1", work, 1000L); //$NON-NLS-1$
        assertEquals(2, runs.get());
    }

    @Test
    public void thrownWorkIsEvictedAndPropagates()
    {
        MutatorIdempotencyStore store = new MutatorIdempotencyStore();
        AtomicInteger runs = new AtomicInteger();
        Supplier<String> work = () -> { runs.incrementAndGet(); throw new RuntimeException("boom"); }; //$NON-NLS-1$
        try
        {
            store.call("k1", work, 1000L); //$NON-NLS-1$
            fail("expected the owner's exception to propagate"); //$NON-NLS-1$
        }
        catch (RuntimeException ex)
        {
            assertEquals("boom", ex.getMessage()); //$NON-NLS-1$
        }
        try
        {
            store.call("k1", work, 1000L); //$NON-NLS-1$
            fail("expected re-run after a thrown failure"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            // second run re-threw
        }
        assertEquals(2, runs.get());
    }

    @Test
    public void concurrentDuplicateDoesNotRunItsOwnWork() throws Exception
    {
        MutatorIdempotencyStore store = new MutatorIdempotencyStore();
        AtomicInteger ownerRuns = new AtomicInteger();
        AtomicInteger duplicateRuns = new AtomicInteger();
        CountDownLatch ownerStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Supplier<String> ownerWork = () ->
        {
            ownerRuns.incrementAndGet();
            ownerStarted.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return OK;
        };
        Supplier<String> duplicateWork = () -> { duplicateRuns.incrementAndGet(); return OK; };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try
        {
            Future<String> owner = pool.submit(() -> store.call("k1", ownerWork, 5000L)); //$NON-NLS-1$
            assertTrue(ownerStarted.await(5, TimeUnit.SECONDS));
            Future<String> dup = pool.submit(() -> store.call("k1", duplicateWork, 5000L)); //$NON-NLS-1$
            Thread.sleep(150); // let the duplicate reach the wait
            release.countDown();
            String r1 = owner.get(5, TimeUnit.SECONDS);
            String r2 = dup.get(5, TimeUnit.SECONDS);
            assertEquals(1, ownerRuns.get());
            assertEquals("the duplicate must not run its own work", 0, duplicateRuns.get()); //$NON-NLS-1$
            assertEquals(r1, r2);
        }
        finally
        {
            pool.shutdownNow();
        }
    }

    @Test
    public void slowDuplicateTimesOutWithARetryNote() throws Exception
    {
        MutatorIdempotencyStore store = new MutatorIdempotencyStore();
        CountDownLatch ownerStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Supplier<String> ownerWork = () ->
        {
            ownerStarted.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return OK;
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try
        {
            Future<String> owner = pool.submit(() -> store.call("k1", ownerWork, 5000L)); //$NON-NLS-1$
            assertTrue(ownerStarted.await(5, TimeUnit.SECONDS));
            String dup = store.call("k1", () -> OK, 100L); // short wait -> times out //$NON-NLS-1$
            assertTrue("timeout note must be non-success", MutatorIdempotencyStore.looksFailed(dup)); //$NON-NLS-1$
            assertTrue(dup.contains("in_progress") || dup.contains("same")); //$NON-NLS-1$ //$NON-NLS-2$
            release.countDown();
            owner.get(5, TimeUnit.SECONDS);
        }
        finally
        {
            pool.shutdownNow();
        }
    }

    @Test
    public void pruneRemovesExpiredSuccess()
    {
        MutatorIdempotencyStore store = new MutatorIdempotencyStore();
        AtomicInteger runs = new AtomicInteger();
        Supplier<String> work = () -> { runs.incrementAndGet(); return OK; };
        store.call("k1", work, 1000L); //$NON-NLS-1$
        assertEquals(1, store.size());
        store.pruneExpired(System.currentTimeMillis() + MutatorIdempotencyStore.COMPLETED_TTL_MS + 5000L);
        assertEquals(0, store.size());
        store.call("k1", work, 1000L); //$NON-NLS-1$
        assertEquals(2, runs.get());
    }

    @Test
    public void looksFailedClassifiesKnownFailureShapes()
    {
        assertTrue(MutatorIdempotencyStore.looksFailed("{\"success\":false}")); //$NON-NLS-1$
        assertTrue(MutatorIdempotencyStore.looksFailed("{\"success\": false}")); //$NON-NLS-1$
        assertTrue(MutatorIdempotencyStore.looksFailed("Error: boom")); //$NON-NLS-1$
        assertTrue(MutatorIdempotencyStore.looksFailed("   Error: boom")); //$NON-NLS-1$
        assertTrue(MutatorIdempotencyStore.looksFailed("Failed while writing the file: x")); //$NON-NLS-1$
        assertTrue(MutatorIdempotencyStore.looksFailed("---\nstatus: error\n---\nboom")); //$NON-NLS-1$
        assertTrue(MutatorIdempotencyStore.looksFailed(null));
    }

    @Test
    public void looksFailedDoesNotFlagSuccesses()
    {
        assertFalse(MutatorIdempotencyStore.looksFailed(OK));
        assertFalse(MutatorIdempotencyStore.looksFailed("## Written\n\nModule updated.")); //$NON-NLS-1$
        assertFalse(MutatorIdempotencyStore.looksFailed(
            "---\nstatus: success\n---\nOperation completed successfully.")); //$NON-NLS-1$
    }

    @Test
    public void keyIsInjective()
    {
        assertNotEquals(MutatorIdempotency.key("ab", "c:d"), //$NON-NLS-1$ //$NON-NLS-2$
            MutatorIdempotency.key("ab:c", "d")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(MutatorIdempotency.key("x", "y"), //$NON-NLS-1$ //$NON-NLS-2$
            MutatorIdempotency.key("x", "y")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
