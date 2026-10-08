/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.moxel.Cell;
import com._1c.g5.v8.dt.moxel.MoxelFactory;
import com._1c.g5.v8.dt.moxel.Row;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.moxel.sheet.SheetFactory;
import com._1c.g5.v8.dt.moxel.util.V8MoxelSerializer;

/**
 * The extent a template declares, and how the writes make it follow the content.
 * <p>
 * A spreadsheet file carries two numbers a 1C session reads back as the size of the table: the
 * declared height, serialized as {@code <height>} and omitted while it is zero, and the declared
 * size of the document's column set, serialized as {@code <columns><size>}. A sheet whose cells
 * were written but whose declared extent was left at zero answers {@code ВысотаТаблицы} and
 * {@code ШиринаТаблицы} of zero while its cells are all there - the content is written, the table
 * is not.
 * </p>
 * <p>
 * Every write that changes the composition of the sheet therefore settles the declared extent: the
 * height becomes the last row a reader reaches and the column set size the last column one reaches.
 * The measure is what a read lists - a non-empty text, a parameter, a parameter or template fill, a
 * turned text - plus the merges, named areas, groups, drawings and print areas; presentation alone
 * is not content, so a row a format walked over or a cell holding an empty string declares nothing.
 * The numbers only grow in a write - a removal lowers them through the declared numbers the row and
 * column removals already carry - so a template that already declares a larger extent than its
 * content occupies keeps it.
 * </p>
 * <p>
 * The numbers asserted here are the ones the platform's own serializer reads out of the model: the
 * height element is written from {@code SpreadsheetDocument.getHeight()} and the size element from
 * {@code Columns.getSize()}, so a document declaring neither writes neither. The file side is
 * covered by a corpus template with the two elements put into its text, read back through the
 * platform deserializer, changed and read again. Writing a document out through that serializer is
 * not covered here: in the test runtime its write does not return.
 * </p>
 * <p>
 * What no test here can answer is what a 1C session reports through {@code ВысотаТаблицы} for such
 * a file. That is a live check.
 * </p>
 */
public class AnExtentOfATemplateFollowsItsContentTest
{
    /**
     * The project the serializer is opened with. It insists that one is there and asks it nothing
     * about a template of numbers and text, so a stand-in answering every call with null carries
     * both directions of the read and the write.
     */
    private static IDtProject anyProject()
    {
        return (IDtProject)Proxy.newProxyInstance(IDtProject.class.getClassLoader(),
            new Class<?>[]{ IDtProject.class }, (proxy, method, args) -> null);
    }

    /** A document the way the helper creates one: the factory's own empty sheet. */
    private static SpreadsheetDocument empty()
    {
        return MoxelFactory.eINSTANCE.createSpreadsheetDocument();
    }

