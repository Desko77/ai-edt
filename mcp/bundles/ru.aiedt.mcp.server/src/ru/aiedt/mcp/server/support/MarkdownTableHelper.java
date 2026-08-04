/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

/**
 * Makes arbitrary text safe to drop into a Markdown answer.
 * <p>
 * The text a tool reports is not Markdown: it is metadata names, file paths, error messages and BSL
 * fragments, written by whoever wrote the configuration. Put such a value into a table cell as it
 * stands and one stray vertical bar splits the row.
 * </p>
 */
public final class MarkdownTableHelper
{
    private MarkdownTableHelper()
    {
        // utility
    }

    /**
     * Escapes a value for use as a single Markdown table cell.
     * <p>
     * A cell cannot hold the column separator, and it cannot span lines. The bar is escaped, a line
     * feed becomes a space and a carriage return is dropped, so CRLF collapses to one space. Nothing
     * else is touched - in particular a backslash stays a backslash, because cells carry Windows
     * paths and doubling their separators would misreport them.
     * </p>
     *
     * @param text the value; may be <code>null</code>
     * @return the escaped value, never <code>null</code>; the empty string for <code>null</code> input
     */
    public static String escapeForTable(String text)
    {
        if (text == null)
        {
            return ""; //$NON-NLS-1$
        }
        return text.replace("|", "\\|") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("\n", " ") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("\r", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Neutralizes the Markdown formatting characters in free text, so that it renders as written.
     * <p>
     * The backslash is escaped first: it is the escape character, so doing it later would double the
     * backslashes the other steps introduce. Punctuation that only means something at the start of a
     * line ({@code #}, {@code -}, {@code +}) is deliberately left alone - escaping it inside a
     * sentence adds noise for no gain.
     * </p>
     *
     * @param text the value; may be <code>null</code>
     * @return the escaped value, never <code>null</code>; the empty string for <code>null</code> input
     */
    public static String escapeMarkdown(String text)
    {
        if (text == null)
        {
            return ""; //$NON-NLS-1$
        }
        return text.replace("\\", "\\\\") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("*", "\\*") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("_", "\\_") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("`", "\\`") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("[", "\\[") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("]", "\\]") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("<", "\\<") //$NON-NLS-1$ //$NON-NLS-2$
            .replace(">", "\\>"); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
