/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Extracts human-readable text from 1C localized-string holders.
 * <p>
 * {@code MdObject.getSynonym()} is an {@code EMap<lang, text>}; a form
 * item / decoration title is an {@code mcore.LocalString} whose
 * {@code getContent()} is the same kind of EMap. When such a holder is reached
 * via reflection and treated as a raw {@link Map}, {@code Map.get("ru")} /
 * {@code values()} on an EMF {@code EcoreEMap} hand back the internal
 * {@code LocalStringMapEntryImpl} / {@code LocalString} wrapper instead of the
 * value - which stringifies to {@code "[...LocalStringMapEntryImpl@hash]"}.
 * This helper unwraps {@link Map.Entry} and nested {@code LocalString} holders
 * and drops object-identity {@code toString()} junk, so callers always get the
 * plain text (or {@code null}).
 */
public final class LocalizedStringUtils
{
    private LocalizedStringUtils()
    {
        // Utility class
    }

    /**
     * Extracts the localized text, preferring the {@code ru} entry.
     *
     * @param holder a synonym EMap, an mcore LocalString, or {@code null}
     * @return the text, or {@code null} when nothing usable is found
     */
    public static String text(Object holder)
    {
        return text(holder, "ru"); //$NON-NLS-1$
    }

    /**
     * Extracts the localized text, preferring the given language code, then
     * any non-empty entry.
     */
    public static String text(Object holder, String preferLang)
    {
        if (holder == null)
        {
            return null;
        }
        // A synonym is already an EMap; a LocalString wrapper exposes its EMap
        // via getContent(). Crucial: an EMF EMap - including BM's
        // EBmStoreEcoreEMap - is NOT a java.util.Map. It is an EList of Entry
        // EObjects (getKey()/getValue()), so handle the Iterable case too.
        Object source;
        if (holder instanceof Map || holder instanceof Iterable)
        {
            source = holder;
        }
        else
        {
            Object content = invokeNoArg(holder, "getContent"); //$NON-NLS-1$
            source = content != null ? content : holder;
        }
        String fallback = null;
        if (source instanceof Map)
        {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) source).entrySet())
            {
                String value = unwrap(entry.getValue());
                if (value == null)
                {
                    continue;
                }
                if (preferLang.equalsIgnoreCase(String.valueOf(entry.getKey())))
                {
                    return value;
                }
                if (fallback == null)
                {
                    fallback = value;
                }
            }
            return fallback;
        }
        if (source instanceof Iterable)
        {
            // EMF EMap entries: read getKey() / getValue() reflectively. get(key)
            // / values() on an EcoreEMap hand back the Entry wrapper instead of
            // the value, so we walk the entry list directly.
            for (Object entry : (Iterable<?>) source)
            {
                Object key = invokeNoArg(entry, "getKey"); //$NON-NLS-1$
                String value = unwrap(invokeNoArg(entry, "getValue")); //$NON-NLS-1$
                if (value == null)
                {
                    continue;
                }
                if (preferLang.equalsIgnoreCase(String.valueOf(key)))
                {
                    return value;
                }
                if (fallback == null)
                {
                    fallback = value;
                }
            }
            return fallback;
        }
        return clean(holder);
    }

    /** Resolves a single map value to text, unwrapping Entry / LocalString. */
    private static String unwrap(Object value)
    {
        if (value == null)
        {
            return null;
        }
        if (value instanceof String)
        {
            String s = (String) value;
            return s.isEmpty() ? null : s;
        }
        if (value instanceof Map.Entry)
        {
            return unwrap(((Map.Entry<?, ?>) value).getValue());
        }
        Object content = invokeNoArg(value, "getContent"); //$NON-NLS-1$
        if (content instanceof Map)
        {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) content).entrySet())
            {
                String v = unwrap(entry.getValue());
                if (v != null)
                {
                    return v;
                }
            }
        }
        return clean(value);
    }

    /** Drops EMF object-identity toString ("[com._1c...@1a2b]", "...Impl@dead"). */
    private static String clean(Object o)
    {
        if (o == null)
        {
            return null;
        }
        String s = o.toString();
        if (s.isEmpty())
        {
            return null;
        }
        // Drop EMF object-identity toString only: a bare "...@deadbeef" tail or
        // bracketed "[Class@deadbeef]" (>=6 hex digits). Keeps real text such as
        // "[Архив]" (no @hex) and "user@cafe" (fewer than 6 hex).
        if (s.matches(".*@[0-9a-fA-F]{6,}$") //$NON-NLS-1$
            || (s.startsWith("[") && s.matches(".*@[0-9a-fA-F]{6,}\\]$"))) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return null;
        }
        return s;
    }

    private static Object invokeNoArg(Object target, String method)
    {
        if (target == null)
        {
            return null;
        }
        try
        {
            Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        }
        catch (Exception e)
        {
            return null;
        }
    }
}
