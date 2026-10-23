/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.layout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the layout of an ordinary form says: its title, attributes, pages, elements with their
 * boxes, captions, data and handlers, and the form's own handlers.
 *
 * <p>A read model. {@link LayoutReader} builds it from the brace text with every id the layout
 * records; {@link #resolve(LayoutNames)} turns the ids into names where the configuration knows
 * them. It answers as nested maps and lists, so a tool can put it on the wire as it is.</p>
 */
public final class FormLayout
{
    /** A procedure bound to an event: the procedure, the event's name and the code it is recorded under. */
    public static final class Handler
    {
        public final String procedure;

        /** The event's name, or {@code null} when the code was not measured. */
        public final String event;

        /** The code the layout records the event under, or {@code null} for an action record without one. */
        public final Integer code;

        Handler(String procedure, String event, Integer code)
        {
            this.procedure = procedure;
            this.event = event;
            this.code = code;
        }

        Map<String, Object> toMap()
        {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("event", event != null ? event : code != null ? "code:" + code : null); //$NON-NLS-1$ //$NON-NLS-2$
            map.put("procedure", procedure); //$NON-NLS-1$
            return map;
        }
    }

    /** A button of a command panel: its name, caption and the procedure its action runs. */
    public static final class Button
    {
        public final String name;

        public final Map<String, String> caption;

        public final String procedure;

        /** What a theme reads off the button beyond the fields above: kind, action, picture, submenu items. */
        public final Map<String, Object> details = new LinkedHashMap<>();

        Button(String name, Map<String, String> caption, String procedure)
        {
            this.name = name;
            this.caption = caption;
            this.procedure = procedure;
        }

        Map<String, Object> toMap()
        {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name); //$NON-NLS-1$
            map.put("caption", text(caption)); //$NON-NLS-1$
            if (procedure != null)
            {
                map.put("procedure", procedure); //$NON-NLS-1$
            }
            map.putAll(details);
            return map;
        }
    }

    /** An attribute of the form: its id in the layout, its name and its type. */
    public static final class Attribute
    {
        public final String id;

        public final String name;

        /** The type pattern as the layout records it, or {@code null}. */
        public final TypePattern pattern;

        /** The type in words, once resolved; the pattern's own words before. */
        public String type;

        Attribute(String id, String name, TypePattern pattern)
        {
            this.id = id;
            this.name = name;
            this.pattern = pattern;
            this.type = pattern == null ? null : pattern.describe(LayoutNames.NONE);
        }

        Map<String, Object> toMap(boolean main)
        {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name); //$NON-NLS-1$
            map.put("type", type); //$NON-NLS-1$
            if (main)
            {
                map.put("main", Boolean.TRUE); //$NON-NLS-1$
            }
            return map;
        }
    }

    /** A page of a panel. */
    public static final class Page
    {
        public final String name;

        public final Map<String, String> title;

        Page(String name, Map<String, String> title)
        {
            this.name = name;
            this.title = title;
        }

        Map<String, Object> toMap()
        {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name); //$NON-NLS-1$
            map.put("title", text(title)); //$NON-NLS-1$
            return map;
        }
    }

    /** One step of a data path as the layout records it. */
    public static final class Segment
    {
        /** A form attribute by its id in the attribute table. */
        public static final int ATTRIBUTE = 0;

        /** A metadata object by its uuid. */
        public static final int MEMBER = 1;

        /** A standard attribute by its negative code. */
        public static final int STANDARD = 2;

        /** Anything else, as text. */
        public static final int TEXT = 3;

        /** The current row of a table element, by the element's id. */
        public static final int ELEMENT_ROW = 4;

        public final int kind;

        public final String value;

        public Segment(int kind, String value)
        {
            this.kind = kind;
            this.value = value;
        }
    }

    /** A column of a table: what the table shows of every row. */
    public static final class Column
    {
        /** {@code list}, {@code tabularSection} or {@code valueTable}: what the table's rows are. */
        public final String kind;

        public String name;

        public Map<String, String> title;

        /** The width as recorded, or {@code null}. */
        public String width;

        /** The type pattern of the column's values, or {@code null}. */
        public TypePattern pattern;

        /** The values' type in words, or {@code null}. */
        public String valueType;

        /** The cell's control - {@code Field}, {@code CheckBox}, {@code SelectField} - or {@code null} when the column only shows. */
        public String control;

        /** The data path relative to the row as recorded: one standard attribute or one member, or empty. */
        public final List<Segment> path = new ArrayList<>();

        /** The data path in words, once resolved. */
        public String data;

        /** The column of the value table the column shows, for a table over a value table. */
        public String valueTableColumn;

        Column(String kind)
        {
            this.kind = kind;
        }

        Map<String, Object> toMap()
        {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name); //$NON-NLS-1$
            if (title != null && !title.isEmpty())
            {
                map.put("title", text(title)); //$NON-NLS-1$
            }
            if (width != null)
            {
                map.put("width", width); //$NON-NLS-1$
            }
            if (data != null)
            {
                map.put("data", data); //$NON-NLS-1$
            }
            else if (valueTableColumn != null)
            {
                map.put("data", valueTableColumn); //$NON-NLS-1$
            }
            if (valueType != null)
            {
                map.put("valueType", valueType); //$NON-NLS-1$
            }
            map.put("control", control); //$NON-NLS-1$
            return map;
        }
    }

    /** An element of the form. */
    public static final class Element
    {
        public final String type;

        public final String id;

        public String name;

        public Map<String, String> title;

        public Map<String, String> tooltip;

        /** left, top, right, bottom, or {@code null} when the record has no box. */
        public int[] box;

        /** The ordinal of the page of the element's parent the element sits on, or {@code null}. */
        public Integer pageIndex;

        /** The name of that page, once known. */
        public String page;

        /** The data path the element shows, resolved as far as the names allow, or {@code null}. */
        public String data;

        /** The data path as recorded, or {@code null}. */
        public List<Segment> path;

        /** The type pattern of the element's own value, or {@code null}. */
        public TypePattern pattern;

        /** The element's value type in words, or {@code null}. */
        public String valueType;

        public final List<Handler> handlers = new ArrayList<>();

        public final List<Button> buttons = new ArrayList<>();

        /** The pages of a panel. */
        public final List<Page> pages = new ArrayList<>();

        public final List<Element> children = new ArrayList<>();

        /** The columns of a table. */
        public final List<Column> columns = new ArrayList<>();

        /**
         * What a theme reads off the element beyond the fields above - style, anchors, the
         * settings of a command panel - under the theme's own keys, printed as they are.
         */
        public final Map<String, Object> details = new LinkedHashMap<>();

        Element(String type, String id)
        {
            this.type = type;
            this.id = id;
        }

        Map<String, Object> toMap()
        {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", type); //$NON-NLS-1$
            map.put("id", id); //$NON-NLS-1$
            map.put("name", name); //$NON-NLS-1$
            if (title != null && !title.isEmpty())
            {
                map.put("title", text(title)); //$NON-NLS-1$
            }
            if (tooltip != null && !tooltip.isEmpty())
            {
                map.put("tooltip", text(tooltip)); //$NON-NLS-1$
            }
            if (box != null)
            {
                Map<String, Object> rect = new LinkedHashMap<>();
                rect.put("left", Integer.valueOf(box[0])); //$NON-NLS-1$
                rect.put("top", Integer.valueOf(box[1])); //$NON-NLS-1$
                rect.put("right", Integer.valueOf(box[2])); //$NON-NLS-1$
                rect.put("bottom", Integer.valueOf(box[3])); //$NON-NLS-1$
                map.put("box", rect); //$NON-NLS-1$
            }
            if (page != null)
            {
                map.put("page", page); //$NON-NLS-1$
            }
            if (data != null)
            {
                map.put("data", data); //$NON-NLS-1$
            }
            if (valueType != null)
            {
                map.put("valueType", valueType); //$NON-NLS-1$
            }
            if (!handlers.isEmpty())
            {
                List<Object> list = new ArrayList<>();
                for (Handler handler : handlers)
                {
                    list.add(handler.toMap());
                }
                map.put("handlers", list); //$NON-NLS-1$
            }
            if (!buttons.isEmpty())
            {
                List<Object> list = new ArrayList<>();
                for (Button button : buttons)
                {
                    list.add(button.toMap());
                }
                map.put("buttons", list); //$NON-NLS-1$
            }
            if (!pages.isEmpty())
            {
                List<Object> list = new ArrayList<>();
                for (Page one : pages)
                {
                    list.add(one.toMap());
                }
                map.put("pages", list); //$NON-NLS-1$
            }
            if (!columns.isEmpty())
            {
                List<Object> list = new ArrayList<>();
                for (Column column : columns)
                {
                    list.add(column.toMap());
                }
                map.put("columns", list); //$NON-NLS-1$
            }
            if (!children.isEmpty())
            {
                List<Object> list = new ArrayList<>();
                for (Element child : children)
                {
                    list.add(child.toMap());
                }
                map.put("children", list); //$NON-NLS-1$
            }
            map.putAll(details);
            return map;
        }
    }

    public String version;

    public Map<String, String> title;

    /** The kind of the form's owner, or {@code null}. */
    public String ownerKind;

    /** Whether the main attribute holds the settings of a report or a data processor. */
    public boolean settingsHolder;

    /** The id of the main attribute in the layout, or {@code null} when the form has none. */
    public String mainAttributeId;

    public final List<Attribute> attributes = new ArrayList<>();

    /** Element id to the data path the element shows, as recorded. */
    public final Map<String, List<Segment>> paths = new LinkedHashMap<>();

    /** Element id to the data path the element shows, in words. */
    public final Map<String, String> bindings = new LinkedHashMap<>();

    public final List<Handler> formHandlers = new ArrayList<>();

    /** The pages of the form's root panel. */
    public final List<Page> pages = new ArrayList<>();

    public final List<Element> elements = new ArrayList<>();

    /** The kinds of records in the layout the reader did not recognise, with counts. */
    public final Map<String, Integer> unread = new LinkedHashMap<>();

    /**
     * Every element in document order, the children after their parent.
     *
     * @return the elements, flattened
     */
    public List<Element> allElements()
    {
        List<Element> out = new ArrayList<>();
        collect(elements, out);
        return out;
    }

    private static void collect(List<Element> from, List<Element> out)
    {
        for (Element element : from)
        {
            out.add(element);
            collect(element.children, out);
        }
    }

    /**
     * The element with a name, the first in document order.
     *
     * @param name the name, case-insensitive
     * @return the element, or {@code null}
     */
    public Element elementNamed(String name)
    {
        for (Element element : allElements())
        {
            if (element.name != null && element.name.equalsIgnoreCase(name))
            {
                return element;
            }
        }
        return null;
    }

    /**
     * The produced-type uuids the layout names in the patterns of its attributes and elements.
     *
     * @return the uuids, lower case, in order of first appearance
     */
    public List<String> producedTypeUuids()
    {
        List<String> out = new ArrayList<>();
        for (Attribute attribute : attributes)
        {
            if (attribute.pattern != null)
            {
                for (String uuid : attribute.pattern.producedTypeUuids())
                {
                    if (!out.contains(uuid))
                    {
                        out.add(uuid);
                    }
                }
            }
        }
        for (Element element : allElements())
        {
            if (element.pattern != null)
            {
                for (String uuid : element.pattern.producedTypeUuids())
                {
                    if (!out.contains(uuid))
                    {
                        out.add(uuid);
                    }
                }
            }
        }
        return out;
    }

    /**
     * Turns the ids the layout records into names where the configuration knows them: the types
     * of the attributes and elements, and the data paths.
     *
     * @param names what the configuration knows; {@link LayoutNames#NONE} leaves the ids
     */
    public void resolve(LayoutNames names)
    {
        Map<String, Attribute> byId = new HashMap<>();
        for (Attribute attribute : attributes)
        {
            byId.put(attribute.id, attribute);
            if (attribute.pattern != null)
            {
                attribute.type = attribute.pattern.describe(names);
            }
        }
        Map<String, Element> byElementId = new HashMap<>();
        for (Element element : allElements())
        {
            byElementId.put(element.id, element);
            if (element.pattern != null)
            {
                element.valueType = element.pattern.describe(names);
            }
        }
        for (Map.Entry<String, List<Segment>> path : paths.entrySet())
        {
            String text = describePath(path.getValue(), byId, names);
            bindings.put(path.getKey(), text);
            Element element = byElementId.get(path.getKey());
            if (element != null)
            {
                element.data = text;
            }
        }
        for (Element element : allElements())
        {
            String rowType = element.pattern == null ? null : element.pattern.singleProducedType();
            for (Column column : element.columns)
            {
                if (column.pattern != null)
                {
                    column.valueType = column.pattern.describe(names);
                }
                column.data = describeColumnPath(column.path, rowType, names);
            }
        }
    }

    /**
     * The words of a column's path: a standard attribute of the row's object, or a member of
     * the row's type.
     */
    private static String describeColumnPath(List<Segment> path, String rowType, LayoutNames names)
    {
        if (path.isEmpty())
        {
            return null;
        }
        Segment segment = path.get(0);
        if (segment.kind == Segment.STANDARD)
        {
            int code = Integer.parseInt(segment.value);
            String kind = rowType == null ? null : names.ownerKind(rowType);
            String typeName = rowType == null ? null : names.typeName(rowType);
            String name = typeName != null && typeName.contains("TabularSection") && code == -2 ? "НомерСтроки" //$NON-NLS-1$ //$NON-NLS-2$
                : StandardAttributes.nameOf(kind, code);
            return name != null ? name : "standard(" + code + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (segment.kind == Segment.MEMBER)
        {
            String name = rowType == null ? null : names.memberName(rowType, segment.value);
            return name != null ? name : "member(" + segment.value + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return segment.kind + "(" + segment.value + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String describePath(List<Segment> path, Map<String, Attribute> byId, LayoutNames names)
    {
        StringBuilder text = new StringBuilder();
        String typeUuid = null;
        String kind = null;
        Map<String, Element> byElementId = new HashMap<>();
        for (Element element : allElements())
        {
            byElementId.put(element.id, element);
        }
        for (int i = 0; i < path.size(); i++)
        {
            Segment segment = path.get(i);
            String part;
            if (i == 0 && segment.kind == Segment.ATTRIBUTE)
            {
                Attribute attribute = byId.get(segment.value);
                part = attribute != null ? attribute.name : "attribute#" + segment.value; //$NON-NLS-1$
                typeUuid = attribute != null && attribute.pattern != null ? attribute.pattern.singleProducedType() : null;
                kind = typeUuid != null ? names.ownerKind(typeUuid) : null;
                if (kind == null && attribute != null && attribute.id.equals(mainAttributeId))
                {
                    kind = ownerKind;
                }
            }
            else if (segment.kind == Segment.ELEMENT_ROW)
            {
                Element table = byElementId.get(segment.value);
                part = (table != null && table.name != null ? table.name : "element#" + segment.value) + ".ТекущаяСтрока"; //$NON-NLS-1$ //$NON-NLS-2$
                typeUuid = table != null && table.pattern != null ? table.pattern.singleProducedType() : null;
                kind = null;
            }
            else if (segment.kind == Segment.MEMBER)
            {
                String name = typeUuid == null ? null : names.memberName(typeUuid, segment.value);
                part = name != null ? name : "uuid:" + segment.value; //$NON-NLS-1$
                kind = typeUuid == null ? null : names.referencedKind(typeUuid, segment.value);
                String next = typeUuid == null ? null : names.memberTypeUuid(typeUuid, segment.value);
                if (next != null)
                {
                    typeUuid = next;
                    if (kind == null)
                    {
                        kind = names.ownerKind(next);
                    }
                }
            }
            else if (segment.kind == Segment.STANDARD)
            {
                int code = Integer.parseInt(segment.value);
                String name = StandardAttributes.nameOf(kind, code);
                part = name != null ? name : "standard(" + segment.value + ")"; //$NON-NLS-1$ //$NON-NLS-2$
                // A parent is of the same kind; anything else leads to an object of an unknown one.
                kind = "Родитель".equals(name) ? kind : null; //$NON-NLS-1$
            }
            else
            {
                part = segment.value;
                kind = null;
            }
            if (text.length() > 0)
            {
                text.append('.');
            }
            text.append(part);
        }
        return text.toString();
    }

    /**
     * The layout as nested maps and lists, for an answer.
     *
     * @return the map
     */
    public Map<String, Object> toMap()
    {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("layoutVersion", version); //$NON-NLS-1$
        map.put("title", text(title)); //$NON-NLS-1$
        List<Object> attrs = new ArrayList<>();
        for (Attribute attribute : attributes)
        {
            attrs.add(attribute.toMap(attribute.id.equals(mainAttributeId)));
        }
        map.put("attributes", attrs); //$NON-NLS-1$
        List<Object> pageList = new ArrayList<>();
        for (Page page : pages)
        {
            pageList.add(page.toMap());
        }
        map.put("pages", pageList); //$NON-NLS-1$
        List<Object> handlerList = new ArrayList<>();
        for (Handler handler : formHandlers)
        {
            handlerList.add(handler.toMap());
        }
        map.put("formHandlers", handlerList); //$NON-NLS-1$
        List<Object> elementList = new ArrayList<>();
        for (Element element : elements)
        {
            elementList.add(element.toMap());
        }
        map.put("elements", elementList); //$NON-NLS-1$
        map.put("elementCount", Integer.valueOf(allElements().size())); //$NON-NLS-1$
        if (!unread.isEmpty())
        {
            map.put("unread", unread); //$NON-NLS-1$
        }
        return map;
    }

    /**
     * A localized string for an answer: the text when one language is there, the map otherwise.
     *
     * @param localized language to text; may be {@code null} or empty
     * @return a string, a map, or {@code null}
     */
    static Object text(Map<String, String> localized)
    {
        if (localized == null || localized.isEmpty())
        {
            return null;
        }
        if (localized.size() == 1)
        {
            return localized.values().iterator().next();
        }
        return localized;
    }
}
