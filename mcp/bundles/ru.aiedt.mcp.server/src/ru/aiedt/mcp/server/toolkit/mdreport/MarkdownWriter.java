/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.mdreport;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

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
     * The sections asked for, normalized, or <code>null</code> when the whole object was asked for.
     */
    private Set<String> wanted;

    /** Whether only the section map is wanted, with no section bodies. */
    private boolean outlineOnly;

    /** The section being written, by its heading; <code>null</code> before the first one opens. */
    private String currentSection;

    /** Whether what is being written now belongs to a section that was asked for. */
    private boolean sectionWanted = true;

    /** Every section seen, in the order seen, against the number of rows it holds. */
    private final Map<String, Integer> sectionRows = new LinkedHashMap<>();

    /** Which of the asked-for names have been seen. A name never seen is a caller's mistake. */
    private final Set<String> matched = new LinkedHashSet<>();

    /**
     * Narrows what gets written.
     * <p>
     * Sections are counted whether or not they are written, because the count is the whole point of the
     * outline and because a caller who asked for one section still deserves to be told what else is
     * there. Call before writing anything.
     * </p>
     *
     * @param sections the section headings to keep, matched case- and space-insensitively; <code>null</code>
     *            or empty keeps every section
     * @param outline <code>true</code> to write no section bodies at all and answer with the section map
     */
    void selectSections(Set<String> sections, boolean outline)
    {
        Set<String> normalized = sections == null ? null : normalizeAll(sections);
        // A selection that normalizes away to nothing - [" "], ["---"] - is not a selection of
        // nothing, it is a caller who selected nothing usable. Suppressing every section for it
        // would answer with a bare object header and no hint that anything was dropped, so it is
        // read as "no selection given" instead: too much is recoverable, silence is not.
        this.wanted = normalized == null || normalized.isEmpty() ? null : normalized;
        this.outlineOnly = outline;
    }

    /**
     * The asked-for section names that no section answered to.
     *
     * @return the unmatched names, normalized; empty when everything asked for was found
     */
    Set<String> unmatchedSelectors()
    {
        if (wanted == null)
        {
            return Collections.emptySet();
        }
        Set<String> missing = new LinkedHashSet<>(wanted);
        missing.removeAll(matched);
        return missing;
    }

    /**
     * Every section this object turned out to have, against how many rows each holds.
     *
     * @return the section map in the order the sections were written, never <code>null</code>
     */
    Map<String, Integer> sectionMap()
    {
        return Collections.unmodifiableMap(sectionRows);
    }

    /**
     * Whether the writer is currently letting output through.
     *
     * @return <code>false</code> while inside a section that was not asked for, or in outline mode
     */
    private boolean writing()
    {
        return sectionWanted && !outlineOnly;
    }

    private static Set<String> normalizeAll(Set<String> names)
    {
        Set<String> normalized = new LinkedHashSet<>();
        for (String name : names)
        {
            if (name == null)
            {
                continue;
            }
            String key = normalize(name);
            // Checked AFTER normalizing, not before: normalizing strips the separators, so a name
            // made only of them survives a blank test and then matches no section ever written.
            if (!key.isEmpty())
            {
                normalized.add(key);
            }
        }
        return normalized;
    }

    /**
     * Reduces a heading to what a caller can be expected to type.
     * <p>
     * The headings come from the model's own feature names run through a title-caser, so
     * {@code tabularSections} reaches the wire as "Tabular Sections". Asking for it back in any of the
     * three spellings has to work, or the parameter is a guessing game.
     * </p>
     *
     * @param name a heading or a caller's selector
     * @return the comparable form
     */
    private static String normalize(String name)
    {
        StringBuilder squeezed = new StringBuilder(name.length());
        for (int index = 0; index < name.length(); index++)
        {
            char character = name.charAt(index);
            if (!Character.isWhitespace(character) && character != '_' && character != '-')
            {
                squeezed.append(Character.toLowerCase(character));
            }
        }
        return squeezed.toString();
    }

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
        currentSection = title;
        sectionRows.putIfAbsent(title, Integer.valueOf(0));
        String key = normalize(title);
        sectionWanted = wanted == null || wanted.contains(key);
        if (wanted != null && wanted.contains(key))
        {
            matched.add(key);
        }
        if (!writing())
        {
            return;
        }
        out.append("\n### ").append(title).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Opens a subsection - one tabular section inside the tabular sections.
     *
     * @param title the heading text, written unescaped
     */
    void subsectionHeader(String title)
    {
        if (!writing())
        {
            return;
        }
        out.append("\n#### ").append(title).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Writes a table's header row and the separator under it.
     *
     * @param headers the column titles, written unescaped - they are ours, not the model's
     */
    void tableHeader(String... headers)
    {
        if (!writing())
        {
            return;
        }
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
        if (currentSection != null)
        {
            // Counted even when suppressed: the count is what the outline answers with, and it has to
            // be the same number whether or not the body was asked for.
            sectionRows.merge(currentSection, Integer.valueOf(1), Integer::sum);
        }
        if (!writing())
        {
            return;
        }
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
        if (currentSection != null)
        {
            sectionRows.merge(currentSection, Integer.valueOf(1), Integer::sum);
        }
        if (!writing())
        {
            return;
        }
        out.append("- ").append(text).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Writes text exactly as given, for the few places that are neither heading, row nor bullet.
     *
     * @param text the markdown
     */
    void literal(String text)
    {
        if (!writing())
        {
            return;
        }
        out.append(text);
    }

    /**
     * Ends the current line group with a blank line.
     */
    void blankLine()
    {
        if (!writing())
        {
            return;
        }
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
        if (!outlineOnly)
        {
            return out.toString();
        }
        return out.toString() + outlineTable();
    }

    /**
     * Renders the section map: what this object is made of and how big each part is.
     * <p>
     * This is the whole answer in outline mode. It exists so an agent can find out that an object has
     * eighty attributes and four forms without reading eighty rows to learn it, and then ask for the
     * one part it needs.
     * </p>
     *
     * @return the table, opening with its own heading
     */
    private String outlineTable()
    {
        StringBuilder table = new StringBuilder();
        table.append("\n### Sections\n\n"); //$NON-NLS-1$
        table.append("| Section | Rows |\n|---|---|\n"); //$NON-NLS-1$
        for (Map.Entry<String, Integer> entry : sectionRows.entrySet())
        {
            table.append("| ").append(entry.getKey()).append(" | ") //$NON-NLS-1$ //$NON-NLS-2$
                .append(entry.getValue()).append(" |\n"); //$NON-NLS-1$
        }
        table.append("\nAsk for one with sections=[\"<name>\"].\n"); //$NON-NLS-1$
        return table.toString();
    }
}
