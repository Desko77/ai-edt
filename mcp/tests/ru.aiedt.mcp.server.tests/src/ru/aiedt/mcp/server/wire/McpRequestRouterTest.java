/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;

/**
 * Exercises the JSON-RPC front door of the server: the four recognized methods, the error codes for
 * malformed or unknown requests, and the argument flattening that keeps a whole number whole on its
 * way to a tool.
 * <p>
 * Runs without an OSGi workbench. The tool-call success path is reachable because every touch on the
 * running server null-guards the activator; only paths that need a live workbench (plain-text mode,
 * user signals) are out of reach here.
 * </p>
 */
public class McpRequestRouterTest
{
    private McpRequestRouter handler;
    private McpToolCatalog registry;

    @Before
    public void registerHandler()
    {
        registry = McpToolCatalog.getInstance();
        registry.clear();
        handler = new McpRequestRouter();
    }

    @After
    public void clearRegistry()
    {
        registry.clear();
    }

    // ---- initialize ----

    @Test
    public void initializeAnswersWithRevisionCapabilitiesAndIdentity()
    {
        JsonObject response = send(request(1, McpServerMeta.METHOD_INITIALIZE, null));
        JsonObject result = response.getAsJsonObject("result");

        assertEquals("2.0", response.get("jsonrpc").getAsString());
        assertNotNull(result.get("protocolVersion"));
        assertNotNull(result.get("capabilities"));
        JsonObject serverInfo = result.getAsJsonObject("serverInfo");
        assertNotNull(serverInfo.get("name"));
        assertNotNull(serverInfo.get("version"));
    }

    @Test
    public void initializeEchoesTheRevisionTheClientAskedFor()
    {
        String params = "{\"protocolVersion\":\"2025-06-18\"}"; //$NON-NLS-1$
        JsonObject result = send(request(1, McpServerMeta.METHOD_INITIALIZE, params))
            .getAsJsonObject("result");

        assertEquals("2025-06-18", result.get("protocolVersion").getAsString());
    }

    /**
     * A revision nobody has published is answered with the one this server implements, not
     * agreed to.
     * <p>
     * Any string shaped like a date used to come straight back as the agreed revision, so a
     * client asking for {@code 1999-01-01} was told this server speaks it. That is not a
     * negotiation - it is a claim made without looking, and the client has no way to find out
     * otherwise until something it expects is missing.
     * </p>
     */
    @Test
    public void initializeRefusesARevisionItDoesNotImplement()
    {
        String params = "{\"protocolVersion\":\"1999-01-01\"}"; //$NON-NLS-1$
        JsonObject result = send(request(1, McpServerMeta.METHOD_INITIALIZE, params))
            .getAsJsonObject("result");

        assertEquals(McpServerMeta.PROTOCOL_VERSION, result.get("protocolVersion").getAsString());
    }

    /** Every revision on the supported list is agreed to, so a client pinned to one keeps working. */
    @Test
    public void initializeAgreesToEveryRevisionItClaimsToSupport()
    {
        for (String revision : McpServerMeta.SUPPORTED_PROTOCOL_VERSIONS)
        {
            String params = "{\"protocolVersion\":\"" + revision + "\"}"; //$NON-NLS-1$ //$NON-NLS-2$
            JsonObject result = send(request(1, McpServerMeta.METHOD_INITIALIZE, params))
                .getAsJsonObject("result");

            assertEquals("a supported revision was not agreed to", //$NON-NLS-1$
                revision, result.get("protocolVersion").getAsString());
        }
    }

