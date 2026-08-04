/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Small text-suggestion helpers shared by tools that turn a wrong identifier
 * (a property name, an enum value, ...) into a "did you mean / available"
 * correction instead of a dead-end error message. Audit B10.
 */
public final class TextSuggest
{
    private TextSuggest()
    {
        // Utility class
    }

    /** Max candidates listed before truncating with a "+N more" suffix. */
    private static final int LIST_CAP = 40;

    /**
     * Builds a "property not found" message that names the closest settable
     * property (case-insensitive Levenshtein within a length-scaled threshold)
     * and lists the available ones - so an agent that typo'd the property name
     * gets a correction rather than a dead end.
     *
     * @param propertyName the name the caller passed
     * @param className the EClass / type name the property was looked up on
     * @param available the settable property names (may be empty/null)
     * @return a single-line diagnostic message
     */
    public static String propertyNotFound(String propertyName, String className,
        Collection<String> available)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Property '").append(propertyName).append("' is absent on ") //$NON-NLS-1$ //$NON-NLS-2$
            .append(className).append("."); //$NON-NLS-1$
        String suggestion = closest(propertyName, available);
        if (suggestion != null)
        {
            sb.append(" Did you mean '").append(suggestion).append("'?"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (available != null && !available.isEmpty())
        {
            List<String> list = new ArrayList<>(available);
            sb.append(" Settable: "); //$NON-NLS-1$
            if (list.size() <= LIST_CAP)
            {
                sb.append(String.join(", ", list)); //$NON-NLS-1$
            }
            else
            {
                sb.append(String.join(", ", list.subList(0, LIST_CAP))) //$NON-NLS-1$
                    .append(", ... (+").append(list.size() - LIST_CAP).append(" more)"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return sb.toString();
    }

    /**
     * Builds an "invalid value" diagnostic for a wrong enum / mode / type argument:
     * names the bad value, suggests the closest valid one (Levenshtein), and lists the
     * valid values (capped). Turns a bare "unknown X" into a self-correcting message so
     * an agent can fix the call without a round-trip.
     *
     * @param param the parameter name (e.g. "mode", "depth", "objectType")
     * @param value the value the caller passed
     * @param valid the allowed values (may be empty/null)
     * @return a single-line diagnostic message
     */
    public static String invalidValue(String param, String value, Collection<String> valid)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Invalid ").append(param).append(" '").append(value).append("'."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String suggestion = closest(value, valid);
        if (suggestion != null)
        {
            sb.append(" Did you mean '").append(suggestion).append("'?"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (valid != null && !valid.isEmpty())
        {
            List<String> list = new ArrayList<>(valid);
            sb.append(" Valid: "); //$NON-NLS-1$
            if (list.size() <= LIST_CAP)
            {
                sb.append(String.join(", ", list)); //$NON-NLS-1$
            }
            else
            {
                sb.append(String.join(", ", list.subList(0, LIST_CAP))) //$NON-NLS-1$
                    .append(", ... (+").append(list.size() - LIST_CAP).append(" more)"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return sb.toString();
    }

    /**
     * Builds a "required parameter missing" diagnostic that carries a concrete
     * example value, so an agent that omitted a required FQN / path / name
     * argument sees the expected shape instead of just "x is required". The
     * returned text has no "Error: " prefix - callers prepend it or wrap in
     * ToolResult.error as their response type requires.
     *
     * @param param the required parameter name (e.g. "objectName", "modulePath")
     * @param example a valid example value, or several comma-separated
     *            (e.g. "'Catalog.Products', 'CommonModules/MyModule/Module.bsl'");
     *            may be null/empty to omit the example
     * @return a single-line diagnostic message
     */
    public static String missingParam(String param, String example)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(param).append(" is required."); //$NON-NLS-1$
        if (example != null && !example.isEmpty())
        {
            sb.append(" Example: ").append(example); //$NON-NLS-1$
        }
        return sb.toString();
    }

    /**
     * Null-safe exception text for surfacing to a caller. Many exceptions
     * (NullPointerException, several EMF / reflection failures) have a null
     * {@code getMessage()}, which otherwise leaks to the agent as a useless
     * "Error: null". Returns the message when present; else the cause's
     * message (prefixed with the cause type); else the exception's simple
     * class name - never null or blank.
     *
     * @param t the throwable (may be null)
     * @return a non-empty, human-meaningful description
     */
    public static String safeMessage(Throwable t)
    {
        if (t == null)
        {
            return "unknown error"; //$NON-NLS-1$
        }
        String msg = t.getMessage();
        if (msg != null && !msg.trim().isEmpty())
        {
            return msg;
        }
        Throwable cause = t.getCause();
        if (cause != null && cause != t)
        {
            String cm = cause.getMessage();
            if (cm != null && !cm.trim().isEmpty())
            {
                return cause.getClass().getSimpleName() + ": " + cm; //$NON-NLS-1$
            }
        }
        return t.getClass().getSimpleName();
    }

    /**
     * Returns the candidate closest to {@code input} by case-insensitive
     * Levenshtein distance within a {@code max(2, len/2)} threshold, or
     * {@code null} when nothing is close enough or the inputs are empty.
     */
    public static String closest(String input, Collection<String> candidates)
    {
        if (input == null || input.isEmpty() || candidates == null)
        {
            return null;
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        int threshold = Math.max(2, input.length() / 2);
        String lower = input.toLowerCase();
        for (String c : candidates)
        {
            if (c == null)
            {
                continue;
            }
            int d = levenshtein(lower, c.toLowerCase());
            if (d < bestDistance)
            {
                bestDistance = d;
                best = c;
            }
        }
        return bestDistance <= threshold ? best : null;
    }

    /**
     * Classic two-row Levenshtein edit distance.
     */
    public static int levenshtein(String a, String b)
    {
        int n = a.length();
        int m = b.length();
        if (n == 0)
        {
            return m;
        }
        if (m == 0)
        {
            return n;
        }
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++)
        {
            prev[j] = j;
        }
        for (int i = 1; i <= n; i++)
        {
            curr[0] = i;
            for (int j = 1; j <= m; j++)
            {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[m];
    }
}
