/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

import ru.aiedt.mcp.server.Activator;

/**
 * Generic async work registry that backs the soft-timeout / {@code runKey}
 * "Pending" protocol shared by every long-running MCP tool (a FULL infobase
 * update, an {@code .epf}/{@code .erf} build, a project-wide {@code find_references},
 * an {@code edit_metadata} batch). Such work can run for minutes and would
 * otherwise pin one of the few MCP HTTP-handler threads for the whole duration.
 * <p>
 * This class consolidates three previously copy-pasted registries
 * ({@code PendingUpdateRegistry} / {@code PendingExportRegistry} /
 * {@code PendingReferencesRegistry}) into one parameterized implementation.
 * Each caller keeps a dedicated instance ({@link #UPDATE}, {@link #EXPORT},
 * {@link #REFERENCES}) with its <b>own</b> bounded executor, so a slow update
 * cannot starve exports or reference searches - the isolation the separate
 * classes provided is preserved.
 * <p>
 * runKeys only have to be unique <em>within</em> a domain: every instance owns a
 * separate {@link #entries} map, so two domains that happen to hash to the same
 * string never collide. Callers therefore build their key from their own
 * canonical parameter set via {@link #computeRunKey(String...)}.
 * <p>
 * Lifecycle:
 * <ol>
 *   <li>{@link #getOrStart} - returns or creates the entry, dispatching the work
 *       on a worker thread. Identical params coalesce onto one future.</li>
 *   <li>{@link PendingEntry#await(long)} - blocks up to the soft timeout.</li>
 *   <li>Completed within the window: the caller returns the result and
 *       {@link #remove}s the entry.</li>
 *   <li>Timeout elapses: the caller returns Pending JSON with the runKey; the
 *       entry remains for subsequent retries with {@code runKey}.</li>
 *   <li>{@link #cancel} detaches a runKey (best-effort - see its javadoc).</li>
 *   <li>{@link #pruneExpired} evicts completed-not-retrieved entries (5 min, or
 *       2 min when the result is oversized - see {@link #MAX_CACHED_RESULT_CHARS})
 *       and abandoned entries (30 min).</li>
 * </ol>
 */
public final class PendingWorkRegistry
{
    /** Async backend for {@code update_database} and {@code edit_metadata} batch. */
    public static final PendingWorkRegistry UPDATE =
        new PendingWorkRegistry("update_database", "update-db-async"); //$NON-NLS-1$ //$NON-NLS-2$

    /** Async backend for {@code export_object} .epf/.erf builds. */
    public static final PendingWorkRegistry EXPORT =
        new PendingWorkRegistry("export_object", "export-object-async"); //$NON-NLS-1$ //$NON-NLS-2$

