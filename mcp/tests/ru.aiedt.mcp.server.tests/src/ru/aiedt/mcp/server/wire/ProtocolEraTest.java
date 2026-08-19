/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.wire.jsonrpc.JsonRpcRequest;

/**
 * Pins how one request is read for which era of the protocol it speaks.
 * <p>
 * Everything downstream hangs off this verdict, and the four outcomes must stay four. Collapsing
 * "names a version I do not serve" into "left out a required field" would send a caller to fix the
 * one thing that was right; collapsing either into "legacy" would silently serve a modern client
 * the old shape and call it success.
 * </p>
 */
public class ProtocolEraTest
{
    /** A request with no metadata at all is a client of the handshake era. */
    @Test
    public void noMetadataMeansLegacy()
    {
        assertEquals(ProtocolEra.Kind.LEGACY, ProtocolEra.of(requestWith(null)).getKind());
        assertEquals("a request with no params at all is legacy too", //$NON-NLS-1$
            ProtocolEra.Kind.LEGACY, ProtocolEra.of(new JsonRpcRequest()).getKind());
        assertEquals("a null request must not throw", //$NON-NLS-1$
            ProtocolEra.Kind.LEGACY, ProtocolEra.of(null).getKind());
    }

    /** Metadata that names a served version and declares capabilities is modern. */
    @Test
    public void completeMetadataOnAServedVersionIsModern()
    {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(McpServerMeta.META_PROTOCOL_VERSION, McpServerMeta.MODERN_PROTOCOL_VERSION);
        meta.put(McpServerMeta.META_CLIENT_CAPABILITIES, new LinkedHashMap<>());

        ProtocolEra era = ProtocolEra.of(requestWith(meta));

        assertTrue("a complete modern request was not read as modern", era.isModern()); //$NON-NLS-1$
        assertEquals(McpServerMeta.MODERN_PROTOCOL_VERSION, era.getRequestedVersion());
    }

    /**
     * A version without capabilities is malformed, not unsupported.
     * <p>
     * The distinction is the point: the caller has to be told to add a field, not to change its
     * version.
     * </p>
     */
    @Test
    public void aVersionWithoutCapabilitiesIsMalformed()
    {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(McpServerMeta.META_PROTOCOL_VERSION, McpServerMeta.MODERN_PROTOCOL_VERSION);

        ProtocolEra era = ProtocolEra.of(requestWith(meta));

        assertEquals(ProtocolEra.Kind.MALFORMED, era.getKind());
        assertFalse("a malformed request must not be served as modern", era.isModern()); //$NON-NLS-1$
        assertTrue("the missing field is not named: " + era.getMissing(), //$NON-NLS-1$
            era.getMissing().contains(McpServerMeta.META_CLIENT_CAPABILITIES));
    }

    /** A complete request naming a version nobody serves is unsupported, and says which. */
    @Test
    public void anUnknownVersionIsUnsupportedNotMalformed()
    {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(McpServerMeta.META_PROTOCOL_VERSION, "1900-01-01"); //$NON-NLS-1$
        meta.put(McpServerMeta.META_CLIENT_CAPABILITIES, new LinkedHashMap<>());

        ProtocolEra era = ProtocolEra.of(requestWith(meta));

        assertEquals(ProtocolEra.Kind.UNSUPPORTED, era.getKind());
        assertEquals("1900-01-01", era.getRequestedVersion()); //$NON-NLS-1$
        assertTrue("nothing should be reported missing", era.getMissing().isEmpty()); //$NON-NLS-1$
    }

    /**
     * An older revision named the modern way is served the shape of the revision it named.
     * <p>
     * The version field says which revision the request is USING. Answering a request that says
     * {@code 2025-11-25} with {@code resultType} and {@code _meta.serverInfo} hands it a body that
     * revision does not define - the same fault as refusing it, told the other way round. It is
     * supported, so it is not refused; it is not the current revision, so it does not get the
     * current revision's shape.
     * </p>
     */
    @Test
    public void anOlderServedVersionNamedTheModernWayGetsItsOwnShape()
    {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(McpServerMeta.META_PROTOCOL_VERSION, McpServerMeta.PROTOCOL_VERSION);
        meta.put(McpServerMeta.META_CLIENT_CAPABILITIES, new LinkedHashMap<>());

        ProtocolEra era = ProtocolEra.of(requestWith(meta));

        assertFalse("an older revision must not be answered in the current revision's shape", //$NON-NLS-1$
            era.isModern());
        assertEquals(ProtocolEra.Kind.LEGACY, era.getKind());
        assertEquals("and it is still the version the caller named, not a refusal", //$NON-NLS-1$
            McpServerMeta.PROTOCOL_VERSION, era.getRequestedVersion());
    }

    /** A version key holding something that is not a string is malformed, not absent. */
    @Test
    public void aVersionOfTheWrongTypeIsMalformed()
    {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(McpServerMeta.META_PROTOCOL_VERSION, Integer.valueOf(20260728));
        meta.put(McpServerMeta.META_CLIENT_CAPABILITIES, new LinkedHashMap<>());

        ProtocolEra era = ProtocolEra.of(requestWith(meta));

        assertEquals("reading it as absent would serve a client that tried to declare itself", //$NON-NLS-1$
            ProtocolEra.Kind.MALFORMED, era.getKind());
        assertTrue(era.getMissing().contains(McpServerMeta.META_PROTOCOL_VERSION));
    }

    /** Capabilities holding something that is not an object are as good as absent. */
    @Test
    public void capabilitiesOfTheWrongTypeAreAsGoodAsMissing()
    {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(McpServerMeta.META_PROTOCOL_VERSION, McpServerMeta.MODERN_PROTOCOL_VERSION);
        meta.put(McpServerMeta.META_CLIENT_CAPABILITIES, "none"); //$NON-NLS-1$

        ProtocolEra era = ProtocolEra.of(requestWith(meta));

        assertEquals(ProtocolEra.Kind.MALFORMED, era.getKind());
        assertTrue(era.getMissing().contains(McpServerMeta.META_CLIENT_CAPABILITIES));
    }

    /**
     * An unserved version outranks a missing field.
     * <p>
     * Order matters here: a client naming a revision nobody serves has to change its version, and
     * telling it to add a field first sends it round a loop it cannot leave.
     * </p>
     */
    @Test
    public void anUnservedVersionIsReportedBeforeAMissingField()
    {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(McpServerMeta.META_PROTOCOL_VERSION, "1900-01-01"); //$NON-NLS-1$

        assertEquals(ProtocolEra.Kind.UNSUPPORTED, ProtocolEra.of(requestWith(meta)).getKind());
    }

    /** An empty version string is no version - the request falls to the handshake era. */
    @Test
    public void anEmptyVersionIsNotAVersion()
    {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(McpServerMeta.META_PROTOCOL_VERSION, ""); //$NON-NLS-1$

        assertEquals(ProtocolEra.Kind.LEGACY, ProtocolEra.of(requestWith(meta)).getKind());
    }

    private static JsonRpcRequest requestWith(Map<String, Object> meta)
    {
        JsonRpcRequest request = new JsonRpcRequest();
        Map<String, Object> params = new LinkedHashMap<>();
        if (meta != null)
        {
            params.put(McpServerMeta.PARAM_META, meta);
        }
        request.setParams(params);
        return request;
    }
}
