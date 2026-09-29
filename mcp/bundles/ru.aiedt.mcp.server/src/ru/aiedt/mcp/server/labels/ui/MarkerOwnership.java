/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.ui;

import java.util.List;
import java.util.function.Function;

import ru.aiedt.mcp.server.labels.model.Marker;

/**
 * Finds the project a marker instance belongs to.
 * <p>
 * {@link Marker#equals(Object)} compares names only, so {@code List.contains} answers the first project
 * that happens to define the same name. Two projects can both define {@code bug}. The owner is the
 * project whose own marker list holds this instance: the pair of that project and that name, not the
 * name by itself.
 * </p>
 */
public final class MarkerOwnership
{
    private MarkerOwnership()
    {
        // Static helpers.
    }

    /**
     * Returns the project that holds this marker instance.
     *
     * @param <P> the project type
     * @param projects the projects to search, in display order
     * @param marker the marker instance shown in the tree
     * @param markersOf the markers defined by a project
     * @return the owning project, or <code>null</code> when none of the lists holds that instance
     */
    public static <P> P projectOf(List<P> projects, Marker marker, Function<P, List<Marker>> markersOf)
    {
        if (projects == null || marker == null || markersOf == null)
        {
            return null;
        }
        for (P project : projects)
        {
            List<Marker> markers = markersOf.apply(project);
            if (markers == null)
            {
                continue;
            }
            for (Marker candidate : markers)
            {
                if (candidate == marker)
                {
                    return project;
                }
            }
        }
        return null;
    }
}
