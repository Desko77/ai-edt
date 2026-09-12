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
            return "This tool's schema could not be read, so its parameters cannot be listed " //$NON-NLS-1$
                + "here."; //$NON-NLS-1$
        }
        // Asked for rather than cast into: getAsJsonObject throws when the member is there and is
        // something else, and a schema that will not read must leave the caller with a sentence
        // rather than with an exception thrown out of a help request.
        JsonElement declared = schema.get("properties"); //$NON-NLS-1$
        if (declared != null && !declared.isJsonObject())
        {
            // Present and not an object is malformed, which is a different answer from empty.
            return "## " + toolName + " - parameters\n\nThis tool's schema declares a " //$NON-NLS-1$ //$NON-NLS-2$
                + "properties member that is not an object, so its parameters cannot be listed " //$NON-NLS-1$
                + "here."; //$NON-NLS-1$
        }
        if (declared == null)
        {
            // Absent is not empty either: a schema with no properties member says nothing about
            // what the tool takes, and answering "takes no parameters" would settle that.
            return "## " + toolName + " - parameters\n\nThis tool's schema declares no " //$NON-NLS-1$ //$NON-NLS-2$
                + "properties member, so what it takes is not stated there."; //$NON-NLS-1$
        }
        JsonObject properties = declared.getAsJsonObject();
        if (properties.size() == 0)
        {
            // Said, not left blank: a tool that takes nothing and a tool whose parameters could not
            // be listed read the same way when the answer is empty.
            return "## " + toolName + " - parameters\n\nThis tool takes no parameters."; //$NON-NLS-1$ //$NON-NLS-2$
        }

        StringBuilder text = new StringBuilder("## ").append(toolName) //$NON-NLS-1$
            .append(" - parameters\n\n"); //$NON-NLS-1$
        Set<String> required = new HashSet<>();
        JsonElement insistedOn = schema.get("required"); //$NON-NLS-1$
        boolean requirednessKnown = insistedOn == null || insistedOn.isJsonArray();
        if (insistedOn != null && insistedOn.isJsonArray())
        {
            for (JsonElement name : insistedOn.getAsJsonArray())
            {
                // A string and nothing else. A number in there would otherwise become the name
                // "1" and mark a property of that name required, which nobody wrote.
                if (name.isJsonPrimitive() && name.getAsJsonPrimitive().isString())
                {
                    required.add(name.getAsString());
                }
                else
                {
                    requirednessKnown = false;
                }
            }
        }
        if (!requirednessKnown)
        {
            text.append("_This schema's required list is not a list of names, so which of " //$NON-NLS-1$
                + "these must be present is not stated below._\n\n"); //$NON-NLS-1$
        }
        // Copied into a sorted map by hand rather than through JsonObject.asMap, which arrived in
        // a later Gson than the one this bundle ships.
        Map<String, JsonElement> sorted = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : properties.entrySet())
        {
            sorted.put(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, JsonElement> property : sorted.entrySet())
        {
            text.append("### ").append(property.getKey()); //$NON-NLS-1$
            if (!property.getValue().isJsonObject())
            {
                // Listed rather than dropped. Skipping it said the tool does not take the
                // parameter, when what is true is that its declaration cannot be read.
                text.append("\n\n_This parameter is declared in a shape that is not a property " //$NON-NLS-1$
                    + "object, so nothing can be said about it here._\n\n"); //$NON-NLS-1$
                continue;
            }
            JsonObject declaredProperty = property.getValue().getAsJsonObject();
            text.append("  _").append(kindOf(declaredProperty)); //$NON-NLS-1$
            text.append(requirednessKnown && required.contains(property.getKey())
                ? ", required_\n\n" : "_\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            JsonElement described = declaredProperty.get("description"); //$NON-NLS-1$
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
     * established as its own. The rest is what the facade reads on the way down, and for a facade
     * that dispatches with a switch that walk does not stop at the handlers - so the second group
     * holds arguments of sibling operations as well, and calling it shared would claim more than
     * holds. Both are shown, because a call may carry either.
     * </p>
     *
     * @param operation the operation asked about, for the heading.
     * @param inputSchema the facade's own schema, where the descriptions live.
     * @param established parameter names established for this operation.
     * @param shared the rest of what the operation accepts, established for no operation in
     *            particular.
     * @return markdown, or a line saying why there is none
     */
    public static String renderNamed(String operation, String inputSchema,
        Collection<String> established, Collection<String> shared)
    {
        // An unreadable schema costs the descriptions, not the names: those came from the map and
        // are the half a caller needs most. Returning here would have hidden them to report a
        // problem with the other half.
        JsonObject properties = propertiesOf(inputSchema);
        StringBuilder text = new StringBuilder("## ").append(operation) //$NON-NLS-1$
            .append(" - parameters\n\n"); //$NON-NLS-1$
        boolean schemaRead = properties != null;
        if (!schemaRead)
        {
            text.append("_The properties of the schema this facade declares could not be read, " //$NON-NLS-1$
                + "so these are named without their descriptions._\n\n"); //$NON-NLS-1$
            properties = new JsonObject();
        }
        if (established.isEmpty() && shared.isEmpty())
        {
            return text.append("The parameters of this operation are not recorded here. What " //$NON-NLS-1$
                + "this facade declares in its own schema still applies.\n").toString(); //$NON-NLS-1$
        }
        appendGroup(text, properties, schemaRead, established,
            "Established for this operation\n\n"); //$NON-NLS-1$
        // Not "read for every operation": the derivation attributes to an operation everything the
        // facade reads on the way down, and for a facade that dispatches with a switch that walk
        // does not stop at the handlers - so this group holds arguments of sibling operations too.
        // Narrowing it would narrow what the unread-argument guard allows, which is a different
        // contract; what can be said truthfully is that these were not established as this
        // operation's own.
        appendGroup(text, properties, schemaRead, shared,
            "Accepted here, not established as this operation's - the facade reads them on the " //$NON-NLS-1$
                + "way down, and some belong to its other operations\n\n"); //$NON-NLS-1$
        return text.toString();
    }

    private static void appendGroup(StringBuilder text, JsonObject properties, boolean schemaRead,
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
            else if (!schemaRead)
            {
                // Deliberately nothing. When the schema could not be read, "not declared" would
                // report as a fact what is only an absence of evidence, and the heading above
                // already says the descriptions are missing.
                text.append(""); //$NON-NLS-1$
            }
            else if (property == null)
            {
                // The map says the operation reads it and it is not among the properties. Said as
                // narrowly as that: a schema may declare a name elsewhere, and this looked in one
                // place.
                text.append(" - read by the code, and not among the properties this facade " //$NON-NLS-1$
                    + "declares"); //$NON-NLS-1$
            }
            else
            {
                // Declared and unreadable is a third thing, and calling it undeclared would send
                // whoever reads this looking for a property that is right there.
                text.append(" - declared in this facade's schema in a shape that is not a " //$NON-NLS-1$
                    + "property object, so nothing can be said about it here"); //$NON-NLS-1$
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
            if (!parsed.isJsonObject())
            {
                return null;
            }
            // Asked for, not cast, for the same reason as in render: getAsJsonObject throws when
            // the member is there and is something else, and the catch below would then report a
            // schema that parsed perfectly well as one that could not be read.
            JsonElement declared = parsed.getAsJsonObject().get("properties"); //$NON-NLS-1$
            return declared != null && declared.isJsonObject() ? declared.getAsJsonObject() : null;
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
