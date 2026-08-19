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

import com.google.gson.JsonArray;
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


    // ---- the 2026-07-28 era, and the promise that the older one is untouched ----

    /** A modern request carries its version in params._meta and needs no handshake first. */
    @Test
    public void aModernRequestIsServedWithoutAnyHandshake()
    {
        JsonObject result = send(request(1, McpServerMeta.METHOD_TOOLS_LIST, modernParams()))
            .getAsJsonObject("result");

        assertNotNull("a modern tools/list was not answered", result); //$NON-NLS-1$
        assertEquals("every modern result declares its kind", //$NON-NLS-1$
            "complete", result.get("resultType").getAsString());
        JsonObject meta = result.getAsJsonObject("_meta");
        assertNotNull("a modern result should identify the server", meta); //$NON-NLS-1$
        assertNotNull(meta.getAsJsonObject("io.modelcontextprotocol/serverInfo"));
    }

    /**
     * The promise this whole change rests on: a caller of the older revision sees exactly what it
     * saw before.
     * <p>
     * Not "sees something compatible" - sees no new fields at all. A client that has been parsing
     * this answer for a year did not ask for extra members, and the specification puts the burden
     * of tolerating an absent resultType on the CLIENT of the new revision, not on servers of the
     * old one.
     * </p>
     */
    @Test
    public void aLegacyRequestIsAnsweredExactlyAsBefore()
    {
        JsonObject result = send(request(1, McpServerMeta.METHOD_TOOLS_LIST, null))
            .getAsJsonObject("result");

        assertNotNull(result);
        assertNull("resultType must not appear for a caller of the older revision", //$NON-NLS-1$
            result.get("resultType"));
        assertNull("_meta must not appear for a caller of the older revision", //$NON-NLS-1$
            result.get("_meta"));
        assertNull("cache hints must not appear for a caller of the older revision", //$NON-NLS-1$
            result.get("ttlMs"));
        assertNull("cache hints must not appear for a caller of the older revision", //$NON-NLS-1$
            result.get("cacheScope"));
    }

    /**
     * The catalogue says how long it keeps, so a client need not fetch it on every reconnect.
     * <p>
     * This is the largest single answer the server sends - the whole tool catalogue, before the
     * client has made one call - and it is the same answer every time until somebody flips a preset.
     * </p>
     */
    @Test
    public void theCatalogueSaysHowLongItKeeps()
    {
        JsonObject result = send(request(1, McpServerMeta.METHOD_TOOLS_LIST, modernParams()))
            .getAsJsonObject("result");

        assertNotNull("the catalogue must say how long it keeps", result.get("ttlMs")); //$NON-NLS-1$
        assertTrue("a freshness window of zero is not a window", //$NON-NLS-1$
            result.get("ttlMs").getAsLong() > 0L);
        assertEquals("the catalogue reflects this workspace, so no shared cache may hold it", //$NON-NLS-1$
            "private", result.get("cacheScope").getAsString());
    }

    /**
     * Two identical requests produce byte-identical catalogues.
     * <p>
     * The registry keeps tools in a hash map, whose iteration order is not part of its contract and
     * shifts as the map grows. A caching client compares what it received with what it has; a model
     * whose prompt cache holds the catalogue loses the hit on the first differing byte. Both want
     * the same bytes, so the order is fixed on the way out.
     * </p>
     */
    @Test
    public void theCatalogueComesOutInTheSameOrderEveryTime()
    {
        registerScrambledCatalogue();

        String first =
            send(request(1, McpServerMeta.METHOD_TOOLS_LIST, null)).getAsJsonObject("result").toString();
        String second =
            send(request(2, McpServerMeta.METHOD_TOOLS_LIST, null)).getAsJsonObject("result").toString();

        assertEquals("the catalogue must be byte-identical between calls", first, second); //$NON-NLS-1$
    }

    /** And the fixed order is by name, so it is predictable rather than merely repeatable. */
    @Test
    public void theCatalogueIsOrderedByName()
    {
        registerScrambledCatalogue();

        JsonArray tools = send(request(1, McpServerMeta.METHOD_TOOLS_LIST, null))
            .getAsJsonObject("result").getAsJsonArray("tools");

        assertTrue("an empty catalogue proves nothing about its order", tools.size() > 1); //$NON-NLS-1$
        String previous = null;
        for (int i = 0; i < tools.size(); i++)
        {
            String name = tools.get(i).getAsJsonObject().get("name").getAsString();
            if (previous != null)
            {
                assertTrue("the catalogue is out of order at " + name + " after " + previous, //$NON-NLS-1$ //$NON-NLS-2$
                    previous.compareTo(name) < 0);
            }
            previous = name;
        }
    }

    /** server/discover answers whoever asks, because it is asked before the era is known. */
    @Test
    public void discoverNamesEveryServedVersionAndIdentifiesTheServer()
    {
        JsonObject result = send(request("d1", McpServerMeta.METHOD_SERVER_DISCOVER, modernParams()))
            .getAsJsonObject("result");

        assertNotNull("server/discover was not answered", result); //$NON-NLS-1$
        assertEquals("complete", result.get("resultType").getAsString());
        String versions = result.get("supportedVersions").toString();
        assertTrue("the current revision is not among the served ones: " + versions, //$NON-NLS-1$
            versions.contains(McpServerMeta.MODERN_PROTOCOL_VERSION));
        assertTrue("an older served revision disappeared: " + versions, //$NON-NLS-1$
            versions.contains(McpServerMeta.PROTOCOL_VERSION));
        assertNotNull("discovery must say what the server can do", //$NON-NLS-1$
            result.getAsJsonObject("capabilities"));
        assertNotNull("a cacheable result must say how long it keeps", result.get("ttlMs")); //$NON-NLS-1$
        assertEquals("the answer names this workspace, so no shared cache may hold it", //$NON-NLS-1$
            "private", result.get("cacheScope").getAsString());
    }

    /** Discovery answers a caller that named no version at all - that is what a probe looks like. */
    @Test
    public void discoverAnswersAProbeThatNamesNoVersion()
    {
        JsonObject response = send(request("d2", McpServerMeta.METHOD_SERVER_DISCOVER, null));

        assertNull("a probe must not be refused", response.get("error")); //$NON-NLS-1$
        assertNotNull(response.getAsJsonObject("result").get("supportedVersions"));
    }

    /**
     * A version nobody serves is refused with the versions that ARE served, so the caller has
     * somewhere to go.
     */
    @Test
    public void anUnservedVersionIsRefusedWithSomewhereToRetry()
    {
        String params = "{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"1900-01-01\","
            + "\"io.modelcontextprotocol/clientCapabilities\":{}}}";

        JsonObject error = send(request(1, McpServerMeta.METHOD_TOOLS_LIST, params))
            .getAsJsonObject("error");

        assertNotNull("an unserved version was not refused", error); //$NON-NLS-1$
        assertEquals(-32022, error.get("code").getAsInt());
        JsonObject data = error.getAsJsonObject("data");
        assertNotNull("the refusal must carry the served versions", data); //$NON-NLS-1$
        assertEquals("1900-01-01", data.get("requested").getAsString());
        assertTrue("the served versions are not listed: " + data, //$NON-NLS-1$
            data.get("supported").toString().contains(McpServerMeta.MODERN_PROTOCOL_VERSION));
    }

    /**
     * Declaring a version but omitting the capabilities is invalid params, NOT an unsupported
     * version - the caller must be sent to fix the field it left out.
     */
    @Test
    public void modernMetadataMissingARequiredFieldIsInvalidParams()
    {
        String params = "{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
            + McpServerMeta.MODERN_PROTOCOL_VERSION + "\"}}";

        JsonObject error = send(request(1, McpServerMeta.METHOD_TOOLS_LIST, params))
            .getAsJsonObject("error");

        assertNotNull(error);
        assertEquals(-32602, error.get("code").getAsInt());
        assertTrue("the message should name the field that is missing: " + error, //$NON-NLS-1$
            error.get("message").getAsString().contains("clientCapabilities"));
    }

    /**
     * Two eras on one connection, in either order, each answered in its own shape.
     * <p>
     * The point of deciding the era per request is that nothing is remembered between requests. A
     * server that latched onto the first caller it saw would serve the second one the wrong shape -
     * and would do it silently, because both shapes parse.
     * </p>
     */
    @Test
    public void erasDoNotLeakIntoEachOtherOnOneServer()
    {
        JsonObject legacyFirst = send(request(1, McpServerMeta.METHOD_TOOLS_LIST, null))
            .getAsJsonObject("result");
        JsonObject modern = send(request(2, McpServerMeta.METHOD_TOOLS_LIST, modernParams()))
            .getAsJsonObject("result");
        JsonObject legacyAgain = send(request(3, McpServerMeta.METHOD_TOOLS_LIST, null))
            .getAsJsonObject("result");

        assertNull("the first legacy answer was decorated", legacyFirst.get("resultType")); //$NON-NLS-1$
        assertNotNull("the modern answer between them lost its kind", modern.get("resultType")); //$NON-NLS-1$
        assertNull("a legacy caller was served the modern shape after a modern one asked", //$NON-NLS-1$
            legacyAgain.get("resultType"));
    }

    /**
     * A modern caller is served without ever having sent a handshake, twice in a row.
     * <p>
     * This is what stateless means in practice, and it is worth a test of its own: no ordering
     * requirement, no first call that unlocks the rest.
     * </p>
     */
    @Test
    public void aModernCallerNeedsNoFirstCall()
    {
        registry.register(stub("alpha", "Alpha tool", "{\"type\":\"object\"}")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        for (int attempt = 1; attempt <= 2; attempt++)
        {
            JsonObject result = send(request(attempt, McpServerMeta.METHOD_TOOLS_LIST, modernParams()))
                .getAsJsonObject("result");

            assertEquals("attempt " + attempt + " was not served", //$NON-NLS-1$ //$NON-NLS-2$
                1, result.getAsJsonArray("tools").size());
        }
    }

    private static String modernParams()
    {
        return "{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
            + McpServerMeta.MODERN_PROTOCOL_VERSION
            + "\",\"io.modelcontextprotocol/clientCapabilities\":{}}}";
    }

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

    /**
     * Fills the registry in an order that is not the answer.
     * <p>
     * Registration order is not iteration order - the registry keeps tools in a hash map - so this
     * does not by itself guarantee the unsorted reading would come out wrong. What it does
     * guarantee is that nothing along the way is quietly relying on the order things went in, and
     * with six names the odds of the hash landing them alphabetically by accident are small enough
     * that removing the sort gets caught.
     * </p>
     */
    private void registerScrambledCatalogue()
    {
        for (String name : new String[] {"zulu", "alpha", "mike", "bravo", "yankee", "charlie"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        {
            registry.register(stub(name, name + " tool", "{\"type\":\"object\"}")); //$NON-NLS-1$ //$NON-NLS-2$
        }
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
