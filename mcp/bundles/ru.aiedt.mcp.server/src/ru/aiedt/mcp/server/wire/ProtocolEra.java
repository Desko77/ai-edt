/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ru.aiedt.mcp.server.wire.jsonrpc.JsonRpcRequest;

/**
 * Which era of the protocol a single request speaks, decided from the request alone.
 * <p>
 * Up to revision {@code 2025-11-25} a client opened with {@code initialize}, and everything after
 * that was understood in the light of that handshake. From {@code 2026-07-28} there is no
 * handshake: every request carries its own protocol version, client identity and capabilities in
 * {@code params._meta}, and the server is forbidden from inferring any of it from what came before.
 * </p>
 * <p>
 * This server answers both, which the specification calls a dual-era server and explicitly allows
 * on one endpoint. The choice is made per request and from one signal only - whether the request
 * carries the modern per-request metadata. Nothing is remembered between requests, so nothing can
 * go stale: a legacy client and a modern client may interleave on the same connection.
 * </p>
 * <p>
 * Deciding the era is kept apart from acting on it. A request can be modern and still malformed -
 * modern metadata with a required field missing - and the two answers differ: one is an unsupported
 * version, the other is invalid params. Conflating them would tell a caller to change the wrong
 * thing.
 * </p>
 */
public final class ProtocolEra
{
    /** What the request turned out to be. */
    public enum Kind
    {
        /** Opened with {@code initialize}, or carries no modern metadata at all. */
        LEGACY,

        /** Carries modern per-request metadata, complete and on a version this server serves. */
        MODERN,

        /** Modern metadata with a required field missing - answered with invalid params. */
        MALFORMED,

        /** Names a protocol version this server does not serve. */
        UNSUPPORTED
    }

    private final Kind kind;

    private final String requestedVersion;

    private final List<String> missing;

    private ProtocolEra(Kind kind, String requestedVersion, List<String> missing)
    {
        this.kind = kind;
        this.requestedVersion = requestedVersion;
        this.missing = missing;
    }

    /**
     * Reads a request and says which era it speaks.
     *
     * @param request the request, possibly null.
     * @return the verdict, never null.
     */
    public static ProtocolEra of(JsonRpcRequest request)
    {
        Map<String, Object> meta = metaOf(request);
        Object version = meta == null ? null : meta.get(McpServerMeta.META_PROTOCOL_VERSION);
        if (!(version instanceof String) || ((String)version).isEmpty())
        {
            // No per-request version: this is a client of the handshake era, or a request that
            // predates the metadata entirely. Either way it is served the old way.
            return new ProtocolEra(Kind.LEGACY, null, List.of());
        }
        String requested = (String)version;
        List<String> absent = new ArrayList<>();
        if (!meta.containsKey(McpServerMeta.META_CLIENT_CAPABILITIES))
        {
            absent.add(McpServerMeta.META_CLIENT_CAPABILITIES);
        }
        if (!absent.isEmpty())
        {
            return new ProtocolEra(Kind.MALFORMED, requested, absent);
        }
        if (!McpServerMeta.SUPPORTED_PROTOCOL_VERSIONS.contains(requested))
        {
            return new ProtocolEra(Kind.UNSUPPORTED, requested, List.of());
        }
        return new ProtocolEra(Kind.MODERN, requested, List.of());
    }

    /**
     * The {@code _meta} map of a request, which lives inside {@code params} rather than beside it.
     *
     * @param request the request, possibly null.
     * @return the map, or null when the request carries none.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> metaOf(JsonRpcRequest request)
    {
        if (request == null || request.getParams() == null)
        {
            return null;
        }
        Object meta = request.getParams().get(McpServerMeta.PARAM_META);
        return meta instanceof Map ? (Map<String, Object>)meta : null;
    }

    /**
     * @return what this request turned out to be.
     */
    public Kind getKind()
    {
        return kind;
    }

    /**
     * @return true when the request is to be served by the current revision's rules.
     */
    public boolean isModern()
    {
        return kind == Kind.MODERN;
    }

    /**
     * @return the version the request asked for, or null when it named none.
     */
    public String getRequestedVersion()
    {
        return requestedVersion;
    }

    /**
     * @return the required metadata fields the request left out; empty unless malformed.
     */
    public List<String> getMissing()
    {
        return missing;
    }
}
