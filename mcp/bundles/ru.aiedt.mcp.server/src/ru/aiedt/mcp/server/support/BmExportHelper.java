/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;

import ru.aiedt.mcp.server.Activator;

/**
 * Generalized BM persistence helper. Wraps
 * {@code IBmModelManager.forceExport(IDtProject, List<String>)} with a
 * subsequent {@code waitComputation()} on the EXP_O / EXP_B / FORM_EXT
 * derived-data segments, mirroring a known upstream contract
 * helper.
 * <p>
 * Used by every tool that mutates the BM model:
 * <ul>
 *   <li>{@code ModuleSourceWriter} (replaces the inline forceExport call
 *       added in 1.31).</li>
 *   <li>{@code EditFormTool} (already has a private {@code persistFormChanges} -
 *       refactor target for 1.40).</li>
 *   <li>{@code EditMetadataTool} (every operation that lands a write).</li>
 * </ul>
 */
public final class BmExportHelper
{
    /**
     * Derived-data segment names expected after a write. Discovered from the
     * reference upstream helper (BmExportHelper.DD_SEGMENT_*).
     */
    public static final String DD_SEGMENT_EXPORT_OBJECT = "EXP_O"; //$NON-NLS-1$
    public static final String DD_SEGMENT_EXPORT_BLOB = "EXP_B"; //$NON-NLS-1$
    public static final String DD_SEGMENT_FORM_EXT = "FORM_EXT"; //$NON-NLS-1$

    /** Default soft cap for the wait phase (10s). */
    private static final long DEFAULT_WAIT_TIMEOUT_MS = 10_000L;

    /** Max concurrent {@code waitModelSynchronization} daemon threads plugin-wide. */
    private static final int MAX_INFLIGHT_SYNC_WAITS = 16;

    /**
     * Caps the number of live {@code waitModelSynchronization} daemon threads
     * across the whole plugin. A stuck synchronization manager (Row 42) would
     * otherwise spawn one blocked daemon per mutation - and per agent retry -
     * without bound. Each wait takes a permit for the life of its daemon and
     * releases it when the vendor call returns; once the cap is hit the flush is
     * clearly not settling, so callers report pending immediately instead of
     * adding another stuck thread. Deliberately NOT a per-project waiter-reuse
     * cache: reusing a waiter that has already returned from the vendor call (but
     * is not yet dead) could falsely report a later save as flushed - each caller
     * gets its own waiter, which provably covers the save it enqueued before
     * starting the wait.
     */
    private static final Semaphore SYNC_WAIT_PERMITS = new Semaphore(MAX_INFLIGHT_SYNC_WAITS);

    private BmExportHelper()
    {
        // utility class
    }

    /**
     * Result of a force-export call.
     */
    public static final class Result
    {
        public List<String> fqns;
        public boolean forceExportOk;
        public boolean waitComputationOk;
        /**
         * {@code true} when the (asynchronous) on-disk flush did not confirm
         * within the wait budget - the BM mutation is committed in memory but
         * the {@code .mdo} on disk may still be stale. The scheduled save keeps
         * running in a background daemon; callers should surface this so an
         * agent knows to re-drive the flush (resync_to_disk) rather than treat
         * the write as fully persisted.
         */
        public boolean syncFlushPending;
        public long forceExportMs;
        public long waitComputationMs;
        public long totalMs;
        public String error;

        public boolean isOk()
        {
            return error == null && forceExportOk;
        }
    }

    /**
     * Forces export of the given top-object FQN and waits for the export
     * derived-data segments to settle. Single-FQN convenience overload.
     */
    public static Result forceExportAndWait(IBmModelManager manager, IProject project, String fqn)
    {
        return forceExportAndWait(manager, project, Collections.singletonList(fqn),
            DEFAULT_WAIT_TIMEOUT_MS);
    }

