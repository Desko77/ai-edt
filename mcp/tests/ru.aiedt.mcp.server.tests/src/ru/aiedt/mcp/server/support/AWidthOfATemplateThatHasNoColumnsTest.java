/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import com._1c.g5.v8.dt.moxel.Column;
import com._1c.g5.v8.dt.moxel.Columns;
import com._1c.g5.v8.dt.moxel.MoxelFactory;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;

/**
 * Where a width goes when the template has no column set.
 * <p>
 * A width belongs to a column, and columns live in the document's column set. A template authored
 * with cells but never with a set has none, and the call used to return there: the width it was
 * asked for was dropped and the answer counted no columns, leaving the caller to read that as
 * "nothing to change". Measured on a real configuration: templates do occur in that shape, and a
 * set made here carries the width the caller named.
 * </p>
 */
public class AWidthOfATemplateThatHasNoColumnsTest
{
    @Test
    public void aWidthAskedOfADocumentWithNoColumnSetMakesOneThatCarries()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        assertNull("this test is about a document without a column set", doc.getColumns()); //$NON-NLS-1$

        BmTemplateHelper.FormatOutcome outcome = BmTemplateHelper.applyCellFormat(doc, 1, 1, 1, 1,
            null, null, null, null, Integer.valueOf(50), null);

        assertNull(outcome.error);
        assertEquals("the width was applied to the one column it names", 1, outcome.columnsChanged); //$NON-NLS-1$
        assertNotNull("a width needs a column set to live in", doc.getColumns()); //$NON-NLS-1$
        assertEquals(1, doc.getColumns().getSize());
        Column column = doc.getColumns().getColumns().get(Integer.valueOf(0));
        assertNotNull(column);
        assertEquals(50, doc.getFormats().get(column.getFormatIndex()).getWidth());
    }

    @Test
    public void aSetThatAlreadyCoversTheColumnsKeepsItsSize()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        Columns set = MoxelFactory.eINSTANCE.createColumns();
        set.setSize(7);
        doc.setColumns(set);

        BmTemplateHelper.FormatOutcome outcome = BmTemplateHelper.applyCellFormat(doc, 1, 1, 1, 3,
            null, null, null, null, Integer.valueOf(50), null);

        assertNull(outcome.error);
        assertEquals(3, outcome.columnsChanged);
        assertEquals("the declared size is not shrunk to the formatted columns", 7, //$NON-NLS-1$
            doc.getColumns().getSize());
        for (int index = 0; index < 3; index++)
        {
            Column column = set.getColumns().get(Integer.valueOf(index));
            assertNotNull("column " + index + " was formatted", column); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(50, doc.getFormats().get(column.getFormatIndex()).getWidth());
        }
    }
}
