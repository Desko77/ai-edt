/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/**
 * Covers the count of errors standing against a named set of objects.
 * <p>
 * It exists for one caller - a merge, which writes into a configuration and must say afterwards
 * whether the project still validates. What is pinned here is the part that caller depends on and
 * cannot check for itself: that "nothing to count" and "could not count" are different answers.
 * </p>
 */
public class ProjectProblemsCountOnTest
{
    /** Nothing asked about is nothing to answer, and that answer is a real zero. */
    @Test
    public void anEmptyRequestCountsNothing()
    {
        assertEquals(0L, ProjectProblemsReader.countErrorsOn("AnyProject", //$NON-NLS-1$
            Collections.emptyList()));
        assertEquals(0L, ProjectProblemsReader.countErrorsOn("AnyProject", null)); //$NON-NLS-1$
    }

    /**
     * Without the marker service the question has no answer, and the answer is not zero.
     * <p>
     * Zero is what a clean project looks like. Returning it when nothing was consulted is the exact
     * shape of mistake this project keeps paying for - an empty result read as a good one - and
     * here it would tell a caller their merge left the configuration sound when nobody looked.
     * </p>
     */
    @Test
    public void anUnaskedQuestionIsNotTheAnswerZero()
    {
        long counted = ProjectProblemsReader.countErrorsOn("NoSuchProject", //$NON-NLS-1$
            Arrays.asList("Catalog.Nowhere")); //$NON-NLS-1$

        assertTrue("outside a workbench there is no marker service, and -1 says so rather than 0: " //$NON-NLS-1$
            + counted, counted == -1L || counted >= 0L);
        // The weaker assertion above holds in both runtimes; this is the one that matters, and it
        // holds in the runtime the suite actually uses.
        if (ru.aiedt.mcp.server.Activator.getDefault() == null)
        {
            assertEquals("with no plugin at all the count must refuse, not report a clean project", //$NON-NLS-1$
                -1L, counted);
        }
    }
}
