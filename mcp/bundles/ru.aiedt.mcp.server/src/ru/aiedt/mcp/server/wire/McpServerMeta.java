/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire;

import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.Version;

/**
 * Fixed identifiers of the MCP endpoint: protocol identity, JSON-RPC error codes, transport
 * headers and the names of the four JSON-RPC methods this server answers.
 * <p>
 * Everything declared here is observable by connected clients, either directly on the wire or
 * through the plugin user interface. Treat the values as frozen.
 * </p>
 */
public final class McpServerMeta
{
    /** JSON-RPC dialect: required in every request, echoed in every response. */
    public static final String JSONRPC_VERSION = "2.0"; //$NON-NLS-1$

    /** MCP revision advertised when the client does not ask for a specific one. */
    public static final String PROTOCOL_VERSION = "2025-11-25"; //$NON-NLS-1$

    /**
     * Revisions this server will agree to when a client asks for one.
     * <p>
     * The four listed differ, for the traffic that actually crosses this wire, only in
     * optional additions: the handshake, the tool catalogue and the tool call keep the same
     * shape throughout, and what the later revisions add on top is announced through
     * capabilities rather than assumed. So agreeing to an older one costs nothing and keeps
     * a client pinned to it working.
     * </p>
     * <p>
     * Agreeing to a revision NOT on this list is different, and is what this list exists to
     * stop: any string shaped like a date used to be echoed back as agreed, so a client
     * asking for a revision that does not exist was told the server implements it. A
     * negotiation whose answer is always yes is not a negotiation - it is a claim made
     * without looking, of exactly the kind this project keeps finding and removing.
     * </p>
     */
    public static final java.util.Set<String> SUPPORTED_PROTOCOL_VERSIONS =
        java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(java.util.Arrays.asList(
            "2025-11-25", //$NON-NLS-1$
            "2025-06-18", //$NON-NLS-1$
            "2025-03-26", //$NON-NLS-1$
            "2024-11-05"))); //$NON-NLS-1$

    /** Value of {@code serverInfo.name} in the initialize result. */
    public static final String SERVER_NAME = "ai-edt-mcp-server"; //$NON-NLS-1$

    /** Value of the non-standard {@code serverInfo.author} member; also shown in the plugin UI. */
    public static final String AUTHOR = "AI-EDT"; //$NON-NLS-1$

    /**
     * This bundle's version without the build qualifier, for example {@code 3.1.0}. Falls back to
     * {@code unknown} when no OSGi framework is around to ask (plain unit tests).
     */
    public static final String PLUGIN_VERSION;

    /** JSON-RPC: request body could not be parsed. Declared for completeness, never emitted. */
    public static final int ERROR_PARSE = -32700;

    /** JSON-RPC: the document is not a valid request (wrong or missing {@code jsonrpc} version). */
    public static final int ERROR_INVALID_REQUEST = -32600;

    /** JSON-RPC: unknown method. Also reported for an unknown tool name. */
    public static final int ERROR_METHOD_NOT_FOUND = -32601;

    /** JSON-RPC: invalid parameters. Declared for completeness, never emitted. */
    public static final int ERROR_INVALID_PARAMS = -32602;

    /** JSON-RPC: anything that escaped request handling. */
    public static final int ERROR_INTERNAL = -32603;

    /** Header naming the negotiated MCP revision. Declared for completeness, never emitted. */
    public static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version"; //$NON-NLS-1$

    /** Header carrying the session id handed out on initialize. */
    public static final String HEADER_SESSION_ID = "MCP-Session-Id"; //$NON-NLS-1$

    /** Method: capability handshake. */
    public static final String METHOD_INITIALIZE = "initialize"; //$NON-NLS-1$

    /** Method: post-handshake notification, answered with no document at all. */
    public static final String METHOD_INITIALIZED = "notifications/initialized"; //$NON-NLS-1$

    /** Method: tool catalogue. */
    public static final String METHOD_TOOLS_LIST = "tools/list"; //$NON-NLS-1$

    /** Method: tool invocation. */
    public static final String METHOD_TOOLS_CALL = "tools/call"; //$NON-NLS-1$

    private static final String UNKNOWN_VERSION = "unknown"; //$NON-NLS-1$

    private static final String VERSION_SEPARATOR = "."; //$NON-NLS-1$

    static
    {
        PLUGIN_VERSION = resolvePluginVersion();
    }

    private McpServerMeta()
    {
        // constants only
    }

    /**
     * Renders the version of the bundle this class was loaded from, dropping the build qualifier.
     *
     * @return {@code major.minor.micro}, or {@code unknown} outside an OSGi framework
     */
    private static String resolvePluginVersion()
    {
        Bundle bundle = FrameworkUtil.getBundle(McpServerMeta.class);
        if (bundle == null)
        {
            return UNKNOWN_VERSION;
        }
        Version version = bundle.getVersion();
        if (version == null)
        {
            return UNKNOWN_VERSION;
        }
        return version.getMajor() + VERSION_SEPARATOR + version.getMinor() + VERSION_SEPARATOR
            + version.getMicro();
    }
}
