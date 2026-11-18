/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/**
 * Holds the explicit classification a continuation relies on: what is always kept, what is
 * canonicalized, what is emptied rather than dropped, and the order in which the rest goes.
 */
public class ResultArgumentProfileTest
{
    @Test
    public void numbersAndBooleansAreKeptWhateverTheirName()
    {
        ResultArgumentProfile profile = ResultArgumentProfile.builder("read").build(); //$NON-NLS-1$
        assertTrue(profile.isKept("offset", Integer.valueOf(10))); //$NON-NLS-1$
        assertTrue(profile.isKept("apply", Boolean.TRUE)); //$NON-NLS-1$
        assertFalse(profile.isKept("note", "text")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anExplicitlyKeptNameIsKeptEvenWhenTheValueIsText()
    {
        ResultArgumentProfile profile = ResultArgumentProfile.builder("read") //$NON-NLS-1$
            .alwaysKept("limit", "sectionsOffset") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
        assertTrue(profile.isKept("limit", "50")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(profile.isKept("sectionsOffset", "7")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anEnumerableIsKeptAndReadsAsItsCanonicalValue()
    {
        ResultArgumentProfile profile = ResultArgumentProfile.builder("compare") //$NON-NLS-1$
            .enumerable("intent", "REPORT") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
        assertTrue(profile.isKept("intent", "   report   ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("REPORT", profile.canonicalValue("intent", "   report   ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("other", profile.canonicalValue("scope", "other")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anElidedRequiredArgumentGetsAnEmptyValueOfItsOwnKind()
    {
        assertEquals("", ResultArgumentProfile.emptyValueOf("text")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(new ArrayList<>(), ResultArgumentProfile.emptyValueOf(new ArrayList<>()));
        assertEquals(new LinkedHashMap<>(), ResultArgumentProfile.emptyValueOf(new LinkedHashMap<>()));
        assertEquals(null, ResultArgumentProfile.emptyValueOf(Integer.valueOf(1)));
    }

    @Test
    public void theDroppingOrderIsLongestFirstThenName()
    {
        ResultArgumentProfile profile = ResultArgumentProfile.builder("read") //$NON-NLS-1$
            .alwaysKept("offset") //$NON-NLS-1$
            .build();
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("offset", "10"); //$NON-NLS-1$ //$NON-NLS-2$
        arguments.put("aaa", "12345"); //$NON-NLS-1$ //$NON-NLS-2$
        arguments.put("bbb", "12345"); //$NON-NLS-1$ //$NON-NLS-2$
        arguments.put("zzz", "123456789"); //$NON-NLS-1$ //$NON-NLS-2$
        arguments.put("flag", Boolean.TRUE);
        Set<String> reserved = new LinkedHashSet<>();
        reserved.add("resultId"); //$NON-NLS-1$
        List<String> order = profile.elisionOrder(arguments, reserved);
        assertEquals(List.of("zzz", "aaa", "bbb"), order); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void theSameArgumentsAlwaysGiveTheSameOrder()
    {
        ResultArgumentProfile profile = ResultArgumentProfile.builder("read").build(); //$NON-NLS-1$
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++)
        {
            arguments.put("name" + i, i % 2 == 0 ? "12345" : "1234567"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        List<String> first = profile.elisionOrder(arguments, Set.of());
        List<String> second = profile.elisionOrder(arguments, Set.of());
        assertEquals(first, second);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aProfileWithoutAnOperationIsRefused()
    {
        ResultArgumentProfile.builder(" "); //$NON-NLS-1$
    }

    @Test(expected = IllegalArgumentException.class)
    public void anEnumerableWithoutACanonicalValueIsRefused()
    {
        ResultArgumentProfile.builder("read").enumerable("intent", null); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
