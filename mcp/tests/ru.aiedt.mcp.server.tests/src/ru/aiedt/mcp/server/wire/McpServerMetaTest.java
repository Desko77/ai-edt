/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.regex.Pattern;

import org.junit.Test;

/**
 * Checks the wire-format literals the server advertises to MCP clients and the JSON-RPC error codes
 * it answers with.
 */
public class McpServerMetaTest
{
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}"); //$NON-NLS-1$

    @Test
    public void advertisedRevisionIsAnIsoDate()
    {
        assertNotNull(McpServerMeta.PROTOCOL_VERSION);
        assertTrue("revision must look like YYYY-MM-DD", //$NON-NLS-1$
            ISO_DATE.matcher(McpServerMeta.PROTOCOL_VERSION).matches());
    }

    @Test
    public void jsonRpcDialectIsTwoPointZero()
    {
        assertEquals("2.0", McpServerMeta.JSONRPC_VERSION);
    }

    @Test
    public void serverIdentityIsTheAiEdtName()
    {
        assertEquals("ai-edt-mcp-server", McpServerMeta.SERVER_NAME);
    }

    @Test
    public void pluginVersionIsEitherResolvedOrTheStatedFallback()
    {
        // The suite runs both ways - inside the OSGi harness, where the bundle answers with its
        // version, and on a plain classpath, where there is no bundle to ask. Pinning either
        // outcome would break in the other environment; what has to hold everywhere is that the
        // resolver returns something printable instead of null or an empty string.
        String version = McpServerMeta.PLUGIN_VERSION;
        assertNotNull(version);
        assertFalse("version must never be blank", version.isBlank()); //$NON-NLS-1$
        if (!"unknown".equals(version)) //$NON-NLS-1$
        {
            assertTrue("a resolved version has to start with a digit, got: " + version, //$NON-NLS-1$
                Character.isDigit(version.charAt(0)));
        }
    }

    @Test
    public void errorCodesMatchTheJsonRpcReservedRange()
    {
        assertEquals(-32700, McpServerMeta.ERROR_PARSE);
        assertEquals(-32600, McpServerMeta.ERROR_INVALID_REQUEST);
        assertEquals(-32601, McpServerMeta.ERROR_METHOD_NOT_FOUND);
        assertEquals(-32602, McpServerMeta.ERROR_INVALID_PARAMS);
        assertEquals(-32603, McpServerMeta.ERROR_INTERNAL);
    }

    @Test
    public void methodNamesAreTheFourTheServerImplements()
    {
        assertEquals("initialize", McpServerMeta.METHOD_INITIALIZE);
        assertEquals("notifications/initialized", McpServerMeta.METHOD_INITIALIZED);
        assertEquals("tools/list", McpServerMeta.METHOD_TOOLS_LIST);
        assertEquals("tools/call", McpServerMeta.METHOD_TOOLS_CALL);
    }

    @Test
    public void transportHeaderNamesAreMcpPrefixed()
    {
        assertEquals("MCP-Protocol-Version", McpServerMeta.HEADER_PROTOCOL_VERSION);
        assertEquals("MCP-Session-Id", McpServerMeta.HEADER_SESSION_ID);
    }
}
