/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Covers the two helper families in {@link JsonUtils}: the JSON envelopes served outside the
 * tool-call path, and the argument extractors tools read their parameters through.
 */
public class JsonUtilsTest
{
    // ---- string argument ----

    @Test
    public void stringArgumentComesBackVerbatimWhenPresent()
    {
        Map<String, String> params = new HashMap<>();
        params.put("project", "Demo");
        assertEquals("Demo", JsonUtils.extractStringArgument(params, "project"));
    }

    @Test
    public void stringArgumentIsNullWhenMissingOrWhenParamsAreAbsent()
    {
        assertNull(JsonUtils.extractStringArgument(new HashMap<>(), "missing"));
        assertNull(JsonUtils.extractStringArgument(null, "anything"));
        assertNull(JsonUtils.extractStringArgument(new HashMap<>(), null));
    }

    // ---- boolean argument ----

    @Test
    public void booleanArgumentRecognizesTrueSpellings()
    {
        Map<String, String> params = new HashMap<>();
        params.put("a", "true");
        params.put("b", "yes");
        params.put("c", "1");
        assertTrue(JsonUtils.extractBooleanArgument(params, "a", false));
        assertTrue(JsonUtils.extractBooleanArgument(params, "b", false));
        assertTrue(JsonUtils.extractBooleanArgument(params, "c", false));
    }

    @Test
    public void booleanArgumentRecognizesFalseSpellings()
    {
        Map<String, String> params = new HashMap<>();
        params.put("a", "false");
        params.put("b", "no");
        params.put("c", "0");
        assertFalse(JsonUtils.extractBooleanArgument(params, "a", true));
        assertFalse(JsonUtils.extractBooleanArgument(params, "b", true));
        assertFalse(JsonUtils.extractBooleanArgument(params, "c", true));
    }

    @Test
    public void booleanArgumentFallsBackToDefaultForMissingOrUnrecognized()
    {
        Map<String, String> params = new HashMap<>();
        params.put("garbage", "maybe");
        assertTrue(JsonUtils.extractBooleanArgument(params, "garbage", true));
        assertFalse(JsonUtils.extractBooleanArgument(params, "missing", false));
        assertTrue(JsonUtils.extractBooleanArgument(null, "flag", true));
    }

    @Test
    public void nullableBooleanKeepsAbsentDistinctFromFalse()
    {
        assertNull(JsonUtils.extractBooleanArgumentNullable(null, "flag"));
        assertNull(JsonUtils.extractBooleanArgumentNullable(new HashMap<>(), "flag"));
        Map<String, String> params = new HashMap<>();
        params.put("on", "TRUE");
        params.put("off", "No");
        assertEquals(Boolean.TRUE, JsonUtils.extractBooleanArgumentNullable(params, "on"));
        assertEquals(Boolean.FALSE, JsonUtils.extractBooleanArgumentNullable(params, "off"));
    }

    // ---- int argument ----

    @Test
    public void intArgumentAcceptsAWholeNumber()
    {
        Map<String, String> params = new HashMap<>();
        params.put("limit", "100");
        assertEquals(100, JsonUtils.extractIntArgument(params, "limit", 0));
    }

    @Test
    public void intArgumentAcceptsTheTrailingZeroFormClientsProduce()
    {
        Map<String, String> params = new HashMap<>();
        params.put("limit", "100.0");
        assertEquals(100, JsonUtils.extractIntArgument(params, "limit", 0));
    }

    @Test
    public void intArgumentRejectsFractionalGarbledAndEmptyValues()
    {
        Map<String, String> params = new HashMap<>();
        params.put("frac", "1.5");
        params.put("word", "abc");
        params.put("blank", "");
        assertEquals(7, JsonUtils.extractIntArgument(params, "frac", 7));
        assertEquals(7, JsonUtils.extractIntArgument(params, "word", 7));
        assertEquals(7, JsonUtils.extractIntArgument(params, "blank", 7));
        assertEquals(7, JsonUtils.extractIntArgument(null, "limit", 7));
    }

