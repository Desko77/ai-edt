/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import ru.aiedt.mcp.server.RunningToolCall;

/**
 * The ambient state of one in-flight tool call - cancellation, the client-gone flag, an optional
 * idempotency key, the negotiated timeout and the response-size cap - carried on a thread-local so a
 * tool can reach it without every method signature growing a parameter.
 *
 * <p>The scope is established on the worker thread that actually runs the tool ({@code enter} at the
 * top of the run, {@code exit} in a finally) and read through {@link #current()}. A tool that spawns
 * its own threads does not inherit it automatically - such a tool must read the fields it needs before
 * it forks, or re-establish a scope on the new thread.
 *
 * <p>The fields are wired incrementally. The response byte cap is read by the router's response path
 * (D3): a per-call {@link #responseByteLimit()} overrides the preference. Still to be wired: the
 * cancellation trigger's producers (the operator signal and a client disconnect) and its consumers
 * (the mutators and the cancellable scans), and the idempotency key - later reliability work.
 */
public final class ToolCallScope
{
    /**
     * A one-way cancellation flag with an optional human-readable reason. Set once; further requests
     * to cancel keep the first reason.
     */
    public static final class Cancellation
    {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private final AtomicReference<String> reason = new AtomicReference<>();

        /**
         * Public so a {@link RunningToolCall} can own one from the moment it is created and the scope
         * can share that same instance - closing the window where a cancel could arrive after the call
         * is active but before its scope exists.
         */
        public Cancellation()
        {
        }

        /**
         * @return <code>true</code> once cancellation has been requested
         */
        public boolean isCancelled()
        {
            return this.cancelled.get();
        }

        /**
         * Requests cancellation. The first non-null reason wins. The reason is published <em>before</em>
         * the flag is raised, so a reader that observes {@link #isCancelled()} is guaranteed by the
         * happens-before on these two writes to observe the reason as well - no window where the flag is
         * set but the reason still reads <code>null</code>.
         *
         * @param why a short reason, shown to the agent; may be <code>null</code>
         */
        public void cancel(String why)
        {
            if (why != null)
            {
                this.reason.compareAndSet(null, why);
            }
            this.cancelled.set(true);
        }

        /**
         * @return the reason cancellation was requested, or <code>null</code> if not cancelled or none
         *         was given
         */
        public String reason()
        {
            return this.reason.get();
        }

        /**
         * Throws if cancellation has been requested, so a hot loop can bail out at a checkpoint.
         *
         * @throws ToolCancelledException when {@link #isCancelled()} is <code>true</code>
         */
        public void throwIfCancelled()
        {
            if (this.cancelled.get())
            {
                throw new ToolCancelledException(this.reason.get());
            }
        }
    }

