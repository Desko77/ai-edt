/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IThread;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BreakpointAccess;
import ru.aiedt.mcp.server.support.DebugSessionBook;
import ru.aiedt.mcp.server.support.TextSuggest;

/**
 * Runs a SUSPENDED debug session to the given BSL line: sets a one-shot
 * run-to-line breakpoint and resumes. The platform stops at the line and removes
 * the breakpoint. Cheaper than set_breakpoint + wait + remove for a one-off jump.
 *
 * <p>Requires an already-suspended session (you are at a breakpoint). Resolve it
 * via threadId (from wait_for_break) / applicationId, or the lone active launch.
 * After it returns, call wait_for_break to catch the stop at the line.
 */
public class RunToLineTool implements IMcpTool
{
    public static final String NAME = "run_to_line"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `launch_debugger` `action=run_to_line`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Run a suspended debug session to a BSL line: sets a one-shot run-to-line breakpoint " //$NON-NLS-1$
            + "and resumes. module = EDT module path or absolute path; lineNumber = 1-based. " //$NON-NLS-1$
            + "Needs an already-suspended session (threadId / applicationId / lone). " //$NON-NLS-1$
            + "Call wait_for_break afterwards to catch the stop."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project (required when module is a module-relative path)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("module", //$NON-NLS-1$
                "Module identifier - EDT module path (CommonModules/Foo/Module.bsl) or absolute path (required)", //$NON-NLS-1$
                true)
            .integerProperty("lineNumber", "1-based line number to run to (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("threadId", "Suspended thread id from wait_for_break (preferred)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", "Application id of the suspended session (alternative)") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    /**
     * Creates the one-shot breakpoint this tool runs to; a seam so the resume can be driven without
     * EDT's own debug bundle, which is what makes the breakpoint real in a live session.
     */
    interface MarkerCreator
    {
        /**
         * @param file the module file
         * @param lineNumber the line to run to
         * @return the registered breakpoint
         * @throws Exception when the breakpoint cannot be created
         */
        IBreakpoint create(IFile file, int lineNumber) throws Exception;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        return execute(params, BreakpointAccess::createRunToLineBreakpoint);
    }

    /**
     * Runs the session to a line, creating the breakpoint through the given creator.
     *
     * @param params the call arguments
     * @param creator how the one-shot breakpoint is made
     * @return the JSON result document
     */
    static String execute(Map<String, String> params, MarkerCreator creator)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String module = JsonUtils.extractStringArgument(params, "module"); //$NON-NLS-1$
        int lineNumber = JsonUtils.extractIntArgument(params, "lineNumber", -1); //$NON-NLS-1$
        if (module == null || module.isEmpty())
        {
            return ToolResult.error(TextSuggest.missingParam("module", //$NON-NLS-1$
                "run_to_line projectName=X module=CommonModules/Foo/Module.bsl lineNumber=42")).toJson(); //$NON-NLS-1$
        }
        if (lineNumber < 1)
        {
            return ToolResult.error("lineNumber counts from 1, so it cannot be lower").toJson(); //$NON-NLS-1$
        }
        boolean modulePathStyle = !BreakpointAccess.looksLikeAbsolutePath(module);
        if (modulePathStyle && (projectName == null || projectName.isEmpty()))
        {
            return ToolResult.error(
                "projectName must be provided when module is expressed as an EDT module path").toJson(); //$NON-NLS-1$
        }

        IFile file = BreakpointAccess.resolveModuleFile(projectName, module);
        if (file == null || !file.exists())
        {
            return ToolResult.error("No module file at: " + module).toJson(); //$NON-NLS-1$
        }

        return execute(params, file, creator);
    }