    @Test
    public void integerArgumentDistinguishesSuppliedZeroFromAbsent()
    {
        Map<String, String> params = new HashMap<>();
        params.put("zero", "0");
        assertEquals(Integer.valueOf(0), JsonUtils.extractIntegerArgument(params, "zero"));
        assertNull(JsonUtils.extractIntegerArgument(params, "missing"));
        assertNull(JsonUtils.extractIntegerArgument(null, "zero"));
    }

    // ---- long argument ----

    @Test
    public void longArgumentAcceptsLargeWholeNumbers()
    {
        // 2^53 is the largest whole number a double can represent exactly; the extractor
        // routes through double, so anything above this loses precision. Assert the largest
        // value that still round-trips, proving large whole numbers are accepted.
        Map<String, String> params = new HashMap<>();
        params.put("offset", "9007199254740992");
        assertEquals(9007199254740992L, JsonUtils.extractLongArgument(params, "offset", 0L));
    }

    @Test
    public void longArgumentFallsBackWhenFractional()
    {
        Map<String, String> params = new HashMap<>();
        params.put("offset", "3.14");
        assertEquals(-1L, JsonUtils.extractLongArgument(params, "offset", -1L));
    }

    // ---- array argument ----

    @Test
    public void arrayArgumentReadsAJsonArrayLiteral()
    {
        Map<String, String> params = new HashMap<>();
        params.put("targets", "[\"Catalog.A\",\"Document.B\"]");
        List<String> values = JsonUtils.extractArrayArgument(params, "targets");

        assertEquals(2, values.size());
        assertEquals("Catalog.A", values.get(0));
        assertEquals("Document.B", values.get(1));
    }

    @Test
    public void arrayArgumentReadsACommaSeparatedList()
    {
        Map<String, String> params = new HashMap<>();
        params.put("targets", " Catalog.A , Document.B ,, ");
        List<String> values = JsonUtils.extractArrayArgument(params, "targets");

        assertEquals(2, values.size());
        assertEquals("Catalog.A", values.get(0));
        assertEquals("Document.B", values.get(1));
    }

    @Test
    public void arrayArgumentIsNullForAbsentEmptyOrBlankValues()
    {
        assertNull(JsonUtils.extractArrayArgument(null, "targets"));
        assertNull(JsonUtils.extractArrayArgument(new HashMap<>(), "missing"));
        Map<String, String> params = new HashMap<>();
        params.put("blank", "");
        params.put("spaces", "   ");
        assertNull(JsonUtils.extractArrayArgument(params, "blank"));
        assertNull(JsonUtils.extractArrayArgument(params, "spaces"));
    }

    @Test
    public void arrayArgumentSkipsNonPrimitiveEntriesOfAJsonArray()
    {
        Map<String, String> params = new HashMap<>();
        params.put("targets", "[\"keep\",{\"drop\":true},[1]]");
        List<String> values = JsonUtils.extractArrayArgument(params, "targets");
        assertEquals(1, values.size());
        assertEquals("keep", values.get(0));
    }

    // ---- operation token normalization ----

    @Test
    public void camelCaseTokenIsRewrittenInSnakeCase()
    {
        // A single uppercase boundary is the documented mechanism...
        assertEquals("text_search", JsonUtils.normalizeOperationToken("textSearch"));
        // ...and consecutive capitals each get their own underscore (the SUT splits per
        // uppercase letter rather than grouping an acronym into one word).
        assertEquals("add_u_r_l_template", JsonUtils.normalizeOperationToken("addURLTemplate"));
    }

    @Test
    public void snakeCaseTokenPassesThroughUnchanged()
    {
        assertEquals("already_snake", JsonUtils.normalizeOperationToken("already_snake"));
    }

    @Test
    public void blankOrNullTokenHandling()
    {
        assertNull(JsonUtils.normalizeOperationToken(null));
        assertEquals("", JsonUtils.normalizeOperationToken(""));
        assertEquals("", JsonUtils.normalizeOperationToken("   "));
    }

