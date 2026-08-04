/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.List;

/**
 * Writes a test run up as Markdown: a summary table, a verdict, and then only the tests that went
 * wrong.
 * <p>
 * The report is the whole answer an agent gets back from a test run, so it leads with the numbers and
 * the verdict and keeps the detail underneath. Sections for failures, errors and skips appear only
 * when there is something to put in them - a green run should read as a green run, not as three empty
 * headings.
 * </p>
 */
public final class JUnitReportFormatter
{
    private JUnitReportFormatter()
    {
        // utility
    }

    /**
     * Renders a run.
     *
     * @param results what the report said
     * @return Markdown: header, summary table, verdict, and a section per non-empty detail list
     */
    public static String format(JUnitRunOutcome results)
    {
        StringBuilder md = new StringBuilder();

        md.append("# Test run\n\n"); //$NON-NLS-1$
        md.append("## Totals\n\n"); //$NON-NLS-1$
        md.append("| Outcome | Tests |\n"); //$NON-NLS-1$
        md.append("|--------|-------|\n"); //$NON-NLS-1$
        md.append("| Total  | ").append(results.getTotal()).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        md.append("| Passed | ").append(results.getPassed()).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        md.append("| Failed | ").append(results.getFailures()).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        md.append("| Errors | ").append(results.getErrors()).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        md.append("| Skipped | ").append(results.getSkipped()).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        md.append("\n"); //$NON-NLS-1$
        md.append(results.isPassed() ? "**Verdict: green**\n" : "**Verdict: red**\n"); //$NON-NLS-1$ //$NON-NLS-2$

        appendDetails(md, "Failures", results.getFailureDetails()); //$NON-NLS-1$
        appendDetails(md, "Errors", results.getErrorDetails()); //$NON-NLS-1$
        appendSkipped(md, results.getSkippedDetails());

        return md.toString();
    }

    /**
     * A section for tests that broke: one sub-heading each, the message when there is one, and the
     * trace in a fenced block so that a 1C stack survives Markdown intact.
     *
     * @param md the report being built
     * @param title the section heading
     * @param testCases the tests to list; nothing is written when this is empty
     */
    private static void appendDetails(StringBuilder md, String title, List<JUnitRunOutcome.TestCase> testCases)
    {
        if (testCases.isEmpty())
        {
            return;
        }

        md.append("\n## ").append(title).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        for (JUnitRunOutcome.TestCase testCase : testCases)
        {
            md.append("\n### ").append(testCase.name).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            if (testCase.message != null && !testCase.message.isEmpty())
            {
                md.append("**Message:** ").append(testCase.message).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (testCase.trace != null && !testCase.trace.isBlank())
            {
                md.append("```\n").append(testCase.trace.trim()).append("\n```\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    /**
     * A section for tests that never ran. A skip has no trace and rarely has anything to say beyond
     * why, so it gets one bullet, not a heading of its own.
     *
     * @param md the report being built
     * @param testCases the skipped tests; nothing is written when this is empty
     */
    private static void appendSkipped(StringBuilder md, List<JUnitRunOutcome.TestCase> testCases)
    {
        if (testCases.isEmpty())
        {
            return;
        }

        md.append("\n## Skipped\n\n"); //$NON-NLS-1$
        for (JUnitRunOutcome.TestCase testCase : testCases)
        {
            md.append("- **").append(testCase.name).append("**"); //$NON-NLS-1$ //$NON-NLS-2$
            if (testCase.message != null && !testCase.message.isEmpty())
            {
                md.append(" - ").append(testCase.message); //$NON-NLS-1$
            }
            md.append("\n"); //$NON-NLS-1$
        }
    }
}
