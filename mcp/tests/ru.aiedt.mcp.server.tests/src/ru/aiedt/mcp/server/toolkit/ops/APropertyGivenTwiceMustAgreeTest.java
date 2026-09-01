/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * What happens when a property is named as an argument and again in the properties object.
 * <p>
 * {@code create_object} reads some properties as arguments of their own - the execution contexts of
 * a common module among them - and now also takes a properties object. A property in both places
 * with two different values is a contradiction, and choosing one of them quietly would write
 * something the caller did not ask for.
 * </p>
 */
public class APropertyGivenTwiceMustAgreeTest
{
    private static Map<String, String> map(String... pairs)
    {
        Map<String, String> built = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            built.put(pairs[i], pairs[i + 1]);
        }
        return built;
    }

    @Test
    public void twoValuesForOnePropertyAreRefused()
    {
        String refused = ObjectOps.contradictingProperty(map("server", "true"),
            map("server", "false"));
        assertNotNull("a property given twice with two values has to stop the call", refused);
        assertTrue("the refusal names the property", refused.contains("server"));
        assertTrue("and both values, so the caller can see which to drop",
            refused.contains("true") && refused.contains("false"));
        assertTrue("and says the object was not created", refused.contains("Nothing was created"));
    }

    /** The same value twice is not a contradiction: the caller asked for one thing, twice. */
    @Test
    public void theSameValueTwiceIsNotAContradiction()
    {
        assertNull(ObjectOps.contradictingProperty(map("server", "true"), map("server", "true")));
    }

    @Test
    public void propertiesThatDoNotOverlapPassThrough()
    {
        assertNull(ObjectOps.contradictingProperty(map("name", "X", "server", "true"),
            map("methodName", "CommonModule.A.B", "use", "false")));
    }

    @Test
    public void nothingToCompareIsNotAContradiction()
    {
        assertNull(ObjectOps.contradictingProperty(map("name", "X"), map()));
        assertNull(ObjectOps.contradictingProperty(null, map("use", "false")));
    }

    /** The first disagreement is enough to stop; the answer names one property, not a list. */
    @Test
    public void theFirstDisagreementIsReported()
    {
        String refused = ObjectOps.contradictingProperty(map("server", "true", "global", "true"),
            map("global", "false"));
        assertNotNull(refused);
        assertTrue(refused.contains("global"));
    }
}
