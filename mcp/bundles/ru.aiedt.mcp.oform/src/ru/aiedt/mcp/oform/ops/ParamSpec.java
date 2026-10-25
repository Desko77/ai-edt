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

    private ParamSpec(String name, Type type, Supplier<String> description)
    {
        this.name = name;
        this.type = type;
        this.description = description;
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
