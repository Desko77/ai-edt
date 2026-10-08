/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.moxel.Cell;
import com._1c.g5.v8.dt.moxel.NamedItemCells;
import com._1c.g5.v8.dt.moxel.Rect;
import com._1c.g5.v8.dt.moxel.RectArea;
import com._1c.g5.v8.dt.moxel.Row;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.moxel.sheet.SheetFactory;
import com._1c.g5.v8.dt.moxel.util.V8MoxelSerializer;

/**
 * Templates that arrived as files, read with the reader EDT itself uses.
 * <p>
 * The other tests of this helper assemble their model in memory, and an assembled model carries
 * the same convention as the code that reads it. A file carries the platform's convention instead:
 * the numbers in it are the numbers the platform wrote, read back by the platform's own
 * deserializer. Four small templates travel with this class as .mxlx resources - areas, merges,
 * rows and columns, and text - and each one is parsed through {@link V8MoxelSerializer}, so what
 * the helper reads is pinned to a file rather than to a model this suite built.
 * </p>
 * <p>
 * Coordinates on the helper are 1-based and 0-based in the moxel model, the split every other
 * coordinate here lives with.
 * </p>
 */
public class ARealTemplateFileIsReadAndChangedTest
{
    /**
     * The project the deserializer is opened with. It insists that one is there; a template of
     * numbers and text asks it nothing further, so a stand-in that answers every call with null
     * carries the read.
     */
    private static IDtProject anyProject()
    {
        return (IDtProject)Proxy.newProxyInstance(IDtProject.class.getClassLoader(),
            new Class<?>[]{ IDtProject.class }, (proxy, method, args) -> null);
    }

    /**
     * The document a corpus file holds, parsed the way EDT parses a template: the factory's empty
     * document - which brings the default format index and its format - read into by the platform
     * deserializer.
     */
    private static SpreadsheetDocument fileOf(String name) throws Exception
    {
        SpreadsheetDocument doc = SheetFactory.createSpreadsheetDocument();
        try (InputStream stream = ARealTemplateFileIsReadAndChangedTest.class.getResourceAsStream(name))
        {
            assertNotNull("the corpus file is missing: " + name, stream); //$NON-NLS-1$
            new V8MoxelSerializer(anyProject(), doc).deserializeXML(stream);
        }
        return doc;
    }

    /** A cell's text as the file carries it, or {@code null} when the cell is not there. */
    private static String textAt(SpreadsheetDocument doc, int row, int col)
    {
        Row held = doc.getRows().get(Integer.valueOf(row - 1));
        Cell cell = held == null ? null : held.getCells().get(Integer.valueOf(col - 1));
        return cell == null || cell.getText() == null
            ? null : cell.getText().getContent().get("ru"); //$NON-NLS-1$
    }

    /** The named area's reading, or a failure naming the area that is not there. */
    private static Map<String, Object> areaNamed(SpreadsheetDocument doc, String name)
    {
        for (Map<String, Object> area : BmTemplateHelper.listNamedAreas(doc))
        {
            if (name.equals(area.get("name"))) //$NON-NLS-1$
            {
                return area;
            }
        }
        throw new AssertionError("no area named " + name); //$NON-NLS-1$
    }

    /** The merges of a document as read corners, in the order the file carries them. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mergesOf(SpreadsheetDocument doc)
    {
        return (List<Map<String, Object>>)BmTemplateHelper.readSpreadsheet(doc, "ru").get("merges"); //$NON-NLS-1$
    }

    /** The position the model holds for a named area of the file. */
    private static Rect positionOf(SpreadsheetDocument doc, String name)
    {
        NamedItemCells item = (NamedItemCells)doc.getNamedItems().get(name);
        assertNotNull("the file has to carry the area " + name, item); //$NON-NLS-1$
        return ((RectArea)item.getArea()).getPosition();
    }

