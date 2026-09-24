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
     * Async backend for {@code export_infobase_objects} infobase exports.
     * <p>
     * Not {@link #EXPORT}: that one belongs to {@code export_object}'s .epf/.erf builds. This
     * operation writes files and disconnects EDT from an infobase, so its runKeys are unique per
     * call - two identical calls are two runs, never one coalesced future and never a replayed
     * cached answer.
     * </p>
     */
    public static final PendingWorkRegistry EXPORT_INFOBASE = new PendingWorkRegistry(
        "export_infobase_objects", "export-infobase-async"); //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * TTL for the scenario domain, whose longest accepted run is an hour.
     * <p>
     * The default would evict a run that is still executing: its entry and its eventual result
     * would go while the client carried on, and a poll would report a missing run over a live one.
     * </p>
     */
    private static final long VANESSA_ABANDONED_TTL_MS = 70 * 60 * 1000L;

    /**
     * Scenario runs.
     * <p>
     * Not {@link #GENERIC}: that one is reserved for reads that can be replayed, and a run drives a
     * client against an infobase. One at a time, because two clients playing scenarios into the
     * same base would be reading each other's work.
     * </p>
     */
    public static final PendingWorkRegistry VANESSA = new PendingWorkRegistry(
        "vanessa", "vanessa-async", 1, VANESSA_ABANDONED_TTL_MS); //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * How long a question that nobody came back for is kept.
     * <p>
     * The longest accepted question is {@code timeoutSeconds} of 1800. The entry has to outlive
     * that, plus a margin, or a question still running is dropped and a later poll reports it
     * missing.
     * </p>
     */
    private static final long NAPARNIK_ABANDONED_TTL_MS = 40L * 60L * 1000L;

    /**
     * Questions sent to 1C:Naparnik.
     * <p>
     * One at a time, on one thread. The key is unique per question: two identical questions are
     * two runs, and a finished answer is not replayed for a later one.
     * </p>
     */
    public static final PendingWorkRegistry NAPARNIK = new PendingWorkRegistry(
        "naparnik", "naparnik-async", 1, NAPARNIK_ABANDONED_TTL_MS); //$NON-NLS-1$ //$NON-NLS-2$

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

    /** Default TTL for never-completed entries (runaway). 30 minutes. */
    private static final long ABANDONED_TTL_MS = 30 * 60 * 1000L;

    private final String domainLabel;

    /**
     * What actually stops this domain's work, when anything can.
     * <p>
     * {@link #cancel} completes the tracking future, which stops work that has not begun and
     * reaches nothing that has. A domain that owns a process it can destroy installs it here, so a
     * cancel arriving through the task interface stops the same thing a cancel through the tool
     * would.
     * </p>
     */
    private volatile Function<String, StopOutcome> stopper;

    /** How long a never-completed entry is kept. */
    private final long abandonedTtlMs;

    private final ConcurrentHashMap<String, PendingEntry> entries = new ConcurrentHashMap<>();

    /**
     * Runs inside a supplier before the body claims its start, when a test has set it.
     * <p>
     * Production leaves it {@code null}. The consumer is handed the entry so the test can cancel
     * that run even when the supplier is still inside {@code computeIfAbsent}. A supplier parked
     * here has been entered and has not yet begun, which is the window in which a cancel must
     * either keep the body from running or keep the permit until the body leaves.
     * </p>
     */
    static volatile java.util.function.Consumer<PendingEntry> beforeWorkClaim;

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
        this(domainLabel, threadPrefix, maxPool, ABANDONED_TTL_MS);
    }

    /**
     * @param domainLabel human-readable domain used in error logs
     * @param threadPrefix worker-thread name prefix
     * @param maxPool executor ceiling
     * @param abandonedTtlMs how long a never-completed entry is kept. A domain must keep an entry
     *            longer than its longest run, or a run still executing loses its result.
     */
    private PendingWorkRegistry(String domainLabel, String threadPrefix, int maxPool,
        long abandonedTtlMs)
    {
        this.domainLabel = domainLabel;
        this.abandonedTtlMs = abandonedTtlMs;

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
     * <p>
     * When a call scope is current, the entry it dispatches is also noted on that scope, so the
     * caller's answer can find the run again after the registry's own tracking of it is dropped.
     * </p>
     *
     * @param runKey the coalescing key, never {@code null}
     * @param work the body, handed the entry it runs under so it can set
     *            {@link PendingEntry#progressNote}; never {@code null}
     * @return the entry, whether freshly started or already running
     */
    public PendingEntry getOrStart(String runKey, Function<PendingEntry, String> work)
    {
        // Capture the calling (worker) thread's whole call scope and re-enter it on the executor
        // thread for the duration of the work. The scope carries more than the cancellation flag
        // now: it carries the heavy-permit ticket a nested heavy call inherits, and the work runs
        // under the permit its call holds. The call graph - the RunningToolCall and its
        // HttpExchange - stays reachable for the run's duration with it; a run that answers Pending
        // goes on after its exchange is closed, and everything a cancel or a nested call needs has
        // to outlive that exchange with it.
        //
        // On coalesce the first caller's scope wins - a later caller's cancel does not reach the
        // shared work. That is right for a read whose result the later caller still wants; it also
        // means a second caller cannot cancel the first's work.
        ToolCallScope current = ToolCallScope.current();
        ToolCallScope.Cancellation dispatchCancellation = current != null ? current.cancellation() : null;
        ru.aiedt.mcp.server.RunningToolCall starter = current != null ? current.runningCall() : null;
        PendingEntry started = entries.computeIfAbsent(runKey, k ->
        {
            PendingEntry entry = new PendingEntry(k);
            // The scope the work runs under, kept where it outlives the request that made it. A
            // run that answers Pending goes on after its exchange is closed, and until the entry
            // held this there was nothing left for a withdrawal to raise.
            entry.scope = current;
            entry.cancellation = dispatchCancellation;
            if (starter != null)
            {
                entry.ownerSession = starter.getSessionId();
                Object requested = starter.getRequestId();
                entry.ownerRequest = requested == null ? null : String.valueOf(requested);
            }
            entry.future = CompletableFuture.supplyAsync(() ->
            {
                // A test parks the supplier here, after it has been entered and before it claims
                // the start: the window a cancel must close. Production leaves the gate unset.
                java.util.function.Consumer<PendingEntry> gate = beforeWorkClaim;
                if (gate != null)
                {
                    gate.accept(entry);
                }
                // The claim and a cancel's "never began" share one lock. Losing it means the body
                // must not run: the permit, if any, was already returned.
                if (!entry.claimWorkStart())
                {
                    return null;
                }
                ToolCallScope previous = ToolCallScope.current();
                if (current != null)
                {
                    ToolCallScope.enter(current);
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
                    if (current != null)
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
                    // The signal a transferred permit waits on. The body's own exit, not the
                    // future's completion: a cancel completes the future without reaching work
                    // that is already running, and the permit belongs to the work, not to the
                    // tracking of it.
                    entry.workExited();
                }
            }, executor);
            entry.future.whenComplete((result, throwable) ->
            {
                String cached = result != null ? result : failed(throwable, entry.runKey);
                entry.cachedResult = cached;
                entry.oversized = cached.length() > MAX_CACHED_RESULT_CHARS;
                entry.completedAt = System.currentTimeMillis();
                // The call graph the scope carries - the run record and its exchange - is done
                // with once the run completes. Held here, a completed-not-retrieved entry pinned
                // it until somebody came to collect or the TTL threw it out.
                entry.scope = null;
                // A future cancelled before its task left the queue never runs the body whose
                // exit would return a transferred permit; this is the only door left for that
                // permit, and the lock tells it apart from a body already in flight.
                entry.settleIfWorkNeverBegan();
            });
            return entry;
        });
        if (current != null)
        {
            // The starter keeps a direct reference to the run it started: a permit spent on the
            // answer that names this key must wait on the work's own exit even when the tracking
            // - this map's entry - is dropped while the work still runs.
            current.notePendingEntry(runKey, started);
        }
        return started;
    }

    /**
     * Raises the flag of a run started by this session and this request, wherever it is running.
     * <p>
     * The one path that reaches work which outlived the call that started it. A run belongs to
     * whoever started it: a withdrawal naming another session's request raises nothing, because a
     * second caller waiting on the same key wants the result rather than the end of it. There is
     * nowhere in the protocol to say so - a cancellation is a notification, answered with 202 and no
     * body - so what happened is said in the log.
     * </p>
     *
     * @param sessionId the session the withdrawal came from; a null one owns nothing
     * @param requestId the request it names
     * @param reason what to record as the cause
     * @return the runKey whose flag was raised, or <code>null</code> when nothing matched
     */
    public static String withdrawOwnedRun(String sessionId, Object requestId, String reason)
    {
        if (sessionId == null || requestId == null)
        {
            return null;
        }
        String named = String.valueOf(requestId);
        for (PendingWorkRegistry domain : domains())
        {
            for (Map.Entry<String, PendingEntry> each : domain.entries.entrySet())
            {
                PendingEntry entry = each.getValue();
                if (entry == null || entry.cancellation == null
                    || !sessionId.equals(entry.ownerSession) || !named.equals(entry.ownerRequest))
                {
                    continue;
                }
                entry.cancellation.cancel(reason);
                return each.getKey();
            }
        }
        return null;
    }

    /**
     * Every domain, in a fixed order.
     * <p>
     * A runKey is unique only within its domain, so anything holding a bare key - a task handle
     * handed to a client, for one - has to be able to find which domain issued it. One map lookup
     * per domain settles that, and the alternative (threading the domain through every layer that
     * only ever passes a key) buys nothing.
     * </p>
     * <p>
     * Every registry declared above belongs here. One that is left out still runs its work, and
     * still answers a poll made straight to its tool - but a bare key from it resolves to no
     * domain, so it never becomes a task.
     * </p>
     *
     * @return the registries, in declaration order
     */
    public static List<PendingWorkRegistry> domains()
    {
        return Collections.unmodifiableList(
            Arrays.asList(UPDATE, EXPORT, EXPORT_INFOBASE, REFERENCES, IMPORT_BINARY, VANESSA,
                NAPARNIK, GENERIC));
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
     * Every entry still tracked, running or completed-not-yet-collected, of one kind of work.
     * <p>
     * One registry may carry more than one tool - UPDATE also carries pending
     * {@code edit_metadata} calls - and a read that cannot tell them apart answers questions about
     * one tool with work belonging to another. An entry whose kind was never set counts for every
     * kind, the way an entry without a subject counts for every subject: it cannot be told apart,
     * and hiding it would say less than is known.
     * </p>
     * <p>
     * A snapshot over a live map: an entry may complete or be evicted after the read. That is what
     * a status read reports, not an error.
     * </p>
     *
     * @param kind the kind of work; <code>null</code> or empty lists everything still tracked.
     * @return the entries, never <code>null</code>
     */
    public java.util.List<PendingEntry> trackedOf(String kind)
    {
        java.util.List<PendingEntry> tracked = new java.util.ArrayList<>();
        for (PendingEntry entry : entries.values())
        {
            if (kind == null || kind.isEmpty() || entry.workKind == null || kind.equals(entry.workKind))
            {
                tracked.add(entry);
            }
        }
        return tracked;
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
     * Drops one entry, and only if it is still the one the caller was looking at.
     * <p>
     * Removing by key alone removed whatever was under it, which after a coalescing window is a
     * different run: a caller collecting a finished result deleted the entry a second caller had
     * just started, and that run then executed with nothing tracking it.
     * </p>
     *
     * @param runKey the key.
     * @param entry the entry the caller read; nothing happens when the key holds another.
     * @return whether that entry was the one removed
     */
    public boolean remove(String runKey, PendingEntry entry)
    {
        return runKey != null && entry != null && entries.remove(runKey, entry);
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
     * <p>
     * A domain that has declared a stopper through {@link #stopsWith} is the exception: its work
     * is stopped as well, because it owns something it can stop. That is what a cancel arriving
     * as {@code tasks/cancel} goes through.
     *
     * @param runKey the key.
     * @return true if a tracked entry existed and was removed
     */
    public boolean cancel(String runKey)
    {
        return drop(runKey, true);
    }

    /**
     * Detaches a runKey without touching the work.
     * <p>
     * For a caller that has already stopped the work itself. Asking the stopper again would find
     * nothing, and anything the stopper does besides stopping would happen twice.
     * </p>
     *
     * @param runKey the key.
     * @return true if a tracked entry existed and was removed
     */
    public boolean detach(String runKey)
    {
        return drop(runKey, false);
    }

    /**
     * Drops an entry and completes its tracking future.
     *
     * @param runKey the key.
     * @param stopTheWork whether to ask the domain's stopper.
     * @return true if a tracked entry existed and was removed
     */
    private boolean drop(String runKey, boolean stopTheWork)
    {
        boolean known = dropEntry(runKey);
        // After the future, so work that has not begun is already stopped by then and the stopper
        // only has to deal with work that has. Asked even for an unknown key: the tool's own cancel
        // removes the entry first, and the process it owns still has to go.
        Function<String, StopOutcome> stopsIt = stopTheWork ? stopper : null;
        if (stopsIt != null)
        {
            stopsIt.apply(runKey);
        }
        return known;
    }

    /**
     * Drops the entry and completes its tracking future.
     *
     * @param runKey the key.
     * @return whether the key was known
     */
    private boolean dropEntry(String runKey)
    {
        PendingEntry entry = entries.remove(runKey);
        boolean known = entry != null;
        if (known && entry.future != null && !entry.future.isDone())
        {
            entry.future.cancel(true);
        }
        return known;
    }

    /**
     * What stopping a domain's work came to.
     * <p>
     * Three answers rather than two, because asking a process to stop and its having stopped are
     * different facts, and a caller told the second when only the first happened will act as
     * though the work is done with whatever it was holding.
     * </p>
     */
    public enum StopOutcome
    {
        /** There was nothing running to stop. */
        NOTHING_TO_STOP,
        /** The work is stopped. */
        STOPPED,
        /** It was told to stop and had not stopped. */
        STILL_RUNNING
    }

    /**
     * Declares what stops this domain's work.
     *
     * @param stopper given a runKey, stops that run's work and reports what that came to.
     */
    public void stopsWith(Function<String, StopOutcome> stopper)
    {
        this.stopper = stopper;
    }

    /**
     * Detaches a runKey, asks the domain to stop the work, and reports what stopping came to.
     *
     * @param runKey the key.
     * @return what the domain's stopper reported, or {@link StopOutcome#NOTHING_TO_STOP} when the
     *         domain has none
     */
    public StopOutcome cancelAndStop(String runKey)
    {
        Function<String, StopOutcome> stopsIt = stopper;
        dropEntry(runKey);
        if (stopsIt == null)
        {
            return StopOutcome.NOTHING_TO_STOP;
        }
        StopOutcome outcome = stopsIt.apply(runKey);
        return outcome == null ? StopOutcome.NOTHING_TO_STOP : outcome;
    }

    /**
     * Whether this domain has a run executing or waiting to.
     * <p>
     * Weakly consistent, like the other reads over the live map. A domain that takes one run at a
     * time uses it to refuse a second submission rather than queue it: queued time counts against
     * the entry's own lifetime, so a run accepted behind a long one can expire before it begins.
     * </p>
     *
     * @return whether any entry has not completed
     */
    public boolean isBusy()
    {
        for (PendingEntry entry : entries.values())
        {
            if (entry.completedAt == 0)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * The keys of the runs that have not finished.
     * <p>
     * A key belongs to one run and is handed to its caller with the Pending answer. A caller that
     * never received it - it went away while waiting - leaves a run nobody can name, and a domain
     * that takes one run at a time is then held by something unaddressable. This is how a refusal
     * says which key it is refusing on behalf of.
     * </p>
     *
     * @return the unfinished keys, in no particular order; never null
     */
    public List<String> unfinishedKeys()
    {
        List<String> keys = new java.util.ArrayList<>();
        for (Map.Entry<String, PendingEntry> entry : entries.entrySet())
        {
            if (entry.getValue().completedAt == 0)
            {
                keys.add(entry.getKey());
            }
        }
        return keys;
    }

    /**
     * @return whether cancelling in this domain stops the work rather than only the waiting
     */
    public boolean stopsItsWork()
    {
        return stopper != null;
    }

    /**
     * @return how long a never-completed entry is kept, in milliseconds
     */
    public long abandonedTtlMs()
    {
        return abandonedTtlMs;
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
            else if (entry.completedAt == 0
                && now - (entry.beganAt > 0 ? entry.beganAt : entry.startedAt) > abandonedTtlMs)
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

    /**
     * Per-runKey state for the registry.
     */
    public static final class PendingEntry
    {
        public final String runKey;
        public final long startedAt = System.currentTimeMillis();

        /**
         * When the work actually began, or 0 while it is still waiting for a worker.
         * <p>
         * Abandonment means running too long, not waiting too long. Counting from submission made
         * a run that queued behind a long one look abandoned while it was still executing, and
         * pruning it cancels a future that reaches nothing already running - so the work carried
         * on with its entry gone and nobody able to reach it.
         * </p>
         */
        public volatile long beganAt;
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
         * The scope the work runs under, held where it outlives the request that started the run.
         * <p>
         * A run that answers Pending continues after its exchange is closed. The scope is what the
         * work re-enters on the executor thread: its cancellation flag is what a withdrawal raises,
         * and its heavy-permit ticket is what a nested heavy call inside the work inherits. Null
         * when the work was started outside a tool call.
         * </p>
         */
        public volatile ToolCallScope scope;

        /**
         * The name a call must declare, through {@code IMcpTool.resumes}, to poll this run.
         * <p>
         * A live {@code runKey} exempts a call from the heavy-tool gates only when that call
         * declares this name for this entry's domain. The name is whoever actually polls - for a
         * metadata batch that is {@code edit_metadata}, not the heavy operation the batch may
         * carry. A matching route is not a declaration.
         * </p>
         */
        public volatile String startedBy;

        /**
         * Coordinates the work's begin and exit with a permit handed over mid-run, so the permit
         * is returned exactly once whichever of the two arrives first.
         */
        private final Object workLife = new Object();

        private boolean workBegan;

        private boolean workExitSettled;

        private final List<Runnable> onWorkExit = new java.util.ArrayList<>(2);

        /**
         * The flag the work watches, held where it outlives the request that started the run.
         * <p>
         * A run that answers Pending continues after its exchange is closed, and the flag belonged
         * to that exchange's call: a withdrawal arriving afterwards found nothing to raise. Null
         * when the work was started outside a tool call.
         * </p>
         */
        public volatile ru.aiedt.mcp.server.support.ToolCallScope.Cancellation cancellation;

        /** The session that started the run; only a withdrawal from it stops the work. */
        public volatile String ownerSession;

        /** The request that started the run, as a withdrawal names it. */
        public volatile String ownerRequest;

        /**
         * What the run is about, for a caller that has no runKey.
         * <p>
         * A refused call never returns one, which left the only exit addressed by something the
         * caller could not obtain. Set by whoever starts the work; null leaves the entry
         * unaddressable this way, exactly as before.
         * </p>
         */
        public volatile String subject;

        /**
         * The kind of work, when two tools share one registry.
         * <p>
         * The UPDATE registry also carries pending {@code edit_metadata} calls, and a key answers
         * the wrong question when it can belong to either: the status read of an update would name
         * a metadata write as an update, and a key handed back would resume work the caller never
         * started. Set by whoever starts the work; null counts as a match for a kind-filtered
         * query, the same way a null subject leaves an entry unaddressable by subject.
         * </p>
         */
        public volatile String workKind;

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

        /**
         * Whether the body has left the executor, or was settled as never having begun.
         * <p>
         * The tracking future is a different fact. A cancel or a detach completes it while a
         * body that already claimed its start keeps running, so {@link #isDone()} is then true
         * for work that still holds the session. A permit follows this, not the future.
         * </p>
         *
         * @return {@code true} once {@link #workExited()} or {@link #settleIfWorkNeverBegan()}
         *         has settled the run
         */
        boolean workHasLeft()
        {
            synchronized (workLife)
            {
                return workExitSettled;
            }
        }

        /**
         * Marks the body as begun, or refuses when a cancel already settled this run.
         * <p>
         * The decision and the mark are one critical section with
         * {@link #settleIfWorkNeverBegan()}. A supplier that loses the race leaves without
         * running, so a permit handed to the entry cannot come back while the body is still
         * alive - the body is not alive.
         * </p>
         *
         * @return {@code false} when the body must not run
         */
        boolean claimWorkStart()
        {
            synchronized (workLife)
            {
                if (workExitSettled)
                {
                    return false;
                }
                beganAt = System.currentTimeMillis();
                workBegan = true;
                return true;
            }
        }

        /**
         * Hands a permit release to the work's own exit.
         * <p>
         * The release runs when the body leaves the executor - in success, in failure, or after a
         * cancel that could not reach it - and never on the tracking future's completion alone,
         * which a cancel moves while the body still runs.
         * </p>
         *
         * @param release what returns the permit; runs immediately when the work already left
         */
        void attachWorkExit(Runnable release)
        {
            boolean settled;
            synchronized (workLife)
            {
                settled = workExitSettled;
                if (!settled)
                {
                    onWorkExit.add(release);
                }
            }
            if (settled)
            {
                release.run();
            }
        }

        /**
         * The body's exit: settles the work-life door and returns every permit handed to it.
         * <p>
         * Called from the executor body's {@code finally} - the one place that knows the work
         * stopped consuming the session's resources.
         * </p>
         */
        void workExited()
        {
            for (Runnable release : settleWorkExit())
            {
                release.run();
            }
        }

        /**
         * Settles the door for a future that completed without its body ever running, so a permit
         * transferred to a run cancelled before it began does not wait on an exit that never
         * comes.
         * <p>
         * The check and the settlement are the same critical section as {@link #claimWorkStart()}.
         * A supplier that has not claimed yet either observes the settlement and does not run, or
         * claims first and this method leaves the permit for the body's own exit.
         * </p>
         */
        void settleIfWorkNeverBegan()
        {
            List<Runnable> releases = null;
            synchronized (workLife)
            {
                if (workBegan || workExitSettled)
                {
                    return;
                }
                workExitSettled = true;
                if (!onWorkExit.isEmpty())
                {
                    releases = new java.util.ArrayList<>(onWorkExit);
                    onWorkExit.clear();
                }
            }
            if (releases == null)
            {
                return;
            }
            for (Runnable release : releases)
            {
                release.run();
            }
        }

        /**
         * Whether {@code name} is the starter this run was stamped with.
         * <p>
         * The road asks this only after the call has declared that it resumes that starter. A
         * matching route is not a declaration, and this method does not treat one as such.
         * </p>
         *
         * @param name the starter a call declared, or the tool the road's generic wrapper runs by
         *            its own name; may be {@code null}
         * @return {@code true} when this run was started under that name
         */
        public boolean resumableBy(String name)
        {
            return name != null && name.equals(startedBy);
        }

        private List<Runnable> settleWorkExit()
        {
            synchronized (workLife)
            {
                if (workExitSettled)
                {
                    return Collections.emptyList();
                }
                workExitSettled = true;
                if (onWorkExit.isEmpty())
                {
                    return Collections.emptyList();
                }
                List<Runnable> toRun = new java.util.ArrayList<>(onWorkExit);
                onWorkExit.clear();
                return toRun;
            }
        }

        public long elapsedMs()
        {
            long end = completedAt > 0 ? completedAt : System.currentTimeMillis();
            return end - startedAt;
        }
    }
}
