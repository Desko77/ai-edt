/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire.jsonrpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.wire.GsonHolder;

/**
 * Round-trip and accessor checks for the three JSON-RPC data-transfer objects a request and a
 * response are built from.
 */
public class JsonRpcEnvelopeTest
{
    // ---- request ----

    @Test
    public void requestDeserializationReadsDialectMethodAndId()
    {
        String document = "{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"tools/list\"}"; //$NON-NLS-1$
        JsonRpcRequest request = GsonHolder.fromJson(document, JsonRpcRequest.class);

        assertEquals("2.0", request.getJsonrpc());
        assertEquals("tools/list", request.getMethod());
        assertEquals(Long.valueOf(42L), request.getId());
    }

    @Test
    public void requestDecodesToolNameAndArgumentsOfAToolCall()
    {
        String document = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"," //$NON-NLS-1$
            + "\"params\":{\"name\":\"get_edt_version\",\"arguments\":{\"projectName\":\"Demo\"}}}"; //$NON-NLS-1$
        JsonRpcRequest request = GsonHolder.fromJson(document, JsonRpcRequest.class);

        assertEquals("get_edt_version", request.getToolName());
        Map<String, Object> arguments = request.getArguments();
        assertNotNull(arguments);
        assertEquals("Demo", arguments.get("projectName"));
    }

    @Test
    public void requestWithNoParamsHasNullAccessors()
    {
        JsonRpcRequest request = GsonHolder.fromJson(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}", JsonRpcRequest.class); //$NON-NLS-1$

        assertNull(request.getParams());
        assertNull(request.getToolName());
        assertNull(request.getArguments());
    }

    @Test
    public void getArgumentsReturnsNullWhenTheMemberIsNotAnObject()
    {
        JsonRpcRequest request = new JsonRpcRequest();
        Map<String, Object> params = new HashMap<>();
        params.put("arguments", "not-a-map");
        request.setParams(params);

        assertNull(request.getArguments());
    }

    @Test
    public void settersAndStringParamAccessorRoundTrip()
    {
        JsonRpcRequest request = new JsonRpcRequest();
        request.setJsonrpc("2.0");
        request.setId("req-1");
        request.setMethod("tools/call");

        Map<String, Object> params = new HashMap<>();
        params.put("name", "do_work");
        params.put("limit", Integer.valueOf(5));
        request.setParams(params);

        assertEquals("2.0", request.getJsonrpc());
        assertEquals("req-1", request.getId());
        assertEquals("tools/call", request.getMethod());
        assertEquals("do_work", request.getStringParam("name"));
        assertEquals("5", request.getStringParam("limit"));
        assertNull(request.getStringParam("absent"));
        assertNull(request.getStringParam(null));
    }

    // ---- response ----

    @Test
    public void successResponseCarriesResultButNoError()
    {
        JsonRpcResponse response = JsonRpcResponse.success(Integer.valueOf(7), "payload");

        assertEquals("2.0", response.getJsonrpc());
        assertEquals(7, response.getId());
        assertEquals("payload", response.getResult());
        assertNull(response.getError());
    }

    @Test
    public void errorResponseCarriesErrorButNoResult()
    {
        JsonRpcResponse response = JsonRpcResponse.error(Integer.valueOf(2),
            ru.aiedt.mcp.server.wire.McpServerMeta.ERROR_INVALID_REQUEST, "Bad shape");

        assertEquals(2, response.getId());
        assertNull(response.getResult());
        assertNotNull(response.getError());
        assertEquals(ru.aiedt.mcp.server.wire.McpServerMeta.ERROR_INVALID_REQUEST,
            response.getError().getCode());
        assertEquals("Bad shape", response.getError().getMessage());
    }

    @Test
    public void successResponseSerializationOmitsTheErrorMember()
    {
        String json = GsonHolder.toJson(JsonRpcResponse.success(1, "ok"));

        assertTrue(json.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(json.contains("\"result\":\"ok\""));
        assertFalse(json.contains("\"error\""));
    }

    @Test
    public void errorResponseSerializationCarriesCodeAndMessage()
    {
        String json = GsonHolder.toJson(JsonRpcResponse.error(1,
            ru.aiedt.mcp.server.wire.McpServerMeta.ERROR_METHOD_NOT_FOUND, "Unknown method"));

        assertTrue(json.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(json.contains("\"code\":-32601"));
        assertTrue(json.contains("\"message\":\"Unknown method\""));
    }

    // ---- error member ----

    @Test
    public void errorMemberRoundTripsItsCodeAndMessage()
    {
        JsonRpcError error = new JsonRpcError(-32603, "boom");
        String json = GsonHolder.toJson(error);
        JsonRpcError read = GsonHolder.fromJson(json, JsonRpcError.class);

        assertEquals(-32603, read.getCode());
        assertEquals("boom", read.getMessage());
    }

    @Test
    public void errorMemberWithNullMessageDropsItFromTheWire()
    {
        String json = GsonHolder.toJson(new JsonRpcError(-32603, null));
        assertFalse(json.contains("\"message\""));
    }
}
