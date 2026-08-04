/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.LinkedHashMap;
import java.util.Map;

import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * 1.42 (RSV 4.2 parity): unified debugger facade with 16 actions.
 *
 * <p>RSV 4.2 ships {@code launch_debugger} as a single MCP tool with a
 * dispatch on {@code action}. We had the same functionality split across
 * 11 standalone tools; this facade collapses them under one name so weak
 * LLMs can reach the debugger without a lookup table:
 *
 * <table>
 *   <tr><th>Action</th><th>Delegates to</th></tr>
 *   <tr><td>launch / debug_launch</td><td>{@link DebugSessionStarter}</td></tr>
 *   <tr><td>add_breakpoint / set_breakpoint</td><td>{@link BreakpointSetter}</td></tr>
 *   <tr><td>remove_breakpoint</td><td>{@link BreakpointRemover}</td></tr>
 *   <tr><td>list_breakpoints</td><td>{@link BreakpointsLister}</td></tr>
 *   <tr><td>get_state / debug_status</td><td>{@link DebugStateReader}</td></tr>
 *   <tr><td>get_variables</td><td>{@link DebugVariablesReader}</td></tr>
 *   <tr><td>step_over / step_into / step_out / step</td><td>{@link DebugStepper}</td></tr>
 *   <tr><td>resume</td><td>{@link DebugResumer}</td></tr>
 *   <tr><td>evaluate</td><td>{@link ExpressionEvaluator}</td></tr>
 *   <tr><td>wait_for_break</td><td>{@link SuspendWaiter}</td></tr>
 *   <tr><td>start_profiling</td><td>{@link ProfilingStarter}</td></tr>
 *   <tr><td>get_profiling_results</td><td>{@link ProfilingResultsReader}</td></tr>
 *   <tr><td>help</td><td>(this tool, topic-driven)</td></tr>
 * </table>
 *
 * <p>The 11 standalone tools stay registered for back-compat - the facade is
 * additive. Step variants (step_over / step_into / step_out) are recognised at
 * the facade level and forwarded to {@link DebugStepper} with the right
 * {@code mode} parameter so the agent does not need to know our internal
 * dispatch. Action tokens are normalized to snake_case, so camelCase variants
 * the agent may emit (addBreakpoint, waitForBreak) are accepted as well.
 */
