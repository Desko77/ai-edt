/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.ColorDef;
import com._1c.g5.v8.dt.mcore.Font;
import com._1c.g5.v8.dt.mcore.FontDef;
import com._1c.g5.v8.dt.mcore.FontRef;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.moxel.Cell;
import com._1c.g5.v8.dt.moxel.Column;
import com._1c.g5.v8.dt.moxel.Columns;
import com._1c.g5.v8.dt.moxel.Format;
import com._1c.g5.v8.dt.moxel.MoxelFactory;
import com._1c.g5.v8.dt.moxel.PageOrientation;
import com._1c.g5.v8.dt.moxel.PrintSettings;
import com._1c.g5.v8.dt.moxel.Row;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.moxel.content.CellLine;
import com._1c.g5.v8.dt.moxel.content.CellLineStyle;
import com._1c.g5.v8.dt.moxel.content.ContentFactory;
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
        look.topMargin = Float.valueOf(10f);
        look.leftMargin = Float.valueOf(15f);

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

    /** A fraction of a millimetre is stored as the nearest whole hundredth. */
    @Test
    public void aFractionalMarginIsStoredAsHundredths()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        BmTemplateHelper.CellLook look = new BmTemplateHelper.CellLook();
        look.topMargin = Float.valueOf(7.5f);

        assertNull(BmTemplateHelper.applyPrintSettings(doc, look));

        assertEquals(750, doc.getPrintSettings().getTopMargin());
    }

    /**
     * The first format a fresh document gets does not land at index 0, which the writer and the
     * reader read as "no format".
     * <p>
     * Index 0 holds the empty placeholder a file-loaded document has, so the parameter format and
     * the bold format both sit past it, and a cell with no format of its own keeps index 0.
     * </p>
     */
    @Test
    public void theFirstFormatDoesNotLandInTheNoFormatSlot()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();

        assertNull(BmTemplateHelper.setCellContent(doc, 1, 1, null, false, null, null, "Total")); //$NON-NLS-1$
        BmTemplateHelper.CellLook bold = new BmTemplateHelper.CellLook();
        bold.fontBold = Boolean.TRUE;
        assertNull(BmTemplateHelper.applyCellFormat(doc, 2, 1, 2, 1, null, null, null, null, null,
            null, bold).error);
        BmTemplateHelper.setCellText(doc, 3, 1, "Plain", null); //$NON-NLS-1$

        assertTrue("the parameter format must not sit at index 0", cellOf(doc, 0, 0).getFormatIndex() > 0); //$NON-NLS-1$
        assertTrue(cellOf(doc, 1, 0).getFormatIndex() > 0);
        assertEquals("a cell with no format of its own keeps index 0", 0, //$NON-NLS-1$
            cellOf(doc, 2, 0).getFormatIndex());
        assertFalse("index 0 is the empty placeholder", doc.getFormats().get(0).isSetFillType()); //$NON-NLS-1$
        assertFalse(doc.getFormats().get(0).isSetFont());
        assertEquals(0, doc.getDefaultFormatIndex());
    }

    /** Bold on a cell that renders with a Verdana 8 FontDef keeps the face and the height. */
    @Test
    public void boldKeepsTheFaceAndHeightOfTheCellsFont()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        doc.getFonts().add(fontDef("Verdana", 8f)); //$NON-NLS-1$
        doc.getFormats().add(MoxelFactory.eINSTANCE.createFormat());
        Format carriesFont = MoxelFactory.eINSTANCE.createFormat();
        carriesFont.setFont(0);
        doc.getFormats().add(carriesFont);
        Cell cell = cellIn(doc, 0, 0, 1);

        BmTemplateHelper.CellLook look = new BmTemplateHelper.CellLook();
        look.fontBold = Boolean.TRUE;
        assertNull(BmTemplateHelper.applyCellFormat(doc, 1, 1, 1, 1, null, null, null, null, null,
            null, look).error);

        FontDef used = (FontDef)fontOfCell(doc, cell);
        assertEquals("Verdana", used.getFaceName()); //$NON-NLS-1$
        assertEquals(8.0, used.getHeight(), 0.01);
        assertTrue(used.isBold());
        assertEquals("the original font entry is left alone", 2, doc.getFonts().size()); //$NON-NLS-1$
    }

    /** A cell with no font of its own is bold on the row's font, not on Arial 10. */
    @Test
    public void boldOnAFontlessCellRidesOnTheRowFont()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        doc.getFonts().add(fontDef("Verdana", 8f)); //$NON-NLS-1$
        doc.getFormats().add(MoxelFactory.eINSTANCE.createFormat());
        Format rowCarries = MoxelFactory.eINSTANCE.createFormat();
        rowCarries.setFont(0);
        doc.getFormats().add(rowCarries);
        Row row = MoxelFactory.eINSTANCE.createRow();
        row.setFormatIndex(1);
        Cell cell = MoxelFactory.eINSTANCE.createCell();
        row.getCells().put(Integer.valueOf(0), cell);
        doc.getRows().put(Integer.valueOf(0), row);

        BmTemplateHelper.CellLook look = new BmTemplateHelper.CellLook();
        look.fontBold = Boolean.TRUE;
        assertNull(BmTemplateHelper.applyCellFormat(doc, 1, 1, 1, 1, null, null, null, null, null,
            null, look).error);

        FontDef used = (FontDef)fontOfCell(doc, cell);
        assertEquals("Verdana", used.getFaceName()); //$NON-NLS-1$
        assertEquals(8.0, used.getHeight(), 0.01);
        assertTrue(used.isBold());
    }

    /** Failing a row font too, the bold rides on the column's font. */
    @Test
    public void boldOnAFontlessCellRidesOnTheColumnFont()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        doc.getFonts().add(fontDef("Verdana", 8f)); //$NON-NLS-1$
        doc.getFormats().add(MoxelFactory.eINSTANCE.createFormat());
        Format columnCarries = MoxelFactory.eINSTANCE.createFormat();
        columnCarries.setFont(0);
        doc.getFormats().add(columnCarries);
        Columns columns = MoxelFactory.eINSTANCE.createColumns();
        Column column = MoxelFactory.eINSTANCE.createColumn();
        column.setFormatIndex(1);
        columns.getColumns().put(Integer.valueOf(0), column);
        doc.setColumns(columns);
        Cell cell = cellIn(doc, 0, 0, 0);

        BmTemplateHelper.CellLook look = new BmTemplateHelper.CellLook();
        look.fontBold = Boolean.TRUE;
        assertNull(BmTemplateHelper.applyCellFormat(doc, 1, 1, 1, 1, null, null, null, null, null,
            null, look).error);

        FontDef used = (FontDef)fontOfCell(doc, cell);
        assertEquals("Verdana", used.getFaceName()); //$NON-NLS-1$
        assertTrue(used.isBold());
    }

    /** Nothing before it names a font: the bold rides on the default format's font. */
    @Test
    public void boldOnAFontlessCellRidesOnTheDefaultFormatFont()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        doc.getFonts().add(fontDef("Verdana", 8f)); //$NON-NLS-1$
        doc.getFormats().add(MoxelFactory.eINSTANCE.createFormat());
        Format defaultCarries = MoxelFactory.eINSTANCE.createFormat();
        defaultCarries.setFont(0);
        doc.getFormats().add(defaultCarries);
        doc.setDefaultFormatIndex(1);
        Cell cell = cellIn(doc, 0, 0, 0);

        BmTemplateHelper.CellLook look = new BmTemplateHelper.CellLook();
        look.fontBold = Boolean.TRUE;
        assertNull(BmTemplateHelper.applyCellFormat(doc, 1, 1, 1, 1, null, null, null, null, null,
            null, look).error);

        FontDef used = (FontDef)fontOfCell(doc, cell);
        assertEquals("Verdana", used.getFaceName()); //$NON-NLS-1$
        assertTrue(used.isBold());
    }

    /**
     * Bold on a style-bound font keeps the binding: the copy carries the local bold and resolves
     * everything else through the font it refers to.
     */
    @Test
    public void boldOnAStyleBoundFontKeepsTheBinding()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        FontDef style = fontDef("Arial", 10f); //$NON-NLS-1$
        FontRef ref = McoreFactory.eINSTANCE.createFontRef();
        ref.setFont(style);
        doc.getFonts().add(ref);
        doc.getFormats().add(MoxelFactory.eINSTANCE.createFormat());
        Format carries = MoxelFactory.eINSTANCE.createFormat();
        carries.setFont(0);
        doc.getFormats().add(carries);
        Cell cell = cellIn(doc, 0, 0, 1);

        BmTemplateHelper.CellLook look = new BmTemplateHelper.CellLook();
        look.fontBold = Boolean.TRUE;
        assertNull(BmTemplateHelper.applyCellFormat(doc, 1, 1, 1, 1, null, null, null, null, null,
            null, look).error);

        Font used = fontOfCell(doc, cell);
        assertTrue("the bold font stays style-bound", used instanceof FontRef); //$NON-NLS-1$
        FontRef bound = (FontRef)used;
        assertTrue(bound.isSetBold());
        assertTrue(bound.bold());
        assertSame(style, bound.getFont());
        assertEquals("Arial", used.faceName()); //$NON-NLS-1$
        assertEquals(10.0, used.heightF(), 0.01);
    }

    /** Text asked together with a parameter fill is refused: the file does not store that text. */
    @Test
    public void textWithAParameterFillIsRefused()
    {
        Map<String, String> params = cellCall();
        params.put("text", "Header"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("fillType", "parameter"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("parameter", "Total"); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = tool.execute(params);

        assertFalse(answer.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("does not store text")); //$NON-NLS-1$
    }

    /** A template fill keeps the text only: a parameter name on it is refused. */
    @Test
    public void aTemplateFillDoesNotTakeAParameter()
    {
        Map<String, String> params = cellCall();
        params.put("text", "Sum []"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("fillType", "template"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("parameter", "Total"); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = tool.execute(params);

        assertFalse(answer.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("does not take a parameter")); //$NON-NLS-1$
    }

    /** Moving a cell from parameter to template drops the name the file would not store. */
    @Test
    public void aTemplateFillDropsTheStaleParameterName()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        BmTemplateHelper.setCellContent(doc, 1, 1, null, false, null, null, "Total"); //$NON-NLS-1$

        assertNull(BmTemplateHelper.setCellContent(doc, 1, 1, "Sum []", true, "ru", "template", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            null));

        assertNull(cellAt(doc).getParameter());
        assertEquals(FillType.TEMPLATE, formatOf(doc, cellAt(doc)).getFillType());
    }

    /** A colour that is not six hexadecimal digits is refused, not read as a dark grey. */
    @Test
    public void aColorThatIsNotHexadecimalDigitsIsRefused()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        BmTemplateHelper.CellLook look = new BmTemplateHelper.CellLook();
        look.textColor = "#+F+F+F"; //$NON-NLS-1$

        BmTemplateHelper.FormatOutcome outcome = BmTemplateHelper.applyCellFormat(doc, 1, 1, 1, 1,
            null, null, null, null, null, null, look);

        assertTrue(outcome.error, outcome.error != null && outcome.error.contains("textColor")); //$NON-NLS-1$
    }

    /** A font height of zero is refused: no cell carries a zero-point font. */
    @Test
    public void aFontSizeOfZeroIsRefused()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        BmTemplateHelper.CellLook look = new BmTemplateHelper.CellLook();
        look.fontSize = Float.valueOf(0f);

        BmTemplateHelper.FormatOutcome outcome = BmTemplateHelper.applyCellFormat(doc, 1, 1, 1, 1,
            null, null, null, null, null, null, look);

        assertTrue(outcome.error, outcome.error != null && outcome.error.contains("fontSize")); //$NON-NLS-1$
    }

    /** NaN is not a font height, and the refusal says so rather than storing it. */
    @Test
    public void aNonFiniteFontSizeIsRefused()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        BmTemplateHelper.CellLook look = new BmTemplateHelper.CellLook();
        look.fontSize = Float.valueOf(Float.NaN);

        BmTemplateHelper.FormatOutcome outcome = BmTemplateHelper.applyCellFormat(doc, 1, 1, 1, 1,
            null, null, null, null, null, null, look);

        assertTrue(outcome.error, outcome.error != null && outcome.error.contains("fontSize")); //$NON-NLS-1$
    }

    /** A line and a colour the table already has are pointed at, not duplicated. */
    @Test
    public void anExistingLineAndColorAreReused()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        CellLine line = ContentFactory.eINSTANCE.createCellLine();
        line.setStyle(CellLineStyle.SOLID);
        line.setWidth(1);
        line.setGap(false);
        doc.getLines().add(line);
        ColorDef red = McoreFactory.eINSTANCE.createColorDef();
        red.setRed(255);
        red.setGreen(0);
        red.setBlue(0);
        doc.getColors().add(red);
        BmTemplateHelper.CellLook look = new BmTemplateHelper.CellLook();
        look.border = "Solid"; //$NON-NLS-1$
        look.textColor = "#FF0000"; //$NON-NLS-1$

        assertNull(BmTemplateHelper.applyCellFormat(doc, 1, 1, 1, 1, null, null, null, null, null,
            null, look).error);

        Format format = formatOf(doc, cellAt(doc));
        assertEquals("the pre-existing line is pointed at", 0, format.getLeftBorder()); //$NON-NLS-1$
        assertEquals("the pre-existing colour is pointed at", 0, format.getTextColor()); //$NON-NLS-1$
        assertEquals(1, doc.getLines().size());
        assertEquals(1, doc.getColors().size());
    }

    /** Changing one of two cells that share a format leaves the other cell and the format alone. */
    @Test
    public void changingOneCellOfASharedFormatLeavesTheOther()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        BmTemplateHelper.CellLook boxed = new BmTemplateHelper.CellLook();
        boxed.border = "Solid"; //$NON-NLS-1$
        assertNull(BmTemplateHelper.applyCellFormat(doc, 1, 1, 1, 2, null, null, null, null, null,
            null, boxed).error);
        Cell first = cellOf(doc, 0, 0);
        Cell second = cellOf(doc, 0, 1);
        assertEquals(first.getFormatIndex(), second.getFormatIndex());
        int shared = second.getFormatIndex();

        BmTemplateHelper.CellLook bold = new BmTemplateHelper.CellLook();
        bold.fontBold = Boolean.TRUE;
        assertNull(BmTemplateHelper.applyCellFormat(doc, 1, 1, 1, 1, null, null, null, null, null,
            null, bold).error);

        assertTrue(first.getFormatIndex() != shared);
        assertEquals("the untouched cell keeps the format it had", shared, //$NON-NLS-1$
            second.getFormatIndex());
        assertFalse("the shared format itself is unchanged", doc.getFormats().get(shared).isSetFont()); //$NON-NLS-1$
    }

    private static FontDef fontDef(String face, float height)
    {
        FontDef font = McoreFactory.eINSTANCE.createFontDef();
        font.setFaceName(face);
        font.setHeight(height);
        font.setScale(100);
        return font;
    }

    /** A cell placed at 0-based keys carrying a format index, in a document with the placeholder. */
    private static Cell cellIn(SpreadsheetDocument doc, int rowKey, int colKey, int formatIndex)
    {
        Row row = doc.getRows().get(Integer.valueOf(rowKey));
        if (row == null)
        {
            row = MoxelFactory.eINSTANCE.createRow();
            doc.getRows().put(Integer.valueOf(rowKey), row);
        }
        Cell cell = MoxelFactory.eINSTANCE.createCell();
        cell.setFormatIndex(formatIndex);
        row.getCells().put(Integer.valueOf(colKey), cell);
        return cell;
    }

    private static Cell cellOf(SpreadsheetDocument doc, int rowKey, int colKey)
    {
        return doc.getRows().get(Integer.valueOf(rowKey)).getCells().get(Integer.valueOf(colKey));
    }

    private static Font fontOfCell(SpreadsheetDocument doc, Cell cell)
    {
        return doc.getFonts().get(doc.getFormats().get(cell.getFormatIndex()).getFont());
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