    /** The server names itself by the workspace it has open, for a machine running several. */
    @Test
    public void initializeCarriesATitleThatNamesThisInstance()
    {
        JsonObject serverInfo = send(request(1, McpServerMeta.METHOD_INITIALIZE, null))
            .getAsJsonObject("result").getAsJsonObject("serverInfo");

        assertNotNull("serverInfo carries no title", serverInfo.get("title")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the title does not name the product: " + serverInfo.get("title"), //$NON-NLS-1$ //$NON-NLS-2$
            serverInfo.get("title").getAsString().startsWith("AI-EDT")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void initializeFallsBackToItsOwnRevisionWhenNoneWasAskedFor()
    {
        JsonObject result = send(request(1, McpServerMeta.METHOD_INITIALIZE, null))
            .getAsJsonObject("result");

        assertEquals(McpServerMeta.PROTOCOL_VERSION, result.get("protocolVersion").getAsString());
    }

    @Test
    public void initializeFallsBackWhenTheAskedRevisionIsNotAnIsoDate()
    {
        String params = "{\"protocolVersion\":\"banana\"}"; //$NON-NLS-1$
        JsonObject result = send(request(1, McpServerMeta.METHOD_INITIALIZE, params))
            .getAsJsonObject("result");

        assertEquals(McpServerMeta.PROTOCOL_VERSION, result.get("protocolVersion").getAsString());
    }

    @Test
    public void numericRequestIdIsEchoedBack()
    {
        JsonObject response = send(request(42, McpServerMeta.METHOD_INITIALIZE, null));
        assertEquals(42, response.get("id").getAsInt());
    }

    @Test
    public void zeroRequestIdStaysWholeNotFractional()
    {
        // Clients that sent id:0 used to receive id:0.0, which no longer matched the request.
        String document = handler.processRequest(request(0, McpServerMeta.METHOD_INITIALIZE, null));

        assertFalse("zero id must not decay to 0.0", document.contains("\"id\":0.0")); //$NON-NLS-1$
        assertEquals(0, JsonParser.parseString(document).getAsJsonObject().get("id").getAsInt());
    }

    @Test
    public void stringRequestIdIsEchoedBack()
    {
        String document = handler.processRequest(
            "{\"jsonrpc\":\"2.0\",\"id\":\"abc-123\",\"method\":\"initialize\"}"); //$NON-NLS-1$
        assertEquals("abc-123",
            JsonParser.parseString(document).getAsJsonObject().get("id").getAsString());
    }

    // ---- notifications/initialized ----

    @Test
    public void initializedNotificationHasNoAnswer()
    {
        assertNull(handler.processRequest(
            request(1, McpServerMeta.METHOD_INITIALIZED, null)));
    }

    // ---- tools/list ----

    @Test
    public void emptyCatalogueHasNoTools()
    {
        JsonObject result = send(request(1, McpServerMeta.METHOD_TOOLS_LIST, null))
            .getAsJsonObject("result");

        assertEquals(0, result.getAsJsonArray("tools").size());
    }

    @Test
    public void catalogueListsEveryEnabledToolWithItsThreeFields()
    {
        registry.register(stub("alpha", "Alpha tool", "{\"type\":\"object\"}")); //$NON-NLS-1$
        registry.register(stub("beta", "Beta tool", //$NON-NLS-1$
            "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}")); //$NON-NLS-1$

        JsonObject result = send(request(1, McpServerMeta.METHOD_TOOLS_LIST, null))
            .getAsJsonObject("result");

        assertEquals(2, result.getAsJsonArray("tools").size());
        for (JsonElement entry : result.getAsJsonArray("tools"))
        {
            JsonObject tool = entry.getAsJsonObject();
            assertNotNull(tool.get("name"));
            assertNotNull(tool.get("description"));
            assertNotNull(tool.get("inputSchema"));
        }
    }

    // ---- malformed and unknown requests ----

    @Test
    public void wrongJsonRpcVersionIsAnInvalidRequest()
    {
        JsonObject response = send("{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"initialize\"}"); //$NON-NLS-1$

        assertEquals(McpServerMeta.ERROR_INVALID_REQUEST,
            response.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    public void missingJsonRpcVersionIsAnInvalidRequest()
    {
        JsonObject response = send("{\"id\":1,\"method\":\"initialize\"}"); //$NON-NLS-1$

        assertNotNull(response.get("error"));
    }

    @Test
    public void unknownMethodIsMethodNotFound()
    {
        JsonObject response = send(request(1, "no/such/method", null)); //$NON-NLS-1$

        assertEquals(McpServerMeta.ERROR_METHOD_NOT_FOUND,
            response.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    public void bodyThatIsNotJsonAtAllIsAparseError()
    {
        JsonObject response = send("definitely-not-json <<<");

        assertEquals(McpServerMeta.ERROR_PARSE,
            response.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    public void truncatedJsonIsAparseErrorRatherThanAnInvalidRequest()
    {
        JsonObject response = send("{\"jsonrpc\":\"2.0\",");

        assertEquals(McpServerMeta.ERROR_PARSE,
            response.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    public void wellFormedJsonThatIsNotARequestIsAnInvalidRequest()
    {
        // An object that never claims to be JSON-RPC 2.0.
        JsonObject object = send("{\"foo\":1}");
        assertEquals(McpServerMeta.ERROR_INVALID_REQUEST,
            object.getAsJsonObject("error").get("code").getAsInt());

        // A JSON array is well-formed JSON but not a request object either.
        JsonObject array = send("[]");
        assertEquals(McpServerMeta.ERROR_INVALID_REQUEST,
            array.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    public void emptyOrNullBodyIsRejected()
    {
        assertNotNull(send("").get("error"));
        assertNotNull(send((String)null).get("error"));
    }

    // ---- tools/call ----

    @Test
    public void callingAnUnknownToolReportsMethodNotFoundAndNamesIt()
    {
        JsonObject response = send(call(1, "ghost_tool", null)); //$NON-NLS-1$

        assertEquals(McpServerMeta.ERROR_METHOD_NOT_FOUND,
            response.getAsJsonObject("error").get("code").getAsInt());
        assertTrue(response.getAsJsonObject("error").get("message").getAsString()
            .contains("ghost_tool"));
    }

    @Test
    public void callingWithoutAToolNameIsRejected()
    {
        String document = request(1, McpServerMeta.METHOD_TOOLS_CALL, "{\"arguments\":{}}"); //$NON-NLS-1$

        assertNotNull(send(document).get("error"));
    }

    @Test
    public void wholeNumberArgumentsReachTheToolWithoutADecimalPoint()
    {
        ArgumentCapturingTool probe = new ArgumentCapturingTool();
        registry.register(probe);

        handler.processRequest(call(1, "probe", //$NON-NLS-1$
            "{\"limit\":42,\"ratio\":1.5,\"items\":[{\"lineNumber\":7,\"hitCount\":3}]}")); //$NON-NLS-1$

        assertEquals("42", probe.captured.get("limit"));
        assertEquals("1.5", probe.captured.get("ratio"));
        assertEquals("[{\"lineNumber\":7,\"hitCount\":3}]", probe.captured.get("items"));
    }

    // ---- helpers ----

    private JsonObject send(String document)
    {
        return JsonParser.parseString(handler.processRequest(document)).getAsJsonObject();
    }

    private static String request(Object id, String method, String paramsJson)
    {
        StringBuilder builder = new StringBuilder("{\"jsonrpc\":\"2.0\""); //$NON-NLS-1$
        if (id instanceof String)
        {
            builder.append(",\"id\":\"").append(id).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            builder.append(",\"id\":").append(id); //$NON-NLS-1$
        }
        builder.append(",\"method\":\"").append(method).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
        if (paramsJson != null)
        {
            builder.append(",\"params\":").append(paramsJson); //$NON-NLS-1$
        }
        return builder.append('}').toString();
    }

    private static String call(Object id, String toolName, String argumentsJson)
    {
        StringBuilder params = new StringBuilder("{\"name\":\"").append(toolName).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
        params.append(",\"arguments\":"); //$NON-NLS-1$
        params.append(argumentsJson != null ? argumentsJson : "{}"); //$NON-NLS-1$
        return request(id, McpServerMeta.METHOD_TOOLS_CALL, params.append('}').toString());
    }

    private static IMcpTool stub(String name, String description, String schema)
    {
        return new IMcpTool()
        {
            @Override
            public String getName()
            {
                return name;
            }

            @Override
            public String getDescription()
            {
                return description;
            }

            @Override
            public String getInputSchema()
            {
                return schema;
            }

            @Override
            public String execute(Map<String, String> params)
            {
                return "{}"; //$NON-NLS-1$
            }
        };
    }

    /** A tool that remembers the flattened argument map the handler handed it. */
    private static final class ArgumentCapturingTool implements IMcpTool
    {
        volatile Map<String, String> captured;

        @Override
        public String getName()
        {
            return "probe"; //$NON-NLS-1$
        }

        @Override
        public String getDescription()
        {
            return "Captures arguments for the test"; //$NON-NLS-1$
        }

        @Override
        public String getInputSchema()
        {
            return "{\"type\":\"object\",\"properties\":{}}"; //$NON-NLS-1$
        }

        @Override
        public String execute(Map<String, String> params)
        {
            captured = params;
            return "ok"; //$NON-NLS-1$
        }
    }
}
