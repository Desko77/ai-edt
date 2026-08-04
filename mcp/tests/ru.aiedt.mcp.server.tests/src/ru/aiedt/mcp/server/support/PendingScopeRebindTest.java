/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Test;

import ru.aiedt.mcp.server.RunningToolCall;

/**
 * Verifies the Pending scope re-entry: work dispatched to the async executor runs
 * with the dispatching call's scope bound, so a cooperative loop on that thread can
 * read the call's cancellation flag. Without a dispatch scope, none is bound. This is
 * the prerequisite that lets a cancel reach the heavy loops that do not run inline.
 */
public class PendingScopeRebindTest
{
    /** Guards against a scope leaking from this thread into another test if an assert throws early. */
    @After
    public void unbind()
    {
        ToolCallScope.exit();
    }

    @Test
    public void asyncWorkSeesACancelRaisedAfterDispatch() throws Exception
    {
        RunningToolCall call = new RunningToolCall(null, "t", 1); //$NON-NLS-1$
        ToolCallScope scope = ToolCallScope.create(call);
        ToolCallScope.enter(scope);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try
        {
            String key = PendingWorkRegistry.computeRunKey("PendingScopeRebindTest", "late-cancel"); //$NON-NLS-1$ //$NON-NLS-2$
            PendingWorkRegistry.PendingEntry entry = PendingWorkRegistry.GENERIC.getOrStart(key, () ->
            {
                started.countDown();
                try
                {
                    release.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
                ToolCallScope inWork = ToolCallScope.current();
                return inWork != null && inWork.cancellation().isCancelled() ? "CANCELLED" : "NOT"; //$NON-NLS-1$ //$NON-NLS-2$
            });
            started.await(5, TimeUnit.SECONDS);
            call.cancellation().cancel("operator"); // raise the flag after the work has begun //$NON-NLS-1$
            release.countDown();
            String result = entry.await(5000L);
            PendingWorkRegistry.GENERIC.remove(key);
            assertEquals("async work must see a cancel raised on the call after dispatch", //$NON-NLS-1$
                "CANCELLED", result); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
            ToolCallScope.exit();
        }
    }

    @Test
    public void asyncWorkRunsInsideTheDispatchScope() throws Exception
    {
        RunningToolCall call = new RunningToolCall(null, "t", 1); //$NON-NLS-1$
        ToolCallScope scope = ToolCallScope.create(call);
        ToolCallScope.enter(scope);
        try
        {
            String key = PendingWorkRegistry.computeRunKey("PendingScopeRebindTest", "with-scope"); //$NON-NLS-1$ //$NON-NLS-2$
            // The work runs under a lightweight scope, but it carries the very same cancellation flag
            // as the dispatching call - which is the whole point (a loop there reads that flag).
            PendingWorkRegistry.PendingEntry entry = PendingWorkRegistry.GENERIC.getOrStart(key,
                () -> ToolCallScope.current() != null
                    && ToolCallScope.current().cancellation() == scope.cancellation() ? "SAME" : "OTHER"); //$NON-NLS-1$ //$NON-NLS-2$
            String result = entry.await(5000L);
            PendingWorkRegistry.GENERIC.remove(key);
            assertEquals("async work must see the dispatching call's cancellation flag", //$NON-NLS-1$
                "SAME", result); //$NON-NLS-1$
        }
        finally
        {
            ToolCallScope.exit();
        }
    }

    @Test
    public void dispatchWithoutAScopeBindsNone() throws Exception
    {
        // No scope entered on this thread, so the work must see none bound - not some
        // stale scope left on the pooled executor thread by an earlier task.
        String key = PendingWorkRegistry.computeRunKey("PendingScopeRebindTest", "no-scope"); //$NON-NLS-1$ //$NON-NLS-2$
        PendingWorkRegistry.PendingEntry entry = PendingWorkRegistry.GENERIC.getOrStart(key,
            () -> ToolCallScope.current() == null ? "NULL" : "BOUND"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = entry.await(5000L);
        PendingWorkRegistry.GENERIC.remove(key);
        assertEquals("no dispatch scope means none is bound in the work", "NULL", result); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
