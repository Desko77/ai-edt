/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire.jsonrpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.aiedt.mcp.server.wire.GsonHolder;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Checks the four shapes of a {@code tools/call} answer (text, structured, textual resource, binary
 * resource), the content-block discriminator that picks between text and resource, and the tools
 * catalogue DTO a {@code tools/list} response is built from.
 */
public class ToolCallResultTest
{
    // ---- ToolCallResult ----

    @Test
    public void textFactoryProducesOneTextBlockAndNoStructuredPayload()
    {
        ToolCallResult result = ToolCallResult.text("report body");

        assertEquals(1, result.getContent().size());
        assertEquals("text", result.getContent().get(0).getType());
        assertEquals("report body", result.getContent().get(0).getText());
        assertNull(result.getContent().get(0).getResource());
        assertNull(result.getStructuredContent());
    }

    @Test
    public void jsonFactoryKeepsThePayloadAndAddsAPlaceholderBlock()
    {
        JsonElement payload = JsonParser.parseString("{\"count\":42}");
        ToolCallResult result = ToolCallResult.json(payload);

        assertEquals("Done", result.getContent().get(0).getText());
        assertEquals(payload, result.getStructuredContent());
    }

    @Test
    public void resourceFactoryWrapsATextualDocument()
    {
        ToolCallResult result = ToolCallResult.resource("embedded://out.md", "text/markdown", "# H");

        ToolCallResult.ContentItem item = result.getContent().get(0);
        assertEquals("resource", item.getType());
        assertNull(item.getText());
        ToolCallResult.ResourceInfo resource = item.getResource();
        assertNotNull(resource);
        assertEquals("embedded://out.md", resource.getUri());
        assertEquals("text/markdown", resource.getMimeType());
        assertEquals("# H", resource.getText());
        assertNull(resource.getBlob());
    }

    @Test
    public void resourceBlobFactoryWrapsABinaryDocument()
    {
        ToolCallResult result =
            ToolCallResult.resourceBlob("embedded://shot.png", "image/png", "AAAB");

        ToolCallResult.ResourceInfo resource = result.getContent().get(0).getResource();
        assertNull(resource.getText());
        assertEquals("AAAB", resource.getBlob());
    }

    @Test
    public void textResultSerializesToOneTextContentItem()
    {
        String json = GsonHolder.toJson(ToolCallResult.text("hi"));
        JsonObject document = JsonParser.parseString(json).getAsJsonObject();

        JsonObject item = document.getAsJsonArray("content").get(0).getAsJsonObject();
        assertEquals("text", item.get("type").getAsString());
        assertEquals("hi", item.get("text").getAsString());
    }

    @Test
    public void jsonResultSerializesStructuredContentAndPlaceholder()
    {
        JsonElement payload = JsonParser.parseString("{\"ok\":true}");
        String json = GsonHolder.toJson(ToolCallResult.json(payload));
        JsonObject document = JsonParser.parseString(json).getAsJsonObject();

        assertNotNull(document.get("structuredContent"));
        assertEquals("Done",
            document.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    public void resourceBlockOmitsTheAbsentPayloadMember()
    {
        String json = GsonHolder.toJson(
            ToolCallResult.resource("embedded://x.md", "text/markdown", "body"));
        JsonObject document = JsonParser.parseString(json).getAsJsonObject();

        JsonObject resource = document.getAsJsonArray("content").get(0)
            .getAsJsonObject().getAsJsonObject("resource");
        assertNotNull(resource.get("text"));
        assertNull(resource.get("blob"));
    }

    // ---- ToolsListResult ----

    @Test
    public void emptyCatalogueStartsWithNoTools()
    {
        ToolsListResult catalogue = new ToolsListResult();
        assertNotNull(catalogue.getTools());
        assertTrue(catalogue.getTools().isEmpty());
    }

    @Test
    public void addToolAppendsAnEntryWithItsThreeFields()
    {
        ToolsListResult catalogue = new ToolsListResult();
        JsonElement schema = JsonParser.parseString("{\"type\":\"object\"}");
        catalogue.addTool("get_edt_version", "Reports the EDT build", schema);

        assertEquals(1, catalogue.getTools().size());
        ToolsListResult.ToolInfo entry = catalogue.getTools().get(0);
        assertEquals("get_edt_version", entry.getName());
        assertEquals("Reports the EDT build", entry.getDescription());
        assertEquals(schema, entry.getInputSchema());
    }

    @Test
    public void catalogueSerializesToAToolsArray()
    {
        ToolsListResult catalogue = new ToolsListResult();
        catalogue.addTool("t1", "First", JsonParser.parseString("{\"type\":\"object\"}"));
        catalogue.addTool("t2", "Second", JsonParser.parseString("{\"type\":\"object\"}"));

        JsonObject document = JsonParser.parseString(
            GsonHolder.toJson(catalogue)).getAsJsonObject();

        assertEquals(2, document.getAsJsonArray("tools").size());
        assertEquals("t1",
            document.getAsJsonArray("tools").get(0).getAsJsonObject().get("name").getAsString());
    }
}
