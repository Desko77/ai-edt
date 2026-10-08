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
 * A {@code limit} that is not a whole number, or one below 1, is refused by name instead of being
 * silently replaced with the default.
 * <p>
 * The reader used to fall back to 100 on a number it could not parse and to pass a negative value
 * through the minimum, so a caller that sent {@code 5.0} or {@code -5} read an answer built for a
 * limit it never asked for.
 * </p>
 */
public class ALimitThatIsNotAWholeNumberIsRefusedTest
{
    /**
     * One call to the reader with the given limit.
     *
     * @param limit the limit as the caller would write it
     * @return the answer as parsed JSON
     */
    private static JsonObject call(String limit)
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("projectName", "AnyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("tags", "[\"Important\"]"); //$NON-NLS-1$
        params.put("limit", limit);
        return JsonParser.parseString(new TaggedObjectsReader().execute(params)).getAsJsonObject();
    }

    /** A limit that is not a whole number is refused with the value the caller sent. */
    @Test
    public void aFractionalLimitIsRefusedWithItsValue()
    {
        JsonObject answer = call("5.0"); //$NON-NLS-1$
        assertFalse(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer.get("error").getAsString().contains("5.0")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A limit below 1 is refused rather than clamped away by the minimum. */
    @Test
    public void aLimitBelowOneIsRefused()
    {
        for (String limit : new String[] {"0", "-5"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            JsonObject answer = call(limit);
            assertFalse(limit, answer.get("success").getAsBoolean()); //$NON-NLS-1$
            assertTrue(limit, answer.get("error").getAsString().contains(limit)); //$NON-NLS-1$
        }
    }

    /** A limit that is not a number at all is refused with the text the caller sent. */
    @Test
    public void aTextualLimitIsRefused()
    {
        JsonObject answer = call("many"); //$NON-NLS-1$
        assertFalse(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer.get("error").getAsString().contains("many")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
