/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.ops;

import java.util.function.Supplier;

/**
 * One argument of an operation as the schema declares it.
 */
public final class ParamSpec
{
    /** The JSON type of an argument. */
    public enum Type
    {
        STRING, INTEGER, BOOLEAN
    }

    public final String name;

    public final Type type;

    private final Supplier<String> description;

    private final String schemaDescription;

    /**
     * Declare an argument whose schema uses its full lazy description by default.
     *
     * @param name the argument name
     * @param type the JSON value type
     * @param description the full lazy description
     */
    private ParamSpec(String name, Type type, Supplier<String> description)
    {
        this(name, type, description, null);
    }

    /**
     * Keep complete help separate from an optional explicit schema summary.
     *
     * @param name the argument name
     * @param type the JSON value type
     * @param description the unchanged full lazy description
     * @param schemaDescription the schema summary, or null to use the full description
     */
    private ParamSpec(String name, Type type, Supplier<String> description, String schemaDescription)
    {
        this.name = name;
        this.type = type;
        this.description = description;
        this.schemaDescription = schemaDescription;
    }

    /**
     * @return what the argument means for the operation that declares it, no operation prefix;
     *         read when the schema is built, so a description that lists a registry (the
     *         properties, the element kinds) names what is registered by then
     */
    public String description()
    {
        return description.get();
    }

    /**
     * Read the explicit schema summary or evaluate the original full supplier when none is set.
     *
     * @return the visible schema description without an operation prefix
     */
    public String schemaDescription()
    {
        return schemaDescription == null ? description() : schemaDescription;
    }

    /**
     * Return an argument with a separate schema summary; leave its full help supplier unchanged.
     *
     * @param summary the nonblank schema description without an operation prefix
     * @return a new argument retaining the same name, type and full lazy description
     */
    public ParamSpec withSchemaDescription(String summary)
    {
        if (summary == null || summary.isBlank())
        {
            throw new IllegalArgumentException("Schema description must not be blank"); //$NON-NLS-1$
        }
        return new ParamSpec(name, type, description, summary);
    }

    /**
     * Declare a string argument with a constant full description.
     *
     * @param name the argument name
     * @param description the complete meaning
     * @return the declared argument
     */
    public static ParamSpec string(String name, String description)
    {
        return new ParamSpec(name, Type.STRING, () -> description);
    }

    /** A string argument whose description is read when the schema is built. */
    public static ParamSpec string(String name, Supplier<String> description)
    {
        return new ParamSpec(name, Type.STRING, description);
    }

    public static ParamSpec integer(String name, String description)
    {
        return new ParamSpec(name, Type.INTEGER, () -> description);
    }

    public static ParamSpec bool(String name, String description)
    {
        return new ParamSpec(name, Type.BOOLEAN, () -> description);
    }
}
