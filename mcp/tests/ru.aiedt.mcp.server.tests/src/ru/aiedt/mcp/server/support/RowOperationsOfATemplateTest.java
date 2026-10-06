/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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
import com._1c.g5.v8.dt.moxel.RowGroup;
import com._1c.g5.v8.dt.moxel.RowMerge;
import com._1c.g5.v8.dt.moxel.RowsArea;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.moxel.SpreadsheetPoint;
import com._1c.g5.v8.dt.moxel.SpreadsheetRect;
import com._1c.g5.v8.dt.moxel.ViewSettings;

/**
 * Rows of a spreadsheet template, inserted, deleted and copied whole.
 * <p>
 * A row is not one thing in this model: the row with its cells and notes, the merges, the named
 * areas, the row groups, the drawings, the print and repeat areas, the declared height and the
 * saved view rows all carry a row number. An operation that moves the rows and leaves any of those
 * behind leaves the template pointing at content that moved, so every holder is checked here
 * against the same call.
 * </p>
 * <p>
 * The model is built in memory with the moxel factory; coordinates on the class under test are
 * 1-based and 0-based in the model, the split every other coordinate here lives with.
 * </p>
 */
public class RowOperationsOfATemplateTest
{
    private static SpreadsheetDocument emptyDocument()
    {
        return MoxelFactory.eINSTANCE.createSpreadsheetDocument();
    }

    private static SpreadsheetDocument withTexts(String... texts)
    {
        SpreadsheetDocument doc = emptyDocument();
        for (int i = 0; i < texts.length; i++)
        {
            BmTemplateHelper.setCellText(doc, i + 1, 1, texts[i], "ru"); //$NON-NLS-1$
        }
        return doc;
    }

    private static String textAt(SpreadsheetDocument doc, int row)
    {
        Row held = doc.getRows().get(Integer.valueOf(row - 1));
        Cell cell = held == null ? null : held.getCells().get(Integer.valueOf(0));
        return cell == null || cell.getText() == null
            ? null : cell.getText().getContent().get("ru"); //$NON-NLS-1$
    }

    private static Row rowAt(SpreadsheetDocument doc, int row)
    {
        return doc.getRows().get(Integer.valueOf(row - 1));
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
        BmTemplateHelper.RowOutcome outcome, Map<String, Object> before)
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

