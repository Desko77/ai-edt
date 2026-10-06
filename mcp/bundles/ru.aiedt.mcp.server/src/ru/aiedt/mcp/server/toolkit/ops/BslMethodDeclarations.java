/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * The methods a BSL module text declares, read off the text the way the compiler reads it: names in
 * comments and in string literals declare nothing.
 * <p>
 * A search for a handler name in the raw text answers a different question - {@code GetAll}
 * contains {@code Get}, and a mention in a comment or a literal swallows the check whole. This
 * reader walks the text line by line, keeping track of whether it stands inside a string literal
 * (a literal continues on the next line only when that line opens with {@code |}) and matches
 * method headers on what is left. A module whose text cannot be read this way - a string literal
 * that is never closed - fails instead of answering, because an answer built over a broken module
 * is a guess.
 * </p>
 */
public final class BslMethodDeclarations
{
    private BslMethodDeclarations()
    {
    }

    /** One declared method: its name and whether it is a function. */
    public static final class Method
    {
        /** The method name as the module declares it. */
        public final String name;

        /** True for a function, false for a procedure. */
        public final boolean function;

        Method(String name, boolean function)
        {
            this.name = name;
            this.function = function;
        }
    }

    /** The module text cannot be read as module text. */
    public static final class ParseFailure extends Exception
    {
        private static final long serialVersionUID = 1L;

        ParseFailure(String message)
        {
            super(message);
        }
    }

    /**
     * Reads the methods a module text declares, in document order. A leading byte-order mark,
     * which a raw byte-to-text decode keeps as U+FEFF, is read past, so a declaration on the
     * module's first line counts.
     *
     * @param moduleText the module text, in either line-break convention, with or without a
     *            leading byte-order mark
     * @return the declared methods
     * @throws ParseFailure when the text cannot be read as module text
     */
    public static List<Method> parse(String moduleText) throws ParseFailure
    {
        List<Method> methods = new ArrayList<>();
        if (moduleText == null || moduleText.isEmpty())
        {
            return methods;
        }
        if (moduleText.charAt(0) == '﻿')
        {
            moduleText = moduleText.substring(1);
            if (moduleText.isEmpty())
            {
                return methods;
            }
        }
        String[] lines = moduleText.replace("\r\n", "\n").split("\n", -1); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        boolean inString = false;
        int stringOpenLine = 0;
        for (int i = 0; i < lines.length; i++)
        {
            String line = lines[i];
            if (inString)
            {
                String continued = line.trim();
                if (continued.isEmpty() || continued.charAt(0) != '|')
                {
                    throw new ParseFailure("the module does not parse: a string literal opened on " //$NON-NLS-1$
                        + "line " + stringOpenLine + " is never closed"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                line = line.substring(line.indexOf('|') + 1);
            }
            StringBuilder code = new StringBuilder(line.length());
            for (int j = 0; j < line.length();)
            {
                char symbol = line.charAt(j);
                if (inString)
                {
                    if (symbol == '"')
                    {
                        if (j + 1 < line.length() && line.charAt(j + 1) == '"')
                        {
                            j += 2;
                        }
                        else
                        {
                            inString = false;
                            j++;
                        }
                    }
                    else
                    {
                        j++;
                    }
                }
                else if (symbol == '"')
                {
                    // A literal leaves a placeholder, so the code around it keeps its shape.
                    code.append("\"\""); //$NON-NLS-1$
                    inString = true;
                    stringOpenLine = i + 1;
                    j++;
                }
                else if (symbol == '/' && j + 1 < line.length() && line.charAt(j + 1) == '/')
                {
                    break;
                }
                else
                {
                    code.append(symbol);
                    j++;
                }
            }
            if (!inString)
            {
                String cleaned = code.toString();
                Matcher header = BslModuleAccess.METHOD_START_PATTERN.matcher(cleaned);
                if (header.find())
                {
                    methods.add(new Method(header.group(1),
                        BslModuleAccess.FUNC_KEYWORD_PATTERN.matcher(cleaned).find()));
                }
            }
        }
        if (inString)
        {
            throw new ParseFailure("the module does not parse: a string literal opened on line " //$NON-NLS-1$
                + stringOpenLine + " is never closed"); //$NON-NLS-1$
        }
        return methods;
    }

    /**
     * Whether the module declares a function of exactly this name, whatever the case of either.
     *
     * @param moduleText the module text
     * @param name the method name the caller asked about
     * @return true when a function of that name is declared
     * @throws ParseFailure when the text cannot be read as module text
     */
    public static boolean declaresFunction(String moduleText, String name) throws ParseFailure
    {
        for (Method method : parse(moduleText))
        {
            if (method.function && method.name.equalsIgnoreCase(name))
            {
                return true;
            }
        }
        return false;
    }
}
