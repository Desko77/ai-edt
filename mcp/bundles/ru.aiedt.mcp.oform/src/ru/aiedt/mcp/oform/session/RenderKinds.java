/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.session;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import ru.aiedt.mcp.oform.layout.ElementKinds;

/**
 * Which branch of the drawing draws a kind of element: one branch name per kind of
 * {@link ElementKinds#names()}, and the drawing of the UI bundle picks its figure factory by
 * that name. Ten kinds the editor draws no imitation of all take the {@link #PLACEHOLDER}
 * branch, which names the kind and the element and draws no contents.
 * <p>
 * Every kind of the registry is mapped exactly once - a kind without a branch here would have
 * no figure at all, a kind with two would be drawn twice - so the session's snapshot hands the
 * branch to the drawing with the element and a test reconciles the mapping against the registry.
 * </p>
 */
public final class RenderKinds
{
    /** A panel: a frame, a tab strip when it has pages, the children of its current page. */
    public static final String PANEL = "panel"; //$NON-NLS-1$

    /** A group: a frame with a caption. */
    public static final String GROUP = "group"; //$NON-NLS-1$

    /** A table: a grid with column captions. */
    public static final String TABLE = "table"; //$NON-NLS-1$

    /** A command panel: a bar with button captions. */
    public static final String COMMAND_PANEL = "commandPanel"; //$NON-NLS-1$

    /** A label: its caption. */
    public static final String LABEL = "label"; //$NON-NLS-1$

    /** A button: its caption. */
    public static final String BUTTON = "button"; //$NON-NLS-1$

    /** A check box: its flag and its caption. */
    public static final String CHECK_BOX = "checkBox"; //$NON-NLS-1$

    /** A radio button: its circle and its caption. */
    public static final String RADIO_BUTTON = "radioButton"; //$NON-NLS-1$

    /** A separator: a line along its box's longer side. */
    public static final String SEPARATOR = "separator"; //$NON-NLS-1$

    /** A field of input: its data path or its value type. */
    public static final String INPUT_FIELD = "inputField"; //$NON-NLS-1$

    /** A kind whose contents the editor draws no imitation of: its kind and its name, alone. */
    public static final String PLACEHOLDER = "placeholder"; //$NON-NLS-1$

    private static final Map<String, String> BY_KIND = branches();

    private RenderKinds()
    {
        // static registry
    }

    private static Map<String, String> branches()
    {
        Map<String, String> byKind = new LinkedHashMap<>();
        byKind.put("Field", INPUT_FIELD); //$NON-NLS-1$
        byKind.put("CheckBox", CHECK_BOX); //$NON-NLS-1$
        byKind.put("RadioBtn", RADIO_BUTTON); //$NON-NLS-1$
        byKind.put("SelectField", INPUT_FIELD); //$NON-NLS-1$
        byKind.put("CommandPanel", COMMAND_PANEL); //$NON-NLS-1$
        byKind.put("Button", BUTTON); //$NON-NLS-1$
        byKind.put("Image", PLACEHOLDER); //$NON-NLS-1$
        byKind.put("Group", GROUP); //$NON-NLS-1$
        byKind.put("Table", TABLE); //$NON-NLS-1$
        byKind.put("TableField", PLACEHOLDER); //$NON-NLS-1$
        byKind.put("Panel", PANEL); //$NON-NLS-1$
        byKind.put("Label", LABEL); //$NON-NLS-1$
        byKind.put("ListField", INPUT_FIELD); //$NON-NLS-1$
        byKind.put("Separator", SEPARATOR); //$NON-NLS-1$
        byKind.put("FieldHtml", PLACEHOLDER); //$NON-NLS-1$
        byKind.put("Indicator", INPUT_FIELD); //$NON-NLS-1$
        byKind.put("CalendarBox", INPUT_FIELD); //$NON-NLS-1$
        byKind.put("TrackBar", INPUT_FIELD); //$NON-NLS-1$
        byKind.put("TextDocumentField", PLACEHOLDER); //$NON-NLS-1$
        byKind.put("GraphicalSchemaField", PLACEHOLDER); //$NON-NLS-1$
        byKind.put("GeographicalSchemaField", PLACEHOLDER); //$NON-NLS-1$
        byKind.put("Chart", PLACEHOLDER); //$NON-NLS-1$
        byKind.put("GanttChart", PLACEHOLDER); //$NON-NLS-1$
        byKind.put("PivotChart", PLACEHOLDER); //$NON-NLS-1$
        byKind.put("Dendrogram", PLACEHOLDER); //$NON-NLS-1$
        return Collections.unmodifiableMap(byKind);
    }

    /**
     * The branch that draws a kind.
     *
     * @param kind the element kind as the reader names it ({@code Field}, {@code Panel}, ...)
     * @return the branch name, or {@code null} for a kind outside the registry
     */
    public static String branchOf(String kind)
    {
        return BY_KIND.get(kind);
    }

    /** @return the kinds and their branches, in registration order */
    public static Map<String, String> branchesByKind()
    {
        return BY_KIND;
    }

    /** @return the branch names, in registration order */
    public static Set<String> branchNames()
    {
        return new LinkedHashSet<>(BY_KIND.values());
    }
}
