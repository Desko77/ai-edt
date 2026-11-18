/*
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.List;

/**
 * Builds the single command line {@code CreateProcessW} takes from the argument list
 * {@code ProcessBuilder} would have taken.
 * <p>
 * The shape is the JDK's Windows command line, measured through {@code GetCommandLineW} rather
 * than copied from its source. It is deliberately not the general {@code CommandLineToArgvW}
 * escaping convention:
 * </p>
 * <ul>
 * <li>an argument that is empty or contains a space or tab is surrounded by quotes;</li>
 * <li>an argument with no such whitespace is written as it is, even when it contains quotes;</li>
 * <li>an embedded quote inside a quoted argument is left as it is, not backslash-escaped;</li>
 * <li>a trailing run of backslashes is doubled only in a quoted argument, so it cannot consume
 * the closing quote.</li>
 * </ul>
 * <p>
 * The distinction matters for the 1C thick client: a connection string such as
 * {@code File="E:\base";} has quotes but no whitespace, and the JDK leaves it bare. Wrapping and
 * escaping it changes the token a custom 1C command-line parser sees.
 * </p>
 */
public final class WindowsCommandLine
{
    private WindowsCommandLine()
    {
    }

    /**
     * The command line for one executable and its arguments.
     *
     * @param arguments the executable first, then its arguments
     * @return the command line, quoted where Windows needs it
     */
    public static String join(List<String> arguments)
    {
        StringBuilder line = new StringBuilder();
        for (int index = 0; index < arguments.size(); index++)
        {
            if (index > 0)
            {
                line.append(' ');
            }
            appendArgument(line, arguments.get(index));
        }
        return line.toString();
    }

    private static void appendArgument(StringBuilder line, String argument)
    {
        String value = argument == null ? "" : argument;
        if (!value.isEmpty() && !containsWhitespace(value))
        {
            // The JDK leaves an argument with embedded quotes but no whitespace bare. The 1C
            // connection string relies on exactly that shape.
            line.append(value);
            return;
        }
        line.append('"');
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '\\')
        {
            end--;
        }
        line.append(value, 0, end);
        appendBackslashes(line, (value.length() - end) * 2);
        line.append('"');
    }

    private static boolean containsWhitespace(String value)
    {
        for (int index = 0; index < value.length(); index++)
        {
            char character = value.charAt(index);
            if (character == ' ' || character == '\t')
            {
                return true;
            }
        }
        return false;
    }

    private static void appendBackslashes(StringBuilder line, int count)
    {
        for (int index = 0; index < count; index++)
        {
            line.append('\\');
        }
    }
}
