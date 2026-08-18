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

import org.junit.Test;

import ru.aiedt.mcp.server.wire.GsonHolder;
import ru.aiedt.mcp.server.wire.McpServerMeta;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Checks the initialize handshake result (with its nested capabilities and server identity) and the
 * JSON-RPC error member, including how each serializes and how the error composes inside a response.
 */
public class HandshakeDtoTest
{
    // ---- InitializeResult ----

    @Test
    public void initializeResultExposesRevisionIdentityAndToolsCapability()
    {
        InitializeResult result = new InitializeResult("2025-11-25", "srv", "3.1.0", "AI-EDT", "AI-EDT @ Demo");

        assertEquals("2025-11-25", result.getProtocolVersion());
        assertNotNull(result.getCapabilities());
        assertNotNull(result.getCapabilities().getTools());
        assertNotNull(result.getServerInfo());
    }

    @Test
    public void serverInfoAccessorsReturnConstructorValues()
    {
        InitializeResult.ServerInfo info =
            new InitializeResult("d", "named-server", "2.0.0", "An Author", "A Title").getServerInfo();

        assertEquals("named-server", info.getName());
        assertEquals("2.0.0", info.getVersion());
        assertEquals("An Author", info.getAuthor());
        assertEquals("A Title", info.getTitle());
    }

    @Test
    public void initializeResultSerializesToExpectedMembers()
    {
        InitializeResult result = new InitializeResult(
            McpServerMeta.PROTOCOL_VERSION, "srv", "1.0", "Author", "AI-EDT @ Demo");
        JsonObject document = JsonParser.parseString(GsonHolder.toJson(result)).getAsJsonObject();

        assertEquals(McpServerMeta.PROTOCOL_VERSION,
            document.get("protocolVersion").getAsString());
        assertNotNull(document.get("capabilities"));
        JsonObject info = document.getAsJsonObject("serverInfo");
        assertEquals("srv", info.get("name").getAsString());
        assertEquals("1.0", info.get("version").getAsString());
        assertEquals("Author", info.get("author").getAsString());
        // On the wire because one machine runs several of these and the name is the same on
        // all of them; the title is the only member that says which workspace answered.
        assertEquals("AI-EDT @ Demo", info.get("title").getAsString());
    }

    @Test
    public void toolsCapabilitySerializesToAnEmptyObject()
    {
        InitializeResult result = new InitializeResult("v", "s", "1", "a", "t");
        JsonObject document = JsonParser.parseString(GsonHolder.toJson(result)).getAsJsonObject();

        JsonObject tools = document.getAsJsonObject("capabilities").getAsJsonObject("tools");
        assertNotNull(tools);
        assertTrue(tools.entrySet().isEmpty());
    }

    @Test
    public void initializeResultWrappedInAResponseStaysJsonRpcCompliant()
    {
        String json = GsonHolder.toJson(JsonRpcResponse.success(1,
            new InitializeResult("2025-11-25", "s", "1.0", "a", "t")));
        JsonObject document = JsonParser.parseString(json).getAsJsonObject();

        assertEquals("2.0", document.get("jsonrpc").getAsString());
        assertNotNull(document.get("result"));
        assertNull(document.get("error"));
    }

    // ---- JsonRpcError ----

    @Test
    public void errorMemberHoldsCodeAndMessage()
    {
        JsonRpcError error = new JsonRpcError(-32600, "Invalid request");
        assertEquals(-32600, error.getCode());
        assertEquals("Invalid request", error.getMessage());
    }

    @Test
    public void errorMemberSerializesItsTwoFields()
    {
        JsonObject document = JsonParser.parseString(
            GsonHolder.toJson(new JsonRpcError(-32601, "Method not found"))).getAsJsonObject();

        assertEquals(-32601, document.get("code").getAsInt());
        assertEquals("Method not found", document.get("message").getAsString());
    }

    @Test
    public void errorMemberInsideResponseLeavesResultAbsent()
    {
        String json = GsonHolder.toJson(
            JsonRpcResponse.error(1, McpServerMeta.ERROR_INTERNAL, "Internal error"));
        JsonObject document = JsonParser.parseString(json).getAsJsonObject();

        assertNull(document.get("result"));
        assertEquals(McpServerMeta.ERROR_INTERNAL,
            document.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    public void standardErrorCodesAreAllNegative()
    {
        // They live in the JSON-RPC reserved range; a positive value here would be a wire-level bug.
        assertFalse(McpServerMeta.ERROR_PARSE >= 0);
        assertFalse(McpServerMeta.ERROR_INVALID_REQUEST >= 0);
        assertFalse(McpServerMeta.ERROR_METHOD_NOT_FOUND >= 0);
        assertFalse(McpServerMeta.ERROR_INVALID_PARAMS >= 0);
        assertFalse(McpServerMeta.ERROR_INTERNAL >= 0);
    }
}
