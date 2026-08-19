/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire.jsonrpc;

import ru.aiedt.mcp.server.wire.McpServerMeta;

/**
 * An outgoing JSON-RPC response.
 * <p>
 * JSON-RPC allows a response to carry a result or an error, never both. That is enforced here by
 * construction: each factory fills one field and leaves the other <code>null</code>, and a
 * <code>null</code> field is not serialized at all.
 * </p>
 * <p>
 * Field order is wire order: {@code jsonrpc}, {@code id}, then whichever of {@code result} /
 * {@code error} was filled in.
 * </p>
 */
public class JsonRpcResponse
{
    private final String jsonrpc = McpServerMeta.JSONRPC_VERSION;

    private Object id;

    private Object result;

    private JsonRpcError error;

    private JsonRpcResponse()
    {
        // use the factories
    }

    /**
     * Builds a successful response.
     *
     * @param id the id of the request being answered
     * @param result the payload; serialized by its runtime type
     * @return the response
     */
    public static JsonRpcResponse success(Object id, Object result)
    {
        JsonRpcResponse response = new JsonRpcResponse();
        response.id = id;
        response.result = result;
        return response;
    }

    /**
     * Builds a failed response.
     *
     * @param id the id of the request being answered
     * @param code one of the {@code McpServerMeta.ERROR_*} codes
     * @param message what went wrong; may be <code>null</code>
     * @return the response
     */
    public static JsonRpcResponse error(Object id, int code, String message)
    {
        return error(id, code, message, null);
    }

    /**
     * Builds a failed response carrying what the caller needs to act on it.
     *
     * @param id the id of the request being answered
     * @param code one of the {@code McpServerMeta.ERROR_*} codes
     * @param message what went wrong; may be <code>null</code>
     * @param data the payload the caller acts on; null leaves it off the wire
     * @return the response
     */
    public static JsonRpcResponse error(Object id, int code, String message, Object data)
    {
        JsonRpcResponse response = new JsonRpcResponse();
        response.id = id;
        response.error = new JsonRpcError(code, message).withData(data);
        return response;
    }

    /**
     * Returns the JSON-RPC dialect, which is always the one this server speaks.
     *
     * @return the version string
     */
    public String getJsonrpc()
    {
        return jsonrpc;
    }

    /**
     * Returns the echoed request id.
     *
     * @return the id, or <code>null</code> when it could not be determined
     */
    public Object getId()
    {
        return id;
    }

    /**
     * Returns the result payload.
     *
     * @return the result, or <code>null</code> on a failed response
     */
    public Object getResult()
    {
        return result;
    }

    /**
     * Returns the error member.
     *
     * @return the error, or <code>null</code> on a successful response
     */
    public JsonRpcError getError()
    {
        return error;
    }
}