    /** The text of a corpus file, to be altered and read back as a file of the test's own. */
    private static String textOf(String name) throws Exception
    {
        try (InputStream stream = AnExtentOfATemplateFollowsItsContentTest.class.getResourceAsStream(name))
        {
            assertNotNull("the corpus file is missing: " + name, stream); //$NON-NLS-1$
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * The document a template file holds, read the way EDT reads a template: the factory's empty
     * document read into by the platform deserializer.
     */
    private static SpreadsheetDocument reread(String xml) throws Exception
    {
        SpreadsheetDocument doc = SheetFactory.createSpreadsheetDocument();
        try (InputStream stream = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
        {
            new V8MoxelSerializer(anyProject(), doc).deserializeXML(stream);
        }
        return doc;
    }

    /**
     * A corpus template file made to declare the extent given, with everything else it carries
     * left as it is: the extent sits before the column set, the way the serializer writes it.
     */
    private static SpreadsheetDocument aFileDeclaring(String name, int rows, int columns) throws Exception
    {
        String xml = textOf(name).replaceFirst("(?s)<columns>\\s*<size>\\d+</size>", //$NON-NLS-1$
            "<height>" + rows + "</height><columns><size>" + columns + "</size>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return reread(xml);
    }

    /** The extent a document declares, and the counts the read of it answers with. */
    private static void assertExtent(SpreadsheetDocument doc, int rows, int columns)
    {
        assertEquals("the declared height", rows, doc.getHeight()); //$NON-NLS-1$
        assertNotNull("a sheet with a column carries its set", doc.getColumns()); //$NON-NLS-1$
        assertEquals("the declared column set size", columns, doc.getColumns().getSize()); //$NON-NLS-1$

        Map<String, Object> read = BmTemplateHelper.readSpreadsheet(doc, "ru"); //$NON-NLS-1$
        assertEquals("the rows the read answers with", Integer.valueOf(rows), read.get("rowCount")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the columns the read answers with", Integer.valueOf(columns), //$NON-NLS-1$
            read.get("colCount")); //$NON-NLS-1$
    }

    /** The text a document holds in a cell, or {@code null} when the cell is not there. */
    private static String cellTextAt(SpreadsheetDocument doc, int row, int col)
    {
        Row held = doc.getRows().get(Integer.valueOf(row - 1));
        Cell cell = held == null ? null : held.getCells().get(Integer.valueOf(col - 1));
        return cell == null || cell.getText() == null ? null : cell.getText().getContent().get("ru"); //$NON-NLS-1$
    }

    /**
     * A cell written into an empty template declares the table its row and its column end at, and
     * the read of the same document answers with those counts.
     */
    @Test
    public void aCellWrittenIntoAnEmptyTemplateDeclaresItsRowAndItsColumn()
    {
        SpreadsheetDocument doc = empty();

        BmTemplateHelper.setCellText(doc, 3, 2, "Итого", "ru"); //$NON-NLS-1$ //$NON-NLS-2$

        assertExtent(doc, 3, 2);
        assertEquals("Итого", cellTextAt(doc, 3, 2)); //$NON-NLS-1$
    }

    /**
     * A write that leaves the cell as empty as it found it declares nothing: an empty string on an
     * empty template is a cell the file carries, not a table.
     */
    @Test
    public void aWriteThatAddsNoCellLeavesTheEmptyTemplateEmpty()
    {
        SpreadsheetDocument doc = empty();
        BmTemplateHelper.ensureColumnSet(doc);

        BmTemplateHelper.setCellText(doc, 4, 4, null, "ru"); //$NON-NLS-1$

        assertEquals("an empty sheet declares no rows", 0, doc.getHeight()); //$NON-NLS-1$
        assertEquals("and no columns", 0, doc.getColumns().getSize()); //$NON-NLS-1$
        assertExtent(doc, 0, 0);
    }

    /** Rows inserted into a template raise the declared height to hold the rows that moved down. */
    @Test
    public void rowsInsertedIntoATemplateRaiseTheDeclaredHeight()
    {
        SpreadsheetDocument doc = empty();
        BmTemplateHelper.setCellText(doc, 1, 1, "Шапка", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        BmTemplateHelper.setCellText(doc, 3, 1, "Итог", "ru"); //$NON-NLS-1$ //$NON-NLS-2$

        BmTemplateHelper.RowOutcome outcome = BmTemplateHelper.insertRows(doc, 3, 2, "none"); //$NON-NLS-1$

        assertNull(outcome.error);
        assertEquals("the row of the last cell moved below the two inserted rows", 5, //$NON-NLS-1$
            outcome.lastRow);
        assertExtent(doc, 5, 1);
        assertEquals("Итог", cellTextAt(doc, 5, 1)); //$NON-NLS-1$
        assertNull("the rows the insert added are empty", cellTextAt(doc, 3, 1)); //$NON-NLS-1$
    }

    /** Rows deleted from a template lower the declared height to the last cell that is left. */
    @Test
    public void rowsDeletedFromATemplateLowerTheDeclaredHeight()
    {
        SpreadsheetDocument doc = empty();
        for (int row = 1; row <= 4; row++)
        {
            BmTemplateHelper.setCellText(doc, row, 1, "Строка " + row, "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        assertExtent(doc, 4, 1);

        BmTemplateHelper.RowOutcome outcome = BmTemplateHelper.deleteRows(doc, 2, 2);

        assertNull(outcome.error);
        assertExtent(doc, 2, 1);
        assertEquals("Строка 1", cellTextAt(doc, 1, 1)); //$NON-NLS-1$
        assertEquals("Строка 4", cellTextAt(doc, 2, 1)); //$NON-NLS-1$
    }

    /** Columns inserted before a cell carry the declared width with them. */
    @Test
    public void columnsInsertedIntoATemplateRaiseTheDeclaredWidth()
    {
        SpreadsheetDocument doc = empty();
        BmTemplateHelper.setCellText(doc, 1, 1, "Код", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        BmTemplateHelper.setCellText(doc, 1, 3, "Сумма", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        assertExtent(doc, 1, 3);

        BmTemplateHelper.ColumnOutcome outcome = BmTemplateHelper.insertColumns(doc, 2, 2,
            "none"); //$NON-NLS-1$

        assertNull(outcome.error);
        assertExtent(doc, 1, 5);
        assertEquals("Сумма", cellTextAt(doc, 1, 5)); //$NON-NLS-1$
        assertNull("the columns the insert added are empty", cellTextAt(doc, 1, 2)); //$NON-NLS-1$
    }

    /** Columns deleted from a template lower the declared width to the last cell that is left. */
    @Test
    public void columnsDeletedFromATemplateLowerTheDeclaredWidth()
    {
        SpreadsheetDocument doc = empty();
        BmTemplateHelper.setCellText(doc, 1, 1, "Код", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        BmTemplateHelper.setCellText(doc, 1, 2, "Товар", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        BmTemplateHelper.setCellText(doc, 1, 3, "Сумма", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        assertExtent(doc, 1, 3);

        BmTemplateHelper.ColumnOutcome outcome = BmTemplateHelper.deleteColumns(doc, 2, 2);

        assertNull(outcome.error);
        assertExtent(doc, 1, 1);
        assertEquals("Код", cellTextAt(doc, 1, 1)); //$NON-NLS-1$
    }

    /** Rows copied past the end of a template raise the declared height to hold the copies. */
    @Test
    public void rowsCopiedPastTheEndRaiseTheDeclaredHeight()
    {
        SpreadsheetDocument doc = empty();
        BmTemplateHelper.setCellText(doc, 1, 1, "Шапка", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        BmTemplateHelper.setCellText(doc, 2, 1, "Товар", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        assertExtent(doc, 2, 1);

        BmTemplateHelper.RowOutcome outcome = BmTemplateHelper.copyRows(doc, 1, 4, 2);

        assertNull(outcome.error);
        assertExtent(doc, 5, 1);
        assertEquals("Шапка", cellTextAt(doc, 4, 1)); //$NON-NLS-1$
        assertEquals("Товар", cellTextAt(doc, 5, 1)); //$NON-NLS-1$
    }

    /** A merge reaching below the content declares the rows and columns it covers. */
    @Test
    public void aMergeBelowTheContentDeclaresTheRowsItReaches()
    {
        SpreadsheetDocument doc = empty();
        BmTemplateHelper.setCellText(doc, 1, 1, "Итого", "ru"); //$NON-NLS-1$ //$NON-NLS-2$

        BmTemplateHelper.mergeCells(doc, 1, 1, 6, 3);

        assertExtent(doc, 6, 3);
    }

    /**
     * A template file that declares its extent keeps it through a write inside it, and grows only
     * when a write reaches past it. The file is a corpus template with the extent put into it, so
     * the numbers are read back out of the file rather than asserted on a document built in code.
     */
    @Test
    public void aFileThatDeclaresItsExtentKeepsItAndGrowsOnlyPastIt() throws Exception
    {
        SpreadsheetDocument doc = aFileDeclaring("template-text.mxlx", 9, 7); //$NON-NLS-1$
        assertExtent(doc, 9, 7);

        BmTemplateHelper.setCellText(doc, 2, 2, "Товар", "ru"); //$NON-NLS-1$ //$NON-NLS-2$

        assertExtent(doc, 9, 7);

        BmTemplateHelper.setCellText(doc, 11, 2, "Всего", "ru"); //$NON-NLS-1$ //$NON-NLS-2$

        assertExtent(doc, 11, 7);
        assertEquals("Всего", cellTextAt(doc, 11, 2)); //$NON-NLS-1$
    }

    /**
     * A copy whose source row is inside the declared height but was never materialized still
     * carries the declaration to the last row the call names. The source row copies as an empty
     * row, so no content arrives to grow the extent by itself, and the target sits past the
     * declared end: the document grows to hold it, the way a copy past the end always did.
     */
    @Test
    public void aCopyPastTheEndGrowsTheDeclaredHeightEvenWhenTheSourceRowIsEmpty()
    {
        SpreadsheetDocument doc = empty();
        BmTemplateHelper.setCellText(doc, 1, 1, "Шапка", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        doc.setHeight(4);

        BmTemplateHelper.RowOutcome outcome = BmTemplateHelper.copyRows(doc, 3, 6, 1);

        assertNull(outcome.error);
        assertEquals("the declared height reaches the last row the copy names", 6, //$NON-NLS-1$
            doc.getHeight());
        assertExtent(doc, 6, 1);
        assertNull("the row the copy replaced holds nothing", cellTextAt(doc, 6, 1)); //$NON-NLS-1$
    }

    /**
     * A cell written with an empty text far down the sheet declares nothing, however far out it
     * sits: the write materializes a cell, and a cell whose text is empty is what a read skips.
     * The next write settles the extent to the content that is really there, not to the
     * coordinate the empty write reached.
     */
    @Test
    public void anEmptyCellWrittenFarOutDoesNotDeclareATable()
    {
        SpreadsheetDocument doc = empty();

        BmTemplateHelper.setCellText(doc, 100, 100, "", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        BmTemplateHelper.setCellText(doc, 1, 2, "Итого", "ru"); //$NON-NLS-1$ //$NON-NLS-2$

        assertExtent(doc, 1, 2);
        assertEquals("Итого", cellTextAt(doc, 1, 2)); //$NON-NLS-1$
    }

    /** Rows inserted and then deleted leave the declared height where it started. */
    @Test
    public void rowsInsertedAndDeletedReturnTheHeightTheyStartedWith()
    {
        SpreadsheetDocument doc = empty();
        BmTemplateHelper.setCellText(doc, 1, 1, "Шапка", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        BmTemplateHelper.setCellText(doc, 3, 1, "Итог", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        assertExtent(doc, 3, 1);

        assertNull(BmTemplateHelper.insertRows(doc, 2, 2, "none").error); //$NON-NLS-1$
        assertExtent(doc, 5, 1);

        assertNull(BmTemplateHelper.deleteRows(doc, 2, 2).error);
        assertExtent(doc, 3, 1);
        assertEquals("Шапка", cellTextAt(doc, 1, 1)); //$NON-NLS-1$
        assertEquals("Итог", cellTextAt(doc, 3, 1)); //$NON-NLS-1$
    }

    /** Columns inserted and then deleted leave the declared width where it started. */
    @Test
    public void columnsInsertedAndDeletedReturnTheWidthTheyStartedWith()
    {
        SpreadsheetDocument doc = empty();
        BmTemplateHelper.setCellText(doc, 1, 1, "Код", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        BmTemplateHelper.setCellText(doc, 1, 3, "Сумма", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        assertExtent(doc, 1, 3);

        assertNull(BmTemplateHelper.insertColumns(doc, 2, 2, "none").error); //$NON-NLS-1$
        assertExtent(doc, 1, 5);

        assertNull(BmTemplateHelper.deleteColumns(doc, 2, 2).error);
        assertExtent(doc, 1, 3);
        assertEquals("Код", cellTextAt(doc, 1, 1)); //$NON-NLS-1$
        assertEquals("Сумма", cellTextAt(doc, 1, 3)); //$NON-NLS-1$
    }
}
