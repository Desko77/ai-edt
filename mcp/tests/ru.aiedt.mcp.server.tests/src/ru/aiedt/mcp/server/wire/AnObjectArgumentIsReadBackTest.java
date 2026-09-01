/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * What comes back from an argument that carries a JSON object.
 * <p>
 * A tool is handed text, so a nested object arrives as its compact JSON form. Reading it back is
 * what lets {@code create_object} take a set of properties in one call instead of one argument per
 * property, which is why a scheduled job could be created with a name and nothing else.
 * </p>
 */
public class AnObjectArgumentIsReadBackTest
{
    private static Map<String, String> withProperties(String raw)
    {
        Map<String, String> params = new HashMap<>();
        if (raw != null)
        {
            params.put("properties", raw);
        }
        return params;
    }

    @Test
    public void membersComeBackAsText()
    {
        Map<String, String> read = JsonUtils.extractObjectArgument(
            withProperties("{\"methodName\":\"CommonModule.A.B\",\"use\":false,\"restarts\":3}"),
            "properties");
        assertEquals(3, read.size());
        assertEquals("CommonModule.A.B", read.get("methodName"));
        assertEquals("false", read.get("use"));
        assertEquals("3", read.get("restarts"));
    }

    /** A refusal names the properties in the order they were written, not in a hash order. */
    @Test
    public void theOrderGivenIsKept()
    {
        Map<String, String> read = JsonUtils.extractObjectArgument(
            withProperties("{\"zebra\":\"1\",\"alpha\":\"2\",\"middle\":\"3\"}"), "properties");
        assertEquals(new ArrayList<>(java.util.Arrays.asList("zebra", "alpha", "middle")),
            new ArrayList<>(read.keySet()));
    }

    /**
     * A member that is itself an object or an array has no meaning for a property setter, and
     * handing it over would pass a string of JSON as the value.
     */
    @Test
    public void aNestedMemberIsLeftOut()
    {
        Map<String, String> read = JsonUtils.extractObjectArgument(
            withProperties("{\"use\":true,\"nested\":{\"a\":1},\"list\":[1,2]}"), "properties");
        assertEquals(1, read.size());
        assertTrue(read.containsKey("use"));
    }

    @Test
    public void nothingGivenReadsAsNothingAsked()
    {
        assertTrue(JsonUtils.extractObjectArgument(withProperties(null), "properties").isEmpty());
        assertTrue(JsonUtils.extractObjectArgument(withProperties(""), "properties").isEmpty());
        assertTrue(JsonUtils.extractObjectArgument(withProperties("   "), "properties").isEmpty());
    }

    /**
     * Text that is not an object carries nothing usable. Guessing a shape here would invent
     * properties nobody wrote and apply them to the object.
     */
    @Test
    public void whatIsNotAnObjectYieldsNothing()
    {
        assertTrue(JsonUtils.extractObjectArgument(withProperties("[1,2]"), "properties").isEmpty());
        assertTrue(JsonUtils.extractObjectArgument(withProperties("plain text"), "properties")
            .isEmpty());
        assertTrue(JsonUtils.extractObjectArgument(withProperties("{broken"), "properties")
            .isEmpty());
    }
}
