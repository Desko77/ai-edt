/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * The bound a caller puts on a documentation answer.
 * <p>
 * A bound of zero or less asks for an answer holding nothing, and the call took it as it stood: the
 * answer was built, and it closed with "Output truncated to -5 entries". A bound that cannot be met
 * is a mistaken call, not an answer, and it is refused for the bound it named.
 * </p>
 * <p>
 * The calls below name a category the call does not know on purpose. That branch answers without
 * reaching the platform register or the workspace, so what is measured is the order of the checks
 * rather than a live lookup - and a headless test cannot reach the model anyway.
 * </p>
 */
public class PlatformDocumentationRefusesAnEmptyBoundTest
{
    private static String call(String limit)
    {
        Map<String, String> params = new HashMap<>();
        params.put("typeName", "Array"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("category", "no_such_category"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("limit", limit); //$NON-NLS-1$
        return new PlatformDocReader().execute(params);
    }

    /** The reading of the bound itself: a number is read, anything else leaves the default. */
    @Test
    public void aBoundIsReadWhenItHoldsANumber()
    {
        assertEquals(Integer.valueOf(5), PlatformDocReader.readLimit("5")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(200), PlatformDocReader.readLimit("200")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(-5), PlatformDocReader.readLimit("-5")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(0), PlatformDocReader.readLimit("0")); //$NON-NLS-1$
    }

    @Test
    public void aBoundThatHoldsNoNumberLeavesTheDefault()
    {
        assertNull(PlatformDocReader.readLimit("many")); //$NON-NLS-1$
        assertNull(PlatformDocReader.readLimit("")); //$NON-NLS-1$
        assertNull(PlatformDocReader.readLimit(null));
    }

    @Test
    public void aBoundOfZeroIsRefusedBeforeTheLookupIsPicked()
    {
        String answer = call("0"); //$NON-NLS-1$

        assertTrue("a bound of zero is refused for the bound it named: " + answer, //$NON-NLS-1$
            answer.contains("'limit' parameter must be a positive number")); //$NON-NLS-1$
        assertFalse("an answer that holds nothing is not built at all: " + answer, //$NON-NLS-1$
            answer.contains("Output truncated")); //$NON-NLS-1$
    }

    @Test
    public void aNegativeBoundIsRefusedAndNamedAsGiven()
    {
        String answer = call("-5"); //$NON-NLS-1$

        assertTrue(answer.contains("'limit' parameter must be a positive number")); //$NON-NLS-1$
        assertTrue("the refusal does not say what it was given: " + answer, answer.contains("-5")); //$NON-NLS-1$
        assertFalse("the answer used to close with this: " + answer, //$NON-NLS-1$
            answer.contains("Output truncated to -5")); //$NON-NLS-1$
    }

    /** A bound of text is not a bound of zero: the default holds and the answer is built. */
    @Test
    public void aBoundOfTextIsNotReadAsAnEmptyAnswer()
    {
        String answer = call("many"); //$NON-NLS-1$

        assertFalse("text is not a bound asking for nothing: " + answer, //$NON-NLS-1$
            answer.contains("must be a positive number")); //$NON-NLS-1$
    }
}
