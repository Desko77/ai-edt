/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire.jsonrpc;

/**
 * The error member of a JSON-RPC response: a code the client can branch on and a message a human (or
 * an agent) can read.
 * <p>
 * A <code>null</code> message - an exception that carried none - leaves the member out of the
 * document rather than writing <code>"message":null</code>.
 * </p>
 */
public class JsonRpcError
{
    private final int code;

    private final String message;

    /**
     * What the caller needs in order to act on the error, when the message alone is not enough.
     * <p>
     * An unsupported protocol version is the case that made this necessary: telling a client that
     * its version is refused, without telling it which versions are served, leaves it with nothing
     * to retry on. Omitted from the wire when there is nothing to say.
     * </p>
     */
    private Object data;

    /**
     * Creates an error member.
     *
     * @param code one of the {@code McpServerMeta.ERROR_*} codes
     * @param message what went wrong; may be <code>null</code>
     */
    public JsonRpcError(int code, String message)
    {
        this.code = code;
        this.message = message;
    }

    /**
     * Returns the error code.
     *
     * @return the code
     */
    /**
     * Attaches what the caller needs to act on this error.
     *
     * @param data the payload; null leaves the member off the wire.
     * @return this error, for chaining at the call site.
     */
    public JsonRpcError withData(Object data)
    {
        this.data = data;
        return this;
    }

    /**
     * Returns what was attached to this error.
     *
     * @return the payload, or null when nothing was attached.
     */
    public Object getData()
    {
        return data;
    }

    public int getCode()
    {
        return code;
    }

    /**
     * Returns the error message.
     *
     * @return the message, or <code>null</code>
     */
    public String getMessage()
    {
        return message;
    }
}
