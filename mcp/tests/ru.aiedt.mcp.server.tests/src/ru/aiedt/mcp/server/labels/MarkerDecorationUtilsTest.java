/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels;

import static org.junit.Assert.assertEquals;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Test;

import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.labels.model.Marker;

/**
 * Verifies {@link MarkerDecorationHelpers#formatMarkers(Set, String)} across the three decoration styles
 * (suffix list, first marker, count) and the fallback that treats an unrecognized style as the suffix
 * style.
 */
public class MarkerDecorationUtilsTest
{
    // ------ No markers to show ------

    @Test
    public void emptySetProducesNoSuffix()
    {
        assertEquals("", MarkerDecorationHelpers.formatMarkers(Set.of(), PrefKeys.MARKERS_STYLE_SUFFIX));
    }

    @Test
    public void nullSetProducesNoSuffix()
    {
        assertEquals("", MarkerDecorationHelpers.formatMarkers(null, PrefKeys.MARKERS_STYLE_SUFFIX));
    }

    // ------ Suffix style (the default) ------

    @Test
    public void suffixStyleRendersSingleMarkerName()
    {
        assertEquals(" [watchlist]",
            MarkerDecorationHelpers.formatMarkers(Set.of(new Marker("watchlist")), PrefKeys.MARKERS_STYLE_SUFFIX));
    }

    @Test
    public void suffixStyleJoinsMultipleNamesCommaSeparated()
    {
        Set<Marker> markers = new LinkedHashSet<>();
        markers.add(new Marker("defect"));
        markers.add(new Marker("blocker"));
        assertEquals(" [defect, blocker]", MarkerDecorationHelpers.formatMarkers(markers, PrefKeys.MARKERS_STYLE_SUFFIX));
    }

    @Test
    public void unknownStyleFallsBackToSuffix()
    {
        assertEquals(" [review]",
            MarkerDecorationHelpers.formatMarkers(Set.of(new Marker("review")), "unknownStyle"));
    }

    @Test
    public void nullStyleFallsBackToSuffix()
    {
        assertEquals(" [review]", MarkerDecorationHelpers.formatMarkers(Set.of(new Marker("review")), null));
    }

    // ------ First-marker style ------

    @Test
    public void firstMarkerStyleWithSingleMarker()
    {
        assertEquals(" [wip]",
            MarkerDecorationHelpers.formatMarkers(Set.of(new Marker("wip")), PrefKeys.MARKERS_STYLE_FIRST_MARKER));
    }

    @Test
    public void firstMarkerStyleShowsOnlyTheLeadingName()
    {
        Set<Marker> markers = new LinkedHashSet<>();
        markers.add(new Marker("first"));
        markers.add(new Marker("second"));
        markers.add(new Marker("third"));
        assertEquals(" [first]",
            MarkerDecorationHelpers.formatMarkers(markers, PrefKeys.MARKERS_STYLE_FIRST_MARKER));
    }

    // ------ Count style ------

    @Test
    public void countStyleReportsSingleMarkerCount()
    {
        assertEquals(" [1 markers]",
            MarkerDecorationHelpers.formatMarkers(Set.of(new Marker("todo")), PrefKeys.MARKERS_STYLE_COUNT));
    }

    @Test
    public void countStyleReportsMultipleMarkerCount()
    {
        Set<Marker> markers = new LinkedHashSet<>();
        markers.add(new Marker("a"));
        markers.add(new Marker("b"));
        markers.add(new Marker("c"));
        assertEquals(" [3 markers]", MarkerDecorationHelpers.formatMarkers(markers, PrefKeys.MARKERS_STYLE_COUNT));
    }

    // ------ Preference constant values ------

    @Test
    public void styleConstantsHoldTheirYamlValues()
    {
        assertEquals("suffix", PrefKeys.MARKERS_STYLE_SUFFIX);
        assertEquals("firstMarker", PrefKeys.MARKERS_STYLE_FIRST_MARKER);
        assertEquals("count", PrefKeys.MARKERS_STYLE_COUNT);
    }
}
