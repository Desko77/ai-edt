/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.google.gson.JsonParser;

/**
 * Checks the fluent input-schema builder: the type it seeds, the property definitions it declares,
 * the required list it accumulates and the two render shapes it offers.
 */
public class SchemaComposerTest
{
    @Test
    public void anEmptySchemaIsAnObjectWithNoPropertiesAndNoRequiredFields()
    {
        String schema = SchemaComposer.object().build();

        assertTrue(schema.contains("\"type\":\"object\""));
        assertTrue(schema.contains("\"properties\":{}"));
        assertTrue(schema.contains("\"required\":[]"));
    }

    @Test
    public void anOptionalStringPropertyCarriesItsDescriptionButNotTheRequiredFlag()
    {
        String schema = SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project to work in")
            .build();

        assertTrue(schema.contains("\"projectName\""));
        assertTrue(schema.contains("\"type\":\"string\""));
        assertTrue(schema.contains("\"description\":\"Name of the EDT project to work in\""));
        assertTrue(schema.contains("\"required\":[]"));
    }

    @Test
    public void aRequiredStringPropertyAppearsInTheRequiredArray()
    {
        String schema = SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project to work in", true)
            .build();

        assertTrue(schema.contains("\"required\":[\"projectName\"]"));
    }

    @Test
    public void anIntegerPropertyUsesTheIntegerJsonSchemaType()
    {
        String schema = SchemaComposer.object()
            .integerProperty("limit", "Row cap")
            .build();

        assertTrue(schema.contains("\"limit\""));
        assertTrue(schema.contains("\"type\":\"integer\""));
    }

    @Test
    public void aBooleanPropertyUsesTheBooleanJsonSchemaType()
    {
        String schema = SchemaComposer.object()
            .booleanProperty("verbose", "Print detail")
            .build();

        assertTrue(schema.contains("\"verbose\""));
        assertTrue(schema.contains("\"type\":\"boolean\""));
    }

    @Test
    public void aStringArrayPropertyDeclaresStringItemsAndMayBeRequired()
    {
        String schema = SchemaComposer.object()
            .stringArrayProperty("targets", "FQN list", true)
            .build();

        assertTrue(schema.contains("\"targets\""));
        assertTrue(schema.contains("\"type\":\"array\""));
        assertTrue(schema.contains("\"items\""));
        assertTrue(schema.contains("\"required\":[\"targets\"]"));
        // Items are typed as string inside the items member.
        var items = JsonParser.parseString(schema).getAsJsonObject()
            .getAsJsonObject("properties").getAsJsonObject("targets").getAsJsonObject("items");
        assertEquals("string", items.get("type").getAsString());
    }

    @Test
    public void severalPropertiesKeepTheirDeclarationOrder()
    {
        String schema = SchemaComposer.object()
            .stringProperty("projectName", "Project", true)
            .stringProperty("modulePath", "Path", true)
            .integerProperty("limit", "Cap")
            .booleanProperty("caseSensitive", "Case")
            .build();

        int first = schema.indexOf("\"projectName\"");
        int second = schema.indexOf("\"modulePath\"");
        int third = schema.indexOf("\"limit\"");
        int fourth = schema.indexOf("\"caseSensitive\"");

        assertTrue("properties keep insertion order", first < second && second < third && third < fourth);
        assertTrue(schema.contains("\"required\":[\"projectName\",\"modulePath\"]"));
    }

    @Test
    public void buildMapHandsBackAnEmbeddableCopyWithoutDrainingTheBuilder()
    {
        Map<String, Object> map = SchemaComposer.object()
            .stringProperty("name", "Identifier")
            .buildMap();

        assertEquals("object", map.get("type"));
        assertNotNull(map.get("properties"));
        assertNotNull(map.get("required"));

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>)map.get("required");
        assertTrue(required.isEmpty());
    }
}
