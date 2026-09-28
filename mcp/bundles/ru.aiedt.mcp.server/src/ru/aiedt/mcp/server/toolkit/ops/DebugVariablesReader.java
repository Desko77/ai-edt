/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.debug.core.model.IStackFrame;
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
            DebugFrameResolution.Resolution resolved =
                DebugFrameResolution.resolve(registry, frameRef, threadId, frameIndex);
            if (resolved.frame == null)
            {
                return ToolResult.error(resolved.refusal).toJson();
            }
            IStackFrame frame = resolved.frame;

            List<Map<String, Object>> vars;
            int withoutValue = 0;
            Map<String, String> scopeFailures = new LinkedHashMap<>();
            if (expandPath != null && !expandPath.isEmpty())
            {
                IVariable resolvedVariable = DebugValueSerializer.resolvePath(frame, expandPath);
                if (resolvedVariable == null)
                {
                    // Not every name the listing prints is in the variables API: module properties
                    // come from it with no value at all, and evaluating the name is how the
                    // environment itself answers for them. Measured 17.09 on a live suspension.
                    ExpressionEvaluator.Evaluation evaluated = evaluateByName(frame, expandPath);
                    if (evaluated.value != null)
                    {
                        return withFrameAddress(ToolResult.success()
                            .put("expandPath", expandPath) //$NON-NLS-1$
                            .put("resolvedBy", "evaluate") //$NON-NLS-1$ //$NON-NLS-2$
                            .put("variable", evaluated.value), resolved).toJson(); //$NON-NLS-1$
                    }
                    return ToolResult.error("expandPath did not resolve: " + expandPath //$NON-NLS-1$
                        + ". The name is not among the frame's variables, and evaluating it answered " //$NON-NLS-1$
                        + "nothing: " + evaluated.refusal).toJson(); //$NON-NLS-1$
                }
                vars = DebugValueSerializer.serializeChildren(resolvedVariable, registry);
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
                    collectScope(readModuleScope(frame, "getModuleVariables", registry), "module", //$NON-NLS-1$ //$NON-NLS-2$
                        vars, scopeFailures);
                }
                if ("all".equals(scope)) //$NON-NLS-1$
                {
                    collectScope(readModuleScope(frame, "getModuleProperties", registry), //$NON-NLS-1$
                        "moduleProperties", vars, scopeFailures); //$NON-NLS-1$
                }
                for (Map<String, Object> record : vars)
                {
                    if (DebugValueSerializer.carriesNoValue(record))
                    {
                        // Said rather than hidden: the value exists, this API just does not carry
                        // it, and dropping the name would hide a variable that can be read.
                        record.put("valueNotReturnedByVariables", Boolean.TRUE); //$NON-NLS-1$
                        withoutValue++;
                    }
                }
            }

            ToolResult answer = withFrameAddress(ToolResult.success()
                .put("variables", vars) //$NON-NLS-1$
                .put("count", Integer.valueOf(vars.size())), resolved); //$NON-NLS-1$
            if (!scopeFailures.isEmpty())
            {
                // An empty list and a refused read are different answers: without this the caller
                // reads a scope that threw as a scope that holds nothing.
                answer.put("scopeFailures", scopeFailures) //$NON-NLS-1$
                    .put("scopeFailureNote", "These scopes were not read: " //$NON-NLS-1$ //$NON-NLS-2$
                        + String.join(", ", scopeFailures.keySet()) //$NON-NLS-1$
                        + ". The list is incomplete. Read one name at a time with expandPath=<name> " //$NON-NLS-1$
                        + "or evaluate expression=<name>."); //$NON-NLS-1$
            }
            if (withoutValue > 0)
            {
                answer.put("valuesNotReturnedByVariables", Integer.valueOf(withoutValue)) //$NON-NLS-1$
                    .put("valuesNote", "The variables API returned no value for " + withoutValue //$NON-NLS-1$ //$NON-NLS-2$
                        + " of these names. Their values are readable one at a time: expandPath=<name> " //$NON-NLS-1$
                        + "or evaluate expression=<name>."); //$NON-NLS-1$
            }
            return answer.toJson();
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
     * @return what the getter answered, and why it answered nothing when it threw
     */
    private static ScopeRead readModuleScope(IStackFrame frame, String method, DebugSessionBook registry)
    {
        ScopeRead read = new ScopeRead();
        try
        {
            Method m = frame.getClass().getMethod(method);
            Object arr = m.invoke(frame);
            if (arr instanceof IVariable[])
            {
                for (IVariable v : (IVariable[])arr)
                {
                    read.variables.add(DebugValueSerializer.serializeVariable(v, registry));
                }
            }
        }
        catch (NoSuchMethodException nsme)
        {
            // Not an IBslStackFrame - module scope is unavailable, contribute nothing.
        }
        catch (Exception e)
        {
            read.failure = TextSuggest.safeMessage(e);
            Activator.logWarning("get_variables " + method + " raised: " + read.failure); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return read;
    }

    /**
     * Adds a scope's variables to the answer, and the reason to its failure list when the read threw.
     *
     * @param read what the scope getter answered
     * @param scope the scope name the caller used, as the failure is reported under it
     * @param vars the collected variables, added to
     * @param failures the scope name to reason, added to
     */
    private static void collectScope(ScopeRead read, String scope, List<Map<String, Object>> vars,
        Map<String, String> failures)
    {
        vars.addAll(read.variables);
        if (read.failure != null)
        {
            failures.put(scope, read.failure);
        }
    }

    /**
     * Reads a name by evaluating it in the frame, for names the variables API does not carry.
     *
     * @param frame the suspended frame.
     * @param name the name the caller asked to expand.
     * @return its type and value, or the reason evaluation answered nothing
     */
    private static ExpressionEvaluator.Evaluation evaluateByName(IStackFrame frame, String name)
    {
        try
        {
            return ExpressionEvaluator.evaluateValue(frame, name);
        }
        catch (Exception e)
        {
            String reason = TextSuggest.safeMessage(e);
            Activator.logDebug("evaluating " + name + " answered nothing: " + reason); //$NON-NLS-1$ //$NON-NLS-2$
            return ExpressionEvaluator.Evaluation.refused(reason);
        }
    }

    /**
     * Names the frame the answer came from.
     * <p>
     * The caller may have named a thread and an index, and an index that landed on somebody else's
     * frame has to be visible in the answer - otherwise the values read out of the wrong frame look
     * exactly like the values it asked for.
     * </p>
     *
     * @param answer the answer being built
     * @param resolved the frame it was read from
     * @return the same answer, with the address of that frame
     */
    private static ToolResult withFrameAddress(ToolResult answer, DebugFrameResolution.Resolution resolved)
    {
        if (resolved.frameRef > 0)
        {
            answer.put("frameRef", resolved.frameRef); //$NON-NLS-1$
        }
        if (resolved.threadId > 0)
        {
            answer.put("threadId", resolved.threadId) //$NON-NLS-1$
                .put("frameIndex", resolved.index); //$NON-NLS-1$
        }
        try
        {
            String name = resolved.frame.getName();
            if (name != null && !name.isEmpty())
            {
                answer.put("frameName", name); //$NON-NLS-1$
            }
        }
        catch (Exception e)
        {
            // A frame that will not name itself is still a frame whose variables were read. Numbers
            // were reported already; the name is what goes missing.
            Activator.logDebug("the frame would not name itself: " + TextSuggest.safeMessage(e)); //$NON-NLS-1$
        }
        return answer;
    }

    /** What one module-scope getter answered: the variables it returned, and why it returned none. */
    private static final class ScopeRead
    {
        /** One DTO per variable the getter returned. */
        final List<Map<String, Object>> variables = new ArrayList<>();

        /** Why the getter threw, or <code>null</code> when it answered. */
        String failure;
    }
}
