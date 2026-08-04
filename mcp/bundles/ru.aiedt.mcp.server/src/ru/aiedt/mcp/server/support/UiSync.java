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
 * a {@link UiBusyException}. The work stays queued and may still run later; that is harmless for the
 * read-only model access this is used for. For work with side effects, the caller must accept that a
 * timed-out operation may still apply.
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
     * @throws UiBusyException if the UI thread does not run the work in time, or the wait is interrupted
     */
    public static <T> T call(Supplier<T> work, long timeoutMs)
    {
        if (Display.getCurrent() != null)
        {
            return work.get();
        }
        Display display = Display.getDefault();
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        display.asyncExec(() -> {
            try
            {
                result.set(work.get());
            }
            catch (RuntimeException e)
            {
                failure.set(e);
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
            throw new UiBusyException("Interrupted while waiting for the EDT UI thread"); //$NON-NLS-1$
        }
        if (!finished)
        {
            throw new UiBusyException("The EDT UI thread did not respond within " + timeoutMs //$NON-NLS-1$
                + " ms (it is busy, or a modal dialog is open)"); //$NON-NLS-1$
        }
        RuntimeException f = failure.get();
        if (f != null)
        {
            throw f;
        }
        return result.get();
    }

    /** Signals that the UI thread was unavailable within the allotted time. */
    public static final class UiBusyException
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
}
