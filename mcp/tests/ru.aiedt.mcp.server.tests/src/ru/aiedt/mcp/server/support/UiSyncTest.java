/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assume.assumeTrue;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.eclipse.swt.widgets.Display;
import org.junit.Test;

/**
 * What a caller of {@link UiSync} sees when the work itself fails.
 * <p>
 * The interesting path is the posted one - the caller on a background thread, the work on the UI
 * thread - because that is where a failure has to be carried across. The tests pump the display
 * themselves: in the headless harness the test thread owns it, and the posted work would never run
 * otherwise.
 * </p>
 */
public class UiSyncTest
{
    /**
     * An Error raised by the work reaches the caller as that same Error, not as a null result.
     *
     * @throws InterruptedException if the test thread is interrupted while waiting
     */
    @Test
    public void anErrorFromTheWorkReachesTheCaller() throws InterruptedException
    {
        Error boom = new AssertionError("boom"); //$NON-NLS-1$
        Throwable caught = callOffDisplayThread(() -> {
            throw boom;
        }).failure;
        assertSame(boom, caught);
    }

    /**
     * A RuntimeException raised by the work is rethrown as is.
     *
     * @throws InterruptedException if the test thread is interrupted while waiting
     */
    @Test
    public void aRuntimeExceptionFromTheWorkReachesTheCallerAsIs() throws InterruptedException
    {
        RuntimeException boom = new IllegalStateException("boom"); //$NON-NLS-1$
        Throwable caught = callOffDisplayThread(() -> {
            throw boom;
        }).failure;
        assertSame(boom, caught);
    }

    /**
     * Work that legitimately answers null still answers null - only a failure is rethrown.
     *
     * @throws InterruptedException if the test thread is interrupted while waiting
     */
    @Test
    public void aLegitimateNullResultStaysNull() throws InterruptedException
    {
        Outcome<Object> outcome = callOffDisplayThread(() -> null);
        assertNull(outcome.failure);
        assertNull(outcome.result);
    }

    /** What one posted call came back with: the result, or the failure it ended in. */
    private static final class Outcome<T>
    {
        /** The work's answer; <code>null</code> when the work failed or answered null. */
        T result;

        /** What the work ended in; <code>null</code> when it returned normally. */
        Throwable failure;
    }

    /**
     * Calls {@link UiSync#call(Supplier)} from a background thread while this thread pumps the
     * display, so the posted work actually runs.
     *
     * @param <T> the result type
     * @param work the work to hand to {@link UiSync}
     * @return what the call came back with
     * @throws InterruptedException if the test thread is interrupted while waiting
     */
    private static <T> Outcome<T> callOffDisplayThread(Supplier<T> work) throws InterruptedException
    {
        Display display = Display.getDefault();
        // Pumping below only works when this thread owns the display; another harness skips.
        assumeTrue(display.getThread() == Thread.currentThread());

        Outcome<T> outcome = new Outcome<>();
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try
            {
                result.set(UiSync.call(work));
            }
            catch (Throwable t)
            {
                failure.set(t);
            }
        }, "uisync-probe"); //$NON-NLS-1$
        caller.setDaemon(true);
        caller.start();
        while (caller.isAlive())
        {
            if (!display.readAndDispatch())
            {
                caller.join(50L);
            }
        }
        caller.join(10_000L);
        outcome.result = result.get();
        outcome.failure = failure.get();
        return outcome;
    }
}
