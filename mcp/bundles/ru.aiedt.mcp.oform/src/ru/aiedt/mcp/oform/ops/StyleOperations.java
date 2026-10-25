/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ru.aiedt.mcp.oform.layout.Anchors;
import ru.aiedt.mcp.oform.layout.ElementStyle;
import ru.aiedt.mcp.oform.layout.LayoutEditor;

/**
 * The style of an element - visibility, enablement, the read-only flag of a field, the font, the
 * three colours, the alignment, the format - and its anchors. Declares no operation of its own:
 * the theme registers its properties in {@link PropertyHandlers} from {@link #specs()}, and
 * {@code set_property} finds them there. {@link ElementStyle} and {@link Anchors} select the
 * type-specific layout positions and refuse kinds or values outside their supported sets.
 */
public final class StyleOperations
{
    private StyleOperations()
    {
        // static catalog
    }

    /** @return the specs of this catalog; registers the theme's properties as a side effect */
    public static List<OperationSpec> specs()
    {
        registerProperties();
        return new ArrayList<>();
    }

    private static boolean propertiesRegistered;

    /**
     * Registers the ten style properties and {@code anchor}. Idempotent: the catalog may be built
     * more than once in a test runtime.
     */
    static synchronized void registerProperties()
    {
        if (propertiesRegistered)
        {
            return;
        }
        propertiesRegistered = true;
        PropertyHandlers.register("visible", "an element's visibility (value=true|false)", (context, element) -> { //$NON-NLS-1$ //$NON-NLS-2$
            Boolean value = truth(context.string("value")); //$NON-NLS-1$
            return value == null || element == null ? needs("visible", "elementName and value=true|false") //$NON-NLS-1$ //$NON-NLS-2$
                : ElementStyle.setVisible(context.tree, element, value.booleanValue());
        });
        PropertyHandlers.register("enabled", "an element's enablement (value=true|false)", (context, element) -> { //$NON-NLS-1$ //$NON-NLS-2$
            Boolean value = truth(context.string("value")); //$NON-NLS-1$
            return value == null || element == null ? needs("enabled", "elementName and value=true|false") //$NON-NLS-1$ //$NON-NLS-2$
                : ElementStyle.setEnabled(context.tree, element, value.booleanValue());
        });
        PropertyHandlers.register("readOnly", "a field's read-only flag (value=true|false); measured on a Field alone", //$NON-NLS-1$
            (context, element) -> { //$NON-NLS-2$
                Boolean value = truth(context.string("value")); //$NON-NLS-1$
                return value == null || element == null ? needs("readOnly", "elementName and value=true|false") //$NON-NLS-1$ //$NON-NLS-2$
                    : ElementStyle.setReadOnly(context.tree, element, value.booleanValue());
            });
        PropertyHandlers.register("tabs", //$NON-NLS-1$
            "a panel's tab strip (value=true|false): true draws a tab per page, false leaves the pages " //$NON-NLS-1$
                + "to be turned over from code. A panel draws tabs from its second page on", //$NON-NLS-1$
            (context, element) -> {
                Boolean value = truth(context.string("value")); //$NON-NLS-1$
                return value == null ? needs("tabs", "value=true|false") //$NON-NLS-1$ //$NON-NLS-2$
                    : context.editor.setTabs(element, value.booleanValue());
            });
        PropertyHandlers.register("font", "an element's font: value=auto resets it to the form's font, or " //$NON-NLS-1$
                + "fontFamily with fontSize (and bold, italic, underline)", //$NON-NLS-1$
            (context, element) -> { //$NON-NLS-2$
                if (element == null)
                {
                    return needs("font", "elementName"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                return ElementStyle.setFont(context.tree, element, context.string("value"), context.string("fontFamily"), //$NON-NLS-1$ //$NON-NLS-2$
                    context.integer("fontSize"), truth(context.string("bold")), truth(context.string("italic")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    truth(context.string("underline"))); //$NON-NLS-1$
            });
        PropertyHandlers.register("textColor", "an element's text colour (value=auto | #RRGGBB | a style element index)", //$NON-NLS-1$
            (context, element) -> element == null ? needs("textColor", "elementName and value") //$NON-NLS-1$ //$NON-NLS-2$
                : ElementStyle.setColor(context.tree, element, "text", context.string("value"))); //$NON-NLS-1$ //$NON-NLS-2$
        PropertyHandlers.register("backColor", "an element's background colour (value=auto | #RRGGBB | a style element index)", //$NON-NLS-1$
            (context, element) -> element == null ? needs("backColor", "elementName and value") //$NON-NLS-1$ //$NON-NLS-2$
                : ElementStyle.setColor(context.tree, element, "back", context.string("value"))); //$NON-NLS-1$ //$NON-NLS-2$
        PropertyHandlers.register("borderColor", "an element's border colour (value=auto | #RRGGBB | a style element index)", //$NON-NLS-1$
            (context, element) -> element == null ? needs("borderColor", "elementName and value") //$NON-NLS-1$ //$NON-NLS-2$
                : ElementStyle.setColor(context.tree, element, "border", context.string("value"))); //$NON-NLS-1$ //$NON-NLS-2$
        PropertyHandlers.register("alignment", "the horizontal alignment (value=left|center|right|auto) of a Label, " //$NON-NLS-1$
                + "a Button or a Field; a Field has no center", //$NON-NLS-1$
            (context, element) -> element == null ? needs("alignment", "elementName and value") //$NON-NLS-1$ //$NON-NLS-2$
                : ElementStyle.setAlignment(context.tree, element, context.string("value"))); //$NON-NLS-1$ //$NON-NLS-2$
        PropertyHandlers.register("format", "a field's format string (value; empty clears it)", (context, element) -> { //$NON-NLS-1$
            if (element == null)
            {
                return needs("format", "elementName"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            String language = context.string("language"); //$NON-NLS-1$
            return ElementStyle.setFormat(context.tree, element, context.string("value"), //$NON-NLS-1$
                language == null || language.isEmpty() ? "ru" : language); //$NON-NLS-1$
        });
        PropertyHandlers.register("editFormat", "a field's edit format - not measured: the platform reads no " //$NON-NLS-1$
                + "ФорматРедактирования and the corpus carries none; refused", //$NON-NLS-1$
            (context, element) -> LayoutEditor.refusal("The edit format is not measured: the platform answers no " //$NON-NLS-1$
                + "ФорматРедактирования on a field and no record of the corpus carries one, so no position is written.")); //$NON-NLS-1$
        PropertyHandlers.register("anchor", "an element's edge (edge=left|top|right|bottom) bound to the form " //$NON-NLS-1$
                + "(value=form), to none (value=none), or to a neighbour (value=<element>.<edge> with offset)", //$NON-NLS-1$
            (context, element) -> element == null ? needs("anchor", "elementName, edge and value") //$NON-NLS-1$ //$NON-NLS-2$
                : Anchors.set(context.tree, element, context.string("edge"), context.string("value"), //$NON-NLS-1$ //$NON-NLS-2$
                    context.integer("offset"))); //$NON-NLS-1$
    }

    /** {@code true} or {@code false} as a boolean, or {@code null} for anything else. */
    private static Boolean truth(String value)
    {
        if (value == null)
        {
            return null;
        }
        String text = value.trim().toLowerCase(Locale.ROOT);
        return "true".equals(text) || "false".equals(text) ? Boolean.valueOf(text) : null; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static LayoutEditor.Outcome needs(String property, String what)
    {
        return LayoutEditor.refusal("set_property " + property + " needs " + what + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
