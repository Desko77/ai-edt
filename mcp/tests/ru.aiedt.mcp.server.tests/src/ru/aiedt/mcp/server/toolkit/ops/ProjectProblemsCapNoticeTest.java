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
 * An overflowing project used to answer with this banner INSTEAD of the findings: the
 * page said "Returned: 300" and carried no rows, while the 300 rows sat collected and
 * were dropped on the way out. Working out which checks fire on a large configuration
 * then cost one call per object. The banner now precedes the rows, and it names the
 * cheaper question - a per-check summary - rather than only telling the caller to narrow
 * down.
 * </p>
 */
public class ProjectProblemsCapNoticeTest
{
    @Test
    public void theBannerReportsBothNumbers()
    {
        String notice = ProjectProblemsReader.capNotice(753, 300);

        assertTrue(notice.contains("753"));
        assertTrue(notice.contains("300"));
    }

    @Test
    public void theBannerSaysTheRowsAreBelowIt()
    {
        // The old wording, "Returned: 300", sat above an empty page. Whatever this says
        // now, it must not promise rows without pointing at them.
        String notice = ProjectProblemsReader.capNotice(753, 300);

        assertTrue(notice.contains("below"));
    }

    @Test
    public void theBannerNamesTheSummaryRouteAndWhereItStops()
    {
        // The caller wanting a denominator - which checks fire at all - has a cheaper
        // answer available. Advice has to be followable: compact mode scans its own cap
        // whatever `limit` says, so telling the caller to raise `limit` would send them
        // after a knob that changes nothing. Name the real boundary instead.
        String notice = ProjectProblemsReader.capNotice(753, 300);

        assertTrue(notice.contains("compact=true"));
        assertTrue(notice.contains("5000"));
        assertFalse(notice.contains("raised"));
    }

    @Test
    public void theBannerKeepsTheNarrowingAdviceItAlwaysHad()
    {
        String notice = ProjectProblemsReader.capNotice(753, 300);

        assertTrue(notice.contains("objects=[...]"));
        assertTrue(notice.contains("checkId"));
        assertTrue(notice.contains("scope=session"));
    }

    @Test
    public void theBannerEndsBlankSoTheListingStartsCleanly()
    {
        String notice = ProjectProblemsReader.capNotice(753, 300);

        assertTrue(notice.endsWith("\n\n"));
        assertFalse(notice.isEmpty());
    }
}
