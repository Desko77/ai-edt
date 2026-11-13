/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import org.junit.Test;

/**
 * Holds the bounded store: the clock-driven expiry, the memory ceiling and oldest-first eviction,
 * the project-close and service-stop cleanup, the token bits that survive eviction, and concurrent
 * reads and removals.
 * <p>
 * Every test runs on a synthetic selection and a controlled clock; none waits for the real
 * fifteen-minute lifetime or creates real project content.
 * </p>
 */
public class PinnedResultCacheTest
{
    private static final long TTL = 60_000L;

    @Test
    public void aRecordLivesUntilTheTtlAndThenExpires()
    {
        FakeClock clock = new FakeClock();
        PinnedResultCache cache = new PinnedResultCache(clock, TTL, 1_000_000L);
        UUID id = UUID.randomUUID();
        assertTrue(cache.put(id, selection("value"))); //$NON-NLS-1$

        clock.advance(TTL - 1);
        assertNotNull(cache.get(id));
        clock.advance(1);
        assertNull("expiry is reached exactly at the TTL", cache.get(id)); //$NON-NLS-1$
        assertEquals(0, cache.size());
    }

    @Test
    public void expiredRecordsStopCountingAgainstMemory()
    {
        FakeClock clock = new FakeClock();
        PinnedResultCache cache = new PinnedResultCache(clock, TTL, 1_000_000L);
        assertTrue(cache.put(UUID.randomUUID(), selection("value"))); //$NON-NLS-1$
        assertTrue(cache.memoryBytes() > 0);
        clock.advance(TTL);
        assertEquals(0, cache.memoryBytes());
    }

    @Test
    public void theOldestRecordGoesWhenTheMemoryCapIsReached()
    {
        FakeClock clock = new FakeClock();
        PinnedResult first = selection("first"); //$NON-NLS-1$
        PinnedResult second = selection("second"); //$NON-NLS-1$
        long cap = first.estimatedBytes() + second.estimatedBytes() - 1;
        PinnedResultCache cache = new PinnedResultCache(clock, TTL, cap);
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        assertTrue(cache.put(firstId, first));
        assertTrue(cache.put(secondId, second));
        assertNull("the older selection is evicted", cache.get(firstId)); //$NON-NLS-1$
        assertNotNull(cache.get(secondId));
        assertTrue(cache.memoryBytes() <= cap);
    }

    @Test
    public void aRecordLargerThanTheWholeCapIsNotStored()
    {
        FakeClock clock = new FakeClock();
        PinnedResult result = selection("value"); //$NON-NLS-1$
        PinnedResultCache cache = new PinnedResultCache(clock, TTL, result.estimatedBytes() - 1);
        assertFalse(cache.put(UUID.randomUUID(), result));
        assertEquals(0, cache.size());
        assertEquals(0, cache.memoryBytes());
    }

    @Test
    public void replacingTheSameIdentityDoesNotCountItTwice()
    {
        FakeClock clock = new FakeClock();
        PinnedResult small = selection("x"); //$NON-NLS-1$
        PinnedResult large = selection("x".repeat(1000)); //$NON-NLS-1$
        PinnedResultCache cache = new PinnedResultCache(clock, TTL, 1_000_000L);
        UUID id = UUID.randomUUID();
        assertTrue(cache.put(id, small));
        assertTrue(cache.put(id, large));
        assertNotNull(cache.get(id));
        assertEquals(large.estimatedBytes(), cache.memoryBytes());
    }

    @Test
    public void aClosingProjectDropsItsSelectionsAndTheUnattributedOnes()
    {
        FakeClock clock = new FakeClock();
        PinnedResultCache cache = new PinnedResultCache(clock, TTL, 1_000_000L);
        UUID inA = UUID.randomUUID();
        UUID inB = UUID.randomUUID();
        UUID workspaceWide = UUID.randomUUID();
        cache.put(inA, selection("a", "A")); //$NON-NLS-1$ //$NON-NLS-2$
        cache.put(inB, selection("b", "B")); //$NON-NLS-1$ //$NON-NLS-2$
        cache.put(workspaceWide, selection("c")); //$NON-NLS-1$

        assertEquals(2, cache.clearProject("A")); //$NON-NLS-1$
        assertNull(cache.get(inA));
        assertNull("an unattributed selection may include the closed project", //$NON-NLS-1$
            cache.get(workspaceWide));
        assertNotNull(cache.get(inB));
        assertEquals(1, cache.size());
    }

    @Test
    public void stoppingTheServiceDropsEverything()
    {
        FakeClock clock = new FakeClock();
        PinnedResultCache cache = new PinnedResultCache(clock, TTL, 1_000_000L);
        cache.put(UUID.randomUUID(), selection("a")); //$NON-NLS-1$
        cache.put(UUID.randomUUID(), selection("b")); //$NON-NLS-1$
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(0, cache.memoryBytes());
    }

