/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.aiedt.mcp.server.support.FailureShape;
import ru.aiedt.mcp.server.wire.jsonrpc.ToolCallResult;

/**
 * Holds the server to telling a client when a tool refused.
 * <p>
 * <b>It knew and did not say.</b> A tool reports refusal in its own body - a JSON answer carrying
 * success false, or text opening with Error - and the router already read exactly that, through
 * FailureShape, on every call. It used the reading for one thing: whether to count the call a
 * success in the history. The answer that went out said nothing, so a client had only the prose to
 * go by, and a refused write came close to being taken for a completed one.
 * </p>
 * <p>
 * <b>A YAML header cannot carry this.</b> The reading above matches on the first characters, so a
 * header in front of a refusal breaks it, and a header after the leading "Error:" is not a header.
 * </p>
 * <p>
 * The mark goes where every answer already passes and nothing else changes: no tool's text is
 * touched, the header stays as it is, and the checks that read a leading "Error:" keep working.
 * </p>
 */
public class RefusalReachesTheClientTest
{
    @Test
    public void anOrdinaryAnswerSaysNothingAboutFailure()
    {
        // Absent, not false: an answer that has always parsed one way must keep parsing that way.
        assertNull(ToolCallResult.text("done").getIsError()); //$NON-NLS-1$
    }

    @Test
    public void aMarkedAnswerCarriesTheFlag()
    {
        assertEquals(Boolean.TRUE, ToolCallResult.text("Error: no such project").asFailure() //$NON-NLS-1$
            .getIsError());
    }

    @Test
    public void markingReturnsTheSameAnswerRatherThanACopy()
    {
        // The router marks in place while shaping; a copy would drop the content built above it.
        ToolCallResult result = ToolCallResult.text("Error: no such project"); //$NON-NLS-1$
        assertEquals(result, result.asFailure());
        assertEquals(1, result.getContent().size());
    }

    @Test
    public void theReadingBehindTheMarkIsTheOneAlreadyInUse()
    {
        // Both shapes a tool can refuse in, and one that is not a refusal at all.
        assertTrue(FailureShape.looksFailed("{\"success\":false,\"error\":\"no\"}")); //$NON-NLS-1$
        assertTrue(FailureShape.looksFailed("Error: projectName must be supplied")); //$NON-NLS-1$
        assertTrue(FailureShape.looksFailed("Failed while writing the module")); //$NON-NLS-1$
        assertFalse(FailureShape.looksFailed("{\"success\":true}")); //$NON-NLS-1$
    }

    @Test
    public void aRefusalMentionedInPassingIsNotARefusal()
    {
        // The word can appear in an answer that succeeded - a listing of errors, a description of
        // one. The reading looks at the opening, not anywhere.
        assertFalse(FailureShape.looksFailed("Found 3 problems, one of them Error: missing type")); //$NON-NLS-1$
    }
}
