/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Covers what an empty problem listing says about the filter that emptied it.
 * <p>
 * The severity argument defaults to the ERROR group, which drops MINOR and TRIVIAL -
 * and most style, module-structure and doc-comment checks report MINOR. The empty
 * answer named the scope, the project and the objects, but not the severity, so a check
 * with findings one argument away read as a check that does not fire. Measured on a
 * demo extension: {@code checkId=module-unused-local-variable} at project scope answers
 * "Nothing Found" by default and returns the finding with {@code severity=ALL}.
 * </p>
 * <p>
 * That mattered beyond a confusing message. A stand agent surveying check coverage read
 * the emptiness as the check being disabled by the tool preset, and diagnosed the cause
 * as the hundred-result cap being applied before the filters - which the code does not
 * do. An unstated filter does not merely hide findings; it invites a wrong theory about
 * the tool.
 * </p>
 */
public class ProjectProblemsSeverityNoticeTest
{
    @Test
    public void theDefaultSaysItIsADefaultAndWhatItDrops()
    {
        String notice = ProjectProblemsReader.describeSeverityFilter(null);

        assertTrue(notice.contains("ERROR"));
        assertTrue(notice.contains("default"));
        // The two levels whose absence is the whole problem.
        assertTrue(notice.contains("MINOR"));
        assertTrue(notice.contains("TRIVIAL"));
    }

    @Test
    public void anExplicitErrorIsNotCalledADefault()
    {
        String notice = ProjectProblemsReader.describeSeverityFilter("ERROR");

        assertTrue(notice.contains("ERROR"));
        assertFalse(notice.contains("default"));
        assertTrue(notice.contains("MINOR"));
    }

    @Test
    public void eachWiderGroupSaysWhatItAdded()
    {
        String warning = ProjectProblemsReader.describeSeverityFilter("WARNING");
        assertTrue(warning.contains("MINOR"));
        assertTrue(warning.contains("TRIVIAL"));

        String info = ProjectProblemsReader.describeSeverityFilter("INFO");
        assertTrue(info.contains("MINOR"));
        assertTrue(info.contains("TRIVIAL"));
    }

    @Test
    public void theWidestGroupAdmitsToFilteringNothing()
    {
        assertTrue(ProjectProblemsReader.describeSeverityFilter("ALL").contains("no severity filter"));
        // Case is the caller's business, not ours.
        assertTrue(ProjectProblemsReader.describeSeverityFilter("all").contains("no severity filter"));
    }

    @Test
    public void aNativeLevelIsReportedAsItself()
    {
        assertTrue(ProjectProblemsReader.describeSeverityFilter("MINOR").contains("MINOR"));
    }

    @Test
    public void theEmptyAnswerCarriesTheFiltersItWasGiven()
    {
        String message = ProjectProblemsReader.nothingFoundMessage("project", "MyProject", //$NON-NLS-1$ //$NON-NLS-2$
            "ALL", "module-unused-local-variable", //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Arrays.asList("CommonModule.Whatever")); //$NON-NLS-1$

        assertTrue(message.contains("MyProject")); //$NON-NLS-1$
        assertTrue(message.contains("module-unused-local-variable")); //$NON-NLS-1$
        assertTrue(message.contains("CommonModule.Whatever")); //$NON-NLS-1$
        assertTrue(message.contains("no severity filter")); //$NON-NLS-1$
        // Nothing left to widen: advising severity=ALL to someone who passed ALL is the
        // answer contradicting itself. The compact path used to produce exactly that,
        // because it reached this message with the filters dropped.
        assertFalse(message.contains("pass severity=ALL")); //$NON-NLS-1$
    }

    @Test
    public void anUnsetFilterIsSimplyAbsentRatherThanEmpty()
    {
        String message = ProjectProblemsReader.nothingFoundMessage("session", null, null, null, //$NON-NLS-1$
            new java.util.ArrayList<>());

        assertFalse(message.contains("Check:")); //$NON-NLS-1$
        assertFalse(message.contains("Objects:")); //$NON-NLS-1$
        assertFalse(message.contains("Project:")); //$NON-NLS-1$
        // The severity is the exception: it is never unset, only defaulted.
        assertTrue(message.contains("Severity:")); //$NON-NLS-1$
        assertTrue(message.contains("pass severity=ALL")); //$NON-NLS-1$
    }

    @Test
    public void compactIsNotOfferedAsAWayRoundTheSeverityFilter()
    {
        // It builds on the same filter and comes back just as empty; suggesting it
        // would send the caller in a circle.
        String message = ProjectProblemsReader.nothingFoundMessage("project", "MyProject", null, //$NON-NLS-1$ //$NON-NLS-2$
            null, new java.util.ArrayList<>());

        assertFalse(message.contains("compact")); //$NON-NLS-1$
    }

    @Test
    public void onlyAllLeavesNoFilterToBlame()
    {
        // Drives whether the empty answer bothers to suggest widening the search.
        assertTrue(ProjectProblemsReader.isEveryLevel("ALL"));
        assertTrue(ProjectProblemsReader.isEveryLevel("all"));
        assertTrue(ProjectProblemsReader.isEveryLevel(" ALL "));
        assertFalse(ProjectProblemsReader.isEveryLevel(null));
        assertFalse(ProjectProblemsReader.isEveryLevel(""));
        assertFalse(ProjectProblemsReader.isEveryLevel("ERROR"));
        assertFalse(ProjectProblemsReader.isEveryLevel("INFO"));
    }
}
