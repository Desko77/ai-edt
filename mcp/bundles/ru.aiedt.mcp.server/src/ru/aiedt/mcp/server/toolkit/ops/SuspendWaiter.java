/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.support.TimeoutArgs;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.DebugSessionBook;

/**
 * Blocks until a 1C debug session suspends (a breakpoint hit, the end of a step, and so on) and returns
 * the snapshot of the suspended thread. Returns at once when the session is already suspended; on timeout
 * returns {@code {hit:false}} without terminating the launch.
 */
public final class SuspendWaiter implements IMcpTool
{
    public static final String NAME = "wait_for_break"; //$NON-NLS-1$

    private static final String DESC =
        "Back-compat alias of `launch_debugger` `action=wait_for_break`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Blocks until the given application's debug session hits a suspend point (breakpoint, step end, etc). " //$NON-NLS-1$
            + "Delivers a snapshot of the suspended thread and its frames, or {hit:false} once the wait times out. " //$NON-NLS-1$
            + "applicationId can be a real id or the synthetic form 'attach:<configName>'. " //$NON-NLS-1$
            + "Left unspecified with only one EDT debug launch running, that launch is picked automatically. " //$NON-NLS-1$
            + "A timeout does not terminate the launch; invoke this tool again to keep waiting."; //$NON-NLS-1$

    private static final int DEFAULT_TIMEOUT = 60;

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
            .stringProperty("applicationId", //$NON-NLS-1$
                "Id of the running debug session (a real id, or 'attach:<configName>' for attach launches). " //$NON-NLS-1$
                    + "Can be left out when there is only one active debug launch.")
            .integerProperty("timeoutSeconds", //$NON-NLS-1$
                "How long to wait, in seconds (defaults to 60). Canonical name; the legacy aliases " //$NON-NLS-1$
                    + "timeout (seconds) and timeoutMs (milliseconds) are still accepted.") //$NON-NLS-1$
            .integerProperty("timeoutMs", //$NON-NLS-1$
                "Legacy alias for the wait window, in milliseconds (rounded to seconds, minimum 1). " //$NON-NLS-1$
                    + "Precedence: timeoutSeconds wins; otherwise timeoutMs beats the legacy timeout.")
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
        // Canonical timeoutSeconds wins; the legacy timeoutMs (ms) still beats the legacy timeout
        // (seconds) when timeoutSeconds is absent, as it did before.
        int timeout = TimeoutArgs.readSeconds(params, DEFAULT_TIMEOUT, 1, 0);

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
                        + "Call debug_status to see the active launches.").toJson();
            }
            autoResolved = true;
        }

        scanForAlreadySuspended(registry, applicationId);

        try
        {
            DebugSessionBook.SuspendSnapshot snapshot = registry.waitForSuspend(applicationId,
                timeout * 1000L);
            if (snapshot == null)
            {
                ToolResult result = ToolResult.success().put("hit", false) //$NON-NLS-1$
                    .put("reason", "timeout") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("applicationId", applicationId); //$NON-NLS-1$
                if (autoResolved)
                {
                    result.put("autoResolved", true); //$NON-NLS-1$
                }
                return result.toJson();
            }
            return buildSnapshotResponse(snapshot, registry, applicationId, autoResolved);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return ToolResult.error("The wait for a debug break was interrupted").toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("wait_for_break failed", e); //$NON-NLS-1$
            return ToolResult.error("Failed: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Looks for a thread already in the suspended state and records it, so that a wait succeeds on a break
     * that has already happened rather than hanging for one that will never come. Best-effort: any failure
     * falls through to the normal wait.
     *
     * @param registry the session registry
     * @param applicationId the application to scan
     */
    private static void scanForAlreadySuspended(DebugSessionBook registry, String applicationId)
    {
        try
        {
            if (registry.hasSnapshot(applicationId))
            {
                return;
            }
            IDebugTarget target = DebugSessionBook.findActiveTarget(applicationId);
            if (target == null || target.isTerminated())
            {
                return;
            }
            for (IThread thread : target.getThreads())
            {
                if (thread.isSuspended())
                {
                    registry.injectSuspend(applicationId, thread);
                    return;
                }
            }
        }
        catch (Exception ignore)
        {
            // best-effort; fall through to the normal wait
        }
    }

    /**
     * Builds the JSON snapshot response for a suspended thread: its id, name, the stack frames (each with a
     * stable reference), and the top frame reference. Shared with {@link DebugStepper}, which is why it is
     * package-private.
     *
     * @param snapshot the suspended-thread snapshot
     * @param registry the session registry
     * @param applicationId the application that suspended
     * @param autoResolved whether the application was auto-resolved rather than named by the caller
     * @return the JSON result document
     * @throws Exception when the debug model refuses to yield stack frames or frame details
     */
    static String buildSnapshotResponse(DebugSessionBook.SuspendSnapshot snapshot,
        DebugSessionBook registry, String applicationId, boolean autoResolved) throws Exception
    {
        IThread thread = snapshot.thread;
        List<Map<String, Object>> frames = new ArrayList<>();
        IStackFrame[] stackFrames = thread.getStackFrames();
        for (int i = 0; i < stackFrames.length; i++)
        {
            IStackFrame f = stackFrames[i];
            long frameRef = registry.registerFrame(f);
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("frameIndex", i); //$NON-NLS-1$
            dto.put("frameRef", frameRef); //$NON-NLS-1$
            dto.put("name", f.getName()); //$NON-NLS-1$
            try
            {
                dto.put("line", f.getLineNumber()); //$NON-NLS-1$
            }
            catch (Exception ignore)
            {
                // some frames throw on getLineNumber
            }
            frames.add(dto);
        }

        ToolResult result = ToolResult.success().put("hit", true) //$NON-NLS-1$
            .put("threadId", snapshot.threadId) //$NON-NLS-1$
            .put("threadName", thread.getName()) //$NON-NLS-1$
            .put("applicationId", applicationId) //$NON-NLS-1$
            .put("frames", frames); //$NON-NLS-1$
        if (autoResolved)
        {
            result.put("autoResolved", true); //$NON-NLS-1$
        }
        if (!frames.isEmpty())
        {
            result.put("topFrameRef", frames.get(0).get("frameRef")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return result.toJson();
    }
}