public class LaunchDebuggerTool implements IMcpTool
{
    public static final String NAME = "launch_debugger"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Unified debugger control. Pass action=<name> plus action-specific " //$NON-NLS-1$
            + "parameters (snake_case canonical; camelCase like addBreakpoint is also " //$NON-NLS-1$
            + "accepted). Actions: launch (start an application in debug), " //$NON-NLS-1$
            + "add_breakpoint / remove_breakpoint / list_breakpoints, " //$NON-NLS-1$
            + "set_exception_breakpoint (suspend on a raised error), run_to_line " //$NON-NLS-1$
            + "(resume to a line, one-shot), wait_for_break (block until a breakpoint " //$NON-NLS-1$
            + "hits), get_state / debug_status, get_variables, set_variable, " //$NON-NLS-1$
            + "step_over / step_into / step_out / step, resume, terminate (stop a " //$NON-NLS-1$
            + "running launch by applicationId / all=true / lone active), evaluate " //$NON-NLS-1$
            + "(run a BSL expression in the current stack frame), start_profiling / " //$NON-NLS-1$
            + "get_profiling_results, help. The standalone tools (set_breakpoint, " //$NON-NLS-1$
            + "remove_breakpoint, list_breakpoints, set_exception_breakpoint, run_to_line, " //$NON-NLS-1$
            + "wait_for_break, get_variables, set_variable, step, resume, terminate_launch, " //$NON-NLS-1$
            + "evaluate_expression, debug_launch, debug_status, start_profiling, " //$NON-NLS-1$
            + "get_profiling_results) are back-compat aliases of the matching actions; prefer " //$NON-NLS-1$
            + "this facade for new prompts."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("action", //$NON-NLS-1$
                "Action: launch / add_breakpoint / remove_breakpoint / list_breakpoints / " //$NON-NLS-1$
                + "set_exception_breakpoint / run_to_line / wait_for_break / get_state / " //$NON-NLS-1$
                + "get_variables / set_variable / step_over / step_into / step_out / step / " //$NON-NLS-1$
                + "resume / terminate / evaluate / start_profiling / get_profiling_results / " //$NON-NLS-1$
                + "debug_status / help (snake_case canonical; camelCase like addBreakpoint " //$NON-NLS-1$
                + "is also accepted).", true) //$NON-NLS-1$
            .stringProperty("topic", //$NON-NLS-1$
                "Help topic when action=help: workflow (the typical debug sequence). " //$NON-NLS-1$
                + "Without topic - lists actions.") //$NON-NLS-1$
            .stringProperty("projectName", "EDT project name (most actions).") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", "Application identifier from list_applications.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("launchConfigurationName", //$NON-NLS-1$
                "launch: exact name of an EDT debug launch configuration (runtime client or Attach). " //$NON-NLS-1$
                    + "Use for Attach configurations (server-side debug: HTTP services, background jobs, " //$NON-NLS-1$
                    + "scheduled jobs) or to select a specific client configuration by name.") //$NON-NLS-1$
            .booleanProperty("updateBeforeLaunch", //$NON-NLS-1$
                "launch: update the database before launching (default true; ignored for Attach).") //$NON-NLS-1$
            .booleanProperty("all", //$NON-NLS-1$
                "terminate: stop every active EDT launch (default false).") //$NON-NLS-1$
            .stringProperty("modulePath", "BSL module path for breakpoint actions.") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("line", "Line number for breakpoint actions.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("breakpoints", //$NON-NLS-1$
                "add_breakpoint batch: a JSON array of breakpoint objects, each " //$NON-NLS-1$
                    + "{modulePath|module, line|lineNumber, ...optional condition / hitCount / " //$NON-NLS-1$
                    + "hitCondition / logExpression}. Sets many breakpoints in one add_breakpoint " //$NON-NLS-1$
                    + "call; projectName is inherited by items that omit it.") //$NON-NLS-1$
            .stringProperty("breakpointId", "Breakpoint identifier for remove_breakpoint.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("message", "set_exception_breakpoint: only break for exceptions whose text " //$NON-NLS-1$
                + "matches this message. Omit to break on any error.") //$NON-NLS-1$
            .booleanProperty("catchAll", "set_exception_breakpoint: break on any raised error. " //$NON-NLS-1$
                + "Default true; default false when message is set. Pass explicitly to override.") //$NON-NLS-1$
            .stringProperty("expression", "BSL expression for evaluate.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("frameRef", "Stack frame reference for get_variables / evaluate.") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("threadId", "Thread id from wait_for_break - alternative to frameRef for " //$NON-NLS-1$
                + "get_variables / set_variable, and the resume target for step / resume / run_to_line.") //$NON-NLS-1$
            .integerProperty("frameIndex", "0-based stack-frame index, used with threadId for " //$NON-NLS-1$
                + "get_variables / set_variable (default 0).") //$NON-NLS-1$
            .stringProperty("expandPath", "get_variables: dot-separated path into a nested variable to " //$NON-NLS-1$
                + "expand (e.g. Struct.Field).") //$NON-NLS-1$
            .stringProperty("scope", "get_variables: which variables to list - locals (default) / module " //$NON-NLS-1$
                + "(module-level variables) / all (locals plus module variables plus module properties). " //$NON-NLS-1$
                + "Ignored when expandPath is set.") //$NON-NLS-1$
            .stringProperty("mode", //$NON-NLS-1$
                "Step mode for the bare 'step' action: over / into / out. " //$NON-NLS-1$
                + "step_over / step_into / step_out also work as top-level actions and " //$NON-NLS-1$
                + "set this internally.") //$NON-NLS-1$
            .integerProperty("timeoutSeconds", //$NON-NLS-1$
                "wait_for_break / step timeout in seconds (min 1). Legacy aliases timeoutMs " //$NON-NLS-1$
                    + "(milliseconds) and timeout (seconds) are still accepted.") //$NON-NLS-1$
            .stringProperty("moduleFilter", "Module substring filter for get_profiling_results.") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("minFrequency", "Minimum hit count for get_profiling_results.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("module", "BSL module path for run_to_line (module-relative or absolute).") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("lineNumber", "Target line for run_to_line (1-based).") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("path", "Variable name (dot-separated for nested) for set_variable.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("value", "New value as a BSL expression for set_variable.") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String action = JsonUtils.extractStringArgument(params, "action"); //$NON-NLS-1$
        if (action == null || action.isBlank())
        {
            return ToolResult.error("action is required. Pass action=help for the catalog.") //$NON-NLS-1$
                .toJson();
        }
        action = JsonUtils.normalizeOperationToken(action);
        switch (action)
        {
            case "launch": //$NON-NLS-1$
            case "debug_launch": //$NON-NLS-1$
                return new DebugSessionStarter().execute(params);

            case "add_breakpoint": //$NON-NLS-1$
            case "set_breakpoint": //$NON-NLS-1$
                return new BreakpointSetter().execute(params);

            case "set_exception_breakpoint": //$NON-NLS-1$
                return new SetExceptionBreakpointTool().execute(params);

            case "run_to_line": //$NON-NLS-1$
                return new RunToLineTool().execute(params);

            case "remove_breakpoint": //$NON-NLS-1$
                return new BreakpointRemover().execute(params);

            case "list_breakpoints": //$NON-NLS-1$
                return new BreakpointsLister().execute(params);

            case "wait_for_break": //$NON-NLS-1$
                return new SuspendWaiter().execute(params);

            case "get_state": //$NON-NLS-1$
            case "debug_status": //$NON-NLS-1$
                return new DebugStateReader().execute(params);

            case "get_variables": //$NON-NLS-1$
                return new DebugVariablesReader().execute(params);

            case "set_variable": //$NON-NLS-1$
                return new DebugVariableWriter().execute(params);

            case "step_over": //$NON-NLS-1$
                return new DebugStepper().execute(withMode(params, "over")); //$NON-NLS-1$
            case "step_into": //$NON-NLS-1$
                return new DebugStepper().execute(withMode(params, "into")); //$NON-NLS-1$
            case "step_out": //$NON-NLS-1$
                return new DebugStepper().execute(withMode(params, "out")); //$NON-NLS-1$
            case "step": //$NON-NLS-1$
                return new DebugStepper().execute(params);

            case "resume": //$NON-NLS-1$
                return new DebugResumer().execute(params);

            case "terminate": //$NON-NLS-1$
            case "terminate_launch": //$NON-NLS-1$
                return new LaunchTerminator().execute(params);

            case "evaluate": //$NON-NLS-1$
                return new ExpressionEvaluator().execute(params);

            case "start_profiling": //$NON-NLS-1$
                return new ProfilingStarter().execute(params);

            case "get_profiling_results": //$NON-NLS-1$
                return new ProfilingResultsReader().execute(params);

            case "help": //$NON-NLS-1$
                return buildHelp(JsonUtils.extractStringArgument(params, "topic")); //$NON-NLS-1$

            default:
                return ToolResult.error(
                    "Unknown action '" + action + "'. Allowed: launch / add_breakpoint / " //$NON-NLS-1$ //$NON-NLS-2$
                        + "set_exception_breakpoint / run_to_line / remove_breakpoint / " //$NON-NLS-1$
                        + "list_breakpoints / wait_for_break / get_state / get_variables / " //$NON-NLS-1$
                        + "set_variable / step_over / step_into / step_out / resume / terminate / " //$NON-NLS-1$
                        + "evaluate / start_profiling / get_profiling_results / debug_status / help.") //$NON-NLS-1$
                    .toJson();
        }
    }

