/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support.oform;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ru.aiedt.mcp.server.toolkit.ops.BslModuleAccess;

/**
 * The procedures an ordinary form refers to by name, and the check a module write runs against
 * them.
 *
 * <p>An ordinary form binds its events to procedures by name: the layout carries a record
 * {@code {3,"Procedure",...}} per bound handler, and the module itself names procedures in
 * string literals for the platform calls that take a handler by name. Neither reference is
 * resolved by EDT, so a write that removes or renames such a procedure passes every check the
 * plugin has and fails in the client, at the moment the event fires. This is where the removed
 * procedures are compared with what still names them.</p>
 */
public final class HandlerBindings
{
    /** A bound handler in the layout: the record opens with 3 and the procedure name. */
    private static final Pattern BOUND = Pattern.compile("\\{3,\"((?:[^\"]|\"\")+)\""); //$NON-NLS-1$

    /** A string literal in BSL, quotes doubled inside; one line, as the module is read by lines. */
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"]|\"\")*)\""); //$NON-NLS-1$

    /** The value of {@code handlerChanges} that lets the write through with the names in the answer. */
    public static final String WARN = "warn"; //$NON-NLS-1$

    /** The value of {@code handlerChanges} that refuses the write while a bound procedure would go. */
    public static final String REFUSE = "refuse"; //$NON-NLS-1$

    private HandlerBindings()
    {
        // static utility
    }

    /**
     * The procedures the layout binds events to.
     *
     * @param layoutText the layout text; {@code null} for a form without one
     * @return the names, case-insensitive, in order of first appearance
     */
    public static Set<String> boundProcedures(String layoutText)
    {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (layoutText == null)
        {
            return names;
        }
        Matcher matcher = BOUND.matcher(layoutText);
        while (matcher.find())
        {
            names.add(matcher.group(1).replace("\"\"", "\"")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return names;
    }

    /**
     * The procedures and functions a module declares.
     *
     * @param lines the module lines
     * @return the names, case-insensitive
     */
    public static Set<String> declaredProcedures(List<String> lines)
    {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String line : lines)
        {
            Matcher matcher = BslModuleAccess.METHOD_START_PATTERN.matcher(line);
            if (matcher.find())
            {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    /**
     * The values of the string literals of a module, where a procedure is named for a platform
     * call that takes a handler by name.
     *
     * @param lines the module lines
     * @return the values, case-insensitive
     */
    public static Set<String> literalValues(List<String> lines)
    {
        Set<String> values = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String line : lines)
        {
            String code = line;
            int comment = indexOfComment(line);
            if (comment >= 0)
            {
                code = line.substring(0, comment);
            }
            Matcher matcher = LITERAL.matcher(code);
            while (matcher.find())
            {
                values.add(matcher.group(1).replace("\"\"", "\"")); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return values;
    }

    /**
     * The procedures a write removes while the layout or the new module still names them.
     *
     * @param layoutText the layout text; {@code null} for a form without one
     * @param before the module before the write
     * @param after the module after it
     * @return the names, in the order the module declared them; empty when the write is safe
     */
    public static List<String> endangered(String layoutText, List<String> before, List<String> after)
    {
        Set<String> declaredAfter = declaredProcedures(after);
        Set<String> bound = boundProcedures(layoutText);
        Set<String> named = literalValues(after);
        List<String> endangered = new ArrayList<>();
        for (String line : before)
        {
            Matcher matcher = BslModuleAccess.METHOD_START_PATTERN.matcher(line);
            if (!matcher.find())
            {
                continue;
            }
            String name = matcher.group(1);
            if (declaredAfter.contains(name) || endangered.contains(name))
            {
                continue;
            }
            if (bound.contains(name) || named.contains(name))
            {
                endangered.add(name);
            }
        }
        return endangered;
    }

    /**
     * Where a line comment starts, outside a string.
     *
     * @param line the line
     * @return the index of {@code //}, or -1
     */
    private static int indexOfComment(String line)
    {
        boolean inString = false;
        for (int i = 0; i < line.length(); i++)
        {
            char c = line.charAt(i);
            if (c == '"')
            {
                inString = !inString;
            }
            else if (!inString && c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/')
            {
                return i;
            }
        }
        return -1;
    }
}
