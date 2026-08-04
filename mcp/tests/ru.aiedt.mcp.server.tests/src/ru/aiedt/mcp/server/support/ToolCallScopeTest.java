/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Test;

import ru.aiedt.mcp.server.support.ToolCallScope.ToolCancelledException;

/**
 * Verifies the {@link ToolCallScope} scaffolding: the thread-local bind/unbind lifecycle (including
 * that a reused thread never sees a stale scope), the one-way cancellation flag, and the unset
 * defaults that later reliability work fills in.
 */
public class ToolCallScopeTest
{
    @After
    public void clearScope()
    {
        // Never let a leaked scope bleed into the next test on this thread.
        ToolCallScope.exit();
    }

    // ------ Thread-local lifecycle ------

    @Test
    public void currentIsNullWithoutAScope()
    {
        ToolCallScope.exit();
        assertNull(ToolCallScope.current());
    }

    @Test
    public void enterBindsAndExitClears()
    {
        ToolCallScope scope = ToolCallScope.create(null);
        ToolCallScope.enter(scope);
        assertSame(scope, ToolCallScope.current());
        ToolCallScope.exit();
        assertNull(ToolCallScope.current());
    }

    @Test
    public void reusedThreadNeverSeesAStaleScope()
    {
        // Simulates two sequential tool calls on the same worker: the second must not inherit the
        // first scope even though exit() ran in between.
        ToolCallScope first = ToolCallScope.create(null);
        ToolCallScope.enter(first);
        ToolCallScope.exit();

        assertNull(ToolCallScope.current());

        ToolCallScope second = ToolCallScope.create(null);
        ToolCallScope.enter(second);
        assertSame(second, ToolCallScope.current());
    }

    @Test
    public void scopeIsNotSharedBetweenThreads() throws InterruptedException
    {
        ToolCallScope.enter(ToolCallScope.create(null));
        final ToolCallScope[] seen = new ToolCallScope[1];
        final boolean[] ran = {false};
        Thread other = new Thread(() -> {
            seen[0] = ToolCallScope.current();
            ran[0] = true;
        });
        other.start();
        other.join();
        assertTrue(ran[0]);
        assertNull("a fresh thread must not inherit the scope", seen[0]); //$NON-NLS-1$
    }

    // ------ Cancellation ------

    @Test
    public void freshScopeIsNotCancelled()
    {
        ToolCallScope scope = ToolCallScope.create(null);
        assertFalse(scope.cancellation().isCancelled());
        assertNull(scope.cancellation().reason());
        scope.cancellation().throwIfCancelled(); // must not throw
    }

    @Test
    public void cancelSetsFlagAndReason()
    {
        ToolCallScope scope = ToolCallScope.create(null);
        scope.cancellation().cancel("operator stopped it"); //$NON-NLS-1$
        assertTrue(scope.cancellation().isCancelled());
        assertEquals("operator stopped it", scope.cancellation().reason()); //$NON-NLS-1$
    }

    @Test
    public void cancelIsIdempotentFirstReasonWins()
    {
        ToolCallScope scope = ToolCallScope.create(null);
        scope.cancellation().cancel("first"); //$NON-NLS-1$
        scope.cancellation().cancel("second"); //$NON-NLS-1$
        assertEquals("first", scope.cancellation().reason()); //$NON-NLS-1$
    }

    @Test
    public void throwIfCancelledUnwindsWithReason()
    {
        ToolCallScope scope = ToolCallScope.create(null);
        scope.cancellation().cancel("stop"); //$NON-NLS-1$
        try
        {
            scope.cancellation().throwIfCancelled();
            fail("expected ToolCancelledException"); //$NON-NLS-1$
        }
        catch (ToolCancelledException e)
        {
            assertEquals("stop", e.getMessage()); //$NON-NLS-1$
        }
    }

    // ------ Defaults and simple state ------

    @Test
    public void defaultsAreUnset()
    {
        ToolCallScope scope = ToolCallScope.create(null);
        assertEquals(ToolCallScope.UNSET, scope.responseByteLimit());
        assertEquals(ToolCallScope.UNSET, scope.timeoutSeconds());
        assertNull(scope.operationId());
        assertFalse(scope.isClientGone());
    }

    @Test
    public void settersRoundTrip()
    {
        ToolCallScope scope = ToolCallScope.create(null);
        scope.setResponseByteLimit(2048L);
        scope.setTimeoutSeconds(30L);
        scope.setOperationId("op-1"); //$NON-NLS-1$
        scope.markClientGone();
        assertEquals(2048L, scope.responseByteLimit());
        assertEquals(30L, scope.timeoutSeconds());
        assertEquals("op-1", scope.operationId()); //$NON-NLS-1$
        assertTrue(scope.isClientGone());
    }

    // ------ Concurrency ------

    @Test
    public void concurrentCancelPublishesReasonWithFlag() throws InterruptedException
    {
        // Regression guard for the publish order in cancel(): a reader on another thread that observes
        // the cancelled flag must also observe the reason (no flag-set-but-reason-null window).
        // Repeated to exercise the cross-thread visibility rather than rely on a single lucky timing.
        for (int i = 0; i < 500; i++)
        {
            final ToolCallScope scope = ToolCallScope.create(null);
            final String[] observedReason = new String[1];
            final boolean[] sawFlag = {false};
            Thread reader = new Thread(() -> {
                while (!scope.cancellation().isCancelled())
                {
                    Thread.onSpinWait();
                }
                sawFlag[0] = true;
                observedReason[0] = scope.cancellation().reason();
            });
            reader.start();
            scope.cancellation().cancel("stopped"); //$NON-NLS-1$
            reader.join(5000);
            assertTrue("reader should observe the flag", sawFlag[0]); //$NON-NLS-1$
            assertEquals("reason must be visible whenever the flag is", //$NON-NLS-1$
                "stopped", observedReason[0]); //$NON-NLS-1$
        }
    }
}