    /** Thrown by {@link Cancellation#throwIfCancelled()} to unwind a cancelled tool call. */
    public static final class ToolCancelledException
        extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        /**
         * @param reason why the call was cancelled; may be <code>null</code>
         */
        public ToolCancelledException(String reason)
        {
            super(reason != null ? reason : "cancelled"); //$NON-NLS-1$
        }
    }

    /**
     * Unset sentinel for the byte cap and the timeout: no limit / not negotiated. Deliberately
     * <code>-1</code>, not <code>0</code>, so a legitimate zero (a zero-second timeout, a zero-byte cap)
     * stays distinguishable from "not set".
     */
    public static final long UNSET = -1L;

    private static final ThreadLocal<ToolCallScope> CURRENT = new ThreadLocal<>();

    private final Cancellation cancellation;

    private final AtomicBoolean clientGone;

    private final RunningToolCall runningCall;

    private volatile long responseByteLimit = UNSET;

    private volatile String operationId;

    private volatile long timeoutSeconds = UNSET;

    /**
     * Entries of background runs this call started, by runKey.
     * <p>
     * The registry map is where a {@code runKey} is normally resolved, but tracking can be dropped
     * while the work still runs - a subject sweep, a cancel that cannot reach it - and the permit
     * that work holds must then wait on something other than the map. The starter keeps a direct
     * reference here for that case. Guarded by itself because the work re-enters this scope on
     * the executor thread while the starting thread is still inside the call.
     * </p>
     */
    private final Map<String, PendingWorkRegistry.PendingEntry> startedEntries = new LinkedHashMap<>();

    /**
     * The call's hold on the heavy-tool limiter, when it has one.
     * <p>
     * Present for two readers: the road, which lets a nested heavy call inherit its caller's
     * permit instead of taking a second one, and the router, which hands the ticket to the body so
     * a {@code Pending} answer can keep the permit until the background work finishes.
     * </p>
     */
    private volatile ToolRoad.Ticket ticket;

    private ToolCallScope(RunningToolCall runningCall, Cancellation cancellation)
    {
        this.runningCall = runningCall;
        this.clientGone = new AtomicBoolean();
        // Share the call's own Cancellation so a cancel arriving on the request thread (via the call)
        // and a checkpoint reading it on the worker thread see one and the same flag - no second
        // instance, no attach race. Tests may pass a null call, in which case the scope owns one.
        this.cancellation = cancellation != null ? cancellation
            : (runningCall != null && runningCall.cancellation() != null)
                ? runningCall.cancellation() : new Cancellation();
    }

    /**
     * A scope for the body of a nested call, carrying this one's state with only the permit
     * ticket replaced.
     *
     * @param caller the scope on the thread, whose state the derived scope shares
     * @param ticket the nested call's own hold on the limiter
     */
    private ToolCallScope(ToolCallScope caller, ToolRoad.Ticket ticket)
    {
        this.runningCall = caller.runningCall;
        this.cancellation = caller.cancellation;
        this.clientGone = caller.clientGone;
        this.responseByteLimit = caller.responseByteLimit;
        this.operationId = caller.operationId;
        this.timeoutSeconds = caller.timeoutSeconds;
        this.ticket = ticket;
    }

    /**
     * A scope like this one whose heavy-permit ticket is the given one, for the body of a nested
     * call that took a permit its caller did not have.
     * <p>
     * A light caller can still make a heavy nested call, and that call's permit is the one the
     * work under it must inherit: a grandchild asked under the caller's own scope would see no
     * permit and take a second one, which the limit refuses. The derived scope shares this one's
     * cancellation flag, call record and client-gone flag, so a cancel still reaches the whole
     * call tree; only the ticket differs, and only for the body's duration.
     * </p>
     *
     * @param ticket the nested call's permit ticket; must not be {@code null}
     * @return a scope like this one carrying that ticket
     */
    public ToolCallScope withTicket(ToolRoad.Ticket ticket)
    {
        return new ToolCallScope(this, Objects.requireNonNull(ticket, "ticket")); //$NON-NLS-1$
    }

    /**
     * Creates a scope for a tool call.
     *
     * @param call the connection the call arrived on; may be <code>null</code> in tests
     * @return a fresh scope, never <code>null</code>
     */
    public static ToolCallScope create(RunningToolCall call)
    {
        return new ToolCallScope(call, null);
    }

    /**
     * Creates a lightweight scope that carries only a cancellation flag - no call, no exchange.
     * <p>
     * The async Pending executors re-enter this on their worker thread so a cooperative loop there
     * still sees the originating call's cancellation, without the future pinning the whole call
     * graph (and its {@link RunningToolCall}'s {@code HttpExchange}) alive for the run's duration.
     *
     * @param cancellation the flag to expose; may be <code>null</code>, in which case a fresh one is made
     * @return a scope bound to that cancellation, never <code>null</code>
     */
    public static ToolCallScope forCancellation(Cancellation cancellation)
    {
        return new ToolCallScope(null, cancellation);
    }

    /**
     * Binds a scope to the current thread. Pair with {@link #exit()} in a finally.
     *
     * @param scope the scope to bind; must not be <code>null</code>
     */
    public static void enter(ToolCallScope scope)
    {
        CURRENT.set(Objects.requireNonNull(scope, "scope")); //$NON-NLS-1$
    }

    /** Unbinds the scope from the current thread. Safe to call when none is bound. */
    public static void exit()
    {
        CURRENT.remove();
    }

    /**
     * Wraps work so it runs under this call's cancellation wherever it is run.
     * <p>
     * The binding is a {@link ThreadLocal}, so work handed to another thread - the UI thread, a
     * worker, a pool - arrives with no scope and reads no cancellation flag. A checkpoint there
     * then answers "not cancelled" for the life of the call, which is indistinguishable from a
     * tool that never asks.
     * </p>
     * <p>
     * What travels is the flag, not the call. Handed-over work can outlive the call that queued it
     * - work posted to a busy UI thread stays in its queue after the caller has given up waiting -
     * and a captured {@link #create(RunningToolCall) full scope} would hold that call's
     * {@link RunningToolCall} and its {@code HttpExchange} alive until the queue drained. The
     * response-size cap and the call record are read on the thread that runs the tool, not inside
     * work handed elsewhere, so a cancellation-only scope loses nothing a checkpoint needs.
     * </p>
     * <p>
     * A thread that already has a scope of its own keeps it: the previous binding is restored
     * rather than removed, so nesting does not strand the outer call.
     * </p>
     *
     * @param <T> what the work returns
     * @param work the work to run elsewhere; must not be <code>null</code>
     * @return the work bound to this call's cancellation, or <code>work</code> itself when there is
     *         no call to carry
     */
    public static <T> Supplier<T> carryCancellation(Supplier<T> work)
    {
        Objects.requireNonNull(work, "work"); //$NON-NLS-1$
        ToolCallScope here = CURRENT.get();
        if (here == null)
        {
            return work;
        }
        ToolCallScope captured = forCancellation(here.cancellation());
        return () -> {
            ToolCallScope previous = CURRENT.get();
            enter(captured);
            try
            {
                return work.get();
            }
            finally
            {
                if (previous != null)
                {
                    enter(previous);
                }
                else
                {
                    exit();
                }
            }
        };
    }

    /**
     * @return the scope bound to the current thread, or <code>null</code> when the caller is not
     *         running inside a tool call (or spawned its own thread)
     */
    public static ToolCallScope current()
    {
        return CURRENT.get();
    }

    /**
     * @return the cancellation flag for this call, never <code>null</code>
     */
    public Cancellation cancellation()
    {
        return this.cancellation;
    }

    /**
     * @return <code>true</code> once the client is known to have disconnected mid-call
     */
    public boolean isClientGone()
    {
        return this.clientGone.get();
    }

    /** Records that the client disconnected while the tool was still running. */
    public void markClientGone()
    {
        this.clientGone.set(true);
    }

    /**
     * @return the connection this call arrived on, or <code>null</code> in tests
     */
    public RunningToolCall runningCall()
    {
        return this.runningCall;
    }

    /**
     * @return the maximum response size in bytes, or {@link #UNSET} for no cap
     */
    public long responseByteLimit()
    {
        return this.responseByteLimit;
    }

    /**
     * @param bytes the cap in bytes, or {@link #UNSET} for no cap
     */
    public void setResponseByteLimit(long bytes)
    {
        this.responseByteLimit = bytes;
    }

    /**
     * @return the client-supplied idempotency key, or <code>null</code> when none was given
     */
    public String operationId()
    {
        return this.operationId;
    }

    /**
     * @param id the client-supplied idempotency key; may be <code>null</code>
     */
    public void setOperationId(String id)
    {
        this.operationId = id;
    }

    /**
     * @return the negotiated timeout in seconds, or {@link #UNSET} when none was given
     */
    public long timeoutSeconds()
    {
        return this.timeoutSeconds;
    }

    /**
     * @param seconds the timeout in seconds, or {@link #UNSET} for none
     */
    public void setTimeoutSeconds(long seconds)
    {
        this.timeoutSeconds = seconds;
    }

    /**
     * Binds the call's heavy-permit ticket to this scope, so the body can hand it to a background
     * run and a nested heavy call can inherit it.
     *
     * @param ticket the ticket the admission produced; may be {@code null} or permit-less
     */
    public void adoptTicket(ToolRoad.Ticket ticket)
    {
        this.ticket = ticket;
    }

    /**
     * @return the heavy-permit ticket bound to this scope, or {@code null} when the call took none
     */
    public ToolRoad.Ticket ticket()
    {
        return this.ticket;
    }

    /**
     * @return whether the call this scope carries still holds a permit of the heavy-tool limiter -
     *         its own or a share another holder has not departed
     */
    public boolean holdsHeavyPermit()
    {
        ToolRoad.Ticket held = this.ticket;
        return held != null && held.holdsPermit();
    }

    /**
     * Records a background run this call started.
     * <p>
     * Written by the registry when it dispatches the work, read by the road when the call's answer
     * names the run: the registry map answers first, and this answers for the run whose tracking
     * was dropped while its work still runs.
     * </p>
     *
     * @param runKey the key the run is addressable by
     * @param entry the entry that runs the work
     */
    void notePendingEntry(String runKey, PendingWorkRegistry.PendingEntry entry)
    {
        if (runKey == null || entry == null)
        {
            return;
        }
        synchronized (startedEntries)
        {
            startedEntries.put(runKey, entry);
        }
    }

    /**
     * @param runKey the key a {@code Pending} answer named
     * @return the entry this call started under that key, or {@code null} when it started none
     */
    PendingWorkRegistry.PendingEntry pendingEntryStartedHere(String runKey)
    {
        if (runKey == null)
        {
            return null;
        }
        synchronized (startedEntries)
        {
            return startedEntries.get(runKey);
        }
    }
}
