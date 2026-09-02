/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable handles over the work already running behind {@link PendingWorkRegistry}.
 * <p>
 * Nothing new runs because of this class. A slow tool has always been able to hand back a
 * {@code runKey} and keep working; what was missing was a way for the caller to hold that handle
 * without knowing which tool it came from, which arguments reproduce it, or that re-calling the
 * tool is how you collect the answer. A key that only the issuing tool can redeem is not a handle,
 * it is a coincidence between two calls.
 * </p>
 * <p>
 * So a task is a name for one run: it records which domain owns the key, what the run was, and -
 * once the work finishes - the answer itself, kept here rather than left in the registry. That last
 * part is the substantive difference. The registry consumes a result when it is collected, which is
 * right for a caller that asked once and got its answer; a task is polled repeatedly and its
 * terminal state has to stop changing, so the first poll that finds the work done takes the result
 * out and keeps it until the task expires.
 * </p>
 */
public final class TaskDirectory
{
    /** Status: the work is still running. */
    public static final String WORKING = "working"; //$NON-NLS-1$

    /** Status: the work finished and the answer is here. */
    public static final String COMPLETED = "completed"; //$NON-NLS-1$

    /** Status: the work ended in an error. */
    public static final String FAILED = "failed"; //$NON-NLS-1$

    /** Status: cancellation was asked for and the run was detached. */
    public static final String CANCELLED = "cancelled"; //$NON-NLS-1$

    /**
     * How long a task is answerable for. Thirty minutes, matching the longest life an untouched run
     * has in the registry: a handle that outlives the work it names would answer about nothing.
     */
    public static final long TTL_MS = 30 * 60 * 1000L;

    /**
     * How often a client is asked to poll. Two seconds - long enough that a client polling a
     * ten-minute infobase update is not asking three hundred times a minute, short enough that a
     * run finishing in five seconds is noticed almost at once.
     */
    public static final long POLL_INTERVAL_MS = 2000L;

    private static final TaskDirectory INSTANCE = new TaskDirectory();

    private final ConcurrentHashMap<String, Task> tasks = new ConcurrentHashMap<>();

    private TaskDirectory()
    {
    }

    /**
     * @return the one directory
     */
    public static TaskDirectory getInstance()
    {
        return INSTANCE;
    }

    /**
     * Names a run that is already going.
     * <p>
     * The domain is resolved now rather than later, while the key is certainly still in it. Looking
     * it up on the first poll would work most of the time and lose the run whenever the poll came
     * after the registry had swept it.
     * </p>
     *
     * @param runKey the key the pending machinery issued, never {@code null}
     * @param operation the operation name that appeared in the pending answer
     * @param toolName the tool that was called
     * @param arguments the call's arguments, kept so the answer can be shaped the way the original
     *            call would have shaped it
     * @return the freshly opened task, as it is - not yet asked how the work is getting on
     */
    public Task open(String runKey, String operation, String toolName, Map<String, String> arguments)
    {
        prune();
        // One task per run. Two identical calls coalesce onto ONE entry in the registry, so giving
        // them two tasks made them compete for a single result: whichever polled first took it and
        // the other reported the run had vanished. They are the same run and now they are the same
        // task.
        for (Task existing : tasks.values())
        {
            if (!existing.isTerminal() && existing.runKey.equals(runKey))
            {
                return existing;
            }
        }
        Task task = new Task(runKey, operation, toolName, arguments);
        tasks.put(task.taskId, task);
        task.watchTheWork();
        return task;
    }

    /**
     * Reads the current state of a task, taking the result out of the registry the first time the
     * work is found finished.
     *
     * @param taskId the id handed out by {@link #open}
     * @return the state, or {@code null} when no such task is known
     */
    public Task poll(String taskId)
    {
        prune();
        Task task = taskId == null ? null : tasks.get(taskId);
        if (task == null)
        {
            return null;
        }
        task.refresh();
        return task;
    }

    /**
     * Asks for a task to stop.
     * <p>
     * Cooperative, and that is not a hedge borrowed from the specification - it is what the
     * machinery underneath actually does. Detaching a run stops this server waiting on it and
     * caching its answer; it does not interrupt a 1C Designer process already running. So the task
     * goes to {@code cancelled} and the work may still finish somewhere behind it.
     * </p>
     *
     * @param taskId the id to cancel
     * @return {@code true} when the task was known, whatever state it was in
     */
    public boolean cancel(String taskId)
    {
        Task task = taskId == null ? null : tasks.get(taskId);
        if (task == null)
        {
            return false;
        }
        task.cancel();
        return true;
    }

    /** @return how many tasks are being tracked, for diagnostics and tests */
    public int size()
    {
        return tasks.size();
    }

