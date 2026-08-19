/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Covers the names one check id is looked up under.
 * <p>
 * A marker reports {@code module-unused-method} and the description ships as
 * {@code module-unused-method-check.md}. Both names are EDT's; only the first was ever tried, so an
 * agent that had a finding could not reach the explanation of it. Measured against a real project:
 * of 51 check ids firing, 7 resolved and 24 more resolve once the suffix is tried.
 * </p>
 */
public class CheckDocNamesTest
{
    /** The id as given is always tried first. */
    @Test
    public void theIdAsGivenComesFirst()
    {
        assertEquals("module-unused-method", //$NON-NLS-1$
            CheckDocReader.candidateNames("module-unused-method").get(0)); //$NON-NLS-1$
    }

    /**
     * Exact before suffixed, and this is not a preference - it is a correctness requirement.
     * <p>
     * One description ships under both names at once
     * ({@code data-composition-conditional-appearance-use} and the same with {@code -check}).
     * Appending before looking would answer a question about one check with the other's text, which
     * is worse than answering nothing.
     * </p>
     */
    @Test
    public void theSuffixedNameIsNeverTriedBeforeTheExactOne()
    {
        List<String> names = CheckDocReader.candidateNames("data-composition-conditional-appearance-use"); //$NON-NLS-1$

        assertEquals("data-composition-conditional-appearance-use", names.get(0)); //$NON-NLS-1$
        assertTrue("the suffixed name has to be reachable, just not first", //$NON-NLS-1$
            names.contains("data-composition-conditional-appearance-use-check")); //$NON-NLS-1$
        assertTrue(names.indexOf("data-composition-conditional-appearance-use") //$NON-NLS-1$
            < names.indexOf("data-composition-conditional-appearance-use-check")); //$NON-NLS-1$
    }

    /** An id that already carries the suffix is not given a second one. */
    @Test
    public void anIdThatAlreadyHasTheSuffixDoesNotGetAnother()
    {
        List<String> names = CheckDocReader.candidateNames("module-empty-method-check"); //$NON-NLS-1$

        assertEquals(1, names.size());
        assertEquals("module-empty-method-check", names.get(0)); //$NON-NLS-1$
    }

    /** A mixed-case id is tried as given and lowercased, with and without the suffix. */
    @Test
    public void aMixedCaseIdIsTriedFourWays()
    {
        List<String> names = CheckDocReader.candidateNames("Module-Unused-Method"); //$NON-NLS-1$

        assertEquals(List.of("Module-Unused-Method", "module-unused-method", //$NON-NLS-1$ //$NON-NLS-2$
            "Module-Unused-Method-check", "module-unused-method-check"), names); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A lowercase id is tried twice, not four times - there is no third spelling of it. */
    @Test
    public void aLowercaseIdIsNotTriedTwiceUnderTheSameName()
    {
        assertEquals(List.of("module-unused-method", "module-unused-method-check"), //$NON-NLS-1$ //$NON-NLS-2$
            CheckDocReader.candidateNames("module-unused-method")); //$NON-NLS-1$
    }
}