    /**
     * The same run over a module file the caller already holds, so the session side of the call can be
     * driven without a workspace.
     *
     * @param params the call arguments
     * @param file the module file the run starts from
     * @param creator how the one-shot breakpoint is made
     * @return the JSON result document
     */
    static String execute(Map<String, String> params, IFile file, MarkerCreator creator)
    {
        String module = JsonUtils.extractStringArgument(params, "module"); //$NON-NLS-1$
        int lineNumber = JsonUtils.extractIntArgument(params, "lineNumber", -1); //$NON-NLS-1$
        long threadId = JsonUtils.extractLongArgument(params, "threadId", -1L); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        DebugSessionBook registry = DebugSessionBook.get();
        registry.ensureListenerRegistered();

        long markerId = -1L;
        try
        {
            // Resolve what to resume BEFORE creating the breakpoint, so a bad
            // session reference does not leave a dangling run-to-line marker.
            IThread thread;
            String appId;
            if (threadId > 0)
            {
                thread = registry.getThread(threadId);
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
                    return ToolResult.error("thread cannot resume (not suspended). run_to_line needs " //$NON-NLS-1$
                        + "a suspended session.").toJson(); //$NON-NLS-1$
                }
                appId = DebugSessionBook.findApplicationIdFor(thread);
            }
            else
            {
                appId = (applicationId != null && !applicationId.isEmpty())
                    ? applicationId : DebugSessionBook.findLoneActiveApplicationId();
                if (appId == null)
                {
                    return ToolResult.error("Provide threadId or applicationId - no single active debug " //$NON-NLS-1$
                        + "launch for auto-resolution. The session must be suspended.").toJson(); //$NON-NLS-1$
                }
                // A debug target of this platform does not resume; its threads do. A session named
                // without a thread id is therefore resolved to the stopped thread it is running to.
                thread = suspendedThread(registry, appId);
                if (thread == null)
                {
                    return ToolResult.error("no suspended thread to run from for applicationId: " + appId //$NON-NLS-1$
                        + ". run_to_line needs a suspended session - call wait_for_break first.").toJson(); //$NON-NLS-1$
                }
            }

            IBreakpoint bp = creator.create(file, lineNumber);
            markerId = bp != null && bp.getMarker() != null ? bp.getMarker().getId() : -1L;

            DebugSessionBook.SuspendSnapshot leftBehind = registry.getSnapshot(appId);
            thread.resume();
            // Drop the stop this call leaves behind, and only that one: a thread that reaches its
            // line while the resume is being made has already recorded a newer stop, which is what
            // the wait the answer invites is for.
            registry.clearSnapshotIfCurrent(appId, leftBehind);

            Activator.logInfo("run_to_line: " + file.getFullPath() + ":" + lineNumber //$NON-NLS-1$ //$NON-NLS-2$
                + " (resumed thread)"); //$NON-NLS-1$
            return ToolResult.success()
                .put("breakpointId", markerId) //$NON-NLS-1$
                .put("resumed", true) //$NON-NLS-1$
                .put("scope", "thread") //$NON-NLS-1$ //$NON-NLS-2$
                .put("module", module) //$NON-NLS-1$
                .put("lineNumber", lineNumber) //$NON-NLS-1$
                .put("note", "Resumed to line " + lineNumber //$NON-NLS-1$
                    + ". Call wait_for_break to catch the stop. The run-to-line breakpoint is one-shot " //$NON-NLS-1$
                    + "(removed when the line is hit); if wait_for_break stops elsewhere or the line is " //$NON-NLS-1$
                    + "never reached, remove it with remove_breakpoint breakpointId=" + markerId + ".") //$NON-NLS-1$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error in run_to_line", e); //$NON-NLS-1$
            ToolResult failure = ToolResult.error("Error: " + TextSuggest.safeMessage(e)); //$NON-NLS-1$
            if (markerId >= 0)
            {
                failure.put("breakpointId", markerId) //$NON-NLS-1$
                    .put("resumed", false) //$NON-NLS-1$
                    .put("module", module) //$NON-NLS-1$
                    .put("lineNumber", lineNumber) //$NON-NLS-1$
                    .put("note", "The run-to-line breakpoint was created and the session was not " //$NON-NLS-1$
                        + "resumed, so it will not fire on its own. Remove it with remove_breakpoint " //$NON-NLS-1$
                        + "breakpointId=" + markerId + "."); //$NON-NLS-1$
            }
            return failure.toJson();
        }
    }

    /**
     * The thread a call without an explicit thread id runs to the line.
     * <p>
     * The application's suspend snapshot names the thread that stopped first; the live debug target of
     * the application, when there is one, contributes any other thread that is stopped and resumable.
     * The target is never resumed itself - this platform's targets are not resumable, only their
     * threads are.
     * </p>
     *
     * @param registry the session registry
     * @param applicationId the application the call addressed; must not be <code>null</code>
     * @return the suspended thread to resume, or <code>null</code> when the session has none
     * @throws DebugException when the debug model refuses to report the state of the target or a thread
     */
    static IThread suspendedThread(DebugSessionBook registry, String applicationId)
        throws DebugException
    {
        DebugSessionBook.SuspendSnapshot snapshot = registry.getSnapshot(applicationId);
        if (snapshot != null && snapshot.thread != null && snapshot.thread.canResume())
        {
            return snapshot.thread;
        }
        IDebugTarget target = DebugSessionBook.findActiveTarget(applicationId);
        if (target == null || target.isTerminated())
        {
            return null;
        }
        for (IThread thread : target.getThreads())
        {
            if (thread.isSuspended() && thread.canResume())
            {
                return thread;
            }
        }
        return null;
    }
}
