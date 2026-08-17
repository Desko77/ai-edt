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

    @Test
    public void demoteTurnsAnAssembledSuccessIntoAFailureKeepingItsFields()
    {
        // For a write that happened but did not do what was asked. The fields describing
        // what WAS done are exactly what the caller needs to put it right, so they stay.
        String json = ToolResult.success()
            .put("operation", "add_form_attribute")
            .put("attributeName", "Дерево")
            .demote("type 'ДеревоЗначений' was not applied")
            .toJson();

        assertTrue(json.contains("\"success\": false") || json.contains("\"success\":false"));
        // Matched without the apostrophes: Gson escapes them by default, so a phrase
        // carrying quotes never appears verbatim in the serialized form.
        assertTrue(json.contains("was not applied"));
        assertTrue(json.contains("ДеревоЗначений"));
        assertTrue(json.contains("add_form_attribute"));
        assertTrue(json.contains("Дерево"));
    }

    @Test
    public void demoteLeavesSuccessLeadingTheAnswer()
    {
        // The backing map is insertion-ordered and demote replaces the value rather than
        // re-adding the key, so a reader still meets the verdict first.
        String json = ToolResult.success().put("operation", "batch").demote("2 of 3 failed").toJson();

        assertTrue(json.indexOf("success") < json.indexOf("operation"));
    }

    @Test
    public void demoteWithoutAMessageStillFlipsTheVerdict()
    {
        String json = ToolResult.success().demote(null).toJson();

        assertTrue(json.contains("\"success\": false") || json.contains("\"success\":false"));
        assertFalse(json.contains("error"));
    }
}
