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

import com._1c.g5.v8.dt.mcore.ColorDef;
import com._1c.g5.v8.dt.mcore.FontDef;
import com._1c.g5.v8.dt.moxel.Cell;
import com._1c.g5.v8.dt.moxel.Format;
import com._1c.g5.v8.dt.moxel.MoxelFactory;
import com._1c.g5.v8.dt.moxel.PageOrientation;
import com._1c.g5.v8.dt.moxel.PrintSettings;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.moxel.content.CellLine;
import com._1c.g5.v8.dt.moxel.content.CellLineStyle;
import com._1c.g5.v8.dt.moxel.content.FillType;

import ru.aiedt.mcp.server.support.BmTemplateHelper;

/**
 * Cell content, appearance and print settings of a spreadsheet template.
 * <p>
 * The refusals are checked on the tool, before a project is opened. What the model stores is
 * checked on a document built in memory: a parameter BSL can fill, a border, a font, a colour and
 * the page.
 * </p>
 */
public class MxlPrintSettingsTest
{
    private final MxlWorkshopTool tool = new MxlWorkshopTool();

    /** A parameter with no name is refused, and the refusal names the missing name. */
    @Test
    public void aParameterWithoutANameIsRefused()
    {
        Map<String, String> params = cellCall();
        params.put("fillType", "parameter"); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = tool.execute(params);

        assertFalse(answer.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("parameter")); //$NON-NLS-1$
    }

    /** A border the model has no style for is refused with the styles it does have. */
    @Test
    public void anUnknownBorderIsRefused()
    {
        Map<String, String> params = formatCall();
        params.put("border", "zigzag"); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = tool.execute(params);

        assertFalse(answer.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("Solid")); //$NON-NLS-1$
    }

    /** Print settings belong to the document, so they do not need a cell to land on. */
    @Test
    public void printSettingsAloneDoNotRequireACell()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "format_cells"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", "Any"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("ownerFqn", "Catalog.Any"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("templateName", "Print"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("pageOrientation", "landscape"); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = tool.execute(params);

