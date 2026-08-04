/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.IMcpTool.ResponseType;

/**
 * Unit tests for {@link QueryValidator}.
 * <p>
 * Covers the tool metadata, the schema (projectName and queryText declared required), and the two
 * required-parameter gates at the top of {@code execute}, which return a {@code success:false}
 * JSON document before any Eclipse resource is touched. The QlDcs parse and validation flow needs a
 * live EDT workspace and is not exercised here.
 * </p>
 */
public class QueryValidatorTest
{
    private static QueryValidator newTool()
    {
        return new QueryValidator();
    }

    private static Map<String, String> params(String... pairs)
    {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    public void nameIsValidateQuery()
    {
        assertEquals("validate_query", newTool().getName());
    }

    @Test
    public void responseTypeIsJson()
    {
        assertEquals(ResponseType.JSON, newTool().getResponseType());
    }

    @Test
    public void schemaDeclaresParametersAndRequiredPair()
    {
        String schema = newTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\""));
        assertTrue(schema.contains("\"queryText\""));
        assertTrue(schema.contains("\"dcsMode\""));
        assertTrue("projectName and queryText must both be required",
            schema.contains("\"required\":[\"projectName\",\"queryText\"]"));
    }

    @Test
    public void missingProjectNameReturnsFailedJson()
    {
        String result = newTool().execute(params("queryText", "SELECT 1"));
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("projectName is required"));
    }

    @Test
    public void missingQueryTextReturnsFailedJson()
    {
        String result = newTool().execute(params("projectName", "AnyProject"));
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("queryText is required"));
    }
}
