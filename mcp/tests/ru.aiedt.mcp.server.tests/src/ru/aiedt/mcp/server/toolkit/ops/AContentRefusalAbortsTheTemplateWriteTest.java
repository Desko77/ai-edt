/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.CommonTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;

import ru.aiedt.mcp.server.support.BmObjectHelper;
import ru.aiedt.mcp.server.support.BmTemplateHelper;

/**
 * A content refusal inside the write callback leaves the write by exception, and the refusal is
 * decided before the change it refused.
 * <p>
 * {@code set_cell} and {@code format_cells} reach their document through
 * {@link MxlWorkshopTool#writableDocument}, which attaches a spreadsheet to a template that has
 * none. A callback that returned the refusal text would leave that attachment to commit: the caller
 * reads a refused call and the owner is exported from a model the call changed. What a workspace run
 * keeps, and the file beside it, is read off that abort; the part a plain test reaches is the
 * refusal itself, what it had already changed when it was decided, and the abort that carries it out
 * of the callback.
 * </p>
 */
public class AContentRefusalAbortsTheTemplateWriteTest
{
    private static final String NAME = "ПечатнаяФорма"; //$NON-NLS-1$

    /**
     * A common template of the spreadsheet type that holds no document yet - the state the write
     * callback opens.
     *
     * @return the template
     */
    private static CommonTemplate templateWithoutItsDocument()
    {
        CommonTemplate template = MdClassFactory.eINSTANCE.createCommonTemplate();
        template.setName(NAME);
        BmObjectHelper.setProperty(template, "templateType", "SpreadsheetDocument"); //$NON-NLS-1$ //$NON-NLS-2$
        return template;
    }

    /**
     * The fill refusal of {@code set_cell} arrives before the cell is created, and the abort is what
     * keeps the attached document out of the commit.
     */
    @Test
    public void theSetCellRefusalLeavesTheCellUnwritten()
    {
        CommonTemplate template = templateWithoutItsDocument();
        SpreadsheetDocument doc = MxlWorkshopTool.writableDocument(template, NAME);
        assertNotNull(doc);
        assertSame("opening the template attached a document the refused call would commit", //$NON-NLS-1$
            doc, BmTemplateHelper.existingSpreadsheetOf(template));

        String refusal = BmTemplateHelper.setCellContent(doc, 1, 1, "Текст", true, null, //$NON-NLS-1$
            "parameter", "ИмяПараметра"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull("a parameter fill stores no text", refusal); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("does not store text")); //$NON-NLS-1$
        assertTrue("the refusal was decided before any cell was created", doc.getRows().isEmpty()); //$NON-NLS-1$
        try
        {
            MxlWorkshopTool.abortOnRefusal(refusal);
            fail("returning the refusal would commit the document the call attached"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException aborted)
        {
            assertEquals("the refusal text travels out of the callback", refusal, //$NON-NLS-1$
                aborted.getMessage());
        }
    }

    /**
     * The placement refusal of {@code format_cells} arrives before the row and format tables grow,
     * and the abort is what keeps the attached document out of the commit.
     */
    @Test
    public void theFormatCellsRefusalLeavesTheTablesAlone()
    {
        CommonTemplate template = templateWithoutItsDocument();
        SpreadsheetDocument doc = MxlWorkshopTool.writableDocument(template, NAME);
        assertSame("opening the template attached a document the refused call would commit", //$NON-NLS-1$
            doc, BmTemplateHelper.existingSpreadsheetOf(template));
        int formatsBefore = doc.getFormats().size();
        BmTemplateHelper.CellLook look = new BmTemplateHelper.CellLook();
        look.fontBold = Boolean.TRUE;

        BmTemplateHelper.FormatOutcome outcome = BmTemplateHelper.applyCellFormat(doc, 1, 1, 1, 1,
            "sideways", null, null, null, null, null, look); //$NON-NLS-1$

        assertNotNull("a placement nobody knows is refused", outcome.error); //$NON-NLS-1$
        assertTrue(outcome.error, outcome.error.contains("textPlacement must be one of")); //$NON-NLS-1$
        assertEquals("no cell was formatted", 0, outcome.cellsChanged); //$NON-NLS-1$
        assertTrue("the refusal was decided before the row table grew", doc.getRows().isEmpty()); //$NON-NLS-1$
        assertEquals("the refusal was decided before the format table grew", //$NON-NLS-1$
            formatsBefore, doc.getFormats().size());
        try
        {
            MxlWorkshopTool.abortOnRefusal(outcome.error);
            fail("returning the refusal would commit the document the call attached"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException aborted)
        {
            assertEquals("the refusal text travels out of the callback", outcome.error, //$NON-NLS-1$
                aborted.getMessage());
        }
    }
}
