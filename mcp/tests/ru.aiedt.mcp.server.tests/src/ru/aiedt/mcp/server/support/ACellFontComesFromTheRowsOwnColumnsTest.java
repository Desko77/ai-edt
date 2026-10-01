/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.FontDef;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.moxel.Cell;
import com._1c.g5.v8.dt.moxel.Column;
import com._1c.g5.v8.dt.moxel.Columns;
import com._1c.g5.v8.dt.moxel.Format;
import com._1c.g5.v8.dt.moxel.MoxelFactory;
import com._1c.g5.v8.dt.moxel.Row;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;

/**
 * A cell with no font of its own renders with its column's font, and a row that carries its own
 * column set takes the column from that set rather than from the document's.
 */
public class ACellFontComesFromTheRowsOwnColumnsTest
{
    /**
     * @param face the face name
     * @return a font of that face
     */
    private static FontDef font(String face)
    {
        FontDef font = McoreFactory.eINSTANCE.createFontDef();
        font.setFaceName(face);
        font.setHeight(10f);
        font.setScale(100);
        return font;
    }

    /**
     * @param formatIndex the column's format
     * @return a set of one column carrying that format
     */
    private static Columns oneColumn(int formatIndex)
    {
        Columns set = MoxelFactory.eINSTANCE.createColumns();
        set.setSize(1);
        Column column = MoxelFactory.eINSTANCE.createColumn();
        column.setFormatIndex(formatIndex);
        set.getColumns().put(Integer.valueOf(0), column);
        return set;
    }

    /**
     * A document whose column set names Arial and which holds a second format naming Verdana.
     *
     * @return the document
     */
    private static SpreadsheetDocument document()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        doc.getFonts().add(font("Arial")); //$NON-NLS-1$
        doc.getFonts().add(font("Verdana")); //$NON-NLS-1$
        doc.getFormats().add(MoxelFactory.eINSTANCE.createFormat());
        Format arial = MoxelFactory.eINSTANCE.createFormat();
        arial.setFont(0);
        doc.getFormats().add(arial);
        Format verdana = MoxelFactory.eINSTANCE.createFormat();
        verdana.setFont(1);
        doc.getFormats().add(verdana);
        doc.setColumns(oneColumn(1));
        return doc;
    }

    /**
     * @param doc the document
     * @param own the row's own column set, or <code>null</code>
     * @return the font the row's only cell renders with
     */
    private static String faceOfTheCell(SpreadsheetDocument doc, Columns own)
    {
        Row row = MoxelFactory.eINSTANCE.createRow();
        if (own != null)
        {
            row.setColumns(own);
        }
        Cell cell = MoxelFactory.eINSTANCE.createCell();
        row.getCells().put(Integer.valueOf(0), cell);
        doc.getRows().put(Integer.valueOf(0), row);
        return ((FontDef)BmTemplateHelper.inheritedCellFont(doc, row, cell, 0)).getFaceName();
    }

    /** A row with its own column set takes the column's font from it. */
    @Test
    public void aRowsOwnColumnSetGivesTheFont()
    {
        SpreadsheetDocument doc = document();
        assertEquals("Verdana", faceOfTheCell(doc, oneColumn(2))); //$NON-NLS-1$
    }

    /** A row without one takes the document's column. */
    @Test
    public void aRowWithoutOneTakesTheDocumentsColumn()
    {
        SpreadsheetDocument doc = document();
        assertEquals("Arial", faceOfTheCell(doc, null)); //$NON-NLS-1$
    }
}
