/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * The argument-level contract of the baseline operations: what each refusal names, answered
 * without a workspace behind it.
 * <p>
 * A real save or comparison needs EDT standing there, and the live equivalence is verified against
 * a running workspace. What a plain JUnit run pins down is the part a caller meets first: a
 * comparison without a project refused by naming the operation that records a baseline, the
 * operations themselves refusing without a project, and the refusals of an absent and an
 * unreadable baseline naming the same operation.
 * </p>
 */
public class DiagnosticsBaselineFacadeTest
{
    /**
     * A comparison without a project is refused and told how to record a baseline.
     */
    @Test
    public void aComparisonWithoutAProjectNamesSaveBaseline()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "get_project_errors"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("sinceBaseline", "true"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = new DiagnosticsFacadeTool().execute(params);

        assertNotNull(result);
        assertTrue(result.contains("save_baseline")); //$NON-NLS-1$
        assertTrue(result.contains("projectName")); //$NON-NLS-1$
    }

    /**
     * save_baseline refuses without a project, naming the argument it needs.
     */
    @Test
    public void saveBaselineWithoutAProjectIsRefused()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "save_baseline"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = new DiagnosticsFacadeTool().execute(params);

        assertNotNull(result);
        assertTrue(result.contains("projectName")); //$NON-NLS-1$
    }

    /**
     * clear_baseline refuses without a project the same way.
     */
    @Test
    public void clearBaselineWithoutAProjectIsRefused()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "clear_baseline"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = new DiagnosticsFacadeTool().execute(params);

        assertNotNull(result);
        assertTrue(result.contains("projectName")); //$NON-NLS-1$
    }

    /**
     * The refusal of a project with no baseline names the operation that records one.
     */
    @Test
    public void anAbsentBaselineRefusalNamesSaveBaseline()
    {
        String refusal = ProjectProblemsReader.baselineAbsentRefusal("Project"); //$NON-NLS-1$

        assertTrue(refusal.contains("Project")); //$NON-NLS-1$
        assertTrue(refusal.contains("save_baseline")); //$NON-NLS-1$
    }

    /**
     * The refusal of a baseline this build cannot read names the operation that records it again -
     * an unreadable file must not send the caller anywhere else.
     */
    @Test
    public void anUnreadableBaselineRefusalNamesSaveBaseline()
    {
        String refusal = ProjectProblemsReader.baselineUnreadableRefusal("Project", //$NON-NLS-1$
            "the file carries format version 7"); //$NON-NLS-1$

        assertTrue(refusal.contains("format version 7")); //$NON-NLS-1$
        assertTrue(refusal.contains("save_baseline")); //$NON-NLS-1$
    }

    /**
     * The refusal a comparison without a project gets names the operation too.
     */
    @Test
    public void aComparisonWithoutAProjectRefusalNamesSaveBaseline()
    {
        assertTrue(ProjectProblemsReader.baselineNeedsProjectRefusal().contains("save_baseline")); //$NON-NLS-1$
    }
}
