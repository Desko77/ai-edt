/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.google.gson.JsonParser;

/**
 * Covers the line that rides beside a structured answer.
 * <p>
 * MCP makes the content array mandatory even when the payload is structured, and for a long time that
 * array held the word "Done". A client that renders content and ignores structured output therefore
 * showed one word for every answer this server gave - and the agents driving those clients read it as
 * "the tool returned nothing". The case that made it costly was not a missing result but a missing
 * refusal: a call rejected with an explanation of which argument was wrong arrived as "Done", and the
 * caller concluded the tool was broken rather than the call.
 * </p>
 */
public class PayloadSummaryTest
{
    private static String summarize(String json)
    {
        return PayloadSummary.of(JsonParser.parseString(json));
    }

    @Test
    public void aRefusalShowsWhyItWasRefused()
    {
        // The whole point. This exact refusal was reported as "the tool answers Done and nothing else".
        String line = summarize("{\"success\": false, \"error\": \"ownerFqn must be 'Configuration' " //$NON-NLS-1$
            + "or 'Subsystem.<name>'\", \"operation\": \"get_command_interface\"}"); //$NON-NLS-1$

        assertTrue(line, line.contains("ownerFqn")); //$NON-NLS-1$
        assertTrue(line, line.contains("Subsystem")); //$NON-NLS-1$
    }

    @Test
    public void aRefusalWithoutAMessageStillSaysItFailed()
    {
        String line = summarize("{\"success\": false}"); //$NON-NLS-1$

        assertTrue(line, line.toLowerCase().contains("failed")); //$NON-NLS-1$
        assertFalse(line, "Done".equals(line)); //$NON-NLS-1$
    }

    @Test
    public void aResultNamesTheOperationAndItsShape()
    {
        String line = summarize("{\"success\": true, \"operation\": \"validate_query\", " //$NON-NLS-1$
            + "\"valid\": true, \"resultTables\": [{\"columns\": []}], \"issues\": []}"); //$NON-NLS-1$

        assertTrue(line, line.startsWith("validate_query: ")); //$NON-NLS-1$
        assertTrue(line, line.contains("valid=true")); //$NON-NLS-1$
        // A collection is worth its size at a glance; the contents are in the structured block.
        assertTrue(line, line.contains("resultTables=1")); //$NON-NLS-1$
        assertTrue(line, line.contains("issues=0")); //$NON-NLS-1$
    }

    @Test
    public void theSuccessFlagIsNotPrinted()
    {
        // It says nothing the rest of the line does not: a failure prints its reason instead.
        assertFalse(summarize("{\"success\": true, \"valid\": true}").contains("success")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theWholePayloadIsNotRepeated()
    {
        // Repeating it would double every answer for a client that reads both blocks, and a metadata
        // answer runs to tens of kilobytes.
        StringBuilder big = new StringBuilder("{\"success\": true, \"operation\": \"x\", \"rows\": ["); //$NON-NLS-1$
        for (int i = 0; i < 500; i++)
        {
            big.append(i > 0 ? "," : "").append("{\"name\": \"item").append(i).append("\"}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        big.append("]}"); //$NON-NLS-1$

        String line = summarize(big.toString());

        assertTrue(line, line.contains("rows=500")); //$NON-NLS-1$
        assertTrue("the summary is " + line.length() + " characters long", line.length() <= 400); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aLongMessageIsCutRatherThanDropped()
    {
        StringBuilder message = new StringBuilder();
        for (int i = 0; i < 60; i++)
        {
            message.append("very long explanation "); //$NON-NLS-1$
        }
        String line = summarize("{\"success\": false, \"error\": \"" + message + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(line, line.startsWith("very long explanation")); //$NON-NLS-1$
        assertTrue(line, line.endsWith("...")); //$NON-NLS-1$
        assertTrue(line, line.length() <= 400);
    }

    @Test
    public void theLineNeverBreaksAcrossLines()
    {
        // It goes into a single content block; a newline there turns one answer into what looks like
        // several.
        String line = summarize("{\"success\": false, \"error\": \"first\\nsecond\"}"); //$NON-NLS-1$

        assertFalse(line, line.contains("\n")); //$NON-NLS-1$
        assertTrue(line, line.contains("first") && line.contains("second")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anArrayAnswerSaysHowManyItemsItHas()
    {
        assertEquals("3 items", summarize("[1, 2, 3]")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("1 item", summarize("[1]")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anEmptyAnswerFallsBackRatherThanGoingBlank()
    {
        // The content block may not be empty, so something has to be said even about nothing.
        assertEquals("Done", summarize("{}")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Done", PayloadSummary.of(null)); //$NON-NLS-1$
        assertEquals("Done", summarize("null")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anOperatorSignalReachesTheContentOnlyClientToo()
    {
        // The signal is the operator asking for something mid-call. Folded into the payload as a
        // nested object, it would be skipped like every other nested object - and skipped precisely
        // for the clients this whole summary exists to serve.
        String line = summarize("{\"success\": true, \"operation\": \"update_database\", " //$NON-NLS-1$
            + "\"userSignal\": {\"type\": \"CANCEL\", \"message\": \"stop after this step\"}}"); //$NON-NLS-1$

        assertTrue(line, line.contains("USER SIGNAL")); //$NON-NLS-1$
        assertTrue(line, line.contains("CANCEL")); //$NON-NLS-1$
        assertTrue(line, line.contains("stop after this step")); //$NON-NLS-1$
    }

    @Test
    public void aSignalSurvivesAFailedAnswer()
    {
        // A signal that arrived while the call was failing is the one most worth reading, and the
        // failure path returns early - so it has to be appended after that decision, not before.
        String line = summarize("{\"success\": false, \"error\": \"could not connect\", " //$NON-NLS-1$
            + "\"userSignal\": {\"type\": \"RETRY\", \"message\": \"try once more\"}}"); //$NON-NLS-1$

        assertTrue(line, line.contains("could not connect")); //$NON-NLS-1$
        assertTrue(line, line.contains("USER SIGNAL")); //$NON-NLS-1$
        assertTrue(line, line.contains("RETRY")); //$NON-NLS-1$
    }

    @Test
    public void aSignalIsNotCutOffTheEndOfALongLine()
    {
        // Appended after clipping: the request from the operator must not be the part that falls off.
        StringBuilder message = new StringBuilder();
        for (int i = 0; i < 60; i++)
        {
            message.append("long failure text "); //$NON-NLS-1$
        }
        String line = summarize("{\"success\": false, \"error\": \"" + message + "\", " //$NON-NLS-1$ //$NON-NLS-2$
            + "\"userSignal\": {\"type\": \"EXPERT\", \"message\": \"ask the user\"}}"); //$NON-NLS-1$

        assertTrue(line, line.contains("USER SIGNAL: EXPERT - ask the user")); //$NON-NLS-1$
    }

    @Test
    public void anEmptySignalAddsNothing()
    {
        String line = summarize("{\"success\": true, \"valid\": true, \"userSignal\": {}}"); //$NON-NLS-1$

        assertFalse(line, line.contains("USER SIGNAL")); //$NON-NLS-1$
        assertTrue(line, line.contains("valid=true")); //$NON-NLS-1$
    }

    @Test
    public void anAnswerWithOnlyAnOperationStillReadsAsASentence()
    {
        String line = summarize("{\"success\": true, \"operation\": \"clean_project\"}"); //$NON-NLS-1$

        assertTrue(line, line.startsWith("clean_project")); //$NON-NLS-1$
        assertFalse(line, line.endsWith(": ")); //$NON-NLS-1$
    }
}
