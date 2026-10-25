/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.layout;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import ru.aiedt.mcp.oform.BraceTree;
import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.BraceTree.Node;
import ru.aiedt.mcp.oform.BraceTree.Token;

/**
 * Edits the layout of an ordinary form in its brace tree: captions, tooltips, boxes, the form's
 * title, handlers of elements and of the form, elements added from measured templates, pages,
 * elements moved to another parent or page, and elements removed with everything that referred
 * to them.
 *
 * <p>Every edit changes the records it names and nothing else, so the text written back differs
 * from the text read only where the edit is. What a record looks like was measured over the
 * corpus ({@link LayoutReader}); a new element is the per-position mode of every record of its
 * type in the corpus, with the id, the name, the box, the page, the caption, the tooltip, the
 * data and the handlers put in. The anchors of a new element name the element itself: its
 * bottom is its top plus its height, its right is its left plus its width, and nothing else is
 * anchored, which is what the Designer writes for an element placed by hand.</p>
 */
public final class LayoutEditor
{
    /** What an edit did, or why it did not. */
    public static final class Outcome
    {
        public final boolean done;

        public final String message;

        /** The id of an element the edit created, or {@code null}. */
        public final String elementId;

        Outcome(boolean done, String message, String elementId)
        {
            this.done = done;
            this.message = message;
            this.elementId = elementId;
        }

        static Outcome refused(String message)
        {
            return new Outcome(false, message, null);
        }

        static Outcome done(String message)
        {
            return new Outcome(true, message, null);
        }
    }

    /** An element record with the list that holds it. */
    private static final class Located
    {
        final ListNode record;

        final ListNode holder;

        final ListNode parentRecord;

        Located(ListNode record, ListNode holder, ListNode parentRecord)
        {
            this.record = record;
            this.holder = holder;
            this.parentRecord = parentRecord;
        }

        String name()
        {
            return LayoutReader.elementName(LayoutReader.identityOf(record));
        }

        String type()
        {
            return LayoutReader.typeName(LayoutReader.tokenText(record.get(0)));
        }

        String id()
        {
            return LayoutReader.tokenText(record.get(1));
        }
    }

    /** The position of the events list in the own list of a type, measured over the corpus. */
    private static final Map<String, Integer> EVENTS_AT = Map.ofEntries(Map.entry("Field", 4), //$NON-NLS-1$
        Map.entry("SelectField", 2), Map.entry("CheckBox", 2), Map.entry("RadioBtn", 5), Map.entry("Button", 2), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Map.entry("Label", 2), Map.entry("Image", 2), Map.entry("Panel", 2), Map.entry("Table", 4), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Map.entry("ListField", 2), Map.entry("CalendarBox", 2), Map.entry("FieldHtml", 2), Map.entry("TableField", 17), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Map.entry("Chart", 201)); //$NON-NLS-1$


