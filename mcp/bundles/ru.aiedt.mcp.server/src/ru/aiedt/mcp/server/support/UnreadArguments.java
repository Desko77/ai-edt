/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The arguments a call carries that the operation it names will not read.
 * <p>
 * An argument nobody reads used to be accepted in silence. A typo in a name and a property the
 * operation does not support came back the same way a correct call did - {@code success: true} - so
 * the caller could not tell a request that was carried out from one that was half thrown away.
 * Reported with a made-up name and with a real form property the operation does not read; both
 * answered success.
 * </p>
 * <p>
 * The list of what an operation reads comes from {@link OperationParameters}, generated from the
 * sources and checked in CI. Where the map has nothing for an operation the answer here is empty:
 * the map states its own gaps - five tools dispatch without a switch and are not in it - and a check
 * that guessed at those would reject calls that work.
 * </p>
 */
public final class UnreadArguments
{
    /**
     * Keys that belong to the call rather than to the operation, so no operation has to declare
     * them. They steer dispatch, preview and batching; the map records only what a handler reads.
     */
    private static final Set<String> SERVICE_KEYS = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("operation", "dryRun", "batch", "operations", "stopOnError", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "runKey", "timeoutSeconds", "topic", "confirm", "projectName"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    private UnreadArguments()
    {
    }

    /**
     * Which of the supplied arguments the operation will not read.
     *
     * @param facadeClass simple name of the facade class, as the map keys it
     * @param operation the operation named by the call
     * @param supplied the call's arguments; may be <code>null</code>
     * @return the argument names in order, or an empty list when there is nothing to say - including
     *         when the map does not record this operation
     */
    public static List<String> of(String facadeClass, String operation, Map<String, String> supplied)
    {
        List<String> unread = new ArrayList<>();
        if (supplied == null || supplied.isEmpty())
        {
            return unread;
        }
        List<String> read = OperationParameters.of(facadeClass, operation);
        if (read.isEmpty())
        {
            // Not recorded. Saying nothing is the only honest answer: this operation may read
            // anything at all, and refusing on a guess would turn working calls away.
            return unread;
        }
        Set<String> known = new HashSet<>(read);
        for (String name : new TreeSet<>(supplied.keySet()))
        {
            if (!known.contains(name) && !SERVICE_KEYS.contains(name))
            {
                unread.add(name);
            }
        }
        return unread;
    }

    /**
     * The refusal naming what was not read and what would be.
     *
     * @param operation the operation named by the call
     * @param unread what it will not read, from {@link #of}
     * @param read what it does read
     * @return the message; never <code>null</code>
     */
    public static String refusal(String operation, List<String> unread, List<String> read)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(unread.size() == 1 ? "Argument " : "Arguments "); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(String.join(", ", unread)); //$NON-NLS-1$
        sb.append(unread.size() == 1 ? " is not read by " : " are not read by "); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(operation);
        sb.append(". It reads: "); //$NON-NLS-1$
        sb.append(String.join(", ", new TreeSet<>(read))); //$NON-NLS-1$
        sb.append(". Nothing was written - a name this operation does not read would have been " //$NON-NLS-1$
            + "dropped in silence, and the answer would have looked the same as a correct call."); //$NON-NLS-1$
        return sb.toString();
    }

    /**
     * The read set, for a caller that wants to build its own message.
     *
     * @param facadeClass simple name of the facade class
     * @param operation the operation
     * @return what the operation reads; empty when not recorded
     */
    public static List<String> readBy(String facadeClass, String operation)
    {
        return OperationParameters.of(facadeClass, operation);
    }
}
