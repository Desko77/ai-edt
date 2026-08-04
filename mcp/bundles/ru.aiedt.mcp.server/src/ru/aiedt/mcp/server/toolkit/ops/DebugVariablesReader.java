/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
 * Reads the variables of a suspended 1C stack frame, or one level inside a composite value. Resolves a
 * frame by the reference an earlier {@code wait_for_break} returned, by thread id plus frame index, or
 * by picking the lone active suspended session when the caller named neither.
 */
public final class DebugVariablesReader implements IMcpTool
{
    private static final String NAME = "get_variables"; //$NON-NLS-1$

    private static final String DESC = "Back-compat alias of `launch_debugger` `action=get_variables`; prefer the facade for new prompts. " //$NON-NLS-1$
        + "Reads the variables of a stack frame belonging to a suspended debug thread. " //$NON-NLS-1$
        + "Pass frameRef from wait_for_break (preferred), or threadId plus frameIndex. Use expandPath to drill " //$NON-NLS-1$
        + "into a nested structure (dot-separated)."; //$NON-NLS-1$

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
            .integerProperty("frameRef", "Frame handle returned by wait_for_break") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("threadId", "Thread id (an alternative to frameRef)") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("frameIndex", "0-based frame index, used together with threadId") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("expandPath", "Dot-separated path into a nested variable to expand") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("scope", "Which variables to list: locals (default) / module (module-level variables) / " //$NON-NLS-1$
                + "all (locals plus module variables plus module properties). Ignored when expandPath is set.") //$NON-NLS-1$
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
        long frameRef = JsonUtils.extractLongArgument(params, "frameRef", -1L); //$NON-NLS-1$
        long threadId = JsonUtils.extractLongArgument(params, "threadId", -1L); //$NON-NLS-1$
        int frameIndex = JsonUtils.extractIntArgument(params, "frameIndex", 0); //$NON-NLS-1$
        String expandPath = JsonUtils.extractStringArgument(params, "expandPath"); //$NON-NLS-1$

        DebugSessionBook registry = DebugSessionBook.get();

        try
        {
            IStackFrame frame;
            if (frameRef > 0)
            {
                frame = registry.getFrame(frameRef);
                if (frame == null)
                {
                    return ToolResult.error("frameRef is no longer valid - call wait_for_break again").toJson(); //$NON-NLS-1$
                }
            }
            else if (threadId > 0)
            {
                IThread thread = registry.getThread(threadId);
                if (thread == null)
                {
                    return ToolResult.error("threadId is no longer valid - call wait_for_break again").toJson(); //$NON-NLS-1$
                }
                IStackFrame[] frames = thread.getStackFrames();
                if (frameIndex < 0 || frameIndex >= frames.length)
                {
                    return ToolResult
                        .error("frameIndex is out of range (0.." + (frames.length - 1) + ")").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
                }
                frame = frames[frameIndex];
            }
            else
            {
                String appId = DebugSessionBook.findLoneActiveApplicationId();
                DebugSessionBook.SuspendSnapshot snap = appId != null ? registry.getSnapshot(appId) : null;
                if (snap == null)
                {
                    return ToolResult.error("Pass frameRef or threadId - there is no single suspended debug launch " //$NON-NLS-1$
                        + "to auto-resolve to. Call wait_for_break first.").toJson(); //$NON-NLS-1$
                }
                IStackFrame[] frames = snap.thread.getStackFrames();
                if (frames.length == 0)
                {
                    return ToolResult.error("the suspended thread has no stack frames").toJson(); //$NON-NLS-1$
                }
                frame = frames[Math.min(Math.max(frameIndex, 0), frames.length - 1)];
            }

            List<Map<String, Object>> vars;
            if (expandPath != null && !expandPath.isEmpty())
            {
                IVariable resolved = DebugValueSerializer.resolvePath(frame, expandPath);
                if (resolved == null)
                {
                    return ToolResult.error("expandPath did not resolve: " + expandPath).toJson(); //$NON-NLS-1$
                }
                vars = DebugValueSerializer.serializeChildren(resolved, registry);
            }
            else
            {
                String scope = JsonUtils.extractStringArgument(params, "scope"); //$NON-NLS-1$
                scope = (scope == null || scope.isEmpty()) ? "locals" : scope.toLowerCase(Locale.ROOT); //$NON-NLS-1$
                if (!"locals".equals(scope) && !"module".equals(scope) && !"all".equals(scope)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                {
                    return ToolResult.error(TextSuggest.invalidValue("scope", scope, //$NON-NLS-1$
                        Arrays.asList("locals", "module", "all"))).toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                vars = new ArrayList<>();
                if ("locals".equals(scope) || "all".equals(scope)) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    vars.addAll(DebugValueSerializer.serializeFrame(frame, registry));
                }
                if ("module".equals(scope) || "all".equals(scope)) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    vars.addAll(serializeModuleScope(frame, "getModuleVariables", registry)); //$NON-NLS-1$
                }
                if ("all".equals(scope)) //$NON-NLS-1$
                {
                    vars.addAll(serializeModuleScope(frame, "getModuleProperties", registry)); //$NON-NLS-1$
                }
            }

            return ToolResult.success().put("variables", vars).put("count", vars.size()).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            Activator.logError("get_variables tool raised an exception", e); //$NON-NLS-1$
            return ToolResult.error("Error: " + TextSuggest.safeMessage(e)).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Calls a no-arg {@code IVariable[]}-returning method on a BSL stack frame by reflection, and
     * serializes whatever it returns. Used for the module-variable and module-property scopes, which
     * only {@code IBslStackFrame} offers; any frame that does not answer the method contributes nothing.
     *
     * @param frame the frame to ask
     * @param method the getter name ({@code getModuleVariables} / {@code getModuleProperties})
     * @param registry passed through to the serializer
     * @return one DTO per variable the method returned; empty when the frame does not expose the method
     */
    private static List<Map<String, Object>> serializeModuleScope(IStackFrame frame, String method,
        DebugSessionBook registry)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        try
        {
            Method m = frame.getClass().getMethod(method);
            Object arr = m.invoke(frame);
            if (arr instanceof IVariable[])
            {
                for (IVariable v : (IVariable[])arr)
                {
                    out.add(DebugValueSerializer.serializeVariable(v, registry));
                }
            }
        }
        catch (NoSuchMethodException nsme)
        {
            // Not an IBslStackFrame - module scope is unavailable, contribute nothing.
        }
        catch (Exception e)
        {
            Activator.logWarning("get_variables " + method + " raised: " + TextSuggest.safeMessage(e)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return out;
    }
}
