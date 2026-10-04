/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.MetadataGuards;

/**
 * A guard refusal that reaches {@code edit_metadata}'s own handler - thrown by an operation, or
 * wrapped by the model on its way out of a write task - answers with its hint and its structured
 * tag, not with its message alone.
 */
public class AGuardRefusalKeepsItsTagThroughEditMetadataTest
{
    /**
     * @return a refusal carrying a supportLock tag
     */
    private static MetadataGuards.BlockedGuardException refusal()
    {
        MetadataGuards.ErrorTag tag = new MetadataGuards.ErrorTag("supportLock") //$NON-NLS-1$
            .put("object", "Catalog.Goods"); //$NON-NLS-1$ //$NON-NLS-2$
        return new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
            "Catalog.Goods is on vendor support", "Change its support mode first", tag)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * @param json an answer
     * @return it parsed
     */
    private static JsonObject parsed(String json)
    {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** A refusal thrown by the operation keeps its tag and hint. */
    @Test
    public void aThrownRefusalKeepsItsTag()
    {
        JsonObject answer = parsed(EditMetadataTool.answerFor("add_attribute", refusal())); //$NON-NLS-1$

        assertEquals("Catalog.Goods", //$NON-NLS-1$
            answer.getAsJsonObject("supportLock").get("object").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Change its support mode first", answer.get("hint").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("add_attribute", answer.get("operation").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A refusal the model wrapped on its way out is found under the wrapper. */
    @Test
    public void aWrappedRefusalKeepsItsTag()
    {
        JsonObject answer = parsed(EditMetadataTool.answerFor("add_attribute", //$NON-NLS-1$
            new RuntimeException("task failed", refusal()))); //$NON-NLS-1$

        assertTrue(answer.toString(), answer.has("supportLock")); //$NON-NLS-1$
    }

    /** Any other failure answers with its message and no tag. */
    @Test
    public void anotherFailureAnswersWithItsMessage()
    {
        JsonObject answer = parsed(EditMetadataTool.answerFor("add_attribute", //$NON-NLS-1$
            new IllegalStateException("no such owner"))); //$NON-NLS-1$

        assertTrue(answer.toString(), answer.toString().contains("no such owner")); //$NON-NLS-1$
        assertFalse(answer.toString(), answer.has("supportLock")); //$NON-NLS-1$
    }
}
