/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.ISuspendResume;
import org.eclipse.debug.core.model.IThread;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.support.TimeoutArgs;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.DebugSessionBook;

/**
 * Pauses a running 1C debug session, so an agent can inspect a client that is idle, stuck or looping
 * without a breakpoint ever firing.
 *
 * <p>A suspend request is placed on the session and the answer says what became of it. The four
 * outcomes are the four answers a caller can act on: {@code paused} - the session stopped, and the
 * answer carries the thread, its stack and the top frame reference the other debug tools take;
 * {@code request_armed} - the request was accepted and nothing suspended within
 * {@code timeoutSeconds}, which is what a session with no BSL running answers; {@code terminated} -
 * there is no live session left to ask; {@code error} - the ask itself failed, and the answer names
 * what refused it.</p>
 *
 * <p>The request goes to one thread when {@code threadId} names one and to every thread of the
 * session otherwise, and falls back to the debug target itself when the target exposes no thread at
 * all. A named thread decides the answer on its own state: a session already stopped on another
 * thread is not read as a stop of the named one, which would answer {@code paused} about a thread
 * that is still running. A thread of another session is refused before anything is asked of it.
 * {@code canSuspend} is read for the report but not used as a gate: whether a 1C thread answers
 * it truthfully is not established, and refusing on an unverified answer would refuse the pause on
 * every client where the feature is wanted. A request the platform rejects answers {@code error} and
 * names each refusal, so a refusal is never reported as an armed request.</p>
 */
public final class DebugPauser implements IMcpTool
{
    public static final String NAME = "pause_thread"; //$NON-NLS-1$

    /** The member naming which of the four outcomes the call reached. */
    static final String KEY_OUTCOME = "outcome"; //$NON-NLS-1$

    /** The session stopped and the answer carries the thread, the stack and the top frame. */
    static final String OUTCOME_PAUSED = "paused"; //$NON-NLS-1$

    /** The suspend request was accepted and nothing suspended within the wait. */
    static final String OUTCOME_REQUEST_ARMED = "request_armed"; //$NON-NLS-1$

    /** There is no live session to pause. */
    static final String OUTCOME_TERMINATED = "terminated"; //$NON-NLS-1$

    /** The ask itself failed. */
    static final String OUTCOME_ERROR = "error"; //$NON-NLS-1$

    /**
     * How long the pause waits by default, in seconds.
     * <p>
     * A pause that lands at all lands at the next BSL line, so the default covers the ordinary case
     * without holding the request open; a session that is not running BSL answers
     * {@code request_armed} after that wait rather than making the caller ask again.
     * </p>
     */
    private static final int DEFAULT_TIMEOUT = 5;

    /**
     * How often a named thread is re-read while waiting for it to stop, in milliseconds.
     * <p>
     * The named thread is judged on its own state rather than on the registry's snapshot, so the
     * wait polls the thread rather than parking on the registry's monitor.
     * </p>
     */
    private static final long NAMED_THREAD_STEP_MS = 100L;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `launch_debugger` `action=pause_thread`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Asks a running 1C debug session to suspend, so a client that is idle or looping can be " //$NON-NLS-1$
            + "inspected without setting a breakpoint first. threadId names one thread (as wait_for_break " //$NON-NLS-1$
            + "reported it); without it every thread of the session is asked. The answer opens with " //$NON-NLS-1$
            + "`outcome`: paused (suspended - the thread, the stack and topFrameRef are in the answer), " //$NON-NLS-1$
            + "request_armed (the request was accepted and nothing suspended within timeoutSeconds), " //$NON-NLS-1$
            + "terminated (no live session) or error. A pause is not a breakpoint: nothing has to be " //$NON-NLS-1$
            + "armed in advance."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("applicationId", //$NON-NLS-1$
                "Id of the running debug session (a real id, or 'attach:<configName>' for attach " //$NON-NLS-1$
                    + "launches). Can be left out when there is only one active debug launch.")
            .integerProperty("threadId", //$NON-NLS-1$
                "Thread to suspend, as wait_for_break reported it. Omitted, every thread of the " //$NON-NLS-1$
                    + "session is asked. An id the registry no longer holds is refused.")
            .integerProperty("timeoutSeconds", //$NON-NLS-1$
                "How long to wait for the suspend, in seconds: 1..50, 5 by default; a longer ask is " //$NON-NLS-1$
                    + "cut to 50 and the answer says so. Aliases: timeout, waitSeconds, timeoutMs.")
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        long threadId = JsonUtils.extractLongArgument(params, "threadId", -1L); //$NON-NLS-1$
        Integer requested = TimeoutArgs.requestedSeconds(params);
        int timeout = TimeoutArgs.readSeconds(params, DEFAULT_TIMEOUT, 1, SuspendWaiter.MAX_TIMEOUT);
        boolean capped = requested != null && requested.intValue() > SuspendWaiter.MAX_TIMEOUT;

