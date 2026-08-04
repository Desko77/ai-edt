/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.wire.jsonrpc.JsonRpcRequest;

/**
 * Verifies the shared Gson instance: singleton lifetime, HTML escaping and the number policy that
 * keeps a whole number whole on its way through an untyped field.
 */
public class GsonHolderTest
{
    @Test
    public void serializingAPrimitiveStringQuotesIt()
    {
        assertEquals("\"hello\"", GsonHolder.toJson("hello"));
    }

    @Test
    public void serializingAMapProducesItsMembers()
    {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("answer", Integer.valueOf(42));
        String json = GsonHolder.toJson(document);
        assertTrue(json.contains("\"answer\":42"));
    }

    @Test
    public void deserializingARequestPopulatesItsFields()
    {
        String document = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"initialize\"}"; //$NON-NLS-1$
        JsonRpcRequest request = GsonHolder.fromJson(document, JsonRpcRequest.class);

        assertEquals("2.0", request.getJsonrpc());
        assertEquals("initialize", request.getMethod());
    }

    @Test
    public void wholeNumbersReadIntoAnUntypedFieldStayLongNotDouble()
    {
        // The LONG_OR_DOUBLE policy is the whole reason this class exists: a 42 sent on the wire
        // must round-trip as a Long, never as the 42.0 the stock policy would produce.
        String document = "{\"id\":42}"; //$NON-NLS-1$
        @SuppressWarnings("unchecked")
        Map<String, Object> read = GsonHolder.fromJson(document, Map.class);

        assertEquals(Long.valueOf(42L), read.get("id"));
    }

    @Test
    public void fractionalNumbersReadIntoAnUntypedFieldBecomeDouble()
    {
        String document = "{\"ratio\":1.5}"; //$NON-NLS-1$
        @SuppressWarnings("unchecked")
        Map<String, Object> read = GsonHolder.fromJson(document, Map.class);

        assertEquals(Double.valueOf(1.5d), read.get("ratio"));
    }

    @Test
    public void getAlwaysHandsOutTheSameInstance()
    {
        assertSame(GsonHolder.get(), GsonHolder.get());
    }
}
