/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.mdreport;

/**
 * The markdown a metadata answer is made of, down to the byte.
 * <p>
 * Every shape in here is read by an agent and is therefore a contract, not a preference. Three of them
 * are easy to break by accident and worth stating outright:
 * </p>
 * <ul>
 * <li>a section header carries a LEADING line feed and a trailing blank line. That leading feed is the
 * only thing separating the previous table from the next heading, because a table is never followed by a
 * blank line of its own. Two headers in a row therefore leave two blank lines between them, and that is
 * correct;</li>
 * <li>a table separator is bare pipes and dashes - {@code |---|---|} - with no padding and no alignment
 * colons;</li>
 * <li>a cell is escaped, a heading is not, and a bullet is not. Only a cell can be split by a stray
 * pipe.</li>
 * </ul>
 * <p>
 * The escaping is deliberately narrow: a pipe would end the cell and a line feed would end the row, so
 * both are neutralized, and nothing else is. A carriage return survives, and so do markdown's own
 * metacharacters - an asterisk in a synonym reaches the agent as emphasis. That is the wire as it
 * stands, and widening the escaping here would silently rewrite every comment and synonym in every
 * answer. {@code MarkdownTableHelper.escapeForTable} is close but not the same: it drops the carriage return
 * and renders an absent value as nothing, where this renders it as a dash.
 * </p>
 */
final class MarkdownWriter
{
    /** What an absent value looks like in a cell. */
    private static final String DASH = "-"; //$NON-NLS-1$

    private final StringBuilder out = new StringBuilder();

    /**
     * Writes the heading the whole answer hangs off.
     * <p>
     * The name is written as it comes, unescaped: a nameless object shows up as the four characters
     * {@code null}, which is what the callers have always been handed and is at least honest about the
     * model being broken.
     * </p>
     *
     * @param type the metadata type, as the model names it - {@code Catalog}, not {@code CatalogImpl}
     * @param name the object's name; may be <code>null</code>
     */
    void mainHeader(String type, String name)
    {
        out.append("## ").append(type).append(": ").append(name).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Opens a section.
     *
     * @param title the heading text, written unescaped
     */
    void sectionHeader(String title)
    {
        out.append("\n### ").append(title).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Opens a subsection - one tabular section inside the tabular sections.
     *
     * @param title the heading text, written unescaped
     */
    void subsectionHeader(String title)
    {
        out.append("\n#### ").append(title).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Writes a table's header row and the separator under it.
     *
     * @param headers the column titles, written unescaped - they are ours, not the model's
     */
    void tableHeader(String... headers)
    {
        out.append("| "); //$NON-NLS-1$
        for (int column = 0; column < headers.length; column++)
        {
            if (column > 0)
            {
                out.append(" | "); //$NON-NLS-1$
            }
            out.append(headers[column]);
        }
        out.append(" |\n|"); //$NON-NLS-1$
        for (int column = 0; column < headers.length; column++)
        {
            out.append("---|"); //$NON-NLS-1$
        }
        out.append("\n"); //$NON-NLS-1$
    }

    /**
     * Writes one table row. The width is not checked against the header: the callers keep them in step.
     *
     * @param cells the values, each escaped; a <code>null</code> becomes a dash and an empty string
     *            becomes an empty cell, and those two are not the same thing anywhere in this subsystem
     */
    void row(String... cells)
    {
        out.append("| "); //$NON-NLS-1$
        for (int column = 0; column < cells.length; column++)
        {
            if (column > 0)
            {
                out.append(" | "); //$NON-NLS-1$
            }
            out.append(escapeCell(cells[column]));
        }
        out.append(" |\n"); //$NON-NLS-1$
    }

    /**
     * Writes a bullet of a list. Unescaped: a list item cannot be split by a pipe the way a cell can.
     *
     * @param text the item
     */
    void bullet(String text)
    {
        out.append("- ").append(text).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Writes text exactly as given, for the few places that are neither heading, row nor bullet.
     *
     * @param text the markdown
     */
    void literal(String text)
    {
        out.append(text);
    }

    /**
     * Ends the current line group with a blank line.
     */
    void blankLine()
    {
        out.append("\n"); //$NON-NLS-1$
    }

    /**
     * Makes a value safe to sit in a single cell.
     *
     * @param value the value; may be <code>null</code>
     * @return the escaped value, or a dash when there was none
     */
    private static String escapeCell(String value)
    {
        if (value == null)
        {
            return DASH;
        }
        return value.replace("|", "\\|") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("\n", " "); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Returns everything written so far.
     *
     * @return the markdown, never <code>null</code>
     */
    @Override
    public String toString()
    {
        return out.toString();
    }
}
