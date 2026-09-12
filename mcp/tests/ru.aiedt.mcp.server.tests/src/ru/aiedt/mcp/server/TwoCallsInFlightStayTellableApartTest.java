/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The server runs a pool of request threads, so two tool calls can be in flight at once.
 * <p>
 * Held in one slot they were not tellable apart: the second to arrive replaced the first, so a
 * signal meant for the call the operator could see went to whichever had started last, and the
 * running-tool indicator - cleared unconditionally by whichever finished first - read "idle" over
 * work still in progress. The second failure is the worse one because it is silent.
 * </p>
 * <p>
 * These tests drive the endpoint's registry directly. They need no HTTP and no workbench: a
 * {@link RunningToolCall} with a null exchange is answerable-to nobody, which is exactly the shape
 * the registry has to handle anyway.
 * </p>
 */
public class TwoCallsInFlightStayTellableApartTest
{
    @Test
    public void theIndicatorKeepsNamingWorkThatIsStillRunning()
    {
        McpHttpEndpoint server = new McpHttpEndpoint();
        RunningToolCall first = new RunningToolCall(null, "code_search", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        RunningToolCall second = new RunningToolCall(null, "project_metrics", "2"); //$NON-NLS-1$ //$NON-NLS-2$

        server.setActiveToolCall(first);
        first.markRunning("code_search"); //$NON-NLS-1$
        server.setActiveToolCall(second);
        second.markRunning("project_metrics"); //$NON-NLS-1$

        assertEquals("the oldest running call is the one on show", "code_search", //$NON-NLS-1$
            server.getCurrentToolName());
        assertEquals(2, server.runningToolCount());

        // The first finishes. The indicator must move to the second, not go dark.
        first.markRunning(null);
        server.clearActiveToolCall(first);

        assertTrue("a tool is still running", server.isToolExecuting());
        assertEquals("project_metrics", server.getCurrentToolName()); //$NON-NLS-1$
        assertEquals(1, server.runningToolCount());

        second.markRunning(null);
        server.clearActiveToolCall(second);

        assertFalse(server.isToolExecuting());
        assertNull(server.getCurrentToolName());
        assertEquals(0, server.runningToolCount());
    }

    @Test
    public void aSignalActsOnTheCallTheOperatorCanSee()
    {
        // Newest-wins was the defect: the operator reads one name off the strip and presses a
        // button that reached a different call.
        McpHttpEndpoint server = new McpHttpEndpoint();
        RunningToolCall shown = new RunningToolCall(null, "find_references", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        RunningToolCall later = new RunningToolCall(null, "validate_query", "2"); //$NON-NLS-1$ //$NON-NLS-2$

        server.setActiveToolCall(shown);
        shown.markRunning("find_references"); //$NON-NLS-1$
        server.setActiveToolCall(later);
        later.markRunning("validate_query"); //$NON-NLS-1$

        assertEquals("find_references", server.getCurrentToolName()); //$NON-NLS-1$
        assertSame("the button must act on what the strip names", shown,
            server.getActiveToolCall());
    }

    @Test
    public void aCallIsFoundByTheIdItWillBeAnsweredWith()
    {
        // What a protocol cancellation needs: notifications/cancelled carries a request id, and the
        // flag has to be raised on the call that id names rather than on whichever started last.
        McpHttpEndpoint server = new McpHttpEndpoint();
        RunningToolCall first = new RunningToolCall(null, "code_search", "req-1"); //$NON-NLS-1$ //$NON-NLS-2$
        RunningToolCall second = new RunningToolCall(null, "project_metrics", Integer.valueOf(7)); //$NON-NLS-1$
        server.setActiveToolCall(first);
        server.setActiveToolCall(second);

        assertSame(first, server.findCallByRequestId("req-1", null)); //$NON-NLS-1$
        assertSame(second, server.findCallByRequestId(Integer.valueOf(7), null));
        assertNull(server.findCallByRequestId("no-such-id", null)); //$NON-NLS-1$
        assertNull("a notification with no id names no call", server.findCallByRequestId(null, null));
    }

    @Test
    public void aNumericIdMatchesWhicheverWayItWasParsed()
    {
        // The trap this is here for: a JSON number reaches the call as a Long and reaches a
        // notification's params as a Double, and those two are never equal. A cancellation would
        // then match nothing and say nothing - the flag would simply never rise.
        McpHttpEndpoint server = new McpHttpEndpoint();
        RunningToolCall call = new RunningToolCall(null, "code_search", Long.valueOf(7)); //$NON-NLS-1$
        server.setActiveToolCall(call);

        assertSame("as a Double, which is what a generic JSON parse gives", call, //$NON-NLS-1$
            server.findCallByRequestId(Double.valueOf(7.0), null));
        assertSame(call, server.findCallByRequestId(Integer.valueOf(7), null));
        assertSame(call, server.findCallByRequestId(Long.valueOf(7), null));
        assertNull("a string id and a numeric id are different ids in JSON-RPC", //$NON-NLS-1$
            server.findCallByRequestId("7", null)); //$NON-NLS-1$
        assertNull("a fractional id is not an id this server ever stored", //$NON-NLS-1$
            server.findCallByRequestId(Double.valueOf(7.5), null));
    }

    @Test
    public void oneClientCannotWithdrawAnotherClientsCall()
    {
        // JSON-RPC ids are unique within a client, and clients start counting at one, so the moment
        // two of them are busy they both have a call numbered 1. Matched on the id alone, the
        // second client's withdrawal stops the first client's work - and the client that asked for
        // the stop goes on waiting.
        McpHttpEndpoint server = new McpHttpEndpoint();
        RunningToolCall theirs = new RunningToolCall(null, "code_search", Long.valueOf(1)); //$NON-NLS-1$
        server.setActiveToolCall(theirs);

        assertNull("a client that names a session cannot reach a call that named none", //$NON-NLS-1$
            server.findCallByRequestId(Long.valueOf(1), "session-b")); //$NON-NLS-1$
        assertSame("and the client that made it still can", theirs, //$NON-NLS-1$
            server.findCallByRequestId(Long.valueOf(1), null));
    }

    @Test
    public void anAnsweredCallIsNoLongerCancellable()
    {
        // A late cancellation must not raise a flag on work that is over; nothing would read it,
        // and the call may be sitting in the queue until its thread tidies up.
        McpHttpEndpoint server = new McpHttpEndpoint();
        RunningToolCall call = new RunningToolCall(null, "code_search", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        server.setActiveToolCall(call);
        assertSame(call, server.findCallByRequestId("1", null)); //$NON-NLS-1$

        call.abandon();

        assertNull("an answered call is past cancelling", server.findCallByRequestId("1", null)); //$NON-NLS-1$
    }

    @Test
    public void aCallThatHasNotStartedItsToolIsNotOnShow()
    {
        // A call is enrolled when it arrives, which is before the router resolves and starts the
        // tool. There is nothing to name or to interrupt in that window.
        McpHttpEndpoint server = new McpHttpEndpoint();
        RunningToolCall arrived = new RunningToolCall(null, "code_search", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        server.setActiveToolCall(arrived);

        assertFalse(server.isToolExecuting());
        assertNull(server.getCurrentToolName());
        assertEquals(0, server.runningToolCount());
        assertNull(server.getActiveToolCall());

        // ...and it can still be found by id, because a cancellation may arrive in that window.
        assertSame(arrived, server.findCallByRequestId("1", null)); //$NON-NLS-1$
    }

    @Test
    public void theToolNameOnShowIsTheOneTheRouterResolved()
    {
        // The request may carry a back-compat alias; the strip shows the tool that actually runs.
        McpHttpEndpoint server = new McpHttpEndpoint();
        RunningToolCall call = new RunningToolCall(null, "search_in_code", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        server.setActiveToolCall(call);

        assertEquals("before the router resolves, the request's own name stands", //$NON-NLS-1$
            "search_in_code", call.runningToolName()); //$NON-NLS-1$

        call.markRunning("code_search"); //$NON-NLS-1$

        assertEquals("code_search", server.getCurrentToolName()); //$NON-NLS-1$
    }
}
