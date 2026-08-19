/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Covers what a failed enumeration lookup says.
 * <p>
 * The values themselves come from the platform register, which needs a workspace; what can be
 * pinned without one is the shape of the failure - and that is the part worth pinning. An empty
 * value list would read as "this enumeration has nothing in it", which is never true of a real one
 * and would send whoever asked looking for the fault in their own code.
 * </p>
 */
public class SystemEnumValuesTest
{
    /** No name, no answer - and a reason rather than an empty list. */
    @Test
    public void anEmptyNameIsAReasonNotAnEmptyList()
    {
        SystemEnumValues.Lookup lookup = SystemEnumValues.of(null, null);

        assertNotNull("a failure must carry its reason", lookup.cannotTell); //$NON-NLS-1$
        assertTrue(lookup.values.isEmpty());
        assertFalse(lookup.isSystemEnum);
    }

    /**
     * Without a project there is no platform version, so there is nothing to ask.
     * <p>
     * Reported as undecided rather than as "unknown type": the register was never consulted, and
     * saying the type does not exist would be a claim made without looking.
     * </p>
     */
    @Test
    public void withoutAProjectTheAnswerIsUndecidedRatherThanNegative()
    {
        SystemEnumValues.Lookup lookup = SystemEnumValues.of("ВидДвиженияНакопления", null); //$NON-NLS-1$

        assertNotNull(lookup.cannotTell);
        assertTrue("the reason must not claim the type is absent: " + lookup.cannotTell, //$NON-NLS-1$
            lookup.cannotTell.contains("not necessarily absent")); //$NON-NLS-1$
        assertTrue(lookup.values.isEmpty());
    }

    /** A blank name is the same as none. */
    @Test
    public void aBlankNameIsNoName()
    {
        assertNotNull(SystemEnumValues.of("", null).cannotTell); //$NON-NLS-1$
    }

    /**
     * The name in front of the dot is not a type, and the lookup must ask the global context
     * before it asks the type register.
     * <p>
     * This is the defect itself, twice over, and both versions survived a green build. The type
     * register answers {@code ВидДвиженияНакопления} with the type OF a value: flagged as a system
     * enumeration, carrying an empty context. The values belong to the type behind the
     * global-context entry of the same name, and that type is in no spelling present in the type
     * register - measured on a stand, not deduced. So a lookup that consults only the type register
     * is wrong however cleverly it guesses names.
     * </p>
     * <p>
     * Without a workspace neither register can be reached, so what is pinned here is that BOTH were
     * asked. That is coupled to the route: drop the global-context step and this reason can no
     * longer be produced.
     * </p>
     */
    @Test
    public void theGlobalContextIsAskedBeforeTheTypeRegister()
    {
        String reason = SystemEnumValues.of("ВидДвиженияНакопления", null).cannotTell; //$NON-NLS-1$

        assertNotNull(reason);
        assertTrue("the global context must be among the places looked: " + reason, //$NON-NLS-1$
            reason.contains("as a global property")); //$NON-NLS-1$
        assertTrue("and the type register too: " + reason, reason.contains("as a type")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A value answers to a name in each script, and the caller writing Russian needs the Russian
     * one. A missing counterpart is empty rather than null, so the answer never carries a hole.
     */
    @Test
    public void aValueCarriesBothOfItsNames()
    {
        SystemEnumValues.Value both = new SystemEnumValues.Value("Receipt", "Приход"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Receipt", both.name); //$NON-NLS-1$
        assertEquals("Приход", both.nameRu); //$NON-NLS-1$

        assertEquals("", new SystemEnumValues.Value("Receipt", null).nameRu); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