    /** Inserting in the middle moves the rows below and leaves the rows above alone. */
    @Test
    public void insertingInTheMiddleMovesOnlyTheRowsBelow()
    {
        SpreadsheetDocument doc = withTexts("A", "B", "C", "D"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        BmTemplateHelper.RowOutcome outcome = BmTemplateHelper.insertRows(doc, 3, 2, "none"); //$NON-NLS-1$

        assertNull(outcome.error);
        assertEquals("the row above the insertion keeps its number", "A", textAt(doc, 1)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("B", textAt(doc, 2)); //$NON-NLS-1$
        assertNull("the new rows are empty", textAt(doc, 3)); //$NON-NLS-1$
        assertNull(textAt(doc, 4));
        assertEquals("the row below lands two rows further down", "C", textAt(doc, 5)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("D", textAt(doc, 6)); //$NON-NLS-1$
        assertEquals("the two rows below changed their number", 2, outcome.shiftedRows); //$NON-NLS-1$
        assertEquals(6, outcome.lastRow);
    }

    /** Inserting at the very end is allowed, at the row past that it is not. */
    @Test
    public void insertingPastTheEndIsRefused()
    {
        SpreadsheetDocument doc = withTexts("A", "B"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> before = readOf(doc);

        BmTemplateHelper.RowOutcome atTheEnd = BmTemplateHelper.insertRows(doc, 3, 1, "none"); //$NON-NLS-1$
        assertNull(atTheEnd.error);
        assertEquals("appending shifts nothing", 0, atTheEnd.shiftedRows); //$NON-NLS-1$
        assertEquals("A", textAt(doc, 1)); //$NON-NLS-1$
        assertEquals("B", textAt(doc, 2)); //$NON-NLS-1$

        refusedLeavingTheDocumentAsItWas(doc, BmTemplateHelper.insertRows(doc, 4, 1, "none"), //$NON-NLS-1$
            before);
    }

    /** The new rows take the row format and the cell formats the formatFrom row carries. */
    @Test
    public void theNewRowsTakeTheirFormatsFromTheRowNamedByFormatFrom()
    {
        SpreadsheetDocument doc = withTexts("A", "B", "C"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        BmTemplateHelper.applyCellFormat(doc, 1, 1, 1, 2, null, null, Integer.valueOf(30), null,
            null, null);
        int rowOneFormat = rowAt(doc, 1).getFormatIndex();
        BmTemplateHelper.RowOutcome fromAbove = BmTemplateHelper.insertRows(doc, 2, 1, "above"); //$NON-NLS-1$
        assertNull(fromAbove.error);
        assertEquals("above: the row format of the row over the point", rowOneFormat, //$NON-NLS-1$
            rowAt(doc, 2).getFormatIndex());

        SpreadsheetDocument lower = withTexts("A", "B", "C"); //$NON-NLS-1$
        BmTemplateHelper.applyCellFormat(lower, 3, 1, 3, 1, null, null, Integer.valueOf(12), null,
            null, null);
        int pointFormat = rowAt(lower, 3).getFormatIndex();

        BmTemplateHelper.RowOutcome fromBelow = BmTemplateHelper.insertRows(lower, 3, 1, "below"); //$NON-NLS-1$

        assertNull(fromBelow.error);
        assertEquals("below: the row format of the row at the point", pointFormat, //$NON-NLS-1$
            rowAt(lower, 3).getFormatIndex());
        assertEquals("C", textAt(lower, 4)); //$NON-NLS-1$

        BmTemplateHelper.RowOutcome fromNone = BmTemplateHelper.insertRows(doc, 2, 1, "none"); //$NON-NLS-1$
        assertNull(fromNone.error);
        assertNull("none: no row is written at all", rowAt(doc, 2)); //$NON-NLS-1$

        SpreadsheetDocument fresh = withTexts("A"); //$NON-NLS-1$
        BmTemplateHelper.RowOutcome aboveTheTop = BmTemplateHelper.insertRows(fresh, 1, 1, "above"); //$NON-NLS-1$
        assertNull(aboveTheTop.error);
        assertNull("above at row 1 reads as none - there is no row over the top", //$NON-NLS-1$
            rowAt(fresh, 1));
        assertEquals("A", textAt(fresh, 2)); //$NON-NLS-1$
    }

    /** A cell format rides along on the copy; text and parameters never do. */
    @Test
    public void cellFormatsRideAlongAndTextDoesNot()
    {
        SpreadsheetDocument doc = withTexts("A"); //$NON-NLS-1$
        BmTemplateHelper.CellLook border = new BmTemplateHelper.CellLook();
        border.border = "Solid"; //$NON-NLS-1$
        BmTemplateHelper.applyCellFormat(doc, 1, 2, 1, 2, null, null, null, null, null, null,
            border);
        int bordered = rowAt(doc, 1).getCells().get(Integer.valueOf(1)).getFormatIndex();

        BmTemplateHelper.RowOutcome outcome = BmTemplateHelper.insertRows(doc, 2, 1, "above"); //$NON-NLS-1$

        assertNull(outcome.error);
        Row inserted = rowAt(doc, 2);
        assertNotNull(inserted);
        Cell carried = inserted.getCells().get(Integer.valueOf(1));
        assertNotNull("a formatted cell of the source is a formatted cell of the copy", //$NON-NLS-1$
            carried);
        assertEquals(bordered, carried.getFormatIndex());
        assertNull("the text does not ride along", carried.getText()); //$NON-NLS-1$
        assertNull(carried.getParameter());
        assertNull("a cell with no format of its own is not materialized", //$NON-NLS-1$
            inserted.getCells().get(Integer.valueOf(0)));
    }

    /** A source row's own column set is what the new rows read their column formats from. */
    @Test
    public void theNewRowCarriesTheSourceRowsOwnColumns()
    {
        SpreadsheetDocument doc = withTexts("A", "B"); //$NON-NLS-1$ //$NON-NLS-2$
        Columns own = MoxelFactory.eINSTANCE.createColumns();
        own.setSize(1);
        Column column = MoxelFactory.eINSTANCE.createColumn();
        column.setFormatIndex(7);
        own.getColumns().put(Integer.valueOf(0), column);
        doc.getAllColumns().add(own);
        rowAt(doc, 1).setColumns(own);

        BmTemplateHelper.RowOutcome outcome = BmTemplateHelper.insertRows(doc, 2, 1, "above"); //$NON-NLS-1$

        assertNull(outcome.error);
        Row inserted = rowAt(doc, 2);
        assertNotNull("a source with a column set of its own materializes the new row", //$NON-NLS-1$
            inserted);
        assertSame("the new row reads the same set the source row reads", own, //$NON-NLS-1$
            inserted.getColumns());
    }

    /** An insertion inside a vertical merge grows it; one above leaves it, one below moves it. */
    @Test
    public void anInsertInsideAMergeGrowsIt()
    {
        SpreadsheetDocument doc = emptyDocument();
        BmTemplateHelper.mergeCells(doc, 2, 1, 4, 1);

        BmTemplateHelper.RowOutcome outcome = BmTemplateHelper.insertRows(doc, 3, 1, "none"); //$NON-NLS-1$

        assertNull(outcome.error);
        Map<String, Object> merge = onlyMerge(doc);
        assertEquals("the merge keeps its top", Integer.valueOf(2), merge.get("fromRow")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("and gains the inserted row", Integer.valueOf(5), merge.get("toRow")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, outcome.resizedMerges);
    }

    @Test
    public void anInsertAboveAMergeLeavesItAndBelowMovesIt()
    {
        SpreadsheetDocument doc = emptyDocument();
        BmTemplateHelper.mergeCells(doc, 3, 1, 4, 1);

        BmTemplateHelper.insertRows(doc, 5, 1, "none"); //$NON-NLS-1$
        Map<String, Object> merge = onlyMerge(doc);
        assertEquals("an insert below the merge leaves it where it is", Integer.valueOf(3), //$NON-NLS-1$
            merge.get("fromRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(4), merge.get("toRow")); //$NON-NLS-1$

        BmTemplateHelper.insertRows(doc, 1, 2, "none"); //$NON-NLS-1$
        merge = onlyMerge(doc);
        assertEquals("an insert above the merge moves it down", Integer.valueOf(5), //$NON-NLS-1$
            merge.get("fromRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(6), merge.get("toRow")); //$NON-NLS-1$

        BmTemplateHelper.insertRows(doc, 7, 1, "none"); //$NON-NLS-1$
        merge = onlyMerge(doc);
        assertEquals("an insert appended below changes nothing above it", Integer.valueOf(5), //$NON-NLS-1$
            merge.get("fromRow")); //$NON-NLS-1$

        BmTemplateHelper.insertRows(doc, 5, 1, "none"); //$NON-NLS-1$
        merge = onlyMerge(doc);
        assertEquals("an insert over the merge's top moves it down", Integer.valueOf(6), //$NON-NLS-1$
            merge.get("fromRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(7), merge.get("toRow")); //$NON-NLS-1$
    }

    /** Inserting inside a rows area and a rectangle grows both; an area below moves. */
    @Test
    public void anInsertInsideNamedAreasGrowsThem()
    {
        SpreadsheetDocument doc = emptyDocument();
        BmTemplateHelper.addNamedArea(doc, "Строки", "rows", 2, 0, 4, 0); //$NON-NLS-1$ //$NON-NLS-2$
        BmTemplateHelper.addNamedArea(doc, "Блок", "rect", 3, 1, 5, 4); //$NON-NLS-1$ //$NON-NLS-2$
        BmTemplateHelper.addNamedArea(doc, "Низ", "rows", 7, 0, 8, 0); //$NON-NLS-1$ //$NON-NLS-2$

        BmTemplateHelper.RowOutcome outcome = BmTemplateHelper.insertRows(doc, 4, 1, "none"); //$NON-NLS-1$

        assertNull(outcome.error);
        assertTrue("the rows area the point falls inside is named as resized", //$NON-NLS-1$
            outcome.resizedNamedAreas.contains("Строки")); //$NON-NLS-1$
        assertTrue("and so is the rectangle", outcome.resizedNamedAreas.contains("Блок")); //$NON-NLS-1$
        List<Map<String, Object>> areas = BmTemplateHelper.listNamedAreas(doc);
        for (Map<String, Object> area : areas)
        {
            if ("Строки".equals(area.get("name"))) //$NON-NLS-1$ //$NON-NLS-2$
            {
                assertEquals("the rows area grew by the count", Integer.valueOf(2), //$NON-NLS-1$
                    area.get("fromRow")); //$NON-NLS-1$
                assertEquals(Integer.valueOf(5), area.get("toRow")); //$NON-NLS-1$
            }
            if ("Блок".equals(area.get("name"))) //$NON-NLS-1$ //$NON-NLS-2$
            {
                assertEquals("the rectangle grew the same way", Integer.valueOf(3), //$NON-NLS-1$
                    area.get("fromRow")); //$NON-NLS-1$
                assertEquals(Integer.valueOf(6), area.get("toRow")); //$NON-NLS-1$
            }
            if ("Низ".equals(area.get("name"))) //$NON-NLS-1$ //$NON-NLS-2$
            {
                assertEquals("the area below moved down", Integer.valueOf(8), //$NON-NLS-1$
                    area.get("fromRow")); //$NON-NLS-1$
            }
        }
    }

    /** A columns area holds no rows, so a row insertion has nothing to say to it. */
    @Test
    public void aColumnsAreaIsNotMovedByRows()
    {
        SpreadsheetDocument doc = withTexts("A", "B"); //$NON-NLS-1$
        BmTemplateHelper.addNamedArea(doc, "Колонка", "columns", 0, 1, 0, 3); //$NON-NLS-1$ //$NON-NLS-2$

        BmTemplateHelper.RowOutcome outcome = BmTemplateHelper.insertRows(doc, 2, 1, "none"); //$NON-NLS-1$

        assertNull(outcome.error);
        assertTrue(outcome.resizedNamedAreas.isEmpty());
        Map<String, Object> area = BmTemplateHelper.listNamedAreas(doc).get(0);
        assertEquals(Integer.valueOf(1), area.get("fromCol")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(3), area.get("toCol")); //$NON-NLS-1$
    }

    /** The row past a rectangular area is not the area's: inserting or deleting it changes nothing. */
    @Test
    public void aRowPastARectangleLeavesIt()
    {
        SpreadsheetDocument doc = withTexts("A", "B", "C", "D", "E", "F"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        BmTemplateHelper.addNamedArea(doc, "Блок", "rect", 3, 1, 5, 4); //$NON-NLS-1$ //$NON-NLS-2$

        BmTemplateHelper.RowOutcome afterInsert = BmTemplateHelper.insertRows(doc, 6, 1, "none"); //$NON-NLS-1$

        assertNull(afterInsert.error);
        assertFalse("the row past the area does not stretch it", //$NON-NLS-1$
            afterInsert.resizedNamedAreas.contains("Блок")); //$NON-NLS-1$
        Map<String, Object> area = areaNamed(doc, "Блок"); //$NON-NLS-1$
        assertEquals(Integer.valueOf(3), area.get("fromRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(5), area.get("toRow")); //$NON-NLS-1$

        BmTemplateHelper.RowOutcome afterDelete = BmTemplateHelper.deleteRows(doc, 6, 1);

        assertNull(afterDelete.error);
        area = areaNamed(doc, "Блок"); //$NON-NLS-1$
        assertEquals("the row past the area does not shrink it either", Integer.valueOf(3), //$NON-NLS-1$
            area.get("fromRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(5), area.get("toRow")); //$NON-NLS-1$
    }

    /** Inserting over the rectangle's last row stretches it by the count. */
    @Test
    public void insertingOverTheLastRowOfARectangleStretchesIt()
    {
        SpreadsheetDocument doc = emptyDocument();
        BmTemplateHelper.addNamedArea(doc, "Блок", "rect", 1, 1, 2, 3); //$NON-NLS-1$ //$NON-NLS-2$

        BmTemplateHelper.RowOutcome outcome = BmTemplateHelper.insertRows(doc, 2, 2, "none"); //$NON-NLS-1$

        assertNull(outcome.error);
        assertTrue(outcome.resizedNamedAreas.contains("Блок")); //$NON-NLS-1$
        Map<String, Object> area = areaNamed(doc, "Блок"); //$NON-NLS-1$
        assertEquals("the first row of the area stays", Integer.valueOf(1), area.get("fromRow")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("and the last one stretches by the count", Integer.valueOf(4), //$NON-NLS-1$
            area.get("toRow")); //$NON-NLS-1$
        assertEquals("the document ends where the area now does", 4, outcome.lastRow); //$NON-NLS-1$
    }

    /** Deleting the rectangle's last row takes exactly that row off the area. */
    @Test
    public void deletingTheLastRowOfARectangleShrinksIt()
    {
        SpreadsheetDocument doc = emptyDocument();
        BmTemplateHelper.addNamedArea(doc, "Блок", "rect", 1, 1, 3, 3); //$NON-NLS-1$ //$NON-NLS-2$

        BmTemplateHelper.RowOutcome outcome = BmTemplateHelper.deleteRows(doc, 3, 1);

        assertNull(outcome.error);
        Map<String, Object> area = areaNamed(doc, "Блок"); //$NON-NLS-1$
        assertEquals(Integer.valueOf(1), area.get("fromRow")); //$NON-NLS-1$
        assertEquals("the area lost the row it lost", Integer.valueOf(2), area.get("toRow")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the document ends where the area now does", 2, outcome.lastRow); //$NON-NLS-1$
    }

    /** Deleting shifts the rows up and names what the range swallowed. */
    @Test
    public void deletingShiftsTheRowsBelowUp()
    {
        SpreadsheetDocument doc = withTexts("A", "B", "C", "D", "E"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        BmTemplateHelper.RowOutcome outcome = BmTemplateHelper.deleteRows(doc, 2, 2);

        assertNull(outcome.error);
        assertEquals("A", textAt(doc, 1)); //$NON-NLS-1$
        assertEquals("the rows below moved up by the count", "D", textAt(doc, 2)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("E", textAt(doc, 3)); //$NON-NLS-1$
        assertNull(textAt(doc, 4));
        assertEquals("two rows below the range moved up", 2, outcome.shiftedRows); //$NON-NLS-1$
        assertEquals(3, outcome.lastRow);
    }

    /** A merge and an area entirely inside the deleted range go with it and are named. */
    @Test
    public void whatLiesEntirelyInsideTheDeletedRangeGoesAndIsNamed()
    {
        SpreadsheetDocument doc = withTexts("A", "B", "C", "D", "E", "F"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        BmTemplateHelper.mergeCells(doc, 3, 1, 4, 2);
        BmTemplateHelper.addNamedArea(doc, "Середина", "rows", 3, 0, 4, 0); //$NON-NLS-1$ //$NON-NLS-2$

        BmTemplateHelper.RowOutcome outcome = BmTemplateHelper.deleteRows(doc, 3, 2);

        assertNull(outcome.error);
        assertEquals("the merge inside the range is counted as removed", 1, outcome.removedMerges); //$NON-NLS-1$
        assertTrue(doc.getMerges().isEmpty());
        assertTrue("the area inside the range is named", //$NON-NLS-1$
            outcome.removedNamedAreas.contains("Середина")); //$NON-NLS-1$
        assertTrue(doc.getNamedItems().isEmpty());
    }

    /** A merge the range cuts in half shrinks by the rows it lost. */
    @Test
    public void aMergeTheRangeCutsShrinks()
    {
        SpreadsheetDocument doc = emptyDocument();
        BmTemplateHelper.mergeCells(doc, 2, 1, 5, 1);

        BmTemplateHelper.RowOutcome outcome = BmTemplateHelper.deleteRows(doc, 4, 2);

        assertNull(outcome.error);
        Map<String, Object> merge = onlyMerge(doc);
        assertEquals("the top of a cut merge stays", Integer.valueOf(2), merge.get("fromRow")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("and the lost rows come off the bottom", Integer.valueOf(3), //$NON-NLS-1$
            merge.get("toRow")); //$NON-NLS-1$
        assertEquals(1, outcome.resizedMerges);
        assertEquals(0, outcome.removedMerges);
    }

    /** A drawing below an insertion moves; one inside a deletion goes and is named by id. */
    @Test
    public void drawingsMoveWithTheRows()
    {
        SpreadsheetDocument doc = emptyDocument();
        int below = BmTemplateHelper.addDrawing(doc, "Line", 5, 1, 6, 2, 0, 0, 0, 0, -1, null, //$NON-NLS-1$
            null, null);

        BmTemplateHelper.RowOutcome afterInsert = BmTemplateHelper.insertRows(doc, 2, 1, "none"); //$NON-NLS-1$

        assertNull(afterInsert.error);
        assertEquals(Integer.valueOf(6),
            Integer.valueOf(doc.getDrawings().get(0).getPosition().getBegin().getCell().getY()
                + 1));

        int inside = BmTemplateHelper.addDrawing(doc, "Line", 7, 1, 8, 2, 0, 0, 0, 0, -1, null, //$NON-NLS-1$
            null, null);
        BmTemplateHelper.RowOutcome afterDelete = BmTemplateHelper.deleteRows(doc, 7, 2);

        assertNull(afterDelete.error);
        assertEquals("the drawing inside the deleted range is named by id", //$NON-NLS-1$
            Integer.valueOf(inside), afterDelete.removedDrawings.get(0));
        assertEquals("the drawing below the range stays", 1, doc.getDrawings().size()); //$NON-NLS-1$
        assertEquals(Integer.valueOf(below), Integer.valueOf(doc.getDrawings().get(0)
            .getDrawingId()));
    }

    /** Row groups and whole-row merges travel with the rows like any other holder. */
    @Test
    public void rowGroupsAndWholeRowMergesTravelWithTheRows()
    {
        SpreadsheetDocument doc = emptyDocument();
        RowGroup group = MoxelFactory.eINSTANCE.createRowGroup();
        group.setBegin(1);
        group.setEnd(3);
        doc.getRowGroups().add(group);
        RowMerge wholeRows = MoxelFactory.eINSTANCE.createRowMerge();
        wholeRows.setBegin(5);
        wholeRows.setEnd(6);
        doc.getRowMerges().add(wholeRows);

        BmTemplateHelper.RowOutcome grown = BmTemplateHelper.insertRows(doc, 3, 1, "none"); //$NON-NLS-1$

        assertNull(grown.error);
        assertEquals("the group the point falls inside grows", 4, doc.getRowGroups().get(0) //$NON-NLS-1$
            .getEnd());
        assertEquals("the whole-row merge below moves", 6, doc.getRowMerges().get(0).getBegin()); //$NON-NLS-1$

        BmTemplateHelper.RowOutcome removed = BmTemplateHelper.deleteRows(doc, 7, 2);

        assertNull(removed.error);
        assertTrue("the whole-row merge inside the range is gone and counted", //$NON-NLS-1$
            doc.getRowMerges().isEmpty());
        assertEquals(1, removed.removedMerges);
    }

    /** The declared height and the saved view rows move with the operation. */
    @Test
    public void theHeightAndTheSavedViewRowsMove()
    {
        SpreadsheetDocument doc = withTexts("A", "B", "C", "D", "E"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        doc.setHeight(5);
        ViewSettings view = MoxelFactory.eINSTANCE.createViewSettings();
        view.setCurrentRow(4);
        doc.setViewSettings(view);

        BmTemplateHelper.insertRows(doc, 2, 2, "none"); //$NON-NLS-1$
        assertEquals("the declared height grew by the count", 7, doc.getHeight()); //$NON-NLS-1$
        assertEquals("the row the editor was on moved with it", 6, doc.getViewSettings() //$NON-NLS-1$
            .getCurrentRow());

        BmTemplateHelper.deleteRows(doc, 2, 2);
        assertEquals(5, doc.getHeight());
        assertEquals(4, doc.getViewSettings().getCurrentRow());
    }

    /** A sheet with nothing frozen gains nothing frozen from an insertion anywhere. */
    @Test
    public void anUnfrozenSheetStaysUnfrozen()
    {
        SpreadsheetDocument doc = withTexts("A", "B", "C"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        ViewSettings view = MoxelFactory.eINSTANCE.createViewSettings();
        doc.setViewSettings(view);

        assertNull(BmTemplateHelper.insertRows(doc, 1, 2, "none").error); //$NON-NLS-1$
        assertEquals("no frozen rows appear because rows were inserted", 0, doc.getViewSettings() //$NON-NLS-1$
            .getFixedRow());

        assertNull(BmTemplateHelper.insertColumns(doc, 1, 2, "none").error); //$NON-NLS-1$
        assertEquals("and no frozen columns either", 0, doc.getViewSettings().getFixedColumn()); //$NON-NLS-1$
    }

    /** The frozen count grows only from a change the frozen prefix itself reaches. */
    @Test
    public void theFrozenPrefixGrowsOnlyFromInside()
    {
        SpreadsheetDocument doc = withTexts("A", "B", "C", "D", "E"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        ViewSettings view = MoxelFactory.eINSTANCE.createViewSettings();
        view.setFixedRow(2);
        doc.setViewSettings(view);

        assertNull(BmTemplateHelper.insertRows(doc, 3, 1, "none").error); //$NON-NLS-1$
        assertEquals("an insertion right below the prefix leaves the count", 2, doc //$NON-NLS-1$
            .getViewSettings().getFixedRow());

        assertNull(BmTemplateHelper.insertRows(doc, 2, 2, "none").error); //$NON-NLS-1$
        assertEquals("one inside the prefix grows it by the count", 4, doc.getViewSettings() //$NON-NLS-1$
            .getFixedRow());

        assertNull(BmTemplateHelper.deleteRows(doc, 2, 1).error);
        assertEquals("and a deletion inside it takes one off", 3, doc.getViewSettings() //$NON-NLS-1$
            .getFixedRow());
    }

    /** A note belongs to its cell and follows the row the cell lands on. */
    @Test
    public void aCellNoteFollowsItsRow()
    {
        SpreadsheetDocument doc = withTexts("A", "B"); //$NON-NLS-1$ //$NON-NLS-2$
        Cell noted = rowAt(doc, 2).getCells().get(Integer.valueOf(0));
        noted.setNoteDrawing(MoxelFactory.eINSTANCE.createCommentDrawing());
        noted.getNoteDrawing().setCellRowIndex(1);

        BmTemplateHelper.RowOutcome outcome = BmTemplateHelper.insertRows(doc, 1, 1, "none"); //$NON-NLS-1$

        assertNull(outcome.error);
        assertEquals("the note points at the row its cell moved to", 2, //$NON-NLS-1$
            noted.getNoteDrawing().getCellRowIndex());
    }

    /** The note's own anchors ride with the row, not just the row index they name. */
    @Test
    public void aNotesAnchorsMoveWithItsRow()
    {
        SpreadsheetDocument doc = withTexts("A", "B", "C"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Cell noted = rowAt(doc, 2).getCells().get(Integer.valueOf(0));
        noted.setNoteDrawing(MoxelFactory.eINSTANCE.createCommentDrawing());
        noted.getNoteDrawing().setCellRowIndex(1);
        noted.getNoteDrawing().setPosition(anchorAt(1, 0));

        BmTemplateHelper.RowOutcome outcome = BmTemplateHelper.insertRows(doc, 1, 1, "none"); //$NON-NLS-1$

        assertNull(outcome.error);
        assertEquals(2, noted.getNoteDrawing().getCellRowIndex());
        assertEquals("the begin anchor moved down with the row", 2, noted.getNoteDrawing() //$NON-NLS-1$
            .getPosition().getBegin().getCell().getY());
        assertEquals("and so did the end anchor", 2, noted.getNoteDrawing().getPosition() //$NON-NLS-1$
            .getEnd().getCell().getY());

        BmTemplateHelper.RowOutcome deleted = BmTemplateHelper.deleteRows(doc, 1, 1);

        assertNull(deleted.error);
        assertEquals(1, noted.getNoteDrawing().getCellRowIndex());
        assertEquals("the anchors move back up on a deletion", 1, noted.getNoteDrawing() //$NON-NLS-1$
            .getPosition().getBegin().getCell().getY());
    }

    /** A drawing reads its data from an area, and that area rides with the rows. */
    @Test
    public void aDrawingDataSourceRidesWithTheRows()
    {
        SpreadsheetDocument doc = withTexts("A", "B", "C", "D"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Rect position = MoxelFactory.eINSTANCE.createRect();
        position.setX(0);
        position.setY(1);
        position.setWidth(1);
        position.setHeight(1);
        RectArea area = MoxelFactory.eINSTANCE.createRectArea();
        area.setPosition(position);
        DrawingsDataSource source = MoxelFactory.eINSTANCE.createDrawingsDataSource();
        source.setDrawingId(1);
        source.setArea(area);
        doc.getDrawingDataSources().add(source);

        BmTemplateHelper.RowOutcome outcome = BmTemplateHelper.insertRows(doc, 1, 1, "none"); //$NON-NLS-1$

        assertNull(outcome.error);
        assertEquals("the area the drawing reads moved down with the rows", 2, position.getY()); //$NON-NLS-1$
        assertEquals(0, outcome.removedDataSources);

        BmTemplateHelper.RowOutcome removed = BmTemplateHelper.deleteRows(doc, 3, 2);

        assertNull(removed.error);
        assertTrue("a source whose area the deletion took is gone", //$NON-NLS-1$
            doc.getDrawingDataSources().isEmpty());
        assertEquals("and is counted", 1, removed.removedDataSources); //$NON-NLS-1$
    }

    /** A data area past the last cell holds the end of the document the area defines. */
    @Test
    public void aDataAreaPastTheCellsHoldsTheEndOfTheDocument()
    {
        SpreadsheetDocument doc = withTexts("A", "B"); //$NON-NLS-1$ //$NON-NLS-2$
        Rect position = MoxelFactory.eINSTANCE.createRect();
        position.setX(0);
        position.setY(2);
        position.setWidth(0);
        position.setHeight(2);
        RectArea area = MoxelFactory.eINSTANCE.createRectArea();
        area.setPosition(position);
        DrawingsDataSource source = MoxelFactory.eINSTANCE.createDrawingsDataSource();
        source.setDrawingId(1);
        source.setArea(area);
        doc.getDrawingDataSources().add(source);

        BmTemplateHelper.RowOutcome inserted = BmTemplateHelper.insertRows(doc, 4, 1, "none"); //$NON-NLS-1$

        assertNull("a row inside the data area is inside the document", inserted.error); //$NON-NLS-1$
        assertEquals("the document runs to the row the area now does", 6, inserted.lastRow); //$NON-NLS-1$
        assertEquals("the area grew over the inserted row without moving", 3, position.getHeight()); //$NON-NLS-1$

        BmTemplateHelper.RowOutcome deleted = BmTemplateHelper.deleteRows(doc, 4, 1);

        assertNull(deleted.error);
        assertEquals("and back to the row it ends at after the deletion", 5, deleted.lastRow); //$NON-NLS-1$
        assertEquals(2, position.getHeight());
    }

    /** A merge and an area wholly below an insertion move; one the point lands inside resizes. */
    @Test
    public void aMergeAndAnAreaWhollyBelowThePointMoveUnresized()
    {
        SpreadsheetDocument doc = withTexts("A", "B"); //$NON-NLS-1$ //$NON-NLS-2$
        BmTemplateHelper.mergeCells(doc, 4, 1, 5, 1);
        RowMerge wholeRows = MoxelFactory.eINSTANCE.createRowMerge();
        wholeRows.setBegin(6);
        wholeRows.setEnd(7);
        doc.getRowMerges().add(wholeRows);
        BmTemplateHelper.addNamedArea(doc, "Низ", "rows", 8, 0, 9, 0); //$NON-NLS-1$ //$NON-NLS-2$

        BmTemplateHelper.RowOutcome moved = BmTemplateHelper.insertRows(doc, 2, 1, "none"); //$NON-NLS-1$

        assertNull(moved.error);
        assertEquals("a merge wholly below the point only moved", 0, moved.resizedMerges); //$NON-NLS-1$
        assertFalse("and so did the area, without being named", //$NON-NLS-1$
            moved.resizedNamedAreas.contains("Низ")); //$NON-NLS-1$
        assertEquals("the merge sits where the shift put it", Integer.valueOf(5), //$NON-NLS-1$
            onlyMerge(doc).get("fromRow")); //$NON-NLS-1$
        assertEquals("the whole-row merge moved uncounted", 7, doc.getRowMerges().get(0) //$NON-NLS-1$
            .getBegin());
        assertEquals("the area moved to where its rows went", Integer.valueOf(9), //$NON-NLS-1$
            areaNamed(doc, "Низ").get("fromRow")); //$NON-NLS-1$ //$NON-NLS-2$

        BmTemplateHelper.RowOutcome grown = BmTemplateHelper.insertRows(doc, 6, 1, "none"); //$NON-NLS-1$

        assertNull(grown.error);
        assertEquals("the point now lands inside the merge, which grows", 1, grown.resizedMerges); //$NON-NLS-1$

        BmTemplateHelper.RowOutcome areaGrown = BmTemplateHelper.insertRows(doc, 11, 1, "none"); //$NON-NLS-1$

        assertNull(areaGrown.error);
        assertEquals(0, areaGrown.resizedMerges);
        assertTrue("the point now lands inside the area, which is named", //$NON-NLS-1$
            areaGrown.resizedNamedAreas.contains("Низ")); //$NON-NLS-1$
    }

    /** An unmerge exception repeats over the target rows the way its merge does, uncounted. */
    @Test
    public void unmergeExceptionsRepeatOverTheTargetRows()
    {
        SpreadsheetDocument doc = withTexts("A", "B", "C", "D", "E", "F"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        doc.getUnmerges().add(unmergeOver(2, 1, 3, 1));
        doc.getUnmerges().add(unmergeOver(5, 1, 5, 1));

        BmTemplateHelper.RowOutcome outcome = BmTemplateHelper.copyRows(doc, 2, 5, 2);

        assertNull(outcome.error);
        assertEquals("the counters stay about merges", 0, outcome.removedMerges); //$NON-NLS-1$
        assertEquals("the target exception came off and the source one repeated over it", 2, //$NON-NLS-1$
            doc.getUnmerges().size());
        boolean kept = false;
        boolean repeated = false;
        for (Merge unmerge : doc.getUnmerges())
        {
            kept |= unmerge.getPosition().getY() == 1
                && unmerge.getPosition().getHeight() == 1;
            repeated |= unmerge.getPosition().getY() == 4
                && unmerge.getPosition().getHeight() == 1;
        }
        assertTrue("the source exception stays where it was", kept); //$NON-NLS-1$
        assertTrue("and a copy of it, not the target's own exception, sits on the target rows", //$NON-NLS-1$
            repeated);
    }

    /** Copying replaces the target rows with what the source rows are, whole. */
    @Test
    public void copyingReplacesTheTargetWithTheSource()
    {
        SpreadsheetDocument doc = withTexts("A", "B", "C"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull(BmTemplateHelper.setCellContent(doc, 1, 2, null, false, "ru", "parameter", //$NON-NLS-1$ //$NON-NLS-2$
            "Товар")); //$NON-NLS-1$
        BmTemplateHelper.applyCellFormat(doc, 1, 1, 1, 1, null, null, Integer.valueOf(30), null,
            null, null);
        int sourceFormat = rowAt(doc, 1).getFormatIndex();
        BmTemplateHelper.setCellText(doc, 5, 1, "Прежняя строка 5", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        BmTemplateHelper.setCellText(doc, 6, 1, "Прежняя строка 6", "ru"); //$NON-NLS-1$ //$NON-NLS-2$

        BmTemplateHelper.RowOutcome outcome = BmTemplateHelper.copyRows(doc, 1, 5, 2);

        assertNull(outcome.error);
        assertEquals("the target row is what the source row is", "A", textAt(doc, 5)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("B", textAt(doc, 6)); //$NON-NLS-1$
        Row copied = rowAt(doc, 5);
        assertEquals("the row format came with the copy", sourceFormat, copied.getFormatIndex()); //$NON-NLS-1$
        assertEquals("the parameter came with the copy", "Товар", //$NON-NLS-1$
            copied.getCells().get(Integer.valueOf(1)).getParameter());
        assertEquals("the source stays where it was", "A", textAt(doc, 1)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("nothing shifted", 0, outcome.shiftedRows); //$NON-NLS-1$
        assertEquals(6, outcome.lastRow);
    }

    /** A merge fully inside the source rows repeats over the target. */
    @Test
    public void aMergeInsideTheSourceRepeatsOverTheTarget()
    {
        SpreadsheetDocument doc = emptyDocument();
        BmTemplateHelper.mergeCells(doc, 1, 1, 2, 2);
        BmTemplateHelper.mergeCells(doc, 4, 1, 5, 2);

        BmTemplateHelper.RowOutcome outcome = BmTemplateHelper.copyRows(doc, 1, 4, 2);

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
            if (Integer.valueOf(4).equals(merge.get("fromRow")) //$NON-NLS-1$
                && Integer.valueOf(5).equals(merge.get("toRow"))) //$NON-NLS-1$
            {
                repeated = true;
            }
        }
        assertTrue("the copy of the source merge sits on the target rows", repeated); //$NON-NLS-1$
    }

    /** A whole-row merge repeats over the target like any other merge a copy replaces. */
    @Test
    public void wholeRowMergesRepeatOverTheTarget()
    {
        SpreadsheetDocument doc = withTexts("A", "B"); //$NON-NLS-1$ //$NON-NLS-2$
        RowMerge source = MoxelFactory.eINSTANCE.createRowMerge();
        source.setBegin(0);
        source.setEnd(1);
        doc.getRowMerges().add(source);
        RowMerge replaced = MoxelFactory.eINSTANCE.createRowMerge();
        replaced.setBegin(3);
        replaced.setEnd(4);
        doc.getRowMerges().add(replaced);

        BmTemplateHelper.RowOutcome outcome = BmTemplateHelper.copyRows(doc, 1, 4, 2);

        assertNull(outcome.error);
        assertEquals("the whole-row merge inside the replaced target came off", 1, //$NON-NLS-1$
            outcome.removedMerges);
        assertEquals("and the source one repeated over it", 2, doc.getRowMerges().size()); //$NON-NLS-1$
        boolean sourceKept = false;
        boolean repeated = false;
        for (RowMerge merge : doc.getRowMerges())
        {
            sourceKept |= merge.getBegin() == 0 && merge.getEnd() == 1;
            repeated |= merge.getBegin() == 3 && merge.getEnd() == 4;
        }
        assertTrue("the source merge stays where it was", sourceKept); //$NON-NLS-1$
        assertTrue("and its copy sits on the target rows", repeated); //$NON-NLS-1$
    }

    /** The target may run past the current end; the document grows to hold it. */
    @Test
    public void theTargetMayRunPastTheEnd()
    {
        SpreadsheetDocument doc = withTexts("A", "B"); //$NON-NLS-1$ //$NON-NLS-2$
        doc.setHeight(2);

        BmTemplateHelper.RowOutcome outcome = BmTemplateHelper.copyRows(doc, 1, 4, 2);

        assertNull(outcome.error);
        assertEquals("A", textAt(doc, 4)); //$NON-NLS-1$
        assertEquals("B", textAt(doc, 5)); //$NON-NLS-1$
        assertEquals("the declared height grew to hold the copy", 5, doc.getHeight()); //$NON-NLS-1$
        assertEquals(5, outcome.lastRow);
    }

    /** Every refusal leaves the document exactly as it was. */
    @Test
    public void refusalsLeaveTheDocumentAsItWas()
    {
        SpreadsheetDocument doc = withTexts("A", "B", "C"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        BmTemplateHelper.mergeCells(doc, 1, 1, 2, 2);
        BmTemplateHelper.addNamedArea(doc, "Шапка", "rows", 1, 0, 2, 0); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> before = readOf(doc);

        refusedLeavingTheDocumentAsItWas(doc,
            BmTemplateHelper.insertRows(doc, 0, 1, "none"), before); //$NON-NLS-1$
        refusedLeavingTheDocumentAsItWas(doc,
            BmTemplateHelper.insertRows(doc, 1, 0, "none"), before); //$NON-NLS-1$
        refusedLeavingTheDocumentAsItWas(doc,
            BmTemplateHelper.insertRows(doc, 5, 1, "none"), before); //$NON-NLS-1$
        refusedLeavingTheDocumentAsItWas(doc,
            BmTemplateHelper.insertRows(doc, 1, 1, "sideways"), before); //$NON-NLS-1$
        refusedLeavingTheDocumentAsItWas(doc,
            BmTemplateHelper.deleteRows(doc, 3, 2), before);
        refusedLeavingTheDocumentAsItWas(doc,
            BmTemplateHelper.deleteRows(doc, 0, 1), before);
        refusedLeavingTheDocumentAsItWas(doc,
            BmTemplateHelper.copyRows(doc, 3, 1, 2), before);
        refusedLeavingTheDocumentAsItWas(doc,
            BmTemplateHelper.copyRows(doc, 1, 2, 2), before);
        refusedLeavingTheDocumentAsItWas(doc,
            BmTemplateHelper.copyRows(doc, 0, 1, 1), before);
    }

    /** The refusal names the argument that was wrong. */
    @Test
    public void theRefusalsNameWhatWasWrong()
    {
        assertTrue(BmTemplateHelper.insertRows(emptyDocument(), 1, 1, "sideways").error //$NON-NLS-1$
            .contains("formatFrom")); //$NON-NLS-1$
        assertTrue(BmTemplateHelper.deleteRows(emptyDocument(), 9, 2).error.contains("past")); //$NON-NLS-1$
        assertTrue(BmTemplateHelper.copyRows(withTexts("A"), 1, 1, 1).error.contains("overlap")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Inserting rows and deleting the same rows returns the document it started from. */
    @Test
    public void insertingThenDeletingTheSameRowsReturnsTheStart()
    {
        SpreadsheetDocument doc = withTexts("A", "B", "C", "D"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        BmTemplateHelper.mergeCells(doc, 2, 1, 3, 2);
        BmTemplateHelper.addNamedArea(doc, "Блок", "rows", 2, 0, 3, 0); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> before = readOf(doc);

        assertNull(BmTemplateHelper.insertRows(doc, 2, 2, "none").error); //$NON-NLS-1$
        assertNull(BmTemplateHelper.deleteRows(doc, 2, 2).error);

        assertEquals("insert and delete of the same rows is the identity", before, readOf(doc)); //$NON-NLS-1$
    }

    /** The same round trip with formats carried by the new rows. */
    @Test
    public void formattedInsertsDeleteCleanlyToo()
    {
        SpreadsheetDocument doc = withTexts("A", "B", "C"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        BmTemplateHelper.applyCellFormat(doc, 1, 1, 1, 1, null, null, Integer.valueOf(30), null,
            null, null);
        Map<String, Object> before = readOf(doc);

        assertNull(BmTemplateHelper.insertRows(doc, 2, 2, "above").error); //$NON-NLS-1$
        assertNotNull(rowAt(doc, 2));
        assertNotNull(rowAt(doc, 3));

        assertNull(BmTemplateHelper.deleteRows(doc, 2, 2).error);
        assertEquals(before, readOf(doc));
    }

    /**
     * A rectangle of one row carries 0 in its height, the way the template file is read:
     * beginRow and endRow of the same value. It rides with the rows like any other area.
     */
    @Test
    public void aRectangleOfOneRowRidesWithTheRows()
    {
        SpreadsheetDocument doc = withTexts("A", "B", "C"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Rect position = MoxelFactory.eINSTANCE.createRect();
        position.setX(1);
        position.setY(1);
        position.setWidth(11);
        position.setHeight(0);
        RectArea area = MoxelFactory.eINSTANCE.createRectArea();
        area.setPosition(position);
        com._1c.g5.v8.dt.moxel.NamedItemCells item = MoxelFactory.eINSTANCE.createNamedItemCells();
        item.setArea(area);
        doc.getNamedItems().put("Объект", item); //$NON-NLS-1$

        BmTemplateHelper.RowOutcome inserted = BmTemplateHelper.insertRows(doc, 2, 2, "none"); //$NON-NLS-1$

        assertNull(inserted.error);
        assertEquals("the area stood on row 2 and the rows went in before it", 3, position.getY()); //$NON-NLS-1$
        assertEquals("one row still", 0, position.getHeight()); //$NON-NLS-1$
        Map<String, Object> read = areaNamed(doc, "Объект"); //$NON-NLS-1$
        assertEquals(Integer.valueOf(4), read.get("fromRow")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(4), read.get("toRow")); //$NON-NLS-1$

        BmTemplateHelper.RowOutcome deleted = BmTemplateHelper.deleteRows(doc, 4, 1);

        assertNull(deleted.error);
        assertTrue("the one row the area held is gone, and the area with it", //$NON-NLS-1$
            deleted.removedNamedAreas.contains("Объект")); //$NON-NLS-1$
    }

    /** The bare row entries an empty template carries are not content: row 1 is open, row 2 is not. */
    @Test
    public void anEmptyTemplatesBareRowsDoNotHoldTheEnd()
    {
        SpreadsheetDocument doc = emptyDocument();
        doc.getRows().put(Integer.valueOf(0), MoxelFactory.eINSTANCE.createRow());
        doc.getRows().put(Integer.valueOf(1), MoxelFactory.eINSTANCE.createRow());

        BmTemplateHelper.RowOutcome refused = BmTemplateHelper.insertRows(doc, 2, 1, "none"); //$NON-NLS-1$
        assertNotNull("a bare row entry does not make row 2 a row of the document", //$NON-NLS-1$
            refused.error);
        assertTrue(refused.error.contains("past the end")); //$NON-NLS-1$

        assertNull("row 1 is the first row an empty document takes", //$NON-NLS-1$
            BmTemplateHelper.insertRows(doc, 1, 1, "none").error); //$NON-NLS-1$
        assertNotNull("and an insertion that materialized nothing opened nothing", //$NON-NLS-1$
            BmTemplateHelper.insertRows(doc, 2, 1, "none").error); //$NON-NLS-1$
    }

    /** A row format and a cell are content; a bare row entry is what does not count. */
    @Test
    public void aRowWithAFormatOrACellHoldsTheEnd()
    {
        SpreadsheetDocument byFormat = emptyDocument();
        Row formatted = MoxelFactory.eINSTANCE.createRow();
        formatted.setFormatIndex(3);
        byFormat.getRows().put(Integer.valueOf(4), formatted);
        assertNull("a row with a row format of its own runs the document to it", //$NON-NLS-1$
            BmTemplateHelper.insertRows(byFormat, 5, 1, "none").error); //$NON-NLS-1$

        SpreadsheetDocument byCell = emptyDocument();
        Row withCell = MoxelFactory.eINSTANCE.createRow();
        withCell.getCells().put(Integer.valueOf(0), MoxelFactory.eINSTANCE.createCell());
        byCell.getRows().put(Integer.valueOf(4), withCell);
        assertNull("a row with a cell runs the document to it", //$NON-NLS-1$
            BmTemplateHelper.insertRows(byCell, 5, 1, "none").error); //$NON-NLS-1$
    }

    /** The declared height, the print area and the drawings hold the end over bare rows, as over content. */
    @Test
    public void theHeightThePrintAreaAndTheDrawingsHoldTheEndOverBareRows()
    {
        SpreadsheetDocument byHeight = emptyDocument();
        byHeight.getRows().put(Integer.valueOf(0), MoxelFactory.eINSTANCE.createRow());
        byHeight.setHeight(5);
        assertNotNull("row 7 is past the declared height of 5", //$NON-NLS-1$
            BmTemplateHelper.insertRows(byHeight, 7, 1, "none").error); //$NON-NLS-1$
        assertNull("and row 6 appends to it", //$NON-NLS-1$
            BmTemplateHelper.insertRows(byHeight, 6, 1, "none").error); //$NON-NLS-1$

        SpreadsheetDocument byPrintArea = emptyDocument();
        byPrintArea.getRows().put(Integer.valueOf(0), MoxelFactory.eINSTANCE.createRow());
        RowsArea print = MoxelFactory.eINSTANCE.createRowsArea();
        print.setBegin(2);
        print.setEnd(3);
        byPrintArea.setPrintArea(print);
        assertNotNull("row 6 is past the print area over rows 3..4", //$NON-NLS-1$
            BmTemplateHelper.insertRows(byPrintArea, 6, 1, "none").error); //$NON-NLS-1$
        assertNull("and row 4 is inside it", //$NON-NLS-1$
            BmTemplateHelper.insertRows(byPrintArea, 4, 1, "none").error); //$NON-NLS-1$

        SpreadsheetDocument byDrawing = emptyDocument();
        byDrawing.getRows().put(Integer.valueOf(0), MoxelFactory.eINSTANCE.createRow());
        BmTemplateHelper.addDrawing(byDrawing, "Line", 4, 1, 5, 2, 0, 0, 0, 0, -1, null, //$NON-NLS-1$
            null, null);
        assertNotNull("row 7 is past the drawing over rows 4..5", //$NON-NLS-1$
            BmTemplateHelper.insertRows(byDrawing, 7, 1, "none").error); //$NON-NLS-1$
        assertNull("and row 5 is inside it", //$NON-NLS-1$
            BmTemplateHelper.insertRows(byDrawing, 5, 1, "none").error); //$NON-NLS-1$
    }
}
