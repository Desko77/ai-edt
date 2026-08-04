/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Map;

import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IVariable;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.DebugSessionBook;
import ru.aiedt.mcp.server.support.TextSuggest;
import ru.aiedt.mcp.server.support.DebugValueSerializer;

/**
 * Sets the value of a variable in a suspended stack frame. Resolve the frame the
 * same way as {@code get_variables} (frameRef from wait_for_break, or
 * threadId+frameIndex, or the lone suspended launch), point {@code path} at the
 * variable (dot-separated to reach a nested one), and pass the new {@code value}
 * as a BSL expression (e.g. {@code 42}, {@code "text"}, {@code True}).
 *
 * <p>Uses the standard Eclipse {@code IVariable.setValue(String)} contract, so it
 * only works on variables that report {@code supportsValueModification()}.
 */
public class DebugVariableWriter implements IMcpTool
{
    public static final String NAME = "set_variable"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `launch_debugger` `action=set_variable`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Set a variable's value in a suspended debug frame. Resolve the frame via frameRef " //$NON-NLS-1$
            + "(from wait_for_break) or threadId+frameIndex; path = variable name (dot-separated for " //$NON-NLS-1$
            + "a nested one); value = a BSL expression (42, \"text\", True). Only works where the " //$NON-NLS-1$
            + "variable supports modification."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .integerProperty("frameRef", "Stable frame reference from wait_for_break") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("threadId", "Suspended thread to work on; use instead of frameRef") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("frameIndex", "Stack frame to write into, counted from zero, when threadId is given") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("path", //$NON-NLS-1$
                "Variable name, dot-separated to reach a nested variable (required)", true) //$NON-NLS-1$
            .stringProperty("value", //$NON-NLS-1$
                "New value as a BSL expression, e.g. 42 / \"text\" / True (required)", true) //$NON-NLS-1$
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
        String path = JsonUtils.extractStringArgument(params, "path"); //$NON-NLS-1$
        if (path == null || path.isEmpty())
        {
            return ToolResult.error(TextSuggest.missingParam("path", //$NON-NLS-1$
                "set_variable frameRef=<n> path=Counter value=42")).toJson(); //$NON-NLS-1$
        }
        String value = JsonUtils.extractStringArgument(params, "value"); //$NON-NLS-1$
        if (value == null)
        {
            return ToolResult.error(TextSuggest.missingParam("value", //$NON-NLS-1$
                "set_variable frameRef=<n> path=Counter value=42")).toJson(); //$NON-NLS-1$
        }

        long frameRef = JsonUtils.extractLongArgument(params, "frameRef", -1L); //$NON-NLS-1$
        long threadId = JsonUtils.extractLongArgument(params, "threadId", -1L); //$NON-NLS-1$
        int frameIndex = JsonUtils.extractIntArgument(params, "frameIndex", 0); //$NON-NLS-1$
        DebugSessionBook registry = DebugSessionBook.get();

        try
        {
            IStackFrame frame = resolveFrame(registry, frameRef, threadId, frameIndex);
            if (frame == null)
            {
                return ToolResult.error("Provide frameRef or threadId - no single suspended debug " //$NON-NLS-1$
                    + "launch is available to auto-resolve. Call wait_for_break first.").toJson(); //$NON-NLS-1$
            }
            IVariable var = DebugValueSerializer.resolvePath(frame, path);
            if (var == null)
            {
                return ToolResult.error("Variable not found at path: " + path //$NON-NLS-1$
                    + ". Use get_variables to see available names.").toJson(); //$NON-NLS-1$
            }
            if (!var.supportsValueModification())
            {
                return ToolResult.error("Variable '" + path //$NON-NLS-1$
                    + "' does not support modification (read-only in the debugger).").toJson(); //$NON-NLS-1$
            }
            if (!var.verifyValue(value))
            {
                return ToolResult.error("Value '" + value + "' is not valid for variable '" + path //$NON-NLS-1$ //$NON-NLS-2$
                    + "'. Pass a BSL expression of a compatible type.").toJson(); //$NON-NLS-1$
            }
            var.setValue(value);
            // Re-read the variable so the response reflects the debugger's ACTUAL
            // post-set state - the Eclipse contract lets setValue silently no-op even
            // after supportsValueModification()/verifyValue() pass, so "applied" only
            // means setValue returned without error; "variable" is the confirmation.
            Map<String, Object> updated = DebugValueSerializer.serializeVariable(var, registry);
            Activator.logInfo("set_variable: " + path + " = " + value); //$NON-NLS-1$ //$NON-NLS-2$
            return ToolResult.success()
                .put("path", path) //$NON-NLS-1$
                .put("applied", true) //$NON-NLS-1$
                .put("variable", updated) //$NON-NLS-1$
                .put("note", "applied = setValue returned without error; 'variable' is re-read from " //$NON-NLS-1$
                    + "the debugger - compare its value to confirm the change took effect.") //$NON-NLS-1$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error: the variable could not be written", e); //$NON-NLS-1$
            return ToolResult.error("Error: " + TextSuggest.safeMessage(e)).toJson(); //$NON-NLS-1$
        }
    }

    /** Resolves a stack frame by frameRef, then threadId+frameIndex, then the lone suspended launch. */
    private static IStackFrame resolveFrame(DebugSessionBook registry, long frameRef,
        long threadId, int frameIndex) throws Exception
    {
        if (frameRef > 0)
        {
            return registry.getFrame(frameRef);
        }
        if (threadId > 0)
        {
            IThread thread = registry.getThread(threadId);
            if (thread == null)
            {
                return null;
            }
            IStackFrame[] frames = thread.getStackFrames();
            if (frameIndex < 0 || frameIndex >= frames.length)
            {
                return null;
            }
            return frames[frameIndex];
        }
        String appId = DebugSessionBook.findLoneActiveApplicationId();
        DebugSessionBook.SuspendSnapshot snap = appId != null ? registry.getSnapshot(appId) : null;
        if (snap == null)
        {
            return null;
        }
        IStackFrame[] frames = snap.thread.getStackFrames();
        if (frames.length == 0)
        {
            return null;
        }
        return frames[Math.min(Math.max(frameIndex, 0), frames.length - 1)];
    }
}
