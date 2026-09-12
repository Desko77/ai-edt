/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.Test;

/**
 * A tool that hands its work to another thread must not lose the call it is running inside.
 * <p>
 * The binding is a {@link ThreadLocal}. Work run on the UI thread, a worker or a pool arrives with
 * no scope, so every cancellation checkpoint inside it answers "not cancelled" - for the whole call,
 * whatever the operator does. That is indistinguishable from a tool with no checkpoints at all, and
 * it is invisible: the code reads as though cancellation is honoured.
 * </p>
 */
public class WorkHandedToAnotherThreadKeepsTheCallTest
{
    @Test
    public void theFlagIsVisibleOnTheThreadThatRunsTheWork() throws Exception
    {
        ToolCallScope.Cancellation flag = new ToolCallScope.Cancellation();
        ToolCallScope.enter(ToolCallScope.forCancellation(flag));
        Supplier<Boolean> carried;
        try
        {
            carried = ToolCallScope.carry(() -> {
                ToolCallScope seen = ToolCallScope.current();
                return seen != null && seen.cancellation().isCancelled();
            });
            flag.cancel("the operator asked to stop"); //$NON-NLS-1$
        }
        finally
        {
            ToolCallScope.exit();
        }

        AtomicReference<Boolean> answer = new AtomicReference<>();
        Thread elsewhere = new Thread(() -> answer.set(carried.get()));
        elsewhere.start();
        elsewhere.join();

        assertTrue("a checkpoint on the other thread must see the operator's cancel",
            answer.get().booleanValue());
    }

    @Test
    public void withoutCarryingTheOtherThreadSeesNothing() throws Exception
    {
        // The control. Without it the test above would pass against a scope that leaks globally,
        // which would be a worse bug than the one it guards.
        ToolCallScope.enter(ToolCallScope.forCancellation(new ToolCallScope.Cancellation()));
        AtomicReference<ToolCallScope> seen = new AtomicReference<>();
        try
        {
            Thread elsewhere = new Thread(() -> seen.set(ToolCallScope.current()));
            elsewhere.start();
            elsewhere.join();
        }
        finally
        {
            ToolCallScope.exit();
        }

        assertNull("a plain hand-off carries no scope, which is why carry exists", seen.get());
    }

    @Test
    public void aThreadWithACallOfItsOwnKeepsItAfterwards() throws Exception
    {
        // Nesting: the UI thread can already be inside a call when a second one hands it work.
        // Removing the binding at the end would strand that outer call for the rest of its run.
        ToolCallScope outer = ToolCallScope.forCancellation(new ToolCallScope.Cancellation());
        ToolCallScope inner = ToolCallScope.forCancellation(new ToolCallScope.Cancellation());

        ToolCallScope.enter(inner);
        Supplier<ToolCallScope> carried;
        try
        {
            carried = ToolCallScope.carry(ToolCallScope::current);
        }
        finally
        {
            ToolCallScope.exit();
        }

        AtomicReference<ToolCallScope> inside = new AtomicReference<>();
        AtomicReference<ToolCallScope> after = new AtomicReference<>();
        Thread host = new Thread(() -> {
            ToolCallScope.enter(outer);
            try
            {
                inside.set(carried.get());
                after.set(ToolCallScope.current());
            }
            finally
            {
                ToolCallScope.exit();
            }
        });
        host.start();
        host.join();

        assertSame("the carried call is the one the work runs under", inner, inside.get());
        assertSame("the host thread's own call has to come back", outer, after.get());
    }

    @Test
    public void outsideACallThereIsNothingToCarry()
    {
        ToolCallScope.exit();
        Supplier<ToolCallScope> work = ToolCallScope::current;

        Supplier<ToolCallScope> carried = ToolCallScope.carry(work);

        assertSame("with no scope to carry the work is handed back untouched", work, carried);
        assertNull(carried.get());
    }

    @Test
    public void theWatchOnTheOtherThreadStopsTheScan() throws Exception
    {
        // What the tools actually do: begin a watch inside the work, on the far thread.
        ToolCallScope.Cancellation flag = new ToolCallScope.Cancellation();
        ToolCallScope.enter(ToolCallScope.forCancellation(flag));
        Supplier<String> carried;
        try
        {
            carried = ToolCallScope.carry(() -> {
                WatchForCancel watch = WatchForCancel.begin();
                int read = 0;
                for (int i = 0; i < 5; i++)
                {
                    if (watch.stopHere())
                    {
                        break;
                    }
                    read++;
                }
                return read + "|" + watch.note("files"); //$NON-NLS-1$ //$NON-NLS-2$
            });
            flag.cancel("the operator asked to stop"); //$NON-NLS-1$
        }
        finally
        {
            ToolCallScope.exit();
        }

        AtomicReference<String> answer = new AtomicReference<>();
        Thread elsewhere = new Thread(() -> answer.set(carried.get()));
        elsewhere.start();
        elsewhere.join();

        String said = answer.get();
        assertNotNull(said);
        assertTrue(said, said.startsWith("0|"));
        assertFalse("a stopped scan that says nothing is the defect this guards",
            said.endsWith("null"));
    }
}
