/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire.jsonrpc;

import java.util.Map;

/**
 * An incoming JSON-RPC request.
 * <p>
 * Field names are the wire names: the serializer binds fields, not accessors. Members the client
 * sends but this server does not know about are dropped on the floor.
 * </p>
 * <p>
 * {@code id} and the values inside {@code params} are typed {@link Object} because JSON says so: an
 * id is a string or a number, and a parameter is anything at all. A whole number lands here as a
 * {@link Long} and a fractional one as a {@link Double} - see {@code GsonHolder}, which sets that
 * policy so an argument sent as {@code 42} does not reach a tool as {@code "42.0"}.
 * </p>
 */
public class JsonRpcRequest
{
    private static final String PARAM_ARGUMENTS = "arguments"; //$NON-NLS-1$

    private static final String PARAM_NAME = "name"; //$NON-NLS-1$

    private String jsonrpc;

    private Object id;

    private String method;

    private Map<String, Object> params;

    /**
     * Returns the JSON-RPC dialect the client claims to speak.
     *
     * @return the version string, or <code>null</code> when the client did not send one
     */
    public String getJsonrpc()
    {
        return jsonrpc;
    }

    /**
     * Sets the JSON-RPC dialect.
     *
     * @param jsonrpc the version string
     */
    public void setJsonrpc(String jsonrpc)
    {
        this.jsonrpc = jsonrpc;
    }

    /**
     * Returns the correlation id to echo back.
     *
     * @return a {@link String}, a {@link Number}, or <code>null</code> for a notification
     */
    public Object getId()
    {
        return id;
    }

    /**
     * Sets the correlation id.
     *
     * @param id the id
     */
    public void setId(Object id)
    {
        this.id = id;
    }

    /**
     * Returns the requested method.
     *
     * @return the method name, or <code>null</code>
     */
    public String getMethod()
    {
        return method;
    }

    /**
     * Sets the requested method.
     *
     * @param method the method name
     */
    public void setMethod(String method)
    {
        this.method = method;
    }

    /**
     * Returns the raw parameter object.
     *
     * @return the parameters, or <code>null</code> when the client sent none
     */
    public Map<String, Object> getParams()
    {
        return params;
    }

    /**
     * Sets the parameter object.
     *
     * @param params the parameters
     */
    public void setParams(Map<String, Object> params)
    {
        this.params = params;
    }

    /**
     * Reads one parameter as text, whatever its JSON type was.
     *
     * @param name the parameter name
     * @return the string form of the value, or <code>null</code> when it is absent
     */
    public String getStringParam(String name)
    {
        if (params == null || name == null)
        {
            return null;
        }
        Object value = params.get(name);
        return value != null ? value.toString() : null;
    }

    /**
     * Returns the {@code arguments} object of a {@code tools/call} request.
     *
     * @return the arguments, or <code>null</code> when absent or not an object
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getArguments()
    {
        if (params == null)
        {
            return null;
        }
        Object arguments = params.get(PARAM_ARGUMENTS);
        if (arguments instanceof Map)
        {
            return (Map<String, Object>)arguments;
        }
        return null;
    }

    /**
     * Returns the tool named by a {@code tools/call} request.
     *
     * @return the tool name, or <code>null</code> when absent
     */
    public String getToolName()
    {
        return getStringParam(PARAM_NAME);
    }
}
