/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

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
}
