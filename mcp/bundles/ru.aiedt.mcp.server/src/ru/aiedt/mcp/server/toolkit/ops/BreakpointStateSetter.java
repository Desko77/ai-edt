/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Map;

import org.eclipse.core.resources.IMarker;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.ILineBreakpoint;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BreakpointAccess;

/**
 * Turns a breakpoint on or off without removing it.
 *
 * <p>A breakpoint that is in the way but still wanted - a line that stops on every pass while the
 * caller works somewhere else - used to have two answers: remove it and lose the line and its
 * options, or leave it and stop at it. This is the third: the breakpoint keeps its place, its line,
 * its condition and its hit count, and the debugger simply stops honoring it until it is enabled
 * again.</p>
 *
 * <p>The state is asked for by name: {@code breakpointEnabled} is required, and a call that leaves
 * it out is refused rather than defaulted, because both defaults are wrong for somebody - enabling a
 * breakpoint the caller meant to switch off stops a client that was running fine.</p>
 */
public final class BreakpointStateSetter implements IMcpTool
{
    public static final String NAME = "set_breakpoint_state"; //$NON-NLS-1$

    /** The state to set: true enables the breakpoint, false switches it off. */
    static final String KEY_BREAKPOINT_ENABLED = "breakpointEnabled"; //$NON-NLS-1$

    private static final String KEY_BREAKPOINT_ID = "breakpointId"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `launch_debugger` `action=set_breakpoint_state`; prefer the facade " //$NON-NLS-1$
            + "for new prompts. Enables or disables an existing breakpoint without removing it: the " //$NON-NLS-1$
            + "breakpoint keeps its line, its condition and its hit count, and the debugger stops " //$NON-NLS-1$
            + "honoring it until it is enabled again. breakpointEnabled is required - true enables, " //$NON-NLS-1$
            + "false switches off. breakpointId comes from list_breakpoints or from the arming call; " //$NON-NLS-1$
            + "the answer says whether the state changed."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty(KEY_BREAKPOINT_ID,
                "Breakpoint to switch, as list_breakpoints or the arming call reported it.")
            .booleanProperty(KEY_BREAKPOINT_ENABLED,
                "Required. true enables the breakpoint, false switches it off without removing it.")
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
        Boolean enabled = JsonUtils.extractBooleanArgumentNullable(params, KEY_BREAKPOINT_ENABLED);
        if (enabled == null)
        {
            return ToolResult.error("breakpointEnabled is required: pass true to enable the breakpoint " //$NON-NLS-1$
                + "or false to switch it off without removing it.")
                .put("outcome", "error") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }

        String rawId = JsonUtils.extractStringArgument(params, KEY_BREAKPOINT_ID);
        long breakpointId = parseId(rawId);
        if (breakpointId < 0)
        {
            return ToolResult.error(rawId == null || rawId.isBlank()
                ? "breakpointId must be provided: call list_breakpoints to see the breakpoints and their ids." //$NON-NLS-1$
                : "breakpointId must be the numeric id a breakpoint was reported with, not '" + rawId + "'.") //$NON-NLS-1$ //$NON-NLS-2$
                .put("outcome", "error") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }

        try
        {
            return setState(BreakpointAccess::findBreakpointById, breakpointId,
                enabled.booleanValue()).toJson();
        }
        catch (Exception e)
        {
            Activator.logError("set_breakpoint_state failed", e); //$NON-NLS-1$
            return ToolResult.error("Failed to change the breakpoint state: " + e.getMessage()) //$NON-NLS-1$
                .put("outcome", "error") //$NON-NLS-1$ //$NON-NLS-2$
                .put(KEY_BREAKPOINT_ID, breakpointId)
                .toJson();
        }
    }

    /**
     * Finds the breakpoint a caller addressed.
     * <p>
     * The live one reads the debug breakpoint manager; a test hands in the breakpoints it built, which
     * is the only way to exercise the decision without a debug session behind it.
     * </p>
     */
    interface BreakpointLookup
    {
        /**
         * @param breakpointId the marker id the caller named
         * @return the breakpoint, or <code>null</code> when no registered breakpoint carries that id
         * @throws Exception when the debug model refuses to be read
         */
        IBreakpoint find(long breakpointId) throws Exception;
    }

    /**
     * Applies the state to the breakpoint the address resolves to, and reports what happened.
     *
     * @param lookup how to find the breakpoint by id
     * @param breakpointId the marker id the caller named
     * @param enabled the state to set
     * @return the answer: outcome enabled / disabled, the id, whether anything changed, and where the
     *         breakpoint sits
     * @throws Exception when the lookup or the write fails
     */
    static ToolResult setState(BreakpointLookup lookup, long breakpointId, boolean enabled)
        throws Exception
    {
        IBreakpoint breakpoint = lookup.find(breakpointId);
        if (breakpoint == null)
        {
            return ToolResult.error("No breakpoint with id " + breakpointId //$NON-NLS-1$
                + " is registered: it may have been removed, or the id may belong to another workspace. " //$NON-NLS-1$
                + "Call list_breakpoints for the current ids.")
                .put("outcome", "error") //$NON-NLS-1$ //$NON-NLS-2$
                .put(KEY_BREAKPOINT_ID, breakpointId)
                .hint("launch_debugger", Map.of("action", "list_breakpoints")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        boolean changed = BreakpointAccess.setBreakpointEnabled(breakpoint, enabled);
        ToolResult result = ToolResult.success()
            .put("outcome", enabled ? "enabled" : "disabled") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .put(KEY_BREAKPOINT_ID, breakpointId)
            .put("enabled", enabled) //$NON-NLS-1$
            .put("changed", changed); //$NON-NLS-1$
        if (!changed)
        {
            result.put("note", "The breakpoint was already " + (enabled ? "enabled" : "disabled") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ", so nothing was written."); //$NON-NLS-1$
        }
        return describePlace(result, breakpoint);
    }

    /**
     * Adds where the breakpoint sits, so the answer names the line the caller is switching rather than
     * an id alone. Reading the marker or the line is best-effort: a breakpoint kind that answers
     * neither is still switched, and the answer keeps the id.
     *
     * @param result the answer being built
     * @param breakpoint the breakpoint that was changed
     * @return the same answer, with the location added where it could be read
     */
    private static ToolResult describePlace(ToolResult result, IBreakpoint breakpoint)
    {
        try
        {
            IMarker marker = breakpoint.getMarker();
            if (marker != null && marker.getResource() != null)
            {
                result.put("file", marker.getResource().getFullPath().toString()); //$NON-NLS-1$
            }
        }
        catch (Exception ignore)
        {
            // the id is the address; the location is an aside
        }
        if (breakpoint instanceof ILineBreakpoint)
        {
            try
            {
                result.put("lineNumber", ((ILineBreakpoint)breakpoint).getLineNumber()); //$NON-NLS-1$
            }
            catch (Exception ignore)
            {
                // some breakpoints throw on getLineNumber
            }
        }
        return result;
    }

    /**
     * @param rawId the id as the caller wrote it
     * @return the id, or -1 when it is absent or not a number
     */
    private static long parseId(String rawId)
    {
        if (rawId == null || rawId.isBlank())
        {
            return -1L;
        }
        try
        {
            return Long.parseLong(rawId.trim());
        }
        catch (NumberFormatException e)
        {
            return -1L;
        }
    }
}