    // ---- envelope builders ----

    @Test
    public void jsonRpcErrorEnvelopeCarriesVersionCodeAndMessage()
    {
        String document = JsonUtils.buildJsonRpcError(-32600, "Bad request", Integer.valueOf(1));

        assertTrue(document.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(document.contains("\"code\":-32600"));
        assertTrue(document.contains("\"message\":\"Bad request\""));
        assertTrue(document.contains("\"id\":1"));
    }

    @Test
    public void jsonRpcErrorEnvelopeSubstitutesForMissingMessage()
    {
        String document = JsonUtils.buildJsonRpcError(-32603, null, 1);
        assertTrue(document.contains("\"message\":\"Unknown error\""));
    }

    @Test
    public void jsonRpcErrorEnvelopeEchoesStringIds()
    {
        String document = JsonUtils.buildJsonRpcError(-32600, "x", "req-9");
        assertTrue(document.contains("\"id\":\"req-9\""));
    }

    @Test
    public void jsonRpcErrorEnvelopeOmitsIdWhenNoneIsSupplied()
    {
        String document = JsonUtils.buildJsonRpcError(-32600, "x", null);
        assertFalse(document.contains("\"id\""));
    }

    @Test
    public void simpleErrorWrapsTheReason()
    {
        assertTrue(JsonUtils.buildSimpleError("denied").contains("\"error\":\"denied\""));
        assertTrue(JsonUtils.buildSimpleError(null).contains("\"error\":\"Unknown error\""));
    }

    @Test
    public void serverInfoPayloadExposesNameVersionAndRunningStatus()
    {
        String document = JsonUtils.buildServerInfo("srv", "3.1", "2026.1", "2025-11-25",
            java.util.Arrays.asList("2026-07-28", "2025-11-25"));

        assertTrue(document.contains("\"name\":\"srv\""));
        assertTrue(document.contains("\"version\":\"3.1\""));
        assertTrue(document.contains("\"edt_version\":\"2026.1\""));
        assertTrue(document.contains("\"protocol_version\":\"2025-11-25\""));
        assertTrue(document.contains("\"status\":\"running\""));
    }

    /**
     * The scalar named one revision while five were served, and a client picking from it would
     * never reach the current one. Both members are published: the scalar is what a handshake
     * settles on, the list is what the server actually serves.
     */
    @Test
    public void serverInfoNamesEveryRevisionItServes()
    {
        String document = JsonUtils.buildServerInfo("srv", "3.1", "2026.1",
            McpServerMeta.PROTOCOL_VERSION, McpServerMeta.SUPPORTED_PROTOCOL_VERSIONS);

        for (String revision : McpServerMeta.SUPPORTED_PROTOCOL_VERSIONS)
        {
            assertTrue("every served revision must be named: " + revision + " in " + document,
                document.contains('"' + revision + '"'));
        }
        assertTrue("the current revision is the one a reader would otherwise miss",
            document.contains('"' + McpServerMeta.MODERN_PROTOCOL_VERSION + '"'));
    }

    /** No list is better than an empty one, which would read as "it serves no revision at all". */
    @Test
    public void anAbsentListIsLeftOutRatherThanPublishedEmpty()
    {
        assertFalse(JsonUtils.buildServerInfo("srv", "3.1", "2026.1", "2025-11-25", null)
            .contains("protocol_versions"));
        assertFalse(JsonUtils
            .buildServerInfo("srv", "3.1", "2026.1", "2025-11-25", java.util.Collections.emptyList())
            .contains("protocol_versions"));
    }

    @Test
    public void healthPayloadReportsOkAndTheEdtRevision()
    {
        String document = JsonUtils.buildHealthResponse("2026.1");
        assertTrue(document.contains("\"status\":\"ok\""));
        assertTrue(document.contains("\"edt_version\":\"2026.1\""));
    }
}
