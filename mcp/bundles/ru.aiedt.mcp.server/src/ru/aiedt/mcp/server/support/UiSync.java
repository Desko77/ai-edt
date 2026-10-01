/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.eclipse.swt.widgets.Display;

/**
 * Runs a piece of work on the SWT UI thread and returns its result, but with two safety rails the raw
 * {@code Display.syncExec} lacks.
 *
 * <p><b>A self-deadlock guard.</b> If the caller is already on the UI thread the work runs inline;
 * posting to the UI thread and then blocking on it would wait on ourselves forever.
 *
 * <p><b>A timeout.</b> A tool call arrives on a background thread and blocks here for the model read it
 * needs. If the UI thread is wedged - a modal dialog is open, another operation is stuck - a plain
 * {@code syncExec} would block that tool forever, and a burst of such calls would exhaust the request
 * pool and take the whole server down with it. Instead this waits a bounded time and then gives up with
 * a {@link UiBusyException}.
 *
 * <p><b>What a given-up call leaves behind.</b> Work the waiter abandoned before it started is marked
 * abandoned, and the queued runnable exits without running it - a write the caller already read as
 * refused must not land on the form a minute later. Work that had already started when the wait ran
 * out cannot be taken back, so the caller waits for its real outcome instead of answering a refusal
 * the write may contradict. An interrupt can still break that wait; what it earns is a
 * {@link UiOutcomeUnknownException}, tagged apart so a caller does not read it as a retryable
 * busy condition - the started work may still apply.
 */
public final class UiSync
{
    /** How long to wait for the UI thread before giving up, when no explicit timeout is given. */
    public static final long DEFAULT_TIMEOUT_MS = 60_000L;

    private UiSync()
    {
    }

