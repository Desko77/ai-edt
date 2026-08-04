/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IVariable;

/**
 * Flattens Eclipse debug variables into maps an MCP client can read.
 * <p>
 * A suspended 1C frame holds live debug-model objects; an agent on the other end of an HTTP call holds
 * JSON. This turns one into the other, one variable at a time, as {@code name} / {@code type} /
 * {@code value} / {@code hasChildren}.
 * </p>
 * <p>
 * Composite values - a Структура, a Соответствие, an object reference - are not expanded in place. A
 * deep 1C value can be enormous, and serializing it eagerly would blow the response up for the
 * majority of calls that never look inside. Instead such a variable is marked
 * {@code hasChildren: true} and carries an {@code expandHint}, its own name: the client sends that
 * back as {@code expandPath} - joining names with a dot to go deeper - and gets exactly the level it
 * asked for. Nothing is cached between calls and no handle is minted; the path is resolved afresh
 * against the frame each time (see {@link #resolvePath(IStackFrame, String)}).
 * </p>
 * <p>
 * A single unreadable variable never sinks the whole list: a value that cannot be read, typed or
 * counted degrades into a DTO that says so.
 * </p>
 */
public final class DebugValueSerializer
{
    /**
     * How much of a value string is sent, in characters.
     * <p>
     * A 1C value can stringify to megabytes. Anything longer than this is cut, and the DTO then says
     * {@code truncated} and how long the value really was. Shared API: the expression evaluator caps
     * its own result the same way, so that one value does not arrive whole down one route and clipped
     * down another.
     * </p>
     */
    public static final int MAX_VALUE_LENGTH = 500;

    private static final String KEY_NAME = "name"; //$NON-NLS-1$
    private static final String KEY_TYPE = "type"; //$NON-NLS-1$
    private static final String KEY_VALUE = "value"; //$NON-NLS-1$
    private static final String KEY_HAS_CHILDREN = "hasChildren"; //$NON-NLS-1$
    private static final String KEY_EXPAND_HINT = "expandHint"; //$NON-NLS-1$
    private static final String KEY_TRUNCATED = "truncated"; //$NON-NLS-1$
    private static final String KEY_FULL_LENGTH = "fullLength"; //$NON-NLS-1$

    private static final String TYPE_UNKNOWN = "<unknown>"; //$NON-NLS-1$
    private static final String TYPE_UNDEFINED = "Undefined"; //$NON-NLS-1$

    private DebugValueSerializer()
    {
        // utility
    }

    /**
     * Serializes every variable visible in a stack frame.
     *
     * @param frame the frame to read; <code>null</code> yields an empty list
     * @param registry not used - kept because the call sites pass it. This serializer mints no ids, so
     *            it has no registry to mint them in
     * @return one DTO per variable, in the order the debug model reports them; a fresh mutable list
     *         the caller may add to
     * @throws Exception if the debug model refuses to hand over the frame's variables
     */
    public static List<Map<String, Object>> serializeFrame(IStackFrame frame, DebugSessionBook registry)
        throws Exception
    {
        List<Map<String, Object>> variables = new ArrayList<>();
        if (frame == null || !frame.hasVariables())
        {
            return variables;
        }

        for (IVariable variable : frame.getVariables())
        {
            variables.add(serializeVariable(variable, registry));
        }
        return variables;
    }

    /**
     * Serializes one level inside a composite variable.
     *
     * @param parent the variable to look inside; <code>null</code>, valueless or childless yields an
     *            empty list
     * @param registry not used; see {@link #serializeFrame(IStackFrame, DebugSessionBook)}
     * @return one DTO per child, in debug-model order; a fresh mutable list
     * @throws Exception if the debug model refuses to hand over the value or its children
     */
    public static List<Map<String, Object>> serializeChildren(IVariable parent, DebugSessionBook registry)
        throws Exception
    {
        List<Map<String, Object>> children = new ArrayList<>();
        if (parent == null)
        {
            return children;
        }

        IValue value = parent.getValue();
        if (value == null || !value.hasVariables())
        {
            return children;
        }

        for (IVariable child : value.getVariables())
        {
            children.add(serializeVariable(child, registry));
        }
        return children;
    }

