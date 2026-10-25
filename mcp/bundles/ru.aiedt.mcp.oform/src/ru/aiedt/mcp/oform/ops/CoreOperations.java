/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.ops;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.ConfigurationNames;
import ru.aiedt.mcp.oform.layout.CommandPanels;
import ru.aiedt.mcp.oform.layout.ElementKinds;
import ru.aiedt.mcp.oform.layout.FormEvents;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.HandlerSignatures;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.LayoutNames;
import ru.aiedt.mcp.oform.layout.TableColumns;
import ru.aiedt.mcp.oform.layout.TypePattern;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * The operations every form has: reading ({@code audit}, {@code layout}, {@code diff_form}, {@code help}), the
 * properties of the form and of an element, handlers, elements, pages.
 * <p>
 * The properties {@code set_property} knows are registered in {@link PropertyHandlers} when this
 * catalog loads; another theme registers its own the same way, in its own catalog.
 * </p>
 */
public final class CoreOperations
{
    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*"); //$NON-NLS-1$

    private CoreOperations()
    {
        // static catalog
    }

    /**
     * @return the specs of this catalog; registers the core properties as a side effect
     */
    public static List<OperationSpec> specs()
    {
        registerProperties();
        List<OperationSpec> out = new ArrayList<>();
        out.add(OperationSpec.query("audit", //$NON-NLS-1$
            "every ordinary form of the project: layout version, container entries, module size, " //$NON-NLS-1$
                + "byte-for-byte round trip of the layout text; details=true lists forms, limit caps the lists", //$NON-NLS-1$
            List.of(ParamSpec.integer("limit", "how many mismatching or unreadable forms to name (default 20)."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.bool("details", "list every form with its version and sizes, capped by limit (default false).")), //$NON-NLS-1$ //$NON-NLS-2$
            FormQueries::audit));
        out.add(OperationSpec.query("layout", //$NON-NLS-1$
            "one form (formFqn or modulePath): title, attributes with types, pages, form handlers with " //$NON-NLS-1$
                + "events and the element tree - type, name, caption, tooltip, box, page, data path, handlers, " //$NON-NLS-1$
                + "buttons of a command panel", //$NON-NLS-1$
            List.of(), FormQueries::layout));
        out.add(OperationSpec.query("diff_form", //$NON-NLS-1$
            "one form (formFqn or modulePath) against HEAD: whether the layout entry and the module " //$NON-NLS-1$
                + "entry changed, and the names of the other entries added, removed or changed; a changed " //$NON-NLS-1$
                + "module is left to diff_module at the module's address; when there is nothing to compare " //$NON-NLS-1$
                + "with, revision names the outcome", //$NON-NLS-1$
            List.of(), FormQueries::diffForm));
        out.add(OperationSpec.query("inspect_layout", //$NON-NLS-1$
            "one form (formFqn or modulePath), read-only: findings over the layout the reader yields - " //$NON-NLS-1$
                + "duplicate ids and names in their namespaces, references to pages, elements, attributes, " //$NON-NLS-1$
                + "action sources and anchor targets the layout does not hold, handlers the module does not " //$NON-NLS-1$
                + "declare, invalid boxes, anchor cycles - and warnings for siblings of one page that overlap " //$NON-NLS-1$
                + "(a Group or Panel frame never a party) and a child past its parent's box; the coverage names " //$NON-NLS-1$
                + "what was and was not checked, and nothing is corrected", //$NON-NLS-1$
            List.of(), FormQueries::inspectLayout));
        out.add(OperationSpec.layout("set_property", //$NON-NLS-1$
            "property=title (of the form, or of an element with elementName) | caption | tooltip " //$NON-NLS-1$
                + "(value) | box (left, top, right, bottom or width, height) of elementName | size (width, height) of the form " //$NON-NLS-1$
                + "| visible, enabled, readOnly, tabs (value=true|false) | font (value=auto, or fontFamily with fontSize, " //$NON-NLS-1$
                + "bold, italic, underline) | textColor, backColor, borderColor (value=auto | #RRGGBB | a style " //$NON-NLS-1$
                + "element index) | alignment (value=left|center|right|auto) | format (value, empty clears) | editFormat (refused) " //$NON-NLS-1$
                + "| anchor (edge=left|top|right|bottom, value=form|none|<element>.<edge>, offset) of elementName", //$NON-NLS-1$
            List.of(ParamSpec.string("elementName", "the element; absent for the form itself."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("property", () -> "one of " + PropertyHandlers.describe() + "."), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                ParamSpec.string("value", "the text for title, caption or tooltip; true|false for visible, enabled, " //$NON-NLS-1$
                    + "readOnly; auto, #RRGGBB or a style element index for the colours; left|center|right|auto for " //$NON-NLS-1$
                    + "alignment; auto for the font; form, none or <element>.<edge> for anchor."), //$NON-NLS-1$
                ParamSpec.integer("left", "box: the left edge."), ParamSpec.integer("top", "box: the top edge."), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                ParamSpec.integer("right", "box: the right edge."), ParamSpec.integer("bottom", "box: the bottom edge."), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                ParamSpec.integer("width", "box: the width, instead of right; size: the form's width."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.integer("height", "box: the height, instead of bottom; size: the form's height."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("fontFamily", "font: the family, with fontSize."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.integer("fontSize", "font: the size, with fontFamily."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.bool("bold", "font: bold (with fontFamily and fontSize)."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.bool("italic", "font: italic (with fontFamily and fontSize)."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.bool("underline", "font: underline (with fontFamily and fontSize)."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("edge", "anchor: the element's own edge - left, top, right or bottom."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.integer("offset", "anchor: the offset from the target's edge (default 0).")), //$NON-NLS-1$ //$NON-NLS-2$
            CoreOperations::setProperty));
        out.add(OperationSpec.layout("bind_handler", //$NON-NLS-1$
            "event + procedure (default <element><event>) of elementName, or of the form without " //$NON-NLS-1$
                + "elementName; stub=true adds an empty procedure with the measured parameters to the module", //$NON-NLS-1$
            List.of(ParamSpec.string("elementName", "the element whose event to bind; absent for the form."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("event", "the event (ПриИзменении, Нажатие, ПриОткрытии, ...; help lists them)."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("procedure", "the procedure to bind (default <element><event>, or the event for the form)."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.bool("stub", "add an empty procedure with the measured parameters to the module when it declares none (default true).")), //$NON-NLS-1$ //$NON-NLS-2$
            CoreOperations::bindHandler));
        out.add(OperationSpec.layout("unbind_handler", "event of elementName or of the form", //$NON-NLS-1$ //$NON-NLS-2$
            List.of(ParamSpec.string("elementName", "the element whose event to unbind; absent for the form."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("event", "the event to unbind.")), //$NON-NLS-1$ //$NON-NLS-2$
            CoreOperations::unbindHandler));
        out.add(OperationSpec.layout("add_element", //$NON-NLS-1$
            "type, name, parent (a Panel) and page, left/top/right/bottom or width/height, caption, " //$NON-NLS-1$
                + "tooltip, data (a path in words: <form attribute>.<member>); the record is the measured " //$NON-NLS-1$
                + "template of the type with the element's own values put in", //$NON-NLS-1$
            List.of(                ParamSpec.string("type", () -> String.join(" | ", ElementKinds.WRITABLE.stream().sorted().toList()) //$NON-NLS-1$ //$NON-NLS-2$
                    + "; a Table shows the rows data names, and so does a ListField; a TableField, a FieldHtml, " //$NON-NLS-1$
                    + "a TextDocumentField and a Chart take no data."), //$NON-NLS-1$
                ParamSpec.string("name", "the name of the new element."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("parent", "the Panel to put the element in; absent for the form."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("page", "the page of the panel or form; absent for the first."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.integer("left", "the left edge."), ParamSpec.integer("top", "the top edge."), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                ParamSpec.integer("right", "the right edge."), ParamSpec.integer("bottom", "the bottom edge."), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                ParamSpec.integer("width", "the width, instead of right."), ParamSpec.integer("height", "the height, instead of bottom."), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                ParamSpec.string("caption", "the caption of a Label, Button, CheckBox, RadioBtn or Group."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("tooltip", "the tooltip."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("data", "the data path in words, <form attribute>[.<member>...] (СправочникОбъект.Артикул). " //$NON-NLS-1$ //$NON-NLS-2$
                    + "A TableField, a FieldHtml, a TextDocumentField and a Chart show what their own record or the " //$NON-NLS-1$
                    + "code sets, so this argument is refused for them; a ListField names the value-list attribute it shows.")), //$NON-NLS-1$
            CoreOperations::addElement));
        out.add(OperationSpec.layout("add_page", "name and title of a page of the form or of parent (a Panel)", //$NON-NLS-1$ //$NON-NLS-2$
            List.of(ParamSpec.string("name", "the name of the new page."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("title", "the page's title."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("parent", "the Panel; absent for the form.")), //$NON-NLS-1$ //$NON-NLS-2$
            context -> {
                String name = context.string("name"); //$NON-NLS-1$
                if (name == null || name.isEmpty())
                {
                    return OperationResult.refused("add_page needs name."); //$NON-NLS-1$
                }
                return OperationResult.of(context.editor.addPage(context.string("parent"), name, context.string("title"))); //$NON-NLS-1$ //$NON-NLS-2$
            }));
        out.add(OperationSpec.layout("remove_page", //$NON-NLS-1$
            "name of a page of the form or of parent (a Panel); a page with elements on it or the last page is refused", //$NON-NLS-1$
            List.of(ParamSpec.string("name", "the name of the page to remove."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("parent", "the Panel; absent for the form.")), //$NON-NLS-1$ //$NON-NLS-2$
            context -> {
                String name = context.string("name"); //$NON-NLS-1$
                if (name == null || name.isEmpty())
                {
                    return OperationResult.refused("remove_page needs name."); //$NON-NLS-1$
                }
                return OperationResult.of(context.editor.removePage(context.string("parent"), name)); //$NON-NLS-1$
            }));
        out.add(OperationSpec.layout("remove_element", //$NON-NLS-1$
            "elementName, with its children, data bindings and the anchors that named it; an element " //$NON-NLS-1$
                + "a command panel takes its actions from is refused unless cascade=true, which clears " //$NON-NLS-1$
                + "the source and the auto-fill of those panels", //$NON-NLS-1$
            List.of(ParamSpec.string("elementName", "the element to remove."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.bool("cascade", "remove an element command panels take their actions from, " //$NON-NLS-1$
                    + "clearing the source and the auto-fill of those panels (default false).")), //$NON-NLS-1$
            context -> {
                String element = context.string("elementName"); //$NON-NLS-1$
                if (element == null || element.isEmpty())
                {
                    return OperationResult.refused("remove_element needs elementName."); //$NON-NLS-1$
                }
                FormLayout.Element found = context.editor.layout().elementNamed(element);
                // the removal takes the whole subtree, so a table deeper inside it is a source
                // about to disappear too; a panel that is itself inside the subtree goes with it
                List<String> sourcing = new ArrayList<>();
                if (found != null)
                {
                    List<FormLayout.Element> subtree = new ArrayList<>();
                    collectSubtree(found, subtree);
                    Set<String> namesInside = new LinkedHashSet<>();
                    for (FormLayout.Element one : subtree)
                    {
                        if (one.name != null)
                        {
                            namesInside.add(one.name.toLowerCase(Locale.ROOT));
                        }
                    }
                    for (FormLayout.Element one : subtree)
                    {
                        for (String panel : CommandPanels.panelsSourcing(context.tree, one.id))
                        {
                            if (!namesInside.contains(panel.toLowerCase(Locale.ROOT)) && !sourcing.contains(panel))
                            {
                                sourcing.add(panel);
                            }
                        }
                    }
                }
                if (!sourcing.isEmpty() && !context.bool("cascade", false)) //$NON-NLS-1$
                {
                    return OperationResult.refused(element + " is the source of actions of the panel(s) " //$NON-NLS-1$
                        + String.join(", ", sourcing) + "; remove them first or give cascade=true."); //$NON-NLS-1$ //$NON-NLS-2$
                }
                OperationResult removed = OperationResult.of(context.editor.removeElement(element));
                if (removed.outcome.done && !sourcing.isEmpty())
                {
                    for (String panel : sourcing)
                    {
                        CommandPanels.setActionSource(context.tree, panel, null, Boolean.FALSE);
                    }
                    context.answer.put("cascade", sourcing.toString()); //$NON-NLS-1$
                }
                return removed;
            }));
        out.add(OperationSpec.projectlessQuery("help", "this catalogue", List.of(), //$NON-NLS-1$ //$NON-NLS-2$
            (project, params) -> help()));
        return out;
    }

    /** The element and every element under it, in the order the tree holds them. */
    private static void collectSubtree(FormLayout.Element element, List<FormLayout.Element> out)
    {
        out.add(element);
        for (FormLayout.Element child : element.children)
        {
            collectSubtree(child, out);
        }
    }

    private static boolean propertiesRegistered;

    /**
     * Registers title, caption, tooltip, box and size. Idempotent: the catalog may be built more
     * than once in a test runtime.
     */
    static synchronized void registerProperties()
    {
        if (propertiesRegistered)
        {
            return;
        }
        propertiesRegistered = true;
        PropertyHandlers.register("title", "the form's title, or an element's caption with elementName", (context, element) -> { //$NON-NLS-1$ //$NON-NLS-2$
            String value = context.string("value"); //$NON-NLS-1$
            if (value == null)
            {
                return LayoutEditor.refusal("set_property title needs value."); //$NON-NLS-1$
            }
            return element != null && !element.isEmpty() ? context.editor.setCaption(element, value) : context.editor.setTitle(value);
        });
        PropertyHandlers.register("caption", "an element's caption (value)", (context, element) -> { //$NON-NLS-1$ //$NON-NLS-2$
            String value = context.string("value"); //$NON-NLS-1$
            return element == null || value == null ? LayoutEditor.refusal("set_property caption needs elementName and value.") //$NON-NLS-1$
                : context.editor.setCaption(element, value);
        });
        PropertyHandlers.register("tooltip", "an element's tooltip (value; empty clears)", (context, element) -> { //$NON-NLS-1$ //$NON-NLS-2$
            String value = context.string("value"); //$NON-NLS-1$
            return element == null ? LayoutEditor.refusal("set_property tooltip needs elementName.") //$NON-NLS-1$
                : context.editor.setTooltip(element, value == null ? "" : value); //$NON-NLS-1$
        });
        PropertyHandlers.register("size", "the form's width and/or height", (context, element) -> { //$NON-NLS-1$ //$NON-NLS-2$
            if (element != null && !element.isEmpty())
            {
                return LayoutEditor.refusal("size is a property of the form; for an element set box."); //$NON-NLS-1$
            }
            Integer width = context.integer("width"); //$NON-NLS-1$
            Integer height = context.integer("height"); //$NON-NLS-1$
            return width == null && height == null ? LayoutEditor.refusal("set_property size needs width and/or height.") //$NON-NLS-1$
                : context.editor.setSize(width, height);
        });
        PropertyHandlers.register("box", "an element's left, top, right, bottom, or width and height", (context, element) -> { //$NON-NLS-1$ //$NON-NLS-2$
            if (element == null)
            {
                return LayoutEditor.refusal("set_property box needs elementName and any of left, top, right, bottom, width, height."); //$NON-NLS-1$
            }
            Integer left = context.integer("left"); //$NON-NLS-1$
            Integer top = context.integer("top"); //$NON-NLS-1$
            Integer right = context.integer("right"); //$NON-NLS-1$
            Integer bottom = context.integer("bottom"); //$NON-NLS-1$
            Integer width = context.integer("width"); //$NON-NLS-1$
            Integer height = context.integer("height"); //$NON-NLS-1$
            FormLayout.Element current = context.editor.layout().elementNamed(element);
            if (current != null && current.box != null)
            {
                int l = left != null ? left.intValue() : current.box[0];
                int t = top != null ? top.intValue() : current.box[1];
                if (width != null)
                {
                    right = Integer.valueOf(l + width.intValue());
                }
                if (height != null)
                {
                    bottom = Integer.valueOf(t + height.intValue());
                }
            }
            if (left == null && top == null && right == null && bottom == null)
            {
                return LayoutEditor.refusal("set_property box needs any of left, top, right, bottom, width, height."); //$NON-NLS-1$
            }
            LayoutEditor.Outcome moved = context.editor.setBox(element, left, top, right, bottom);
            if (moved != null && moved.done)
            {
                context.editor.refreshPageBoundsAround(element);
            }
            return moved;
        });
    }

    private static OperationResult setProperty(LayoutContext context)
    {
        String property = context.string("property"); //$NON-NLS-1$
        String element = context.string("elementName"); //$NON-NLS-1$
        PropertyHandlers.PropertyHandler handler = PropertyHandlers.find(property);
        if (handler == null)
        {
            return OperationResult.refused("Unknown property " + property + "; known: " //$NON-NLS-1$ //$NON-NLS-2$
                + String.join(", ", PropertyHandlers.names()) + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return OperationResult.of(handler.apply(context, element));
    }

    private static OperationResult unbindHandler(LayoutContext context)
    {
        String element = context.string("elementName"); //$NON-NLS-1$
        String event = context.string("event"); //$NON-NLS-1$
        if (event == null || event.isEmpty())
        {
            return OperationResult.refused("unbind_handler needs event."); //$NON-NLS-1$
        }
        return OperationResult.of(element == null || element.isEmpty() ? context.editor.unbindFormHandler(event)
            : context.editor.unbindHandler(element, event));
    }

    private static OperationResult bindHandler(LayoutContext context)
    {
        String element = context.string("elementName"); //$NON-NLS-1$
        String event = context.string("event"); //$NON-NLS-1$
        String procedure = context.string("procedure"); //$NON-NLS-1$
        boolean stub = context.bool("stub", true); //$NON-NLS-1$
        if (event == null || event.isEmpty())
        {
            return OperationResult.refused("bind_handler needs event, and elementName for an element's event."); //$NON-NLS-1$
        }
        boolean ofForm = element == null || element.isEmpty();
        if (procedure == null || procedure.isEmpty())
        {
            procedure = ofForm ? event : element + event;
        }
        if (!IDENTIFIER.matcher(procedure).matches())
        {
            return OperationResult.refused("The procedure name must be an identifier: " + procedure); //$NON-NLS-1$
        }
        FormLayout layout = context.editor.layout();
        String parameters;
        LayoutEditor.Outcome outcome;
        if (ofForm)
        {
            outcome = context.editor.bindFormHandler(event, procedure);
            parameters = HandlerSignatures.formParameters(event, layout.settingsHolder, "Document".equalsIgnoreCase(context.ownerKind)); //$NON-NLS-1$
        }
        else
        {
            outcome = context.editor.bindHandler(element, event, procedure);
            FormLayout.Element found = layout.elementNamed(element);
            parameters = found == null ? null : HandlerSignatures.elementParameters(found.type, event);
        }
        if (!outcome.done)
        {
            return OperationResult.of(outcome);
        }
        ToolResult answer = context.answer;
        answer.put("procedure", procedure); //$NON-NLS-1$
        if (parameters != null)
        {
            answer.put("parameters", parameters); //$NON-NLS-1$
        }
        String module = context.file.moduleText();
        boolean declared = module != null && Pattern.compile("(?imu)^\\s*(Процедура|Функция)\\s+" + Pattern.quote(procedure) + "\\s*\\(") //$NON-NLS-1$ //$NON-NLS-2$
            .matcher(module).find();
        answer.put("procedureDeclared", declared); //$NON-NLS-1$
        String moduleText = null;
        if (!declared && stub)
        {
            if (parameters == null)
            {
                answer.put("stub", "not added: the parameters of " + event + " were not measured; write the procedure by hand"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            else
            {
                moduleText = withStub(module, procedure, parameters);
                answer.put("stub", "Процедура " + procedure + "(" + parameters + ") added to the module"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            }
        }
        return moduleText == null ? OperationResult.of(outcome) : OperationResult.withModule(outcome, moduleText);
    }

    /**
     * The module text with an empty procedure appended.
     *
     * @param module the module text, or {@code null} when the container has none
     * @param procedure the procedure's name
     * @param parameters its parameter list
     * @return the new text
     */
    public static String withStub(String module, String procedure, String parameters)
    {
        String text = module == null ? "" : module; //$NON-NLS-1$
        String delimiter = text.contains(CRLF) || text.isEmpty() ? CRLF : "\n"; //$NON-NLS-1$
        StringBuilder next = new StringBuilder(text);
        if (next.length() > 0 && !text.endsWith("\n")) //$NON-NLS-1$
        {
            next.append(delimiter);
        }
        if (next.length() > 0)
        {
            next.append(delimiter);
        }
        next.append("Процедура ").append(procedure).append('(').append(parameters).append(')').append(delimiter) //$NON-NLS-1$
            .append("КонецПроцедуры").append(delimiter); //$NON-NLS-1$
        return next.toString();
    }

    private static OperationResult addElement(LayoutContext context)
    {
        String type = context.string("type"); //$NON-NLS-1$
        String name = context.string("name"); //$NON-NLS-1$
        if (type == null || type.isEmpty() || name == null || name.isEmpty())
        {
            return OperationResult.refused("add_element needs type and name; types: " + String.join(", ", LayoutEditor.templatedTypes()) + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        Integer left = context.integer("left"); //$NON-NLS-1$
        Integer top = context.integer("top"); //$NON-NLS-1$
        Integer right = context.integer("right"); //$NON-NLS-1$
        Integer bottom = context.integer("bottom"); //$NON-NLS-1$
        Integer width = context.integer("width"); //$NON-NLS-1$
        Integer height = context.integer("height"); //$NON-NLS-1$
        int l = left == null ? 8 : left.intValue();
        int t = top == null ? 8 : top.intValue();
        int r = right != null ? right.intValue() : l + (width != null ? width.intValue() : DataPaths.defaultWidth(type));
        int b = bottom != null ? bottom.intValue() : t + (height != null ? height.intValue() : DataPaths.defaultHeight(type));
        if (r < l || b < t)
        {
            return OperationResult.refused("The box must have its right at or beyond its left and its bottom at or below its top."); //$NON-NLS-1$
        }
        String caption = context.string("caption"); //$NON-NLS-1$
        if (caption == null)
        {
            caption = context.string("title"); //$NON-NLS-1$
        }
        String tooltip = context.string("tooltip"); //$NON-NLS-1$
        String data = context.string("data"); //$NON-NLS-1$
        List<FormLayout.Segment> path = null;
        ListNode pattern = null;
        if (data != null && !data.trim().isEmpty())
        {
            FormLayout layout = context.editor.layout();
            LayoutNames names = ConfigurationNames.of(context.project, layout.producedTypeUuids());
            layout.resolve(names);
            path = new ArrayList<>();
            ListNode[] patternOut = new ListNode[1];
            String problem = DataPaths.resolve(layout, names, context.ownerKind, data.trim(), path, patternOut);
            if (problem != null)
            {
                return OperationResult.refused(problem);
            }
            pattern = patternOut[0];
            context.answer.put("data", data.trim()); //$NON-NLS-1$
        }
        ListNode settings = null;
        if ("table".equalsIgnoreCase(type.trim())) //$NON-NLS-1$
        {
            if (pattern == null)
            {
                return OperationResult.refused("A table shows rows: give data naming the list, tabular section or value table it shows."); //$NON-NLS-1$
            }
            FormLayout layout = context.editor.layout();
            LayoutNames names = ConfigurationNames.of(context.project, layout.producedTypeUuids());
            String rowType = TypePattern.of(pattern).singleProducedType();
            String rowTypeName = rowType == null ? null : names.typeName(rowType);
            settings = TableColumns.settingsBlock(rowTypeName, rowType);
            if (settings == null)
            {
                return OperationResult.refused("No table was measured over rows of " + (rowTypeName != null ? rowTypeName : TypePattern.of(pattern).describe(names)) //$NON-NLS-1$
                    + "; a table shows a list of a catalog, document, chart of characteristic types, exchange plan, register or task, " //$NON-NLS-1$
                    + "a tabular section, a record set, a value table or a value tree" //$NON-NLS-1$
                    + (names == LayoutNames.NONE ? " (the configuration is not loaded in this environment)." : ".")); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        LayoutEditor.Outcome outcome = context.editor.addElement(type, name, context.string("parent"), //$NON-NLS-1$
            context.string("page"), new int[] { l, t, r, b }, caption, tooltip, pattern, settings); //$NON-NLS-1$
        if (outcome.done && path != null)
        {
            LayoutEditor.Outcome bound = context.editor.bindData(outcome.elementId, path);
            if (!bound.done)
            {
                return OperationResult.of(bound);
            }
        }
        if (outcome.done)
        {
            context.answer.put("box", l + "," + t + "," + r + "," + b); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        return OperationResult.of(outcome);
    }

    /**
     * The help: the operations of every catalog, the events, and what an ordinary form is.
     */
    private static String help()
    {
        return ToolResult.success()
            .put("tool", "ordinary_forms") //$NON-NLS-1$ //$NON-NLS-2$
            .put("operations", OperationsCatalog.helpLines()) //$NON-NLS-1$
            .put("properties", PropertyHandlers.descriptions()) //$NON-NLS-1$
            .put("events", eventsCatalogue()) //$NON-NLS-1$
            .put("writes", "Every writing operation takes dryRun; a write rewrites only the records the operation names, " //$NON-NLS-1$ //$NON-NLS-2$
                + "and marks the form's owner in the sync baselines so update_database carries the form.") //$NON-NLS-1$
            .put("whatAnOrdinaryFormIs", "A form of the thick client's ordinary application mode. EDT keeps it " //$NON-NLS-1$ //$NON-NLS-2$
                + "as Form.oform - a platform container with two text entries, 'form' (the layout in the " //$NON-NLS-1$
                + "platform's brace format) and 'module' (BSL) - and does not open it in any editor.") //$NON-NLS-1$
            .toJson();
    }

    /** The events of the form and of every element type, for the help. */
    static Map<String, Object> eventsCatalogue()
    {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("form", new ArrayList<>(FormEvents.formEvents().values())); //$NON-NLS-1$
        out.put("mainAttributeOfAnObject", new ArrayList<>(FormEvents.mainAttributeEvents(false).values())); //$NON-NLS-1$
        out.put("mainAttributeOfSettings", new ArrayList<>(FormEvents.mainAttributeEvents(true).values())); //$NON-NLS-1$
        for (String type : new String[] { "Field", "SelectField", "CheckBox", "RadioBtn", "Button", "Label", "Image", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            "Panel", "Table", "TableField", "ListField", "CalendarBox", "FieldHtml", "Chart" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        {
            out.put(type, new ArrayList<>(FormEvents.elementEvents(type).values()));
        }
        return out;
    }
}