    /**
     * Bulk overload. {@code fqns} may contain any mix of top-object FQNs
     * (e.g. {@code "Catalog.Products"} and {@code "Form.ItemForm.Form"}).
     */
    public static Result forceExportAndWait(IBmModelManager manager, IProject project,
        List<String> fqns, long waitTimeoutMs)
    {
        Result r = new Result();
        r.fqns = fqns;
        long t0 = System.currentTimeMillis();
        if (manager == null)
        {
            r.error = "IBmModelManager is null"; //$NON-NLS-1$
            return r;
        }
        if (project == null)
        {
            r.error = "project is null"; //$NON-NLS-1$
            return r;
        }
        if (fqns == null || fqns.isEmpty())
        {
            r.error = "fqns is empty"; //$NON-NLS-1$
            return r;
        }

        try
        {
            // Called on the interface. This was reached by name on manager.getClass(),
            // which is the implementation - and an EDT service implementation is
            // routinely not public, so invoke answers IllegalAccessException however
            // public the method itself is. Both getDtProject(String) and the two
            // forceExport overloads are declared by IBmModelManager, so the compiler
            // can bind them and the whole overload probe goes away with the reflection.
            IDtProject dtProject = manager.getDtProject(project.getName());
            if (dtProject == null)
            {
                r.error = "IDtProject not resolved for " + project.getName(); //$NON-NLS-1$
                return r;
            }

            // The List overload lets EDT batch the export once.
            long forceStart = System.currentTimeMillis();
            boolean exportOk = manager.forceExport(dtProject, fqns);
            r.forceExportOk = exportOk;
            r.forceExportMs = System.currentTimeMillis() - forceStart;

            if (!exportOk)
            {
                Activator.logWarning("forceExport returned false for " + fqns //$NON-NLS-1$
                    + " - waitComputation skipped"); //$NON-NLS-1$
            }
            else
            {
                // 1.42.3: forceExport just enqueues a SaveObjectTask via
                // synchronizationManager.scheduleSave(...) - the call returns
                // before .mdo files hit disk. waitComputation tracks derived-data
                // segments (EXP_O / EXP_B / FORM_EXT), which do NOT cover
                // the scheduled save. waitModelSynchronization(IProject) blocks
                // synchronously until the synchronization manager has flushed
                // everything queued for the project, including SaveObjectTask.
                // Without this, child mutations (add_object_attribute, ...)
                // appear in the BM index but never reach disk.
                //
                // Row 42 (2026-07-09): on a large or post-hang config the
                // synchronization manager can be backlogged/stuck, and this
                // otherwise-unbounded wait would hang the whole tool call past
                // any HTTP timeout (agent saw a bare "timeout" while the BM
                // already held the change). Bound it with the wait budget: the
                // mutation is already committed, so on timeout we flag
                // syncFlushPending and let the daemon finish the save in the
                // background instead of blocking.
                SyncWaitOutcome outcome = boundedWaitModelSync(manager, project, waitTimeoutMs);
                if (outcome == SyncWaitOutcome.TIMED_OUT)
                {
                    r.syncFlushPending = true;
                    Activator.logWarning("waitModelSynchronization did not confirm within " //$NON-NLS-1$
                        + waitTimeoutMs + "ms for " + fqns //$NON-NLS-1$
                        + " - BM committed, on-disk flush still pending"); //$NON-NLS-1$
                    // Skip the segment wait: it cannot settle while the flush
                    // that feeds it is still in flight, and would just burn
                    // another full budget for nothing.
                }
                else
                {
                    long waitStart = System.currentTimeMillis();
                    r.waitComputationOk = waitForSegments(manager, dtProject,
                        Arrays.asList(DD_SEGMENT_EXPORT_OBJECT, DD_SEGMENT_EXPORT_BLOB,
                            DD_SEGMENT_FORM_EXT),
                        waitTimeoutMs);
                    r.waitComputationMs = System.currentTimeMillis() - waitStart;
                }
            }
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            r.error = "wait interrupted"; //$NON-NLS-1$
        }
        catch (Throwable t)
        {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            r.error = cause.getClass().getSimpleName() + ": " + cause.getMessage(); //$NON-NLS-1$
            Activator.logWarning("BmExportHelper.forceExportAndWait failed: " + r.error); //$NON-NLS-1$
        }
        r.totalMs = System.currentTimeMillis() - t0;
        return r;
    }

    /** Outcome of the bounded {@code waitModelSynchronization} call. */
    private enum SyncWaitOutcome
    {
        /** The synchronization completed within the budget. */
        COMPLETED,
        /** The wait exceeded the budget - the flush is still running. */
        TIMED_OUT
        // There used to be an UNAVAILABLE for "this EDT build has no
        // waitModelSynchronization(IProject)". It went with the reflection: the call is
        // now bound by the compiler, so a build without the method would not resolve at
        // all rather than take a branch here.
    }

    /**
     * Public boolean form of the bounded model-sync wait. Returns {@code true}
     * when the on-disk flush confirmed within {@code budgetMs} (or the EDT build
     * has no {@code waitModelSynchronization} to bound), {@code false} when it
     * exceeded the budget and is still flushing in the background. Reusable by
     * any path that would otherwise call {@code waitModelSynchronization(IProject)}
     * directly and unbounded (e.g. {@code BmFormHelper.persistFormChanges}), so
     * there is a single bounded implementation of Row 42's fix.
     */
    public static boolean waitModelSyncConfirmed(IBmModelManager manager, IProject project,
        long budgetMs)
    {
        if (manager == null || project == null)
        {
            return false;
        }
        return boundedWaitModelSync(manager, project, budgetMs) != SyncWaitOutcome.TIMED_OUT;
    }