    /**
     * Turns one variable into the DTO the client sees.
     * <p>
     * Key order is the order a client reads them in, so the map preserves insertion: name, type, value
     * (followed by {@code truncated} and {@code fullLength} when the value was cut), then
     * {@code hasChildren}, then {@code expandHint} when there is something to expand.
     * </p>
     * <p>
     * A value that cannot be read at all still produces a DTO - typed {@code <unknown>}, with the
     * failure in the value slot - because one broken variable in a frame should not cost the agent the
     * other twenty.
     * </p>
     *
     * @param var the variable; must not be <code>null</code>
     * @param registry not used; see {@link #serializeFrame(IStackFrame, DebugSessionBook)}
     * @return the DTO
     * @throws Exception if even the variable's name cannot be read, which leaves nothing to report
     */
    public static Map<String, Object> serializeVariable(IVariable var, DebugSessionBook registry) throws Exception
    {
        Map<String, Object> dto = new LinkedHashMap<>();

        String name = var.getName();
        dto.put(KEY_NAME, name == null ? "" : name); //$NON-NLS-1$

        IValue value;
        try
        {
            value = var.getValue();
        }
        catch (Exception e)
        {
            dto.put(KEY_TYPE, TYPE_UNKNOWN);
            dto.put(KEY_VALUE, error(e));
            dto.put(KEY_HAS_CHILDREN, Boolean.FALSE);
            return dto;
        }

        if (value == null)
        {
            dto.put(KEY_TYPE, TYPE_UNDEFINED);
            dto.put(KEY_VALUE, null);
            dto.put(KEY_HAS_CHILDREN, Boolean.FALSE);
            return dto;
        }

        dto.put(KEY_TYPE, typeOf(value));
        putValue(dto, valueOf(value));

        boolean hasChildren = hasChildren(value);
        dto.put(KEY_HAS_CHILDREN, Boolean.valueOf(hasChildren));
        if (hasChildren)
        {
            dto.put(KEY_EXPAND_HINT, dto.get(KEY_NAME));
        }
        return dto;
    }

    /**
     * Follows a dotted path from a frame into a nested value.
     * <p>
     * Segments are matched without regard to case: BSL does not distinguish it, so neither does an
     * agent typing a variable name back at us. The first variable at each level whose name matches wins.
     * A name that itself contains a dot cannot be reached this way - an accepted limit, since 1C
     * identifiers do not contain dots.
     * </p>
     *
     * @param frame the suspended frame to start from; <code>null</code> yields <code>null</code>
     * @param expandPath dot-separated variable names, outermost first; <code>null</code> or empty
     *            yields <code>null</code>
     * @return the variable the path names, or <code>null</code> when any segment does not resolve
     * @throws Exception if the debug model refuses to hand over a level of variables
     */
    public static IVariable resolvePath(IStackFrame frame, String expandPath) throws Exception
    {
        if (frame == null || expandPath == null || expandPath.isEmpty())
        {
            return null;
        }

        IVariable[] level = frame.getVariables();
        IVariable found = null;

        for (String segment : expandPath.split("\\.")) //$NON-NLS-1$
        {
            found = null;
            for (IVariable candidate : level)
            {
                if (segment.equalsIgnoreCase(candidate.getName()))
                {
                    found = candidate;
                    break;
                }
            }

            if (found == null)
            {
                return null;
            }

            IValue value = found.getValue();
            level = value == null || !value.hasVariables() ? new IVariable[0] : value.getVariables();
        }
        return found;
    }

    /**
     * Puts the value into the DTO, cutting it when it is too long to send whole.
     *
     * @param dto the DTO being built
     * @param value the value string, possibly <code>null</code>
     */
    private static void putValue(Map<String, Object> dto, String value)
    {
        if (value != null && value.length() > MAX_VALUE_LENGTH)
        {
            dto.put(KEY_VALUE, value.substring(0, MAX_VALUE_LENGTH));
            dto.put(KEY_TRUNCATED, Boolean.TRUE);
            dto.put(KEY_FULL_LENGTH, Integer.valueOf(value.length()));
            return;
        }
        dto.put(KEY_VALUE, value);
    }

    /**
     * @param value the value to describe
     * @return the 1C type name, or an empty string when the debug model has none or will not say
     */
    private static String typeOf(IValue value)
    {
        try
        {
            String type = value.getReferenceTypeName();
            return type == null ? "" : type; //$NON-NLS-1$
        }
        catch (Exception e)
        {
            return ""; //$NON-NLS-1$
        }
    }

    /**
     * @param value the value to render
     * @return its string form, or a note of why it could not be rendered
     */
    private static String valueOf(IValue value)
    {
        try
        {
            return value.getValueString();
        }
        catch (Exception e)
        {
            return error(e);
        }
    }

    /**
     * @param value the value to probe
     * @return whether it is worth expanding; <code>false</code> when the debug model will not say
     */
    private static boolean hasChildren(IValue value)
    {
        try
        {
            return value.hasVariables();
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /**
     * @param e what went wrong
     * @return the failure, in the value slot, where the client will see it
     */
    private static String error(Exception e)
    {
        return "<error: " + e.getMessage() + ">"; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
