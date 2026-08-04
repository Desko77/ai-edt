/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.upkeep;

import java.util.Optional;

/**
 * The single owner of upkeep state and the only place where concurrent work is serialized.
 * <p>
 * Three consumers read this state - the MCP tool, the background sweep and the status bar - and two
 * of them can start work. Neither of the obvious alternatives actually serializes anything: a job
 * family only groups tasks for cancelling and waiting, and the p2 profile lock produces a late
 * refusal after a second installer has already begun. So the rule is here instead: whoever takes
 * the work slot owns the work, and whoever fails to take it does not start.
 * </p>
 * <p>
 * <b>Two different mechanisms guard against a setting changed mid-operation, and they are not
 * interchangeable.</b>
 * </p>
 * <ul>
 * <li><b>The generation</b> decides the fate of a <em>result that depends on the source</em>. An
 * answer that arrives after the site was changed describes a site nobody asked about any more, so
 * it is dropped. The one exception is an install that already changed the profile on disk: that is
 * a fact about this machine, not about a site, and dropping it would hide the need to restart and
 * then allow another install on top of a runtime and a profile that disagree.</li>
 * <li><b>The lease</b> decides whether the place is taken. Only the holder gives it up, and only
 * when its task has actually finished or been abandoned. A generation bump must not release it: if
 * changing a setting cleared the busy flag, a second install would start on top of a live first
 * one.</li>
 * </ul>
 * <p>
 * <b>Why a lock rather than atomic fields.</b> The slot, the generation and the published snapshot
 * are one invariant, not three independent values, and an operation here reads and writes several
 * of them at once. Guarding each field separately looks thread-safe and is not: an abandoned
 * operation could pass its ownership check, lose the slot to a cancellation, and then publish its
 * stale answer over the state of a task that had already started in the meantime. Every section
 * below is a handful of field accesses with no I/O and no call into platform code, so a monitor
 * costs nothing measurable and removes the whole class of interleavings.
 * </p>
 */
public final class UpkeepLedger
{
    /** What a lease covers. */
    public enum Work
    {
        /** Reading the configured site and comparing versions. */
        CHECK,
        /** Provisioning a new version into the profile. */
        INSTALL
    }

    /**
     * A claim on the single work slot. Handed out by {@link UpkeepLedger#begin(Work)} and given
     * back by {@link UpkeepLedger#complete(Lease, ReleaseOffer)} or
     * {@link UpkeepLedger#release(Lease)}, which is why callers hold it in a try/finally.
     */
    public static final class Lease
    {
        private final long id;
        private final int generation;
        private final Work work;

        private Lease(long id, int generation, Work work)
        {
            this.id = id;
            this.generation = generation;
            this.work = work;
        }

        /**
         * @return an identifier unique within this session, for logs and diagnostics
         */
        public long id()
        {
            return id;
        }

        /**
         * @return what this lease covers
         */
        public Work work()
        {
            return work;
        }

        @Override
        public String toString()
        {
            return "Lease[" + work + " #" + id + " gen=" + generation + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
    }

    private final Object monitor = new Object();

    private ReleaseOffer current = ReleaseOffer.dormant();
    private Lease active;
    private int generation;
    private long nextLeaseId = 1L;

    /**
     * @return the current snapshot, never <code>null</code>
     */
    public ReleaseOffer current()
    {
        synchronized (monitor)
        {
            return current;
        }
    }

    /**
     * Whether work is in flight.
     * <p>
     * Read from the lease rather than from the state on purpose: changing a setting resets the
     * visible state while a task is still running, and a caller that consulted the state would then
     * start a second one.
     * </p>
     *
     * @return <code>true</code> while a lease is held
     */
    public boolean busy()
    {
        synchronized (monitor)
        {
            return active != null;
        }
    }

    /**
     * @return the current configuration generation, incremented by {@link #reconfigured}
     */
    public int generation()
    {
        synchronized (monitor)
        {
            return generation;
        }
    }

    /**
     * Takes the work slot.
     * <p>
     * An empty result is the ordinary answer for "something else is already running" and for a
     * pending restart, and callers report it as such instead of queueing a second task.
     * </p>
     *
     * @param work what the caller intends to do
     * @return the lease, or empty when the slot could not be taken
     */
    public Optional<Lease> begin(Work work)
    {
        if (work == null)
        {
            return Optional.empty();
        }
        synchronized (monitor)
        {
            if (active != null || !UpkeepPolicy.mayStartWork(current.state()))
            {
                return Optional.empty();
            }
            Lease lease = new Lease(nextLeaseId++, generation, work);
            active = lease;
            current = work == Work.CHECK ? current.checking() : current.installing();
            return Optional.of(lease);
        }
    }

    /**
     * Gives the slot back and publishes the result, unless the result has been overtaken by a
     * configuration change.
     * <p>
     * A lease that is no longer the active one publishes nothing at all - it was abandoned, and its
     * answer must not land on top of whatever started afterwards.
     * </p>
     * <p>
     * A finished install that left a pending restart is published even when the site setting was
     * edited while the install ran: the profile on disk has already changed, and that has to be
     * visible.
     * </p>
     *
     * @param lease the lease obtained from {@link #begin(Work)}
     * @param result the outcome to publish
     * @return <code>true</code> when the result was published
     */
    public boolean complete(Lease lease, ReleaseOffer result)
    {
        if (lease == null || result == null)
        {
            return false;
        }
        synchronized (monitor)
        {
            if (active != lease)
            {
                return false;
            }
            active = null;
            boolean overtaken = lease.generation != generation;
            boolean publish = !overtaken || result.state() == ReleaseOffer.State.RESTART_PENDING;
            if (publish)
            {
                current = result;
            }
            return publish;
        }
    }

    /**
     * Gives the slot back without publishing anything, for a cancelled task or a bundle stopping
     * mid-operation.
     * <p>
     * The visible state is left as the caller found it. It may still read as in-flight, which is
     * honest while the operation is being abandoned rather than finished; the next completed check
     * replaces it. Once released, the lease is spent: a task that finishes after being abandoned
     * finds the slot taken from it and publishes nothing.
     * </p>
     *
     * @param lease the lease to give back
     */
    public void release(Lease lease)
    {
        if (lease == null)
        {
            return;
        }
        synchronized (monitor)
        {
            if (active == lease)
            {
                active = null;
            }
        }
    }

    /**
     * Publishes a fact established outside any lease - at startup, for instance, where comparing
     * the running bundle against the profile establishes a pending restart with no network and no
     * task involved.
     *
     * @param offer the snapshot to publish
     */
    public void publish(ReleaseOffer offer)
    {
        if (offer == null)
        {
            return;
        }
        synchronized (monitor)
        {
            current = offer;
        }
    }

    /**
     * Records that the configuration changed: results still in flight stop being relevant, and the
     * accumulated offer is replaced by the given starting point.
     * <p>
     * A pending restart survives this. It is a statement about the running process rather than
     * about any site, and clearing it would hide from the user that their IDE is running code the
     * profile no longer describes.
     * </p>
     *
     * @param fresh the state to adopt, typically dormant or awaiting a first check
     * @return the state in effect afterwards
     */
    public ReleaseOffer reconfigured(ReleaseOffer fresh)
    {
        synchronized (monitor)
        {
            generation++;
            if (fresh != null && current.state() != ReleaseOffer.State.RESTART_PENDING)
            {
                current = fresh;
            }
            return current;
        }
    }
}
