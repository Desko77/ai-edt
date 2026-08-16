/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * At-most-once execution of a mutator keyed by a client {@code operationId}
 * ({@link MutatorIdempotency} decides which calls are keyed).
 * <p>
 * The first caller for a key becomes the <em>owner</em> and runs the mutation
 * INLINE on its own request thread - the same thread the mutator runs on today, so
 * this adds no new executor and no second Pending contract. A concurrent duplicate
 * with the same key does not run the mutation; it waits (bounded) on the owner's
 * future and returns the owner's result. After the owner finishes:
 * <ul>
 *   <li>a SUCCESS result is cached under the key for {@link #COMPLETED_TTL_MS}, so a
 *       later retry with the same id replays it WITHOUT mutating again;</li>
 *   <li>a FAILURE result (or a thrown exception) is evicted, so a retry re-runs -
 *       the allow-retry policy, safe because every keyed mutator is a single BM
 *       transaction (or file write) that leaves no durable effect on failure (the
 *       multi-step mutators that could partially apply are excluded upstream).</li>
 * </ul>
 * The store is in-memory: on an EDT restart / self-update the keys are lost, so a
 * retry issued across a restart may run the mutation a second time. This is a
 * documented limitation.
 */
public final class MutatorIdempotencyStore
{
    /** Shared instance used by the request router. */
    public static final MutatorIdempotencyStore INSTANCE = new MutatorIdempotencyStore();

    /** How long a completed-success result stays replayable. */
    static final long COMPLETED_TTL_MS = 5L * 60L * 1000L;

    /** Default bound on how long a duplicate waits for the owner before returning a retry note. */
    public static final long DEFAULT_WAITER_TIMEOUT_MS = 60_000L;

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    /** Package-private for tests. */
    MutatorIdempotencyStore()
    {
    }

    private static final class Entry
    {
        final CompletableFuture<String> future = new CompletableFuture<>();
        final AtomicBoolean owner = new AtomicBoolean(false);
        volatile long completedAtMillis; // 0 while in-flight
    }

    /**
     * Runs {@code work} at most once per {@code key}: the owner runs it inline and
     * caches the outcome; a concurrent duplicate waits on the owner and replays it.
     *
     * @param key the composite idempotency key from {@link MutatorIdempotency#key}
     * @param work the mutator body (typically {@code () -> tool.execute(args)})
     * @param waiterTimeoutMs how long a duplicate waits before returning a retry note
     * @return the mutation result (owner's fresh result, or a duplicate's replay of it,
     *         or a still-running note if the wait timed out)
     */
    public String call(String key, java.util.function.Supplier<String> work, long waiterTimeoutMs)
    {
        pruneExpired(System.currentTimeMillis());
        Entry e = this.entries.computeIfAbsent(key, k -> new Entry());
        if (e.owner.compareAndSet(false, true))
        {
            return runAsOwner(key, e, work);
        }
        return waitAsDuplicate(e, waiterTimeoutMs);
    }

    private String runAsOwner(String key, Entry e, java.util.function.Supplier<String> work)
    {
        String result = null;
        boolean threw = false;
        try
        {
            result = work.get();
            return result;
        }
        catch (RuntimeException ex)
        {
            threw = true;
            result = "Error: " + (ex.getMessage() != null ? ex.getMessage() //$NON-NLS-1$
                : ex.getClass().getSimpleName());
            throw ex; // propagate so the request path records the failure exactly as today
        }
        finally
        {
            // Complete FIRST (always), so any waiter already blocked on this future is
            // released with the result - only then evict a failed entry so the NEXT
            // fresh caller re-runs. Never remove before completing.
            e.completedAtMillis = System.currentTimeMillis();
            e.future.complete(result);
            if (threw || looksFailed(result))
            {
                this.entries.remove(key, e);
            }
        }
    }

    private String waitAsDuplicate(Entry e, long waiterTimeoutMs)
    {
        try
        {
            return e.future.get(Math.max(1L, waiterTimeoutMs), TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException te)
        {
            return stillRunningNote();
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return ToolResult.error("Interrupted while waiting for a concurrent call with the " //$NON-NLS-1$
                + "same operationId.").toJson();
        }
        catch (java.util.concurrent.ExecutionException ee)
        {
            // The owner always completes with a string (never completeExceptionally), so this
            // is not expected; surface it as an error rather than swallowing it.
            return "Error: concurrent call with the same operationId failed: " //$NON-NLS-1$
                + ee.getMessage();
        }
    }

    /**
     * The response a duplicate gets when the owner is still running past the wait bound.
     * Shaped as a non-success so a client does not mistake it for a completed mutation,
     * and it explicitly tells the client to reuse the SAME operationId (minting a new one
     * would start an independent mutation while the owner still holds this key).
     */
    private static String stillRunningNote()
    {
        return ToolResult.error("This operationId is still being applied. Retry with the SAME " //$NON-NLS-1$
                + "operationId to keep waiting for the result - do NOT mint a new operationId, " //$NON-NLS-1$
                + "which would start an independent mutation.") //$NON-NLS-1$
            .put("status", "in_progress") //$NON-NLS-1$ //$NON-NLS-2$
            .toJson();
    }

    /**
     * Whether a mutator result reads as a failure (evict, allow retry) rather than a success
     * (cache, replay).
     *
     * @param result the tool's answer
     * @return whether it reads as a failure
     * @see FailureShape
     */
    static boolean looksFailed(String result)
    {
        return FailureShape.looksFailed(result);
    }

    /** Drops completed-success entries older than the TTL. In-flight entries are kept. */
    void pruneExpired(long nowMillis)
    {
        Iterator<Map.Entry<String, Entry>> it = this.entries.entrySet().iterator();
        while (it.hasNext())
        {
            Entry e = it.next().getValue();
            long done = e.completedAtMillis;
            if (done > 0 && nowMillis - done > COMPLETED_TTL_MS)
            {
                it.remove();
            }
        }
    }

    /** Test seam: current number of tracked entries. */
    int size()
    {
        return this.entries.size();
    }
}
