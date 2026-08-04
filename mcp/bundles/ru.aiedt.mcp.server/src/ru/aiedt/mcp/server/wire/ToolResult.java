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
