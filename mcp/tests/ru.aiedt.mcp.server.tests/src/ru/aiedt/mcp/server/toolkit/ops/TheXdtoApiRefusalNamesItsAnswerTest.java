/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The XDTO API refusal names the call that answers availability, and that call answers it.
 * <p>
 * The refusal used to say only that the API is not reachable - a dead end, because nothing in it
 * said where the state is even reported. The facade's help ends with an {@code XDTO API} line, so
 * the refusal names that help and carries it as a hint.
 * </p>
 */
public class TheXdtoApiRefusalNamesItsAnswerTest
{
    /** The refusal names the help call, and hands it over ready to send. */
    @Test
    public void theRefusalNamesTheHelpCallAndCarriesItAsAHint()
    {
        JsonObject body = JsonParser.parseString(XdtoWorkshopTool.apiNotReachable())
            .getAsJsonObject();

        assertEquals(Boolean.FALSE, body.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue("the message must name the call that answers availability: " + body, //$NON-NLS-1$
            body.get("error").getAsString().contains("operation=help")); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject hint = body.getAsJsonObject("helpHint"); //$NON-NLS-1$
        assertEquals("xdto_workshop", hint.get("tool").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("help", hint.getAsJsonObject("arguments").get("operation").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** The call the refusal names really answers the availability it is named for. */
    @Test
    public void theHelpTheRefusalNamesAnswersAvailability()
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject body = JsonParser.parseString(new XdtoWorkshopTool().execute(params))
            .getAsJsonObject();

        assertEquals(Boolean.TRUE, body.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue("the help must answer the API line the refusal sends the caller to: " //$NON-NLS-1$
            + body.get("help"),
            body.get("help").getAsString().contains("XDTO API:")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
