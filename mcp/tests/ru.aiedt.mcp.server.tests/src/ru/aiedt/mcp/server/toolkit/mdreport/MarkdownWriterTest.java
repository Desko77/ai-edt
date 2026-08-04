/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.mdreport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Covers the builder every metadata report is assembled with.
 * <p>
 * One thing here is not cosmetic. A cell value comes out of the model, and a value containing a pipe
 * would end the cell early - shifting every column after it, so the reader is handed a table that
 * parses cleanly and says something false. Escaping is the reason this class exists rather than a
 * bare StringBuilder, and it is what the tests below hold it to; the rest is layout.
 * </p>
 */
public class MarkdownWriterTest
{
    @Test
    public void aFreshWriterIsEmpty()
    {
        assertEquals("", new MarkdownWriter().toString()); //$NON-NLS-1$
    }

    @Test
    public void theMainHeaderNamesTheTypeAndTheObject()
    {
        MarkdownWriter writer = new MarkdownWriter();

        writer.mainHeader("Catalog", "Warehouses"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("## Catalog: Warehouses\n\n", writer.toString()); //$NON-NLS-1$
    }

    @Test
    public void headingsNestByLevel()
    {
        MarkdownWriter writer = new MarkdownWriter();

        writer.sectionHeader("Attributes"); //$NON-NLS-1$
        writer.subsectionHeader("Tabular section: Goods"); //$NON-NLS-1$

        String out = writer.toString();
        assertTrue(out, out.contains("### Attributes")); //$NON-NLS-1$
        assertTrue("a subsection has to sit one level deeper", //$NON-NLS-1$
            out.contains("#### Tabular section: Goods")); //$NON-NLS-1$
    }

    @Test
    public void aTableHeaderCarriesItsSeparatorRow()
    {
        MarkdownWriter writer = new MarkdownWriter();

        writer.tableHeader("Name", "Type", "Synonym"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        // Without the separator no renderer treats the lines as a table at all.
        assertEquals("| Name | Type | Synonym |\n|---|---|---|\n", writer.toString()); //$NON-NLS-1$
    }

    @Test
    public void aRowIsWrittenCellByCell()
    {
        MarkdownWriter writer = new MarkdownWriter();

        writer.row("Code", "String", "Код"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("| Code | String | Код |\n", writer.toString()); //$NON-NLS-1$
    }

    @Test
    public void aPipeInsideACellIsEscaped()
    {
        // The column-shifting bug: a composite type reads "CatalogRef.A|DocumentRef.B" and would
        // otherwise close its cell in the middle of the value.
        MarkdownWriter writer = new MarkdownWriter();

        writer.row("Owner", "CatalogRef.A|DocumentRef.B"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("| Owner | CatalogRef.A\\|DocumentRef.B |\n", writer.toString()); //$NON-NLS-1$
    }

    @Test
    public void aNewlineInsideACellBecomesASpace()
    {
        // A multi-line comment would otherwise break the row into two, and the second half would be
        // read as a row of its own.
        MarkdownWriter writer = new MarkdownWriter();

        writer.row("Comment", "first line\nsecond line"); //$NON-NLS-1$ //$NON-NLS-2$

        String out = writer.toString();
        assertEquals("| Comment | first line second line |\n", out); //$NON-NLS-1$
        assertEquals("exactly one line was written", 1, out.chars().filter(c -> c == '\n').count()); //$NON-NLS-1$
    }

    @Test
    public void aMissingValueAndAnEmptyValueAreNotTheSameCell()
    {
        MarkdownWriter writer = new MarkdownWriter();

        writer.row("absent", null); //$NON-NLS-1$
        writer.row("blank", ""); //$NON-NLS-1$ //$NON-NLS-2$

        String out = writer.toString();
        assertTrue("a missing value reads as a dash: " + out, out.contains("| absent | - |")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("an empty value stays empty: " + out, out.contains("| blank |  |")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aHeadingIsNotEscaped()
    {
        // Headings are ours, not the model's; escaping them would put backslashes in front of text
        // this code wrote itself.
        MarkdownWriter writer = new MarkdownWriter();

        writer.sectionHeader("Rights | Roles"); //$NON-NLS-1$

        assertFalse(writer.toString(), writer.toString().contains("\\|")); //$NON-NLS-1$
    }

    @Test
    public void bulletsAndLiteralsAndBlankLinesCompose()
    {
        MarkdownWriter writer = new MarkdownWriter();

        writer.bullet("first"); //$NON-NLS-1$
        writer.bullet("second"); //$NON-NLS-1$
        writer.blankLine();
        writer.literal("plain text"); //$NON-NLS-1$

        assertEquals("- first\n- second\n\nplain text", writer.toString()); //$NON-NLS-1$
    }

    @Test
    public void writesAccumulateInOrder()
    {
        MarkdownWriter writer = new MarkdownWriter();

        writer.mainHeader("Document", "Invoice"); //$NON-NLS-1$ //$NON-NLS-2$
        writer.sectionHeader("Attributes"); //$NON-NLS-1$
        writer.tableHeader("Name", "Type"); //$NON-NLS-1$ //$NON-NLS-2$
        writer.row("Date", "Date"); //$NON-NLS-1$ //$NON-NLS-2$

        String out = writer.toString();
        assertTrue(out.indexOf("## Document") < out.indexOf("### Attributes")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(out.indexOf("### Attributes") < out.indexOf("| Name | Type |")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(out.indexOf("| Name | Type |") < out.indexOf("| Date | Date |")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
