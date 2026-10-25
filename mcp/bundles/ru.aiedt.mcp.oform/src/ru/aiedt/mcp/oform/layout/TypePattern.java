/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.layout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ru.aiedt.mcp.oform.BraceTree;
import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.BraceTree.Node;
import ru.aiedt.mcp.oform.BraceTree.Token;

/**
 * A type pattern as the layout records it: {@code {"Pattern",{"#",<uuid>},{"S",100,1},...}}.
 *
 * <p>Measured over the corpus: a type item opens with {@code "#"} and a produced-type uuid,
 * {@code "S"} with an optional length and a variable-length flag, {@code "N"} with digits,
 * fraction digits and a sign flag, {@code "D"} with an optional date-fractions token, or
 * {@code "B"}. A pattern without items admits any value. A {@code "#"} item may also name a
 * type of the platform rather than a produced one; the ids below were voted by the names of the
 * attributes that carry them over the corpus (203 value tables named as tables, 66 value lists
 * named as lists, and so on), and an id outside the table stays an id.</p>
 */
public final class TypePattern
{
    /** The ids of the platform's own types, lower case, to their names. */
    static final Map<String, String> PLATFORM_TYPES = new HashMap<>();

    static
    {
        PLATFORM_TYPES.put("acf6192e-81ca-46ef-93a6-5a6968b78663", "ValueTable"); //$NON-NLS-1$ //$NON-NLS-2$
        PLATFORM_TYPES.put("4772b3b4-f4a3-49c0-a1a5-8cb5961511a3", "ValueList"); //$NON-NLS-1$ //$NON-NLS-2$
        PLATFORM_TYPES.put("e603c0f2-92fb-4d47-8f38-a44a381cf235", "ValueTree"); //$NON-NLS-1$ //$NON-NLS-2$
        PLATFORM_TYPES.put("0dda99d9-ae9f-43d2-b7ac-44f3fb0d4059", "ReportBuilder"); //$NON-NLS-1$ //$NON-NLS-2$
        PLATFORM_TYPES.put("b1b064f3-ae38-49bf-8c6d-390c65fd94af", "ComparisonType"); //$NON-NLS-1$ //$NON-NLS-2$
        PLATFORM_TYPES.put("4652c4ec-1d1d-4af4-b835-e33fcb43af8c", "Filter"); //$NON-NLS-1$ //$NON-NLS-2$
        PLATFORM_TYPES.put("0bbd3a56-3629-40a6-bf30-f3ea76d721a5", "Order"); //$NON-NLS-1$ //$NON-NLS-2$
        PLATFORM_TYPES.put("dcfc3784-a14f-4786-ac7b-c82db5ba275f", "ConstantsSet"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private final ListNode node;

    private TypePattern(ListNode node)
    {
        this.node = node;
    }

    /**
     * The pattern held by a node, when the node is one.
     *
     * @param node a node of the tree
     * @return the pattern, or {@code null} when the node is not a pattern
     */
    public static TypePattern of(Node node)
    {
        ListNode list = node instanceof ListNode ? (ListNode)node : null;
        if (list == null || list.size() < 1 || !"\"Pattern\"".equals(LayoutReader.tokenText(list.get(0)))) //$NON-NLS-1$
        {
            return null;
        }
        return new TypePattern(list);
    }

    /**
     * The first pattern found under a node, depth first.
     *
     * @param node the node to search
     * @return the pattern, or {@code null}
     */
    public static TypePattern find(Node node)
    {
        TypePattern own = of(node);
        if (own != null)
        {
            return own;
        }
        if (node instanceof ListNode)
        {
            for (Node item : ((ListNode)node).items())
            {
                TypePattern found = find(item);
                if (found != null)
                {
                    return found;
                }
            }
        }
        return null;
    }

    /** @return the node of the pattern, as recorded */
    public ListNode node()
    {
        return node;
    }

    /**
     * The produced-type uuids the pattern names.
     *
     * @return the uuids, lower case, in order
     */
    public List<String> producedTypeUuids()
    {
        List<String> out = new ArrayList<>();
        for (int i = 1; i < node.size(); i++)
        {
            ListNode item = LayoutReader.asList(node.get(i));
            if (item != null && item.size() == 2 && "\"#\"".equals(LayoutReader.tokenText(item.get(0)))) //$NON-NLS-1$
            {
                String uuid = LayoutReader.tokenText(item.get(1));
                if (uuid != null)
                {
                    out.add(uuid.toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    /**
     * The type letters of a pattern: {@code S}, {@code N}, {@code D}, {@code B} or {@code #} per
     * type item, in order; empty when the pattern is absent or admits any value.
     *
     * @param pattern the pattern node, or {@code null}
     * @return the letters
     */
    public static List<String> letters(ListNode pattern)
    {
        List<String> out = new ArrayList<>();
        if (pattern != null)
        {
            for (int i = 1; i < pattern.size(); i++)
            {
                ListNode item = LayoutReader.asList(pattern.get(i));
                String letter = item == null || item.size() == 0 ? null : LayoutReader.tokenText(item.get(0));
                if (letter != null && letter.length() >= 2 && letter.startsWith("\"")) //$NON-NLS-1$
                {
                    out.add(letter.substring(1, letter.length() - 1));
                }
            }
        }
        return out;
    }

    /**
     * The one produced type the pattern names, when it names exactly one type and that is it.
     *
     * @return the uuid, lower case, or {@code null}
     */
    public String singleProducedType()
    {
        List<String> uuids = producedTypeUuids();
        return uuids.size() == 1 && node.size() == 2 ? uuids.get(0) : null;
    }

    /**
     * The pattern in words: the types it admits, comma separated, or {@code Any} for none.
     *
     * @param names what resolves a produced-type uuid to a type name
     * @return the words
     */
    public String describe(LayoutNames names)
    {
        List<String> parts = new ArrayList<>();
        for (int i = 1; i < node.size(); i++)
        {
            ListNode item = LayoutReader.asList(node.get(i));
            if (item == null || item.size() == 0)
            {
                continue;
            }
            String kind = LayoutReader.tokenText(item.get(0));
            String text;
            if ("\"#\"".equals(kind) && item.size() == 2) //$NON-NLS-1$
            {
                String uuid = LayoutReader.tokenText(item.get(1));
                String name = uuid == null ? null : names.typeName(uuid.toLowerCase(Locale.ROOT));
                if (name == null && uuid != null)
                {
                    name = PLATFORM_TYPES.get(uuid.toLowerCase(Locale.ROOT));
                }
                text = name != null ? name : uuid;
            }
            else if ("\"S\"".equals(kind)) //$NON-NLS-1$
            {
                String length = item.size() > 1 ? LayoutReader.tokenText(item.get(1)) : null;
                text = length == null || "0".equals(length) ? "String" : "String(" + length + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            }
            else if ("\"N\"".equals(kind)) //$NON-NLS-1$
            {
                String digits = item.size() > 1 ? LayoutReader.tokenText(item.get(1)) : null;
                String fraction = item.size() > 2 ? LayoutReader.tokenText(item.get(2)) : null;
                text = digits == null ? "Number" : "Number(" + digits + (fraction == null || "0".equals(fraction) ? "" : "," + fraction) + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            }
            else if ("\"D\"".equals(kind)) //$NON-NLS-1$
            {
                String fractions = item.size() > 1 ? LayoutReader.tokenText(item.get(1)) : null;
                text = "\"T\"".equals(fractions) ? "Time" : "\"DT\"".equals(fractions) ? "DateTime" : "Date"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            }
            else if ("\"B\"".equals(kind)) //$NON-NLS-1$
            {
                text = "Boolean"; //$NON-NLS-1$
            }
            else
            {
                text = BraceTree.serialize(item).replace("\r\n", ""); //$NON-NLS-1$ //$NON-NLS-2$
            }
            parts.add(text);
        }
        return parts.isEmpty() ? "Any" : String.join(", ", parts); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The name of a platform type by the id a pattern records, for callers outside a pattern.
     *
     * @param uuid the id, either case
     * @return the name, or {@code null}
     */
    public static String platformType(String uuid)
    {
        return uuid == null ? null : PLATFORM_TYPES.get(uuid.toLowerCase(Locale.ROOT));
    }

    /**
     * A pattern for a produced type.
     *
     * @param uuid the produced-type uuid
     * @return the pattern
     */
    public static ListNode ofProducedType(String uuid)
    {
        ListNode item = new ListNode();
        item.items().add(new Token("\"#\"")); //$NON-NLS-1$
        item.items().add(new Token(uuid));
        ListNode pattern = new ListNode();
        pattern.items().add(new Token("\"Pattern\"")); //$NON-NLS-1$
        pattern.items().add(item);
        return pattern;
    }
}
