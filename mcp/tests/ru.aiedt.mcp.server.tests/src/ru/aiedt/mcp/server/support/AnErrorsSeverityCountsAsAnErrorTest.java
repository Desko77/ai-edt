/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Covers the severity grouping behind the post-write validation counts.
 * <p>
 * EDT's {@code ERRORS} level - the one most configuration checks report their failures at -
 * fell into the default branch and was counted as a warning, so a write that left the module
 * broken answered {@code validationErrors: 0}. These tests pin {@code ERRORS} to the errors
 * group the count is read from.
 * </p>
 */
public class AnErrorsSeverityCountsAsAnErrorTest
{
    @Test
    public void anErrorsMarkerLandsInTheErrorsGroup()
    {
        FileMarkers.Grouped grouped = FileMarkers.groupBySeverity(markers("ERRORS")); //$NON-NLS-1$

        assertEquals(1, grouped.errorCount());
        assertEquals(0, grouped.warningCount());
        assertEquals(0, grouped.codeStyleCount());
    }

    @Test
    public void theSeverityNameIsReadCaseInsensitively()
    {
        FileMarkers.Grouped grouped = FileMarkers.groupBySeverity(markers("errors")); //$NON-NLS-1$

        assertEquals(1, grouped.errorCount());
    }

    @Test
    public void theOtherLevelsKeepTheirGroups()
    {
        FileMarkers.Grouped grouped = FileMarkers.groupBySeverity(
            markers("BLOCKER", "CRITICAL", "MAJOR", "MINOR", "TRIVIAL")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertEquals(2, grouped.errorCount());
        assertEquals(1, grouped.warningCount());
        assertEquals(2, grouped.codeStyleCount());
    }

    private static List<FileMarkers.MarkerInfo> markers(String... severities)
    {
        return Arrays.stream(severities).map(severity -> {
            FileMarkers.MarkerInfo marker = new FileMarkers.MarkerInfo();
            marker.severity = severity;
            return marker;
        }).collect(java.util.stream.Collectors.toList());
    }
}