        DebugSessionBook registry = DebugSessionBook.get();
        registry.ensureListenerRegistered();

        boolean autoResolved = false;
        if (applicationId == null || applicationId.isEmpty())
        {
            applicationId = DebugSessionBook.findLoneActiveApplicationId();
            if (applicationId == null)
            {
                return ToolResult.error(
                    "applicationId must be provided: there is no single active debug launch to resolve it from automatically. " //$NON-NLS-1$
                        + "Call debug_status to see the active launches.")
                    .put(KEY_OUTCOME, OUTCOME_ERROR)
                    .put("activeLaunches", DebugSessionBook.describeActiveLaunches()) //$NON-NLS-1$
                    .toJson();
            }
            autoResolved = true;
        }

        IThread only = null;
        if (threadId > 0)
        {
            only = registry.getThread(threadId);
            if (only == null)
            {
                return ToolResult.error("Unknown or stale threadId " + threadId //$NON-NLS-1$
                    + ": the registry no longer holds that thread, so there is nothing to pause on that address. " //$NON-NLS-1$
                    + "Call wait_for_break for a current threadId, or leave threadId out to pause the whole session.")
                    .put(KEY_OUTCOME, OUTCOME_ERROR)
                    .put("applicationId", applicationId) //$NON-NLS-1$
                    .put("threadId", threadId) //$NON-NLS-1$
                    .toJson();
            }
            ToolResult foreign = DebugThreadOwnership.refusal(only, applicationId, threadId);
            if (foreign != null)
            {
                return foreign.put(KEY_OUTCOME, OUTCOME_ERROR).toJson();
            }
        }

