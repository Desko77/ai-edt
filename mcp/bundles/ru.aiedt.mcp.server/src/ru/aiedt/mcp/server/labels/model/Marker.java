/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.model;

import java.util.Objects;

/**
 * A named, colored label that a user can attach to metadata objects.
 * <p>
 * A marker carries a display name, a color as a {@code #RRGGBB} hex string, and a free-text
 * description. Only the name identifies it: two markers with the same name are the same marker as far as
 * {@link #equals(Object)} and {@link #hashCode()} are concerned, whatever their color or
 * description. The whole subsystem keys markers by name - assignments store names, selection state is
 * matched by name - so name-only identity is what lets a {@code Marker} stand in for its name inside a
 * set.
 * </p>
 * <p>
 * This is a plain bean: SnakeYAML reads and writes it through the no-argument constructor and the
 * accessors, which is why the setters exist and why nothing here reaches outside the class.
 * </p>
 */
public class Marker
{
    /** The color a marker falls back to when none is given - a neutral mid-gray. */
    private static final String DEFAULT_COLOR = "#808080"; //$NON-NLS-1$

    private String name;

    private String color;

    private String description;

    /**
     * Creates a marker with the given name, the default gray color and an empty description.
     *
     * @param name the marker name; must not be <code>null</code>
     */
    public Marker(String name)
    {
        this(name, DEFAULT_COLOR, ""); //$NON-NLS-1$
    }

    /**
     * Creates a marker with the given name and color and an empty description.
     *
     * @param name the marker name; must not be <code>null</code>
     * @param color a {@code #RRGGBB} hex color, or <code>null</code> for the default gray
     */
    public Marker(String name, String color)
    {
        this(name, color, ""); //$NON-NLS-1$
    }

    /**
     * Creates a fully specified marker.
     *
     * @param name the marker name; must not be <code>null</code>
     * @param color a {@code #RRGGBB} hex color, or <code>null</code> for the default gray
     * @param description a description, or <code>null</code> for an empty one
     */
    public Marker(String name, String color, String description)
    {
        this.name = Objects.requireNonNull(name, "Marker name cannot be null"); //$NON-NLS-1$
        this.color = color != null ? color : DEFAULT_COLOR;
        this.description = description != null ? description : ""; //$NON-NLS-1$
    }

    /**
     * Creates an empty marker for the YAML reader to fill in through the setters.
     */
    public Marker()
    {
        this.name = ""; //$NON-NLS-1$
        this.color = DEFAULT_COLOR;
        this.description = ""; //$NON-NLS-1$
    }

    /**
     * Returns the marker name.
     *
     * @return the name
     */
    public String getName()
    {
        return name;
    }

    /**
     * Sets the marker name.
     *
     * @param name the new name
     */
    public void setName(String name)
    {
        this.name = name;
    }

    /**
     * Returns the marker color.
     *
     * @return a {@code #RRGGBB} hex color
     */
    public String getColor()
    {
        return color;
    }

    /**
     * Sets the marker color.
     *
     * @param color a {@code #RRGGBB} hex color
     */
    public void setColor(String color)
    {
        this.color = color;
    }

    /**
     * Returns the marker description.
     *
     * @return the description, possibly empty
     */
    public String getDescription()
    {
        return description;
    }

    /**
     * Sets the marker description.
     *
     * @param description the new description
     */
    public void setDescription(String description)
    {
        this.description = description;
    }

    /**
     * Two markers are equal when they are both {@code Marker} instances with the same name. Color and
     * description are deliberately not part of identity.
     *
     * @param obj the object to compare with
     * @return <code>true</code> when {@code obj} is a marker with an equal name
     */
    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (obj == null || getClass() != obj.getClass())
        {
            return false;
        }
        Marker other = (Marker)obj;
        return Objects.equals(name, other.name);
    }

    /**
     * Hashes on the name alone, to agree with {@link #equals(Object)}.
     *
     * @return the hash of the name
     */
    @Override
    public int hashCode()
    {
        return Objects.hash(name);
    }

    /**
     * Returns the marker name, so a marker reads as its name wherever a string is expected.
     *
     * @return the name
     */
    @Override
    public String toString()
    {
        return name;
    }
}
