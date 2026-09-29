/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;

/**
 * Covers the heading of a problem summary scoped to one project.
 * <p>
 * Asked with a {@code projectName}, the tool counted only that project's markers but titled
 * the page "Workspace Totals" and totalled it as a grand total - so one broken project read
 * as the whole workspace being broken, and one clean project as the workspace being clean.
 * These tests pin the heading and the total row to the project the count is actually about.
 * </p>
 */
public class AScopedSummaryNamesTheProjectTest
{
    @Test
    public void aScopedCountNamesTheProjectInTheHeadingAndTheTotal()
    {
        String markdown = ProblemSummaryReader.render(totals(), new HashMap<>(), "MyProject"); //$NON-NLS-1$

        assertTrue(markdown.contains("MyProject Totals")); //$NON-NLS-1$
        assertTrue(markdown.contains("MyProject TOTAL")); //$NON-NLS-1$
        assertFalse("a scoped page must not read as the whole workspace: " + markdown, //$NON-NLS-1$
            markdown.contains("Workspace Totals")); //$NON-NLS-1$
        assertFalse(markdown.contains("GRAND TOTAL")); //$NON-NLS-1$
    }

    @Test
    public void aWorkspaceCountKeepsTheWorkspaceHeading()
    {
        String markdown = ProblemSummaryReader.render(totals(), new HashMap<>(), null);

        assertTrue(markdown.contains("Workspace Totals")); //$NON-NLS-1$
        assertTrue(markdown.contains("GRAND TOTAL")); //$NON-NLS-1$
    }

    @Test
    public void anEmptyProjectNameIsTheWorkspace()
    {
        String markdown = ProblemSummaryReader.render(totals(), new HashMap<>(), ""); //$NON-NLS-1$

        assertTrue(markdown.contains("Workspace Totals")); //$NON-NLS-1$
    }

    private static Map<MarkerSeverity, Integer> totals()
    {
        Map<MarkerSeverity, Integer> totals = new EnumMap<>(MarkerSeverity.class);
        totals.put(MarkerSeverity.ERRORS, Integer.valueOf(3));
        return totals;
    }
}
