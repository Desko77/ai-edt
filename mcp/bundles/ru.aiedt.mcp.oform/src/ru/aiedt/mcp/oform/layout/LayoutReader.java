/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.layout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ru.aiedt.mcp.oform.BraceTree;
import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.BraceTree.Node;
import ru.aiedt.mcp.oform.BraceTree.Token;

/**
 * Reads the layout of an ordinary form out of its brace tree.
 *
 * <p>What it reads was measured over a corpus of 1335 forms of every layout version from 19 to
 * 27. The root is {@code {<version>, <body>, <attribute table>, <events and the rest>...}}. The
 * body opens with a number, then the head with the title, then the root panel - a three-item
 * record {@code {<Panel uuid>, {1, <properties>, {0}}, {<count>, <elements>...}}} whose
 * properties hold the pages and whose third item is the counted list of the elements - then
 * the form's size. An element is a list of six items: the type uuid,
 * the element id, the type's own properties, the common properties, the identity list with
 * the name, the children; a Chart is seven, with a version tag {@code {11}} before its own
 * properties, so its lists sit one item further. In the common list the box is items 1 to 4
 * and the ordinal of the
 * page the element sits on is the fifth item from the end. In the own list a style block - a
 * list opening with a number and 1, whose thirteenth item is a localized string - carries the
 * tooltip, the caption sits at a position that depends on the type, a handler is an entry
 * {@code {<code>, e1692cc2-..., {3,"Procedure",{1,"Procedure",...}}}} in a counted list, a
 * command panel's button is {@code {8,"Name",...}}, a panel's page is a record with the
 * name as its seventh item. The attribute table is {@code {{<main id>}, n, {count, rows...},
 * {count, {<element id>, {count, segments...}}...}}}: a row is {@code {{<id>}, ..., "Name",
 * {"Pattern",...}}} with the name as its first string, a path segment is {@code {<attribute
 * id>}}, {@code {0,<uuid>}} for a metadata object or {@code {<negative code>}} for a standard
 * attribute. A record of a shape the corpus did not hold yields nothing rather than a wrong
 * value, and a record of an unknown type is counted under its type id.</p>
 */
public final class LayoutReader
{
    /** The uuid of the root panel and of every panel. */
    public static final String PANEL_TYPE = ElementKinds.PANEL_TYPE;

    /** The position of the tooltip inside a style block. */
    private static final int STYLE_TOOLTIP = 12;

    /** The distance of the page ordinal from the end of the common list. */
    static final int PAGE_FROM_END = 5;

    private final FormLayout layout = new FormLayout();

    /** The record of every element just read, for the style and anchor details. */
    private final Map<FormLayout.Element, ListNode> recordsOf = new IdentityHashMap<>();

    private LayoutReader()
    {
        // built through read
    }

    /**
     * Reads a layout. The ids stay ids; {@link FormLayout#resolve(LayoutNames)} names them.
     *
     * @param root the brace tree of the {@code form} entry
     * @param ownerKind the metadata kind of the form's owner in EDT's English spelling
     *        ({@code Catalog}, {@code Document}, ...), which names the standard attributes of the
     *        main attribute when the configuration does not, and decides what the events of the
     *        main attribute are; {@code null} for a form without a known owner
     * @return the layout
     */
    public static FormLayout read(ListNode root, String ownerKind)
    {
        LayoutReader reader = new LayoutReader();
        reader.layout.ownerKind = ownerKind;
        reader.layout.settingsHolder = "DataProcessor".equalsIgnoreCase(ownerKind) || "Report".equalsIgnoreCase(ownerKind); //$NON-NLS-1$ //$NON-NLS-2$
        reader.readInto(root);
        return reader.layout;
    }

    /**
     * Reads a layout and names its ids at once.
     *
     * @param root the brace tree of the {@code form} entry
     * @param ownerKind the owner's kind, as for {@link #read(ListNode, String)}
     * @param names what the configuration knows, or {@code null} for nothing
     * @return the layout, resolved
     */
    public static FormLayout read(ListNode root, String ownerKind, LayoutNames names)
    {
        FormLayout layout = read(root, ownerKind);
        layout.resolve(names == null ? LayoutNames.NONE : names);
        return layout;
    }

    /**
     * The name of an element type by its id, for callers that meet a record on their own.
     *
     * @param typeId the id, either case
     * @return the name, or {@code null}
     */
    public static String typeName(String typeId)
    {
        return ElementKinds.nameOf(typeId);
    }

    /**
     * The id of an element type by its name.
     *
     * @param typeName the name, case-insensitive
     * @return the id, or {@code null}
     */
    public static String typeId(String typeName)
    {
        return ElementKinds.idOf(typeName);
    }

