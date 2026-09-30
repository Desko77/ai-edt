/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.Arrays;
import java.util.List;

/**
 * Reads a boolean the way a person writes one.
 * <p>
 * The spellings, compared without regard to case and after surrounding spaces are removed, are
 * {@code true}/{@code false}, {@code истина}/{@code ложь}, {@code да}/{@code нет},
 * {@code yes}/{@code no} and {@code 1}/{@code 0}. Any other text is refused, and the refusal names
 * every accepted spelling. Storing the unknown text as {@code false} would report a successful
 * write of a value the caller did not ask for.
 * </p>
 */
public final class BooleanLiteral
{
    private static final String[] TRUE_SPELLINGS =
        { "true", "истина", "да", "yes", "1" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    private static final String[] FALSE_SPELLINGS =
        { "false", "ложь", "нет", "no", "0" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    /** Every accepted spelling, in the order a refusal lists them. */
    private static final List<String> ACCEPTED = Arrays.asList(
        "true", "false", "истина", "ложь", "да", "нет", "yes", "no", "1", "0"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$

    private BooleanLiteral()
    {
    }

    /**
     * Parses one boolean literal.
     *
     * @param text the text the caller gave; may be {@code null} or blank
     * @return {@code true} for a true spelling, {@code false} for a false spelling
     * @throws IllegalArgumentException when {@code text} is not an accepted spelling; the message
     *             lists them
     */
    public static boolean parse(String text)
    {
        String token = text == null ? "" : text.trim(); //$NON-NLS-1$
        if (matches(token, TRUE_SPELLINGS))
        {
            return true;
        }
        if (matches(token, FALSE_SPELLINGS))
        {
            return false;
        }
        throw new IllegalArgumentException(
            TextSuggest.invalidValue("boolean value", token, ACCEPTED)); //$NON-NLS-1$
    }

    /**
     * Whether {@code token} equals one of {@code spellings}, ignoring case.
     *
     * @param token the trimmed text, never {@code null}
     * @param spellings the spellings of one boolean, never {@code null}
     * @return {@code true} when {@code token} is one of them
     */
    private static boolean matches(String token, String[] spellings)
    {
        for (String spelling : spellings)
        {
            if (spelling.equalsIgnoreCase(token))
            {
                return true;
            }
        }
        return false;
    }
}