    @Test
    public void anUnknownProjectNameClearsNothing()
    {
        FakeClock clock = new FakeClock();
        PinnedResultCache cache = new PinnedResultCache(clock, TTL, 1_000_000L);
        cache.put(UUID.randomUUID(), selection("a", "A")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, cache.clearProject(null));
        assertEquals(0, cache.clearProject(" ")); //$NON-NLS-1$
        assertEquals(1, cache.size());
    }

    @Test
    public void aTokenCarriesBothFlagsAndRoundTrips()
    {
        UUID id = UUID.randomUUID();
        PinnedResultCache.ParsedResultId parsed =
            PinnedResultCache.parse(PinnedResultCache.resultId(id, true, true));
        assertNotNull(parsed);
        assertEquals(id, parsed.id);
        assertTrue(parsed.incomplete);
        assertTrue(parsed.shortForm);

        PinnedResultCache.ParsedResultId plain =
            PinnedResultCache.parse(PinnedResultCache.resultId(id, false, false));
        assertNotNull(plain);
        assertFalse(plain.incomplete);
        assertFalse(plain.shortForm);
    }

    @Test
    public void aTokenThatIsNotOursIsRejected()
    {
        assertNull(PinnedResultCache.parse(null));
        assertNull(PinnedResultCache.parse("")); //$NON-NLS-1$
        assertNull(PinnedResultCache.parse("r1.nothex")); //$NON-NLS-1$
        assertNull(PinnedResultCache.parse(PinnedResultCache.resultId(UUID.randomUUID(), false, false)
            + ".x")); //$NON-NLS-1$
        assertNull(PinnedResultCache.parse(PinnedResultCache.resultId(UUID.randomUUID(), true, true)
            .replace(".is", ".ii"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aTokenWithClearedOrAlteredFlagsOrSealIsRejected()
    {
        // Clearing the short-form bit must not turn a short-form token into a full-form one.
        String shortForm = PinnedResultCache.resultId(UUID.randomUUID(), false, true);
        assertNull(PinnedResultCache.parse(shortForm.replace(".s.", ".-."))); //$NON-NLS-1$ //$NON-NLS-2$
        // Clearing the incomplete bit must not make an unfinished collection rebuildable.
        String unfinished = PinnedResultCache.resultId(UUID.randomUUID(), true, false);
        assertNull(PinnedResultCache.parse(unfinished.replace(".i.", ".-."))); //$NON-NLS-1$ //$NON-NLS-2$
        // Dropping one flag of a doubly flagged token is the same forgery.
        String both = PinnedResultCache.resultId(UUID.randomUUID(), true, true);
        assertNull(PinnedResultCache.parse(both.replace(".is.", ".s."))); //$NON-NLS-1$ //$NON-NLS-2$
        // Raising a flag on a plain token is the forgery in the other direction.
        String plain = PinnedResultCache.resultId(UUID.randomUUID(), false, false);
        assertNull(PinnedResultCache.parse(plain.replace(".-.", ".s."))); //$NON-NLS-1$ //$NON-NLS-2$
        // A tampered seal changes the token's meaning without a valid recompute.
        String sealed = PinnedResultCache.resultId(UUID.randomUUID(), false, true);
        char last = sealed.charAt(sealed.length() - 1);
        char swapped = last == '0' ? '1' : '0';
        assertNull(PinnedResultCache.parse(sealed.substring(0, sealed.length() - 1) + swapped));
    }

    @Test
    public void concurrentReadsAndRemovalsFinishWithoutCorruption() throws Exception
    {
        PinnedResultCache cache = new PinnedResultCache(() -> 0L, TTL, 1_000_000L);
        UUID id = UUID.randomUUID();
        assertTrue(cache.put(id, selection("value"))); //$NON-NLS-1$
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> workers = new ArrayList<>();
        for (int worker = 0; worker < 4; worker++)
        {
            Thread thread = new Thread(() ->
            {
                try
                {
                    start.await();
                    for (int i = 0; i < 50; i++)
                    {
                        cache.get(id);
                        cache.size();
                        cache.memoryBytes();
                        if (i % 10 == 0)
                        {
                            cache.remove(id);
                        }
                        cache.put(id, selection("value")); //$NON-NLS-1$
                    }
                }
                catch (Throwable problem)
                {
                    failure.compareAndSet(null, problem);
                }
            }, "pinned-cache-worker"); //$NON-NLS-1$
            workers.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : workers)
        {
            thread.join(TimeUnit.SECONDS.toMillis(20));
            assertFalse("a worker is still running", thread.isAlive()); //$NON-NLS-1$
        }
        assertNull(String.valueOf(failure.get()), failure.get());
        assertTrue(cache.size() <= 1);
    }

    private static PinnedResult selection(String text, String... projects)
    {
        return PinnedResult.builder("read") //$NON-NLS-1$
            .projects(List.of(projects))
            .units(List.of(ResultUnit.of(text)))
            .build();
    }

    /** A clock a test moves by hand. */
    private static final class FakeClock implements LongSupplier
    {
        private long now;

        @Override
        public long getAsLong()
        {
            return now;
        }

        private void advance(long millis)
        {
            now += millis;
        }
    }
}
