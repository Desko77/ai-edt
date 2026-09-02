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
     * The revision that dropped the handshake: version, identity and capabilities travel in every
     * request instead.
     * <p>
     * Kept apart from {@link #PROTOCOL_VERSION} on purpose. That one is what an {@code initialize}
     * handshake answers with, and a client that reaches the handshake at all is a client of the
     * older era - answering it with a revision that has no handshake would name a revision in
     * which the very question it just asked does not exist.
     * </p>
     */
    public static final String MODERN_PROTOCOL_VERSION = "2026-07-28"; //$NON-NLS-1$

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
            MODERN_PROTOCOL_VERSION,
            "2025-11-25", //$NON-NLS-1$
            "2025-06-18", //$NON-NLS-1$
            "2025-03-26", //$NON-NLS-1$
            "2024-11-05"))); //$NON-NLS-1$

    /**
     * Revisions whose rules a request may be answered by when it declares its version per request.
     * <p>
     * Only revisions that DEFINE that shape belong here, and the distinction is not pedantic. The
     * version in {@code params._meta} says which revision the request is using; answering a request
     * that says {@code 2025-11-25} with {@code resultType} and {@code _meta.serverInfo} produces a
     * body that revision does not define. The version being supported is a separate question from
     * the version defining the answer, and one list cannot answer both.
     * </p>
     * <p>
     * A request that declares a supported but older revision is therefore served the shape of the
     * revision it named - the same answer a client of that era gets - rather than refused. It asked
     * for a revision this server serves; it just did not ask for this one.
     * </p>
     */
    public static final java.util.Set<String> MODERN_PROTOCOL_VERSIONS =
        java.util.Collections.singleton(MODERN_PROTOCOL_VERSION);

    /**
     * Revisions the {@code initialize} handshake may agree to.
     * <p>
     * Everything except the current revision, because the current revision has no handshake. A
     * server that answered {@code initialize} with {@code 2026-07-28} would be agreeing to a
     * revision in which the question it was just asked does not exist.
     * </p>
     */
    public static final java.util.Set<String> HANDSHAKE_PROTOCOL_VERSIONS =
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

    /**
     * Asks a server what it speaks, before anything else is sent. Required of every server from
     * {@link #MODERN_PROTOCOL_VERSION}, and the one method a client may call without knowing which
     * era the server belongs to.
     */
    public static final String METHOD_SERVER_DISCOVER = "server/discover"; //$NON-NLS-1$

    /** Where a request keeps its metadata: inside {@code params}, not beside it. */
    public static final String PARAM_META = "_meta"; //$NON-NLS-1$

    /** Metadata key: the protocol version this one request speaks. Required from the modern era. */
    public static final String META_PROTOCOL_VERSION =
        "io.modelcontextprotocol/protocolVersion"; //$NON-NLS-1$

    /** Metadata key: what the client can do. Required from the modern era. */
    public static final String META_CLIENT_CAPABILITIES =
        "io.modelcontextprotocol/clientCapabilities"; //$NON-NLS-1$

    /** Metadata key: who the client is. Advisory - never used to decide behaviour. */
    public static final String META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo"; //$NON-NLS-1$

    /** Metadata key: who answered. Put on every modern result. */
    public static final String META_SERVER_INFO = "io.modelcontextprotocol/serverInfo"; //$NON-NLS-1$

    /** Every modern result says what kind it is; an ordinary one is complete. */
    public static final String RESULT_TYPE = "resultType"; //$NON-NLS-1$

    /** The result kind of an ordinary, finished answer. */
    public static final String RESULT_COMPLETE = "complete"; //$NON-NLS-1$

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

    /**
     * The request named a protocol version this server does not serve. Its {@code data} carries the
     * versions that ARE served, so the client can retry on one of them rather than give up.
     * <p>
     * Allocated by the specification, not by us: the range from {@code -32020} belongs to MCP, and
     * emitting anything else from it would be inventing a meaning for somebody else's number.
     * </p>
     */
    public static final int ERROR_UNSUPPORTED_PROTOCOL_VERSION = -32022;

    /**
     * The request needs a client capability the request itself did not declare.
     * <p>
     * Taken from the core schema of {@code 2026-07-28}, which allocates {@code -32020} onwards to
     * the protocol and says in the same breath that {@code -32002} and {@code -32003} are retired
     * and not to be reused. The tasks extension's own draft still cites {@code -32003} for this;
     * where the two disagree the core schema is the one clients are built against.
     * </p>
     */
    public static final int ERROR_MISSING_CLIENT_CAPABILITY = -32021;

    /** Header naming the negotiated MCP revision. Declared for completeness, never emitted. */
    public static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version"; //$NON-NLS-1$

    /** Header carrying the session id handed out on initialize. */
    public static final String HEADER_SESSION_ID = "MCP-Session-Id"; //$NON-NLS-1$

    /** Result kind: not the answer, a handle to where the answer will be. */
    public static final String RESULT_TASK = "task"; //$NON-NLS-1$

    /** The tasks extension, by its reverse-DNS name. */
    public static final String EXTENSION_TASKS = "io.modelcontextprotocol/tasks"; //$NON-NLS-1$

    /** Key under which capabilities - a client's or this server's - list their extensions. */
    public static final String CAPABILITY_EXTENSIONS = "extensions"; //$NON-NLS-1$

    /** Method: read a task's state. */
    public static final String METHOD_TASKS_GET = "tasks/get"; //$NON-NLS-1$

    /** Method: answer a task's outstanding input requests. */
    public static final String METHOD_TASKS_UPDATE = "tasks/update"; //$NON-NLS-1$

    /** Method: ask a task to stop. */
    public static final String METHOD_TASKS_CANCEL = "tasks/cancel"; //$NON-NLS-1$

    /** Method: capability handshake. */
    public static final String METHOD_INITIALIZE = "initialize"; //$NON-NLS-1$

    /** Method: post-handshake notification, answered with no document at all. */
    public static final String METHOD_INITIALIZED = "notifications/initialized"; //$NON-NLS-1$

    /** Method: tool catalogue. */
    public static final String METHOD_TOOLS_LIST = "tools/list"; //$NON-NLS-1$

    /** Method: tool invocation. */
    public static final String METHOD_TOOLS_CALL = "tools/call"; //$NON-NLS-1$

    /**
     * Method: liveness. Answered with an empty result, which is the whole of it.
     * <p>
     * In the base specification since the beginning, and answered with method-not-found here until
     * now - so a client checking liveness the standard way concluded the server was broken.
     * </p>
     */
    public static final String METHOD_PING = "ping"; //$NON-NLS-1$

    /** Method: what documents this server can hand over. */
    public static final String METHOD_RESOURCES_LIST = "resources/list"; //$NON-NLS-1$

    /** Method: hand one of them over, named by uri. */
    public static final String METHOD_RESOURCES_READ = "resources/read"; //$NON-NLS-1$

    /**
     * The templates half of the resources capability.
     * <p>
     * Declared capabilities are a promise: this server advertises {@code resources}, so a client
     * may call every method of that capability. Answering one of them with "method not found"
     * makes the server look broken to a client that reads the promise and asks - which is what a
     * Cursor discovery pass does before it will show any tool at all.
     * </p>
     */
    public static final String METHOD_RESOURCES_TEMPLATES_LIST = "resources/templates/list"; //$NON-NLS-1$

    /**
     * Asked by clients that probe rather than read the declared capabilities.
     * <p>
     * This server declares no prompts, so a client that goes by the capabilities never sends this.
     * Some send it anyway and take the error for a failed handshake, and an empty list costs
     * nothing to return.
     * </p>
     */
    public static final String METHOD_PROMPTS_LIST = "prompts/list"; //$NON-NLS-1$

    /**
     * The scheme this server's resources are addressed under.
     * <p>
     * Its own scheme rather than {@code file:} on purpose: a description may be packaged inside the
     * plugin jar, where there is no file for a client to open, and an address that looks openable
     * and is not is worse than one that plainly needs this server to resolve it.
     * </p>
     */
    public static final String RESOURCE_SCHEME = "aiedt://"; //$NON-NLS-1$

    /** The one family of resources served today: the write-ups for EDT validation checks. */
    public static final String RESOURCE_CHECKS = RESOURCE_SCHEME + "checks/"; //$NON-NLS-1$

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
