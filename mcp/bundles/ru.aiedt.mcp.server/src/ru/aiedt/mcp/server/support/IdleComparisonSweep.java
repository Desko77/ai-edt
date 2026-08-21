/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import ru.aiedt.mcp.server.Activator;

/**
 * Closes comparisons that have gone idle, on a clock rather than on the next call.
 * <p>
 * <b>The idle limit was written down and nothing reached it.</b> Expiry ran only as a side effect
 * of another comparison, so a caller who took one report and stopped left the session open until
 * the plugin shut down. What that costs is not memory: an open comparison holds a transaction on
 * the environment's comparison store, and a held transaction has already left an EDT sitting at no
 * load, unable to shut down.
 * </p>
 * <p>
 * Started only once something is actually kept open, so an install where nobody compares pays
 * nothing. It stops itself when the registry empties and starts again with the next kept session -
 * a timer ticking against an empty registry is a thread that exists to find nothing.
 * </p>
 */
public final class IdleComparisonSweep
{
    /**
     * How often to look. Well under the idle limit so a session is closed near its deadline rather
     * than up to a full period late, and far enough apart to stay invisible.
     */
    private static final long PERIOD_MS = 5L * 60L * 1000L;

    private static Job sweeper;

    private static boolean stopped;

    private IdleComparisonSweep()
    {
        // static utility
    }

    /**
     * Starts the sweep if it is not already running. Safe to call on every kept session.
     */
    public static synchronized void ensureRunning()
    {
        if (sweeper != null || stopped)
        {
            return;
        }
        if (!ComparisonSessions.anythingOpen())
        {
            // The invariant stated once, in the place that can break it: armed only while
            // something is held. Callers arm from a path where a session was just kept, so this
            // does not fire in practice - it is here so the rule holds no matter who calls.
            return;
        }
        Job job = new Job("AI-EDT idle comparison sweep") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                int closed = 0;
                try
                {
                    closed = BmComparisonHelper.sweepIdle();
                }
                catch (RuntimeException | LinkageError refused)
                {
                    // A sweep that throws must not take the timer with it: the next tick is what
                    // closes whatever this one could not.
                    Activator.logDebug("idle comparison sweep failed: " + refused); //$NON-NLS-1$
                }
                if (closed > 0)
                {
                    Activator.logDebug("idle comparison sweep closed " + closed //$NON-NLS-1$
                        + " session(s)"); //$NON-NLS-1$
                }
                rescheduleOrStop(this);
                return Status.OK_STATUS;
            }
        };
        // A system job: this is housekeeping, and a progress entry appearing in the environment
        // every five minutes would be the server interrupting somebody to say it did nothing.
        job.setSystem(true);
        job.setPriority(Job.DECORATE);
        sweeper = job;
        job.schedule(PERIOD_MS);
    }

    /**
     * Puts the sweep back on the queue, or lets it end when there is nothing left to watch.
     *
     * @param job the job that just ran.
     */
    private static synchronized void rescheduleOrStop(Job job)
    {
        if (stopped || sweeper != job)
        {
            return;
        }
        if (!ComparisonSessions.anythingOpen())
        {
            sweeper = null;
            return;
        }
        job.schedule(PERIOD_MS);
    }

    /**
     * Stops the sweep for good. Called when the bundle goes down.
     * <p>
     * The flag is set before the cancel and checked by every path that schedules, so a run
     * finishing at the same moment cannot put itself back on the queue afterwards.
     * </p>
     */
    public static synchronized void shutdown()
    {
        stopped = true;
        if (sweeper != null)
        {
            sweeper.cancel();
            sweeper = null;
        }
    }

    /**
     * Whether a sweep is currently scheduled. For tests and for {@code self_status}.
     *
     * @return <code>true</code> while the timer is armed
     */
    public static synchronized boolean isRunning()
    {
        return sweeper != null;
    }

    /**
     * Lifts the permanent stop, so a test can exercise arming after having stopped it.
     * <p>
     * <b>A test hook, and named as one rather than disguised as an API.</b> {@link #shutdown} is
     * final on purpose - a run finishing while the bundle stops must not be able to re-arm - and
     * that stickiness is exactly what makes a suite order-dependent: the first test to call
     * shutdown would make every later arming assertion pass without arming anything, which is a
     * test proving nothing.
     * </p>
     */
    static synchronized void allowRestartForTest()
    {
        stopped = false;
    }
}