    /**
     * Returns a copy of {@code params} with {@code mode} set to the given
     * value. Used so the agent can call action=step_over without knowing about
     * DebugStepper's internal mode dispatch.
     */
    private static Map<String, String> withMode(Map<String, String> params, String mode)
    {
        Map<String, String> copy = new LinkedHashMap<>(params);
        copy.put("mode", mode); //$NON-NLS-1$
        return copy;
    }

    private static String buildHelp(String topic)
    {
        topic = JsonUtils.normalizeOperationToken(topic);
        StringBuilder sb = new StringBuilder();
        if (topic == null || topic.isEmpty())
        {
            sb.append("# launch_debugger - actions\n\n"); //$NON-NLS-1$
            sb.append("- **launch** - start an application in debug mode.\n"); //$NON-NLS-1$
            sb.append("- **add_breakpoint / remove_breakpoint / list_breakpoints**. " //$NON-NLS-1$
                + "add_breakpoint takes condition / hitCount+hitCondition / logExpression.\n"); //$NON-NLS-1$
            sb.append("- **set_exception_breakpoint** - suspend where an error is raised " //$NON-NLS-1$
                + "(projectName, optional message, catchAll).\n"); //$NON-NLS-1$
            sb.append("- **run_to_line** - resume a suspended session to module+lineNumber (one-shot).\n"); //$NON-NLS-1$
            sb.append("- **wait_for_break** - block until a breakpoint hits.\n"); //$NON-NLS-1$
            sb.append("- **get_state** - current debug state.\n"); //$NON-NLS-1$
            sb.append("- **get_variables** - variables in a stack frame (scope=locals/module/all).\n"); //$NON-NLS-1$
            sb.append("- **set_variable** - set a variable's value (frameRef, path, value).\n"); //$NON-NLS-1$
            sb.append("- **step_over / step_into / step_out** - step.\n"); //$NON-NLS-1$
            sb.append("- **resume** - continue execution.\n"); //$NON-NLS-1$
            sb.append("- **terminate** - stop a running launch (applicationId / all=true / " //$NON-NLS-1$
                + "lone active).\n"); //$NON-NLS-1$
            sb.append("- **evaluate** - run a BSL expression in the current frame.\n"); //$NON-NLS-1$
            sb.append("- **start_profiling / get_profiling_results**.\n"); //$NON-NLS-1$
            sb.append("- **help** - this catalog. topic=workflow lists the typical sequence.\n"); //$NON-NLS-1$
            sb.append("\nCompatibility aliases (back-compat, same behavior): debug_launch=launch, " //$NON-NLS-1$
                + "set_breakpoint=add_breakpoint, debug_status=get_state, step=step_over/into/out, " //$NON-NLS-1$
                + "terminate_launch=terminate, evaluate_expression=evaluate.\n"); //$NON-NLS-1$
            return sb.toString();
        }
        if ("workflow".equals(topic)) //$NON-NLS-1$
        {
            sb.append("# launch_debugger - typical workflow\n\n"); //$NON-NLS-1$
            sb.append("1. `list_applications` to get applicationId.\n"); //$NON-NLS-1$
            sb.append("2. `launch_debugger action=launch applicationId=...`.\n"); //$NON-NLS-1$
            sb.append("3. `launch_debugger action=add_breakpoint modulePath=... line=...`.\n"); //$NON-NLS-1$
            sb.append("4. User triggers a code path in 1C:Enterprise.\n"); //$NON-NLS-1$
            sb.append("5. `launch_debugger action=wait_for_break` (or get_state in a poll loop).\n"); //$NON-NLS-1$
            sb.append("6. `launch_debugger action=get_variables frameRef=...` " //$NON-NLS-1$
                + "→ `evaluate expression=...` to inspect.\n"); //$NON-NLS-1$
            sb.append("7. `launch_debugger action=step_over` / `step_into` / `resume`.\n"); //$NON-NLS-1$
            sb.append("8. `launch_debugger action=remove_breakpoint breakpointId=...` " //$NON-NLS-1$
                + "when done.\n"); //$NON-NLS-1$
            return sb.toString();
        }
        return "# Unknown topic '" + topic + "'.\n\nAvailable: workflow.\n"; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