    private void readInto(ListNode root)
    {
        if (root.size() > 0)
        {
            layout.version = tokenText(root.get(0));
        }
        ListNode body = root.size() > 1 ? asList(root.get(1)) : null;
        if (body != null)
        {
            ListNode head = body.size() > 1 ? asList(body.get(1)) : null;
            if (head != null && head.size() > 0)
            {
                layout.title = localized(head.get(0));
            }
            ListNode rootPanel = body.size() > 2 ? asList(body.get(2)) : null;
            if (rootPanel != null && rootPanel.size() >= 2 && PANEL_TYPE.equalsIgnoreCase(tokenText(rootPanel.get(0))))
            {
                collectPages(asList(rootPanel.get(1)), layout.pages);
                if (rootPanel.size() > 2 && asList(rootPanel.get(2)) != null)
                {
                    walk(asList(rootPanel.get(2)), layout.elements);
                }
            }
            for (int i = 2; i < body.size(); i++)
            {
                ListNode item = asList(body.get(i));
                if (item != null && item != rootPanel)
                {
                    walk(item, layout.elements);
                }
            }
            namePages(layout.elements, layout.pages);
        }
        for (int i = 2; i < root.size(); i++)
        {
            ListNode item = asList(root.get(i));
            if (item == null)
            {
                continue;
            }
            if (isAttributeTable(item))
            {
                readAttributeTable(item);
            }
            else
            {
                collectFormHandlers(item);
            }
        }
        // the panel's source, the style and the anchors all name other elements by id, so they
        // are read once every element of the form is
        Map<String, String> namesById = new HashMap<>();
        for (FormLayout.Element element : layout.allElements())
        {
            if (element.id != null && element.name != null)
            {
                namesById.put(element.id, element.name);
            }
        }
        namePanelSources(namesById);
        for (Map.Entry<FormLayout.Element, ListNode> pair : recordsOf.entrySet())
        {
            FormLayout.Element one = pair.getKey();
            one.details.putAll(ElementStyle.read(pair.getValue(), one.type));
            Map<String, Object> anchors = Anchors.read(pair.getValue(), namesById);
            if (!anchors.isEmpty())
            {
                one.details.put("anchors", anchors); //$NON-NLS-1$
            }
        }
    }

