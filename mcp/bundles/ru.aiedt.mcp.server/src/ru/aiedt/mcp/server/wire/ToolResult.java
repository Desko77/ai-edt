/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The payload a tool with {@code ResponseType.JSON} hands back: an outcome flag plus whatever the
 * tool wants to report.
 * <p>
 * A result always opens with {@code success}. A failed one adds {@code error}; a successful one adds
 * whatever the tool puts in.
 * </p>
 * <p>
 * A <code>null</code> value drops its key from the document rather than writing
 * <code>"key":null</code>, so an optional field costs nothing and needs no guard at the call site.
 * </p>
 *
 * <pre>
 * return ToolResult.success().put("count", items.size()).put("items", items).toJson();
 * return ToolResult.error("Project not found: " + projectName).toJson();
 * </pre>
 */
public class ToolResult
{
    private static final String KEY_SUCCESS = "success"; //$NON-NLS-1$

    private static final String KEY_ERROR = "error"; //$NON-NLS-1$

    private static final String KEY_HELP_HINT = "helpHint"; //$NON-NLS-1$

    private final Map<String, Object> data = new LinkedHashMap<>();

    private ToolResult()
    {
        // use the factories
    }

    /**
     * Starts a successful result.
     *
     * @return a result seeded with <code>{"success":true}</code>
     */
    public static ToolResult success()
    {
        ToolResult result = new ToolResult();
        result.data.put(KEY_SUCCESS, Boolean.TRUE);
        return result;
    }

    /**
     * Starts a failed result.
     *
     * @param message what went wrong, in terms the calling agent can act on; a <code>null</code>
     *            message leaves the {@code error} key out
     * @return a result seeded with <code>{"success":false,"error":"..."}</code>
     */
    public static ToolResult error(String message)
    {
        ToolResult result = new ToolResult();
        result.data.put(KEY_SUCCESS, Boolean.FALSE);
        result.data.put(KEY_ERROR, message);
        return result;
    }

    /**
     * Attaches the call that answers the refusal - the tool and the arguments a caller can send
     * as they stand, without composing them from the prose of the error.
     * <p>
     * A refusal that only describes what went wrong leaves the next step to be reconstructed from
     * its wording; when the next step is known - the project is not found, so list the projects;
     * the object is not found, so search the metadata - it is handed over as a call. The member is
     * {@code helpHint}: {@code {"tool": ..., "arguments": {...}}}, plus whatever the refusing
     * code adds beside it.
     * </p>
     *
     * @param tool the tool to call next
     * @param arguments the arguments for that call, in the order given; may be empty
     * @return this result
     */
    public ToolResult hint(String tool, Map<String, ?> arguments)
    {
        data.put(KEY_HELP_HINT, nextCall(tool, arguments));
        return this;
    }

    /**
     * The shape of a hint on its own - for a helper that reports through a tag map rather than
     * through this builder and still owes the caller the same {@code helpHint}.
     *
     * @param tool the tool to call next
     * @param arguments the arguments for that call, in the order given; may be empty
     * @return {@code {"tool": ..., "arguments": {...}}}, mutable so a detail can be added beside
     */
    public static Map<String, Object> nextCall(String tool, Map<String, ?> arguments)
    {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("tool", tool); //$NON-NLS-1$
        call.put("arguments", arguments == null ? new LinkedHashMap<>() : new LinkedHashMap<>(arguments)); //$NON-NLS-1$
        return call;
    }

    /**
     * The member name a hint is published under, for a helper that writes one into a tag map.
     */
    public static final String HELP_HINT = KEY_HELP_HINT;

    /**
     * Adds a member to the hint attached by {@link #hint} - a suggested value the caller may
     * prefer to the listing, say - and is a no-op when no hint has been attached.
     *
     * @param key the member name
     * @param value the value; <code>null</code> drops nothing and adds nothing
     * @return this result
     */
    @SuppressWarnings("unchecked")
    public ToolResult hintDetail(String key, Object value)
    {
        Object call = data.get(KEY_HELP_HINT);
        if (call instanceof Map && value != null)
        {
            ((Map<String, Object>)call).put(key, value);
        }
        return this;
    }

    /**
     * Turns an already-assembled successful result into a failed one, keeping every
     * member added so far.
     * <p>
     * For the case a mutation cannot express any other way: the write happened, but it
     * did not achieve what was asked - an attribute created without the type requested
     * for it, say. Rebuilding the result would lose the fields that tell the caller what
     * WAS done, and those are exactly what they need to put it right. {@code success}
     * keeps its leading position because the backing map is insertion-ordered and this
     * replaces the value rather than re-adding the key.
     * </p>
     *
     * @param message what went wrong, in terms the calling agent can act on; a
     *            <code>null</code> message leaves the {@code error} key out
     * @return this result, now reading <code>{"success":false,"error":"..."}</code>
     */
    public ToolResult demote(String message)
    {
        data.put(KEY_SUCCESS, Boolean.FALSE);
        if (message != null)
        {
            data.put(KEY_ERROR, message);
        }
        return this;
    }

    /**
     * Adds a string member.
     *
     * @param key the member name
     * @param value the value; <code>null</code> drops the member
     * @return this result
     */
    public ToolResult put(String key, String value)
    {
        data.put(key, value);
        return this;
    }

    /**
     * Adds an int member.
     *
     * @param key the member name
     * @param value the value
     * @return this result
     */
    public ToolResult put(String key, int value)
    {
        data.put(key, Integer.valueOf(value));
        return this;
    }

    /**
     * Adds a long member.
     *
     * @param key the member name
     * @param value the value
     * @return this result
     */
    public ToolResult put(String key, long value)
    {
        data.put(key, Long.valueOf(value));
        return this;
    }

    /**
     * Adds a boolean member.
     *
     * @param key the member name
     * @param value the value
     * @return this result
     */
    public ToolResult put(String key, boolean value)
    {
        data.put(key, Boolean.valueOf(value));
        return this;
    }

    /**
     * Adds a list member, serialized as a JSON array.
     *
     * @param key the member name
     * @param value the elements; <code>null</code> drops the member
     * @return this result
     */
    public ToolResult put(String key, List<?> value)
    {
        data.put(key, value);
        return this;
    }

    /**
     * Adds a member of any other type, serialized by its runtime type.
     *
     * @param key the member name
     * @param value the value; <code>null</code> drops the member
     * @return this result
     */
    public ToolResult put(String key, Object value)
    {
        data.put(key, value);
        return this;
    }

    /**
     * Renders the accumulated members.
     *
     * @return the result as a JSON document
     */
    public String toJson()
    {
        return GsonHolder.toJson(data);
    }

    /**
     * Renders an arbitrary object with the shared serializer, for a tool whose payload is already
     * shaped and does not need this builder.
     *
     * @param obj the object to serialize; may be <code>null</code>
     * @return the JSON document
     */
    public static String toJsonStatic(Object obj)
    {
        return GsonHolder.toJson(obj);
    }
}
