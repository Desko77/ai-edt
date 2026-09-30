/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.Test;

/**
 * Verifies the canonical Pending wrapper: immediate work returns its result, slow
 * work hands back a resumable Pending response with the shared field set, a resume
 * poll drains the result, an unknown key errors cleanly, work that throws reaches a
 * terminal error (no forever-pending leak), and the registry flags oversized results
 * and evicts them on the shorter TTL.
 */
public class PendingExecutorTest
{
    /** Any domain instance works for POJO tests; keys are namespaced per test. */
    private static final PendingWorkRegistry REG = PendingWorkRegistry.EXPORT;

    private static String key(String label)
    {
        return PendingWorkRegistry.computeRunKey("PendingExecutorTest", label); //$NON-NLS-1$
    }

    /** A supplier that blocks on the latch, then returns the given result. */
    private static Supplier<String> blockingWork(CountDownLatch release, String result)
    {
        return () ->
        {
            try
            {
                release.await(10, TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            return result;
        };
    }

    /**
     * Blocks until the entry's {@code whenComplete} callback has run (it stamps
     * {@code completedAt} last, after {@code cachedResult} and {@code oversized}),
     * so a test may safely read those flags. Guards the race where
     * {@code await()} returns via {@code future.get()} before the callback fires.
     */
    private static void awaitCompleted(PendingWorkRegistry.PendingEntry entry) throws InterruptedException
    {
        for (int i = 0; i < 200 && entry.completedAt == 0; i++)
        {
            Thread.sleep(5);
        }
        assertTrue("entry should have completed", entry.completedAt > 0); //$NON-NLS-1$
    }

    @Test
    public void immediateCompletionReturnsResultNotPending()
    {
        String rk = key("immediate"); //$NON-NLS-1$
        String out = PendingExecutor.start(REG, "test_op", rk, 5000L, //$NON-NLS-1$
            () -> "{\"ok\":true}", null); //$NON-NLS-1$
        assertTrue("should be the work result", out.contains("\"ok\":true")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("must not be Pending", out.contains("\"status\":\"Pending\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("entry removed after retrieval", REG.get(rk)); //$NON-NLS-1$
    }

    @Test
    public void slowWorkReturnsPendingThenResumes() throws Exception
    {
        String rk = key("slow"); //$NON-NLS-1$
        CountDownLatch release = new CountDownLatch(1);
        try
        {
            String pending = PendingExecutor.start(REG, "test_op", rk, 150L, //$NON-NLS-1$
                blockingWork(release, "{\"done\":true}"), null); //$NON-NLS-1$

            assertTrue("status Pending", pending.contains("\"status\":\"Pending\"")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("carries runKey", pending.contains("\"runKey\":\"" + rk + "\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue("carries operation", pending.contains("\"operation\":\"test_op\"")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("carries waitedMs", pending.contains("\"waitedMs\":150")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("carries elapsedMs", pending.contains("\"elapsedMs\":")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("carries hint", pending.contains("\"hint\":")); //$NON-NLS-1$ //$NON-NLS-2$
            assertNotNull("entry still tracked while running", REG.get(rk)); //$NON-NLS-1$

            release.countDown();
            String result = PendingExecutor.resume(REG, "test_op", rk, 5000L, null); //$NON-NLS-1$
            assertTrue("resume yields the finished result", result.contains("\"done\":true")); //$NON-NLS-1$ //$NON-NLS-2$
            assertNull("entry removed after resume retrieval", REG.get(rk)); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
            REG.remove(rk);
        }
    }

    @Test
    public void pendingIncludesDomainFields() throws Exception
    {
        String rk = key("fields"); //$NON-NLS-1$
        CountDownLatch release = new CountDownLatch(1);
        try
        {
            String pending = PendingExecutor.start(REG, "export_object", rk, 100L, //$NON-NLS-1$
                blockingWork(release, "x"), //$NON-NLS-1$
                tr -> tr.put("projectName", "MyProj").put("outputPath", "C:/out.epf")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            assertTrue("domain field projectName", //$NON-NLS-1$
                pending.contains("\"projectName\":\"MyProj\"")); //$NON-NLS-1$
            assertTrue("domain field outputPath", //$NON-NLS-1$
                pending.contains("\"outputPath\":\"C:/out.epf\"")); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
            REG.remove(rk);
        }
    }

    @Test
    public void resumeUnknownKeyReturnsError()
    {
        String out = PendingExecutor.resume(REG, "test_op", key("nonexistent-xyz"), 100L, null); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("explains the missing key", out.contains("runKey not found")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("echoes operation", out.contains("\"operation\":\"test_op\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void executeWithRunKeyParamRoutesToResume()
    {
        Map<String, String> params = new HashMap<>();
        params.put("runKey", key("routed-unknown")); //$NON-NLS-1$ //$NON-NLS-2$
        boolean[] workRan = {false};
        String out = PendingExecutor.execute(REG, "test_op", params, key("start-key"), 100L, //$NON-NLS-1$ //$NON-NLS-2$
            () ->
            {
                workRan[0] = true;
                return "started"; //$NON-NLS-1$
            }, null);
        assertTrue("unknown resume key errors", out.contains("runKey not found")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("start work must not run on a resume", workRan[0]); //$NON-NLS-1$
    }

    @Test
    public void evictCompletedOnStartForcesReRun() throws Exception
    {
        String rk = key("fresh-run"); //$NON-NLS-1$
        int[] runs = {0};
        Supplier<String> work = () ->
        {
            runs[0]++;
            return "{\"run\":" + runs[0] + "}"; //$NON-NLS-1$ //$NON-NLS-2$
        };
        // First call completes and is retrieved (entry removed).
        String first = PendingExecutor.start(REG, "op", rk, 5000L, work, null, true); //$NON-NLS-1$
        assertTrue("first run", first.contains("\"run\":1")); //$NON-NLS-1$ //$NON-NLS-2$
        // A fresh call with evictCompletedOnStart re-runs (does not replay).
        String second = PendingExecutor.start(REG, "op", rk, 5000L, work, null, true); //$NON-NLS-1$
        assertTrue("re-ran, not replayed", second.contains("\"run\":2")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("work invoked twice", 2, runs[0]); //$NON-NLS-1$
        REG.remove(rk);
    }

    @Test
    public void workThatThrowsYieldsTerminalErrorNotHang()
    {
        String rk = key("throws"); //$NON-NLS-1$
        String out = PendingExecutor.start(REG, "test_op", rk, 5000L, //$NON-NLS-1$
            () ->
            {
                throw new RuntimeException("boom"); //$NON-NLS-1$
            }, null);
        // The contract is a terminal refusal - not a hang, not Pending - and it is structured.
        // It used to be the sentence "Error: boom", which nothing downstream could tell from an
        // answer beginning with that word, and which carried no success:false for anything reading
        // the structured channel.
        assertTrue("a thrown failure must answer success:false: " + out, //$NON-NLS-1$
            out.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("and must name the exception type, not only its message: " + out, //$NON-NLS-1$
            out.contains("RuntimeException")); //$NON-NLS-1$
        assertTrue("and carry what was thrown: " + out, out.contains("boom")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("and say it happened in the background: " + out, //$NON-NLS-1$
            out.contains("\"failedInBackground\":true")); //$NON-NLS-1$
        assertFalse("a thrown failure must not read as Pending", //$NON-NLS-1$
            out.contains("\"status\":\"Pending\"")); //$NON-NLS-1$
        assertNull("entry removed after terminal retrieval", REG.get(rk)); //$NON-NLS-1$
    }

    @Test
    public void oversizedResultIsFlaggedSmallIsNot() throws Exception
    {
        char[] chunk = new char[1024 * 1024];
        Arrays.fill(chunk, 'x');
        String oneMeg = new String(chunk);

        String rkBig = key("oversized"); //$NON-NLS-1$
        PendingWorkRegistry.PendingEntry big = REG.getOrStart(rkBig,
            () -> oneMeg + oneMeg + oneMeg + oneMeg + oneMeg); // ~5M chars
        assertNotNull(big.await(5000L));
        awaitCompleted(big);
        assertTrue("result past the cap is flagged oversized", big.oversized); //$NON-NLS-1$
        REG.remove(rkBig);

        String rkSmall = key("small"); //$NON-NLS-1$
        PendingWorkRegistry.PendingEntry small = REG.getOrStart(rkSmall, () -> "small"); //$NON-NLS-1$
        assertNotNull(small.await(5000L));
        awaitCompleted(small);
        assertFalse("a small result is not oversized", small.oversized); //$NON-NLS-1$
        REG.remove(rkSmall);
    }

    @Test
    public void oversizedCompletedEntryEvictedOnShorterTtl() throws Exception
    {
        char[] chunk = new char[1024 * 1024];
        Arrays.fill(chunk, 'x');
        String oneMeg = new String(chunk);

        String rkBig = key("evict-big"); //$NON-NLS-1$
        PendingWorkRegistry.PendingEntry big = REG.getOrStart(rkBig,
            () -> oneMeg + oneMeg + oneMeg + oneMeg + oneMeg);
        assertNotNull(big.await(5000L));
        awaitCompleted(big);

        String rkSmall = key("evict-small"); //$NON-NLS-1$
        PendingWorkRegistry.PendingEntry small = REG.getOrStart(rkSmall, () -> "small"); //$NON-NLS-1$
        assertNotNull(small.await(5000L));
        awaitCompleted(small);

        // Backdate both completions to 150s ago: past the oversized 2-min TTL but
        // within the normal 5-min TTL, so only the oversized entry is evicted.
        long backdated = System.currentTimeMillis() - 150_000L;
        big.completedAt = backdated;
        small.completedAt = backdated;
        REG.pruneExpired();

        assertNull("oversized entry evicted on the shorter TTL", REG.get(rkBig)); //$NON-NLS-1$
        assertNotNull("normal entry survives within the 5-min TTL", REG.get(rkSmall)); //$NON-NLS-1$
        REG.remove(rkSmall);
    }

    @Test
    public void parseTimeoutClampsAndDefaults()
    {
        Map<String, String> p = new HashMap<>();
        assertEquals("absent -> default", 9999L, PendingExecutor.parseTimeoutMs(p, 9999L)); //$NON-NLS-1$
        p.put("timeoutSeconds", "3"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("below min clamps to 5s", 5000L, PendingExecutor.parseTimeoutMs(p, 9999L)); //$NON-NLS-1$
        p.put("timeoutSeconds", "999"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("above max clamps to 120s", 120000L, PendingExecutor.parseTimeoutMs(p, 9999L)); //$NON-NLS-1$
        p.put("timeoutSeconds", "42"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("in-range passes through", 42000L, PendingExecutor.parseTimeoutMs(p, 9999L)); //$NON-NLS-1$
        p.put("timeoutSeconds", "notanumber"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("unparseable -> default", 9999L, PendingExecutor.parseTimeoutMs(p, 9999L)); //$NON-NLS-1$
    }

    /**
     * A run that takes longer than the abandoned TTL keeps its entry, its result, and its place in
     * the key: work whose duration the server cannot bound reaches that TTL.
     */
    @Test
    public void workStillExecutingIsNotEvictedByTheAbandonedTtl() throws Exception
    {
        String rk = key("still-executing"); //$NON-NLS-1$
        CountDownLatch began = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        int[] runs = {0};
        Supplier<String> longWork = () ->
        {
            runs[0]++;
            began.countDown();
            try
            {
                release.await(20, TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            return "{\"updated\":true}"; //$NON-NLS-1$
        };
        try
        {
            String pending = PendingExecutor.start(REG, "update_database", rk, 100L, longWork, null); //$NON-NLS-1$
            assertTrue("status Pending", pending.contains("\"status\":\"Pending\"")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("the body is executing", began.await(10, TimeUnit.SECONDS)); //$NON-NLS-1$

            PendingWorkRegistry.PendingEntry entry = REG.get(rk);
            assertNotNull(entry);
            entry.beganAt = System.currentTimeMillis() - REG.abandonedTtlMs() - 1000L;

            REG.pruneExpired();

            assertNotNull("a run whose body is still executing keeps its entry", REG.get(rk)); //$NON-NLS-1$

            String again = PendingExecutor.start(REG, "update_database", rk, 100L, longWork, null); //$NON-NLS-1$
            assertTrue("the repeat coalesces onto the run in flight", //$NON-NLS-1$
                again.contains("\"status\":\"Pending\"")); //$NON-NLS-1$
            assertEquals("and does not start a second copy", 1, runs[0]); //$NON-NLS-1$

            release.countDown();
            String result = PendingExecutor.resume(REG, "update_database", rk, 5000L, null); //$NON-NLS-1$
            assertTrue("the result of the long run is still collectable", //$NON-NLS-1$
                result.contains("\"updated\":true")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            release.countDown();
            REG.remove(rk);
        }
    }

    /**
     * A run whose body has left but whose result is not recorded yet keeps its entry past the
     * abandoned TTL: the result lands on the entry when the tracking future completes, a moment
     * after the body's own exit, and a prune in between would drop it.
     */
    @Test
    public void workThatLeftBeforeItsResultWasRecordedIsNotEvictedByTheAbandonedTtl() throws Exception
    {
        String rk = key("left-not-recorded"); //$NON-NLS-1$
        CountDownLatch began = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Supplier<String> longWork = () ->
        {
            began.countDown();
            try
            {
                release.await(20, TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            return "{\"updated\":true}"; //$NON-NLS-1$
        };
        try
        {
            PendingExecutor.start(REG, "update_database", rk, 100L, longWork, null); //$NON-NLS-1$
            assertTrue("the body is executing", began.await(10, TimeUnit.SECONDS)); //$NON-NLS-1$

            PendingWorkRegistry.PendingEntry entry = REG.get(rk);
            assertNotNull(entry);
            entry.beganAt = System.currentTimeMillis() - REG.abandonedTtlMs() - 1000L;
            entry.workExited();
            assertEquals("the result is not recorded yet", 0L, entry.completedAt); //$NON-NLS-1$

            REG.pruneExpired();

            assertNotNull("a run whose body has left keeps its entry until the result lands", //$NON-NLS-1$
                REG.get(rk));

            release.countDown();
            String result = PendingExecutor.resume(REG, "update_database", rk, 5000L, null); //$NON-NLS-1$
            assertTrue("the result is collectable", result.contains("\"updated\":true")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            release.countDown();
            REG.remove(rk);
        }
    }

    /**
     * Collecting a result drops the run that produced it, not whatever run holds the key by then.
     * <p>
     * A caller with the same arguments coalesces onto one run, so a second caller can be waiting
     * for the same result while a third - after the key was freed - starts a new run under it. The
     * two waiters then collect the same answer, and the one removing by key alone deletes the run
     * nobody has collected: it goes on with nothing tracking it and cannot be polled or cancelled.
     * </p>
     */
    @Test
    public void collectingAResultDropsThatRunAndNotWhateverTookItsKey() throws Exception
    {
        assertThatCollectingKeepsTheRunThatTookTheKey(false);
    }

    /** The same for a poll: a resume drops the run it read, not a newer one under the same key. */
    @Test
    public void resumingAResultDropsThatRunAndNotWhateverTookItsKey() throws Exception
    {
        assertThatCollectingKeepsTheRunThatTookTheKey(true);
    }

    /**
     * Runs a long body, frees its key while a second caller waits on its result, and then lets it
     * finish - the second caller collecting by {@code resume} or by a repeat {@code start}.
     *
     * @param byResume whether the waiting caller polls instead of re-issuing the call
     * @throws Exception when the waiting thread cannot be joined
     */
    private static void assertThatCollectingKeepsTheRunThatTookTheKey(boolean byResume) throws Exception
    {
        String rk = key(byResume ? "generation-resume" : "generation-start"); //$NON-NLS-1$ //$NON-NLS-2$
        CountDownLatch began = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        int[] runs = {0};
        Supplier<String> firstWork = () ->
        {
            runs[0]++;
            began.countDown();
            try
            {
                release.await(20, TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            return "{\"first\":true}"; //$NON-NLS-1$
        };
        try
        {
            String pending = PendingExecutor.start(REG, "export_object", rk, 100L, firstWork, null); //$NON-NLS-1$
            assertTrue("status Pending", pending.contains("\"status\":\"Pending\"")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("the body is executing", began.await(10, TimeUnit.SECONDS)); //$NON-NLS-1$

            PendingWorkRegistry.PendingEntry first = REG.get(rk);
            assertNotNull(first);

            // The second caller waits for this run's result.
            java.util.concurrent.atomic.AtomicReference<String> collected = new java.util.concurrent
                .atomic.AtomicReference<>();
            Thread waiter = new Thread(() -> collected.set(byResume
                ? PendingExecutor.resume(REG, "export_object", rk, 10000L, null) //$NON-NLS-1$
                : PendingExecutor.start(REG, "export_object", rk, 10000L, firstWork, null))); //$NON-NLS-1$
            waiter.start();
            Thread.sleep(300L);

            // The run that owned the key is gone and a new one takes it, while the waiter is still
            // holding the first run's result.
            assertTrue("the first run is dropped", REG.remove(rk, first)); //$NON-NLS-1$
            PendingWorkRegistry.PendingEntry second = REG.getOrStart(rk, e -> "{\"second\":true}"); //$NON-NLS-1$
            assertNotNull(second);

            release.countDown();
            waiter.join(15000L);
            assertTrue("the waiter collected the first run's result, not the second's: " //$NON-NLS-1$
                + collected.get(), collected.get() != null && collected.get().contains("\"first\":true")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("and did not start a second copy of the work", 1, runs[0]); //$NON-NLS-1$

            assertNotNull("the run that took the key is still tracked", REG.get(rk)); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
            REG.remove(rk);
        }
    }
}
