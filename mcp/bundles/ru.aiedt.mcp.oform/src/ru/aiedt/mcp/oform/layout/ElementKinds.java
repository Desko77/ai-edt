/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.layout;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The kinds of elements an ordinary form holds: the type id of each, its name, and whether an
 * editor operation creates it.
 * <p>
 * The reader names a record by its type id here; the editor asks here whether a kind is
 * writable, and the corpus census reads the {@link #WRITABLE} literal off this file: a kind added
 * to the set without a measured template and a platform check is a defect, not a feature.
 * </p>
 */
public final class ElementKinds
{
    /** The uuid of the root panel and of every panel. */
    public static final String PANEL_TYPE = "09ccdc77-ea1a-4a6d-ab1c-3435eada2433"; //$NON-NLS-1$

    /**
     * The kinds some editor operation creates from a measured template. The corpus census
     * ({@code scripts/corpus/kinds_census.py --writable-from}) reads this literal.
     */
    public static final Set<String> WRITABLE = Set.of("Label", "Field", "Button", "CheckBox", "Group", "Panel", "Table", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        "RadioBtn", "Separator", "Image", "SelectField", "CommandPanel", "TableField", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "FieldHtml", "ListField", "TextDocumentField", "Chart"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    private static final Map<String, String> NAMES = new HashMap<>();

    private static final Map<String, String> IDS = new LinkedHashMap<>();

    static
    {
        put("381ed624-9217-4e63-85db-c4c3cb87daae", "Field"); //$NON-NLS-1$ //$NON-NLS-2$
        put("35af3d93-d7c7-4a2e-a8eb-bac87a1a3f26", "CheckBox"); //$NON-NLS-1$ //$NON-NLS-2$
        put("782e569a-79a7-4a4f-a936-b48d013936ec", "RadioBtn"); //$NON-NLS-1$ //$NON-NLS-2$
        put("64483e7f-3833-48e2-8c75-2c31aac49f6e", "SelectField"); //$NON-NLS-1$ //$NON-NLS-2$
        put("e69bf21d-97b2-4f37-86db-675aea9ec2cb", "CommandPanel"); //$NON-NLS-1$ //$NON-NLS-2$
        put("6ff79819-710e-4145-97cd-1618da79e3e2", "Button"); //$NON-NLS-1$ //$NON-NLS-2$
        put("151ef23e-6bb2-4681-83d0-35bc2217230c", "Image"); //$NON-NLS-1$ //$NON-NLS-2$
        put("90db814a-c75f-4b54-bc96-df62e554d67d", "Group"); //$NON-NLS-1$ //$NON-NLS-2$
        put("ea83fe3a-ac3c-4cce-8045-3dddf35b28b1", "Table"); //$NON-NLS-1$ //$NON-NLS-2$
        put("236a17b3-7f44-46d9-a907-75f9cdc61ab5", "TableField"); //$NON-NLS-1$ //$NON-NLS-2$
        put(PANEL_TYPE, "Panel"); //$NON-NLS-1$
        put("0fc7e20d-f241-460c-bdf4-5ad88e5474a5", "Label"); //$NON-NLS-1$ //$NON-NLS-2$
        put("19f8b798-314e-4b4e-8121-905b2a7a03f5", "ListField"); //$NON-NLS-1$ //$NON-NLS-2$
        put("36e52348-5d60-4770-8e89-a16ed50a2006", "Separator"); //$NON-NLS-1$ //$NON-NLS-2$
        put("d92a805c-98ae-4750-9158-d9ce7cec2f20", "FieldHtml"); //$NON-NLS-1$ //$NON-NLS-2$
        put("b1db1f86-abbb-4cf0-8852-fe6ae21650c2", "Indicator"); //$NON-NLS-1$ //$NON-NLS-2$
        put("e3c063d8-ef92-41be-9c89-b70290b5368b", "CalendarBox"); //$NON-NLS-1$ //$NON-NLS-2$
        put("6c06cd5d-8481-4b6f-a90a-7a97a8bb8bef", "TrackBar"); //$NON-NLS-1$ //$NON-NLS-2$
        put("14c4a229-bfc3-42fe-9ce1-2da049fd0109", "TextDocumentField"); //$NON-NLS-1$ //$NON-NLS-2$
        put("42248403-7748-49da-b782-e4438fd7bff3", "GraphicalSchemaField"); //$NON-NLS-1$ //$NON-NLS-2$
        put("ad37194e-555e-4305-b718-5dca84baf145", "GeographicalSchemaField"); //$NON-NLS-1$ //$NON-NLS-2$
        put("a8b97779-1a4b-4059-b09c-807f86d2a461", "Chart"); //$NON-NLS-1$ //$NON-NLS-2$
        put("e5fdc112-5c84-4a16-9728-72b85692b6e2", "GanttChart"); //$NON-NLS-1$ //$NON-NLS-2$
        put("a26da99e-184a-4823-b0d6-62816d38dc4e", "PivotChart"); //$NON-NLS-1$ //$NON-NLS-2$
        put("984981b1-622d-4ebc-94f7-885f0cdfb59a", "Dendrogram"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private ElementKinds()
    {
        // static registry
    }

    private static void put(String uuid, String name)
    {
        NAMES.put(uuid.toLowerCase(Locale.ROOT), name);
        IDS.put(name, uuid);
    }

    /** @return the kind's name for a type id, or {@code null}; case does not matter */
    public static String nameOf(String typeId)
    {
        return typeId == null ? null : NAMES.get(typeId.toLowerCase(Locale.ROOT));
    }

    /** @return the type id of a kind, or {@code null}; case does not matter */
    public static String idOf(String name)
    {
        String canonical = canonical(name);
        return canonical == null ? null : IDS.get(canonical);
    }

    /** @return the kind's name as spelled here, or {@code null} when unknown; case does not matter */
    public static String canonical(String name)
    {
        if (name == null)
        {
            return null;
        }
        for (String known : IDS.keySet())
        {
            if (known.equalsIgnoreCase(name.trim()))
            {
                return known;
            }
        }
        return null;
    }

    /** @return whether the type id is a kind this registry knows */
    public static boolean isKnown(String typeId)
    {
        return nameOf(typeId) != null;
    }

    /** @return the names of every kind, in registration order */
    public static Set<String> names()
    {
        return Collections.unmodifiableSet(IDS.keySet());
    }

    /** @return whether an editor operation creates the kind; case does not matter */
    public static boolean isWritable(String name)
    {
        String canonical = canonical(name);
        return canonical != null && WRITABLE.contains(canonical);
    }

    /** @return the writable kinds, lower case, sorted */
    public static Set<String> writableNamesLowerCase()
    {
        Set<String> out = new TreeSet<>();
        for (String kind : WRITABLE)
        {
            out.add(kind.toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
