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
 * Covers the banner in front of a capped project listing.
 * <p>
 * It has been wrong twice, in the same way both times: by answering a question the caller had not
 * asked. First it replaced the findings - "Returned: 300" over an empty page while the 300 rows sat
 * collected and were dropped on the way out. Then, once the rows came back, it went on counting
 * markers across the whole project even when the request had narrowed to one check, and advised
 * passing the filter the caller had just passed.
 * </p>
 */
public class ProjectProblemsCapNoticeTest
{
    @Test
    public void theBannerReportsBothNumbers()
    {
        String notice = ProjectProblemsReader.capNotice(exactly(753), 300, false);

        assertTrue(notice.contains("753"));
        assertTrue(notice.contains("300"));
    }

    /**
     * The number is about the request, not about the project.
     * <p>
     * This is the whole fix. A caller filtered to one check and getting a full page was told the
     * project holds 45 000 markers - true, useless, and read as a refusal by every agent that saw
     * it.
     * </p>
     */
    @Test
    public void theNumberIsAboutWhatWasAskedFor()
    {
        String notice = ProjectProblemsReader.capNotice(exactly(753), 300, true);

        assertTrue("the banner must say what the number counts: " + notice, //$NON-NLS-1$
            notice.contains("Matching this request"));
        assertFalse("counting the whole project is what was wrong: " + notice, //$NON-NLS-1$
            notice.contains("Total markers in project"));
    }

    /** A count that stopped at its ceiling says so rather than passing itself off as exact. */
    @Test
    public void anInexactCountAdmitsIt()
    {
        String notice = ProjectProblemsReader.capNotice(atLeast(5000), 300, false);

        assertTrue("a ceiling-stopped count must not read as exact: " + notice, //$NON-NLS-1$
            notice.contains("more than 5000"));
    }

    @Test
    public void theBannerSaysTheRowsAreBelowIt()
    {
        // The old wording, "Returned: 300", sat above an empty page. Whatever this says
        // now, it must not promise rows without pointing at them.
        String notice = ProjectProblemsReader.capNotice(exactly(753), 300, false);

        assertTrue(notice.contains("below"));
    }

    @Test
    public void theBannerNamesTheSummaryRouteAndWhereItStops()
    {
        // The caller wanting a denominator - which checks fire at all - has a cheaper
        // answer available. Advice has to be followable: compact mode scans its own cap
        // whatever `limit` says, so telling the caller to raise `limit` would send them
        // after a knob that changes nothing. Name the real boundary instead.
        String notice = ProjectProblemsReader.capNotice(exactly(753), 300, false);

        assertTrue(notice.contains("compact=true"));
        assertTrue(notice.contains("5000"));
    }

    /** A caller who has not narrowed is told how. */
    @Test
    public void anUnfilteredRequestIsToldHowToNarrow()
    {
        String notice = ProjectProblemsReader.capNotice(exactly(753), 300, false);

        assertTrue(notice.contains("objects=[...]"));
        assertTrue(notice.contains("checkId"));
        assertTrue(notice.contains("scope=session"));
    }

    /**
     * A caller who has already narrowed is not told to narrow.
     * <p>
     * Advice that repeats what the caller just did is worse than no advice: it implies the request
     * was wrong when it was right, and there is nothing to act on.
     * </p>
     */
    @Test
    public void aFilteredRequestIsNotToldToPassTheFilterItPassed()
    {
        String notice = ProjectProblemsReader.capNotice(exactly(753), 300, true);

        assertFalse("it advises narrowing to somebody who already narrowed: " + notice, //$NON-NLS-1$
            notice.contains("objects=[...]"));
        assertTrue("and it should say what actually helps instead: " + notice, //$NON-NLS-1$
            notice.contains("raise `limit`"));
    }

    @Test
    public void theBannerEndsBlankSoTheListingStartsCleanly()
    {
        String notice = ProjectProblemsReader.capNotice(exactly(753), 300, false);

        assertTrue(notice.endsWith("\n\n"));
        assertFalse(notice.isEmpty());
    }

    /** A count that reached the end of what matched. */
    @Test
    public void anExactCountComparesOnItsValue()
    {
        assertTrue(exactly(753).exceeds(200));
        assertFalse(exactly(120).exceeds(200));
    }

    /** A count that stopped early is above any threshold below its ceiling, by construction. */
    @Test
    public void aCeilingStoppedCountIsAboveEveryThresholdItCouldHaveTested()
    {
        assertTrue(atLeast(5000).exceeds(200));
        assertTrue("stopping early means there was more, whatever the threshold", //$NON-NLS-1$
            atLeast(5000).exceeds(5000));
    }

    private static ProjectProblemsReader.MatchCount exactly(long value)
    {
        return new ProjectProblemsReader.MatchCount(value, false);
    }

    private static ProjectProblemsReader.MatchCount atLeast(long value)
    {
        return new ProjectProblemsReader.MatchCount(value, true);
    }
}
