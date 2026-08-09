/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.mdreport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Covers reading a large object one part at a time.
 * <p>
 * A document with a hundred attributes, a dozen tabular sections and twenty forms is one answer that
 * has to be read whole to find one attribute. The outline says what an object is made of, and a
 * section list fetches one part of it.
 * </p>
 * <p>
 * The counting is what makes the outline worth anything: it has to report the same number whether or
 * not the body was written, or an agent would have to fetch a section to learn how big it is - which
 * is the cost the outline exists to avoid.
 * </p>
 */
public class SectionSelectionTest
{
    private static Set<String> asked(String... names)
    {
        Set<String> set = new LinkedHashSet<>();
        for (String name : names)
        {
            set.add(name);
        }
        return set;
    }

    /** Writes two sections of two rows each, the shape every test here needs. */
    private static MarkdownWriter twoSections(Set<String> wanted, boolean outline)
    {
        MarkdownWriter writer = new MarkdownWriter();
        writer.selectSections(wanted, outline);
        writer.mainHeader("Catalog", "Products"); //$NON-NLS-1$ //$NON-NLS-2$
        writer.sectionHeader("Attributes"); //$NON-NLS-1$
        writer.tableHeader("Name", "Type"); //$NON-NLS-1$ //$NON-NLS-2$
        writer.row("Price", "Number"); //$NON-NLS-1$ //$NON-NLS-2$
        writer.row("Barcode", "String"); //$NON-NLS-1$ //$NON-NLS-2$
        writer.sectionHeader("Tabular Sections"); //$NON-NLS-1$
        writer.tableHeader("Name"); //$NON-NLS-1$
        writer.row("Goods"); //$NON-NLS-1$
        writer.row("Services"); //$NON-NLS-1$
        return writer;
    }

    @Test
    public void withoutASelectionEverythingIsWritten()
    {
        String written = twoSections(null, false).toString();

        assertTrue(written, written.contains("### Attributes")); //$NON-NLS-1$
        assertTrue(written, written.contains("### Tabular Sections")); //$NON-NLS-1$
        assertTrue(written, written.contains("| Price | Number |")); //$NON-NLS-1$
    }

    @Test
    public void anUnaskedSectionIsLeftOutEntirely()
    {
        // Not just its heading: the rows under it must not leak through either, or the caller gets
        // a table with no heading over it and no way to tell what it lists.
        String written = twoSections(asked("Attributes"), false).toString(); //$NON-NLS-1$

        assertTrue(written, written.contains("### Attributes")); //$NON-NLS-1$
        assertTrue(written, written.contains("| Price | Number |")); //$NON-NLS-1$
        assertFalse(written, written.contains("Tabular Sections")); //$NON-NLS-1$
        assertFalse(written, written.contains("Goods")); //$NON-NLS-1$
    }

    @Test
    public void aSectionIsFoundHoweverItIsSpelled()
    {
        // The headings are built from the model's feature names run through a title-caser, so the
        // name an agent knows the collection by is not the name it is printed under.
        for (String spelling : new String[] { "Tabular Sections", "tabularsections", //$NON-NLS-1$ //$NON-NLS-2$
            "tabular_sections", "TABULAR-SECTIONS" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            String written = twoSections(asked(spelling), false).toString();

            assertTrue(spelling, written.contains("### Tabular Sections")); //$NON-NLS-1$
            assertFalse(spelling, written.contains("Price")); //$NON-NLS-1$
        }
    }

    @Test
    public void theMainHeaderSurvivesAnySelection()
    {
        // Whatever was asked for, the answer has to say which object it is about.
        String written = twoSections(asked("Attributes"), false).toString(); //$NON-NLS-1$

        assertTrue(written, written.startsWith("## Catalog: Products")); //$NON-NLS-1$
    }

    @Test
    public void anOutlineCountsWhatItDoesNotWrite()
    {
        String written = twoSections(null, true).toString();

        assertFalse(written, written.contains("| Price | Number |")); //$NON-NLS-1$
        assertTrue(written, written.contains("| Attributes | 2 |")); //$NON-NLS-1$
        assertTrue(written, written.contains("| Tabular Sections | 2 |")); //$NON-NLS-1$
    }

    @Test
    public void theOutlineCountIsTheSameNumberTheSectionWouldShow()
    {
        // The point of the outline is deciding what to fetch, so its count has to be the count.
        MarkdownWriter full = twoSections(null, false);
        MarkdownWriter outline = twoSections(null, true);

        assertEquals(full.sectionMap(), outline.sectionMap());
        assertEquals(Integer.valueOf(2), outline.sectionMap().get("Attributes")); //$NON-NLS-1$
    }

    @Test
    public void aSectionThatWasAskedForAndIsNotThereIsReported()
    {
        // Otherwise a mistyped name answers with an object that has nothing in it, which reads
        // exactly like an object that IS empty.
        MarkdownWriter writer = twoSections(asked("Forms", "Attributes"), false); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(asked("forms"), writer.unmatchedSelectors()); //$NON-NLS-1$
    }

    @Test
    public void nothingIsUnmatchedWhenNothingWasAsked()
    {
        assertTrue(twoSections(null, false).unmatchedSelectors().isEmpty());
    }

    @Test
    public void aSelectionOfNothingUsableIsTreatedAsNoSelection()
    {
        // A name made only of separators survives a blank test and then matches nothing, so taking
        // it at face value would answer with a bare object header and no sign anything was dropped.
        for (String useless : new String[] { " ", "---", "__", " - _ " }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            String written = twoSections(asked(useless), false).toString();

            assertTrue(useless, written.contains("### Attributes")); //$NON-NLS-1$
            assertTrue(useless, written.contains("### Tabular Sections")); //$NON-NLS-1$
        }
    }

    @Test
    public void anEmptySelectorIsDroppedButItsNeighbourStillSelects()
    {
        MarkdownWriter writer = twoSections(asked("", "Attributes"), false); //$NON-NLS-1$ //$NON-NLS-2$
        String written = writer.toString();

        assertTrue(written, written.contains("### Attributes")); //$NON-NLS-1$
        assertFalse(written, written.contains("Tabular Sections")); //$NON-NLS-1$
        // And the dropped one is not reported as a section that could not be found - there was no
        // name in it to look for.
        assertTrue(writer.unmatchedSelectors().toString(), writer.unmatchedSelectors().isEmpty());
    }

    @Test
    public void theSectionMapKeepsTheOrderTheSectionsCameIn()
    {
        // An agent reads it as a table of contents; alphabetical order would misdescribe the object.
        assertEquals("[Attributes, Tabular Sections]", //$NON-NLS-1$
            twoSections(null, true).sectionMap().keySet().toString());
    }
}
