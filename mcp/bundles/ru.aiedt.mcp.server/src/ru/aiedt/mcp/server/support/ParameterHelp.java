/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.Activator;

/**
 * A tool's own parameters, asked of the tool rather than read out of the whole catalogue.
 * <p>
 * A client that wants to know what one tool takes has one place to look today: the catalogue it
 * received at the start of the conversation, which describes every tool there is. Asking the tool
 * itself costs nothing and answers about one.
 * </p>
 * <p>
 * Rendered FROM the tool's schema, not written beside it. The same prose kept in two places drifts,
 * and a help text that disagrees with the schema is worse than no help text - the schema is what
 * the call is validated against, so the copy would be the wrong half to trust.
 * </p>
 */
public final class ParameterHelp
{
    private ParameterHelp()
    {
    }

    /**
     * Every parameter a tool declares, with what it is and whether it must be present.
     *
     * @param toolName the tool's wire name, for the heading.
     * @param inputSchema the tool's schema, as {@code getInputSchema} returns it.
     * @return markdown, or a line saying why there is none
     */
    public static String render(String toolName, String inputSchema)
    {
        JsonObject schema;
        try
        {
            JsonElement parsed = JsonParser.parseString(inputSchema);
            schema = parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        }
        catch (RuntimeException notJson)
        {
            Activator.logWarning("a tool's schema would not parse for help: " + notJson); //$NON-NLS-1$
            schema = null;
        }
        if (schema == null)
        {
            return "This tool declares no schema that could be read, so its parameters " //$NON-NLS-1$
                + "cannot be listed here. The catalogue entry is the only description."; //$NON-NLS-1$
        }
        JsonObject properties = schema.getAsJsonObject("properties"); //$NON-NLS-1$
        if (properties == null || properties.size() == 0)
        {
            // Said, not left blank: a tool that takes nothing and a tool whose parameters could not
            // be listed read the same way when the answer is empty.
            return "## " + toolName + " - parameters\n\nThis tool takes no parameters."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        Set<String> required = new HashSet<>();
        JsonArray insisted = schema.getAsJsonArray("required"); //$NON-NLS-1$
        if (insisted != null)
        {
            for (JsonElement name : insisted)
            {
                required.add(name.getAsString());
            }
        }

        StringBuilder text = new StringBuilder("## ").append(toolName) //$NON-NLS-1$
            .append(" - parameters\n\n"); //$NON-NLS-1$
        Map<String, JsonObject> sorted = new TreeMap<>();
        for (Map.Entry<String, JsonElement> property : properties.entrySet())
        {
            if (property.getValue().isJsonObject())
            {
                sorted.put(property.getKey(), property.getValue().getAsJsonObject());
            }
        }
        for (Map.Entry<String, JsonObject> property : sorted.entrySet())
        {
            text.append("### ").append(property.getKey()); //$NON-NLS-1$
            text.append("  _").append(kindOf(property.getValue())); //$NON-NLS-1$
            text.append(required.contains(property.getKey()) ? ", required_\n\n" : "_\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            JsonElement described = property.getValue().get("description"); //$NON-NLS-1$
            text.append(described != null && described.isJsonPrimitive()
                ? described.getAsString()
                // A parameter with no description is named anyway. Leaving it out would say the
                // tool does not take it, which is a different thing from taking it undescribed.
                : "_No description is declared for this parameter._"); //$NON-NLS-1$
            text.append("\n\n"); //$NON-NLS-1$
        }
        return text.toString();
    }

    /**
     * The named parameters of one schema, in two groups.
     * <p>
     * For an operation a facade handles itself. There is no schema of its own to render, so the
     * names come from the operation-parameter map and the descriptions from the schema the facade
     * declares - the two halves of one answer, neither of which is enough alone.
     * </p>
     * <p>
     * Two groups because the map answers two questions. What the operation's own code reads is
     * established as its own; what the facade reads before dispatching is the same for every
     * operation it has, and printing it as the operation's would say it takes something it does
     * not. Both are shown, because a caller may send either.
     * </p>
     *
     * @param operation the operation asked about, for the heading.
     * @param inputSchema the facade's own schema, where the descriptions live.
     * @param established parameter names established for this operation.
     * @param shared parameter names the facade reads for every operation.
     * @return markdown, or a line saying why there is none
     */
    public static String renderNamed(String operation, String inputSchema,
        Collection<String> established, Collection<String> shared)
    {
        JsonObject properties = propertiesOf(inputSchema);
        if (properties == null)
        {
            return "## " + operation + " - parameters\n\nThe schema this facade declares could " //$NON-NLS-1$ //$NON-NLS-2$
                + "not be read, so the descriptions of these parameters cannot be shown.\n"; //$NON-NLS-1$
        }
        StringBuilder text = new StringBuilder("## ").append(operation) //$NON-NLS-1$
            .append(" - parameters\n\n"); //$NON-NLS-1$
        if (established.isEmpty() && shared.isEmpty())
        {
            return text.append("The parameters of this operation are not recorded. The schema " //$NON-NLS-1$
                + "this facade declares is what a call is validated against.\n").toString(); //$NON-NLS-1$
        }
        appendGroup(text, properties, established,
            "Established for this operation\n\n"); //$NON-NLS-1$
        appendGroup(text, properties, shared,
            "Read by this facade for every operation it has\n\n"); //$NON-NLS-1$
        return text.toString();
    }

    private static void appendGroup(StringBuilder text, JsonObject properties,
        Collection<String> names, String heading)
    {
        if (names.isEmpty())
        {
            return;
        }
        text.append("### ").append(heading); //$NON-NLS-1$
        for (String name : new TreeSet<>(names))
        {
            text.append("- **").append(name).append("**"); //$NON-NLS-1$ //$NON-NLS-2$
            JsonElement property = properties.get(name);
            if (property != null && property.isJsonObject())
            {
                JsonObject declared = property.getAsJsonObject();
                text.append("  _").append(kindOf(declared)).append("_"); //$NON-NLS-1$ //$NON-NLS-2$
                JsonElement described = declared.get("description"); //$NON-NLS-1$
                if (described != null && described.isJsonPrimitive())
                {
                    text.append(" - ").append(described.getAsString()); //$NON-NLS-1$
                }
            }
            else
            {
                // The map says the operation reads it and the schema does not declare it. Both
                // facts are worth the caller's attention, and neither is this method's to settle.
                text.append(" - read by the code, and not declared in this facade's schema"); //$NON-NLS-1$
            }
            text.append("\n"); //$NON-NLS-1$
        }
        text.append("\n"); //$NON-NLS-1$
    }

    private static JsonObject propertiesOf(String inputSchema)
    {
        try
        {
            JsonElement parsed = JsonParser.parseString(inputSchema);
            return parsed.isJsonObject() ? parsed.getAsJsonObject().getAsJsonObject("properties") //$NON-NLS-1$
                : null;
        }
        catch (RuntimeException notJson)
        {
            Activator.logWarning("a facade's schema would not parse for help: " + notJson); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * What a parameter is, in the words a caller builds a value from.
     *
     * @param property one property of the schema.
     * @return the kind, never <code>null</code>
     */
    private static String kindOf(JsonObject property)
    {
        JsonElement type = property.get("type"); //$NON-NLS-1$
        String named = type != null && type.isJsonPrimitive() ? type.getAsString() : "unknown"; //$NON-NLS-1$
        if (!"array".equals(named)) //$NON-NLS-1$
        {
            return named;
        }
        JsonElement items = property.get("items"); //$NON-NLS-1$
        if (items != null && items.isJsonObject())
        {
            JsonElement inner = items.getAsJsonObject().get("type"); //$NON-NLS-1$
            if (inner != null && inner.isJsonPrimitive())
            {
                // An array of what: a caller sending strings where objects are wanted is refused by
                // the client before the request is made, and "array" alone does not say which.
                return "array of " + inner.getAsString(); //$NON-NLS-1$
            }
        }
        return named;
    }
}
