/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.session;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.PropertyApplicability;

/**
 * What the drawing and the property panel of the editor look at, in the session's own types: the
 * elements with their render branch and the properties their kind was measured on, the pages,
 * the handlers, the attributes, the unread record count. Built afresh from the current layout
 * text after every fixation, so a figure never edits a copy of its own coordinates.
 * <p>
 * Read only. Nothing here writes the container, and nothing here imports the layout types the
 * editor's own model is made of: the panel learns "the kind has none" from
 * {@link Element#applicableProperties} and "not read" from a field that is {@code null}.
 * </p>
 */
public final class FormSnapshot
{
    /** The version token the layout opens with, or {@code null} when there is none. */
    public final String version;

    /** Whether the version is one the layout writers were measured on. */
    public final boolean versionSupported;

    /** The form's title in its one language, or the whole map when it has several. */
    public final Object title;

    /** The form's width and height as the layout records them, or {@code null}. */
    public final Integer width;

    /** @see #width */
    public final Integer height;

    /** The owner's kind, or {@code null} for a common form. */
    public final String ownerKind;

    /** The form's attributes. */
    public final List<Attribute> attributes;

    /** The pages of the form's root panel. */
    public final List<Page> pages;

    /** The form's own bound handlers. */
    public final List<Handler> formHandlers;

    /** The elements of the form, nested as the layout nests them. */
    public final List<Element> elements;

    /** Every element, nested ones included. */
    public final List<Element> allElements;

    /** Records of kinds the reader does not recognise, all kinds summed. */
    public final int unreadRecords;

    /** Why the layout could not be read, or {@code null} when it was. */
    public final String problem;

    private FormSnapshot(String version, boolean versionSupported, Object title, Integer width, Integer height,
        String ownerKind, List<Attribute> attributes, List<Page> pages, List<Handler> formHandlers,
        List<Element> elements, int unreadRecords, String problem)
    {
        this.version = version;
        this.versionSupported = versionSupported;
        this.title = title;
        this.width = width;
        this.height = height;
        this.ownerKind = ownerKind;
        this.attributes = attributes;
        this.pages = pages;
        this.formHandlers = formHandlers;
        this.elements = elements;
        this.allElements = flatten(elements);
        this.unreadRecords = unreadRecords;
        this.problem = problem;
    }

    /**
     * Builds a snapshot of a layout as it was read.
     *
     * @param layout the layout; {@code null} when the text could not be read
     * @param version the version token, or {@code null}
     * @param problem why the layout could not be read, or {@code null}
     * @return the snapshot
     */
    public static FormSnapshot of(FormLayout layout, String version, String problem)
    {
        if (layout == null)
        {
            return new FormSnapshot(version, false, null, null, null, null, List.of(), List.of(), List.of(),
                List.of(), 0, problem);
        }
        List<Attribute> attributes = new ArrayList<>();
        for (FormLayout.Attribute attribute : layout.attributes)
        {
            attributes.add(new Attribute(attribute.name, attribute.type));
        }
        List<Page> pages = new ArrayList<>();
        for (FormLayout.Page page : layout.pages)
        {
            pages.add(page(page));
        }
        List<Handler> handlers = new ArrayList<>();
        for (FormLayout.Handler handler : layout.formHandlers)
        {
            handlers.add(new Handler(handler.event, handler.procedure));
        }
        List<Element> elements = new ArrayList<>();
        for (FormLayout.Element element : layout.elements)
        {
            elements.add(element(element));
        }
        int unread = 0;
        for (Map.Entry<String, Integer> entry : layout.unread.entrySet())
        {
            unread += entry.getValue().intValue();
        }
        return new FormSnapshot(version, layout.version != null
            && ru.aiedt.mcp.oform.layout.LayoutEditor.SUPPORTED_VERSIONS.contains(layout.version),
            FormLayout.text(layout.title), layout.width, layout.height, layout.ownerKind, List.copyOf(attributes),
            List.copyOf(pages), List.copyOf(handlers), List.copyOf(elements), unread, problem);
    }

    /** An element of the form, with the branch that draws it and the properties its kind has. */
    public static final class Element
    {
        /** The element's kind as the reader names it ({@code Field}, {@code Panel}, ...). */
        public final String kind;

        /** The element's id in the layout. */
        public final String id;

        /** The element's name, or {@code null} when the record names none. */
        public final String name;

        /** The caption in its one language, or the whole map; {@code null} when not read. */
        public final Object title;

        /** The tooltip in its one language, or the whole map; {@code null} when not read. */
        public final Object tooltip;

        /** left, top, right, bottom, or {@code null} when the record has no box. */
        public final int[] box;

        /** The name of the page of the element's parent it sits on, or {@code null}. */
        public final String page;

        /** The data path in words, or {@code null}. */
        public final String data;

        /** The value type in words, or {@code null}. */
        public final String valueType;

        /** The element's bound handlers. */
        public final List<Handler> handlers;

        /** The buttons of a command panel. */
        public final List<Button> buttons;

        /** The pages of a panel. */
        public final List<Page> pages;

        /** The columns of a table. */
        public final List<Column> columns;

        /** The children, nested as the layout nests them. */
        public final List<Element> children;

        /** The branch of the drawing that draws the kind. */
        public final String renderBranch;

        /** The properties the kind was measured on; what is missing the kind has none of. */
        public final Set<String> applicableProperties;