    /** Drops every task. For tests. */
    public void clear()
    {
        tasks.clear();
    }

    /**
     * Evicts tasks past their own lifetime, which is at least {@link #TTL_MS} and at least as
     * long as their domain keeps the run.
     */
    public void prune()
    {
        long now = System.currentTimeMillis();
        boolean domainsPruned = false;
        Iterator<Map.Entry<String, Task>> it = tasks.entrySet().iterator();
        while (it.hasNext())
        {
            Task task = it.next().getValue();
            if (now - task.createdAt <= task.lifetimeMs)
            {
                continue;
            }
            if (task.isTerminal())
            {
                if (now - task.lastUpdatedAt <= TTL_MS)
                {
                    // It finished after its nominal lifetime had passed, which the lifetime does
                    // not account for. Dropping it here would destroy a result nobody has had the
                    // chance to collect.
                    continue;
                }
            }
            else
            {
                if (!domainsPruned)
                {
                    // Nothing else prunes a domain when the only caller left is a task poll, and
                    // an entry kept past its own lifetime would keep this task alive forever.
                    for (PendingWorkRegistry domain : PendingWorkRegistry.domains())
                    {
                        domain.pruneExpired();
                    }
                    domainsPruned = true;
                }
                if (PendingWorkRegistry.domainOf(task.runKey) != null)
                {
                    // Its run is still tracked, so the handle to it stays whatever the clock says.
                    // A task's lifetime counts from when it opened and a run's age counts from
                    // when it began, so a run that waited before starting outlives its handle.
                    continue;
                }
            }
            it.remove();
        }
    }

    /**
     * One named run.
     * <p>
     * Mutable, and deliberately not a snapshot: a caller polls the same task many times and each
     * poll is allowed to move it forward. What may not move is a terminal state - once the answer
     * or the error is here, {@link #refresh()} leaves it alone.
     * </p>
     */
    public static final class Task
    {
        /** The id handed to the client. */
        public final String taskId = UUID.randomUUID().toString();

        /** The pending key this task names. */
        public final String runKey;

        /** The operation name, as the pending answer reported it. */
        public final String operation;

        /** The tool that was called, so its answer can be shaped like any other answer of its. */
        public final String toolName;

        /** The call's arguments, kept for the same reason. */
        public final Map<String, String> arguments;

        /** When the task was opened. */
        public final long createdAt = System.currentTimeMillis();

        /**
         * How long this task is kept.
         * <p>
         * At least {@link TaskDirectory#TTL_MS}, and never less than its domain keeps the run
         * itself: a task dropped while its run is still executing leaves the caller holding a
         * handle to a run it can no longer reach. Fixed when the task opens, because a domain is
         * found by looking the key up among the runs it holds, and that stops answering the moment
         * the run ends.
         * </p>
         */
        public final long lifetimeMs;

        /** When it last moved. */
        public volatile long lastUpdatedAt = System.currentTimeMillis();

        /** One of {@link TaskDirectory#WORKING} and its terminal siblings. */
        public volatile String status = WORKING;

        /** A sentence about the current state, for a human reading the poll. */
        public volatile String statusMessage;

        /** The tool's own answer, once there is one. */
        public volatile String result;

        /** What went wrong, when something did. */
        public volatile String failure;

        Task(String runKey, String operation, String toolName, Map<String, String> arguments)
        {
            this.runKey = runKey;
            PendingWorkRegistry domain = PendingWorkRegistry.domainOf(runKey);
            this.lifetimeMs = domain == null ? TTL_MS : Math.max(TTL_MS, domain.abandonedTtlMs());
            this.operation = operation;
            this.toolName = toolName;
            this.arguments = arguments == null ? new LinkedHashMap<>() : new LinkedHashMap<>(arguments);
            this.statusMessage = operation + " is running"; //$NON-NLS-1$
        }

        /** @return whether this task has stopped moving */
        public boolean isTerminal()
        {
            return !WORKING.equals(status);
        }

        synchronized void cancel()
        {
            if (isTerminal())
            {
                return;
            }
            PendingWorkRegistry registry = PendingWorkRegistry.domainOf(runKey);
            PendingWorkRegistry.StopOutcome stopping = registry == null
                ? PendingWorkRegistry.StopOutcome.NOTHING_TO_STOP
                : registry.cancelAndStop(runKey);
            statusMessage = cancellationMessage(stopping);
            status = CANCELLED;
            lastUpdatedAt = System.currentTimeMillis();
        }

