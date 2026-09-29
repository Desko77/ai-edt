/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels;

import java.util.regex.Pattern;

/**
 * Compiles the text a user types into the marker panel search.
 * <p>
 * Marker names and object names in a 1C project are written in Cyrillic as often as in Latin, and
 * the search is case-insensitive. Java folds only ASCII when {@link Pattern#CASE_INSENSITIVE} is
 * set alone, so a lowercase Russian query would miss {@code Catalog.Товары}. {@link Pattern#UNICODE_CASE}
 * is what makes that query match.
 * </p>
 */
public final class MarkerSearch
{
    private MarkerSearch()
    {
        // Static helpers.
    }

    /**
     * Compiles a search expression with Unicode case folding.
     *
     * @param text the query the user typed; not empty
     * @return the compiled pattern
     * @throws java.util.regex.PatternSyntaxException when {@code text} is not a valid expression
     */
    public static Pattern compile(String text)
    {
        return Pattern.compile(text, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
