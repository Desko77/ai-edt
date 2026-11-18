/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.layout;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The one source of the fresh uuids a layout edit writes: a command panel, its actions, a
 * submenu's block, the standard actions of a form.
 *
 * <p>By default every uuid is random. A caller that has to compare two runs of one edit byte for
 * byte runs both under {@link #using(Supplier, Supplier)} with a {@link #sequence()} of its own;
 * the source is bound to the calling thread, so a concurrent edit elsewhere keeps its random ids.</p>
 */
public final class LayoutIds
{
    private static final Supplier<String> RANDOM = () -> UUID.randomUUID().toString();

    private static final ThreadLocal<Supplier<String>> CURRENT = new ThreadLocal<>();

    private LayoutIds()
    {
        // static source
    }

    /**
     * The next fresh uuid from the source bound to this thread, a random one when none is bound.
     *
     * @return the uuid as the layout writes it, lower case
     */
    public static String next()
    {
        Supplier<String> source = CURRENT.get();
        return (source == null ? RANDOM : source).get();
    }

    /**
     * Runs work with a source of uuids bound to this thread, restoring the previous one after.
     *
     * @param <T> the result's type
     * @param source the source, for instance {@link #sequence()}
     * @param work what runs with the source
     * @return what the work returned
     */
    public static <T> T using(Supplier<String> source, Supplier<T> work)
    {
        Supplier<String> previous = CURRENT.get();
        CURRENT.set(source);
        try
        {
            return work.get();
        }
        finally
        {
            if (previous == null)
            {
                CURRENT.remove();
            }
            else
            {
                CURRENT.set(previous);
            }
        }
    }

    /**
     * A deterministic source: uuids numbered from 1 in the order they are asked for, so two fresh
     * sequences given to one edit produce the same text.
     *
     * @return a new sequence starting at 1
     */
    public static Supplier<String> sequence()
    {
        long[] counter = new long[1];
        return () -> String.format(Locale.ROOT, "00000000-0000-4000-8000-%012x", Long.valueOf(++counter[0])); //$NON-NLS-1$
    }
}
