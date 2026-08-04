/**

 * AI-EDT - 1C AI tools for EDT

 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)

 * Licensed under AGPL-3.0-or-later

 */



package ru.aiedt.mcp.server.labels;



import java.util.Set;

import java.util.stream.Collectors;



import ru.aiedt.mcp.server.settings.PrefKeys;

import ru.aiedt.mcp.server.labels.model.Marker;



/**

 * Turns the set of markers on an object into the suffix shown after its name in the Navigator.

 * <p>

 * The chosen preference decides the shape: a count like {@code  [3 markers]}, the first marker alone, or -

 * the default and the fallback for anything unrecognized - every marker name in brackets. Every non-empty

 * result begins with a single space, which is what separates the suffix from the object's own label.

 * </p>

 */

public final class MarkerDecorationHelpers

{

    private MarkerDecorationHelpers()

    {

        // Static utility.

    }



    /**

     * Formats the marker suffix for one object.

     *

     * @param markers the markers on the object; may be <code>null</code> or empty

     * @param style one of the {@code PrefKeys.TAGS_STYLE_*} values; an unknown or

     *            <code>null</code> value is treated as the suffix style

     * @return the suffix, leading space included, or the empty string when there are no markers

     */

    public static String formatMarkers(Set<Marker> markers, String style)

    {

        if (markers == null || markers.isEmpty())

        {

            return ""; //$NON-NLS-1$

        }



        if (PrefKeys.MARKERS_STYLE_COUNT.equals(style))

        {

            return " [" + markers.size() + " markers]"; //$NON-NLS-1$ //$NON-NLS-2$

        }



        if (PrefKeys.MARKERS_STYLE_FIRST_MARKER.equals(style))

        {

            return " [" + markers.iterator().next().getName() + "]"; //$NON-NLS-1$ //$NON-NLS-2$

        }



        String joined = markers.stream().map(Marker::getName).collect(Collectors.joining(", ")); //$NON-NLS-1$

        return " [" + joined + "]"; //$NON-NLS-1$ //$NON-NLS-2$

    }

}