    /**
     * Runs work on the UI thread with the {@link #DEFAULT_TIMEOUT_MS default timeout}.
     *
     * @param <T> the result type
     * @param work the work to run; must not be <code>null</code>
     * @return the work's result
     * @throws UiBusyException if the UI thread does not run the work in time
     */
    public static <T> T call(Supplier<T> work)
    {
        return call(work, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Runs work on the UI thread, waiting at most {@code timeoutMs} for it.
     *
     * @param <T> the result type
     * @param work the work to run; must not be <code>null</code>
     * @param timeoutMs how long to wait for the UI thread, in milliseconds
     * @return the work's result
     * @throws UiBusyException if the UI thread did not start the work in time (the work never
     *             runs), or the wait was interrupted before the work started
     * @throws UiOutcomeUnknownException if the wait was interrupted after the work had started -
     *             the work may still apply, so the outcome is unknown and a blind retry is unsafe
     */
    public static <T> T call(Supplier<T> work, long timeoutMs)
    {
        if (Display.getCurrent() != null)
        {
            return work.get();
        }
        // The UI thread has no scope of its own, and the binding is a ThreadLocal, so work
        // handed over unwrapped would read no cancellation flag and every checkpoint
        // inside it would answer "not cancelled" for the life of the call. The flag travels;
        // the call does not, because this runnable outlives the wait below when the UI is wedged.
        Supplier<T> carried = ToolCallScope.carryCancellation(work);
        Display display = Display.getDefault();
        AtomicReference<T> result = new AtomicReference<>();
        // Throwable, not RuntimeException: an Error the work raises would otherwise escape the
        // catch, the latch would still open, and the caller would read the missing result as a
        // null answer instead of the failure it was.
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        // The border between a runnable the queue may still run and one it may not. The runnable
        // claims the work by PENDING -> STARTED; a waiter that stopped waiting claims it by
        // PENDING -> ABANDONED. Exactly one compareAndSet wins, so work the caller read as
        // refused never starts, while work that did start is waited for below.
        AtomicReference<QueueState> state = new AtomicReference<>(QueueState.PENDING);
        display.asyncExec(() -> {
            if (!state.compareAndSet(QueueState.PENDING, QueueState.STARTED))
            {
                return;
            }
            try
            {
                result.set(carried.get());
            }
            catch (Throwable t)
            {
                failure.set(t);
            }
            finally
            {
                done.countDown();
            }
        });
        boolean finished;
        try
        {
            finished = done.await(timeoutMs, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw abandonedByInterrupt(state);
        }
        if (!finished && state.compareAndSet(QueueState.PENDING, QueueState.ABANDONED))
        {
            // Nothing was changed: the queue still holds the runnable, but it exits before the
            // work, and the work is what writes.
            throw new UiBusyException("The EDT UI thread did not respond within " + timeoutMs //$NON-NLS-1$
                + " ms (it is busy, or a modal dialog is open); nothing was changed"); //$NON-NLS-1$
        }
        if (!finished)
        {
            // The work is already running, so its effects land whether this caller waits or not.
            // Answering "UI busy, retry" here could double a write the caller retries, so the
            // call stays for the real outcome. An interrupt can still break the wait - it answers
            // the outcome-unknown condition, because by then the work may apply.
            try
            {
                done.await();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new UiOutcomeUnknownException("Interrupted while the work was already running on the " //$NON-NLS-1$
                    + "EDT UI thread; it may still apply"); //$NON-NLS-1$
            }
        }
        Throwable f = failure.get();
        if (f instanceof RuntimeException)
        {
            throw (RuntimeException)f;
        }
        if (f instanceof Error)
        {
            throw (Error)f;
        }
        if (f != null)
        {
            // A Supplier declares no checked exception, so one can only arrive smuggled past the
            // compiler; it still belongs to the caller, wrapped so the signature holds.
            throw new RuntimeException(f);
        }
        return result.get();
    }

    /**
     * Tells a queued runnable from one the waiter stopped waiting for, and a started one from both.
     * <p>
     * The state moves exactly once out of {@code PENDING}, by whichever side wins the
     * compareAndSet: the runnable starting the work, or the waiter giving the work up. The loser
     * reads the winner's state and acts on it - the runnable exits without running, the waiter
     * either answers a refusal that is true (nothing runs) or waits for the outcome.
     * </p>
     */
    private enum QueueState
    {
        /** In the UI thread's queue; the waiter is still waiting for it. */
        PENDING,

        /** Running on the UI thread right now; its effects will land. */
        STARTED,

        /** The waiter gave the work up before it started; the runnable must not run it. */
        ABANDONED
    }

    /**
     * Takes the queued work out of the queue on behalf of an interrupted waiter, and answers the
     * refusal that matches what was actually left behind.
     *
     * @param state the queue state of the call the interrupt broke
     * @return the refusal for the interrupted wait; its kind says whether the work can still apply
     */
    private static UiBusyException abandonedByInterrupt(AtomicReference<QueueState> state)
    {
        if (state.compareAndSet(QueueState.PENDING, QueueState.ABANDONED))
        {
            return new UiBusyException("Interrupted while waiting for the EDT UI thread; " //$NON-NLS-1$
                + "nothing was changed"); //$NON-NLS-1$
        }
        return new UiOutcomeUnknownException("Interrupted while the work was already running on the " //$NON-NLS-1$
            + "EDT UI thread; it may still apply"); //$NON-NLS-1$
    }

    /**
     * Signals that the UI thread was unavailable within the allotted time, or the wait for it was
     * interrupted before the work started - nothing was changed, and a retry is safe.
     * <p>
     * Not final: {@link UiOutcomeUnknownException} extends it, so a catcher written for the busy
     * condition also catches the outcome-unknown one and only has to read {@link #tag()} apart.
     * </p>
     */
    public static class UiBusyException
        extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        /**
         * @param message what happened
         */
        public UiBusyException(String message)
        {
            super(message);
        }

        /**
         * @return the machine-readable tag for this condition
         */
        public String tag()
        {
            return ErrorTags.UI_BUSY.wire();
        }
    }

    /**
     * Signals that the wait was broken after the work had already started on the UI thread, so
     * whether the work's effects landed is unknown.
     * <p>
     * A subclass of {@link UiBusyException}, so a catcher written for the busy condition still
     * catches this one; what differs is the tag. {@code uiBusy} reads as "nothing was changed,
     * retry", which a started work may contradict - a retried add/remove would race the write
     * already running or repeat it. This one tags {@code outcomeUnknown}: not retryable, read
     * the target's state first.
     * </p>
     */
    public static final class UiOutcomeUnknownException
        extends UiBusyException
    {
        private static final long serialVersionUID = 1L;

        /**
         * @param message what happened
         */
        public UiOutcomeUnknownException(String message)
        {
            super(message);
        }

        @Override
        public String tag()
        {
            return ErrorTags.OUTCOME_UNKNOWN.wire();
        }
    }
}
