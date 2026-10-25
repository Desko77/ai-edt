/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The controls a table column can carry in its cells, by name.
 * <p>
 * {@code field} is the input field, {@code checkBox} the check box, {@code selectField} the
 * choice field and {@code none} a column that only shows; every one carries the uuid the column
 * record holds at body position 38. A control registers the value types it was measured with:
 * the letters of the type pattern ({@code S}, {@code N}, {@code D}, {@code B}, {@code #} for a
 * reference), the word {@code none} for the untyped pattern, or an empty list for any. The
 * column operation looks the control up here and refuses a pair that was not measured.
 * </p>
 */
public final class ColumnControls
{
    /** A control a cell can carry. */
    public static final class ColumnControl
    {
        public final String name;

        /** Whether the cell gets an editor: what {@code editable} meant before controls had names. */
        public final boolean editor;

        /** The value type letters the control was measured with, {@code none} for the untyped pattern; empty means any. */
        public final List<String> valueTypes;

        /** The uuid of the control in the column record. */
        public final String uuid;

        ColumnControl(String name, boolean editor, List<String> valueTypes, String uuid)
        {
            this.name = name;
            this.editor = editor;
            this.valueTypes = List.copyOf(valueTypes);
            this.uuid = uuid;
        }
    }

    private static final Map<String, ColumnControl> CONTROLS = new LinkedHashMap<>();

    static
    {
        register("field", true, List.of(), "381ed624-9217-4e63-85db-c4c3cb87daae"); //$NON-NLS-1$ //$NON-NLS-2$
        // the corpus holds a check box on string (193) and number (18) columns as well, and the
        // platform reads those as no control at all: measured on the stand, a check box over a
        // boolean column is read as Флажок, over a string column as Не определено
        register("checkBox", true, List.of("B"), "35af3d93-d7c7-4a2e-a8eb-bac87a1a3f26"); //$NON-NLS-1$ //$NON-NLS-2$
        register("selectField", true, List.of("S", "N", "B", "#", "none"), "64483e7f-3833-48e2-8c75-2c31aac49f6e"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        register("none", false, List.of(), "00000000-0000-0000-0000-000000000000"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private ColumnControls()
    {
        // static registry
    }

    /**
     * Registers a control. A name registered twice is a programming error.
     *
     * @param name the control's name as the argument spells it
     * @param editor whether the cell gets an editor
     * @param valueTypes the value type letters the control was measured with, {@code none} for
     *        the untyped pattern; empty for any
     * @param uuid the uuid of the control in the column record
     */
    public static synchronized void register(String name, boolean editor, List<String> valueTypes, String uuid)
    {
        String key = name.toLowerCase(Locale.ROOT);
        if (CONTROLS.containsKey(key))
        {
            throw new IllegalStateException("The column control " + name + " is registered twice"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        CONTROLS.put(key, new ColumnControl(name, editor, valueTypes, uuid));
    }

    /** @return the control, or {@code null} */
    public static ColumnControl find(String name)
    {
        return name == null ? null : CONTROLS.get(name.trim().toLowerCase(Locale.ROOT));
    }

    /** @return the names as registered, in order */
    public static List<String> names()
    {
        List<String> out = new ArrayList<>();
        for (ColumnControl control : CONTROLS.values())
        {
            out.add(control.name);
        }
        return out;
    }
}
