/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Map;

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
                if (!thread.canResume())
                {
                    return ToolResult.error("thread is not resumable (state: " //$NON-NLS-1$
                        + (thread.isSuspended() ? "suspended" : "running") //$NON-NLS-1$ //$NON-NLS-2$
                        + ")").toJson(); //$NON-NLS-1$
                }
                thread.resume();
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

            // Resume the suspended thread, not the target (EDT targets don't support canResume)
            DebugSessionBook.SuspendSnapshot snapshot = registry.getSnapshot(effectiveAppId);
            if (snapshot != null && snapshot.thread.canResume())
            {
                snapshot.thread.resume();
                ToolResult res = ToolResult.success().put("resumed", true) //$NON-NLS-1$
                    .put("scope", "thread") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("applicationId", effectiveAppId); //$NON-NLS-1$
                if (applicationId == null || applicationId.isEmpty())
                {
                    res.put("autoResolved", true); //$NON-NLS-1$
                }
                return res.toJson();
            }

            // Fallback: scan the target's threads for a suspended, resumable one
            IDebugTarget target = DebugSessionBook.findActiveTarget(effectiveAppId);
            if (target == null)
            {
                return ToolResult.error(
                    "no active debug target found for applicationId: " + effectiveAppId).toJson(); //$NON-NLS-1$
            }
            for (IThread thread : target.getThreads())
            {
                if (thread.isSuspended() && thread.canResume())
                {
                    thread.resume();
                    ToolResult res = ToolResult.success().put("resumed", true) //$NON-NLS-1$
                        .put("scope", "thread") //$NON-NLS-1$ //$NON-NLS-2$
                        .put("applicationId", effectiveAppId); //$NON-NLS-1$
                    if (applicationId == null || applicationId.isEmpty())
                    {
                        res.put("autoResolved", true); //$NON-NLS-1$
                    }
                    return res.toJson();
                }
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
}
