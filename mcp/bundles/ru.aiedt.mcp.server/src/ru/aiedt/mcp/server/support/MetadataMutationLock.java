/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.concurrent.locks.ReentrantLock;

/**
 * A single process-wide lock that a metadata mutation holds so a batch of edits runs
 * as one uninterrupted unit.
 * <p>
 * A single {@code edit_metadata} operation cannot race another: it runs its BM write on
 * the one EDT UI thread via {@code Display.syncExec}, which serialises them. A
 * {@code batch}, though, opens a <em>separate</em> {@code syncExec} for each sub-operation,
 * so between two sub-operations the UI thread is free and another edit can slip in and
 * apply a change against the same object mid-batch. Holding this lock across the whole
 * batch (and, for symmetry and in case the UI-thread funnel is ever relaxed, across a
 * single operation too) closes that window.
 * <p>
 * It is deliberately coarse - one lock, not one per project or object. Because every write
 * funnels through the one UI thread today, finer keys would buy no real parallelism; the
 * cost is only that two edits to unrelated projects wait on each other, which is
 * negligible for the 1-2 concurrent clients this serves. Reentrant, so a batch that itself
 * runs a nested exclusive section does not self-deadlock. Other mutators may adopt it later
 * for cross-tool batch atomicity; today only {@code edit_metadata} uses it.
 */
public final class MetadataMutationLock
{
    private static final ReentrantLock LOCK = new ReentrantLock();

    private MetadataMutationLock()
    {
    }

    /** Acquires the lock, blocking until it is free. Always pair with {@link #release()} in a finally. */
    public static void acquire()
    {
        LOCK.lock();
    }

    /** Releases the lock. Call once per {@link #acquire()}, from a finally. */
    public static void release()
    {
        LOCK.unlock();
    }

    /** Whether the lock is currently held by any thread, for diagnostics and tests. */
    public static boolean isHeld()
    {
        return LOCK.isLocked();
    }
}
