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

import ru.aiedt.mcp.server.support.PendingWorkRegistry;
import ru.aiedt.mcp.server.support.TaskDirectory;
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
    /** The key the stub slow tool reports, seeded into the generic domain by the tests using it. */
    private static final String PENDING_RUN_KEY = "aiedt-test-pending-run"; //$NON-NLS-1$

    /** Held down while a seeded run must look unfinished; released when the test ends. */
    private final java.util.concurrent.CountDownLatch seededRunRelease =
        new java.util.concurrent.CountDownLatch(1);

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
        seededRunRelease.countDown();
        PendingWorkRegistry.GENERIC.remove(PENDING_RUN_KEY);
        TaskDirectory.getInstance().clear();
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

    /**
     * The handshake agrees to every revision that HAS a handshake - and to no others.
     * <p>
     * The current revision has none: {@code initialize} was removed from it. Agreeing to it here
     * would name a revision in which the question just asked does not exist, which is a claim made
     * without looking - the kind this project keeps finding and removing.
     * </p>
     */
    @Test
    public void initializeAgreesToEveryHandshakeRevisionAndNoOther()
    {
        String modern = send(request(1, McpServerMeta.METHOD_INITIALIZE,
            "{\"protocolVersion\":\"" + McpServerMeta.MODERN_PROTOCOL_VERSION + "\"}")) //$NON-NLS-1$ //$NON-NLS-2$
                .getAsJsonObject("result").get("protocolVersion").getAsString();
        assertEquals("the handshake agreed to a revision that has no handshake", //$NON-NLS-1$
            McpServerMeta.PROTOCOL_VERSION, modern);

        for (String revision : McpServerMeta.HANDSHAKE_PROTOCOL_VERSIONS)
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

    // ---- what a declared capability promises ----

    @Test
    public void theTemplatesHalfOfResourcesIsAnsweredRatherThanRefused()
    {
        // The handshake declares the resources capability, so a client may call every method of
        // it. Answering one with "method not found" makes the server look broken to a client that
        // reads the declaration and asks - and a discovery pass that fails shows no tools at all.
        JsonObject result = send(request(1, McpServerMeta.METHOD_RESOURCES_TEMPLATES_LIST, null))
            .getAsJsonObject("result");

        assertNotNull("a declared capability answers its own methods", result); //$NON-NLS-1$
        assertTrue("the answer carries the list, empty though it is", //$NON-NLS-1$
            result.has("resourceTemplates")); //$NON-NLS-1$
        assertEquals("this server holds no resource templates", //$NON-NLS-1$
            0, result.getAsJsonArray("resourceTemplates").size()); //$NON-NLS-1$
    }

    @Test
    public void promptsAreAnsweredEmptyForAClientThatProbes()
    {
        JsonObject result = send(request(1, McpServerMeta.METHOD_PROMPTS_LIST, null))
            .getAsJsonObject("result");

        assertNotNull("a client that asks before reading the capabilities gets an answer", //$NON-NLS-1$
            result);
        assertEquals("there are none, which is not the same as a missing method", //$NON-NLS-1$
            0, result.getAsJsonArray("prompts").size()); //$NON-NLS-1$
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

    // ---- tasks, and who is allowed to be handed one ----

    /**
     * A slow tool's pending answer becomes a task for a caller that says it understands tasks.
     */
    @Test
    public void aPendingAnswerBecomesATaskForACallerThatAskedForOne()
    {
        seedPendingRun();
        registry.register(stub("slow", "Slow tool", "{\"type\":\"object\"}", pendingEnvelope())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        JsonObject result = send(toolCall(1, "slow", null, tasksParams())) //$NON-NLS-1$
            .getAsJsonObject("result");

        assertEquals("a task handle must say it is not the answer", //$NON-NLS-1$
            "task", result.get("resultType").getAsString());
        assertNotNull("a handle without an id is not a handle", result.get("taskId")); //$NON-NLS-1$
        assertEquals("working", result.get("status").getAsString());
        assertNotNull("a client needs to know how often to ask", result.get("pollIntervalMs")); //$NON-NLS-1$
        assertNotNull("and how long the handle is good for", result.get("ttlMs")); //$NON-NLS-1$
    }

    /**
     * The same call from a caller that did not declare the extension gets the runKey answer it has
     * always got.
     * <p>
     * This is the rule the specification states outright - never hand a task to a client that did
     * not ask for one - and it is also the only behaviour that keeps every existing client working.
     * </p>
     */
    @Test
    public void aCallerThatDidNotAskForTasksStillGetsItsRunKey()
    {
        seedPendingRun();
        registry.register(stub("slow", "Slow tool", "{\"type\":\"object\"}", pendingEnvelope())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        for (String params : new String[] {null, modernParams()})
        {
            JsonObject result = send(toolCall(1, "slow", null, params)).getAsJsonObject("result"); //$NON-NLS-1$

            assertNull("a caller that did not declare tasks was handed one", //$NON-NLS-1$
                result.get("taskId"));
            assertTrue("the runKey answer went missing: " + result, //$NON-NLS-1$
                result.toString().contains("runKey")); //$NON-NLS-1$
        }
    }

    /** An ordinary answer is left alone even when the caller understands tasks. */
    @Test
    public void anOrdinaryAnswerIsNotDressedUpAsATask()
    {
        registry.register(stub("quick", "Quick tool", "{\"type\":\"object\"}", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"success\":true,\"status\":\"Done\"}")); //$NON-NLS-1$

        JsonObject result = send(toolCall(1, "quick", null, tasksParams())).getAsJsonObject("result"); //$NON-NLS-1$

        assertNull(result.get("taskId"));
        assertEquals("complete", result.get("resultType").getAsString());
    }

    /** Polling a task reports where it has got to. */
    @Test
    public void aTaskCanBePolled()
    {
        seedPendingRun();
        registry.register(stub("slow", "Slow tool", "{\"type\":\"object\"}", pendingEnvelope())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String taskId = send(toolCall(1, "slow", null, tasksParams())) //$NON-NLS-1$
            .getAsJsonObject("result").get("taskId").getAsString();

        JsonObject result = send(request(2, McpServerMeta.METHOD_TASKS_GET,
            "{\"taskId\":\"" + taskId + "\"," + tasksMeta() + "}")).getAsJsonObject("result"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("a poll is itself a finished answer, about a task", //$NON-NLS-1$
            "complete", result.get("resultType").getAsString());
        assertEquals(taskId, result.get("taskId").getAsString());
        assertNotNull("a poll must say where the task has got to", result.get("status")); //$NON-NLS-1$
    }

    /** An id nobody handed out is invalid params, naming the id. */
    @Test
    public void pollingAnUnknownTaskIsInvalidParams()
    {
        JsonObject error = send(request(1, McpServerMeta.METHOD_TASKS_GET,
            "{\"taskId\":\"never-issued\"," + tasksMeta() + "}")).getAsJsonObject("error"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(-32602, error.get("code").getAsInt());
        assertTrue("the message should name the id: " + error, //$NON-NLS-1$
            error.get("message").getAsString().contains("never-issued"));
    }

    /** Cancelling acknowledges with an empty result, as the extension requires. */
    @Test
    public void cancellingATaskIsAcknowledgedAndNothingMore()
    {
        seedPendingRun();
        registry.register(stub("slow", "Slow tool", "{\"type\":\"object\"}", pendingEnvelope())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String taskId = send(toolCall(1, "slow", null, tasksParams())) //$NON-NLS-1$
            .getAsJsonObject("result").get("taskId").getAsString();

        JsonObject result = send(request(2, McpServerMeta.METHOD_TASKS_CANCEL,
            "{\"taskId\":\"" + taskId + "\"," + tasksMeta() + "}")).getAsJsonObject("result"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("complete", result.get("resultType").getAsString());
        assertNull("an acknowledgement carries nothing else", result.get("status")); //$NON-NLS-1$

        JsonObject polled = send(request(3, McpServerMeta.METHOD_TASKS_GET,
            "{\"taskId\":\"" + taskId + "\"," + tasksMeta() + "}")).getAsJsonObject("result"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("cancelled", polled.get("status").getAsString());
    }

    /** The task methods do not exist for a caller of the older revision, and say so. */
    @Test
    public void theTaskMethodsDoNotExistInTheOlderRevision()
    {
        JsonObject error = send(request(1, McpServerMeta.METHOD_TASKS_GET, "{\"taskId\":\"x\"}")) //$NON-NLS-1$
            .getAsJsonObject("error");

        assertEquals(McpServerMeta.ERROR_METHOD_NOT_FOUND, error.get("code").getAsInt());
    }

    /** Discovery advertises the extension, which is how a client knows it may ask for a task. */
    @Test
    public void discoveryAdvertisesTheTasksExtension()
    {
        JsonObject capabilities = send(request("d", McpServerMeta.METHOD_SERVER_DISCOVER, modernParams())) //$NON-NLS-1$
            .getAsJsonObject("result").getAsJsonObject("capabilities");

        JsonObject extensions = capabilities.getAsJsonObject("extensions");
        assertNotNull("a server that implements an extension has to say so", extensions); //$NON-NLS-1$
        assertTrue("the tasks extension is not advertised: " + extensions, //$NON-NLS-1$
            extensions.has(McpServerMeta.EXTENSION_TASKS));
    }

    /**
     * A pending answer names a run that is really going, so the seed puts one there.
     * <p>
     * Without it the key belongs to no domain and the server declines to build a task over it -
     * which is the right refusal, and would make these tests prove the opposite of what they mean.
     * </p>
     */
    private void seedPendingRun()
    {
        // Held open until the test ends. A run that finishes the instant it starts is not a slow
        // run, and a task over it is completed before anybody asks - which is correct behaviour
        // and useless for testing what a handle to UNFINISHED work says.
        PendingWorkRegistry.GENERIC.getOrStart(PENDING_RUN_KEY, () -> {
            try
            {
                seededRunRelease.await(10, java.util.concurrent.TimeUnit.SECONDS);
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
            }
            return "the slow answer"; //$NON-NLS-1$
        });
    }

    /** A task over a key nobody owns is refused, because it could never be answered. */
    @Test
    public void noTaskIsBuiltOverAKeyNoDomainOwns()
    {
        registry.register(stub("orphan", "Orphan", "{\"type\":\"object\"}", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"success\":true,\"operation\":\"orphan\",\"status\":\"Pending\",\"" //$NON-NLS-1$
                + ru.aiedt.mcp.server.support.PendingEnvelope.MARK //$NON-NLS-1$
                + "\":true,\"runKey\":\"belongs-to-nobody\"}")); //$NON-NLS-1$

        JsonObject result = send(toolCall(1, "orphan", null, tasksParams())).getAsJsonObject("result"); //$NON-NLS-1$

        assertNull("a task was opened over a key that can never be redeemed", result.get("taskId")); //$NON-NLS-1$
        assertTrue("and the caller lost its key with it: " + result, //$NON-NLS-1$
            result.toString().contains("belongs-to-nobody")); //$NON-NLS-1$
    }

    /**
     * A pending envelope exactly as a producer writes one, mark and all.
     * <p>
     * The mark is what the router recognises. Written here through the same constant the producers
     * use, so a test cannot pass on an envelope no real tool would emit - and so that renaming the
     * member breaks this in the compiler rather than at run time.
     * </p>
     *
     * @return the envelope
     */
    private static String pendingEnvelope()
    {
        return "{\"success\":true,\"operation\":\"slow\",\"status\":\"Pending\",\"" //$NON-NLS-1$
            + ru.aiedt.mcp.server.support.PendingEnvelope.MARK + "\":true,\"runKey\":\"" //$NON-NLS-1$
            + PENDING_RUN_KEY + "\",\"elapsedMs\":10,\"waitedMs\":10}"; //$NON-NLS-1$
    }

    private static String tasksMeta()
    {
        return "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"" //$NON-NLS-1$
            + McpServerMeta.MODERN_PROTOCOL_VERSION
            + "\",\"io.modelcontextprotocol/clientCapabilities\":{\"extensions\":{\"" //$NON-NLS-1$
            + McpServerMeta.EXTENSION_TASKS + "\":{}}}}"; //$NON-NLS-1$
    }

    private static String tasksParams()
    {
        return "{" + tasksMeta() + "}"; //$NON-NLS-1$ //$NON-NLS-2$
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

    private static IMcpTool stub(String name, String description, String schema, String answer)
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
                return answer;
            }
        };
    }

    /**
     * A tools/call document that can also carry per-request metadata.
     *
     * @param id the request id.
     * @param toolName the tool to call.
     * @param argumentsJson the arguments object, or null for none.
     * @param extraParamsJson a params object whose members are merged in, or null.
     * @return the request document
     */
    private static String toolCall(Object id, String toolName, String argumentsJson,
        String extraParamsJson)
    {
        StringBuilder params = new StringBuilder("{\"name\":\""); //$NON-NLS-1$
        params.append(toolName).append("\",\"arguments\":"); //$NON-NLS-1$
        params.append(argumentsJson != null ? argumentsJson : "{}"); //$NON-NLS-1$
        if (extraParamsJson != null && extraParamsJson.length() > 2)
        {
            params.append(',').append(extraParamsJson, 1, extraParamsJson.length() - 1);
        }
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
