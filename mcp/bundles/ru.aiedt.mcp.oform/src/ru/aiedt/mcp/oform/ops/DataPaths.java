/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ru.aiedt.mcp.oform.BraceTree;
import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.LayoutNames;
import ru.aiedt.mcp.oform.layout.StandardAttributes;

/**
 * Data paths in words and the type patterns of values: what the element and column operations
 * share when they turn {@code СправочникОбъект.Артикул} into the segments a record carries.
 */
public final class DataPaths
{
    private DataPaths()
    {
        // static helpers
    }

    /**
     * Turns a data path in words into the segments the layout records.
     *
     * @param layout the layout, resolved against the names
     * @param names the configuration's names
     * @param ownerKind the owner's kind, which names the standard attributes of the main attribute
     * @param data the path in words
     * @param path receives the segments
     * @param patternOut receives the type pattern of the value the path ends at, or {@code null}
     * @return {@code null} when resolved, a message otherwise
     */
    public static String resolve(FormLayout layout, LayoutNames names, String ownerKind, String data,
        List<FormLayout.Segment> path, ListNode[] patternOut)
    {
        String[] parts = data.split("\\."); //$NON-NLS-1$
        FormLayout.Attribute attribute = null;
        for (FormLayout.Attribute candidate : layout.attributes)
        {
            if (candidate.name.equalsIgnoreCase(parts[0]))
            {
                attribute = candidate;
            }
        }
        if (attribute == null)
        {
            List<String> known = new ArrayList<>();
            for (FormLayout.Attribute candidate : layout.attributes)
            {
                known.add(candidate.name);
            }
            return "No form attribute named " + parts[0] + "; the form has: " + String.join(", ", known) + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        path.add(new FormLayout.Segment(FormLayout.Segment.ATTRIBUTE, attribute.id));
        String typeUuid = attribute.pattern == null ? null : attribute.pattern.singleProducedType();
        String kind = typeUuid == null ? null : names.ownerKind(typeUuid);
        if (kind == null && attribute.id.equals(layout.mainAttributeId))
        {
            kind = ownerKind;
        }
        if (parts.length == 1)
        {
            patternOut[0] = attribute.pattern == null ? null : LayoutEditor.copyOf(attribute.pattern.node());
            return null;
        }
        for (int i = 1; i < parts.length; i++)
        {
            String part = parts[i];
            String uuid = typeUuid == null ? null : names.memberUuid(typeUuid, part);
            if (uuid != null)
            {
                path.add(new FormLayout.Segment(FormLayout.Segment.MEMBER, uuid));
                patternOut[0] = names.memberPattern(typeUuid, uuid);
                kind = names.referencedKind(typeUuid, uuid);
                String next = names.memberTypeUuid(typeUuid, uuid);
                typeUuid = next != null ? next : typeUuid;
                continue;
            }
            Integer code = StandardAttributes.codeOf(kind, part);
            if (code != null)
            {
                path.add(new FormLayout.Segment(FormLayout.Segment.STANDARD, Integer.toString(code.intValue())));
                patternOut[0] = standardPattern(part);
                kind = "Родитель".equalsIgnoreCase(part) ? kind : null; //$NON-NLS-1$
                continue;
            }
            return "No member named " + part + " under " + String.join(".", java.util.Arrays.copyOf(parts, i)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + (names == LayoutNames.NONE ? " (the configuration is not loaded in this environment)" : ""); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    /**
     * The type pattern of a standard attribute by name: dates for Дата and Период, strings for
     * Код, Наименование and Номер.
     *
     * @param name the standard attribute's name
     * @return the pattern, or {@code null}
     */
    public static ListNode standardPattern(String name)
    {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("дата") || lower.equals("период")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return BraceTree.parse("{\"Pattern\",{\"D\"}}"); //$NON-NLS-1$
        }
        if (lower.equals("код") || lower.equals("наименование") || lower.equals("номер")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return BraceTree.parse("{\"Pattern\",{\"S\"}}"); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * The type pattern of a value type named in words.
     *
     * @param word String, Number, Date or Boolean, in English or Russian
     * @return the pattern, or {@code null} for another word
     */
    public static ListNode typePattern(String word)
    {
        switch (word.trim().toLowerCase(Locale.ROOT))
        {
        case "string": //$NON-NLS-1$
        case "строка": //$NON-NLS-1$
            return BraceTree.parse("{\"Pattern\",{\"S\"}}"); //$NON-NLS-1$
        case "number": //$NON-NLS-1$
        case "число": //$NON-NLS-1$
            return BraceTree.parse("{\"Pattern\",{\"N\",15,2,0}}"); //$NON-NLS-1$
        case "date": //$NON-NLS-1$
        case "дата": //$NON-NLS-1$
            return BraceTree.parse("{\"Pattern\",{\"D\"}}"); //$NON-NLS-1$
        case "boolean": //$NON-NLS-1$
        case "булево": //$NON-NLS-1$
            return BraceTree.parse("{\"Pattern\",{\"B\"}}"); //$NON-NLS-1$
        default:
            return null;
        }
    }

    /** @return the width a new element of the kind gets when the caller names none */
    public static int defaultWidth(String type)
    {
        String lower = type.toLowerCase(Locale.ROOT);
        if (lower.equals("button")) //$NON-NLS-1$
        {
            return 80;
        }
        if (lower.equals("table")) //$NON-NLS-1$
        {
            return 400;
        }
        if (lower.equals("label") || lower.equals("checkbox") || lower.equals("radiobtn")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return 120;
        }
        if (lower.equals("group") || lower.equals("panel")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return 300;
        }
        return 200;
    }

    /** @return the height a new element of the kind gets when the caller names none */
    public static int defaultHeight(String type)
    {
        String lower = type.toLowerCase(Locale.ROOT);
        if (lower.equals("button")) //$NON-NLS-1$
        {
            return 23;
        }
        if (lower.equals("group") || lower.equals("panel") || lower.equals("table")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return 150;
        }
        return 19;
    }
}
