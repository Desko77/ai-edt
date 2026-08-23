/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Holds a failed test run to being reported as one.
 * <p>
 * The facade declares JSON and the run-mode runner answers markdown, so its answers are wrapped
 * into an envelope. Wrapping every one of them as a success is the obvious way to do that and the
 * wrong one: the runner reports a refusal as markdown too, and a structured client would then read
 * {@code success:true} over a run that never started. A bare call with no project named is exactly
 * that case, and it is the first call anyone makes.
 * </p>
 * <p>
 * This is the defect this repository has paid for repeatedly under another name - an answer that
 * says the operation succeeded while nothing happened. Fixing the shape of the answer must not
 * introduce it.
 * </p>
 */
public class RunnerFailureStaysAFailureTest
{
    @Test
    public void aBareCallReportsFailureRatherThanAnEmptySuccess()
    {
        // No projectName and no launch configuration: the runner refuses before anything is
        // started. What comes back has to say so in the field a client reads.
        JsonObject answer = call(new LinkedHashMap<>());

        assertTrue("the answer must be a JSON object at all - it used to fail to parse", //$NON-NLS-1$
            answer != null);
        assertFalse("a run that never started is not a success", //$NON-NLS-1$
            answer.has("success") && answer.get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theRefusalKeepsItsText()
    {
        // The wrapping exists so the caller can read the answer. An envelope that reported the
        // failure and dropped what it said would trade one unreadable answer for another.
        JsonObject answer = call(new LinkedHashMap<>());

        String text = answer.toString();
        assertTrue("the reason has to survive the envelope: " + text, //$NON-NLS-1$
            text.contains("projectName") || text.contains("launchConfigurationName")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anUnknownModeIsStillRefusedAsJson()
    {
        Map<String, String> arguments = new LinkedHashMap<>();
        arguments.put("mode", "sideways"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject answer = call(arguments);

        assertFalse(answer.has("success") && answer.get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("yaxunit_tests", answer.get("operation").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Calls the tool and parses what it returns.
     *
     * @param arguments the call shape.
     * @return the answer as an object; the parse itself is part of what is under test
     */
    private static JsonObject call(Map<String, String> arguments)
    {
        String answer = new YaxunitTestsTool().execute(arguments);
        return JsonParser.parseString(answer).getAsJsonObject();
    }
}
