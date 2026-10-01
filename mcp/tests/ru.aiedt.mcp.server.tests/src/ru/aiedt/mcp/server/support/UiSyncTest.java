/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /**
     * Work a timed-out call gave up on never runs, even when the UI thread's queue is pumped
     * afterwards.
     * <p>
     * The call happens while this thread - the display's owner - dispatches nothing, so the posted
     * work cannot start and the wait runs out. The runnable stays in the queue; pumping it later
     * has to leave the work unrun, or a write the caller already read as refused would land after
     * the refusal.
     * </p>
     *
     * @throws InterruptedException if the join is interrupted
     */
    @Test
    public void aTimedOutCallDoesNotRunItsWorkLater() throws InterruptedException
    {
        Display display = Display.getDefault();
        // Pumping below only works when this thread owns the display; another harness skips.
        assumeTrue(display.getThread() == Thread.currentThread());

        AtomicBoolean ran = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try
            {
                UiSync.call(() -> {
                    ran.set(true);
                    return null;
                }, 100L);
            }
            catch (Throwable t)
            {
                failure.set(t);
            }
        }, "uisync-abandon-probe"); //$NON-NLS-1$
        caller.setDaemon(true);
        caller.start();
        // No readAndDispatch while the caller waits: the work cannot start.
        caller.join(10_000L);
        assertFalse("the caller has to finish within the join", caller.isAlive()); //$NON-NLS-1$
        assertTrue("the expired wait has to answer UiBusyException, not " + failure.get(), //$NON-NLS-1$
            failure.get() instanceof UiSync.UiBusyException);

        // The queue drains afterwards - the abandoned runnable in it must not run the work.
        while (display.readAndDispatch())
        {
            // every remaining event gets its turn
        }
        assertFalse("abandoned work must not run when the queue is later pumped", ran.get()); //$NON-NLS-1$
    }

    /**
     * A caller whose work already started waits for the work's real outcome instead of answering a
     * busy refusal the work may contradict.
     * <p>
     * The work sleeps longer than the caller's wait, so the wait runs out while the work is
     * running. The call still has to come back with the work's result: a "UI busy" here would
     * tell the caller nothing was written while the write was already landing.
     * </p>
     *
     * @throws InterruptedException if the join is interrupted
     */
    @Test
    public void aWorkThatAlreadyStartedStillAnswersItsOutcome() throws InterruptedException
    {
        Display display = Display.getDefault();
        // Pumping below only works when this thread owns the display; another harness skips.
        assumeTrue(display.getThread() == Thread.currentThread());

        CountDownLatch workStarted = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try
            {
                result.set(UiSync.call(() -> {
                    workStarted.countDown();
                    try
                    {
                        Thread.sleep(3_000L);
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                    }
                    return "done"; //$NON-NLS-1$
                }, 1_000L));
            }
            catch (Throwable t)
            {
                failure.set(t);
            }
        }, "uisync-started-probe"); //$NON-NLS-1$
        caller.setDaemon(true);
        caller.start();
        // Dispatch until the work is running: each readAndDispatch runs one queued event on this
        // thread, and the work it starts here holds the thread for 3 s - past the caller's
        // 1 s wait, so the caller's wait expires while the work is running.
        long deadline = System.currentTimeMillis() + 10_000L;
        while (!workStarted.await(20L, TimeUnit.MILLISECONDS))
        {
            assertTrue("the work has to start once the queue is pumped", //$NON-NLS-1$
                System.currentTimeMillis() < deadline);
            display.readAndDispatch();
        }
        caller.join(10_000L);
        assertFalse("the caller has to finish within the join", caller.isAlive()); //$NON-NLS-1$
        assertNull("a started work is not a busy refusal: " + failure.get(), failure.get()); //$NON-NLS-1$
        assertEquals("the caller has to read the work's own outcome", "done", result.get()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * An interrupt that breaks the first wait while the work is already running answers the
     * outcome-unknown condition, not a retryable busy refusal: the work may still apply, and a
     * "UI busy, retry" tag would invite a second write racing the first.
     *
     * @throws InterruptedException if the join is interrupted
     */
    @Test
    public void anInterruptOfTheFirstWaitOnStartedWorkAnswersOutcomeUnknown() throws InterruptedException
    {
        Display display = Display.getDefault();
        // Pumping below only works when this thread owns the display; another harness skips.
        assumeTrue(display.getThread() == Thread.currentThread());

        CountDownLatch workStarted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try
            {
                UiSync.call(() -> {
                    workStarted.countDown();
                    sleep(5_000L);
                    return null;
                }, 30_000L);
            }
            catch (Throwable t)
            {
                failure.set(t);
            }
        }, "uisync-interrupt-first-wait-probe"); //$NON-NLS-1$
        caller.setDaemon(true);
        caller.start();
        interruptOnceStarted(workStarted, caller, 300L);

        // The work runs inline inside readAndDispatch and sleeps there: while it does, the
        // helper above breaks the caller's wait, so the caller's answer is decided before
        // this pump returns.
        long deadline = System.currentTimeMillis() + 15_000L;
        while (!workStarted.await(20L, TimeUnit.MILLISECONDS))
        {
            assertTrue("the work has to start once the queue is pumped", //$NON-NLS-1$
                System.currentTimeMillis() < deadline);
            display.readAndDispatch();
        }
        caller.join(10_000L);
        assertFalse("the caller has to finish within the join", caller.isAlive()); //$NON-NLS-1$
        assertOutcomeUnknown(failure.get());
    }

    /**
     * A wait that ran out while the work was running falls back to waiting for the work's outcome;
     * an interrupt of that fallback answers the outcome-unknown condition too, for the same
     * reason as an interrupt of the first wait.
     *
     * @throws InterruptedException if the join is interrupted
     */
    @Test
    public void anInterruptOfTheFallbackWaitOnStartedWorkAnswersOutcomeUnknown() throws InterruptedException
    {
        Display display = Display.getDefault();
        // Pumping below only works when this thread owns the display; another harness skips.
        assumeTrue(display.getThread() == Thread.currentThread());

        CountDownLatch workStarted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try
            {
                UiSync.call(() -> {
                    workStarted.countDown();
                    sleep(5_000L);
                    return null;
                }, 500L);
            }
            catch (Throwable t)
            {
                failure.set(t);
            }
        }, "uisync-interrupt-fallback-probe"); //$NON-NLS-1$
        caller.setDaemon(true);
        caller.start();
        // A beat past the caller's 500 ms wait, so the interrupt lands in the fallback wait
        // for the started work's outcome, not in the bounded first wait.
        interruptOnceStarted(workStarted, caller, 1_000L);

        long deadline = System.currentTimeMillis() + 15_000L;
        while (!workStarted.await(20L, TimeUnit.MILLISECONDS))
        {
            assertTrue("the work has to start once the queue is pumped", //$NON-NLS-1$
                System.currentTimeMillis() < deadline);
            display.readAndDispatch();
        }
        caller.join(10_000L);
        assertFalse("the caller has to finish within the join", caller.isAlive()); //$NON-NLS-1$
        assertOutcomeUnknown(failure.get());
    }

    /**
     * Interrupts the caller a beat after its work started on the UI thread.
     *
     * @param workStarted counted down by the work when it starts
     * @param caller the waiting call to break
     * @param delayMs how long after the start to interrupt, so the caller is deep in its wait
     */
    private static void interruptOnceStarted(CountDownLatch workStarted, Thread caller, long delayMs)
    {
        Thread interrupter = new Thread(() -> {
            try
            {
                if (workStarted.await(15_000L, TimeUnit.MILLISECONDS))
                {
                    Thread.sleep(delayMs);
                    caller.interrupt();
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }, "uisync-interrupter"); //$NON-NLS-1$
        interrupter.setDaemon(true);
        interrupter.start();
    }

    /**
     * Sleeps, restoring an interrupt instead of letting it out: the work has to hold the UI
     * thread for its full length whatever signals arrive.
     *
     * @param millis how long to sleep
     */
    private static void sleep(long millis)
    {
        try
        {
            Thread.sleep(millis);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Asserts the failure is the outcome-unknown condition: the work had already started when the
     * wait broke, so a retryable busy tag on it would be a lie the caller acts on.
     *
     * @param failure what the call ended in
     */
    private static void assertOutcomeUnknown(Throwable failure)
    {
        assertTrue("an interrupt after the work started is a busy-family refusal, not " + failure, //$NON-NLS-1$
            failure instanceof UiSync.UiBusyException);
        assertEquals("an interrupt after the work started has to carry the outcome-unknown tag", //$NON-NLS-1$
            "outcomeUnknown", ((UiSync.UiBusyException)failure).tag()); //$NON-NLS-1$
        assertEquals("UiOutcomeUnknownException", failure.getClass().getSimpleName()); //$NON-NLS-1$
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
