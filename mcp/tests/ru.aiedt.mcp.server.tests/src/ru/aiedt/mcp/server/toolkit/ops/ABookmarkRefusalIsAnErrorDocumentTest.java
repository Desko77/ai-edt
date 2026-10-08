/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A bookmark read that refuses answers an error document with {@code isError}, not a markdown
 * table whose first line begins with {@code **Error:**}.
 * <p>
 * A caller that reads the MCP envelope cannot see a refusal hidden in the content: it parses a
 * table that is not there and reports a defect in the server. The unknown project is the refusal
 * a test can reach without a workspace project of its own.
 * </p>
 */
public class ABookmarkRefusalIsAnErrorDocumentTest
{
    /** An unknown project answers a JSON error document. */
    @Test
    public void anUnknownProjectAnswersAnErrorDocument()
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("projectName", "NoSuchBookmarkProject"); //$NON-NLS-1$
        String answer = new BookmarksReader().execute(params);
        assertTrue("the answer is a JSON document: " + answer, answer.trim().startsWith("{")); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject parsed = JsonParser.parseString(answer).getAsJsonObject();
        assertFalse(parsed.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(parsed.get("error").getAsString().contains("NoSuchBookmarkProject")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("no **Error:** line hides in the content", answer.contains("**Error:**")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
