/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IThread;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.DebugSessionBook;

/**
 * Resumes a suspended 1C debug thread (by thread id) or every thread of a debug target (by application id).
 * With no arguments, resumes the single active launch when exactly one is running.
 */
public final class DebugResumer implements IMcpTool
{
    public static final String NAME = "resume"; //$NON-NLS-1$

    private static final String DESC = "Back-compat alias of `launch_debugger` `action=resume`; prefer the facade for new prompts. " //$NON-NLS-1$
        + "Resume execution of a suspended debug thread, or every thread of a debug " //$NON-NLS-1$
        + "target. Pass threadId (from wait_for_break) or applicationId. " //$NON-NLS-1$
        + "Called with no arguments, resumes the one active debug launch when exactly one exists."; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return DESC;
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .integerProperty("threadId", "Thread id returned by wait_for_break") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application id (real, or 'attach:<configName>') - resumes every thread of that target")
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
        long threadId = JsonUtils.extractLongArgument(params, "threadId", -1L); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$


        DebugSessionBook registry = DebugSessionBook.get();
        registry.ensureListenerRegistered();

        try
        {
            if (threadId > 0)
            {
                IThread thread = registry.getThread(threadId);
                if (thread == null)
                {
                    return ToolResult.error("stale threadId - call wait_for_break again").toJson(); //$NON-NLS-1$
                }
                ToolResult foreignThread = DebugThreadOwnership.refusal(thread,
                    DebugThreadOwnership.namedApplication(params), threadId);
                if (foreignThread != null)
                {
                    return foreignThread.toJson();
                }
                if (!thread.canResume())
                {
                    return ToolResult.error("thread is not resumable (state: " //$NON-NLS-1$
                        + (thread.isSuspended() ? "suspended" : "running") //$NON-NLS-1$ //$NON-NLS-2$
                        + ")").toJson(); //$NON-NLS-1$
                }
                String owner = DebugSessionBook.findApplicationIdFor(thread);
                thread.resume();
                // Drop the snapshot here rather than waiting for the platform's RESUME event: the
                // caller is told to wait right after this answer, and the event arrives later.
                registry.clearSnapshot(owner);
                return ToolResult.success().put("resumed", true) //$NON-NLS-1$
                    .put("scope", "thread").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
            }

            String effectiveAppId = (applicationId != null && !applicationId.isEmpty())
                ? applicationId
                : DebugSessionBook.findLoneActiveApplicationId();
            if (effectiveAppId == null)
            {
                return ToolResult.error(
                    "Provide threadId or applicationId - there isn't exactly one active debug launch to " //$NON-NLS-1$
                        + "resolve automatically. Use debug_status to list active launches.").toJson();
            }

            IDebugTarget target = DebugSessionBook.findActiveTarget(effectiveAppId);
            ToolResult resumed = resumeApplication(registry, effectiveAppId, target,
                applicationId == null || applicationId.isEmpty());
            if (resumed != null)
            {
                return resumed.toJson();
            }
            if (target == null)
            {
                return ToolResult.error(
                    "no active debug target found for applicationId: " + effectiveAppId).toJson(); //$NON-NLS-1$
            }
            return ToolResult.error(
                "no suspended thread found to resume for applicationId: " + effectiveAppId).toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("resume failed", e); //$NON-NLS-1$
            return ToolResult.error("Error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Resumes every suspended thread of an application.
     * <p>
     * A thread of this platform is what resumes; the target itself is not resumable, so it is only
     * where the threads are found. The application's suspend snapshot names one of them, and the
     * target's own threads the rest - both are collected before any of them is resumed, so an answer
     * names every thread it acted on whatever route found it.
     * </p>
     * <p>
     * The application's snapshot is dropped before the threads are resumed, so it cannot survive a
     * resume that fails partway through the list. The platform reports a resume asynchronously, and a
     * caller that waits right after this answer would otherwise read the stop that has just been left
     * behind.
     * </p>
     *
     * @param registry the session registry
     * @param applicationId the application to resume; must not be <code>null</code>
     * @param target the application's debug target, or <code>null</code> when it has none
     * @param autoResolved whether the application was picked rather than named by the caller
     * @return the result, or <code>null</code> when the application has no suspended thread
     * @throws DebugException when the debug model refuses to report the state of the target or a
     *             thread, or to resume one of them
     */
    static ToolResult resumeApplication(DebugSessionBook registry, String applicationId,
        IDebugTarget target, boolean autoResolved) throws DebugException
    {
        List<IThread> toResume = new ArrayList<>();

        DebugSessionBook.SuspendSnapshot snapshot = registry.getSnapshot(applicationId);
        if (snapshot != null && snapshot.thread != null && snapshot.thread.canResume())
        {
            toResume.add(snapshot.thread);
        }
        if (target != null && !target.isTerminated())
        {
            for (IThread thread : target.getThreads())
            {
                if (thread.isSuspended() && thread.canResume() && !contains(toResume, thread))
                {
                    toResume.add(thread);
                }
            }
        }
        if (toResume.isEmpty())
        {
            return null;
        }

        // Dropped before the resume rather than after it: a resume that fails on the second thread
        // would otherwise leave a snapshot standing for the first one, which is already away.
        registry.clearSnapshot(applicationId);

        List<Long> resumedIds = new ArrayList<>();
        for (IThread thread : toResume)
        {
            thread.resume();
            resumedIds.add(Long.valueOf(registry.threadIdOf(thread)));
        }

        ToolResult res = ToolResult.success().put("resumed", true) //$NON-NLS-1$
            .put("scope", "target") //$NON-NLS-1$ //$NON-NLS-2$
            .put("applicationId", applicationId) //$NON-NLS-1$
            .put("resumedCount", toResume.size())
            .put("resumedThreadIds", resumedIds);
        if (autoResolved)
        {
            res.put("autoResolved", true); //$NON-NLS-1$
        }
        return res;
    }

    /**
     * @param threads the threads collected so far
     * @param thread the thread to look for
     * @return whether the thread is already among them
     */
    private static boolean contains(List<IThread> threads, IThread thread)
    {
        for (IThread collected : threads)
        {
            if (collected == thread)
            {
                return true;
            }
        }
        return false;
    }
}
