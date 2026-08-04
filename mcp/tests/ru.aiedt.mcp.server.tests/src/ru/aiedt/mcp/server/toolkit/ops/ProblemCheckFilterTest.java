/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.junit.Test;

/**
 * Covers the {@code checkId} filter of {@code get_project_errors}.
 * <p>
 * An EDT marker knows itself by a short opaque uid; the readable id the report
 * prints is resolved from that uid separately. While the filter compared only the
 * uid, asking for a check by the id the grouped view had just printed answered
 * "nothing found" - the tool contradicting itself over the same markers. These
 * tests pin the filter to the identity the report actually shows.
 * </p>
 */
public class ProblemCheckFilterTest
{
    @Test
    public void theIdPrintedByTheReportIsTheIdTheFilterAccepts()
    {
        assertTrue(filterFor("common-module-type") //$NON-NLS-1$
            .matchesCheckIdentity("common-module-type", "1a2b3c")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aPartialIdStillMatches()
    {
        assertTrue("the parameter is documented as a substring filter", //$NON-NLS-1$
            filterFor("module-type").matchesCheckIdentity("common-module-type", "1a2b3c")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void theShortCodeKeepsWorkingForCallersThatHaveOne()
    {
        assertTrue(filterFor("1a2b3c").matchesCheckIdentity("common-module-type", "1a2b3c")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void aRowWithNoReadableIdIsStillReachableByItsCode()
    {
        // Eclipse-side markers carry only a source id, which the report prints in
        // the same column - so it has to be filterable by the same argument.
        assertTrue(filterFor("org.eclipse.xtext") //$NON-NLS-1$
            .matchesCheckIdentity(null, "org.eclipse.xtext.diagnostics.Diagnostic.Syntax")); //$NON-NLS-1$
    }

    @Test
    public void anUnrelatedCheckIsRejected()
    {
        assertFalse(filterFor("ql-temp-table-index") //$NON-NLS-1$
            .matchesCheckIdentity("common-module-type", "1a2b3c")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void noFilterKeepsEverythingIncludingRowsWithNoIdentityAtAll()
    {
        assertTrue(filterFor(null).matchesCheckIdentity(null, null));
        assertTrue(filterFor("").matchesCheckIdentity(null, null)); //$NON-NLS-1$
    }

    private static ProjectProblemsReader.Filter filterFor(String checkId)
    {
        return new ProjectProblemsReader.Filter(null, null, checkId, Collections.emptySet(),
            Collections.emptySet(), false, null);
    }
}
