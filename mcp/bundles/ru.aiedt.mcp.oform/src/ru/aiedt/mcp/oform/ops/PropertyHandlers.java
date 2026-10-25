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

import ru.aiedt.mcp.oform.layout.LayoutEditor;

/**
 * The properties {@code set_property} knows, by name: each theme catalog registers its own when
 * the operations catalog loads it, and the operation looks the property up here instead of
 * switching on the name.
 * <p>
 * A handler reads the arguments it needs off the context itself ({@code value}, {@code width},
 * {@code fontSize}, ...) and refuses with a message naming what is missing.
 * </p>
 */
public final class PropertyHandlers
{
    /** What a property does when set. */
    @FunctionalInterface
    public interface PropertyHandler
    {
        /**
         * @param context the form, the editor and the arguments
         * @param elementName the element the property is set on, or {@code null} for the form
         * @return the editor's outcome
         */
        LayoutEditor.Outcome apply(LayoutContext context, String elementName);
    }

    private static final Map<String, PropertyHandler> HANDLERS = new LinkedHashMap<>();

    private static final Map<String, String> DESCRIPTIONS = new LinkedHashMap<>();

    private PropertyHandlers()
    {
        // static registry
    }

    /**
     * Registers a property. A name registered twice is a programming error.
     *
     * @param name the property name as the argument spells it (case does not matter on lookup)
     * @param description what the property is, for the schema and the refusal message
     * @param handler what setting it does
     */
    public static synchronized void register(String name, String description, PropertyHandler handler)
    {
        String key = name.toLowerCase(Locale.ROOT);
        if (HANDLERS.containsKey(key))
        {
            throw new IllegalStateException("The property " + name + " is registered twice"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        HANDLERS.put(key, handler);
        DESCRIPTIONS.put(name, description);
    }

    /** @return the handler of a property, or {@code null} */
    public static PropertyHandler find(String name)
    {
        return name == null ? null : HANDLERS.get(name.trim().toLowerCase(Locale.ROOT));
    }

    /** @return the property names as registered, in order */
    public static List<String> names()
    {
        return new ArrayList<>(DESCRIPTIONS.keySet());
    }

    /** @return a copy of the property descriptions in registration order, for help */
    public static Map<String, String> descriptions()
    {
        return new LinkedHashMap<>(DESCRIPTIONS);
    }

    /** @return the names with their descriptions, for the schema */
    public static String describe()
    {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : DESCRIPTIONS.entrySet())
        {
            if (out.length() > 0)
            {
                out.append(" | "); //$NON-NLS-1$
            }
            out.append(entry.getKey()).append(" (").append(entry.getValue()).append(')'); //$NON-NLS-1$
        }
        return out.toString();
    }
}