    /** Async backend for {@code find_references} project-wide searches. */
    public static final PendingWorkRegistry REFERENCES =
        new PendingWorkRegistry("find_references", "find-references-async"); //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * Async backend for {@code import_configuration_from_binary} staging runs.
     * <p>
     * Its own instance rather than {@link #GENERIC}, which is reserved for reads: this one creates
     * an infobase and a project. Coalescing two identical calls is right here - the second would
     * only fail on the taken project name - but a completed entry is dropped before a fresh submit
     * rather than replayed, because the workspace may have moved on since.
     * </p>
     */
    public static final PendingWorkRegistry IMPORT_BINARY = new PendingWorkRegistry(
        "import_configuration_from_binary", "import-binary-async"); //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * Shared async backend for the slow read-only analysis tools that are wrapped
     * generically (see {@code GenericPending}) rather than each hand-rolling their
     * own registry. The runKey embeds the tool name, so distinct tools never
     * collide inside this one instance. Reserved for idempotent, side-effect-free
     * reads - never a mutator, whose cache-replay/coalescing would be unsafe.
     */
    public static final PendingWorkRegistry GENERIC =
        new PendingWorkRegistry("generic_tool", "generic-tool-async", 4); //$NON-NLS-1$ //$NON-NLS-2$

    /** TTL for completed entries that were never retrieved. 5 minutes. */
    private static final long COMPLETED_TTL_MS = 5 * 60 * 1000L;

    /**
     * Shorter TTL for a completed-not-retrieved entry whose result is oversized
     * (see {@link #MAX_CACHED_RESULT_CHARS}). A large payload - a whole-config
     * XML export, a project-wide reference dump - must not linger in the cache
     * for the full {@link #COMPLETED_TTL_MS}: if the caller has not fetched it
     * within this window it is evicted so it stops pinning the heap. 2 minutes -
     * short enough to bound heap retention, long enough that a caller that issued
     * one poll and briefly stepped away can still collect a large payload.
     */
    private static final long COMPLETED_TTL_OVERSIZED_MS = 2 * 60 * 1000L;

    /**
     * Result size (in {@code char}s) beyond which a completed entry is treated
     * as oversized and evicted on {@link #COMPLETED_TTL_OVERSIZED_MS}. ~4M chars
     * is roughly 8 MB of UTF-16 heap per retained entry - already generous for a
     * tool response; anything larger should be consumed promptly, not cached.
     */
    private static final int MAX_CACHED_RESULT_CHARS = 4 * 1024 * 1024;

    /** TTL for never-completed entries (runaway). 30 minutes. */
    private static final long ABANDONED_TTL_MS = 30 * 60 * 1000L;

    private final String domainLabel;

    private final ConcurrentHashMap<String, PendingEntry> entries = new ConcurrentHashMap<>();

    private final ExecutorService executor;

    /**
     * @param domainLabel human-readable domain used in error logs (e.g.
     *            {@code "update_database"})
     * @param threadPrefix worker-thread name prefix (e.g. {@code "update-db-async"})
     */
    private PendingWorkRegistry(String domainLabel, String threadPrefix)
    {
        this(domainLabel, threadPrefix, 8);
    }

    /**
     * @param domainLabel human-readable domain used in error logs
     * @param threadPrefix worker-thread name prefix
     * @param maxPool executor ceiling. The shared {@link #GENERIC} domain uses a
     *            tighter bound than the dedicated domains: its background work
     *            keeps running after the heavy permit is released on a Pending
     *            return, so a low ceiling keeps that background load close to the
     *            B2 heavy-tool limit rather than letting it accumulate.
     */
    private PendingWorkRegistry(String domainLabel, String threadPrefix, int maxPool)
    {
        this.domainLabel = domainLabel;

        ThreadFactory threadFactory = new ThreadFactory()
        {
            private final AtomicLong counter = new AtomicLong(0);

            @Override
            public Thread newThread(Runnable r)
            {
                Thread t = new Thread(r, threadPrefix + "-" + counter.incrementAndGet()); //$NON-NLS-1$
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        };

        // Bounded executor (corePoolSize=min(2,maxPool), maxPoolSize=maxPool,
        // queue=20) with CallerRunsPolicy - backpressure when the queue saturates
        // instead of unbounded thread growth. One executor PER domain so a slow
        // update cannot starve exports or reference searches.
        //
        // Note: CallerRunsPolicy blocks the submitting thread - the MCP tool-worker
        // (McpHttpEndpoint's per-call MCP-Tool-Executor thread) that dispatched the
        // work, not the HTTP accept loop. Acceptable for the target audience of 1-2
        // concurrent AI clients; under load tests of 100+ overflow tasks it can back up.
        this.executor = new ThreadPoolExecutor(
            Math.min(2, maxPool), maxPool,
            60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(20),
            threadFactory,
            new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * Returns the existing entry for the given key, or creates and dispatches a
     * new one when absent. Threadsafe; identical params coalesce onto one future.
     */
    public PendingEntry getOrStart(String runKey, Supplier<String> work)
    {
        return getOrStart(runKey, entry -> work.get());
    }

    /**
     * As {@link #getOrStart(String, Supplier)}, for work that reports its progress.
     *
     * @param runKey the coalescing key, never {@code null}
     * @param work the body, handed the entry it runs under so it can set
     *            {@link PendingEntry#progressNote}; never {@code null}
     * @return the entry, whether freshly started or already running
     */
    public PendingEntry getOrStart(String runKey, Function<PendingEntry, String> work)
    {
        // Capture just the calling (worker) thread's cancellation flag so the async work can expose
        // it and a cooperative loop on the executor thread bails out when the operator cancels. We
        // capture the flag, not the whole scope, so the future does not pin the RunningToolCall (and
        // its HttpExchange) alive for the run's duration.
        //
        // On coalesce the first caller's flag wins - a later caller's cancel does not reach the shared
        // work. That is right for a read whose result the later caller still wants; for a mutator that
        // coalesces (UPDATE/EXPORT/REFERENCES/batch) it means a second caller cannot cancel the first's
        // work, which those tools do not rely on today (they carry no cancellation checkpoints yet).
        ToolCallScope current = ToolCallScope.current();
        ToolCallScope.Cancellation dispatchCancellation = current != null ? current.cancellation() : null;
        return entries.computeIfAbsent(runKey, k ->
        {
            PendingEntry entry = new PendingEntry(k);
            entry.future = CompletableFuture.supplyAsync(() ->
            {
                ToolCallScope previous = ToolCallScope.current();
                if (dispatchCancellation != null)
                {
                    ToolCallScope.enter(ToolCallScope.forCancellation(dispatchCancellation));
                }
                try
                {
                    return work.apply(entry);
                }
                catch (Throwable t)
                {
                    Activator.logError(domainLabel + " async work failed for runKey=" + k, t); //$NON-NLS-1$
                    return failed(t, k);
                }
                finally
                {
                    // Restore whatever was bound before. On a fresh executor thread nothing was, so
                    // exit; but CallerRunsPolicy runs this inline on the submitting tool-worker thread,
                    // whose own scope must survive - re-enter it rather than clearing it.
                    if (dispatchCancellation != null)
                    {
                        if (previous != null)
                        {
                            ToolCallScope.enter(previous);
                        }
                        else
                        {
                            ToolCallScope.exit();
                        }
                    }
                }
            }, executor);
            entry.future.whenComplete((result, throwable) ->
            {
                String cached = result != null ? result : failed(throwable, entry.runKey);
                entry.cachedResult = cached;
                entry.oversized = cached.length() > MAX_CACHED_RESULT_CHARS;
                entry.completedAt = System.currentTimeMillis();
            });
            return entry;
        });
    }

    /**
     * Every domain, in a fixed order.
     * <p>
     * A runKey is unique only within its domain, so anything holding a bare key - a task handle
     * handed to a client, for one - has to be able to find which domain issued it. Five map lookups
     * settle that, and the alternative (threading the domain through every layer that only ever
     * passes a key) buys nothing.
     * </p>
     *
     * @return the registries, in declaration order
     */
    public static List<PendingWorkRegistry> domains()
    {
        return Collections.unmodifiableList(
            Arrays.asList(UPDATE, EXPORT, REFERENCES, IMPORT_BINARY, GENERIC));
    }

    /**
     * Finds the domain holding a key.
     *
     * @param runKey the key to look for, may be {@code null}
     * @return the registry that has it, or {@code null} when no domain does
     */
    public static PendingWorkRegistry domainOf(String runKey)
    {
        if (runKey == null || runKey.isEmpty())
        {
            return null;
        }
        for (PendingWorkRegistry registry : domains())
        {
            if (registry.get(runKey) != null)
            {
                return registry;
            }
        }
        return null;
    }

    /**
     * @return the human-readable domain name this registry was built with
     */
    public String domain()
    {
        return domainLabel;
    }

    /**
     * Returns the entry for the given key if present, or {@code null}. Used by
     * {@code retry} mode (the AI explicitly polls a previously-issued runKey).
     */
    public PendingEntry get(String runKey)
    {
        return entries.get(runKey);
    }

    /**
     * Removes an entry once the caller has consumed its result.
     */
    public void remove(String runKey)
    {
        entries.remove(runKey);
    }

    /** Number of tracked entries (running or completed-not-yet-collected), for diagnostics. */
    public int size()
    {
        return entries.size();
    }

    /**
     * Number of entries whose work is still running (not yet completed), for
     * diagnostics. Weakly consistent - a snapshot over a live map, which is fine
     * for a status read. Distinct from {@link #size()}, which also counts
     * completed results not yet collected.
     */
    public int runningCount()
    {
        int running = 0;
        for (PendingEntry entry : entries.values())
        {
            if (!entry.isDone())
            {
                running++;
            }
        }
        return running;
    }

    /**
     * Detaches a runKey: cancels the tracking future and drops the entry.
     * <p>
     * <b>Best-effort only.</b> A structural/FULL {@code update_database} runs
     * against a platform behaviour delegate that wraps a blocking 1C Designer-mode
     * process. {@link CompletableFuture#cancel} IGNORES {@code mayInterruptIfRunning}
     * - it never interrupts the worker thread. So this only detaches the tracking
     * future and stops the server from waiting on / caching the result; it is NOT
     * guaranteed to abort work already in progress (the update may keep running
     * and still commit, holding one executor slot until it returns naturally). Use
     * it to stop tracking a runaway or no-longer-wanted poll, not as a guaranteed
     * rollback.
     *
     * @return true if a tracked entry existed and was removed
     */
    public boolean cancel(String runKey)
    {
        PendingEntry entry = entries.remove(runKey);
        if (entry == null)
        {
            return false;
        }
        if (entry.future != null && !entry.future.isDone())
        {
            entry.future.cancel(true);
        }
        return true;
    }

    /**
     * Stops tracking every run still going for one subject, without needing a runKey.
     * <p>
     * A finished result that nobody has collected is LEFT ALONE. It carries the error text of a
     * failed run, and that text is the whole reason a caller comes back for it; dropping it here
     * to make a counter look tidy would destroy the one thing worth keeping.
     * </p>
     * <p>
     * Like {@link #cancel}, this detaches tracking and does not stop the work. The platform call
     * carries on and still holds the infobase; what it holds is readable from
     * {@code MonopolyLock.outstandingHere}.
     * </p>
     *
     * @param subject what the runs are about; nothing happens when it is null or empty.
     * @return how many runs stopped being tracked
     */
    public int stopTrackingFor(String subject)
    {
        if (subject == null || subject.isEmpty())
        {
            return 0;
        }
        int stopped = 0;
        Iterator<Map.Entry<String, PendingEntry>> it = entries.entrySet().iterator();
        while (it.hasNext())
        {
            PendingEntry entry = it.next().getValue();
            if (!subject.equals(entry.subject) || entry.completedAt > 0)
            {
                continue;
            }
            if (entry.future != null && !entry.future.isDone() && entry.future.cancel(true))
            {
                // Removed only when the cancellation actually won. A run that finished between
                // the check and the call has a result waiting, and this method promises to keep it.
                it.remove();
                stopped++;
            }
        }
        return stopped;
    }

    /**
     * Evicts entries past their TTL.
     */
    public void pruneExpired()
    {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, PendingEntry>> it = entries.entrySet().iterator();
        while (it.hasNext())
        {
            Map.Entry<String, PendingEntry> e = it.next();
            PendingEntry entry = e.getValue();
            long completedTtl = entry.oversized ? COMPLETED_TTL_OVERSIZED_MS : COMPLETED_TTL_MS;
            if (entry.completedAt > 0 && now - entry.completedAt > completedTtl)
            {
                it.remove();
            }
            else if (entry.completedAt == 0 && now - entry.startedAt > ABANDONED_TTL_MS)
            {
                if (entry.future != null && !entry.future.isDone())
                {
                    entry.future.cancel(true);
                }
                it.remove();
            }
        }
    }

    /**
     * Computes a stable runKey from the given parts: SHA-256 (64-bit hex prefix)
     * of the parts joined by {@code '|'}. Nulls fold to empty. An identical
     * re-issue coalesces onto the same future. Callers pass their own canonical
     * parameter set (booleans/ints via {@link String#valueOf}); the key only has
     * to be unique within one domain's registry instance.
     */
    public static String computeRunKey(String... parts)
    {
        StringBuilder sb = new StringBuilder();
        for (String p : parts)
        {
            if (sb.length() > 0)
            {
                sb.append('|');
            }
            sb.append(p == null ? "" : p); //$NON-NLS-1$
        }
        return sha256(sb.toString());
    }

    private static String sha256(String input)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest)
            {
                hex.append(String.format("%02x", b & 0xFF)); //$NON-NLS-1$
            }
            return hex.substring(0, 16); // 64-bit prefix is plenty for our scale
        }
        catch (NoSuchAlgorithmException e)
        {
            // Should never happen on standard JDK
            return Integer.toHexString(input.hashCode());
        }
    }

    /**
     * Per-runKey state for the registry.
     */
    /**
     * What a caller gets when the work threw instead of answering.
     * <p>
     * A structured refusal, not the sentence {@code "Error: " + message} this used to return. That
     * sentence was the tool's entire result: nothing downstream could tell a failure from an
     * answer that happened to start with the word, the response carried no {@code success:false}
     * for anything reading the structured channel, and a message-less exception - which is most of
     * them - produced "Error: null".
     * </p>
     * <p>
     * The exception type is named as well as its message, for the same reason it is named
     * everywhere else here: the type is what says where to look when the message says nothing.
     * </p>
     *
     * @param t what was thrown; may be a completion wrapper.
     * @param runKey the run it belonged to, so the answer can be tied back to the request.
     * @return the failure as a tool result
     */
    private static String failed(Throwable t, String runKey)
    {
        Throwable cause = t instanceof java.util.concurrent.CompletionException && t.getCause() != null
            ? t.getCause() : t;
        String message = cause == null ? null : cause.getMessage();
        String named = cause == null ? "the work failed without saying how" //$NON-NLS-1$
            : (message == null || message.isEmpty() ? cause.getClass().getName()
                : cause.getClass().getSimpleName() + ": " + message); //$NON-NLS-1$
        return ru.aiedt.mcp.server.wire.ToolResult.error(named)
            .put("runKey", runKey) //$NON-NLS-1$
            .put("failedInBackground", true) //$NON-NLS-1$
            .toJson();
    }

    public static final class PendingEntry
    {
        public final String runKey;
        public final long startedAt = System.currentTimeMillis();
        public CompletableFuture<String> future;
        /** Cached result once the future completes. */
        public volatile String cachedResult;
        /** Set when the cached result exceeds the oversized threshold (short TTL). */
        public volatile boolean oversized;
        public volatile long completedAt;
        /**
         * What the run has finished so far, for a caller that is still waiting.
         * <p>
         * A run that outlives the soft timeout answers Pending, and until this field existed that
         * answer carried the elapsed milliseconds and nothing else: the caller could not tell a run
         * that had applied five of six operations from one that had applied none. Work with steps
         * publishes a line here after each; work without steps leaves it null and the answer is
         * unchanged.
         * </p>
         */
        public volatile String progressNote;

        /**
         * What the run is about, for a caller that has no runKey.
         * <p>
         * A refused call never returns one, which left the only exit addressed by something the
         * caller could not obtain. Set by whoever starts the work; null leaves the entry
         * unaddressable this way, exactly as before.
         * </p>
         */
        public volatile String subject;

        PendingEntry(String runKey)
        {
            this.runKey = runKey;
        }

        /**
         * Waits up to the given milliseconds for the future to complete. Returns
         * the cached result when ready, or {@code null} on timeout.
         */
        public String await(long timeoutMs)
        {
            if (cachedResult != null)
            {
                return cachedResult;
            }
            try
            {
                return future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            catch (java.util.concurrent.TimeoutException timeout)
            {
                return null;
            }
            catch (Exception e)
            {
                // Same shape as a failure raised inside the work. Waiting for it and running it
                // are two ways to meet the same exception, and answering them differently made
                // the caller's handling depend on which one happened to reach it first.
                return failed(e, runKey);
            }
        }

        public boolean isDone()
        {
            return cachedResult != null || (future != null && future.isDone());
        }

        public long elapsedMs()
        {
            long end = completedAt > 0 ? completedAt : System.currentTimeMillis();
            return end - startedAt;
        }
    }
}
