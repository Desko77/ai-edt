/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.After;
import org.junit.Test;

import ru.aiedt.mcp.server.support.PendingWorkRegistry.PendingEntry;

/**
 * A repeat call that races the drop of the same key never becomes a second copy of the work.
 * <p>
 * The drop writes twice - the key into {@code stopping}, the entry out of the map - and the repeat
 * reads {@code stopping} before it maps a new entry. Both orders of those writes, and the re-read
 * inside the mapping, are held in place here: parked exactly in each window by a test gate, a
 * repeat is refused rather than dispatched, and the body under the key keeps running alone.
 * </p>
 */
public class ARepeatDuringADropTest
{
    @After
    public void theGatesGo()
    {
        PendingWorkRegistry.afterEntryDetached = null;
        PendingWorkRegistry.beforeCoalesceMapping = null;
    }

    /**
     * Starts a run under a call scope of its own, the way a tool call does.
     *
     * @param domain the registry the run belongs to
     * @param key the run key
     * @param work the body
     * @return the entry the call was dispatched on
     */
    private static PendingEntry startInACall(PendingWorkRegistry domain, String key,
        Function<PendingEntry, String> work)
    {
        ToolCallScope.enter(ToolCallScope.forCancellation(new ToolCallScope.Cancellation()));
        try
        {
            return domain.getOrStart(key, work);
        }
        finally
        {
            ToolCallScope.exit();
        }
    }

    /**
     * A body that counts its entry and waits to be let go.
     *
     * @param began counted down when the body starts
     * @param release awaited before the body returns
     * @param runs counts how many bodies were entered
     * @return the body
     */
    private static Function<PendingEntry, String> countingBody(CountDownLatch began,
        CountDownLatch release, AtomicInteger runs)
    {
        return entry -> {
            runs.incrementAndGet();
            began.countDown();
            try
            {
                release.await(20, TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            return "done"; //$NON-NLS-1$
        };
    }

    private static void sleep(long ms)
    {
        try
        {
            Thread.sleep(ms);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A repeat issued while the drop is between its two writes - the entry already out of the
     * map - is refused, not answered with a second body.
     * <p>
     * The gate parks a whole repeat call inside {@code dropEntry}, after the entry has left the
     * map. That window is what the ordering of the two writes closes: with the key in
     * {@code stopping} before the entry goes, the repeat finds the key held however close to the
     * drop it ran.
     * </p>
     *
     * @throws Exception when the latches cannot be awaited
     */
    @Test
    public void aRepeatInsideTheDropWindowIsRefusedNotStarted() throws Exception
    {
        PendingWorkRegistry domain = PendingWorkRegistry.UPDATE;
        String key = "drop-window-" + UUID.randomUUID(); //$NON-NLS-1$
        CountDownLatch began = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        Function<PendingEntry, String> body = countingBody(began, release, runs);
        AtomicReference<String> answerInTheWindow = new AtomicReference<>();
        PendingWorkRegistry.afterEntryDetached = () -> answerInTheWindow
            .set(domain.getOrStart(key, body).await(1000));
        try
        {
            startInACall(domain, key, body);
            assertTrue("the first body is running", began.await(10, TimeUnit.SECONDS)); //$NON-NLS-1$

            assertTrue("the drop had a tracked entry to remove", domain.cancel(key)); //$NON-NLS-1$

            String inWindow = answerInTheWindow.get();
            assertNotNull("the repeat in the window was answered at once, not left waiting", //$NON-NLS-1$
                inWindow);
            assertTrue(inWindow, inWindow.contains("\"stillStopping\":true")); //$NON-NLS-1$
            assertEquals("the repeat started nothing while the first body executes", 1, //$NON-NLS-1$
                runs.get());

            release.countDown();
            sleep(300L);
            assertEquals("and no second copy appeared after the drop returned", 1, runs.get()); //$NON-NLS-1$

            // Once the body has left, the key is free again: a fresh call runs.
            long end = System.currentTimeMillis() + 10_000L;
            PendingEntry fresh = null;
            while (runs.get() < 2 && System.currentTimeMillis() < end)
            {
                fresh = startInACall(domain, key, entry -> {
                    runs.incrementAndGet();
                    return "second"; //$NON-NLS-1$
                });
                sleep(20);
            }
            assertEquals("a call after the body left starts a run", 2, runs.get()); //$NON-NLS-1$
            assertEquals("second", fresh.await(5000)); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
            PendingWorkRegistry.afterEntryDetached = null;
            domain.remove(key);
        }
    }

    /**
     * A repeat that read {@code stopping} before the drop, and maps its entry only after the drop
     * finished, is refused by the mapping itself.
     * <p>
     * The gate parks the repeat between its read of {@code stopping} and its mapping. The drop
     * then runs to completion on the main thread, and the repeat resumes into a key absent from
     * the map and held in {@code stopping} - the interleaving only the mapping's own re-read
     * closes.
     * </p>
     *
     * @throws Exception when the latches cannot be awaited
     */
    @Test
    public void aRepeatThatMissedTheStopIsRefusedInsideTheMapping() throws Exception
    {
        PendingWorkRegistry domain = PendingWorkRegistry.UPDATE;
        String key = "mapping-reread-" + UUID.randomUUID(); //$NON-NLS-1$
        CountDownLatch began = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        Function<PendingEntry, String> body = countingBody(began, release, runs);
        CountDownLatch parkedPastTheRead = new CountDownLatch(1);
        CountDownLatch goOn = new CountDownLatch(1);
        AtomicReference<String> repeatAnswer = new AtomicReference<>();
        PendingWorkRegistry.beforeCoalesceMapping = () -> {
            parkedPastTheRead.countDown();
            try
            {
                goOn.await(20, TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        };
        Thread repeat = new Thread(() -> repeatAnswer
            .set(String.valueOf(domain.getOrStart(key, body).await(1000))));
        repeat.setDaemon(true);
        try
        {
            startInACall(domain, key, body);
            assertTrue("the first body is running", began.await(10, TimeUnit.SECONDS)); //$NON-NLS-1$

            repeat.start();
            assertTrue("the repeat parked between its read and its mapping", //$NON-NLS-1$
                parkedPastTheRead.await(10, TimeUnit.SECONDS));

            // The whole drop happens while the repeat is parked past its read.
            assertTrue(domain.cancel(key));

            goOn.countDown();
            repeat.join(15_000L);
            String answer = repeatAnswer.get();
            assertNotNull(answer);
            assertTrue(answer, answer.contains("\"stillStopping\":true")); //$NON-NLS-1$
            assertEquals("the mapping refused rather than dispatched", 1, runs.get()); //$NON-NLS-1$

            release.countDown();
            sleep(300L);
            assertEquals("and no second copy appeared afterwards", 1, runs.get()); //$NON-NLS-1$
        }
        finally
        {
            goOn.countDown();
            release.countDown();
            PendingWorkRegistry.beforeCoalesceMapping = null;
            repeat.join(15_000L);
            domain.remove(key);
        }
    }
}