        Element(String kind, String id, String name, Object title, Object tooltip, int[] box, String page,
            String data, String valueType, List<Handler> handlers, List<Button> buttons, List<Page> pages,
            List<Column> columns, List<Element> children)
        {
            this.kind = kind;
            this.id = id;
            this.name = name;
            this.title = title;
            this.tooltip = tooltip;
            this.box = box;
            this.page = page;
            this.data = data;
            this.valueType = valueType;
            this.handlers = handlers;
            this.buttons = buttons;
            this.pages = pages;
            this.columns = columns;
            this.children = children;
            this.renderBranch = RenderKinds.branchOf(kind);
            this.applicableProperties = applicablePropertiesOf(kind);
        }
    }

    /** A page of a panel. */
    public static final class Page
    {
        /** The page's name. */
        public final String name;

        /** The title in its one language, or the whole map. */
        public final Object title;

        Page(String name, Object title)
        {
            this.name = name;
            this.title = title;
        }
    }

    /** A handler bound to an event. */
    public static final class Handler
    {
        /** The event's name, or {@code null} when the code was not measured. */
        public final String event;

        /** The procedure. */
        public final String procedure;

        Handler(String event, String procedure)
        {
            this.event = event;
            this.procedure = procedure;
        }
    }

    /** A button of a command panel. */
    public static final class Button
    {
        /** The button's name. */
        public final String name;

        /** The caption in its one language, or the whole map. */
        public final Object title;

        /** The procedure its action runs, or {@code null}. */
        public final String procedure;

        Button(String name, Object title, String procedure)
        {
            this.name = name;
            this.title = title;
            this.procedure = procedure;
        }
    }

    /** A column of a table. */
    public static final class Column
    {
        /** The column's name. */
        public final String name;

        /** The title in its one language, or the whole map, or {@code null}. */
        public final Object title;

        /** The width as recorded, or {@code null}. */
        public final String width;

        /** The values' type in words, or {@code null}. */
        public final String valueType;

        /** The data path in words, or {@code null}. */
        public final String data;

        /** The cell's control, or {@code null} when the column only shows. */
        public final String control;

        Column(String name, Object title, String width, String valueType, String data, String control)
        {
            this.name = name;
            this.title = title;
            this.width = width;
            this.valueType = valueType;
            this.data = data;
            this.control = control;
        }
    }

    /** An attribute of the form. */
    public static final class Attribute
    {
        /** The attribute's name. */
        public final String name;

        /** The type in words. */
        public final String type;

        Attribute(String name, String type)
        {
            this.name = name;
            this.type = type;
        }
    }

    /** The properties a kind was measured on, under the panel's own names. */
    static Set<String> applicablePropertiesOf(String kind)
    {
        Set<String> applicable = new LinkedHashSet<>();
        if (PropertyApplicability.captionKinds().contains(kind))
        {
            applicable.add("caption"); //$NON-NLS-1$
        }
        applicable.add("tooltip"); //$NON-NLS-1$
        applicable.add("visible"); //$NON-NLS-1$
        if (PropertyApplicability.styleKinds().contains(kind))
        {
            applicable.add("enabled"); //$NON-NLS-1$
            applicable.add("font"); //$NON-NLS-1$
            applicable.add("textColor"); //$NON-NLS-1$
            applicable.add("backColor"); //$NON-NLS-1$
            applicable.add("borderColor"); //$NON-NLS-1$
        }
        if (PropertyApplicability.readOnlyKinds().contains(kind))
        {
            applicable.add("readOnly"); //$NON-NLS-1$
        }
        if (PropertyApplicability.alignmentKinds().contains(kind))
        {
            applicable.add("alignment"); //$NON-NLS-1$
        }
        if (PropertyApplicability.formatKinds().contains(kind))
        {
            applicable.add("format"); //$NON-NLS-1$
        }
        if (PropertyApplicability.eventKinds().contains(kind))
        {
            applicable.add("handlers"); //$NON-NLS-1$
        }
        return Set.copyOf(applicable);
    }

    private static Element element(FormLayout.Element element)
    {
        List<Handler> handlers = new ArrayList<>();
        for (FormLayout.Handler handler : element.handlers)
        {
            handlers.add(new Handler(handler.event, handler.procedure));
        }
        List<Button> buttons = new ArrayList<>();
        for (FormLayout.Button button : element.buttons)
        {
            buttons.add(new Button(button.name, FormLayout.text(button.caption), button.procedure));
        }
        List<Page> pages = new ArrayList<>();
        for (FormLayout.Page page : element.pages)
        {
            pages.add(page(page));
        }
        List<Column> columns = new ArrayList<>();
        for (FormLayout.Column column : element.columns)
        {
            columns.add(new Column(column.name, FormLayout.text(column.title), column.width, column.valueType,
                column.data, column.control));
        }
        List<Element> children = new ArrayList<>();
        for (FormLayout.Element child : element.children)
        {
            children.add(element(child));
        }
        return new Element(element.type, element.id, element.name, FormLayout.text(element.title),
            FormLayout.text(element.tooltip), element.box == null ? null : element.box.clone(), element.page,
            element.data, element.valueType, List.copyOf(handlers), List.copyOf(buttons), List.copyOf(pages),
            List.copyOf(columns), List.copyOf(children));
    }

    private static Page page(FormLayout.Page page)
    {
        return new Page(page.name, FormLayout.text(page.title));
    }

    private static List<Element> flatten(List<Element> elements)
    {
        List<Element> out = new ArrayList<>();
        collect(elements, out);
        return List.copyOf(out);
    }

    private static void collect(List<Element> elements, List<Element> out)
    {
        for (Element element : elements)
        {
            out.add(element);
            collect(element.children, out);
        }
    }
}