        assertFalse(answer.contains("\"success\":true")); //$NON-NLS-1$
        assertFalse(answer, answer.contains("cell range")); //$NON-NLS-1$
    }

    /** A parameter cell keeps the name and is filled as a parameter. */
    @Test
    public void aParameterCellKeepsTheNameAndTheFill()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();

        assertNull(BmTemplateHelper.setCellContent(doc, 1, 1, null, false, null, null, "Total")); //$NON-NLS-1$

        Cell cell = cellAt(doc);
        assertEquals("Total", cell.getParameter()); //$NON-NLS-1$
        assertEquals(FillType.PARAMETER, formatOf(doc, cell).getFillType());
        Map<String, Object> read = readCell(doc);
        assertEquals("Total", read.get("parameter")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Parameter", read.get("fillType")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A template cell keeps the text, placeholders included, and is filled as a template. */
    @Test
    public void aTemplateCellKeepsTheTemplateText()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();

        assertNull(BmTemplateHelper.setCellContent(doc, 1, 1, "Sum [Total]", true, "ru", //$NON-NLS-1$ //$NON-NLS-2$
            "template", null)); //$NON-NLS-1$

        Cell cell = cellAt(doc);
        assertEquals("Sum [Total]", cell.getText().getContent().get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(FillType.TEMPLATE, formatOf(doc, cell).getFillType());
    }

    /** Plain text is a text fill, and it does not keep a parameter name. */
    @Test
    public void aTextFillClearsTheParameter()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        BmTemplateHelper.setCellContent(doc, 1, 1, null, false, null, null, "Total"); //$NON-NLS-1$

        assertNull(BmTemplateHelper.setCellContent(doc, 1, 1, "Heading", true, "ru", "text", null)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Cell cell = cellAt(doc);
        assertNull(cell.getParameter());
        assertEquals(FillType.TEXT, formatOf(doc, cell).getFillType());
        assertEquals("Heading", cell.getText().getContent().get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A solid border is one shared cell line, on every side. */
    @Test
    public void aSolidBorderIsStoredAsACellLine()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        BmTemplateHelper.CellLook look = new BmTemplateHelper.CellLook();
        look.border = "Solid"; //$NON-NLS-1$

        BmTemplateHelper.FormatOutcome outcome = BmTemplateHelper.applyCellFormat(doc, 1, 1, 1, 2,
            null, null, null, null, null, null, look);

        assertNull(outcome.error);
        Format format = formatOf(doc, cellAt(doc));
        assertEquals(format.getLeftBorder(), format.getRightBorder());
        assertEquals(format.getLeftBorder(), format.getTopBorder());
        assertEquals(format.getLeftBorder(), format.getBottomBorder());
        CellLine line = (CellLine)doc.getLines().get(format.getLeftBorder());
        assertEquals(CellLineStyle.SOLID, line.getStyle());
        assertEquals(1, line.getWidth());
        assertEquals("the two cells share the line", 1, doc.getLines().size()); //$NON-NLS-1$
    }

    /** A font is stored by face, height and weight. */
    @Test
    public void aFontIsStoredByFaceAndHeight()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        BmTemplateHelper.CellLook look = new BmTemplateHelper.CellLook();
        look.fontName = "Arial"; //$NON-NLS-1$
        look.fontSize = Float.valueOf(10f);
        look.fontBold = Boolean.TRUE;

        assertNull(BmTemplateHelper.applyCellFormat(doc, 1, 1, 1, 1, null, null, null, null, null,
            null, look).error);

        FontDef font = (FontDef)doc.getFonts().get(formatOf(doc, cellAt(doc)).getFont());
        assertEquals("Arial", font.getFaceName()); //$NON-NLS-1$
        assertEquals(10.0, font.getHeight(), 0.01);
        assertTrue(font.isBold());
        assertEquals(100, font.getScale());
    }

    /** A colour is stored as red, green and blue. */
    @Test
    public void aColorIsStoredAsRgb()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        BmTemplateHelper.CellLook look = new BmTemplateHelper.CellLook();
        look.textColor = "#FF0000"; //$NON-NLS-1$
        look.backColor = "00FF00"; //$NON-NLS-1$

        assertNull(BmTemplateHelper.applyCellFormat(doc, 1, 1, 1, 1, null, null, null, null, null,
            null, look).error);

        Format format = formatOf(doc, cellAt(doc));
        ColorDef text = (ColorDef)doc.getColors().get(format.getTextColor());
        ColorDef back = (ColorDef)doc.getColors().get(format.getBackColor());
        assertEquals(255, text.getRed());
        assertEquals(0, text.getGreen());
        assertEquals(0, text.getBlue());
        assertEquals(0, back.getRed());
        assertEquals(255, back.getGreen());
    }

    /**
     * Print settings are the document's, and a margin in millimetres is stored as hundredths.
     */
    @Test
    public void printSettingsAreStoredInTheModelUnits()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        BmTemplateHelper.CellLook look = new BmTemplateHelper.CellLook();
        look.pageOrientation = "landscape"; //$NON-NLS-1$
        look.scale = Integer.valueOf(80);
        look.copies = Integer.valueOf(2);
        look.perPage = Integer.valueOf(1);
        look.fitToPage = Boolean.TRUE;
        look.topMargin = Integer.valueOf(10);
        look.leftMargin = Integer.valueOf(15);

        assertNull(BmTemplateHelper.applyPrintSettings(doc, look));

        PrintSettings settings = doc.getPrintSettings();
        assertEquals(PageOrientation.LANDSCAPE, settings.getPageOrientation());
        assertEquals(80, settings.getScale());
        assertEquals(2, settings.getCopies());
        assertEquals(1, settings.getPerPage());
        assertTrue(settings.isFitToPage());
        assertEquals(1000, settings.getTopMargin());
        assertEquals(1500, settings.getLeftMargin());
    }

    private Map<String, String> cellCall()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "set_cell"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", "Any"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("ownerFqn", "Catalog.Any"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("templateName", "Print"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("row", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("col", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        return params;
    }

    private Map<String, String> formatCall()
    {
        Map<String, String> params = cellCall();
        params.put("operation", "format_cells"); //$NON-NLS-1$ //$NON-NLS-2$
        return params;
    }

    private static Cell cellAt(SpreadsheetDocument doc)
    {
        return doc.getRows().get(Integer.valueOf(0)).getCells().get(Integer.valueOf(0));
    }

    private static Format formatOf(SpreadsheetDocument doc, Cell cell)
    {
        return doc.getFormats().get(cell.getFormatIndex());
    }

    private static Map<String, Object> readCell(SpreadsheetDocument doc)
    {
        Map<String, Object> read = BmTemplateHelper.readSpreadsheet(doc, "ru"); //$NON-NLS-1$
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cells = (List<Map<String, Object>>)read.get("cells"); //$NON-NLS-1$
        return cells.get(0);
    }
}