        /**
         * What the caller is told, according to what stopping actually came to.
         * <p>
         * Read from the outcome rather than from whether the domain has a stopper at all: a
         * stopper that asked and was refused is not a stopper that stopped anything, and a caller
         * told otherwise reads the run as done with whatever it was holding.
         * </p>
         *
         * @param stopping what the domain reported.
         * @return the sentence for the caller
         */
        private static String cancellationMessage(PendingWorkRegistry.StopOutcome stopping)
        {
            if (stopping == PendingWorkRegistry.StopOutcome.STOPPED)
            {
                return "Cancellation was asked for. This server stopped waiting on the run, and " //$NON-NLS-1$
                    + "the domain stopped the work it had started. What that work had already " //$NON-NLS-1$
                    + "written stays written."; //$NON-NLS-1$
            }
            if (stopping == PendingWorkRegistry.StopOutcome.STILL_RUNNING)
            {
                return "Cancellation was asked for and the work was told to stop, but it had " //$NON-NLS-1$
                    + "not stopped. It may still be running and still writing."; //$NON-NLS-1$
            }
            return "Cancellation was asked for and this server stopped waiting on the run. The " //$NON-NLS-1$
                + "work itself may still be finishing: a Designer-mode process cannot be " //$NON-NLS-1$
                + "interrupted once started."; //$NON-NLS-1$
        }

        /**
         * Subscribes to the work, so the answer lands here when the work finishes.
         * <p>
         * The first version took the answer out of the registry on whichever poll first found the
         * work done. Three faults followed from that one decision, and they are worth naming
         * because the fix is one line of design rather than three patches:
         * </p>
         * <ul>
         * <li>the result was CONSUMED, so a second task over the same run - which happens whenever
         * two identical calls coalesce - found nothing and reported the run had vanished;</li>
         * <li>two concurrent polls could both pass the not-yet-terminal check, and the loser
         * overwrote the winner's answer with a failure;</li>
         * <li>the registry drops an uncollected result after five minutes, while the task told the
         * client it had thirty - so a client that believed the TTL got a failure.</li>
         * </ul>
         * <p>
         * Subscribing removes all three: the answer is copied here the moment the work produces it,
         * whoever is or is not polling.
         * </p>
         */
        void watchTheWork()
        {
            PendingWorkRegistry registry = PendingWorkRegistry.domainOf(runKey);
            PendingWorkRegistry.PendingEntry entry = registry == null ? null : registry.get(runKey);
            if (entry == null || entry.future == null)
            {
                // Nothing to subscribe to. The poll path below reports it rather than leaving the
                // task working forever.
                return;
            }
            entry.future.whenComplete((produced, thrown) -> settle(produced, thrown));
        }

        /**
         * Records what the work produced, once.
         *
         * @param produced the tool's answer, or {@code null} when the work threw.
         * @param thrown what it threw, or {@code null}.
         */
        synchronized void settle(String produced, Throwable thrown)
        {
            if (isTerminal())
            {
                return;
            }
            if (produced != null)
            {
                // The answer before the status, and not the other way round: a reader that sees
                // COMPLETED must find the result already there.
                result = produced;
                statusMessage = operation + " finished"; //$NON-NLS-1$
                status = COMPLETED;
            }
            else
            {
                failure = thrown == null ? "The run finished without producing a result." //$NON-NLS-1$
                    : "The run failed: " + thrown.getMessage(); //$NON-NLS-1$
                statusMessage = failure;
                status = FAILED;
            }
            lastUpdatedAt = System.currentTimeMillis();
        }

        /**
         * Reports where the work has got to, without taking anything from it.
         */
        synchronized void refresh()
        {
            if (isTerminal())
            {
                return;
            }
            PendingWorkRegistry registry = PendingWorkRegistry.domainOf(runKey);
            PendingWorkRegistry.PendingEntry running =
                registry != null ? registry.get(runKey) : null;
            if (running != null)
            {
                // Work with steps says where it has got to. Carrying that here is what makes a
                // poll worth issuing: without it every poll of a long run answers the same
                // sentence, and the caller learns only that time has passed.
                if (running.progressNote != null)
                {
                    statusMessage = running.progressNote;
                    lastUpdatedAt = System.currentTimeMillis();
                }
                return;
            }
            // The run is in no domain and nothing settled this task. Either the answer was redeemed
            // by something else - a caller re-issuing the same call with the same arguments produces
            // the same key - or the run was swept for age. Both are worth saying out loud rather
            // than reporting as still working forever.
            failure = "The run behind this task is no longer held by the server. Its result was " //$NON-NLS-1$
                + "either collected by another call with the same parameters, or the run was " //$NON-NLS-1$
                + "evicted for age. Start the work again."; //$NON-NLS-1$
            statusMessage = failure;
            status = FAILED;
            lastUpdatedAt = System.currentTimeMillis();
        }
    }
}
