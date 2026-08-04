/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.aiedt.mcp.server.support.ToolCallScope;

/**
 * Verifies the cancellation wiring: a call owns its cancellation flag from creation, the scope shares
 * that same instance (not a second one), and a cancel raised on the call - as the request thread does
 * on a cancel signal - is visible through the scope a cooperative loop reads. This is the race-free
 * foundation; the loops that consume the flag are wired in later increments.
 */
public class ToolCallCancellationTest
{
    @Test
    public void callOwnsACancellationFromTheStart()
    {
        RunningToolCall call = new RunningToolCall(null, "t", 1); //$NON-NLS-1$
        assertNotNull(call.cancellation());
        assertFalse(call.cancellation().isCancelled());
    }

    @Test
    public void scopeSharesTheCallsCancellation()
    {
        RunningToolCall call = new RunningToolCall(null, "t", 1); //$NON-NLS-1$
        ToolCallScope scope = ToolCallScope.create(call);
        assertSame("the scope must share the call's flag, not create a second one", //$NON-NLS-1$
            call.cancellation(), scope.cancellation());
    }

    @Test
    public void cancellingViaTheCallIsSeenThroughTheScope()
    {
        RunningToolCall call = new RunningToolCall(null, "t", 1); //$NON-NLS-1$
        ToolCallScope scope = ToolCallScope.create(call);
        assertFalse(scope.cancellation().isCancelled());

        call.cancellation().cancel("cancelled by operator"); //$NON-NLS-1$

        assertTrue("a cancel raised on the call is visible through the scope", //$NON-NLS-1$
            scope.cancellation().isCancelled());
        assertEquals("cancelled by operator", scope.cancellation().reason()); //$NON-NLS-1$
    }

    @Test
    public void scopeWithoutACallStillHasACancellation()
    {
        ToolCallScope scope = ToolCallScope.create(null);
        assertNotNull(scope.cancellation());
        assertFalse(scope.cancellation().isCancelled());
    }
}
