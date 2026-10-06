/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Point;
import com._1c.g5.v8.dt.moxel.Cell;
import com._1c.g5.v8.dt.moxel.Column;
import com._1c.g5.v8.dt.moxel.Columns;
import com._1c.g5.v8.dt.moxel.DrawingsDataSource;
import com._1c.g5.v8.dt.moxel.Merge;
import com._1c.g5.v8.dt.moxel.MoxelFactory;
import com._1c.g5.v8.dt.moxel.Rect;
import com._1c.g5.v8.dt.moxel.RectArea;
import com._1c.g5.v8.dt.moxel.Row;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.moxel.SpreadsheetPoint;
import com._1c.g5.v8.dt.moxel.SpreadsheetRect;
import com._1c.g5.v8.dt.moxel.ViewSettings;

/**
 * Columns of a spreadsheet template, inserted, deleted and copied whole.
 * <p>
 * A column is not one thing in this model: the cells of every row with their notes, the entries of
 * every column set - a set shared by several rows counts once - the merges, the named areas, the
 * column groups, the drawings, the print and repeat areas and the saved view columns all carry a
 * column number. An operation that moves the columns and leaves any of those behind leaves the
 * template pointing at content that moved, so every holder is checked here against the same call.
 * </p>
 * <p>
 * The model is built in memory with the moxel factory; coordinates on the class under test are
 * 1-based and 0-based in the model, the split every other coordinate here lives with.
 * </p>
 */
public class ColumnOperationsOfATemplateTest
{
    private static SpreadsheetDocument emptyDocument()
    {
        return MoxelFactory.eINSTANCE.createSpreadsheetDocument();
    }

    private static SpreadsheetDocument withTextsAcross(String... texts)
    {
        SpreadsheetDocument doc = emptyDocument();
        for (int i = 0; i < texts.length; i++)
        {
            BmTemplateHelper.setCellText(doc, 1, i + 1, texts[i], "ru"); //$NON-NLS-1$
        }
        return doc;
    }

    private static String textAt(SpreadsheetDocument doc, int col)
    {
        return textAt(doc, 1, col);
    }

    private static String textAt(SpreadsheetDocument doc, int row, int col)
    {
        Row held = doc.getRows().get(Integer.valueOf(row - 1));
        Cell cell = held == null ? null : held.getCells().get(Integer.valueOf(col - 1));
        return cell == null || cell.getText() == null
            ? null : cell.getText().getContent().get("ru"); //$NON-NLS-1$
    }

    private static Row rowAt(SpreadsheetDocument doc, int row)
    {
        return doc.getRows().get(Integer.valueOf(row - 1));
    }

    /** A column set with a formatted column at the 1-based column, held by the document. */
    private static Columns setWithColumn(SpreadsheetDocument doc, int col, int formatIndex)
    {
        Columns set = MoxelFactory.eINSTANCE.createColumns();
        set.setSize(col);
        Column column = MoxelFactory.eINSTANCE.createColumn();
        column.setFormatIndex(formatIndex);
        set.getColumns().put(Integer.valueOf(col - 1), column);
        doc.getAllColumns().add(set);
        return set;
    }

    /** What the whole document reads as, for before-and-after comparisons. */
    private static Map<String, Object> readOf(SpreadsheetDocument doc)
    {
        return BmTemplateHelper.readSpreadsheet(doc, "ru"); //$NON-NLS-1$
    }

