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

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Checks the {@link ToolResult} builder: the two factory seeds, the typed {@code put} overloads,
 * chaining, the null-drops-key contract and the static serializer shortcut.
 */
public class ToolResultTest
{
    private JsonObject parsed(String json)
    {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    public void successSeedCarriesOnlyTheTrueFlag()
    {
        JsonObject document = parsed(ToolResult.success().toJson());

        assertTrue(document.get("success").getAsBoolean());
        assertNull(document.get("error"));
    }

    @Test
    public void errorSeedCarriesFalseFlagAndReason()
    {
        JsonObject document = parsed(ToolResult.error("Project missing").toJson());

        assertFalse(document.get("success").getAsBoolean());
        assertEquals("Project missing", document.get("error").getAsString());
    }

    @Test
    public void putIntEmitsANumberMember()
    {
        assertEquals(42, parsed(ToolResult.success().put("count", 42).toJson())
            .get("count").getAsInt());
    }

    @Test
    public void putLongEmitsANumberMember()
    {
        assertEquals(123456789L, parsed(ToolResult.success().put("big", 123456789L).toJson())
            .get("big").getAsLong());
    }

    @Test
    public void putBooleanEmitsABooleanMember()
    {
        assertTrue(parsed(ToolResult.success().put("active", true).toJson())
            .get("active").getAsBoolean());
    }

    @Test
    public void putStringEmitsAStringMember()
    {
        assertEquals("Demo", parsed(ToolResult.success().put("name", "Demo").toJson())
            .get("name").getAsString());
    }

    @Test
    public void putListEmitsAnArrayMember()
    {
        JsonObject document = parsed(ToolResult.success()
            .put("rows", Arrays.asList("a", "b", "c")).toJson());

        assertEquals(3, document.getAsJsonArray("rows").size());
    }

    @Test
    public void chainedPutsOrderMembersAfterTheSeed()
    {
        JsonObject document = parsed(ToolResult.success()
            .put("first", 1)
            .put("second", "two")
            .put("third", true)
            .toJson());

        assertTrue(document.get("success").getAsBoolean());
        assertEquals(1, document.get("first").getAsInt());
        assertEquals("two", document.get("second").getAsString());
        assertTrue(document.get("third").getAsBoolean());
    }

    @Test
    public void aNullValueDropsItsKeyRatherThanWritingNull()
    {
        String json = ToolResult.success().put("absent", (String)null).toJson();

        assertFalse(json.contains("absent"));
    }

    @Test
    public void toJsonStaticSerializesAnyTree()
    {
        List<Integer> numbers = Arrays.asList(1, 2, 3);
        String json = ToolResult.toJsonStatic(numbers);

        assertTrue(json.startsWith("["));
        assertTrue(json.contains("1"));
        assertTrue(json.contains("3"));
    }
}