    /** Turns the element id a panel holds as its source of actions into the element's name. */
    private void namePanelSources(Map<String, String> namesById)
    {
        for (FormLayout.Element element : layout.allElements())
        {
            Object source = element.details.get("actionSourceId"); //$NON-NLS-1$
            element.details.remove("actionSourceId"); //$NON-NLS-1$
            if (source != null && !"none".equals(source)) //$NON-NLS-1$
            {
                element.details.put("actionSource", namesById.get(source)); //$NON-NLS-1$
            }
            else if (source != null)
            {
                element.details.put("actionSource", "none"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    // ---- elements --------------------------------------------------------------------------

    private void walk(ListNode node, List<FormLayout.Element> out)
    {
        for (Node item : node.items())
        {
            ListNode list = asList(item);
            if (list == null)
            {
                continue;
            }
            if (isRecord(list))
            {
                out.add(element(list));
            }
            else
            {
                walk(list, out);
            }
        }
    }

    /**
     * Whether a list is an element record: six items, a type uuid, an id, four lists.
     *
     * @param list the list
     * @return whether it is a record of a known type; an unknown type is counted
     */
    public boolean isRecord(ListNode list)
    {
        if (list.size() < 6 || asList(list.get(0)) != null || asList(list.get(1)) != null)
        {
            return false;
        }
        String type = tokenText(list.get(0));
        if (type == null || type.length() != 36 || intOf(list.get(1)) == null)
        {
            return false;
        }
        for (int i = 2; i < 6; i++)
        {
            if (asList(list.get(i)) == null)
            {
                return false;
            }
        }
        if (ElementKinds.isKnown(type))
        {
            return true;
        }
        layout.unread.merge(type.toLowerCase(Locale.ROOT), Integer.valueOf(1), Integer::sum);
        return false;
    }

    /**
     * Whether a list is an element record of a known type, without counting.
     *
     * @param list the list
     * @return whether it is one
     */
    public static boolean isElementRecord(ListNode list)
    {
        if (list.size() < 6 || asList(list.get(0)) != null || intOf(list.get(1)) == null)
        {
            return false;
        }
        String type = tokenText(list.get(0));
        if (type == null || !ElementKinds.isKnown(type))
        {
            return false;
        }
        for (int i = 2; i < 6; i++)
        {
            if (asList(list.get(i)) == null)
            {
                return false;
            }
        }
        return true;
    }

    /**
     * The positions of an element record's four lists - the own, the common, the identity and
     * the children - as {@code {own, common, identity, children}}. A Chart carries a version tag
     * {@code {11}} before its own list, so every list of a Chart sits one item further than
     * another kind's.
     *
     * @param record an element record
     * @return the four positions
     */
    public static int[] recordIndices(ListNode record)
    {
        String chart = ElementKinds.idOf("Chart"); //$NON-NLS-1$
        return record.size() >= 7 && chart != null && chart.equalsIgnoreCase(tokenText(record.get(0)))
            ? new int[] { 3, 4, 5, 6 } : new int[] { 2, 3, 4, 5 };
    }

    /**
     * The own list of an element record.
     *
     * @param record the record
     * @return the list, or {@code null} when the item is not a list
     */
    public static ListNode ownOf(ListNode record)
    {
        int[] at = recordIndices(record);
        return record.size() > at[0] ? asList(record.get(at[0])) : null;
    }

    /**
     * The common list of an element record.
     *
     * @param record the record
     * @return the list, or {@code null} when the item is not a list
     */
    public static ListNode commonOf(ListNode record)
    {
        int[] at = recordIndices(record);
        return record.size() > at[1] ? asList(record.get(at[1])) : null;
    }

    /**
     * The identity list of an element record.
     *
     * @param record the record
     * @return the list, or {@code null} when the item is not a list
     */
    public static ListNode identityOf(ListNode record)
    {
        int[] at = recordIndices(record);
        return record.size() > at[2] ? asList(record.get(at[2])) : null;
    }

    /**
     * The children list of an element record.
     *
     * @param record the record
     * @return the list, or {@code null} when the item is not a list
     */
    public static ListNode childrenOf(ListNode record)
    {
        int[] at = recordIndices(record);
        return record.size() > at[3] ? asList(record.get(at[3])) : null;
    }

    private FormLayout.Element element(ListNode record)
    {
        String type = typeName(tokenText(record.get(0)));
        FormLayout.Element element = new FormLayout.Element(type, tokenText(record.get(1)));
        ListNode identity = identityOf(record);
        element.name = elementName(identity);
        ListNode common = commonOf(record);
        element.box = box(common);
        element.pageIndex = pageIndex(common);
        ListNode own = ownOf(record);
        element.title = caption(type, own);
        element.tooltip = tooltip(own);
        element.pattern = TypePattern.find(own);
        element.valueType = element.pattern == null ? null : element.pattern.describe(LayoutNames.NONE);
        // The document sits in the own list and no record carries a pattern: 142 of 142 fields
        // are unbound, so the words of the data are the document itself.
        if ("TableField".equals(type) && element.valueType == null) //$NON-NLS-1$
        {
            element.valueType = "SpreadsheetDocument"; //$NON-NLS-1$
        }
        collectHandlers(own, type, element.handlers);
        if ("CommandPanel".equals(type)) //$NON-NLS-1$
        {
            collectButtons(own, element.buttons);
            // Автозаполнение at the third item of the inner list, and the source of the panel's
            // actions as the element id the identity holds; the id turns into a name below
            ListNode inner = own != null && own.size() > 1 ? asList(own.get(1)) : null;
            element.details.put("autoFill", inner != null && inner.size() > 3 //$NON-NLS-1$
                ? Boolean.valueOf("1".equals(tokenText(inner.get(3)))) : Boolean.FALSE); //$NON-NLS-1$ //$NON-NLS-2$
            String sourceId = identity != null && identity.size() > 2 ? tokenText(identity.get(2)) : null;
            element.details.put("actionSourceId", sourceId == null || "0".equals(sourceId) //$NON-NLS-1$ //$NON-NLS-2$
                || "4294967295".equals(sourceId) ? "none" : sourceId); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if ("Panel".equals(type)) //$NON-NLS-1$
        {
            collectPages(own, element.pages);
            ListNode panelProps = own != null && own.size() > 1 ? asList(own.get(1)) : null;
            int flag = tabsFlagAt(panelProps);
            if (flag >= 0)
            {
                element.details.put("tabs", //$NON-NLS-1$
                    Boolean.valueOf(!"0".equals(tokenText(panelProps.get(flag))))); //$NON-NLS-1$
            }
        }
        if ("Table".equals(type)) //$NON-NLS-1$
        {
            collectColumns(own, element.columns);
        }
        ListNode children = childrenOf(record);
        if (children != null)
        {
            walk(children, element.children);
            namePages(element.children, element.pages);
        }
        recordsOf.put(element, record);
        return element;
    }

    /**
     * The name of an element off its identity list, the second item as a string.
     *
     * @param identity the identity list
     * @return the name, or {@code null}
     */
    public static String elementName(ListNode identity)
    {
        return identity != null && identity.size() > 1 && isString(identity.get(1)) ? stringValue(identity.get(1)) : null;
    }

    /**
     * The box of an element off its common list.
     *
     * @param common the common list
     * @return left, top, right, bottom, or {@code null}
     */
    public static int[] box(ListNode common)
    {
        if (common == null || common.size() < 5)
        {
            return null;
        }
        int[] box = new int[4];
        for (int i = 0; i < 4; i++)
        {
            Integer value = intOf(common.get(i + 1));
            if (value == null)
            {
                return null;
            }
            box[i] = value.intValue();
        }
        return box;
    }

    /**
     * The ordinal of the page an element sits on, off its common list.
     *
     * @param common the common list
     * @return the ordinal, or {@code null}
     */
    public static Integer pageIndex(ListNode common)
    {
        if (common == null || common.size() < 12 + PAGE_FROM_END)
        {
            return null;
        }
        return intOf(common.get(common.size() - PAGE_FROM_END));
    }

    private static void namePages(List<FormLayout.Element> elements, List<FormLayout.Page> pages)
    {
        for (FormLayout.Element element : elements)
        {
            if (element.pageIndex != null && element.pageIndex.intValue() >= 0 && element.pageIndex.intValue() < pages.size())
            {
                element.page = pages.get(element.pageIndex.intValue()).name;
            }
        }
    }

    /**
     * The caption of an element: where the type keeps it.
     *
     * @param type the element type
     * @param own the own list
     * @return the caption, or {@code null} for a type without one
     */
    static Map<String, String> caption(String type, ListNode own)
    {
        Map<String, String> caption = node(captionNode(type, own));
        return caption == null || caption.isEmpty() ? null : caption;
    }

    private static Map<String, String> node(Node node)
    {
        return node == null ? null : localized(node);
    }

    /**
     * The node that holds an element's caption, for a reader or a writer.
     *
     * @param type the element type
     * @param own the own list
     * @return the node, or {@code null} for a type without a caption or a list of another shape
     */
    public static Node captionNode(String type, ListNode own)
    {
        if (own == null || type == null)
        {
            return null;
        }
        int[] path;
        switch (type)
        {
        case "Label": //$NON-NLS-1$
        case "Button": //$NON-NLS-1$
        case "Group": //$NON-NLS-1$
            path = new int[] { 1, 2 };
            break;
        case "CheckBox": //$NON-NLS-1$
            path = new int[] { 1, 0, 2 };
            break;
        case "RadioBtn": //$NON-NLS-1$
            path = new int[] { 2, 0, 2 };
            break;
        default:
            return null;
        }
        Node node = own;
        for (int index : path)
        {
            ListNode list = asList(node);
            if (list == null || list.size() <= index)
            {
                return null;
            }
            node = list.get(index);
        }
        return localized(node) == null ? null : node;
    }

    /**
     * The tooltip of an element: the localized string at the thirteenth position of the first
     * style block in its own list.
     *
     * @param own the own list
     * @return the tooltip, or {@code null}
     */
    static Map<String, String> tooltip(ListNode own)
    {
        ListNode style = styleBlock(own);
        Map<String, String> tooltip = style == null ? null : localized(style.get(STYLE_TOOLTIP));
        return tooltip == null || tooltip.isEmpty() ? null : tooltip;
    }

    /**
     * The first style block under a node, depth first.
     *
     * @param node the node
     * @return the block, or {@code null}
     */
    public static ListNode styleBlock(Node node)
    {
        ListNode list = asList(node);
        if (list == null)
        {
            return null;
        }
        if (isStyleBlock(list))
        {
            return list;
        }
        for (Node item : list.items())
        {
            ListNode found = styleBlock(item);
            if (found != null)
            {
                return found;
            }
        }
        return null;
    }

    private static boolean isStyleBlock(ListNode list)
    {
        if (list.size() <= STYLE_TOOLTIP || intOf(list.get(0)) == null || !"1".equals(tokenText(list.get(1)))) //$NON-NLS-1$
        {
            return false;
        }
        ListNode second = asList(list.get(2));
        ListNode third = asList(list.get(3));
        return second != null && third != null && second.size() >= 2 && third.size() >= 2
            && "3".equals(tokenText(second.get(0))) && "3".equals(tokenText(third.get(0))) //$NON-NLS-1$ //$NON-NLS-2$
            && localized(list.get(STYLE_TOOLTIP)) != null;
    }

    // ---- handlers --------------------------------------------------------------------------

    /**
     * Whether a list is a handler entry: {@code {<code>, <handler uuid>, {3,"Procedure",...}}}.
     *
     * @param list the list
     * @return whether it is one
     */
    public static boolean isHandlerEntry(ListNode list)
    {
        if (list.size() < 3 || !FormEvents.HANDLER.equalsIgnoreCase(tokenText(list.get(1))))
        {
            return false;
        }
        return handlerProcedure(list.get(2)) != null;
    }

    /**
     * The procedure a handler record names: {@code {3,"Procedure",{1,...}}}.
     *
     * @param node the record
     * @return the procedure, or {@code null} when the node is not a handler record
     */
    public static String handlerProcedure(Node node)
    {
        ListNode record = asList(node);
        if (record == null || record.size() < 3 || !"3".equals(tokenText(record.get(0))) || !isString(record.get(1))) //$NON-NLS-1$
        {
            return null;
        }
        ListNode action = asList(record.get(2));
        if (action == null || action.size() < 2 || !"1".equals(tokenText(action.get(0)))) //$NON-NLS-1$
        {
            return null;
        }
        return stringValue(record.get(1));
    }

    private static void collectHandlers(ListNode node, String type, List<FormLayout.Handler> out)
    {
        if (node == null)
        {
            return;
        }
        if (isHandlerEntry(node))
        {
            Integer code = intOf(node.get(0));
            String event = code == null ? null : FormEvents.elementEvent(type, code.intValue());
            out.add(new FormLayout.Handler(handlerProcedure(node.get(2)), event, code));
            return;
        }
        for (Node item : node.items())
        {
            ListNode child = asList(item);
            if (child != null && !isButtonRecord(child))
            {
                collectHandlers(child, type, out);
            }
        }
    }

    private void collectFormHandlers(ListNode node)
    {
        if (isHandlerEntry(node))
        {
            Integer code = intOf(node.get(0));
            String event = code == null ? null : FormEvents.formEvent(code.intValue(), layout.settingsHolder);
            layout.formHandlers.add(new FormLayout.Handler(handlerProcedure(node.get(2)), event, code));
            return;
        }
        for (Node item : node.items())
        {
            ListNode child = asList(item);
            if (child != null)
            {
                collectFormHandlers(child);
            }
        }
    }

    // ---- columns of a table ----------------------------------------------------------------

    /**
     * The columns list of a table's own list: the twenty-fourth item of the table block, a
     * counted list of {@code {<kind uuid>, {<n>, {8, <body>, <segment>, <segment>, <segment>}, ...}}}.
     *
     * @param own the table's own list
     * @return the columns list, or {@code null} when the table holds none where the corpus does
     */
    public static ListNode columnsList(ListNode own)
    {
        ListNode block = own == null || own.size() < 3 ? null : asList(own.get(2));
        ListNode table = block == null || block.size() < 2 ? null : asList(block.get(1));
        ListNode columns = table == null || table.size() < 24 ? null : asList(table.get(23));
        if (columns == null || columns.size() == 0 || intOf(columns.get(0)) == null
            || intOf(columns.get(0)).intValue() != columns.size() - 1)
        {
            return null;
        }
        for (int i = 1; i < columns.size(); i++)
        {
            ListNode column = asList(columns.get(i));
            if (column == null || column.size() < 2 || TableColumns.kindOf(tokenText(column.get(0))) == null)
            {
                return null;
            }
        }
        return columns;
    }

    private void collectColumns(ListNode own, List<FormLayout.Column> out)
    {
        ListNode columns = columnsList(own);
        if (columns == null)
        {
            return;
        }
        for (int i = 1; i < columns.size(); i++)
        {
            ListNode column = asList(columns.get(i));
            FormLayout.Column read = column(column);
            if (read != null)
            {
                out.add(read);
            }
            else
            {
                layout.unread.merge("column", Integer.valueOf(1), Integer::sum); //$NON-NLS-1$
            }
        }
    }

    /**
     * A column off its record.
     *
     * @param column the record {@code {<kind uuid>, {<n>, {8, <body>, <segments>...}, <tail>...}}}
     * @return the column, or {@code null} when the record is not shaped so
     */
    static FormLayout.Column column(ListNode column)
    {
        String kind = TableColumns.kindOf(tokenText(column.get(0)));
        ListNode inner = asList(column.get(1));
        ListNode record = inner == null || inner.size() < 2 ? null : asList(inner.get(1));
        ListNode body = record == null || record.size() < 5 ? null : asList(record.get(1));
        if (kind == null || body == null || body.size() < 39)
        {
            return null;
        }
        FormLayout.Column out = new FormLayout.Column(kind);
        out.name = stringValue(body.get(30));
        out.title = localized(body.get(1));
        out.width = tokenText(body.get(4));
        ListNode pattern = asList(body.get(35));
        out.pattern = pattern == null ? null : TypePattern.of(pattern);
        out.valueType = out.pattern == null ? null : out.pattern.describe(LayoutNames.NONE);
        String control = body.size() >= 40 ? tokenText(body.get(38)) : null;
        out.control = control == null || control.startsWith("00000000-") ? null : typeName(control); //$NON-NLS-1$
        for (int i = 2; i < record.size() && i < 5; i++)
        {
            ListNode segment = asList(record.get(i));
            if (segment == null)
            {
                continue;
            }
            if (segment.size() == 2 && "0".equals(tokenText(segment.get(0)))) //$NON-NLS-1$
            {
                out.path.add(new FormLayout.Segment(FormLayout.Segment.MEMBER, tokenText(segment.get(1))));
            }
            else if (segment.size() == 1 && intOf(segment.get(0)) != null && intOf(segment.get(0)).intValue() != -1)
            {
                out.path.add(new FormLayout.Segment(FormLayout.Segment.STANDARD, tokenText(segment.get(0))));
            }
        }
        if (TableColumns.VALUE_TABLE.equals(kind) && inner.size() > 2)
        {
            out.valueTableColumn = stringValue(inner.get(2));
        }
        return out;
    }

    // ---- buttons of a command panel --------------------------------------------------------

    private static boolean isButtonRecord(ListNode list)
    {
        return list.size() >= 5 && ("8".equals(tokenText(list.get(0))) || "7".equals(tokenText(list.get(0)))) //$NON-NLS-1$ //$NON-NLS-2$
            && isString(list.get(1));
    }

    /**
     * The buttons of a command panel. A button is {@code {7,"Name",...}} on layouts of versions
     * 25 and 26 and {@code {8,"Name",...}} on 27, with its caption among its items; the uuid
     * before it names the action it runs, and an action
     * {@code {6,<uuid>,1,<handler uuid>,{3,"Procedure",...},0}} names the procedure. The panel's
     * own buttons sit in the one block the content opens with the pair (b78f2e80, 4); the buttons
     * of a submenu sit in a block of their own beside it, named by the submenu action's payload,
     * and are read under the submenu button as its children.
     */
    private static void collectButtons(ListNode own, List<FormLayout.Button> out)
    {
        Map<String, ListNode> actions = new HashMap<>();
        collectActions(own, actions);
        ListNode inner = own != null && own.size() > 1 ? asList(own.get(1)) : null;
        ListNode content = inner != null && inner.size() > 7 ? asList(inner.get(7)) : null;
        if (content == null)
        {
            return;
        }
        for (Node item : content.items())
        {
            ListNode block = asList(item);
            if (block == null || block.size() < 6 || !"5".equals(tokenText(block.get(0))) //$NON-NLS-1$
                || !CommandPanels.MAIN_BLOCK.equalsIgnoreCase(tokenText(block.get(1))) //$NON-NLS-1$
                || !CommandPanels.MAIN_BLOCK_NUM.equals(tokenText(block.get(2)))) //$NON-NLS-1$
            {
                continue;
            }
            readBlockButtons(content, block, actions, out);
            break;
        }
    }

    /** The buttons of one block, each with its kind, its action, its picture and its submenu children. */
    private static void readBlockButtons(ListNode content, ListNode block, Map<String, ListNode> actions,
        List<FormLayout.Button> out)
    {
        Integer count = intOf(block.get(4));
        int buttons = count == null ? 0 : count.intValue();
        for (int pair = 0; pair < buttons && 5 + pair * 2 + 1 < block.size(); pair++)
        {
            String actionUuid = tokenText(block.get(5 + pair * 2));
            ListNode child = asList(block.get(5 + pair * 2 + 1));
            if (child == null || !isButtonRecord(child))
            {
                continue;
            }
            Map<String, String> caption = null;
            for (int k = 2; k < child.size() && caption == null; k++)
            {
                caption = localized(child.get(k));
            }
            ListNode action = actionUuid == null ? null : actions.get(actionUuid.toLowerCase(Locale.ROOT));
            String procedure = action != null ? handlerProcedure(action.get(4)) : null;
            FormLayout.Button button = new FormLayout.Button(stringValue(child.get(1)), caption, procedure);
            fillButtonDetails(button, child, action, content, actions);
            out.add(button);
        }
    }

    /** The details a theme reads off a button: its kind, its action, its picture, its submenu items. */
    private static void fillButtonDetails(FormLayout.Button button, ListNode record, ListNode action, ListNode content,
        Map<String, ListNode> actions)
    {
        String kind = record.size() > 9 && tokenText(record.get(9)) != null && tokenText(record.get(9)).length() == 1
            ? tokenText(record.get(9)) : null;
        String type = action == null ? null : tokenText(action.get(3));
        if (kind == null)
        {
            kind = CommandPanels.ACTION_SEPARATOR.equalsIgnoreCase(type) ? "2" //$NON-NLS-1$
                : CommandPanels.ACTION_SUBMENU.equalsIgnoreCase(type) ? "1" : "0"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        button.details.put("kind", "2".equals(kind) ? "separator" : "1".equals(kind) ? "submenu" : "button"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        String procedureName = action == null ? null : handlerProcedure(action.get(4));
        if (procedureName != null)
        {
            button.details.put("action", "procedure:" + procedureName); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (action != null && CommandPanels.ACTION_STANDARD.equalsIgnoreCase(type))
        {
            ListNode payload = asList(action.get(4));
            if (payload != null && payload.size() > 3)
            {
                StandardActions.Action standard = StandardActions.bySetAndCode(tokenText(payload.get(2)),
                    tokenText(payload.get(3)));
                button.details.put("action", "standard:" //$NON-NLS-1$ //$NON-NLS-2$
                    + (standard != null ? standard.name : "code" + tokenText(payload.get(3)))); //$NON-NLS-1$
                button.details.put("picture", pictureOf(payload)); //$NON-NLS-1$
            }
        }
        if (!button.details.containsKey("picture")) //$NON-NLS-1$
        {
            button.details.put("picture", "none"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!"1".equals(kind) || action == null) //$NON-NLS-1$
        {
            return;
        }
        // the buttons of the submenu sit in the block its payload names
        ListNode payload = asList(action.get(4));
        ListNode own = null;
        if (payload != null && payload.size() > 2)
        {
            for (Node item : content.items())
            {
                ListNode candidate = asList(item);
                if (candidate != null && candidate.size() >= 6 && "5".equals(tokenText(candidate.get(0))) //$NON-NLS-1$
                    && tokenText(payload.get(1)) != null
                    && tokenText(payload.get(1)).equalsIgnoreCase(tokenText(candidate.get(1)))
                    && tokenText(payload.get(2)) != null && tokenText(payload.get(2)).equals(tokenText(candidate.get(2))))
                {
                    own = candidate;
                    break;
                }
            }
        }
        if (own != null)
        {
            List<FormLayout.Button> children = new ArrayList<>();
            readBlockButtons(content, own, actions, children);
            List<Object> maps = new ArrayList<>();
            for (FormLayout.Button child : children)
            {
                maps.add(child.toMap());
            }
            button.details.put("children", maps); //$NON-NLS-1$
        }
    }

    /** The picture of a standard action's payload: the dictionary index, or the raw uuid and index. */
    private static Object pictureOf(ListNode payload)
    {
        ListNode picture = payload.size() > 4 ? asList(payload.get(4)) : null;
        if (picture == null || picture.size() != 5)
        {
            return "none"; //$NON-NLS-1$
        }
        String text = BraceTree.serialize(picture);
        for (int i = 0; i < CommandPanels.pictureCount(); i++)
        {
            if (text.equals(BraceTree.serialize(CommandPanels.pictureNode(i))))
            {
                return Integer.valueOf(i);
            }
        }
        String set = tokenText(picture.get(2));
        String index = tokenText(picture.get(3));
        if (set == null || set.length() < 8)
        {
            return "none"; //$NON-NLS-1$
        }
        return set.substring(0, 8) + ":" + index; //$NON-NLS-1$
    }

    /**
     * Whether a list is an action of a command panel that runs a procedure.
     *
     * @param list the list
     * @return whether it is one
     */
    public static boolean isActionRecord(ListNode list)
    {
        return list.size() >= 5 && "6".equals(tokenText(list.get(0))) && tokenText(list.get(1)) != null //$NON-NLS-1$
            && tokenText(list.get(1)).length() == 36 && FormEvents.HANDLER.equalsIgnoreCase(tokenText(list.get(3)))
            && handlerProcedure(list.get(4)) != null;
    }

    private static void collectActions(ListNode node, Map<String, ListNode> actions)
    {
        if (node.size() >= 5 && "6".equals(tokenText(node.get(0))) && tokenText(node.get(1)) != null //$NON-NLS-1$
            && tokenText(node.get(1)).length() == 36 && tokenText(node.get(3)) != null
            && (FormEvents.HANDLER.equalsIgnoreCase(tokenText(node.get(3)))
                || CommandPanels.ACTION_STANDARD.equalsIgnoreCase(tokenText(node.get(3)))
                || CommandPanels.ACTION_SEPARATOR.equalsIgnoreCase(tokenText(node.get(3)))
                || CommandPanels.ACTION_SUBMENU.equalsIgnoreCase(tokenText(node.get(3)))))
        {
            actions.put(tokenText(node.get(1)).toLowerCase(Locale.ROOT), node);
            return;
        }
        for (Node item : node.items())
        {
            ListNode child = asList(item);
            if (child != null)
            {
                collectActions(child, actions);
            }
        }
    }

    // ---- pages -----------------------------------------------------------------------------

    /**
     * Whether a list is a page record: a number, the title, a font, then the name as the
     * seventh item.
     *
     * @param list the list
     * @return whether it is one
     */
    public static boolean isPageRecord(ListNode list)
    {
        return list.size() >= 8 && intOf(list.get(0)) != null && localized(list.get(1)) != null
            && asList(list.get(2)) != null && isString(list.get(6));
    }

    /**
     * The slot that says whether a panel draws a tab per page: it lies two before the pages list
     * in the panel's property block. Measured over the corpus: of 339 panels of more than one
     * page, 124 carry 1 and show a tab strip, 215 carry 0 and are turned over from code.
     *
     * @param props the property block of a panel, or of the form root
     * @return the index of the flag, or -1 when the block does not hold the pages list itself
     */
    public static int tabsFlagAt(ListNode props)
    {
        if (props == null)
        {
            return -1;
        }
        for (int i = 2; i < props.size(); i++)
        {
            ListNode child = asList(props.get(i));
            if (child != null && isPagesList(child) && intOf(props.get(i - 2)) != null)
            {
                return i - 2;
            }
        }
        return -1;
    }

    /**
     * @param node a list
     * @return whether it is a pages list: the count, and that many page records
     */
    private static boolean isPagesList(ListNode node)
    {
        Integer count = node.size() >= 2 ? intOf(node.get(1)) : null;
        return node.size() > 2 && "1".equals(tokenText(node.get(0))) && count != null //$NON-NLS-1$
            && count.intValue() == node.size() - 2 && asList(node.get(2)) != null
            && isPageRecord(asList(node.get(2)));
    }

    private static void collectPages(ListNode node, List<FormLayout.Page> out)
    {
        if (node == null)
        {
            return;
        }
        if (isPageRecord(node))
        {
            out.add(new FormLayout.Page(stringValue(node.get(6)), localized(node.get(1))));
            return;
        }
        for (Node item : node.items())
        {
            ListNode child = asList(item);
            if (child != null && !isElementRecord(child))
            {
                collectPages(child, out);
            }
        }
    }

    // ---- attributes and bindings -----------------------------------------------------------

    /**
     * Whether a list is the attribute table of the form.
     *
     * @param item a root item
     * @return whether it is the table
     */
    public static boolean isAttributeTable(ListNode item)
    {
        if (item.size() < 4)
        {
            return false;
        }
        ListNode main = asList(item.get(0));
        return main != null && main.size() == 1 && intOf(main.get(0)) != null && asList(item.get(2)) != null
            && asList(item.get(3)) != null && intOf(item.get(1)) != null;
    }

    private void readAttributeTable(ListNode item)
    {
        layout.mainAttributeId = tokenText(asList(item.get(0)).get(0));
        ListNode table = asList(item.get(2));
        for (int i = 1; i < table.size(); i++)
        {
            ListNode row = asList(table.get(i));
            FormLayout.Attribute attribute = attributeRow(row);
            if (attribute != null)
            {
                layout.attributes.add(attribute);
            }
        }
        ListNode registry = asList(item.get(3));
        for (int i = 1; i < registry.size(); i++)
        {
            ListNode pair = asList(registry.get(i));
            if (pair == null || pair.size() != 2)
            {
                continue;
            }
            ListNode path = asList(pair.get(1));
            String elementId = tokenText(pair.get(0));
            if (path == null || elementId == null || path.size() < 2)
            {
                continue;
            }
            List<FormLayout.Segment> segments = new ArrayList<>();
            int from = 1;
            if (path.size() >= 4 && isSingle(path.get(1), "-2") && single(path.get(2)) != null && isSingle(path.get(3), "0")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                // {-2},{<element id>},{0}: the current row of a table element, then its members.
                segments.add(new FormLayout.Segment(FormLayout.Segment.ELEMENT_ROW, single(path.get(2))));
                from = 4;
            }
            for (int k = from; k < path.size(); k++)
            {
                segments.add(segment(path.get(k), k == 1));
            }
            layout.paths.put(elementId, segments);
        }
    }

    /**
     * An attribute off a row of the table: the id, the first string as the name, the pattern.
     *
     * @param row the row
     * @return the attribute, or {@code null} when the row has no id or no name
     */
    public static FormLayout.Attribute attributeRow(ListNode row)
    {
        if (row == null || row.size() < 3)
        {
            return null;
        }
        ListNode id = asList(row.get(0));
        if (id == null || id.size() != 1 || intOf(id.get(0)) == null)
        {
            return null;
        }
        String name = null;
        TypePattern pattern = null;
        for (int i = 1; i < row.size(); i++)
        {
            if (name == null && isString(row.get(i)))
            {
                name = stringValue(row.get(i));
            }
            if (pattern == null)
            {
                pattern = TypePattern.of(row.get(i));
            }
        }
        return name == null ? null : new FormLayout.Attribute(tokenText(id.get(0)), name, pattern);
    }

    private static String single(Node node)
    {
        ListNode list = asList(node);
        return list != null && list.size() == 1 ? tokenText(list.get(0)) : null;
    }

    private static boolean isSingle(Node node, String value)
    {
        return value.equals(single(node));
    }

    private static FormLayout.Segment segment(Node node, boolean first)
    {
        ListNode list = asList(node);
        if (list == null)
        {
            return new FormLayout.Segment(FormLayout.Segment.TEXT, tokenText(node));
        }
        if (list.size() == 1)
        {
            Integer number = intOf(list.get(0));
            if (first)
            {
                return new FormLayout.Segment(FormLayout.Segment.ATTRIBUTE, tokenText(list.get(0)));
            }
            if (number != null && number.intValue() < 0)
            {
                return new FormLayout.Segment(FormLayout.Segment.STANDARD, tokenText(list.get(0)));
            }
            return new FormLayout.Segment(FormLayout.Segment.TEXT, "#" + tokenText(list.get(0))); //$NON-NLS-1$
        }
        if (list.size() == 2 && "0".equals(tokenText(list.get(0))) && tokenText(list.get(1)) != null) //$NON-NLS-1$
        {
            return new FormLayout.Segment(FormLayout.Segment.MEMBER, tokenText(list.get(1)).toLowerCase(Locale.ROOT));
        }
        return new FormLayout.Segment(FormLayout.Segment.TEXT,
            BraceTree.serialize(list).replace("\r\n", "").replace("\n", "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    // ---- node helpers ----------------------------------------------------------------------

    /**
     * A localized string: {@code {1, n, {"lang","text"}...}}.
     *
     * @param node a node
     * @return language to text, empty for {@code {1,0}}, or {@code null} when the node is not one
     */
    public static Map<String, String> localized(Node node)
    {
        ListNode list = asList(node);
        if (list == null || list.size() < 2 || !"1".equals(tokenText(list.get(0)))) //$NON-NLS-1$
        {
            return null;
        }
        Integer count = intOf(list.get(1));
        if (count == null || count.intValue() < 0 || list.size() != 2 + count.intValue())
        {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 2; i < list.size(); i++)
        {
            ListNode pair = asList(list.get(i));
            if (pair == null || pair.size() != 2 || !isString(pair.get(0)) || !isString(pair.get(1)))
            {
                return null;
            }
            out.put(stringValue(pair.get(0)), stringValue(pair.get(1)));
        }
        return out;
    }

    /**
     * A localized string node for a text in one language.
     *
     * @param language the language code
     * @param text the text
     * @return the node
     */
    public static ListNode localizedNode(String language, String text)
    {
        ListNode pair = new ListNode();
        pair.items().add(Token.string(language));
        pair.items().add(Token.string(text));
        ListNode node = new ListNode();
        node.items().add(new Token("1")); //$NON-NLS-1$
        node.items().add(new Token("1")); //$NON-NLS-1$
        node.items().add(pair);
        return node;
    }

    static ListNode asList(Node node)
    {
        return node instanceof ListNode ? (ListNode)node : null;
    }

    static String tokenText(Node node)
    {
        return node instanceof Token ? ((Token)node).text() : null;
    }

    static boolean isString(Node node)
    {
        return node instanceof Token && ((Token)node).isString();
    }

    static String stringValue(Node node)
    {
        return ((Token)node).value();
    }

    static Integer intOf(Node node)
    {
        String text = tokenText(node);
        if (text == null || text.isEmpty())
        {
            return null;
        }
        try
        {
            return Integer.valueOf(text);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }
}
