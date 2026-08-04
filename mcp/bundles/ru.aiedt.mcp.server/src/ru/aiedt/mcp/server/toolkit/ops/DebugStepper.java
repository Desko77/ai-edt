/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Map;

import org.eclipse.debug.core.model.IStep;
import org.eclipse.debug.core.model.IThread;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.support.TimeoutArgs;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.DebugSessionBook;
import ru.aiedt.mcp.server.support.TextSuggest;

/**
 * Steps a suspended 1C debug thread (over, into or out of the current line) and blocks until the next
 * SUSPEND event, returning a fresh frame snapshot in the same shape as {@code wait_for_break}.
 */
public final class DebugStepper implements IMcpTool
{
    public static final String NAME = "step"; //$NON-NLS-1$

    private static final String DESC = "Back-compat alias of `launch_debugger` `action=step`; prefer the facade for new prompts. " //$NON-NLS-1$
        + "Steps a suspended debug thread once. kind is one of {over, into, out} (return " //$NON-NLS-1$
        + "works as an alias for out). Waits for the next SUSPEND event (or a timeout), then hands back the fresh frame snapshot."; //$NON-NLS-1$

    private static final int DEFAULT_TIMEOUT = 30;

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
            .integerProperty("threadId", "Thread id obtained from wait_for_break (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("kind", "Which step to perform: over, into, out (return behaves as out) (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("timeoutSeconds", //$NON-NLS-1$
                "How long to wait, in seconds (default: 30). Legacy aliases: timeout (seconds), " //$NON-NLS-1$
                    + "timeoutMs (milliseconds).") //$NON-NLS-1$
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
        String kind = JsonUtils.extractStringArgument(params, "kind"); //$NON-NLS-1$
        if (kind == null || kind.isEmpty())
        {
            // The facade passes mode (over/into/out) for the bare step action; accept it as a fallback
            kind = JsonUtils.extractStringArgument(params, "mode"); //$NON-NLS-1$
        }
        int timeout = TimeoutArgs.readSeconds(params, DEFAULT_TIMEOUT, 1, 0);

        if (threadId <= 0)
        {
            // Auto-resolve from the suspended snapshot when threadId is not supplied
            String appId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
            if (appId == null || appId.isEmpty())
            {
                appId = DebugSessionBook.findLoneActiveApplicationId();
            }
            DebugSessionBook.SuspendSnapshot snapshot =
                appId != null ? DebugSessionBook.get().getSnapshot(appId) : null;
            if (snapshot == null)
            {
                return ToolResult.error(
                    "threadId must be supplied - call wait_for_break first to obtain one").toJson(); //$NON-NLS-1$
            }
            threadId = snapshot.threadId;
        }
        if (kind == null || kind.isEmpty())
        {
            return ToolResult.error("kind must be supplied (over/into/out)").toJson(); //$NON-NLS-1$
        }

        DebugSessionBook registry = DebugSessionBook.get();
        IThread thread = registry.getThread(threadId);
        if (thread == null)
        {
            return ToolResult.error("threadId is stale - call wait_for_break again").toJson(); //$NON-NLS-1$
        }
        if (!(thread instanceof IStep))
        {
            return ToolResult.error("this thread does not support stepping").toJson(); //$NON-NLS-1$
        }
        IStep stepper = (IStep)thread;

        String appId = DebugSessionBook.findApplicationIdFor(thread);
        if (appId == null)
        {
            return ToolResult.error("unable to resolve an applicationId for this thread").toJson(); //$NON-NLS-1$
        }

        try
        {
            // Clear the current snapshot so waitForSuspend only catches the NEW suspend after the step.
            registry.clearSnapshot(appId);

            switch (kind.toLowerCase())
            {
            case "over": //$NON-NLS-1$
                if (!stepper.canStepOver())
                {
                    return ToolResult.error("this thread cannot step over right now").toJson(); //$NON-NLS-1$
                }
                stepper.stepOver();
                break;
            case "into": //$NON-NLS-1$
                if (!stepper.canStepInto())
                {
                    return ToolResult.error("this thread cannot step into right now").toJson(); //$NON-NLS-1$
                }
                stepper.stepInto();
                break;
            case "out": //$NON-NLS-1$
            case "return": //$NON-NLS-1$
                if (!stepper.canStepReturn())
                {
                    return ToolResult.error("this thread cannot step out right now").toJson(); //$NON-NLS-1$
                }
                stepper.stepReturn();
                break;
            default:
                return ToolResult.error(TextSuggest.invalidValue("kind", kind, //$NON-NLS-1$
                    java.util.Arrays.asList("over", "into", "out", "return"))).toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            }

            DebugSessionBook.SuspendSnapshot snapshot = registry.waitForSuspend(appId,
                timeout * 1000L);
            if (snapshot == null)
            {
                return ToolResult.success().put("hit", false) //$NON-NLS-1$
                    .put("reason", "timeout").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return SuspendWaiter.buildSnapshotResponse(snapshot, registry, appId, false);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return ToolResult.error("the wait was interrupted").toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("step tool failed", e); //$NON-NLS-1$
            return ToolResult.error("Error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }
}
