/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;

/**
 * Holder of the one {@link Gson} instance the whole plugin shares.
 * <p>
 * Three settings are load bearing:
 * </p>
 * <ul>
 * <li>nulls are NOT serialized - this is what keeps a JSON-RPC envelope legal (exactly one of
 * {@code result} / {@code error}) and an embedded resource well formed (exactly one of
 * {@code text} / {@code blob});</li>
 * <li>HTML escaping stays on - it is the byte form clients already receive;</li>
 * <li>a JSON number read into an {@code Object} keeps its shape: a whole number becomes a
 * {@code Long}, a fractional one a {@code Double}. Gson's stock policy makes every such number a
 * {@code Double}, and that leaked all the way to the agent: an argument sent as {@code 42} reached
 * a tool as the string {@code "42.0"}, and inside a nested array - where nothing re-parses it - the
 * tool echoed {@code 42.0} back.</li>
 * </ul>
 * <p>
 * Do not create ad-hoc Gson instances elsewhere: parser construction is expensive and the settings
 * above would drift.
 * </p>
 */
public final class GsonHolder
{
    private static final Gson GSON =
        new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();

    private GsonHolder()
    {
        // static access only
    }

    /**
     * Returns the shared instance. It is immutable and safe to use from any thread.
     *
     * @return the shared Gson instance, never <code>null</code>
     */
    public static Gson get()
    {
        return GSON;
    }

    /**
     * Serializes an object with the shared instance.
     *
     * @param src the object to serialize; may be <code>null</code>
     * @return the JSON document
     */
    public static String toJson(Object src)
    {
        return GSON.toJson(src);
    }

    /**
     * Deserializes a JSON document with the shared instance.
     *
     * @param json the document to read; may be <code>null</code>
     * @param classOfT the target type
     * @param <T> the target type
     * @return the deserialized object, or <code>null</code> for an empty document
     */
    public static <T> T fromJson(String json, Class<T> classOfT)
    {
        return GSON.fromJson(json, classOfT);
    }
}
