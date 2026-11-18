/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.layout;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which kinds of element a property of the layout was measured on: the events list's position per
 * kind, the kinds that carry a caption, a style block, a read-only flag, an alignment, a format.
 * <p>
 * The sets live here once; the writers ({@link LayoutEditor}, {@link ElementStyle},
 * {@link LayoutReader#captionNode(String, BraceTree.ListNode)}) read them from here, and the
 * session snapshot answers them to the property panel, so what a writer refuses a kind for and
 * what the panel calls "the kind has none" cannot drift apart.
 * </p>
 */
public final class PropertyApplicability
{
    /** The kinds whose own list carries an events list, and where it sits. */
    private static final Map<String, Integer> EVENTS_AT = Map.ofEntries(Map.entry("Field", 4), //$NON-NLS-1$
        Map.entry("SelectField", 2), Map.entry("CheckBox", 2), Map.entry("RadioBtn", 5), Map.entry("Button", 2), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Map.entry("Label", 2), Map.entry("Image", 2), Map.entry("Panel", 2), Map.entry("Table", 4), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-4$
        Map.entry("ListField", 2), Map.entry("CalendarBox", 2), Map.entry("FieldHtml", 2), Map.entry("TableField", 17), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-4$
        Map.entry("Chart", 201)); //$NON-NLS-1$

    /** The kinds that carry a caption, and the path of the caption node in their own list. */
    private static final Map<String, int[]> CAPTION_PATHS = captionPaths();

    /** The kinds whose style block was measured; every other kind is refused by name. */
    private static final Set<String> STYLE_KINDS = new LinkedHashSet<>(java.util.List.of("Label", "Button", "Group", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "Image", "Separator", "SelectField", "Panel", "CommandPanel", "CheckBox", "Field", "RadioBtn", "Table")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$

    /** The kinds whose read-only flag was measured. */
    private static final Set<String> READ_ONLY_KINDS = new LinkedHashSet<>(java.util.List.of("Field")); //$NON-NLS-1$

    /** The kinds whose horizontal alignment was measured. */
    private static final Set<String> ALIGNMENT_KINDS = new LinkedHashSet<>(java.util.List.of("Label", "Button", "Field")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** The kinds whose format string was measured. */
    private static final Set<String> FORMAT_KINDS = new LinkedHashSet<>(java.util.List.of("Field")); //$NON-NLS-1$

    private PropertyApplicability()
    {
        // static registry
    }

    private static Map<String, int[]> captionPaths()
    {
        Map<String, int[]> paths = new LinkedHashMap<>();
        paths.put("Label", new int[] { 1, 2 }); //$NON-NLS-1$
        paths.put("Button", new int[] { 1, 2 }); //$NON-NLS-1$
        paths.put("Group", new int[] { 1, 2 }); //$NON-NLS-1$
        paths.put("CheckBox", new int[] { 1, 0, 2 }); //$NON-NLS-1$
        paths.put("RadioBtn", new int[] { 2, 0, 2 }); //$NON-NLS-1$
        return Collections.unmodifiableMap(paths);
    }

    /**
     * Where a kind's own list carries its events list.
     *
     * @param kind the element kind as the reader names it
     * @return the position, or {@code null} for a kind without a measured events list
     */
    public static Integer eventsAt(String kind)
    {
        return kind == null ? null : EVENTS_AT.get(kind);
    }

    /** @return the kinds whose own list carries a measured events list, in registration order */
    public static Set<String> eventKinds()
    {
        return new LinkedHashSet<>(EVENTS_AT.keySet());
    }

    /**
     * The path of a kind's caption node in its own list.
     *
     * @param kind the element kind as the reader names it
     * @return the positions, or {@code null} for a kind without a measured caption
     */
    public static int[] captionPath(String kind)
    {
        return kind == null ? null : CAPTION_PATHS.get(kind);
    }

    /** @return the kinds that carry a caption, in registration order */
    public static Set<String> captionKinds()
    {
        return new LinkedHashSet<>(CAPTION_PATHS.keySet());
    }

    /** @return the kinds whose style block was measured, in registration order */
    public static Set<String> styleKinds()
    {
        return new LinkedHashSet<>(STYLE_KINDS);
    }

    /** @return the kinds whose read-only flag was measured, in registration order */
    public static Set<String> readOnlyKinds()
    {
        return new LinkedHashSet<>(READ_ONLY_KINDS);
    }

    /** @return the kinds whose alignment was measured, in registration order */
    public static Set<String> alignmentKinds()
    {
        return new LinkedHashSet<>(ALIGNMENT_KINDS);
    }

    /** @return the kinds whose format was measured, in registration order */
    public static Set<String> formatKinds()
    {
        return new LinkedHashSet<>(FORMAT_KINDS);
    }
}