    /**
     * Invokes the (otherwise unbounded) {@code waitModelSynchronization(IProject)}
     * on a daemon thread and joins for at most {@code budgetMs}. The BM mutation
     * that scheduled the save is already committed, so if the flush does not
     * confirm within the budget we return {@link SyncWaitOutcome#TIMED_OUT} and
     * leave the daemon running - the save completes in the background rather
     * than hanging the calling tool. A wait that throws (but returns) is treated
     * as {@link SyncWaitOutcome#COMPLETED} so the caller proceeds to the segment
     * wait exactly as before this bounding was added. Each call gets its own
     * waiter (the save it enqueued is provably covered); the total number of
     * live waiters is capped by {@link #SYNC_WAIT_PERMITS}.
     */
    private static SyncWaitOutcome boundedWaitModelSync(IBmModelManager manager, IProject project,
        long budgetMs)
    {
        // waitModelSynchronization(IProject) is declared by IBmModelManager, so it is
        // called on the interface. Looked up by name on the implementation it could
        // fail two ways - the method missing, which was handled, and the enclosing
        // implementation class not being public, which was not: invoke would have
        // answered IllegalAccessException inside the waiter thread and been logged as
        // a failed wait rather than as an unusable call.
        // Bound the number of concurrent flush-wait daemons plugin-wide so a
        // permanently-stuck sync manager cannot accumulate threads as mutations
        // and retries pile up. No permit free -> the flush is clearly not
        // settling; report pending immediately (EDT still flushes the queued
        // save on its own - the daemon exists only for US to observe it).
        if (!SYNC_WAIT_PERMITS.tryAcquire())
        {
            return SyncWaitOutcome.TIMED_OUT;
        }

        // Own waiter per call: the caller enqueued its save (forceExport) before
        // reaching here, so this wait provably covers it. No waiter reuse means
        // no "already returned from the vendor call but not yet dead" window that
        // could falsely report a later save as flushed.
        Thread waiter = new Thread(() -> {
            try
            {
                manager.waitModelSynchronization(project);
            }
            catch (Throwable ex)
            {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                Activator.logWarning("waitModelSynchronization failed: " + cause.getMessage()); //$NON-NLS-1$
            }
            finally
            {
                SYNC_WAIT_PERMITS.release();
            }
        }, "mcp-bm-flush-wait"); //$NON-NLS-1$
        waiter.setDaemon(true);
        try
        {
            waiter.start();
        }
        catch (Throwable startFailure)
        {
            // Thread creation failed (e.g. OutOfMemoryError "unable to create
            // new native thread") before the waiter body could run its
            // permit-releasing finally - release here so the permit is not
            // leaked out of the bounded pool.
            SYNC_WAIT_PERMITS.release();
            Activator.logWarning("failed to start flush-wait thread: " //$NON-NLS-1$
                + startFailure.getMessage());
            return SyncWaitOutcome.TIMED_OUT;
        }

        try
        {
            waiter.join(budgetMs > 0 ? budgetMs : DEFAULT_WAIT_TIMEOUT_MS);
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return SyncWaitOutcome.TIMED_OUT;
        }
        // Still flushing - leave the daemon running (it releases its permit when
        // the vendor call finally returns), report pending.
        return waiter.isAlive() ? SyncWaitOutcome.TIMED_OUT : SyncWaitOutcome.COMPLETED;
    }

    /**
     * Polls {@code waitComputation(...)} for the given derived-data segments
     * up to the given timeout. The exact method signature varies between EDT
     * versions, so we try a few shapes via reflection. Returns {@code true}
     * when the EDT confirms the segments are computed; {@code false} otherwise.
     */
    private static boolean waitForSegments(IBmModelManager manager, Object dtProject,
        List<String> segments, long timeoutMs) throws InterruptedException
    {
        if (segments == null || segments.isEmpty())
        {
            return false;
        }

        // Candidate signature 1: waitComputation(IDtProject, String[], long timeoutMs)
        // Candidate signature 2: waitComputation(IDtProject, Collection<String>)
        // Candidate signature 3: waitModelSynchronization(IProject) - fallback
        Class<?> dtProjectIface = dtProject.getClass();
        Method best = null;
        for (Method m : manager.getClass().getMethods())
        {
            if (!"waitComputation".equals(m.getName())) //$NON-NLS-1$
            {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            if (params.length >= 1 && params[0].isAssignableFrom(dtProjectIface))
            {
                best = m;
                break;
            }
        }
        if (best != null)
        {
            try
            {
                Object[] args = buildWaitArgs(best, dtProject, segments, timeoutMs);
                Object result = best.invoke(manager, args);
                return !(result instanceof Boolean) || ((Boolean) result).booleanValue();
            }
            catch (Exception e)
            {
                Activator.logWarning("BmExportHelper.waitComputation failed: " + e.getMessage()); //$NON-NLS-1$
            }
        }

        // Last resort: short polling sleep
        Thread.sleep(Math.min(500L, timeoutMs));
        return true;
    }

    private static Object[] buildWaitArgs(Method m, Object dtProject, List<String> segments,
        long timeoutMs)
    {
        Class<?>[] params = m.getParameterTypes();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++)
        {
            Class<?> p = params[i];
            if (p.isAssignableFrom(dtProject.getClass()))
            {
                args[i] = dtProject;
            }
            else if (p == String[].class)
            {
                args[i] = segments.toArray(new String[0]);
            }
            else if (p.isAssignableFrom(java.util.List.class))
            {
                args[i] = segments;
            }
            else if (p == long.class || p == Long.class)
            {
                args[i] = Long.valueOf(timeoutMs);
            }
            else
            {
                args[i] = null; // best-effort
            }
        }
        return args;
    }
}
