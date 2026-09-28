/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.moxel.Cell;
import com._1c.g5.v8.dt.moxel.Format;
import com._1c.g5.v8.dt.moxel.MoxelFactory;
import com._1c.g5.v8.dt.moxel.Row;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;

import ru.aiedt.mcp.server.support.BmTemplateHelper;

/**
 * Covers what {@code format_cells} promises before it reaches a workspace.
 * <p>
 * The refusals are the part worth pinning. A formatter that quietly does nothing is the worst of the
 * three outcomes: it reports success, the template is unchanged, and whoever asked goes looking for
 * the reason somewhere else entirely.
 * </p>
 */
public class MxlFormatContractTest
{
    private final MxlWorkshopTool tool = new MxlWorkshopTool();

    /** Every formatting property is declared, or a client cannot pass it. */
    @Test
    public void everyFormattingPropertyIsDeclared()
    {
        String schema = tool.getInputSchema();

        for (String argument : new String[] {"textPlacement", "textOrientation", "rowHeight", //$NON-NLS-1$
            "autoColumnWidth", "columnWidth", "columnWidthWeight", "border", "fontName", //$NON-NLS-1$
            "textColor", "pageOrientation", "fillType", "parameter"}) //$NON-NLS-1$
        {
            assertTrue("undeclared argument " + argument, schema.contains('"' + argument + '"')); //$NON-NLS-1$
        }
        assertTrue("rotation must be named in degrees", schema.contains("degrees")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The operation is named in the description, or an agent will not find it. */
    @Test
    public void theOperationIsNamedWhereOperationsAreListed()
    {
        assertTrue(tool.getDescription().contains("format_cells")); //$NON-NLS-1$
    }

    /**
     * A call that would change nothing is refused rather than answered with success.
     * <p>
     * Passing a range and no properties is a request with no content. Reporting success for it
     * would be the "empty answer taken for a successful one" this project keeps paying for.
     * </p>
     */
    @Test
    public void aCallThatWouldChangeNothingIsRefused()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "format_cells"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", "Any"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("ownerFqn", "Catalog.Any"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("templateName", "Print"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("row", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("col", "1"); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = tool.execute(params);

        assertFalse("a request with no properties must not report success", //$NON-NLS-1$
            answer.contains("\"success\":true")); //$NON-NLS-1$
    }

    /** Without a range there is nothing to format, and it says which arguments are missing. */
    @Test
    public void aCallWithoutARangeIsRefused()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "format_cells"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", "Any"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("textPlacement", "wrap"); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = tool.execute(params);

        assertFalse(answer.contains("\"success\":true")); //$NON-NLS-1$
    }

    /** A rotation below zero is refused with the range, in degrees. */
    @Test
    public void orientationBelowZeroIsRefusedInDegrees()
    {
        Map<String, String> params = formatCall();
        params.put("textOrientation", "-1"); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = tool.execute(params);

        assertFalse(answer.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("degrees")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("360")); //$NON-NLS-1$
    }

    /** A rotation past a full circle is refused with the same range. */
    @Test
    public void orientationPastAFullCircleIsRefusedInDegrees()
    {
        Map<String, String> params = formatCall();
        params.put("textOrientation", "361"); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = tool.execute(params);

        assertFalse(answer.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("degrees")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("0")); //$NON-NLS-1$
    }

    /**
     * Ninety degrees is what the editor shows for a vertical caption, and the template stores it
     * as 900 tenths.
     */
    @Test
    public void ninetyDegreesIsStoredAsNineHundredTenths()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();

        BmTemplateHelper.FormatOutcome outcome = BmTemplateHelper.applyCellFormat(doc, 1, 1, 1, 1,
            null, Integer.valueOf(90), null, null, null, null);

        assertNull(outcome.error);
        assertEquals(900, formatOf(doc, 0, 0).getTextOrientation());
    }

    /** The ends of the range are legal and stored as tenths too. */
    @Test
    public void zeroAndFullCircleAreStoredAsTenths()
    {
        SpreadsheetDocument flat = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        SpreadsheetDocument turned = MoxelFactory.eINSTANCE.createSpreadsheetDocument();

        assertNull(BmTemplateHelper.applyCellFormat(flat, 1, 1, 1, 1, null, Integer.valueOf(0),
            null, null, null, null).error);
        assertNull(BmTemplateHelper.applyCellFormat(turned, 1, 1, 1, 1, null, Integer.valueOf(360),
            null, null, null, null).error);

        assertEquals(0, formatOf(flat, 0, 0).getTextOrientation());
        assertEquals(3600, formatOf(turned, 0, 0).getTextOrientation());
    }

    /** Reading the angle divides the stored tenths by ten, so 900 comes back as 90 degrees. */
    @Test
    public void readingTheAngleReturnsDegrees()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        BmTemplateHelper.applyCellFormat(doc, 1, 1, 1, 1, null, Integer.valueOf(90), null, null,
            null, null);

        Map<String, Object> read = BmTemplateHelper.readSpreadsheet(doc, "ru"); //$NON-NLS-1$
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cells = (List<Map<String, Object>>)read.get("cells"); //$NON-NLS-1$

        assertEquals(1, cells.size());
        assertEquals(Integer.valueOf(90), cells.get(0).get("textOrientation")); //$NON-NLS-1$
    }

    /** A value outside the range does not touch the document. */
    @Test
    public void orientationOutsideTheRangeWritesNothing()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();

        BmTemplateHelper.FormatOutcome outcome = BmTemplateHelper.applyCellFormat(doc, 1, 1, 1, 1,
            null, Integer.valueOf(-1), null, null, null, null);

        assertTrue(outcome.error, outcome.error != null && outcome.error.contains("degrees")); //$NON-NLS-1$
        assertTrue(doc.getRows().isEmpty());
    }

    private Map<String, String> formatCall()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "format_cells"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", "Any"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("ownerFqn", "Catalog.Any"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("templateName", "Print"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("row", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("col", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        return params;
    }

    private static Format formatOf(SpreadsheetDocument doc, int row, int col)
    {
        Row held = doc.getRows().get(Integer.valueOf(row));
        Cell cell = held.getCells().get(Integer.valueOf(col));
        return doc.getFormats().get(cell.getFormatIndex());
    }
}
