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
}