    /** The layout versions every writer is measured on; another version is refused before any edit. */
    public static final Set<String> SUPPORTED_VERSIONS = Set.of("25", "26", "27"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** The name of the page a new panel is born with. */
    public static final String BORN_PAGE_NAME = "Страница1"; //$NON-NLS-1$

    private static final String NO_ANCHOR = "{2,-1,6,0}"; //$NON-NLS-1$

    private static final int ANCHOR_TOP = 6;

    private static final int ANCHOR_BOTTOM = 7;

    private static final int ANCHOR_LEFT = 8;

    private static final int ANCHOR_RIGHT = 9;

    private static final int ANCHORS_END = 12;

    private final ListNode root;

    private final String ownerKind;

    private final String language;

    private LayoutEditor(ListNode root, String ownerKind, String language)
    {
        this.root = root;
        this.ownerKind = ownerKind;
        this.language = language;
    }

    /**
     * An editor over a tree.
     *
     * @param root the brace tree of the {@code form} entry, edited in place
     * @param ownerKind the owner's kind as for {@link LayoutReader#read(ListNode, String)}
     * @param language the language code new texts are written in ({@code ru})
     * @return the editor
     * @throws UnsupportedLayoutVersionException when the layout's version is not one the writers
     *         were measured on: 25, 26 and 27
     */
    public static LayoutEditor over(ListNode root, String ownerKind, String language)
    {
        String version = root == null || root.size() == 0 ? null : LayoutReader.tokenText(root.get(0));
        if (version == null || !SUPPORTED_VERSIONS.contains(version))
        {
            throw new UnsupportedLayoutVersionException(version);
        }
        return new LayoutEditor(root, ownerKind, language == null || language.isEmpty() ? "ru" : language); //$NON-NLS-1$
    }

    /** @return the tree as edited */
    public ListNode root()
    {
        return root;
    }

    /** @return the layout as it reads now, ids unresolved */
    public FormLayout layout()
    {
        return LayoutReader.read(root, ownerKind);
    }

    // ---- the form ---------------------------------------------------------------------------

    /**
     * Sets the form's title.
     *
     * @param title the text
     * @return the outcome
     */
    public Outcome setTitle(String title)
    {
        ListNode body = body();
        ListNode head = body == null || body.size() < 2 ? null : LayoutReader.asList(body.get(1));
        if (head == null || head.size() < 1 || LayoutReader.localized(head.get(0)) == null)
        {
            return Outcome.refused("The layout has no title record where the corpus holds one."); //$NON-NLS-1$
        }
        head.items().set(0, LayoutReader.localizedNode(language, title));
        return Outcome.done("title set"); //$NON-NLS-1$
    }

    /**
     * Sets the form's size: the two numbers after the root panel in the body, and their second
     * copy when the body carries one.
     *
     * @param width the width, or {@code null} to keep
     * @param height the height, or {@code null} to keep
     * @return the outcome
     */
    public Outcome setSize(Integer width, Integer height)
    {
        ListNode body = body();
        if (body == null || body.size() < 5 || LayoutReader.intOf(body.get(3)) == null || LayoutReader.intOf(body.get(4)) == null)
        {
            return Outcome.refused("The layout has no size after the root panel where the corpus holds one."); //$NON-NLS-1$
        }
        int oldWidth = LayoutReader.intOf(body.get(3)).intValue();
        int oldHeight = LayoutReader.intOf(body.get(4)).intValue();
        int newWidth = width != null ? width.intValue() : oldWidth;
        int newHeight = height != null ? height.intValue() : oldHeight;
        if (newWidth <= 0 || newHeight <= 0)
        {
            return Outcome.refused("The size must be positive."); //$NON-NLS-1$
        }
        body.items().set(3, new Token(Integer.toString(newWidth)));
        body.items().set(4, new Token(Integer.toString(newHeight)));
        if (body.size() >= 13 && LayoutReader.intOf(body.get(11)) != null && LayoutReader.intOf(body.get(12)) != null
            && LayoutReader.intOf(body.get(11)).intValue() == oldWidth && LayoutReader.intOf(body.get(12)).intValue() == oldHeight)
        {
            body.items().set(11, new Token(Integer.toString(newWidth)));
            body.items().set(12, new Token(Integer.toString(newHeight)));
        }
        return Outcome.done("size set to " + newWidth + "x" + newHeight); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- properties of an element ---------------------------------------------------------

    /**
     * Sets the caption of an element that has one.
     *
     * @param elementName the element
     * @param text the caption
     * @return the outcome
     */
    public Outcome setCaption(String elementName, String text)
    {
        Located located = locate(elementName);
        if (located == null)
        {
            return noElement(elementName);
        }
        ListNode own = LayoutReader.ownOf(located.record);
        Node caption = LayoutReader.captionNode(located.type(), own);
        if (caption == null)
        {
            return Outcome.refused("An element of type " + located.type() + " has no caption."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        replace(own, caption, LayoutReader.localizedNode(language, text));
        return Outcome.done("caption of " + located.name() + " set"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Sets the tooltip of an element.
     *
     * @param elementName the element
     * @param text the tooltip; empty removes it
     * @return the outcome
     */
    public Outcome setTooltip(String elementName, String text)
    {
        Located located = locate(elementName);
        if (located == null)
        {
            return noElement(elementName);
        }
        ListNode style = LayoutReader.styleBlock(LayoutReader.ownOf(located.record));
        if (style == null)
        {
            return Outcome.refused("The record of " + located.name() + " has no style block to hold a tooltip."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        style.items().set(12, text == null || text.isEmpty() ? emptyLocalized() : LayoutReader.localizedNode(language, text));
        return Outcome.done("tooltip of " + located.name() + " set"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Sets the box of an element. The anchors that name the element's own size follow the box.
     *
     * @param elementName the element
     * @param left the left, or {@code null} to keep
     * @param top the top, or {@code null} to keep
     * @param right the right, or {@code null} to keep
     * @param bottom the bottom, or {@code null} to keep
     * @return the outcome
     */
    public Outcome setBox(String elementName, Integer left, Integer top, Integer right, Integer bottom)
    {
        Located located = locate(elementName);
        if (located == null)
        {
            return noElement(elementName);
        }
        ListNode common = LayoutReader.commonOf(located.record);
        int[] box = LayoutReader.box(common);
        if (box == null)
        {
            return Outcome.refused("The record of " + located.name() + " has no box."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        int[] next = { left != null ? left : box[0], top != null ? top : box[1], right != null ? right : box[2],
            bottom != null ? bottom : box[3] };
        if (next[2] < next[0] || next[3] < next[1])
        {
            return Outcome.refused("The box must have its right at or beyond its left and its bottom at or below its top."); //$NON-NLS-1$
        }
        for (int i = 0; i < 4; i++)
        {
            common.items().set(i + 1, new Token(Integer.toString(next[i])));
        }
        List<String> kept = selfAnchors(common, located.id(), next, false);
        String message = "box of " + located.name() + " set to " + next[0] + "," + next[1] + "," + next[2] + "," + next[3]; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        if (!kept.isEmpty())
        {
            message += "; the " + String.join(" and ", kept) + " stays anchored to another element, which decides that edge at run time"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return Outcome.done(message);
    }

    // ---- handlers ---------------------------------------------------------------------------

    /**
     * Binds an event of an element to a procedure, replacing the binding of that event when
     * there is one.
     *
     * @param elementName the element
     * @param event the event as {@link FormEvents} names it
     * @param procedure the procedure
     * @return the outcome
     */
    public Outcome bindHandler(String elementName, String event, String procedure)
    {
        Located located = locate(elementName);
        if (located == null)
        {
            return noElement(elementName);
        }
        String type = located.type();
        Integer code = FormEvents.elementCode(type, event);
        if (code == null)
        {
            return Outcome.refused("An element of type " + type + " has no event " + event + "; it has: " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + String.join(", ", FormEvents.elementEvents(type).values()) + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        ListNode events = eventsList(located);
        if (events == null)
        {
            return Outcome.refused("The record of " + located.name() + " has no events list where the corpus holds one."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String was = bind(events, code.intValue(), procedure);
        return Outcome.done(was == null ? event + " of " + located.name() + " bound to " + procedure //$NON-NLS-1$ //$NON-NLS-2$
            : event + " of " + located.name() + " rebound from " + was + " to " + procedure); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Removes the binding of an event of an element.
     *
     * @param elementName the element
     * @param event the event
     * @return the outcome
     */
    public Outcome unbindHandler(String elementName, String event)
    {
        Located located = locate(elementName);
        if (located == null)
        {
            return noElement(elementName);
        }
        Integer code = FormEvents.elementCode(located.type(), event);
        ListNode events = code == null ? null : eventsList(located);
        String was = events == null ? null : unbind(events, code.intValue());
        return was == null ? Outcome.refused(event + " of " + located.name() + " is not bound.") //$NON-NLS-1$ //$NON-NLS-2$
            : Outcome.done(event + " of " + located.name() + " unbound from " + was); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Binds an event of the form, or of its main attribute, to a procedure.
     *
     * @param event the event as {@link FormEvents} names it
     * @param procedure the procedure
     * @return the outcome
     */
    public Outcome bindFormHandler(String event, String procedure)
    {
        boolean settings = layout().settingsHolder;
        Integer code = FormEvents.formCode(event, settings);
        if (code == null)
        {
            return Outcome.refused("The form has no event " + event + "; it has: " //$NON-NLS-1$ //$NON-NLS-2$
                + String.join(", ", FormEvents.formEvents().values()) + " and, of its main attribute, " //$NON-NLS-1$ //$NON-NLS-2$
                + String.join(", ", FormEvents.mainAttributeEvents(settings).values()) + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        ListNode events = formEventsList(code.intValue());
        if (events == null)
        {
            return Outcome.refused("The layout has no events list for " + event + " where the corpus holds one."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String was = bind(events, code.intValue(), procedure);
        return Outcome.done(was == null ? event + " of the form bound to " + procedure //$NON-NLS-1$
            : event + " of the form rebound from " + was + " to " + procedure); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Removes the binding of an event of the form or of its main attribute.
     *
     * @param event the event
     * @return the outcome
     */
    public Outcome unbindFormHandler(String event)
    {
        Integer code = FormEvents.formCode(event, layout().settingsHolder);
        ListNode events = code == null ? null : formEventsList(code.intValue());
        String was = events == null ? null : unbind(events, code.intValue());
        return was == null ? Outcome.refused(event + " of the form is not bound.") //$NON-NLS-1$
            : Outcome.done(event + " of the form unbound from " + was); //$NON-NLS-1$
    }

    private String bind(ListNode events, int code, String procedure)
    {
        String was = unbind(events, code);
        events.items().add(handlerEntry(code, procedure));
        events.items().set(0, new Token(Integer.toString(events.size() - 1)));
        return was;
    }

    private static String unbind(ListNode events, int code)
    {
        String was = null;
        for (int i = events.size() - 1; i >= 1; i--)
        {
            ListNode entry = LayoutReader.asList(events.get(i));
            if (entry != null && LayoutReader.isHandlerEntry(entry) && Integer.toString(code).equals(LayoutReader.tokenText(entry.get(0))))
            {
                was = LayoutReader.handlerProcedure(entry.get(2));
                events.items().remove(i);
            }
        }
        events.items().set(0, new Token(Integer.toString(events.size() - 1)));
        return was;
    }

    private ListNode handlerEntry(int code, String procedure)
    {
        String title = presentation(procedure);
        ListNode action = list(new Token("1"), Token.string(procedure), LayoutReader.localizedNode(language, title), //$NON-NLS-1$
            LayoutReader.localizedNode(language, title), LayoutReader.localizedNode(language, title), font(),
            list(new Token("0"), new Token("0"), new Token("0"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        ListNode record = list(new Token("3"), Token.string(procedure), action); //$NON-NLS-1$
        return list(new Token(Integer.toString(code)), new Token(FormEvents.HANDLER), record);
    }

    /**
     * The presentation the Designer derives from a procedure name: the words of the name,
     * the first capitalised, the rest lower case - {@code ИмяПриИзменении} is
     * {@code Имя при изменении}.
     *
     * @param procedure the procedure name
     * @return the presentation
     */
    public static String presentation(String procedure)
    {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < procedure.length(); i++)
        {
            char c = procedure.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && !Character.isUpperCase(procedure.charAt(i - 1)))
            {
                out.append(' ');
                out.append(Character.toLowerCase(c));
            }
            else
            {
                out.append(c);
            }
        }
        return out.toString();
    }

    private ListNode eventsList(Located located)
    {
        Integer at = EVENTS_AT.get(located.type());
        ListNode own = LayoutReader.ownOf(located.record);
        if (at == null || own == null || own.size() <= at.intValue())
        {
            return null;
        }
        ListNode events = LayoutReader.asList(own.get(at.intValue()));
        return isEventsList(events) ? events : null;
    }

    private static boolean isEventsList(ListNode list)
    {
        if (list == null || list.size() < 1 || LayoutReader.intOf(list.get(0)) == null
            || LayoutReader.intOf(list.get(0)).intValue() != list.size() - 1)
        {
            return false;
        }
        for (int i = 1; i < list.size(); i++)
        {
            ListNode entry = LayoutReader.asList(list.get(i));
            if (entry == null || !LayoutReader.isHandlerEntry(entry))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * The list the form keeps a code's handlers in: the list after the main attribute's block
     * for the form's own events, the list inside that block for the main attribute's.
     */
    private ListNode formEventsList(int code)
    {
        if (root.size() < 5)
        {
            return null;
        }
        ListNode block = LayoutReader.asList(root.get(3));
        if (code >= 80000 && code < 90000)
        {
            // the block of the main attribute: {<uuid of the kind>, 1, {...}} with the events list
            // at a position of its own per kind - the third item on a catalog, the second on a
            // document or register, the fourth on a data processor or report
            ListNode inner = block == null || block.size() < 3 ? null : LayoutReader.asList(block.get(2));
            for (int i = 0; inner != null && i < inner.size(); i++)
            {
                ListNode events = LayoutReader.asList(inner.get(i));
                if (isEventsList(events))
                {
                    return events;
                }
            }
            return null;
        }
        ListNode events = LayoutReader.asList(root.get(4));
        return isEventsList(events) ? events : null;
    }

    // ---- elements ---------------------------------------------------------------------------

    /**
     * Adds an element from the template of its type.
     *
     * @param type the element type ({@code Label}, {@code Field}, {@code Button}, {@code CheckBox},
     *        {@code Group}, {@code Panel}, {@code RadioBtn}, {@code Separator}, {@code Image},
     *        {@code SelectField}, {@code TableField}, {@code FieldHtml}, {@code ListField},
     *        {@code TextDocumentField}, {@code Chart}). A {@code TableField} is the record 50 fields
     *        of version 27 share, out of 142 fields on 131 forms (115 of the fields sit on version
     *        27); it shows the spreadsheet document the record carries, and the corpus binds no
     *        form attribute to one, so {@code data} is refused. A {@code FieldHtml} is the record
     *        4 of the 20 HTML fields on versions 25, 26 and 27 share (21 fields on 18 forms; 19 of
     *        them sit on version 27); it shows the HTML document set in code, and {@code data} is
     *        refused. A {@code ListField} is the record 3 of the 17 list fields share; it shows a
     *        value list, so {@code data} is required and names a value-list attribute. A
     *        {@code TextDocumentField} is one real record - no two of the 17 fields on 10 forms
     *        share one - and shows the text document set in code, so {@code data} is refused. A
     *        {@code Chart} is one real record of seven items - no two of the 11 charts on versions
     *        25, 26 and 27 share one (12 charts on 9 forms); it shows the series and points of its
     *        own record, so {@code data} is refused.
     * @param name the element's name, unique on the form
     * @param parentPanel the panel to put it in, or {@code null} for the form
     * @param page the page of that panel or form, or {@code null} for the first
     * @param box left, top, right, bottom
     * @param caption the caption, for a type with one; {@code null} for none
     * @param tooltip the tooltip, or {@code null}
     * @param data the pattern of the data the element shows, or {@code null}
     * @return the outcome, with the new element's id
     */
    public Outcome addElement(String type, String name, String parentPanel, String page, int[] box, String caption,
        String tooltip, ListNode data)
    {
        return addElement(type, name, parentPanel, page, box, caption, tooltip, data, null);
    }

    /**
     * Adds an element from the template of its type, with the settings block a table's rows call
     * for.
     *
     * @param settings the settings block of a table, {@code {<uuid>,{...}}} as
     *        {@link TableColumns#settingsBlock} gives it; a table without one is refused
     * @return the outcome, with the new element's id
     * @see #addElement(String, String, String, String, int[], String, String, ListNode)
     */
    public Outcome addElement(String type, String name, String parentPanel, String page, int[] box, String caption,
        String tooltip, ListNode data, ListNode settings)
    {
        String canonical = canonicalType(type);
        if ("Table".equals(canonical) && (data == null || settings == null)) //$NON-NLS-1$
        {
            return Outcome.refused("A table shows rows: give data naming the list, tabular section or value table it shows."); //$NON-NLS-1$
        }
        if ("TableField".equals(canonical) && data != null) //$NON-NLS-1$
        {
            return refusedTableFieldData(data);
        }
        if ("ListField".equals(canonical) && data == null) //$NON-NLS-1$
        {
            return Outcome.refused("A list field shows a value list: give data naming the form attribute it shows."); //$NON-NLS-1$
        }
        if ("ListField".equals(canonical) && !isValueList(data)) //$NON-NLS-1$
        {
            return refusedData("A list field shows a value list", data); //$NON-NLS-1$
        }
        if ("FieldHtml".equals(canonical) && data != null) //$NON-NLS-1$
        {
            return refusedData("An HTML field shows the HTML document set in code", data); //$NON-NLS-1$
        }
        if ("TextDocumentField".equals(canonical) && data != null) //$NON-NLS-1$
        {
            return refusedData("A text-document field shows the text document set in code", data); //$NON-NLS-1$
        }
        if ("Chart".equals(canonical) && data != null) //$NON-NLS-1$
        {
            return refusedData("A chart shows the series and points of its own record", data); //$NON-NLS-1$
        }
        if (canonical == null || !ElementKinds.WRITABLE.contains(canonical))
        {
            return Outcome.refused("No template for an element of type " + type + "; templates: " //$NON-NLS-1$ //$NON-NLS-2$
                + String.join(", ", ElementKinds.WRITABLE) + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (name == null || name.isEmpty() || !name.matches("[\\p{L}_][\\p{L}\\p{N}_]*")) //$NON-NLS-1$
        {
            return Outcome.refused("The name must be an identifier."); //$NON-NLS-1$
        }
        if (tooltip != null && !tooltip.isEmpty() && !carriesATooltip(canonical))
        {
            return Outcome.refused("An element of type " + canonical //$NON-NLS-1$
                + " carries no tooltip: the record the corpus writes for it has no slot for one."); //$NON-NLS-1$
        }
        if (locate(name) != null)
        {
            return Outcome.refused("The form already has an element named " + name + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        ListNode holder;
        List<FormLayout.Page> pages;
        if (parentPanel == null || parentPanel.isEmpty())
        {
            holder = rootChildren();
            pages = layout().pages;
        }
        else
        {
            Located panel = locate(parentPanel);
            if (panel == null)
            {
                return noElement(parentPanel);
            }
            if (!"Panel".equals(panel.type())) //$NON-NLS-1$
            {
                return Outcome.refused(parentPanel + " is a " + panel.type() + ", not a Panel; only a panel holds elements."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            holder = LayoutReader.childrenOf(panel.record);
            FormLayout.Element element = layout().elementNamed(parentPanel);
            pages = element == null ? new ArrayList<>() : element.pages;
        }
        if (holder == null)
        {
            return Outcome.refused("The holder of the elements was not found in the layout."); //$NON-NLS-1$
        }
        int pageIndex = 0;
        if (page != null && !page.isEmpty())
        {
            pageIndex = -1;
            for (int i = 0; i < pages.size(); i++)
            {
                if (page.equalsIgnoreCase(pages.get(i).name))
                {
                    pageIndex = i;
                }
            }
            if (pageIndex < 0)
            {
                List<String> names = new ArrayList<>();
                for (FormLayout.Page one : pages)
                {
                    names.add(one.name);
                }
                return Outcome.refused("No page " + page + " there; pages: " + names + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
        ListNode record;
        try
        {
            record = template(canonical);
        }
        catch (IOException e)
        {
            return Outcome.refused("The template of " + canonical + " could not be read: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        int newId = maxElementId() + 1;
        String id = Integer.toString(newId);
        fill(record, id, name, box, pageIndex, caption, tooltip, data);
        if ("TableField".equals(canonical)) //$NON-NLS-1$
        {
            // [2][1..4] repeats the box on 138 of 142 fields
            ListNode own = LayoutReader.asList(record.get(2));
            if (own != null && own.size() > 4)
            {
                for (int i = 0; i < 4; i++)
                {
                    own.items().set(i + 1, new Token(Integer.toString(box[i])));
                }
            }
        }
        if (settings != null)
        {
            substitute(record, "@SETTINGS", () -> copy(settings)); //$NON-NLS-1$
        }
        if ("CommandPanel".equals(canonical)) //$NON-NLS-1$
        {
            // the panel's own placeholders: a fresh content uuid, the button id allocator at
            // zero, no auto-fill and no source of actions
            CommandPanels.prepareNew(record);
        }
        holder.items().add(record);
        holder.items().set(0, new Token(Integer.toString(holder.size() - 1)));
        refreshPageBoundsAround(name);
        // the last number of the body: the next element id the Designer hands out, above every
        // id in use in 649 of 670 measured forms
        ListNode body = body();
        Integer next = body == null || body.size() < 11 ? null : LayoutReader.intOf(body.get(10));
        if (next != null && next.intValue() <= newId)
        {
            body.items().set(10, new Token(Integer.toString(newId + 1)));
        }
        return new Outcome(true, canonical + " " + name + " added with id " + id, id); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Adds the command panel of an object form's standard actions - OK, Записать, Закрыть - as
     * the corpus holds it, with fresh identifiers for the panel and its actions.
     *
     * @param name the panel's name, unique on the form
     * @param box left, top, right, bottom
     * @return the outcome, with the new element's id
     */
    public Outcome addStandardActions(String name, int[] box)
    {
        if (locate(name) != null)
        {
            return Outcome.refused("The form already has an element named " + name + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        ListNode holder = rootChildren();
        if (holder == null)
        {
            return Outcome.refused("The holder of the elements was not found in the layout."); //$NON-NLS-1$
        }
        ListNode record;
        try
        {
            record = template("StandardActions"); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            return Outcome.refused("The template of the standard actions could not be read: " + e.getMessage()); //$NON-NLS-1$
        }
        int newId = maxElementId() + 1;
        String id = Integer.toString(newId);
        fill(record, id, name, box, 0, null, null, null);
        for (int i = 0; i < 7; i++)
        {
            String uuid = java.util.UUID.randomUUID().toString();
            substitute(record, "@U" + i, () -> new Token(uuid)); //$NON-NLS-1$
        }
        substitute(record, "@OK", () -> LayoutReader.localizedNode(language, "OK")); //$NON-NLS-1$ //$NON-NLS-2$
        substitute(record, "@WRITE", () -> LayoutReader.localizedNode(language, "Записать")); //$NON-NLS-1$ //$NON-NLS-2$
        substitute(record, "@CLOSE", () -> LayoutReader.localizedNode(language, "Закрыть")); //$NON-NLS-1$ //$NON-NLS-2$
        holder.items().add(record);
        holder.items().set(0, new Token(Integer.toString(holder.size() - 1)));
        ListNode body = body();
        Integer next = body == null || body.size() < 11 ? null : LayoutReader.intOf(body.get(10));
        if (next != null && next.intValue() <= newId)
        {
            body.items().set(10, new Token(Integer.toString(newId + 1)));
        }
        return new Outcome(true, "CommandPanel " + name + " added with id " + id, id); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Records the data an element shows.
     *
     * @param elementId the element
     * @param path the segments of the path: the attribute id, then members by uuid or standard
     *        attributes by code
     * @return the outcome
     */
    public Outcome bindData(String elementId, List<FormLayout.Segment> path)
    {
        ListNode registry = registry();
        if (registry == null)
        {
            return Outcome.refused("The layout has no binding registry where the corpus holds one."); //$NON-NLS-1$
        }
        for (int i = registry.size() - 1; i >= 1; i--)
        {
            ListNode pair = LayoutReader.asList(registry.get(i));
            if (pair != null && pair.size() == 2 && elementId.equals(LayoutReader.tokenText(pair.get(0))))
            {
                registry.items().remove(i);
            }
        }
        ListNode segments = new ListNode();
        segments.items().add(new Token(Integer.toString(path.size())));
        for (FormLayout.Segment segment : path)
        {
            if (segment.kind == FormLayout.Segment.MEMBER)
            {
                segments.items().add(list(new Token("0"), new Token(segment.value))); //$NON-NLS-1$
            }
            else
            {
                segments.items().add(list(new Token(segment.value)));
            }
        }
        registry.items().add(list(new Token(elementId), segments));
        registry.items().set(0, new Token(Integer.toString(registry.size() - 1)));
        return Outcome.done("data of element " + elementId + " bound"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Renames a page of a panel, and its title with it.
     *
     * @param panelName the panel, or {@code null} for the form
     * @param name the page as it is named now
     * @param newName the name to give it
     * @param title the title to give it, or {@code null} to keep the one it has
     * @return the outcome
     */
    public Outcome renamePage(String panelName, String name, String newName, String title)
    {
        if (name == null || name.isEmpty() || newName == null || newName.isEmpty()
            || !newName.matches("[\\p{L}_][\\p{L}\\p{N}_]*")) //$NON-NLS-1$
        {
            return Outcome.refused("rename_page takes the page and a name that is an identifier."); //$NON-NLS-1$
        }
        ListNode own;
        if (panelName == null || panelName.isEmpty())
        {
            ListNode rootPanel = rootPanel();
            own = rootPanel == null ? null : LayoutReader.asList(rootPanel.get(1));
        }
        else
        {
            Located panel = locate(panelName);
            if (panel == null)
            {
                return noElement(panelName);
            }
            own = LayoutReader.ownOf(panel.record);
        }
        ListNode props = own == null || own.size() < 2 ? null : LayoutReader.asList(own.get(1));
        ListNode pages = pagesList(props);
        if (pages == null || pages.size() < 3)
        {
            return Outcome.refused("No pages list was found on " + (panelName == null ? "the form" : panelName) + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        for (int i = 2; i < pages.size(); i++)
        {
            ListNode page = LayoutReader.asList(pages.get(i));
            if (page == null || page.size() < 7)
            {
                continue;
            }
            if (!name.equalsIgnoreCase(LayoutReader.stringValue(page.get(6))))
            {
                continue;
            }
            page.items().set(6, Token.string(newName));
            if (title != null && !title.isEmpty())
            {
                page.items().set(1, LayoutReader.localizedNode(language, title));
            }
            return Outcome.done("page " + name + " renamed to " + newName); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return Outcome.refused("No page named " + name + "."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Recomputes the page bounds a panel carries behind its pages list - four rows per page, the
     * box of the elements standing on that page. The platform lays a page out inside that box, so
     * a page left at zero draws its elements from the panel's own top, under the tab strip. A page
     * with no elements keeps the rows it has.
     *
     * @param elementName the element whose page to recompute
     */
    public void refreshPageBoundsAround(String elementName)
    {
        Located located = locate(elementName);
        Integer ordinal = located == null ? null : LayoutReader.pageIndex(LayoutReader.commonOf(located.record));
        if (ordinal != null)
        {
            refreshPageBounds(located.parentRecord, located.holder, ordinal.intValue());
        }
    }

    /**
     * @param parentRecord the panel's record, or {@code null} for the form
     * @param holder the children list of that panel
     * @param pageIndex the page to recompute; the other pages keep the rows they have
     */
    private void refreshPageBounds(ListNode parentRecord, ListNode holder, int pageIndex)
    {
        ListNode own;
        if (parentRecord == null)
        {
            ListNode rootPanel = rootPanel();
            own = rootPanel == null ? null : LayoutReader.asList(rootPanel.get(1));
        }
        else
        {
            own = LayoutReader.ownOf(parentRecord);
        }
        ListNode props = own == null || own.size() < 2 ? null : LayoutReader.asList(own.get(1));
        ListNode pages = props == null ? null : pagesList(props);
        if (pages == null || holder == null || checkPageGeometry(props, pages) != null)
        {
            return;
        }
        if (pageIndex < 0 || pageIndex >= pages.size() - 2)
        {
            return;
        }
        int[] bounds = null;
        for (int i = 1; i < holder.size(); i++)
        {
            ListNode child = LayoutReader.asList(holder.get(i));
            ListNode common = child == null || !LayoutReader.isElementRecord(child) ? null
                : LayoutReader.commonOf(child);
            Integer ordinal = common == null || common.size() < 5 ? null : LayoutReader.pageIndex(common);
            if (ordinal == null || ordinal.intValue() != pageIndex)
            {
                continue;
            }
            int[] box = new int[4];
            boolean read = true;
            for (int edge = 0; edge < 4; edge++)
            {
                Integer value = LayoutReader.intOf(common.get(edge + 1));
                read = read && value != null;
                box[edge] = value == null ? 0 : value.intValue();
            }
            if (!read)
            {
                continue;
            }
            bounds = bounds == null ? box
                : new int[] { Math.min(bounds[0], box[0]), Math.min(bounds[1], box[1]),
                    Math.max(bounds[2], box[2]), Math.max(bounds[3], box[3]) };
        }
        if (bounds == null)
        {
            return;
        }
        int k = indexOf(props, pages);
        int rows = LayoutReader.intOf(props.get(k + 4)).intValue();
        for (int i = 0; i < rows; i++)
        {
            ListNode row = LayoutReader.asList(props.get(k + 5 + i));
            Integer edge = row == null ? null : LayoutReader.intOf(row.get(4));
            Integer page = row == null ? null : LayoutReader.intOf(row.get(5));
            if (edge == null || page == null || page.intValue() != pageIndex || edge.intValue() < 1
                || edge.intValue() > 4)
            {
                continue;
            }
            row.items().set(1, new Token(Integer.toString(bounds[edge.intValue() - 1])));
        }
    }

    /**
     * Switches a panel between a tab per page and pages turned over from code.
     *
     * @param panelName the panel, or {@code null} for the form itself
     * @param tabs whether the platform draws a tab per page
     * @return the outcome
     */
    public Outcome setTabs(String panelName, boolean tabs)
    {
        ListNode props = panelProps(panelName);
        if (props == null)
        {
            return panelName == null || panelName.isEmpty() ? Outcome.refused("The form holds no pages list.") //$NON-NLS-1$
                : noElement(panelName);
        }
        int flag = LayoutReader.tabsFlagAt(props);
        if (flag < 0)
        {
            return Outcome.refused("No pages list was found on " //$NON-NLS-1$
                + (panelName == null || panelName.isEmpty() ? "the form" : panelName) //$NON-NLS-1$
                + ", so there is no tabs flag to set."); //$NON-NLS-1$
        }
        props.items().set(flag, new Token(tabs ? "1" : "0")); //$NON-NLS-1$ //$NON-NLS-2$
        return Outcome.done((panelName == null || panelName.isEmpty() ? "the form" : panelName) //$NON-NLS-1$
            + (tabs ? " draws a tab per page" : " turns its pages over from code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * @param panelName a panel, or {@code null} for the form itself
     * @return its property block, or {@code null} when there is no such panel
     */
    private ListNode panelProps(String panelName)
    {
        ListNode own;
        if (panelName == null || panelName.isEmpty())
        {
            ListNode rootPanel = rootPanel();
            own = rootPanel == null ? null : LayoutReader.asList(rootPanel.get(1));
        }
        else
        {
            Located panel = locate(panelName);
            if (panel == null || !"Panel".equals(panel.type())) //$NON-NLS-1$
            {
                return null;
            }
            own = LayoutReader.ownOf(panel.record);
        }
        return own == null || own.size() < 2 ? null : LayoutReader.asList(own.get(1));
    }

    /**
     * Adds a page to the form's root panel or to a panel, after its last page.
     *
     * @param panelName the panel, or {@code null} for the form
     * @param name the page's name
     * @param title the page's title
     * @return the outcome
     */
    public Outcome addPage(String panelName, String name, String title)
    {
        ListNode own;
        if (panelName == null || panelName.isEmpty())
        {
            ListNode rootPanel = rootPanel();
            own = rootPanel == null ? null : LayoutReader.asList(rootPanel.get(1));
        }
        else
        {
            Located panel = locate(panelName);
            if (panel == null)
            {
                return noElement(panelName);
            }
            if (!"Panel".equals(panel.type())) //$NON-NLS-1$
            {
                return Outcome.refused(panelName + " is a " + panel.type() + ", not a Panel."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            own = LayoutReader.ownOf(panel.record);
        }
        ListNode props = own == null || own.size() < 2 ? null : LayoutReader.asList(own.get(1));
        ListNode pages = pagesList(props);
        if (pages == null || pages.size() < 3)
        {
            return Outcome.refused("No pages list with a page to copy was found; a page is added beside an existing one."); //$NON-NLS-1$
        }
        String geometry = checkPageGeometry(props, pages);
        if (geometry != null)
        {
            return Outcome.refused(geometry);
        }
        for (int i = 2; i < pages.size(); i++)
        {
            ListNode existing = LayoutReader.asList(pages.get(i));
            if (existing != null && name.equalsIgnoreCase(LayoutReader.stringValue(existing.get(6))))
            {
                return Outcome.refused("A page named " + name + " is already there."); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        ListNode page = copy(LayoutReader.asList(pages.get(pages.size() - 1)));
        page.items().set(1, LayoutReader.localizedNode(language, title == null ? name : title));
        page.items().set(6, Token.string(name));
        pages.items().add(page);
        pages.items().set(1, new Token(Integer.toString(pages.size() - 2)));
        growPageGeometry(props, pages, pages.size() - 3);
        String andTabs = ""; //$NON-NLS-1$
        int flag = LayoutReader.tabsFlagAt(props);
        // a panel born with one page carries no tab strip; the page that makes it two is the page
        // the platform has to let the user reach, so the tabs come on with it
        if (flag >= 0 && pages.size() == 4 && "0".equals(LayoutReader.tokenText(props.get(flag)))) //$NON-NLS-1$
        {
            props.items().set(flag, new Token("1")); //$NON-NLS-1$
            andTabs = ", and the panel now draws a tab per page"; //$NON-NLS-1$
        }
        return Outcome.done("page " + name + " added as page " + (pages.size() - 3) + andTabs); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Removes a page of the form's root panel or of a panel. A page that holds elements is
     * refused, and so is the last page; elements on the pages after it move down one ordinal.
     *
     * @param panelName the panel, or {@code null} for the form
     * @param name the page's name
     * @return the outcome
     */
    public Outcome removePage(String panelName, String name)
    {
        ListNode own;
        ListNode children;
        if (panelName == null || panelName.isEmpty())
        {
            ListNode rootPanel = rootPanel();
            own = rootPanel == null ? null : LayoutReader.asList(rootPanel.get(1));
            children = rootChildren();
        }
        else
        {
            Located panel = locate(panelName);
            if (panel == null)
            {
                return noElement(panelName);
            }
            if (!"Panel".equals(panel.type())) //$NON-NLS-1$
            {
                return Outcome.refused(panelName + " is a " + panel.type() + ", not a Panel."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            own = LayoutReader.ownOf(panel.record);
            children = LayoutReader.childrenOf(panel.record);
        }
        ListNode props = own == null || own.size() < 2 ? null : LayoutReader.asList(own.get(1));
        ListNode pages = pagesList(props);
        if (pages == null || pages.size() < 3)
        {
            return Outcome.refused("No pages list was found where the corpus holds one."); //$NON-NLS-1$
        }
        String geometry = checkPageGeometry(props, pages);
        if (geometry != null)
        {
            return Outcome.refused(geometry);
        }
        int index = -1;
        for (int i = 2; i < pages.size(); i++)
        {
            ListNode existing = LayoutReader.asList(pages.get(i));
            if (existing != null && name != null && name.equalsIgnoreCase(LayoutReader.stringValue(existing.get(6))))
            {
                index = i - 2;
            }
        }
        if (index < 0)
        {
            return Outcome.refused("No page named " + name + " there."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (pages.size() == 3)
        {
            return Outcome.refused("The last page stays: a panel holds at least one."); //$NON-NLS-1$
        }
        List<String> onIt = new ArrayList<>();
        for (int i = 1; children != null && i < children.size(); i++)
        {
            ListNode child = LayoutReader.asList(children.get(i));
            Integer ordinal = child == null || !LayoutReader.isElementRecord(child) ? null
                : LayoutReader.pageIndex(LayoutReader.commonOf(child));
            if (ordinal != null && ordinal.intValue() == index)
            {
                onIt.add(LayoutReader.elementName(LayoutReader.identityOf(child)));
            }
        }
        if (!onIt.isEmpty())
        {
            return Outcome.refused("The page " + name + " holds " + onIt + "; remove or move them first."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        pages.items().remove(index + 2);
        pages.items().set(1, new Token(Integer.toString(pages.size() - 2)));
        for (int i = 1; children != null && i < children.size(); i++)
        {
            ListNode child = LayoutReader.asList(children.get(i));
            ListNode common = child == null || !LayoutReader.isElementRecord(child) ? null
                : LayoutReader.commonOf(child);
            Integer ordinal = LayoutReader.pageIndex(common);
            if (ordinal != null && ordinal.intValue() > index)
            {
                common.items().set(common.size() - LayoutReader.PAGE_FROM_END, new Token(Integer.toString(ordinal.intValue() - 1)));
            }
        }
        shrinkPageGeometry(props, pages, index);
        return Outcome.done("page " + name + " removed"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Drops the geometry rows and the entry of a removed page; the rows of later pages move down. */
    private static void shrinkPageGeometry(ListNode props, ListNode pages, int removed)
    {
        int k = indexOf(props, pages);
        int rows = LayoutReader.intOf(props.get(k + 4)).intValue();
        int kept = 0;
        for (int i = 0; i < rows; i++)
        {
            ListNode row = LayoutReader.asList(props.get(k + 5 + kept));
            Integer page = LayoutReader.intOf(row.get(5));
            if (page != null && page.intValue() == removed)
            {
                props.items().remove(k + 5 + kept);
                continue;
            }
            if (page != null && page.intValue() > removed)
            {
                row.items().set(5, new Token(Integer.toString(page.intValue() - 1)));
            }
            kept++;
        }
        props.items().set(k + 4, new Token(Integer.toString(kept)));
        // the entry of the removed page follows the 0 behind the rows
        props.items().remove(k + 5 + kept + 1 + removed);
    }

    /**
     * The block a panel keeps behind its pages list: three numbers, a counted list of nine-item
     * geometry rows {2, coordinate, axis, 1, edge, page, 0, offset, 0} - four per page - then 0,
     * one entry per page (4294967295 when the page names no element), and 5, 64, 0. Measured over
     * 1147 panels: 1141 hold one entry per page, and a page added without its entry is a form the
     * platform does not open.
     *
     * @return why the block is not that shape, or {@code null} when it is
     */
    private static String checkPageGeometry(ListNode props, ListNode pages)
    {
        int k = indexOf(props, pages);
        int pageCount = pages.size() - 2;
        if (k < 0 || props.size() < k + 6)
        {
            return "The geometry block behind the pages list was not found."; //$NON-NLS-1$
        }
        Integer rows = LayoutReader.intOf(props.get(k + 4));
        if (rows == null || rows.intValue() < 0 || props.size() < k + 5 + rows.intValue() + 1 + pageCount + 2)
        {
            return "The geometry block behind the pages list does not count its rows."; //$NON-NLS-1$
        }
        for (int i = 0; i < rows.intValue(); i++)
        {
            ListNode row = LayoutReader.asList(props.get(k + 5 + i));
            if (row == null || row.size() != 9 || !"2".equals(LayoutReader.tokenText(row.get(0)))) //$NON-NLS-1$
            {
                return "The geometry block behind the pages list holds a row of another shape."; //$NON-NLS-1$
            }
        }
        int zero = k + 5 + rows.intValue();
        if (!"0".equals(LayoutReader.tokenText(props.get(zero))) //$NON-NLS-1$
            || !"5".equals(LayoutReader.tokenText(props.get(zero + 1 + pageCount))) //$NON-NLS-1$
            || !"64".equals(LayoutReader.tokenText(props.get(zero + 2 + pageCount)))) //$NON-NLS-1$
        {
            return "The geometry block behind the pages list does not hold one entry per page."; //$NON-NLS-1$
        }
        return null;
    }

    /** Adds the four geometry rows and the entry of a page to the block {@link #checkPageGeometry} accepted. */
    private static void growPageGeometry(ListNode props, ListNode pages, int pageIndex)
    {
        int k = indexOf(props, pages);
        int rows = LayoutReader.intOf(props.get(k + 4)).intValue();
        int at = k + 5 + rows;
        for (int edge = 1; edge <= 4; edge++)
        {
            props.items().add(at++, list(new Token("2"), new Token("0"), new Token(edge % 2 == 1 ? "1" : "0"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                new Token("1"), new Token(Integer.toString(edge)), new Token(Integer.toString(pageIndex)), //$NON-NLS-1$
                new Token("0"), new Token("0"), new Token("0"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        props.items().set(k + 4, new Token(Integer.toString(rows + 4)));
        // at now points at the 0 before the entries; the entries of the pages before this one follow it
        props.items().add(at + 1 + pageIndex, new Token("4294967295")); //$NON-NLS-1$
    }

    private static int indexOf(ListNode holder, Node item)
    {
        for (int i = 0; i < holder.size(); i++)
        {
            if (holder.get(i) == item)
            {
                return i;
            }
        }
        return -1;
    }

    /**
     * Adds a column to a table, after its last one.
     *
     * @param tableName the table
     * @param kind the kind of column the table takes, from {@link TableColumns#kindForRows}
     * @param name the column's name, unique on the table
     * @param caption the caption, or {@code null} for the name
     * @param width the width, or {@code null} for the template's
     * @param pattern the type pattern of the values, {@code {"Pattern",...}}, or {@code null}
     * @param path the binding: one standard attribute or one member of the row, or empty
     * @param valueTableColumn the column of the value table the column shows, for that kind
     * @param control the cell's control: {@code field}, {@code checkBox}, {@code selectField}
     *        or {@code none}; the record carries the control's uuid and, unless {@code none},
     *        its blob
     * @return the outcome
     */
    public Outcome addColumn(String tableName, String kind, String name, String caption, Integer width, ListNode pattern,
        List<FormLayout.Segment> path, String valueTableColumn, String control)
    {
        Located table = locate(tableName);
        if (table == null)
        {
            return noElement(tableName);
        }
        if (!"Table".equals(table.type())) //$NON-NLS-1$
        {
            return Outcome.refused(tableName + " is a " + table.type() + ", not a Table."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (name == null || name.isEmpty() || !name.matches("[\\p{L}_][\\p{L}\\p{N}_]*")) //$NON-NLS-1$
        {
            return Outcome.refused("The column's name must be an identifier."); //$NON-NLS-1$
        }
        ListNode columns = LayoutReader.columnsList(LayoutReader.ownOf(table.record));
        if (columns == null)
        {
            return Outcome.refused("The table holds no columns list where the corpus keeps one."); //$NON-NLS-1$
        }
        for (int i = 1; i < columns.size(); i++)
        {
            FormLayout.Column existing = LayoutReader.column(LayoutReader.asList(columns.get(i)));
            if (existing != null && name.equalsIgnoreCase(existing.name))
            {
                return Outcome.refused("The table already has a column named " + name + "."); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        String controlUuid = TableColumns.controlUuid(control);
        if (controlUuid == null)
        {
            return Outcome.refused("Unknown column control " + control + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        ListNode record;
        try
        {
            record = TableColumns.template(kind);
            ListNode blob = TableColumns.controlBlob(control, pattern);
            ListNode typed = pattern != null ? copy(pattern) : BraceTree.parse("{\"Pattern\"}"); //$NON-NLS-1$
            String text = caption == null ? name : caption;
            TableColumns.substitute(record, "@CAPTION", LayoutReader.localizedNode(language, text)); //$NON-NLS-1$
            TableColumns.substitute(record, "@WIDTH", new Token(Integer.toString(width == null ? defaultColumnWidth(typed) : width.intValue()))); //$NON-NLS-1$
            TableColumns.substitute(record, "@INDEX", new Token(Integer.toString(columns.size() - 1))); //$NON-NLS-1$
            TableColumns.substitute(record, "@NAME", Token.string(name)); //$NON-NLS-1$
            TableColumns.substitute(record, "@PATTERN", typed); //$NON-NLS-1$
            TableColumns.substitute(record, "@CONTROL", new Token(controlUuid)); //$NON-NLS-1$
            TableColumns.substitute(record, "@COLUMN", Token.string(valueTableColumn == null ? name : valueTableColumn)); //$NON-NLS-1$
            for (int i = 0; i < 3; i++)
            {
                FormLayout.Segment segment = i < path.size() ? path.get(i) : null;
                Node value = segment == null ? list(new Token("-1")) //$NON-NLS-1$
                    : segment.kind == FormLayout.Segment.MEMBER ? list(new Token("0"), new Token(segment.value)) //$NON-NLS-1$
                    : list(new Token(segment.value));
                TableColumns.substitute(record, "@SEG" + (i + 1), value); //$NON-NLS-1$
            }
            ListNode body = LayoutReader.asList(LayoutReader.asList(LayoutReader.asList(record.get(1)).get(1)).get(1));
            if (blob != null)
            {
                TableColumns.substitute(record, "@BLOB", blob); //$NON-NLS-1$
            }
            else
            {
                // a column that only shows carries no blob: the body ends at the control uuid
                body.items().remove(body.size() - 1);
            }
        }
        catch (IOException e)
        {
            return Outcome.refused("The column template could not be read: " + e.getMessage()); //$NON-NLS-1$
        }
        columns.items().add(record);
        columns.items().set(0, new Token(Integer.toString(columns.size() - 1)));
        return Outcome.done("column " + name + " added to " + tableName + " as column " + (columns.size() - 2)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Removes a column of a table.
     *
     * @param tableName the table
     * @param columnName the column
     * @return the outcome
     */
    public Outcome removeColumn(String tableName, String columnName)
    {
        Located table = locate(tableName);
        if (table == null)
        {
            return noElement(tableName);
        }
        ListNode columns = "Table".equals(table.type()) ? LayoutReader.columnsList(LayoutReader.ownOf(table.record)) : null; //$NON-NLS-1$
        if (columns == null)
        {
            return Outcome.refused(tableName + " is not a Table with a columns list."); //$NON-NLS-1$
        }
        for (int i = 1; i < columns.size(); i++)
        {
            FormLayout.Column existing = LayoutReader.column(LayoutReader.asList(columns.get(i)));
            if (existing != null && columnName != null && columnName.equalsIgnoreCase(existing.name))
            {
                columns.items().remove(i);
                columns.items().set(0, new Token(Integer.toString(columns.size() - 1)));
                return Outcome.done("column " + existing.name + " removed from " + tableName); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return Outcome.refused(tableName + " has no column named " + columnName + "."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static int defaultColumnWidth(ListNode pattern)
    {
        ListNode type = pattern == null || pattern.size() < 2 ? null : LayoutReader.asList(pattern.get(1));
        String kind = type == null || type.size() == 0 ? null : LayoutReader.tokenText(type.get(0));
        if ("\"N\"".equals(kind) || "\"D\"".equals(kind)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return 80;
        }
        if ("\"B\"".equals(kind)) //$NON-NLS-1$
        {
            return 40;
        }
        Integer length = type != null && type.size() > 1 ? LayoutReader.intOf(type.get(1)) : null;
        return length != null && length.intValue() > 0 ? Math.max(40, Math.min(300, length.intValue() * 7)) : 120;
    }

    /**
     * Moves an element to another panel or to the form, or onto another page of the same parent.
     * The box is left as it is. The page the element leaves and the page it lands on both have
     * their bounds recomputed, the former by the ordinal it had, after the element has left it.
     * Anchors between the element and the neighbours the move separates are released both ways
     * through {@link Anchors#dropAnchorsNaming}, the same clearing {@link #removeElement} uses:
     * on another page of the same parent, the neighbours of the page it left; under another
     * parent, every former neighbour in the holder it left. An anchor to the form and an anchor
     * of the element to its own size stay. The same parent and the same page change nothing.
     *
     * @param elementName the element
     * @param parent {@code form} for the root, or the name of a panel
     * @param page the page of that parent
     * @param released filled with the anchors that went, as {@code who.edge -> target.edge};
     *        ignored when {@code null}
     * @return the outcome; the message is {@code unchanged} when the parent and the page are the
     *         ones the element already has
     */
    public Outcome moveElement(String elementName, String parent, String page, List<String> released)
    {
        List<String> gone = released == null ? new ArrayList<>() : released;
        Located located = locate(elementName);
        if (located == null)
        {
            return noElement(elementName);
        }
        if (parent == null || parent.trim().isEmpty())
        {
            return Outcome.refused("move_element needs parent: a panel, or form."); //$NON-NLS-1$
        }
        if (page == null || page.trim().isEmpty())
        {
            return Outcome.refused("move_element needs page."); //$NON-NLS-1$
        }
        String parentName = parent.trim();
        String pageName = page.trim();
        ListNode newParentRecord;
        ListNode newHolder;
        List<FormLayout.Page> pages;
        String parentWord;
        if ("form".equalsIgnoreCase(parentName)) //$NON-NLS-1$
        {
            newParentRecord = null;
            newHolder = rootChildren();
            pages = layout().pages;
            parentWord = "the form"; //$NON-NLS-1$
        }
        else
        {
            Located panel = locate(parentName);
            if (panel == null)
            {
                return Outcome.refused("No parent named " + parentName + " on the form."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (!"Panel".equals(panel.type())) //$NON-NLS-1$
            {
                return Outcome.refused(parentName + " is a " + panel.type() + ", not a panel and not the form."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (panel.record == located.record)
            {
                return Outcome.refused("An element cannot be moved into itself: " + located.name() + "."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (encloses(located.record, panel.record))
            {
                return Outcome.refused("An element cannot be moved into its descendant: " + panel.name() + "."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            newParentRecord = panel.record;
            newHolder = LayoutReader.childrenOf(panel.record);
            FormLayout.Element element = layout().elementNamed(panel.name());
            pages = element == null ? new ArrayList<>() : element.pages;
            parentWord = panel.name();
        }
        if (newHolder == null)
        {
            return Outcome.refused("The holder of the elements was not found in the layout."); //$NON-NLS-1$
        }
        int pageIndex = -1;
        String canonicalPage = pageName;
        for (int i = 0; i < pages.size(); i++)
        {
            if (pageName.equalsIgnoreCase(pages.get(i).name))
            {
                pageIndex = i;
                canonicalPage = pages.get(i).name;
            }
        }
        if (pageIndex < 0)
        {
            List<String> names = new ArrayList<>();
            for (FormLayout.Page one : pages)
            {
                names.add(one.name);
            }
            return Outcome.refused("No page " + pageName + " there; pages: " + names + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        ListNode common = LayoutReader.commonOf(located.record);
        if (common == null || common.size() < 12 + LayoutReader.PAGE_FROM_END)
        {
            return Outcome.refused("The record of " + located.name() + " has no page where the corpus holds one."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Integer currentPage = LayoutReader.pageIndex(common);
        if (newHolder == located.holder && currentPage != null && currentPage.intValue() == pageIndex)
        {
            return Outcome.done("unchanged"); //$NON-NLS-1$
        }
        int oldPageIndex = currentPage == null ? -1 : currentPage.intValue();
        ListNode oldHolder = located.holder;
        ListNode oldParentRecord = located.parentRecord;
        boolean sameParent = newHolder == oldHolder;
        Set<String> neighborIds = new HashSet<>();
        for (Located other : allElements())
        {
            if (other.record == located.record || other.holder != oldHolder)
            {
                continue;
            }
            if (sameParent)
            {
                Integer otherPage = LayoutReader.pageIndex(LayoutReader.commonOf(other.record));
                if (otherPage == null || otherPage.intValue() != oldPageIndex)
                {
                    continue;
                }
            }
            neighborIds.add(other.id());
        }
        common.items().set(common.size() - LayoutReader.PAGE_FROM_END, new Token(Integer.toString(pageIndex)));
        if (!sameParent)
        {
            oldHolder.items().remove(located.record);
            oldHolder.items().set(0, new Token(Integer.toString(oldHolder.size() - 1)));
            newHolder.items().add(located.record);
            newHolder.items().set(0, new Token(Integer.toString(newHolder.size() - 1)));
        }
        String id = located.id();
        Set<String> mine = new HashSet<>();
        mine.add(id);
        for (Located other : allElements())
        {
            if (id.equals(other.id()))
            {
                gone.addAll(Anchors.dropAnchorsNaming(root, LayoutReader.commonOf(other.record), other.name(),
                    neighborIds));
            }
            else if (neighborIds.contains(other.id()))
            {
                gone.addAll(Anchors.dropAnchorsNaming(root, LayoutReader.commonOf(other.record), other.name(), mine));
            }
        }
        if (oldPageIndex >= 0)
        {
            refreshPageBounds(oldParentRecord, oldHolder, oldPageIndex);
        }
        refreshPageBounds(newParentRecord, newHolder, pageIndex);
        String message = located.type() + " " + located.name() + " moved to " + parentWord + ", page " + canonicalPage; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (!gone.isEmpty())
        {
            message += "; released " + String.join(", ", gone); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return Outcome.done(message);
    }

    /** Whether {@code record} holds {@code target} anywhere under its children. */
    private static boolean encloses(ListNode record, ListNode target)
    {
        ListNode children = LayoutReader.childrenOf(record);
        return children != null && enclosesList(children, target);
    }

    private static boolean enclosesList(ListNode holder, ListNode target)
    {
        for (Node item : holder.items())
        {
            ListNode list = LayoutReader.asList(item);
            if (list == null)
            {
                continue;
            }
            if (list == target)
            {
                return true;
            }
            if (LayoutReader.isElementRecord(list))
            {
                if (encloses(list, target))
                {
                    return true;
                }
            }
            else if (enclosesList(list, target))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes an element with its children, its data bindings, and every anchor that named it.
     *
     * @param elementName the element
     * @return the outcome, naming the procedures the removed handlers were bound to
     */
    public Outcome removeElement(String elementName)
    {
        Located located = locate(elementName);
        if (located == null)
        {
            return noElement(elementName);
        }
        Set<String> ids = new HashSet<>();
        List<String> procedures = new ArrayList<>();
        collectIds(located.record, ids, procedures);
        Integer wasOn = LayoutReader.pageIndex(LayoutReader.commonOf(located.record));
        located.holder.items().remove(located.record);
        located.holder.items().set(0, new Token(Integer.toString(located.holder.size() - 1)));
        if (wasOn != null)
        {
            refreshPageBounds(located.parentRecord, located.holder, wasOn.intValue());
        }
        ListNode registry = registry();
        if (registry != null)
        {
            for (int i = registry.size() - 1; i >= 1; i--)
            {
                ListNode pair = LayoutReader.asList(registry.get(i));
                if (pair != null && pair.size() == 2 && ids.contains(LayoutReader.tokenText(pair.get(0))))
                {
                    registry.items().remove(i);
                }
            }
            registry.items().set(0, new Token(Integer.toString(registry.size() - 1)));
        }
        for (Located other : allElements())
        {
            Anchors.dropAnchorsNaming(root, LayoutReader.commonOf(other.record), other.name(), ids);
        }
        Anchors.dropFormBackReferences(root, ids);
        String message = located.type() + " " + located.name() + " removed"; //$NON-NLS-1$ //$NON-NLS-2$
        if (ids.size() > 1)
        {
            message += " with " + (ids.size() - 1) + " nested element(s)"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!procedures.isEmpty())
        {
            message += "; handlers were bound to " + String.join(", ", procedures) + ", which the module still declares"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return Outcome.done(message);
    }

    private static void collectIds(ListNode record, Set<String> ids, List<String> procedures)
    {
        ids.add(LayoutReader.tokenText(record.get(1)));
        collectProcedures(LayoutReader.ownOf(record), procedures);
        ListNode children = LayoutReader.childrenOf(record);
        if (children != null)
        {
            for (Node item : children.items())
            {
                ListNode child = LayoutReader.asList(item);
                if (child != null && LayoutReader.isElementRecord(child))
                {
                    collectIds(child, ids, procedures);
                }
            }
        }
    }

    private static void collectProcedures(ListNode node, List<String> out)
    {
        if (node == null)
        {
            return;
        }
        if (LayoutReader.isHandlerEntry(node))
        {
            String procedure = LayoutReader.handlerProcedure(node.get(2));
            if (!out.contains(procedure))
            {
                out.add(procedure);
            }
            return;
        }
        for (Node item : node.items())
        {
            ListNode child = LayoutReader.asList(item);
            if (child != null)
            {
                collectProcedures(child, out);
            }
        }
    }

    /**
     * A spreadsheet field shows the document the record carries. The corpus binds no form
     * attribute to one (0 of 142), so any data is outside the measured type.
     *
     * @param data the pattern that was given
     * @return the refusal, naming the document and the type
     */
    private static Outcome refusedTableFieldData(ListNode data)
    {
        return refusedData("A spreadsheet field shows the spreadsheet document it carries", data); //$NON-NLS-1$
    }

    /**
     * @param data the pattern that was given
     * @return whether it names the value list alone - the type the corpus binds to every one of
     *         its 17 list fields
     */
    static boolean isValueList(ListNode data)
    {
        TypePattern pattern = TypePattern.of(data);
        return pattern != null && pattern.describe(LayoutNames.NONE).equals("ValueList"); //$NON-NLS-1$
    }

    /**
     * The refusal for data of a type the corpus does not bind to the kind, naming what the kind
     * shows and the type that was given.
     *
     * @param what the kind shows, as a sentence without the full stop
     * @param data the pattern that was given
     * @return the refusal
     */
    private static Outcome refusedData(String what, ListNode data)
    {
        TypePattern pattern = TypePattern.of(data);
        String type = pattern == null ? BraceTree.serialize(data) : pattern.describe(LayoutNames.NONE);
        return Outcome.refused(what + ". Data of type " + type + " was not measured for it."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- templates ----------------------------------------------------------------------------

    private static ListNode template(String type) throws IOException
    {
        try (InputStream in = LayoutEditor.class.getResourceAsStream("templates/" + type + ".form")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            if (in == null)
            {
                throw new IOException("no template " + type); //$NON-NLS-1$
            }
            return BraceTree.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * @param kind the canonical kind
     * @return whether the measured record of that kind holds a tooltip
     */
    private static boolean carriesATooltip(String kind)
    {
        try (InputStream in = LayoutEditor.class.getResourceAsStream("templates/" + kind + ".form")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return in != null && new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("@TOOLTIP"); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            return false;
        }
    }

    private void fill(ListNode record, String id, String name, int[] box, int pageIndex, String caption, String tooltip,
        ListNode data)
    {
        record.items().set(1, new Token(id));
        ListNode identity = LayoutReader.identityOf(record);
        identity.items().set(1, Token.string(name));
        ListNode common = LayoutReader.commonOf(record);
        for (int i = 0; i < 4; i++)
        {
            common.items().set(i + 1, new Token(Integer.toString(box[i])));
        }
        for (int i = ANCHOR_TOP; i < ANCHORS_END; i++)
        {
            common.items().set(i, list(new Token("0"), BraceTree.parse(NO_ANCHOR), BraceTree.parse(NO_ANCHOR))); //$NON-NLS-1$
        }
        selfAnchors(common, id, box, true);
        substitute(record, "@ANCH", () -> new Token("0")); //$NON-NLS-1$ //$NON-NLS-2$
        substitute(record, "@PAGE", () -> new Token(Integer.toString(pageIndex))); //$NON-NLS-1$
        substitute(record, "@LOC0", LayoutEditor::emptyLocalized); //$NON-NLS-1$
        substitute(record, "@TOOLTIP", () -> tooltip == null || tooltip.isEmpty() ? emptyLocalized() //$NON-NLS-1$
            : LayoutReader.localizedNode(language, tooltip));
        substitute(record, "@CAPTION", () -> caption == null ? emptyLocalized() : LayoutReader.localizedNode(language, caption)); //$NON-NLS-1$
        substitute(record, "@EVENTS", () -> list(new Token("0"))); //$NON-NLS-1$ //$NON-NLS-2$
        substitute(record, "@DATA", () -> data != null ? copy(data) : list(new Token("\"Pattern\""))); //$NON-NLS-1$ //$NON-NLS-2$
        substitute(record, "@OFF", () -> new Token("0")); //$NON-NLS-1$ //$NON-NLS-2$
        substitute(record, "@REF", () -> new Token("-1")); //$NON-NLS-1$ //$NON-NLS-2$
        substitute(record, "@PAGES", () -> onePage(BORN_PAGE_NAME)); //$NON-NLS-1$
        substitute(record, "@CHILDREN", () -> list(new Token("0"))); //$NON-NLS-1$ //$NON-NLS-2$
        substitute(record, "@ID", () -> new Token(id)); //$NON-NLS-1$
    }

    /**
     * The pages list of a new panel: one page, named and titled alike, as the version-3 record
     * that lies beside version-21 panel properties in 1941 of the 1959 pages measured.
     */
    private ListNode onePage(String name)
    {
        ListNode fonts = list(new Token("3"), new Token("0"), BraceTree.parse("{3,0,{0},\"\",-1,-1,1,0}")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        ListNode page = list(new Token("3"), LayoutReader.localizedNode(language, name), fonts, new Token("-1"), //$NON-NLS-1$ //$NON-NLS-2$
            new Token("1"), new Token("1"), Token.string(name), new Token("1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return list(new Token("1"), new Token("1"), page); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The bottom follows the top by the height, the right follows the left by the width: the
     * anchor slots that name the element itself are set to the size of the box. A slot that
     * names another element is left alone unless forced, and named in the answer.
     *
     * @return the edges whose anchors were left naming another element
     */
    private static List<String> selfAnchors(ListNode common, String id, int[] box, boolean force)
    {
        List<String> kept = new ArrayList<>();
        if (common.size() < ANCHORS_END)
        {
            return kept;
        }
        ListNode bottom = LayoutReader.asList(common.get(ANCHOR_BOTTOM));
        ListNode right = LayoutReader.asList(common.get(ANCHOR_RIGHT));
        if (bottom != null && bottom.size() >= 2)
        {
            if (force || anchorsSelf(bottom.get(1), id))
            {
                bottom.items().set(1, list(new Token("2"), new Token(id), new Token("0"), new Token(Integer.toString(box[3] - box[1])))); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else if (anchorsOther(bottom.get(1)))
            {
                kept.add("bottom"); //$NON-NLS-1$
            }
        }
        if (right != null && right.size() >= 2)
        {
            if (force || anchorsSelf(right.get(1), id))
            {
                right.items().set(1, list(new Token("2"), new Token(id), new Token("2"), new Token(Integer.toString(box[2] - box[0])))); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else if (anchorsOther(right.get(1)))
            {
                kept.add("right"); //$NON-NLS-1$
            }
        }
        return kept;
    }

    private static boolean anchorsSelf(Node anchor, String id)
    {
        ListNode list = LayoutReader.asList(anchor);
        return list != null && list.size() == 4 && id.equals(LayoutReader.tokenText(list.get(1)));
    }

    private static boolean anchorsOther(Node anchor)
    {
        ListNode list = LayoutReader.asList(anchor);
        return list != null && list.size() == 4 && !"-1".equals(LayoutReader.tokenText(list.get(1))); //$NON-NLS-1$
    }

    private static void substitute(ListNode node, String placeholder, java.util.function.Supplier<Node> value)
    {
        for (int i = 0; i < node.size(); i++)
        {
            Node item = node.get(i);
            if (item instanceof ListNode)
            {
                substitute((ListNode)item, placeholder, value);
            }
            else if (placeholder.equals(LayoutReader.tokenText(item)))
            {
                node.items().set(i, value.get());
            }
        }
    }

    // ---- lookups ------------------------------------------------------------------------------

    private ListNode body()
    {
        return root.size() > 1 ? LayoutReader.asList(root.get(1)) : null;
    }

    private ListNode rootPanel()
    {
        ListNode body = body();
        ListNode panel = body == null || body.size() < 3 ? null : LayoutReader.asList(body.get(2));
        return panel != null && panel.size() >= 3 && LayoutReader.PANEL_TYPE.equalsIgnoreCase(LayoutReader.tokenText(panel.get(0)))
            ? panel : null;
    }

    private ListNode rootChildren()
    {
        ListNode panel = rootPanel();
        return panel == null ? null : LayoutReader.asList(panel.get(2));
    }

    private ListNode registry()
    {
        for (int i = 2; i < root.size(); i++)
        {
            ListNode item = LayoutReader.asList(root.get(i));
            if (item != null && LayoutReader.isAttributeTable(item))
            {
                return LayoutReader.asList(item.get(3));
            }
        }
        return null;
    }

    /** The pages list {1, n, page...} inside a panel's own list. */
    private static ListNode pagesList(ListNode node)
    {
        if (node == null)
        {
            return null;
        }
        if (node.size() >= 2 && "1".equals(LayoutReader.tokenText(node.get(0))) && LayoutReader.intOf(node.get(1)) != null //$NON-NLS-1$
            && LayoutReader.intOf(node.get(1)).intValue() == node.size() - 2 && node.size() > 2
            && LayoutReader.asList(node.get(2)) != null && LayoutReader.isPageRecord(LayoutReader.asList(node.get(2))))
        {
            return node;
        }
        for (Node item : node.items())
        {
            ListNode child = LayoutReader.asList(item);
            if (child != null && !LayoutReader.isElementRecord(child))
            {
                ListNode found = pagesList(child);
                if (found != null)
                {
                    return found;
                }
            }
        }
        return null;
    }

    private List<Located> allElements()
    {
        List<Located> out = new ArrayList<>();
        ListNode children = rootChildren();
        if (children != null)
        {
            collect(children, null, out);
        }
        return out;
    }

    private static void collect(ListNode holder, ListNode parentRecord, List<Located> out)
    {
        for (Node item : holder.items())
        {
            ListNode record = LayoutReader.asList(item);
            if (record == null)
            {
                continue;
            }
            if (LayoutReader.isElementRecord(record))
            {
                out.add(new Located(record, holder, parentRecord));
                ListNode nested = LayoutReader.childrenOf(record);
                if (nested != null)
                {
                    collect(nested, record, out);
                }
            }
            else
            {
                collect(record, parentRecord, out);
            }
        }
    }

    private Located locate(String name)
    {
        if (name == null)
        {
            return null;
        }
        for (Located located : allElements())
        {
            if (name.equalsIgnoreCase(located.name()))
            {
                return located;
            }
        }
        return null;
    }

    /** The highest element id in the layout, records of a type the reader does not know included. */
    private int maxElementId()
    {
        ListNode children = rootChildren();
        return children == null ? 0 : maxIdBelow(children, 0);
    }

    private static int maxIdBelow(ListNode node, int max)
    {
        for (Node item : node.items())
        {
            ListNode list = LayoutReader.asList(item);
            if (list == null)
            {
                continue;
            }
            if (list.size() >= 6 && LayoutReader.asList(list.get(0)) == null && LayoutReader.tokenText(list.get(0)) != null
                && LayoutReader.tokenText(list.get(0)).length() == 36 && LayoutReader.intOf(list.get(1)) != null)
            {
                max = Math.max(max, LayoutReader.intOf(list.get(1)).intValue());
            }
            max = maxIdBelow(list, max);
        }
        return max;
    }

    private static Outcome noElement(String name)
    {
        return Outcome.refused("No element named " + name + " on the form."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String canonicalType(String type)
    {
        return ElementKinds.canonical(type);
    }

    // ---- node helpers -------------------------------------------------------------------------

    private static void replace(ListNode within, Node old, Node fresh)
    {
        for (int i = 0; i < within.size(); i++)
        {
            Node item = within.get(i);
            if (item == old)
            {
                within.items().set(i, fresh);
                return;
            }
            if (item instanceof ListNode)
            {
                replace((ListNode)item, old, fresh);
            }
        }
    }

    private static ListNode emptyLocalized()
    {
        return list(new Token("1"), new Token("0")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static ListNode font()
    {
        return BraceTree.parse("{4,0,{0},\"\",-1,-1,1,0,\"\"}"); //$NON-NLS-1$
    }

    static ListNode list(Node... items)
    {
        ListNode node = new ListNode();
        for (Node item : items)
        {
            node.items().add(item);
        }
        return node;
    }

    static ListNode copy(ListNode node)
    {
        return BraceTree.parse(BraceTree.serialize(node));
    }

    /** @return the tree serialized as the platform writes it */
    public String text()
    {
        return BraceTree.serialize(root);
    }

    /**
     * A refusal for a caller outside this package.
     *
     * @param message why
     * @return the outcome
     */
    public static Outcome refusal(String message)
    {
        return Outcome.refused(message);
    }

    /**
     * A deep copy of a node.
     *
     * @param node the node
     * @return the copy
     */
    public static ListNode copyOf(ListNode node)
    {
        return copy(node);
    }

    /** @return the lower-case names of the kinds an operation creates */
    public static Set<String> templatedTypes()
    {
        return ElementKinds.writableNamesLowerCase();
    }
}