    /** Asserts one merge of a document against the 1-based corners a reader gets for it. */
    private static void assertMerge(Map<String, Object> merge, int fromRow, int fromCol, int toRow,
        int toCol)
    {
        assertEquals("fromRow", Integer.valueOf(fromRow), merge.get("fromRow")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("fromCol", Integer.valueOf(fromCol), merge.get("fromCol")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("toRow", Integer.valueOf(toRow), merge.get("toRow")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("toCol", Integer.valueOf(toCol), merge.get("toCol")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The three areas of the corpus file come back as the cells their begin and end cover. */
    @Test
    public void theAreasOfAFileAreReadAsTheCellsTheyCover() throws Exception
    {
        SpreadsheetDocument doc = fileOf("template-areas.mxlx"); //$NON-NLS-1$

        Map<String, Object> cell = areaNamed(doc, "ОднаЯчейка"); //$NON-NLS-1$
        assertEquals("rect", cell.get("kind")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(6), cell.get("fromRow")); //$NON-NLS-1$
        assertEquals("endRow 5 is the same row, not one past it", Integer.valueOf(6), //$NON-NLS-1$
            cell.get("toRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(15), cell.get("fromCol")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(15), cell.get("toCol")); //$NON-NLS-1$

        Map<String, Object> block = areaNamed(doc, "Блок"); //$NON-NLS-1$
        assertEquals(Integer.valueOf(1), block.get("fromRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(3), block.get("toRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(1), block.get("fromCol")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(4), block.get("toCol")); //$NON-NLS-1$

        Map<String, Object> strip = areaNamed(doc, "СтрокаИтога"); //$NON-NLS-1$
        assertEquals(Integer.valueOf(3), strip.get("fromRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(3), strip.get("toRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(3), strip.get("fromCol")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(14), strip.get("toCol")); //$NON-NLS-1$
    }

    /**
     * The model position of an area of the file is the far corner the file carries: height is
     * endRow - beginRow and width is endColumn - beginColumn, so a one-cell area is height 0 and
     * width 0 and the three-row block is height 2. The position read as a count of cells would make
     * every one of them one row too tall.
     */
    @Test
    public void thePositionOfAnAreaIsTheFarCornerTheFileCarries() throws Exception
    {
        SpreadsheetDocument doc = fileOf("template-areas.mxlx"); //$NON-NLS-1$

        Rect cell = positionOf(doc, "ОднаЯчейка"); //$NON-NLS-1$
        assertEquals(5, cell.getY());
        assertEquals(14, cell.getX());
        assertEquals("endRow 5 of beginRow 5", 0, cell.getHeight()); //$NON-NLS-1$
        assertEquals("endColumn 14 of beginColumn 14", 0, cell.getWidth()); //$NON-NLS-1$

        Rect block = positionOf(doc, "Блок"); //$NON-NLS-1$
        assertEquals(0, block.getY());
        assertEquals(0, block.getX());
        assertEquals("endRow 2 of beginRow 0", 2, block.getHeight()); //$NON-NLS-1$
        assertEquals("endColumn 3 of beginColumn 0", 3, block.getWidth()); //$NON-NLS-1$

        Rect strip = positionOf(doc, "СтрокаИтога"); //$NON-NLS-1$
        assertEquals(2, strip.getY());
        assertEquals(2, strip.getX());
        assertEquals(0, strip.getHeight());
        assertEquals("endColumn 13 of beginColumn 2", 11, strip.getWidth()); //$NON-NLS-1$
    }

    /** An area written by the helper over one cell holds the position the file holds for one. */
    @Test
    public void writingAnAreaOfOneCellLandOnThePositionTheFileCarries() throws Exception
    {
        SpreadsheetDocument doc = fileOf("template-areas.mxlx"); //$NON-NLS-1$

        BmTemplateHelper.addNamedArea(doc, "НоваяЯчейка", "rect", 6, 15, 6, 15); //$NON-NLS-1$ //$NON-NLS-2$

        Rect written = positionOf(doc, "НоваяЯчейка"); //$NON-NLS-1$
        Rect carried = positionOf(doc, "ОднаЯчейка"); //$NON-NLS-1$
        assertEquals(carried.getY(), written.getY());
        assertEquals(carried.getX(), written.getX());
        assertEquals(carried.getHeight(), written.getHeight());
        assertEquals(carried.getWidth(), written.getWidth());

        Map<String, Object> back = areaNamed(doc, "НоваяЯчейка"); //$NON-NLS-1$
        assertEquals(Integer.valueOf(6), back.get("fromRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(6), back.get("toRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(15), back.get("fromCol")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(15), back.get("toCol")); //$NON-NLS-1$
    }

    /** The merges of the file come back as the rows and columns their r, c, w and h cover. */
    @Test
    public void theMergesOfAFileAreReadAsTheCellsTheyCover() throws Exception
    {
        SpreadsheetDocument doc = fileOf("template-merges.mxlx"); //$NON-NLS-1$

        List<Map<String, Object>> merges = mergesOf(doc);
        assertEquals("the file carries two merges", 2, merges.size()); //$NON-NLS-1$
        assertMerge(merges.get(0), 1, 1, 2, 3);
        assertMerge(merges.get(1), 4, 2, 4, 3);

        Map<String, Object> header = areaNamed(doc, "Шапка"); //$NON-NLS-1$
        assertEquals(Integer.valueOf(1), header.get("fromRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(2), header.get("toRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(1), header.get("fromCol")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(3), header.get("toCol")); //$NON-NLS-1$

        Map<String, Object> total = areaNamed(doc, "СтрокаИтога"); //$NON-NLS-1$
        assertEquals(Integer.valueOf(4), total.get("fromRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(4), total.get("toRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(2), total.get("fromCol")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(3), total.get("toCol")); //$NON-NLS-1$
    }

    /** A row inserted into a file moves what the file had below it and grows what holds it. */
    @Test
    public void aRowInsertedIntoAFileMovesWhatIsBelowIt() throws Exception
    {
        SpreadsheetDocument doc = fileOf("template-areas.mxlx"); //$NON-NLS-1$

        BmTemplateHelper.RowOutcome outcome = BmTemplateHelper.insertRows(doc, 2, 1, "none"); //$NON-NLS-1$

        assertNull(outcome.error);
        Map<String, Object> block = areaNamed(doc, "Блок"); //$NON-NLS-1$
        assertEquals("the inserted row lands inside the block", Integer.valueOf(1), //$NON-NLS-1$
            block.get("fromRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(4), block.get("toRow")); //$NON-NLS-1$
        Map<String, Object> strip = areaNamed(doc, "СтрокаИтога"); //$NON-NLS-1$
        assertEquals("the row the strip named moved down", Integer.valueOf(4), //$NON-NLS-1$
            strip.get("fromRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(4), strip.get("toRow")); //$NON-NLS-1$
        Map<String, Object> cell = areaNamed(doc, "ОднаЯчейка"); //$NON-NLS-1$
        assertEquals(Integer.valueOf(7), cell.get("fromRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(7), cell.get("toRow")); //$NON-NLS-1$
        assertEquals("a row is no reason for a column to move", Integer.valueOf(15), //$NON-NLS-1$
            cell.get("fromCol")); //$NON-NLS-1$

        Rect carried = positionOf(doc, "ОднаЯчейка"); //$NON-NLS-1$
        assertEquals(6, carried.getY());
        assertEquals("and the one-cell span stays a one-cell span", 0, carried.getHeight()); //$NON-NLS-1$
    }

    /** A column inserted into a file takes its neighbors with it, areas, merges and text alike. */
    @Test
    public void aColumnInsertedIntoAFileMovesTheAreasAndTheText() throws Exception
    {
        SpreadsheetDocument doc = fileOf("template-rows-columns.mxlx"); //$NON-NLS-1$

        assertEquals("Товар", textAt(doc, 1, 2)); //$NON-NLS-1$
        BmTemplateHelper.ColumnOutcome outcome = BmTemplateHelper.insertColumns(doc, 1, 1, "none"); //$NON-NLS-1$
        assertNull(outcome.error);

        Map<String, Object> data = areaNamed(doc, "Данные"); //$NON-NLS-1$
        assertEquals("the area of the file spans the rows its begin and end name", //$NON-NLS-1$
            Integer.valueOf(2), data.get("fromRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(3), data.get("toRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(2), data.get("fromCol")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(5), data.get("toCol")); //$NON-NLS-1$

        assertMerge(mergesOf(doc).get(0), 1, 2, 1, 5);
        assertNull("the column the insert added is empty", textAt(doc, 1, 1)); //$NON-NLS-1$
        assertEquals("the text the file had in the first column moved with it", //$NON-NLS-1$
            "Код", textAt(doc, 1, 2)); //$NON-NLS-1$
        assertEquals("Товар", textAt(doc, 1, 3)); //$NON-NLS-1$
    }

    /** The text of a file is read, written over and read back, with the areas left alone. */
    @Test
    public void theTextOfAFileIsReadAndCanBeWrittenOver() throws Exception
    {
        SpreadsheetDocument doc = fileOf("template-text.mxlx"); //$NON-NLS-1$

        assertEquals("Наименование", textAt(doc, 1, 1)); //$NON-NLS-1$
        assertEquals("Товар", textAt(doc, 2, 1)); //$NON-NLS-1$
        assertEquals("Услуга", textAt(doc, 3, 1)); //$NON-NLS-1$

        Map<String, Object> goods = areaNamed(doc, "Товары"); //$NON-NLS-1$
        assertEquals(Integer.valueOf(2), goods.get("fromRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(3), goods.get("toRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(3), goods.get("toCol")); //$NON-NLS-1$

        BmTemplateHelper.setCellText(doc, 4, 1, "Итого", "ru"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("Итого", textAt(doc, 4, 1)); //$NON-NLS-1$
        assertEquals("writing a cell is no reason for an area to move", Integer.valueOf(2), //$NON-NLS-1$
            areaNamed(doc, "Товары").get("fromRow")); //$NON-NLS-1$
        assertEquals("and the file had no merges to lose", 0, mergesOf(doc).size()); //$NON-NLS-1$

        BmTemplateHelper.addNamedArea(doc, "Итог", "rect", 4, 1, 4, 3); //$NON-NLS-1$ //$NON-NLS-2$
        BmTemplateHelper.mergeCells(doc, 4, 1, 4, 3);

        Map<String, Object> total = areaNamed(doc, "Итог"); //$NON-NLS-1$
        assertEquals(Integer.valueOf(4), total.get("fromRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(4), total.get("toRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(1), total.get("fromCol")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(3), total.get("toCol")); //$NON-NLS-1$
        List<Map<String, Object>> merges = mergesOf(doc);
        assertEquals(1, merges.size());
        assertMerge(merges.get(0), 4, 1, 4, 3);
        assertTrue("three cells of the row are the merge, not two", //$NON-NLS-1$
            positionOf(doc, "Итог").getWidth() == 2); //$NON-NLS-1$
    }
}
