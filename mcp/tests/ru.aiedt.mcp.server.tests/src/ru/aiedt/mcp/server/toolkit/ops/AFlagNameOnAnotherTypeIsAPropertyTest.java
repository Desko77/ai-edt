/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Which members of the properties object {@code create_object} still has to write itself.
 * <p>
 * A common module reads its execution contexts as arguments of its own, so a properties object
 * carrying one of those names is folded into the arguments instead of being applied twice. No other
 * type reads them, so on any other type a property of the same name is an ordinary property.
 * </p>
 * <p>
 * The first version of the change took those names out for EVERY type, which would have dropped a
 * property named {@code server} on a catalogue in silence - the loss the change exists to end,
 * reintroduced one method away from the fix. It was caught reading the change back rather than by a
 * test, so this is that test.
 * </p>
 */
public class AFlagNameOnAnotherTypeIsAPropertyTest
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
    public void aFlagNameSurvivesOnAnotherType()
    {
        Map<String, String> toApply =
            ObjectOps.propertiesToApply(map("server", "true", "use", "false"), false);
        assertTrue("a catalogue has no 'server' argument, so this one has to be applied",
            toApply.containsKey("server"));
        assertEquals(2, toApply.size());
    }

    @Test
    public void aFlagNameIsFoldedAwayOnACommonModule()
    {
        Map<String, String> toApply =
            ObjectOps.propertiesToApply(map("server", "true", "comment", "x"), true);
        assertEquals("the module reads it as an argument; applying it again would write it twice",
            map("comment", "x"), toApply);
    }

    /** Service arguments steer the call and describe no object, whatever the type. */
    @Test
    public void serviceArgumentsComeOutForEveryType()
    {
        for (boolean isCommonModule : new boolean[] { true, false })
        {
            Map<String, String> toApply = ObjectOps.propertiesToApply(
                map("projectName", "P", "objectType", "Catalog", "name", "X", "synonym", "Y",
                    "dryRun", "true", "properties", "{}", "comment", "kept"),
                isCommonModule);
            assertEquals("only the real property is left", map("comment", "kept"), toApply);
        }
    }

    /** A refusal names the properties in the order the caller wrote them. */
    @Test
    public void theOrderGivenSurvives()
    {
        Map<String, String> toApply =
            ObjectOps.propertiesToApply(map("zebra", "1", "alpha", "2", "middle", "3"), false);
        assertEquals(new ArrayList<>(Arrays.asList("zebra", "alpha", "middle")),
            new ArrayList<>(toApply.keySet()));
    }

    @Test
    public void nothingDeclaredLeavesNothingToApply()
    {
        assertTrue(ObjectOps.propertiesToApply(map(), false).isEmpty());
        assertTrue(ObjectOps.propertiesToApply(map(), true).isEmpty());
    }

    /**
     * A folded flag never reaches the generic setter, so nothing downstream would refuse a value
     * the boolean reader cannot read. It answers "not given" to anything outside true/false, and
     * "not given" is what a flag the caller left out looks like - so the default context would be
     * written and the call would succeed as though the property had not been asked for.
     */
    @Test
    public void aFlagValueThatIsNotBooleanIsRefused()
    {
        String refused = ObjectOps.unreadableFlag(map("server", "tru"), true);
        assertNotNull(refused);
        assertTrue(refused.contains("server"));
        assertTrue(refused.contains("tru"));
        assertTrue(refused.contains("Nothing was created"));
    }

    @Test
    public void theSpellingsTheReaderAcceptsPassThrough()
    {
        for (String spelling : new String[] { "true", "false", "1", "0", "yes", "no", "TRUE" })
        {
            assertNull("'" + spelling + "' is a value the reader takes",
                ObjectOps.unreadableFlag(map("global", spelling), true));
        }
    }

    /**
     * On any other type the same name is an ordinary property, and the generic setter judges the
     * value. Refusing it here would reject a property the object may well have.
     */
    @Test
    public void aFlagValueIsNotJudgedOnAnotherType()
    {
        assertNull(ObjectOps.unreadableFlag(map("server", "tru"), false));
    }

    @Test
    public void aPropertyThatIsNotAFlagIsNotJudgedHere()
    {
        assertNull(ObjectOps.unreadableFlag(map("methodName", "CommonModule.A.B"), true));
    }
}
