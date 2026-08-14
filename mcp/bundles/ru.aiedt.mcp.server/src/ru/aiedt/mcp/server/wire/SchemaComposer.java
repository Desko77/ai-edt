/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fluent builder for the {@code inputSchema} a tool advertises in the catalogue.
 * <p>
 * Deliberately narrow: strings, integers, booleans and arrays of strings, each with a description
 * and an optional required flag. A tool that needs more than that writes its schema by hand. Adding
 * a feature here would change the schema of every tool that uses the builder, so do not.
 * </p>
 * <p>
 * Properties keep the order in which they were declared, because that is the order clients show
 * them in and tools declare the parameter that matters first.
 * </p>
 *
 * <pre>
 * SchemaComposer.object()
 *     .stringProperty("projectName", "Project name", true)
 *     .integerProperty("limit", "Maximum number of rows")
 *     .build();
 * </pre>
 */
public class SchemaComposer
{
    private static final String KEY_TYPE = "type"; //$NON-NLS-1$

    private static final String KEY_DESCRIPTION = "description"; //$NON-NLS-1$

    private static final String KEY_ITEMS = "items"; //$NON-NLS-1$

    private static final String KEY_PROPERTIES = "properties"; //$NON-NLS-1$

    private static final String KEY_REQUIRED = "required"; //$NON-NLS-1$

    private static final String TYPE_OBJECT = "object"; //$NON-NLS-1$

    private static final String TYPE_STRING = "string"; //$NON-NLS-1$

    private static final String TYPE_INTEGER = "integer"; //$NON-NLS-1$

    private static final String TYPE_BOOLEAN = "boolean"; //$NON-NLS-1$

    private static final String TYPE_ARRAY = "array"; //$NON-NLS-1$

    private final Map<String, Object> schema = new LinkedHashMap<>();

    private final Map<String, Object> properties = new LinkedHashMap<>();

    private final List<String> required = new ArrayList<>();

    private SchemaComposer()
    {
        schema.put(KEY_TYPE, TYPE_OBJECT);
    }

    /**
     * Starts a schema for a parameter object.
     *
     * @return a fresh builder seeded with <code>{"type":"object"}</code>
     */
    public static SchemaComposer object()
    {
        return new SchemaComposer();
    }

    /**
     * Declares an optional string parameter.
     *
     * @param name the parameter name
     * @param description what the parameter means, as the client will show it
     * @return this builder
     */
    public SchemaComposer stringProperty(String name, String description)
    {
        return stringProperty(name, description, false);
    }

    /**
     * Declares a string parameter.
     *
     * @param name the parameter name
     * @param description what the parameter means, as the client will show it
     * @param required whether the client must supply it
     * @return this builder
     */
    public SchemaComposer stringProperty(String name, String description, boolean required)
    {
        return scalarProperty(name, TYPE_STRING, description, required);
    }

    /**
     * Declares an optional integer parameter.
     *
     * @param name the parameter name
     * @param description what the parameter means, as the client will show it
     * @return this builder
     */
    public SchemaComposer integerProperty(String name, String description)
    {
        return integerProperty(name, description, false);
    }

    /**
     * Declares an integer parameter.
     *
     * @param name the parameter name
     * @param description what the parameter means, as the client will show it
     * @param required whether the client must supply it
     * @return this builder
     */
    public SchemaComposer integerProperty(String name, String description, boolean required)
    {
        return scalarProperty(name, TYPE_INTEGER, description, required);
    }

    /**
     * Declares an optional boolean parameter.
     *
     * @param name the parameter name
     * @param description what the parameter means, as the client will show it
     * @return this builder
     */
    public SchemaComposer booleanProperty(String name, String description)
    {
        return booleanProperty(name, description, false);
    }

    /**
     * Declares a boolean parameter.
     *
     * @param name the parameter name
     * @param description what the parameter means, as the client will show it
     * @param required whether the client must supply it
     * @return this builder
     */
    public SchemaComposer booleanProperty(String name, String description, boolean required)
    {
        return scalarProperty(name, TYPE_BOOLEAN, description, required);
    }

    /**
     * Declares an optional array-of-strings parameter.
     *
     * @param name the parameter name
     * @param description what the parameter means, as the client will show it
     * @return this builder
     */
    public SchemaComposer stringArrayProperty(String name, String description)
    {
        return stringArrayProperty(name, description, false);
    }

    /**
     * Declares an array-of-strings parameter.
     *
     * @param name the parameter name
     * @param description what the parameter means, as the client will show it
     * @param required whether the client must supply it
     * @return this builder
     */
    public SchemaComposer stringArrayProperty(String name, String description, boolean required)
    {
        Map<String, Object> items = new LinkedHashMap<>();
        items.put(KEY_TYPE, TYPE_STRING);

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put(KEY_TYPE, TYPE_ARRAY);
        definition.put(KEY_ITEMS, items);
        definition.put(KEY_DESCRIPTION, description);
        return addProperty(name, definition, required);
    }

    /**
     * Declares an array parameter whose entries are not all of one type.
     * <p>
     * Used where a caller may spell an entry more than one way - a compact string or an object with
     * named fields, say. Declaring the items as strings there would advertise one spelling while the
     * description promises two, and a client that enforces the schema would reject the very form the
     * description told it to send.
     * </p>
     *
     * @param name the parameter name
     * @param description what the parameter means, including which spellings are accepted
     * @return this builder
     */
    public SchemaComposer arrayProperty(String name, String description)
    {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put(KEY_TYPE, TYPE_ARRAY);
        definition.put(KEY_DESCRIPTION, description);
        return addProperty(name, definition, false);
    }

    /**
     * Renders the schema.
     * <p>
     * {@code properties} and {@code required} are always present, empty if nothing was declared.
     * Calling this more than once is harmless.
     * </p>
     *
     * @return the schema as a JSON document
     */
    public String build()
    {
        schema.put(KEY_PROPERTIES, properties);
        schema.put(KEY_REQUIRED, required);
        return GsonHolder.toJson(schema);
    }

    /**
     * Renders the schema as a map instead of a document, for a caller that wants to embed it.
     *
     * @return a copy of the schema; the builder is left untouched
     */
    public Map<String, Object> buildMap()
    {
        Map<String, Object> copy = new LinkedHashMap<>(schema);
        copy.put(KEY_PROPERTIES, new LinkedHashMap<>(properties));
        copy.put(KEY_REQUIRED, new ArrayList<>(required));
        return copy;
    }

    /**
     * Declares a property whose definition is nothing but a type and a description.
     *
     * @param name the parameter name
     * @param jsonType the JSON Schema type
     * @param description what the parameter means
     * @param isRequired whether the client must supply it
     * @return this builder
     */
    private SchemaComposer scalarProperty(String name, String jsonType, String description,
        boolean isRequired)
    {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put(KEY_TYPE, jsonType);
        definition.put(KEY_DESCRIPTION, description);
        return addProperty(name, definition, isRequired);
    }

    /**
     * Files a finished property definition under its name.
     *
     * @param name the parameter name
     * @param definition the property definition
     * @param isRequired whether the client must supply it
     * @return this builder
     */
    private SchemaComposer addProperty(String name, Map<String, Object> definition,
        boolean isRequired)
    {
        properties.put(name, definition);
        if (isRequired)
        {
            required.add(name);
        }
        return this;
    }
}