        try
        {
            ToolResult result = pause(registry, applicationId,
                DebugSessionBook.findActiveTarget(applicationId), only, threadId, timeout);
            if (autoResolved)
            {
                result.put("autoResolved", true); //$NON-NLS-1$
            }
            if (capped)
            {
                result.put("timeoutCapped", Boolean.TRUE) //$NON-NLS-1$
                    .put("requestedSeconds", requested) //$NON-NLS-1$
                    .put("capNote", "A single request does not live long enough for the wait you " //$NON-NLS-1$ //$NON-NLS-2$
                        + "asked for, so it was cut to " + SuspendWaiter.MAX_TIMEOUT + " seconds."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return result.toJson();
        }
        catch (Exception e)
        {
            Activator.logError("pause_thread failed", e); //$NON-NLS-1$
            return ToolResult.error("Failed to pause the debug session: " + e.getMessage()) //$NON-NLS-1$
                .put(KEY_OUTCOME, OUTCOME_ERROR)
                .put("applicationId", applicationId) //$NON-NLS-1$
                .toJson();
        }
    }

    /**
     * Pauses the session the address resolves to, with the thread id left unnamed.
     * <p>
     * The seam the tests drive; the caller named no thread id, so an answer built from a thread the
     * registry registered resolves its id from what the registry holds.
     * </p>
     *
     * @param registry the session registry
     * @param applicationId the application to pause
     * @param target the session's live debug target, or <code>null</code> when there is none - the
     *            seam a test drives against a target it built
     * @param only the one thread to ask, or <code>null</code> for every thread of the session
     * @param timeoutSeconds how long to wait for the suspend to arrive, in seconds
     * @return the answer, without the asides {@link #execute} adds
     * @throws Exception when the session stopped and its stack could not be read
     */
    static ToolResult pause(DebugSessionBook registry, String applicationId, IDebugTarget target,
        IThread only, int timeoutSeconds) throws Exception
    {
        return pause(registry, applicationId, target, only, -1L, timeoutSeconds);
    }

    /**
     * Pauses the session the address resolves to.
     * <p>
     * The resolution steps are ordered by what they cost the caller. A thread the caller named
     * decides on its own state - a snapshot of another thread of the same session says nothing about
     * the named one, and answering from it would report a stop that did not happen. A call that
     * named no thread is answered from the registry when the session is already suspended, so a
     * pause over a client that stopped at a breakpoint reports the stop it has rather than asking
     * again. Otherwise the request goes to the thread the caller named, or to every thread of the
     * session, or - when the target exposes none - to the target itself, which stands for the
     * session as a whole. Then the platform is given {@code timeoutSeconds} to report a suspend.
     * </p>
     *
     * @param registry the session registry
     * @param applicationId the application to pause
     * @param target the session's live debug target, or <code>null</code> when there is none - the
     *            seam a test drives against a target it built
     * @param only the one thread to ask, or <code>null</code> for every thread of the session
     * @param namedThreadId the id the caller named the thread by, or a value at most zero when the
     *            call named no thread
     * @param timeoutSeconds how long to wait for the suspend to arrive, in seconds
     * @return the answer, without the asides {@link #execute} adds
     * @throws Exception when the session stopped and its stack could not be read
     */
    static ToolResult pause(DebugSessionBook registry, String applicationId, IDebugTarget target,
        IThread only, long namedThreadId, int timeoutSeconds) throws Exception
    {
        if (target == null || target.isTerminated())
        {
            return terminated(applicationId, target != null);
        }

        if (only != null)
        {
            return pauseNamedThread(registry, applicationId, target, only, namedThreadId,
                timeoutSeconds);
        }

        DebugSessionBook.SuspendSnapshot arrived = registry.getSnapshot(applicationId);
        if (arrived == null)
        {
            SuspendWaiter.scanForAlreadySuspended(registry, applicationId, target);
            arrived = registry.getSnapshot(applicationId);
        }
        if (arrived != null)
        {
            return paused(arrived, registry, applicationId);
        }

        List<Map<String, Object>> armed = new ArrayList<>();
        List<Map<String, Object>> refused = new ArrayList<>();
        List<IThread> threads = both(only, target);
        for (IThread thread : threads)
        {
            place(thread, thread.getName(), armed, refused);
        }
        if (threads.isEmpty())
        {
            place(target, "debug target", armed, refused); //$NON-NLS-1$
        }
        if (armed.isEmpty())
        {
            return allRefused(applicationId, refused);
        }

        try
        {
            arrived = registry.waitForSuspend(applicationId, timeoutSeconds * 1000L);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return ToolResult.error("The wait for the debug session to pause was interrupted") //$NON-NLS-1$
                .put(KEY_OUTCOME, OUTCOME_ERROR)
                .put("applicationId", applicationId) //$NON-NLS-1$
                .put("armed", armed); //$NON-NLS-1$
        }
        if (arrived != null)
        {
            return paused(arrived, registry, applicationId);
        }
        if (target.isTerminated())
        {
            return terminated(applicationId, true);
        }
        return ToolResult.success()
            .put(KEY_OUTCOME, OUTCOME_REQUEST_ARMED)
            .put("applicationId", applicationId) //$NON-NLS-1$
            .put("waitedSeconds", timeoutSeconds) //$NON-NLS-1$
            .put("armed", armed) //$NON-NLS-1$
            .put("note", "The suspend request was accepted and nothing suspended within " //$NON-NLS-1$ //$NON-NLS-2$
                + timeoutSeconds + " seconds - what a session with no BSL running answers. The request " //$NON-NLS-1$
                + "stands: call wait_for_break to block until it lands, or call pause_thread again.")
            .hint("launch_debugger", Map.of("action", "wait_for_break")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The answer for a session that suspended: the shared snapshot body plus this tool's outcome.
     *
     * @param snapshot the suspended-thread snapshot
     * @param registry the session registry
     * @param applicationId the application that suspended
     * @return the answer
     * @throws Exception when the stack of the suspended thread cannot be read
     */
    private static ToolResult paused(DebugSessionBook.SuspendSnapshot snapshot,
        DebugSessionBook registry, String applicationId) throws Exception
    {
        return SuspendWaiter.buildSnapshotResult(snapshot, registry, applicationId, false)
            .put(KEY_OUTCOME, OUTCOME_PAUSED);
    }

    /**
     * Pauses the thread the caller named, judged on that thread's own state.
     * <p>
     * A snapshot the registry holds for the application may describe another thread, and answering
     * from it would report a stop that did not happen: the named thread that is already suspended is
     * answered as such with {@code alreadySuspended: true} and nothing is asked of it, and one that
     * is running receives the suspend request and is watched until it stops, the wait runs out or
     * the session ends.
     * </p>
     *
     * @param registry the session registry
     * @param applicationId the application to pause
     * @param target the session's live debug target
     * @param thread the thread the caller named
     * @param namedThreadId the id the caller named it by, or a value at most zero
     * @param timeoutSeconds how long to wait for the thread to suspend, in seconds
     * @return the answer
     * @throws Exception when the thread stopped and its stack could not be read
     */
    private static ToolResult pauseNamedThread(DebugSessionBook registry, String applicationId,
        IDebugTarget target, IThread thread, long namedThreadId, int timeoutSeconds)
        throws Exception
    {
        if (threadSuspended(thread))
        {
            return pausedThread(registry, applicationId, thread, namedThreadId, true);
        }

        List<Map<String, Object>> armed = new ArrayList<>();
        List<Map<String, Object>> refused = new ArrayList<>();
        place(thread, thread.getName(), armed, refused);
        if (armed.isEmpty())
        {
            return allRefused(applicationId, refused);
        }

        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (!threadSuspended(thread))
        {
            if (target.isTerminated())
            {
                return terminated(applicationId, true);
            }
            if (System.currentTimeMillis() >= deadline)
            {
                ToolResult armedAnswer = ToolResult.success()
                    .put(KEY_OUTCOME, OUTCOME_REQUEST_ARMED)
                    .put("applicationId", applicationId) //$NON-NLS-1$
                    .put("waitedSeconds", timeoutSeconds) //$NON-NLS-1$
                    .put("armed", armed) //$NON-NLS-1$
                    .put("note", "The suspend request was accepted on the named thread and it did " //$NON-NLS-1$
                        + "not stop within " + timeoutSeconds + " seconds - what a thread with no " //$NON-NLS-1$
                        + "BSL running answers. The request stands: call wait_for_break to block " //$NON-NLS-1$
                        + "until it lands, or call pause_thread again.")
                    .hint("launch_debugger", Map.of("action", "wait_for_break")); //$NON-NLS-1$ //$NON-NLS-2$
                if (namedThreadId > 0)
                {
                    armedAnswer.put("threadId", namedThreadId); //$NON-NLS-1$
                }
                return armedAnswer;
            }
            Thread.sleep(NAMED_THREAD_STEP_MS);
        }
        return pausedThread(registry, applicationId, thread, namedThreadId, false);
    }

    /**
     * The answer for a named thread that is suspended: the shared body built from the thread itself,
     * echoing the id the caller named.
     *
     * @param registry the session registry
     * @param applicationId the application that suspended
     * @param thread the suspended thread the caller named
     * @param namedThreadId the id the caller named it by, or a value at most zero
     * @param alreadySuspended whether the thread was suspended before anything was asked
     * @return the answer
     * @throws Exception when the stack of the suspended thread cannot be read
     */
    private static ToolResult pausedThread(DebugSessionBook registry, String applicationId,
        IThread thread, long namedThreadId, boolean alreadySuspended) throws Exception
    {
        ToolResult result = SuspendWaiter.buildThreadResult(
            idFor(registry, applicationId, thread, namedThreadId), thread, registry, applicationId,
            false).put(KEY_OUTCOME, OUTCOME_PAUSED);
        if (alreadySuspended)
        {
            result.put("alreadySuspended", Boolean.TRUE); //$NON-NLS-1$
        }
        return result;
    }

    /**
     * The id the answer names a suspended thread by: the one the caller named, or - when the call
     * reached this tool without one - the id the registry issued for this thread, registering the
     * stop first when nothing else had.
     *
     * @param registry the session registry
     * @param applicationId the application that suspended
     * @param thread the suspended thread
     * @param namedThreadId the id the caller named, or a value at most zero
     * @return the id to answer with, or -1 when neither the caller nor the registry has one
     */
    private static long idFor(DebugSessionBook registry, String applicationId, IThread thread,
        long namedThreadId)
    {
        if (namedThreadId > 0)
        {
            return namedThreadId;
        }
        DebugSessionBook.SuspendSnapshot recorded = registry.getSnapshot(applicationId);
        if (recorded != null && recorded.thread == thread)
        {
            return recorded.threadId;
        }
        registry.injectSuspend(applicationId, thread);
        recorded = registry.getSnapshot(applicationId);
        return recorded != null && recorded.thread == thread ? recorded.threadId : -1L;
    }

    /**
     * Whether the thread reports itself suspended. A thread that cannot answer reads as running: the
     * wait around this question is what a stopped client answers eventually, and a thread that went
     * with its session is settled by the terminated check beside it.
     *
     * @param thread the thread to read
     * @return whether it reports itself suspended
     */
    private static boolean threadSuspended(IThread thread)
    {
        try
        {
            return thread.isSuspended();
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /**
     * The answer for a session that refused every suspend request.
     *
     * @param applicationId the application that was addressed
     * @param refused the refused requests, each naming its thread and why it refused
     * @return the answer
     */
    private static ToolResult allRefused(String applicationId, List<Map<String, Object>> refused)
    {
        return ToolResult.error("The debug session refused every suspend request: " //$NON-NLS-1$
            + describe(refused) + ". The session is alive, so this is a state it will not stop in, " //$NON-NLS-1$
            + "not a missing launch.")
            .put(KEY_OUTCOME, OUTCOME_ERROR)
            .put("applicationId", applicationId) //$NON-NLS-1$
            .put("refused", refused) //$NON-NLS-1$
            .hint("launch_debugger", Map.of("action", "debug_status")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The answer for a session that is not there any more.
     *
     * @param applicationId the application that was addressed
     * @param seen whether a target was found at all, which separates a launch that ended from an
     *            address that never had one
     * @return the answer
     */
    private static ToolResult terminated(String applicationId, boolean seen)
    {
        return ToolResult.success()
            .put(KEY_OUTCOME, OUTCOME_TERMINATED)
            .put("applicationId", applicationId) //$NON-NLS-1$
            .put("note", seen //$NON-NLS-1$
                ? "The debug session has terminated, so there is nothing left to pause. " //$NON-NLS-1$
                    + "Call debug_status to see the active launches."
                : "No live debug session is running for this applicationId. " //$NON-NLS-1$
                    + "Call debug_status to see the active launches.")
            .hint("launch_debugger", Map.of("action", "debug_status")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Places one suspend request and files it under what came of it.
     *
     * @param stoppable the thread or target to ask
     * @param name how the answer names it
     * @param armed collects the requests that were accepted
     * @param refused collects the requests the platform rejected
     */
    private static void place(ISuspendResume stoppable, String name, List<Map<String, Object>> armed,
        List<Map<String, Object>> refused)
    {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("thread", name); //$NON-NLS-1$
        try
        {
            request.put("canSuspend", Boolean.valueOf(stoppable.canSuspend())); //$NON-NLS-1$
            stoppable.suspend();
            armed.add(request);
        }
        catch (Exception e)
        {
            request.put("reason", e.getMessage() != null ? e.getMessage() : e.toString()); //$NON-NLS-1$
            refused.add(request);
        }
    }

    /**
     * The threads to ask: the one the caller named, or every thread the target exposes.
     *
     * @param only the named thread; may be <code>null</code>
     * @param target the live debug target
     * @return the threads to ask, empty when the target exposes none
     */
    private static List<IThread> both(IThread only, IDebugTarget target)
    {
        if (only != null)
        {
            return Collections.singletonList(only);
        }
        try
        {
            IThread[] threads = target.getThreads();
            return threads == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(threads));
        }
        catch (Exception e)
        {
            return new ArrayList<>();
        }
    }

    /**
     * @param refused the refused requests, each naming its thread and why it refused
     * @return the refusals as one sentence
     */
    private static String describe(List<Map<String, Object>> refused)
    {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> request : refused)
        {
            if (sb.length() > 0)
            {
                sb.append("; "); //$NON-NLS-1$
            }
            sb.append(request.get("thread")).append(": ").append(request.get("reason")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return sb.toString();
    }
}