    /** The one merge of a document built with exactly one, as read corners. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> onlyMerge(SpreadsheetDocument doc)
    {
        List<Map<String, Object>> merges =
            (List<Map<String, Object>>)readOf(doc).get("merges"); //$NON-NLS-1$
        assertEquals("the document holds exactly one merge", 1, merges.size()); //$NON-NLS-1$
        return merges.get(0);
    }

    /** Asserts the call refused and the document reads exactly as it did before it. */
    private static void refusedLeavingTheDocumentAsItWas(SpreadsheetDocument doc,
        BmTemplateHelper.ColumnOutcome outcome, Map<String, Object> before)
    {
        assertNotNull("the call has to say why it did nothing", outcome.error); //$NON-NLS-1$
        assertEquals("a refused call is not allowed to touch the document", before, readOf(doc)); //$NON-NLS-1$
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

    /** A note anchor whose begin and end both sit on one 0-based row and column. */
    private static SpreadsheetRect anchorAt(int row, int col)
    {
        SpreadsheetRect rect = MoxelFactory.eINSTANCE.createSpreadsheetRect();
        rect.setBegin(pointAt(row, col));
        rect.setEnd(pointAt(row, col));
        return rect;
    }

    /** A drawing point pinned to one 0-based row and column. */
    private static SpreadsheetPoint pointAt(int row, int col)
    {
        SpreadsheetPoint point = MoxelFactory.eINSTANCE.createSpreadsheetPoint();
        Point cell = McoreFactory.eINSTANCE.createPoint();
        cell.setX(col);
        cell.setY(row);
        point.setCell(cell);
        return point;
    }

    /** An unmerge exception over 1-based rows and columns, built the way a merge is. */
    private static Merge unmergeOver(int fromRow, int fromCol, int toRow, int toCol)
    {
        Merge unmerge = MoxelFactory.eINSTANCE.createMerge();
        Rect rect = MoxelFactory.eINSTANCE.createRect();
        rect.setX(fromCol - 1);
        rect.setY(fromRow - 1);
        rect.setWidth(toCol - fromCol);
        rect.setHeight(toRow - fromRow);
        unmerge.setPosition(rect);
        return unmerge;
    }

    /** Inserting in the middle moves the columns to the right and leaves the ones left alone. */
    @Test
    public void insertingInTheMiddleMovesOnlyTheColumnsToTheRight()
    {
        SpreadsheetDocument doc = withTextsAcross("A", "B", "C", "D"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        BmTemplateHelper.ColumnOutcome outcome = BmTemplateHelper.insertColumns(doc, 3, 2, "none"); //$NON-NLS-1$

        assertNull(outcome.error);
        assertEquals("the column left of the insertion keeps its number", "A", textAt(doc, 1)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("B", textAt(doc, 2)); //$NON-NLS-1$
        assertNull("the new columns are empty", textAt(doc, 3)); //$NON-NLS-1$
        assertNull(textAt(doc, 4));
        assertEquals("the column right of the point lands two columns further", "C", textAt(doc, 5)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("D", textAt(doc, 6)); //$NON-NLS-1$
        assertEquals("the two columns right of the point changed their number", 2, //$NON-NLS-1$
            outcome.shiftedColumns);
        assertEquals(6, outcome.lastColumn);
    }

    /** Inserting at the very end is allowed, at the column past that it is not. */
    @Test
    public void insertingPastTheEndIsRefused()
    {
        SpreadsheetDocument doc = withTextsAcross("A", "B"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> before = readOf(doc);

        BmTemplateHelper.ColumnOutcome atTheEnd = BmTemplateHelper.insertColumns(doc, 3, 1, "none"); //$NON-NLS-1$
        assertNull(atTheEnd.error);
        assertEquals("appending shifts nothing", 0, atTheEnd.shiftedColumns); //$NON-NLS-1$
        assertEquals("A", textAt(doc, 1)); //$NON-NLS-1$
        assertEquals("B", textAt(doc, 2)); //$NON-NLS-1$

        refusedLeavingTheDocumentAsItWas(doc, BmTemplateHelper.insertColumns(doc, 4, 1, "none"), //$NON-NLS-1$
            before);
    }

    /** The new columns take the column format and the cell formats the formatFrom column carries. */
    @Test
    public void theNewColumnsTakeTheirFormatsFromTheColumnNamedByFormatFrom()
    {
        SpreadsheetDocument doc = withTextsAcross("A", "B", "C"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        BmTemplateHelper.applyCellFormat(doc, 1, 1, 1, 1, null, null, null, null,
            Integer.valueOf(30), null, null);
        Columns docSet = doc.getColumns();
        int leftFormat = docSet.getColumns().get(Integer.valueOf(0)).getFormatIndex();

        BmTemplateHelper.ColumnOutcome fromLeft = BmTemplateHelper.insertColumns(doc, 2, 1, "left"); //$NON-NLS-1$

        assertNull(fromLeft.error);
        assertEquals("left: the column entry of the column left of the point", leftFormat, //$NON-NLS-1$
            docSet.getColumns().get(Integer.valueOf(1)).getFormatIndex());

        SpreadsheetDocument rightDoc = withTextsAcross("A", "B", "C"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        BmTemplateHelper.applyCellFormat(rightDoc, 1, 3, 1, 3, null, null, null, null,
            Integer.valueOf(12), null, null);
        Columns rightSet = rightDoc.getColumns();
        int pointFormat = rightSet.getColumns().get(Integer.valueOf(2)).getFormatIndex();

        BmTemplateHelper.ColumnOutcome fromRight = BmTemplateHelper.insertColumns(rightDoc, 3, 1,
            "right"); //$NON-NLS-1$

        assertNull(fromRight.error);
        assertEquals("right: the column entry of the column at the point", pointFormat, //$NON-NLS-1$
            rightSet.getColumns().get(Integer.valueOf(2)).getFormatIndex());
        assertEquals("C", textAt(rightDoc, 4)); //$NON-NLS-1$

        BmTemplateHelper.ColumnOutcome fromNone = BmTemplateHelper.insertColumns(doc, 2, 1, "none"); //$NON-NLS-1$
        assertNull(fromNone.error);
        assertNull("none: no column entry is written at all", //$NON-NLS-1$
            docSet.getColumns().get(Integer.valueOf(3)));

        SpreadsheetDocument fresh = withTextsAcross("A"); //$NON-NLS-1$
        BmTemplateHelper.ColumnOutcome leftOfTheEdge = BmTemplateHelper.insertColumns(fresh, 1, 1,
            "left"); //$NON-NLS-1$
        assertNull(leftOfTheEdge.error);
        assertNull("left at col 1 reads as none - there is no column left of the edge", //$NON-NLS-1$
            fresh.getColumns() == null ? null
                : fresh.getColumns().getColumns().get(Integer.valueOf(0)));
        assertEquals("A", textAt(fresh, 2)); //$NON-NLS-1$
    }

    /** A cell format rides along on the copy; text and parameters never do. */
    @Test
    public void cellFormatsRideAlongAndTextDoesNot()
    {
        SpreadsheetDocument doc = withTextsAcross("A", "B"); //$NON-NLS-1$ //$NON-NLS-2$
        BmTemplateHelper.CellLook border = new BmTemplateHelper.CellLook();
        border.border = "Solid"; //$NON-NLS-1$
        BmTemplateHelper.applyCellFormat(doc, 1, 2, 1, 2, null, null, null, null, null, null,
            border);
        int bordered = rowAt(doc, 1).getCells().get(Integer.valueOf(1)).getFormatIndex();

        BmTemplateHelper.ColumnOutcome outcome = BmTemplateHelper.insertColumns(doc, 3, 1, "left"); //$NON-NLS-1$

        assertNull(outcome.error);
        Row row = rowAt(doc, 1);
        Cell carried = row.getCells().get(Integer.valueOf(2));
        assertNotNull("a formatted cell of the source is a formatted cell of the copy", //$NON-NLS-1$
            carried);
        assertEquals(bordered, carried.getFormatIndex());
        assertNull("the text does not ride along", carried.getText()); //$NON-NLS-1$
        assertNull(carried.getParameter());
        assertNull("a cell with no format of its own is not materialized", //$NON-NLS-1$
            row.getCells().get(Integer.valueOf(3)));
    }

    /** An insertion inside a horizontal merge grows it; one left of it leaves it, one right moves it. */
    @Test
    public void anInsertInsideAMergeGrowsIt()
    {
        SpreadsheetDocument doc = emptyDocument();
        BmTemplateHelper.mergeCells(doc, 1, 2, 1, 4);

        BmTemplateHelper.ColumnOutcome outcome = BmTemplateHelper.insertColumns(doc, 3, 1, "none"); //$NON-NLS-1$

        assertNull(outcome.error);
        Map<String, Object> merge = onlyMerge(doc);
        assertEquals("the merge keeps its left edge", Integer.valueOf(2), merge.get("fromCol")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("and gains the inserted column", Integer.valueOf(5), merge.get("toCol")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, outcome.resizedMerges);
    }

    @Test
    public void anInsertLeftOfAMergeLeavesItAndRightMovesIt()
    {
        SpreadsheetDocument doc = emptyDocument();
        BmTemplateHelper.mergeCells(doc, 1, 3, 1, 4);

        BmTemplateHelper.insertColumns(doc, 5, 1, "none"); //$NON-NLS-1$
        Map<String, Object> merge = onlyMerge(doc);
        assertEquals("an insert right of the merge leaves it where it is", Integer.valueOf(3), //$NON-NLS-1$
            merge.get("fromCol")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(4), merge.get("toCol")); //$NON-NLS-1$

        BmTemplateHelper.insertColumns(doc, 1, 2, "none"); //$NON-NLS-1$
        merge = onlyMerge(doc);
        assertEquals("an insert left of the merge moves it right", Integer.valueOf(5), //$NON-NLS-1$
            merge.get("fromCol")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(6), merge.get("toCol")); //$NON-NLS-1$

        BmTemplateHelper.insertColumns(doc, 7, 1, "none"); //$NON-NLS-1$
        merge = onlyMerge(doc);
        assertEquals("an insert appended past changes nothing before it", Integer.valueOf(5), //$NON-NLS-1$
            merge.get("fromCol")); //$NON-NLS-1$

        BmTemplateHelper.insertColumns(doc, 5, 1, "none"); //$NON-NLS-1$
        merge = onlyMerge(doc);
        assertEquals("an insert over the merge's left edge moves it right", Integer.valueOf(6), //$NON-NLS-1$
            merge.get("fromCol")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(7), merge.get("toCol")); //$NON-NLS-1$
    }

    /** Inserting inside a columns area and a rectangle grows both; an area right of it moves. */
    @Test
    public void anInsertInsideNamedAreasGrowsThem()
    {
        SpreadsheetDocument doc = emptyDocument();
        BmTemplateHelper.addNamedArea(doc, "Столбцы", "columns", 0, 2, 0, 4); //$NON-NLS-1$ //$NON-NLS-2$
        BmTemplateHelper.addNamedArea(doc, "Блок", "rect", 3, 2, 5, 4); //$NON-NLS-1$ //$NON-NLS-2$
        BmTemplateHelper.addNamedArea(doc, "Право", "columns", 0, 7, 0, 8); //$NON-NLS-1$ //$NON-NLS-2$

        BmTemplateHelper.ColumnOutcome outcome = BmTemplateHelper.insertColumns(doc, 4, 1, "none"); //$NON-NLS-1$

        assertNull(outcome.error);
        assertTrue("the columns area the point falls inside is named as resized", //$NON-NLS-1$
            outcome.resizedNamedAreas.contains("Столбцы")); //$NON-NLS-1$
        assertTrue("and so is the rectangle", outcome.resizedNamedAreas.contains("Блок")); //$NON-NLS-1$
        List<Map<String, Object>> areas = BmTemplateHelper.listNamedAreas(doc);
        for (Map<String, Object> area : areas)
        {
            if ("Столбцы".equals(area.get("name"))) //$NON-NLS-1$ //$NON-NLS-2$
            {
                assertEquals("the columns area grew by the count", Integer.valueOf(2), //$NON-NLS-1$
                    area.get("fromCol")); //$NON-NLS-1$
                assertEquals(Integer.valueOf(5), area.get("toCol")); //$NON-NLS-1$
            }
            if ("Блок".equals(area.get("name"))) //$NON-NLS-1$ //$NON-NLS-2$
            {
                assertEquals("the rectangle grew the same way", Integer.valueOf(2), //$NON-NLS-1$
                    area.get("fromCol")); //$NON-NLS-1$
                assertEquals(Integer.valueOf(5), area.get("toCol")); //$NON-NLS-1$
            }
            if ("Право".equals(area.get("name"))) //$NON-NLS-1$ //$NON-NLS-2$
            {
                assertEquals("the area right of the point moved right", Integer.valueOf(8), //$NON-NLS-1$
                    area.get("fromCol")); //$NON-NLS-1$
            }
        }
    }

    /** A rows area holds no columns, so a column insertion has nothing to say to it. */
    @Test
    public void aRowsAreaIsNotMovedByColumns()
    {
        SpreadsheetDocument doc = withTextsAcross("A", "B"); //$NON-NLS-1$ //$NON-NLS-2$
        BmTemplateHelper.addNamedArea(doc, "Шапка", "rows", 2, 0, 3, 0); //$NON-NLS-1$ //$NON-NLS-2$

        BmTemplateHelper.ColumnOutcome outcome = BmTemplateHelper.insertColumns(doc, 2, 1, "none"); //$NON-NLS-1$

        assertNull(outcome.error);
        assertTrue(outcome.resizedNamedAreas.isEmpty());
        Map<String, Object> area = BmTemplateHelper.listNamedAreas(doc).get(0);
        assertEquals(Integer.valueOf(2), area.get("fromRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(3), area.get("toRow")); //$NON-NLS-1$
    }

    /** The column past a rectangular area is not the area's: either operation changes nothing. */
    @Test
    public void aColumnPastARectangleLeavesIt()
    {
        SpreadsheetDocument doc = withTextsAcross("A", "B", "C", "D", "E", "F"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        BmTemplateHelper.addNamedArea(doc, "Блок", "rect", 1, 2, 3, 4); //$NON-NLS-1$ //$NON-NLS-2$

        BmTemplateHelper.ColumnOutcome afterInsert = BmTemplateHelper.insertColumns(doc, 5, 1,
            "none"); //$NON-NLS-1$

        assertNull(afterInsert.error);
        assertFalse("the column past the area does not stretch it", //$NON-NLS-1$
            afterInsert.resizedNamedAreas.contains("Блок")); //$NON-NLS-1$
        Map<String, Object> area = areaNamed(doc, "Блок"); //$NON-NLS-1$
        assertEquals(Integer.valueOf(2), area.get("fromCol")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(4), area.get("toCol")); //$NON-NLS-1$

        BmTemplateHelper.ColumnOutcome afterDelete = BmTemplateHelper.deleteColumns(doc, 5, 1);

        assertNull(afterDelete.error);
        area = areaNamed(doc, "Блок"); //$NON-NLS-1$
        assertEquals("the column past the area does not shrink it either", Integer.valueOf(2), //$NON-NLS-1$
            area.get("fromCol")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(4), area.get("toCol")); //$NON-NLS-1$
    }

    /** Inserting over the rectangle's last column stretches it by the count. */
    @Test
    public void insertingOverTheLastColumnOfARectangleStretchesIt()
    {
        SpreadsheetDocument doc = emptyDocument();
        BmTemplateHelper.addNamedArea(doc, "Блок", "rect", 1, 1, 3, 2); //$NON-NLS-1$ //$NON-NLS-2$

        BmTemplateHelper.ColumnOutcome outcome = BmTemplateHelper.insertColumns(doc, 2, 2, "none"); //$NON-NLS-1$

        assertNull(outcome.error);
        assertTrue(outcome.resizedNamedAreas.contains("Блок")); //$NON-NLS-1$
        Map<String, Object> area = areaNamed(doc, "Блок"); //$NON-NLS-1$
        assertEquals("the first column of the area stays", Integer.valueOf(1), //$NON-NLS-1$
            area.get("fromCol")); //$NON-NLS-1$
        assertEquals("and the last one stretches by the count", Integer.valueOf(4), //$NON-NLS-1$
            area.get("toCol")); //$NON-NLS-1$
        assertEquals("the document ends where the area now does", 4, outcome.lastColumn); //$NON-NLS-1$
    }

    /** Deleting the rectangle's last column takes exactly that column off the area. */
    @Test
    public void deletingTheLastColumnOfARectangleShrinksIt()
    {
        SpreadsheetDocument doc = emptyDocument();
        BmTemplateHelper.addNamedArea(doc, "Блок", "rect", 1, 1, 3, 3); //$NON-NLS-1$ //$NON-NLS-2$

        BmTemplateHelper.ColumnOutcome outcome = BmTemplateHelper.deleteColumns(doc, 3, 1);

        assertNull(outcome.error);
        Map<String, Object> area = areaNamed(doc, "Блок"); //$NON-NLS-1$
        assertEquals(Integer.valueOf(1), area.get("fromCol")); //$NON-NLS-1$
        assertEquals("the area lost the column it lost", Integer.valueOf(2), area.get("toCol")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the document ends where the area now does", 2, outcome.lastColumn); //$NON-NLS-1$
    }

    /** A row with its own column set shifts with the document's set, each set once. */
    @Test
    public void aRowWithItsOwnColumnSetShiftsBothSetsOnce()
    {
        SpreadsheetDocument doc = withTextsAcross("A", "B", "C"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        BmTemplateHelper.setCellText(doc, 2, 3, "Низ", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        BmTemplateHelper.applyCellFormat(doc, 1, 3, 1, 3, null, null, null, null,
            Integer.valueOf(30), null, null);
        Columns docSet = doc.getColumns();
        docSet.setSize(3);
        Columns ownSet = setWithColumn(doc, 3, 7);
        rowAt(doc, 2).setColumns(ownSet);

        BmTemplateHelper.ColumnOutcome outcome = BmTemplateHelper.insertColumns(doc, 2, 1, "none"); //$NON-NLS-1$

        assertNull(outcome.error);
        assertEquals("the document set's entry moved by the count", Integer.valueOf(3), //$NON-NLS-1$
            lastKeyOf(docSet));
        assertEquals("the document set's declared size grew", 4, docSet.getSize());
        assertEquals("the row set's entry moved by the count", Integer.valueOf(3), //$NON-NLS-1$
            lastKeyOf(ownSet));
        assertEquals("the row set's declared size grew", 4, ownSet.getSize());
        assertEquals("the cell of the second row moved with its set", "Низ", textAt(doc, 2, 4)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("three cells and two column entries changed their column", 5, //$NON-NLS-1$
            outcome.shiftedColumns);
    }

    /** A set shared by two rows shifts once, by the count, not once per row that reaches it. */
    @Test
    public void aSetSharedByTwoRowsShiftsExactlyByTheCount()
    {
        SpreadsheetDocument doc = withTextsAcross("A", "B"); //$NON-NLS-1$ //$NON-NLS-2$
        BmTemplateHelper.setCellText(doc, 2, 1, "Второй", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        Columns shared = setWithColumn(doc, 2, 5);
        rowAt(doc, 1).setColumns(shared);
        rowAt(doc, 2).setColumns(shared);

        BmTemplateHelper.ColumnOutcome outcome = BmTemplateHelper.insertColumns(doc, 2, 3, "none"); //$NON-NLS-1$

        assertNull(outcome.error);
        assertEquals("the shared set's entry moved by the count alone", Integer.valueOf(4), //$NON-NLS-1$
            lastKeyOf(shared));
        assertEquals("and its declared size grew by the count alone", 5, shared.getSize());
        assertEquals("the one cell and the one entry the shift reached moved", 2, //$NON-NLS-1$
            outcome.shiftedColumns);
    }

    /** The highest 0-based key a column set holds, as an Integer for the assertion message. */
    private static Integer lastKeyOf(Columns set)
    {
        Integer last = null;
        for (Map.Entry<Integer, Column> held : set.getColumns())
        {
            if (held != null && held.getKey() != null
                && (last == null || held.getKey().intValue() > last.intValue()))
            {
                last = held.getKey();
            }
        }
        return last;
    }

    /** Deleting shifts the columns right of the range left and names what the range swallowed. */
    @Test
    public void deletingShiftsTheColumnsToTheRightLeft()
    {
        SpreadsheetDocument doc = withTextsAcross("A", "B", "C", "D", "E"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        BmTemplateHelper.ColumnOutcome outcome = BmTemplateHelper.deleteColumns(doc, 2, 2);

        assertNull(outcome.error);
        assertEquals("A", textAt(doc, 1)); //$NON-NLS-1$
        assertEquals("the columns right of the range moved left by the count", "D", textAt(doc, 2)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("E", textAt(doc, 3)); //$NON-NLS-1$
        assertNull(textAt(doc, 4));
        assertEquals("two columns right of the range moved left", 2, outcome.shiftedColumns); //$NON-NLS-1$
        assertEquals(3, outcome.lastColumn);
    }

    /** A merge and an area entirely inside the deleted range go with it and are named. */
    @Test
    public void whatLiesEntirelyInsideTheDeletedRangeGoesAndIsNamed()
    {
        SpreadsheetDocument doc = withTextsAcross("A", "B", "C", "D", "E", "F"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        BmTemplateHelper.mergeCells(doc, 1, 3, 2, 4);
        BmTemplateHelper.addNamedArea(doc, "Середина", "columns", 0, 3, 0, 4); //$NON-NLS-1$ //$NON-NLS-2$

        BmTemplateHelper.ColumnOutcome outcome = BmTemplateHelper.deleteColumns(doc, 3, 2);

        assertNull(outcome.error);
        assertEquals("the merge inside the range is counted as removed", 1, outcome.removedMerges); //$NON-NLS-1$
        assertTrue(doc.getMerges().isEmpty());
        assertTrue("the area inside the range is named", //$NON-NLS-1$
            outcome.removedNamedAreas.contains("Середина")); //$NON-NLS-1$
        assertTrue(doc.getNamedItems().isEmpty());
    }

    /** A merge the range cuts in half shrinks by the columns it lost. */
    @Test
    public void aMergeTheRangeCutsShrinks()
    {
        SpreadsheetDocument doc = emptyDocument();
        BmTemplateHelper.mergeCells(doc, 1, 2, 1, 5);

        BmTemplateHelper.ColumnOutcome outcome = BmTemplateHelper.deleteColumns(doc, 4, 2);

        assertNull(outcome.error);
        Map<String, Object> merge = onlyMerge(doc);
        assertEquals("the left edge of a cut merge stays", Integer.valueOf(2), merge.get("fromCol")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("and the lost columns come off the right", Integer.valueOf(3), //$NON-NLS-1$
            merge.get("toCol")); //$NON-NLS-1$
        assertEquals(1, outcome.resizedMerges);
        assertEquals(0, outcome.removedMerges);
    }

    /** A drawing right of an insertion moves; one inside a deletion goes and is named by id. */
    @Test
    public void drawingsMoveWithTheColumns()
    {
        SpreadsheetDocument doc = emptyDocument();
        int left = BmTemplateHelper.addDrawing(doc, "Line", 1, 5, 2, 6, 0, 0, 0, 0, -1, null, //$NON-NLS-1$
            null, null);

        BmTemplateHelper.ColumnOutcome afterInsert = BmTemplateHelper.insertColumns(doc, 2, 1, "none"); //$NON-NLS-1$

        assertNull(afterInsert.error);
        assertEquals(Integer.valueOf(6),
            Integer.valueOf(doc.getDrawings().get(0).getPosition().getBegin().getCell().getX()
                + 1));

        int inside = BmTemplateHelper.addDrawing(doc, "Line", 1, 7, 2, 8, 0, 0, 0, 0, -1, null, //$NON-NLS-1$
            null, null);
        BmTemplateHelper.ColumnOutcome afterDelete = BmTemplateHelper.deleteColumns(doc, 7, 2);

        assertNull(afterDelete.error);
        assertEquals("the drawing inside the deleted range is named by id", //$NON-NLS-1$
            Integer.valueOf(inside), afterDelete.removedDrawings.get(0));
        assertEquals("the drawing left of the range stays", 1, doc.getDrawings().size()); //$NON-NLS-1$
        assertEquals(Integer.valueOf(left), Integer.valueOf(doc.getDrawings().get(0)
            .getDrawingId()));
    }

    /** Column groups and whole-column merges travel with the columns like any other holder. */
    @Test
    public void columnGroupsAndWholeColumnMergesTravelWithTheColumns()
    {
        SpreadsheetDocument doc = emptyDocument();
        com._1c.g5.v8.dt.moxel.ColumnGroup group = MoxelFactory.eINSTANCE.createColumnGroup();
        group.setBegin(1);
        group.setEnd(3);
        doc.getColumnGroups().add(group);
        com._1c.g5.v8.dt.moxel.ColumnMerge wholeColumns =
            MoxelFactory.eINSTANCE.createColumnMerge();
        wholeColumns.setBegin(5);
        wholeColumns.setEnd(6);
        doc.getColumnMerges().add(wholeColumns);

        BmTemplateHelper.ColumnOutcome grown = BmTemplateHelper.insertColumns(doc, 3, 1, "none"); //$NON-NLS-1$

        assertNull(grown.error);
        assertEquals("the group the point falls inside grows", 4, doc.getColumnGroups().get(0) //$NON-NLS-1$
            .getEnd());
        assertEquals("the whole-column merge right of the point moves", 6, //$NON-NLS-1$
            doc.getColumnMerges().get(0).getBegin());

        BmTemplateHelper.ColumnOutcome removed = BmTemplateHelper.deleteColumns(doc, 7, 2);

        assertNull(removed.error);
        assertTrue("the whole-column merge inside the range is gone and counted", //$NON-NLS-1$
            doc.getColumnMerges().isEmpty());
        assertEquals(1, removed.removedMerges);
    }

    /** The saved view columns move with the operation. */
    @Test
    public void theSavedViewColumnsMove()
    {
        SpreadsheetDocument doc = withTextsAcross("A", "B", "C", "D", "E"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        ViewSettings view = MoxelFactory.eINSTANCE.createViewSettings();
        view.setCurrentColumn(4);
        doc.setViewSettings(view);

        BmTemplateHelper.insertColumns(doc, 2, 2, "none"); //$NON-NLS-1$
        assertEquals("the column the editor was on moved with it", 6, doc.getViewSettings() //$NON-NLS-1$
            .getCurrentColumn());

        BmTemplateHelper.deleteColumns(doc, 2, 2);
        assertEquals(4, doc.getViewSettings().getCurrentColumn());
    }

    /** The frozen column count grows only from a change the frozen prefix itself reaches. */
    @Test
    public void theFrozenColumnPrefixGrowsOnlyFromInside()
    {
        SpreadsheetDocument doc = withTextsAcross("A", "B", "C", "D", "E"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        ViewSettings view = MoxelFactory.eINSTANCE.createViewSettings();
        view.setFixedColumn(2);
        doc.setViewSettings(view);

        assertNull(BmTemplateHelper.insertColumns(doc, 3, 1, "none").error); //$NON-NLS-1$
        assertEquals("an insertion right below the prefix leaves the count", 2, doc //$NON-NLS-1$
            .getViewSettings().getFixedColumn());

        assertNull(BmTemplateHelper.insertColumns(doc, 2, 2, "none").error); //$NON-NLS-1$
        assertEquals("one inside the prefix grows it by the count", 4, doc.getViewSettings() //$NON-NLS-1$
            .getFixedColumn());

        assertNull(BmTemplateHelper.deleteColumns(doc, 2, 1).error);
        assertEquals("and a deletion inside it takes one off", 3, doc.getViewSettings() //$NON-NLS-1$
            .getFixedColumn());
    }

    /** A note belongs to its cell and follows the column the cell lands on. */
    @Test
    public void aCellNoteFollowsItsColumn()
    {
        SpreadsheetDocument doc = withTextsAcross("A", "B"); //$NON-NLS-1$ //$NON-NLS-2$
        Cell noted = rowAt(doc, 1).getCells().get(Integer.valueOf(1));
        noted.setNoteDrawing(MoxelFactory.eINSTANCE.createCommentDrawing());
        noted.getNoteDrawing().setCellColumnIndex(1);

        BmTemplateHelper.ColumnOutcome outcome = BmTemplateHelper.insertColumns(doc, 1, 1, "none"); //$NON-NLS-1$

        assertNull(outcome.error);
        assertEquals("the note points at the column its cell moved to", 2, //$NON-NLS-1$
            noted.getNoteDrawing().getCellColumnIndex());
    }

    /** The note's own anchors ride with the column, not just the column index they name. */
    @Test
    public void aNotesAnchorsMoveWithItsColumn()
    {
        SpreadsheetDocument doc = withTextsAcross("A", "B", "C"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Cell noted = rowAt(doc, 1).getCells().get(Integer.valueOf(1));
        noted.setNoteDrawing(MoxelFactory.eINSTANCE.createCommentDrawing());
        noted.getNoteDrawing().setCellColumnIndex(1);
        noted.getNoteDrawing().setPosition(anchorAt(0, 1));

        BmTemplateHelper.ColumnOutcome outcome = BmTemplateHelper.insertColumns(doc, 1, 1, "none"); //$NON-NLS-1$

        assertNull(outcome.error);
        assertEquals(2, noted.getNoteDrawing().getCellColumnIndex());
        assertEquals("the begin anchor moved right with the column", 2, noted.getNoteDrawing() //$NON-NLS-1$
            .getPosition().getBegin().getCell().getX());
        assertEquals("and so did the end anchor", 2, noted.getNoteDrawing().getPosition() //$NON-NLS-1$
            .getEnd().getCell().getX());

        BmTemplateHelper.ColumnOutcome deleted = BmTemplateHelper.deleteColumns(doc, 1, 1);

        assertNull(deleted.error);
        assertEquals(1, noted.getNoteDrawing().getCellColumnIndex());
        assertEquals("the anchors move back left on a deletion", 1, noted.getNoteDrawing() //$NON-NLS-1$
            .getPosition().getBegin().getCell().getX());
    }

    /** A drawing reads its data from an area, and that area rides with the columns. */
    @Test
    public void aDrawingDataSourceRidesWithTheColumns()
    {
        SpreadsheetDocument doc = withTextsAcross("A", "B", "C", "D"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Rect position = MoxelFactory.eINSTANCE.createRect();
        position.setX(1);
        position.setY(0);
        position.setWidth(1);
        position.setHeight(1);
        RectArea area = MoxelFactory.eINSTANCE.createRectArea();
        area.setPosition(position);
        DrawingsDataSource source = MoxelFactory.eINSTANCE.createDrawingsDataSource();
        source.setDrawingId(1);
        source.setArea(area);
        doc.getDrawingDataSources().add(source);

        BmTemplateHelper.ColumnOutcome outcome = BmTemplateHelper.insertColumns(doc, 1, 1, "none"); //$NON-NLS-1$

        assertNull(outcome.error);
        assertEquals("the area the drawing reads moved right with the columns", 2, //$NON-NLS-1$
            position.getX());
        assertEquals(0, outcome.removedDataSources);

        BmTemplateHelper.ColumnOutcome removed = BmTemplateHelper.deleteColumns(doc, 3, 2);

        assertNull(removed.error);
        assertTrue("a source whose area the deletion took is gone", //$NON-NLS-1$
            doc.getDrawingDataSources().isEmpty());
        assertEquals("and is counted", 1, removed.removedDataSources); //$NON-NLS-1$
    }

    /** A data area past the last cell holds the end of the document the area defines. */
    @Test
    public void aDataAreaPastTheCellsHoldsTheEndOfTheDocument()
    {
        SpreadsheetDocument doc = withTextsAcross("A", "B"); //$NON-NLS-1$ //$NON-NLS-2$
        Rect position = MoxelFactory.eINSTANCE.createRect();
        position.setX(2);
        position.setY(0);
        position.setWidth(2);
        position.setHeight(0);
        RectArea area = MoxelFactory.eINSTANCE.createRectArea();
        area.setPosition(position);
        DrawingsDataSource source = MoxelFactory.eINSTANCE.createDrawingsDataSource();
        source.setDrawingId(1);
        source.setArea(area);
        doc.getDrawingDataSources().add(source);

        BmTemplateHelper.ColumnOutcome inserted = BmTemplateHelper.insertColumns(doc, 4, 1,
            "none"); //$NON-NLS-1$

        assertNull("a column inside the data area is inside the document", inserted.error); //$NON-NLS-1$
        assertEquals("the document runs to the column the area now does", 6, //$NON-NLS-1$
            inserted.lastColumn);
        assertEquals("the area grew over the inserted column without moving", 3, //$NON-NLS-1$
            position.getWidth());

        BmTemplateHelper.ColumnOutcome deleted = BmTemplateHelper.deleteColumns(doc, 4, 1);

        assertNull(deleted.error);
        assertEquals("and back to the column it ends at after the deletion", 5, //$NON-NLS-1$
            deleted.lastColumn);
        assertEquals(2, position.getWidth());
    }

    /** A merge and an area wholly right of an insertion move; one the point lands inside resizes. */
    @Test
    public void aMergeAndAnAreaWhollyRightOfThePointMoveUnresized()
    {
        SpreadsheetDocument doc = withTextsAcross("A", "B"); //$NON-NLS-1$ //$NON-NLS-2$
        BmTemplateHelper.mergeCells(doc, 1, 4, 1, 5);
        com._1c.g5.v8.dt.moxel.ColumnMerge wholeColumns =
            MoxelFactory.eINSTANCE.createColumnMerge();
        wholeColumns.setBegin(6);
        wholeColumns.setEnd(7);
        doc.getColumnMerges().add(wholeColumns);
        BmTemplateHelper.addNamedArea(doc, "Право", "columns", 0, 8, 0, 9); //$NON-NLS-1$ //$NON-NLS-2$

        BmTemplateHelper.ColumnOutcome moved = BmTemplateHelper.insertColumns(doc, 2, 1, "none"); //$NON-NLS-1$

        assertNull(moved.error);
        assertEquals("a merge wholly right of the point only moved", 0, moved.resizedMerges); //$NON-NLS-1$
        assertFalse("and so did the area, without being named", //$NON-NLS-1$
            moved.resizedNamedAreas.contains("Право")); //$NON-NLS-1$
        assertEquals("the merge sits where the shift put it", Integer.valueOf(5), //$NON-NLS-1$
            onlyMerge(doc).get("fromCol")); //$NON-NLS-1$
        assertEquals("the whole-column merge moved uncounted", 7, doc.getColumnMerges().get(0) //$NON-NLS-1$
            .getBegin());
        assertEquals("the area moved to where its columns went", Integer.valueOf(9), //$NON-NLS-1$
            areaNamed(doc, "Право").get("fromCol")); //$NON-NLS-1$ //$NON-NLS-2$

        BmTemplateHelper.ColumnOutcome grown = BmTemplateHelper.insertColumns(doc, 6, 1, "none"); //$NON-NLS-1$

        assertNull(grown.error);
        assertEquals("the point now lands inside the merge, which grows", 1, grown.resizedMerges); //$NON-NLS-1$

        BmTemplateHelper.ColumnOutcome areaGrown = BmTemplateHelper.insertColumns(doc, 11, 1,
            "none"); //$NON-NLS-1$

        assertNull(areaGrown.error);
        assertEquals(0, areaGrown.resizedMerges);
        assertTrue("the point now lands inside the area, which is named", //$NON-NLS-1$
            areaGrown.resizedNamedAreas.contains("Право")); //$NON-NLS-1$
    }

    /** Copying past a set's declared size grows the set; a set declaring none keeps declaring none. */
    @Test
    public void copyingPastTheDeclaredSizeGrowsTheSet()
    {
        SpreadsheetDocument doc = withTextsAcross("A"); //$NON-NLS-1$
        Columns declared = setWithColumn(doc, 1, 5);

        BmTemplateHelper.ColumnOutcome outcome = BmTemplateHelper.copyColumns(doc, 1, 4, 1);

        assertNull(outcome.error);
        assertEquals("the declared size grew to the column past the last one written", 4, //$NON-NLS-1$
            declared.getSize());
        assertEquals("and the copied entry sits inside the declared extent", 5, //$NON-NLS-1$
            declared.getColumns().get(Integer.valueOf(3)).getFormatIndex());

        Columns undeclared = MoxelFactory.eINSTANCE.createColumns();
        undeclared.setSize(0);
        Column column = MoxelFactory.eINSTANCE.createColumn();
        column.setFormatIndex(6);
        undeclared.getColumns().put(Integer.valueOf(0), column);
        doc.getAllColumns().add(undeclared);

        BmTemplateHelper.ColumnOutcome untouched = BmTemplateHelper.copyColumns(doc, 1, 6, 1);

        assertNull(untouched.error);
        assertEquals("a set that declares no size keeps declaring none", 0, //$NON-NLS-1$
            undeclared.getSize());
        assertEquals("while the entry itself still lands where the copy put it", 6, //$NON-NLS-1$
            undeclared.getColumns().get(Integer.valueOf(5)).getFormatIndex());
    }

    /** An unmerge exception repeats over the target columns the way its merge does, uncounted. */
    @Test
    public void unmergeExceptionsRepeatOverTheTargetColumns()
    {
        SpreadsheetDocument doc = withTextsAcross("A", "B", "C", "D", "E", "F"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        doc.getUnmerges().add(unmergeOver(1, 2, 2, 3));
        doc.getUnmerges().add(unmergeOver(1, 5, 1, 5));

        BmTemplateHelper.ColumnOutcome outcome = BmTemplateHelper.copyColumns(doc, 2, 5, 2);

        assertNull(outcome.error);
        assertEquals("the counters stay about merges", 0, outcome.removedMerges); //$NON-NLS-1$
        assertEquals("the target exception came off and the source one repeated over it", 2, //$NON-NLS-1$
            doc.getUnmerges().size());
        boolean kept = false;
        boolean repeated = false;
        for (Merge unmerge : doc.getUnmerges())
        {
            kept |= unmerge.getPosition().getX() == 1
                && unmerge.getPosition().getWidth() == 1;
            repeated |= unmerge.getPosition().getX() == 4
                && unmerge.getPosition().getWidth() == 1;
        }
        assertTrue("the source exception stays where it was", kept); //$NON-NLS-1$
        assertTrue("and a copy of it, not the target's own exception, sits on the target columns", //$NON-NLS-1$
            repeated);
    }

    /** Copying replaces the target columns with what the source columns are, whole. */
    @Test
    public void copyingReplacesTheTargetWithTheSource()
    {
        SpreadsheetDocument doc = withTextsAcross("A", "B", "C"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull(BmTemplateHelper.setCellContent(doc, 2, 1, null, false, "ru", "parameter", //$NON-NLS-1$ //$NON-NLS-2$
            "Товар")); //$NON-NLS-1$
        BmTemplateHelper.applyCellFormat(doc, 1, 1, 1, 1, null, null, null, null,
            Integer.valueOf(30), null, null);
        Columns docSet = doc.getColumns();
        int sourceFormat = docSet.getColumns().get(Integer.valueOf(0)).getFormatIndex();
        BmTemplateHelper.setCellText(doc, 1, 5, "Прежний столбец 5", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        BmTemplateHelper.setCellText(doc, 1, 6, "Прежний столбец 6", "ru"); //$NON-NLS-1$ //$NON-NLS-2$

        BmTemplateHelper.ColumnOutcome outcome = BmTemplateHelper.copyColumns(doc, 1, 5, 2);

        assertNull(outcome.error);
        assertEquals("the target column is what the source column is", "A", textAt(doc, 5)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("B", textAt(doc, 6)); //$NON-NLS-1$
        assertEquals("the parameter came with the copy", "Товар", //$NON-NLS-1$
            rowAt(doc, 2).getCells().get(Integer.valueOf(4)).getParameter());
        assertEquals("the column entry came with the copy", sourceFormat, //$NON-NLS-1$
            docSet.getColumns().get(Integer.valueOf(4)).getFormatIndex());
        assertEquals("the source stays where it was", "A", textAt(doc, 1)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("nothing shifted", 0, outcome.shiftedColumns); //$NON-NLS-1$
        assertEquals(6, outcome.lastColumn);
    }

    /** A merge fully inside the source columns repeats over the target. */
    @Test
    public void aMergeInsideTheSourceRepeatsOverTheTarget()
    {
        SpreadsheetDocument doc = emptyDocument();
        BmTemplateHelper.mergeCells(doc, 1, 1, 2, 2);
        BmTemplateHelper.mergeCells(doc, 1, 4, 2, 5);

        BmTemplateHelper.ColumnOutcome outcome = BmTemplateHelper.copyColumns(doc, 1, 4, 2);

        assertNull(outcome.error);
        assertEquals("the merge fully inside the replaced target came off", 1, //$NON-NLS-1$
            outcome.removedMerges);
        assertEquals("and the source merge repeated over it", 2, doc.getMerges().size()); //$NON-NLS-1$
        Map<String, Object> read = readOf(doc);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> merges = (List<Map<String, Object>>)read.get("merges"); //$NON-NLS-1$
        boolean repeated = false;
        for (Map<String, Object> merge : merges)
        {
            if (Integer.valueOf(4).equals(merge.get("fromCol")) //$NON-NLS-1$
                && Integer.valueOf(5).equals(merge.get("toCol"))) //$NON-NLS-1$
            {
                repeated = true;
            }
        }
        assertTrue("the copy of the source merge sits on the target columns", repeated); //$NON-NLS-1$
    }

    /** A whole-column merge repeats over the target like any other merge a copy replaces. */
    @Test
    public void wholeColumnMergesRepeatOverTheTarget()
    {
        SpreadsheetDocument doc = withTextsAcross("A", "B"); //$NON-NLS-1$ //$NON-NLS-2$
        com._1c.g5.v8.dt.moxel.ColumnMerge source =
            MoxelFactory.eINSTANCE.createColumnMerge();
        source.setBegin(0);
        source.setEnd(1);
        doc.getColumnMerges().add(source);
        com._1c.g5.v8.dt.moxel.ColumnMerge replaced =
            MoxelFactory.eINSTANCE.createColumnMerge();
        replaced.setBegin(3);
        replaced.setEnd(4);
        doc.getColumnMerges().add(replaced);

        BmTemplateHelper.ColumnOutcome outcome = BmTemplateHelper.copyColumns(doc, 1, 4, 2);

        assertNull(outcome.error);
        assertEquals("the whole-column merge inside the replaced target came off", 1, //$NON-NLS-1$
            outcome.removedMerges);
        assertEquals("and the source one repeated over it", 2, doc.getColumnMerges().size()); //$NON-NLS-1$
        boolean sourceKept = false;
        boolean repeated = false;
        for (com._1c.g5.v8.dt.moxel.ColumnMerge merge : doc.getColumnMerges())
        {
            sourceKept |= merge.getBegin() == 0 && merge.getEnd() == 1;
            repeated |= merge.getBegin() == 3 && merge.getEnd() == 4;
        }
        assertTrue("the source merge stays where it was", sourceKept); //$NON-NLS-1$
        assertTrue("and its copy sits on the target columns", repeated); //$NON-NLS-1$
    }

    /** The target may run past the current end; the document grows to hold it. */
    @Test
    public void theTargetMayRunPastTheEnd()
    {
        SpreadsheetDocument doc = withTextsAcross("A", "B"); //$NON-NLS-1$ //$NON-NLS-2$

        BmTemplateHelper.ColumnOutcome outcome = BmTemplateHelper.copyColumns(doc, 1, 4, 2);

        assertNull(outcome.error);
        assertEquals("A", textAt(doc, 4)); //$NON-NLS-1$
        assertEquals("B", textAt(doc, 5)); //$NON-NLS-1$
        assertEquals(5, outcome.lastColumn);
    }

    /** Every refusal leaves the document exactly as it was. */
    @Test
    public void refusalsLeaveTheDocumentAsItWas()
    {
        SpreadsheetDocument doc = withTextsAcross("A", "B", "C"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        BmTemplateHelper.mergeCells(doc, 1, 1, 2, 2);
        BmTemplateHelper.addNamedArea(doc, "Шапка", "columns", 0, 1, 0, 2); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> before = readOf(doc);

        refusedLeavingTheDocumentAsItWas(doc,
            BmTemplateHelper.insertColumns(doc, 0, 1, "none"), before); //$NON-NLS-1$
        refusedLeavingTheDocumentAsItWas(doc,
            BmTemplateHelper.insertColumns(doc, 1, 0, "none"), before); //$NON-NLS-1$
        refusedLeavingTheDocumentAsItWas(doc,
            BmTemplateHelper.insertColumns(doc, 5, 1, "none"), before); //$NON-NLS-1$
        refusedLeavingTheDocumentAsItWas(doc,
            BmTemplateHelper.insertColumns(doc, 1, 1, "sideways"), before); //$NON-NLS-1$
        refusedLeavingTheDocumentAsItWas(doc,
            BmTemplateHelper.insertColumns(doc, 1, 1, "above"), before); //$NON-NLS-1$
        refusedLeavingTheDocumentAsItWas(doc, BmTemplateHelper.deleteColumns(doc, 3, 2), before);
        refusedLeavingTheDocumentAsItWas(doc, BmTemplateHelper.deleteColumns(doc, 0, 1), before);
        refusedLeavingTheDocumentAsItWas(doc, BmTemplateHelper.copyColumns(doc, 3, 1, 2), before);
        refusedLeavingTheDocumentAsItWas(doc, BmTemplateHelper.copyColumns(doc, 1, 2, 2), before);
        refusedLeavingTheDocumentAsItWas(doc, BmTemplateHelper.copyColumns(doc, 0, 1, 1), before);
    }

    /** The refusal names the argument that was wrong. */
    @Test
    public void theRefusalsNameWhatWasWrong()
    {
        assertTrue(BmTemplateHelper.insertColumns(emptyDocument(), 1, 1, "sideways").error //$NON-NLS-1$
            .contains("formatFrom")); //$NON-NLS-1$
        assertTrue(BmTemplateHelper.deleteColumns(emptyDocument(), 9, 2).error.contains("past")); //$NON-NLS-1$
        assertTrue(BmTemplateHelper.copyColumns(withTextsAcross("A"), 1, 1, 1).error //$NON-NLS-1$
            .contains("overlap")); //$NON-NLS-1$
    }

    /** A formatFrom word of the other axis is refused on both axes, not read as the default. */
    @Test
    public void aFormatFromOfTheOtherAxisIsRefusedOnBothAxes()
    {
        assertNotNull(BmTemplateHelper.insertColumns(emptyDocument(), 1, 1, "above").error); //$NON-NLS-1$
        assertTrue(BmTemplateHelper.insertColumns(emptyDocument(), 1, 1, "above").error //$NON-NLS-1$
            .contains("left")); //$NON-NLS-1$
        assertNotNull(BmTemplateHelper.insertRows(emptyDocument(), 1, 1, "left").error); //$NON-NLS-1$
        assertTrue(BmTemplateHelper.insertRows(emptyDocument(), 1, 1, "left").error //$NON-NLS-1$
            .contains("above")); //$NON-NLS-1$
    }

    /** Inserting columns and deleting the same columns returns the document it started from. */
    @Test
    public void insertingThenDeletingTheSameColumnsReturnsTheStart()
    {
        SpreadsheetDocument doc = withTextsAcross("A", "B", "C", "D"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        BmTemplateHelper.mergeCells(doc, 1, 2, 2, 3);
        BmTemplateHelper.addNamedArea(doc, "Блок", "columns", 0, 2, 0, 3); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> before = readOf(doc);

        assertNull(BmTemplateHelper.insertColumns(doc, 2, 2, "none").error); //$NON-NLS-1$
        assertNull(BmTemplateHelper.deleteColumns(doc, 2, 2).error);

        assertEquals("insert and delete of the same columns is the identity", before, readOf(doc)); //$NON-NLS-1$
    }

    /** The same round trip with formats carried by the new columns. */
    @Test
    public void formattedInsertsDeleteCleanlyToo()
    {
        SpreadsheetDocument doc = withTextsAcross("A", "B", "C"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        BmTemplateHelper.applyCellFormat(doc, 1, 1, 3, 1, null, null, null, null,
            Integer.valueOf(30), null, null);
        Map<String, Object> before = readOf(doc);

        assertNull(BmTemplateHelper.insertColumns(doc, 2, 2, "left").error); //$NON-NLS-1$
        Columns docSet = doc.getColumns();
        assertNotNull(docSet.getColumns().get(Integer.valueOf(1)));
        assertNotNull(docSet.getColumns().get(Integer.valueOf(2)));

        assertNull(BmTemplateHelper.deleteColumns(doc, 2, 2).error);
        assertEquals(before, readOf(doc));
    }

    /**
     * A rectangle of one column carries 0 in its width, the way the template file is read:
     * beginColumn and endColumn of the same value. It rides with the columns like any other area.
     */
    @Test
    public void aRectangleOfOneColumnRidesWithTheColumns()
    {
        SpreadsheetDocument doc = withTextsAcross("A", "B", "C"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Rect position = MoxelFactory.eINSTANCE.createRect();
        position.setX(1);
        position.setY(0);
        position.setWidth(0);
        position.setHeight(0);
        RectArea area = MoxelFactory.eINSTANCE.createRectArea();
        area.setPosition(position);
        com._1c.g5.v8.dt.moxel.NamedItemCells item = MoxelFactory.eINSTANCE.createNamedItemCells();
        item.setArea(area);
        doc.getNamedItems().put("Ячейка", item); //$NON-NLS-1$

        BmTemplateHelper.ColumnOutcome inserted = BmTemplateHelper.insertColumns(doc, 2, 2,
            "none"); //$NON-NLS-1$

        assertNull(inserted.error);
        assertEquals("the area stood on column 2 and the columns went in before it", 3, //$NON-NLS-1$
            position.getX());
        assertEquals("one column still", 0, position.getWidth()); //$NON-NLS-1$
        Map<String, Object> read = areaNamed(doc, "Ячейка"); //$NON-NLS-1$
        assertEquals(Integer.valueOf(4), read.get("fromCol")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(4), read.get("toCol")); //$NON-NLS-1$

        BmTemplateHelper.ColumnOutcome deleted = BmTemplateHelper.deleteColumns(doc, 4, 1);

        assertNull(deleted.error);
        assertTrue("the one column the area held is gone, and the area with it", //$NON-NLS-1$
            deleted.removedNamedAreas.contains("Ячейка")); //$NON-NLS-1$
    }

    /** An empty template answers both axes the same: the first position is open, the second is not. */
    @Test
    public void anEmptyTemplateTakesColumnOneAndRefusesColumnTwo()
    {
        SpreadsheetDocument doc = emptyDocument();
        doc.getRows().put(Integer.valueOf(0), MoxelFactory.eINSTANCE.createRow());
        doc.getRows().put(Integer.valueOf(1), MoxelFactory.eINSTANCE.createRow());
        doc.setColumns(MoxelFactory.eINSTANCE.createColumns());

        BmTemplateHelper.ColumnOutcome refused = BmTemplateHelper.insertColumns(doc, 2, 1,
            "none"); //$NON-NLS-1$
        assertNotNull("a column set of size 0 does not make column 2 a column of the document", //$NON-NLS-1$
            refused.error);
        assertTrue(refused.error.contains("past the end")); //$NON-NLS-1$
        assertNull("column 1 is the first column an empty document takes", //$NON-NLS-1$
            BmTemplateHelper.insertColumns(doc, 1, 1, "none").error); //$NON-NLS-1$
        assertNotNull("and the bare row entries do not open row 2 either", //$NON-NLS-1$
            BmTemplateHelper.insertRows(doc, 2, 1, "none").error); //$NON-NLS-1$
    }
}
