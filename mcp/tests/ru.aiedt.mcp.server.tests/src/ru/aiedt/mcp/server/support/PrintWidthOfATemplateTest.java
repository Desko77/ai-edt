/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;

import com._1c.g5.v8.dt.moxel.Column;
import com._1c.g5.v8.dt.moxel.Columns;
import com._1c.g5.v8.dt.moxel.ColumnsArea;
import com._1c.g5.v8.dt.moxel.Format;
import com._1c.g5.v8.dt.moxel.MoxelFactory;
import com._1c.g5.v8.dt.moxel.PageOrientation;
import com._1c.g5.v8.dt.moxel.PrintSettings;
import com._1c.g5.v8.dt.moxel.Row;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;

/**
 * Whether a template's print area fits the sheet by width.
 * <p>
 * The widths are millimetres, so the answer depends on how wide one character is - and EDT measures
 * that with SWT, from the font the machine happens to have. The measurement is therefore handed in
 * rather than discovered here: these layouts are the ones a template from a real configuration has
 * (a print form 805 eighths of a character wide on a 190 mm strip, an A4 sheet in landscape, a
 * template scaled down to 34%), and the two character widths below are the ones that corpus was
 * measured at. A verdict that rests on the machine's fonts is not a verdict a test can hold still.
 * </p>
 * <p>
 * Documents are built with the model factory: the question is arithmetic over the model, so it is
 * asked of a model assembled in the test rather than of a template on a stand.
 * </p>
 */
public class PrintWidthOfATemplateTest
{
    /** What Arial 8 measured on a machine whose font metrics are whole pixels. */
    private static final double NARROW_CHAR_MM = 1.8521;

    /** And on one whose metrics are fractional, which is where the same layout stops fitting. */
    private static final double WIDE_CHAR_MM = 1.8824;

    private static SpreadsheetDocument emptyDocument()
    {
        return MoxelFactory.eINSTANCE.createSpreadsheetDocument();
    }

    /**
     * Sets the columns up so that column 0 is the first of the given widths, and the set's declared
     * size covers them - the way a template on disk declares its columns.
     *
     * @param doc the document.
     * @param widths each column's width, in eighths of a character.
     */
    private static void withColumnWidths(SpreadsheetDocument doc, int... widths)
    {
        Columns columns = MoxelFactory.eINSTANCE.createColumns();
        columns.setSize(widths.length);
        doc.setColumns(columns);
        for (int index = 0; index < widths.length; index++)
        {
            columns.getColumns().put(Integer.valueOf(index), columnOf(formatOfWidth(doc, widths[index])));
        }
    }

    /** @return a row of that many cells, added to the document. */
    private static Row withRowOfCells(SpreadsheetDocument doc, int count)
    {
        Row row = MoxelFactory.eINSTANCE.createRow();
        for (int index = 0; index < count; index++)
        {
            row.getCells().put(Integer.valueOf(index), MoxelFactory.eINSTANCE.createCell());
        }
        doc.getRows().put(Integer.valueOf(0), row);
        return row;
    }

    private static int formatOfWidth(SpreadsheetDocument doc, int width)
    {
        Format format = MoxelFactory.eINSTANCE.createFormat();
        format.setWidth(width);
        doc.getFormats().add(format);
        return doc.getFormats().size() - 1;
    }

    private static int formatWithoutWidth(SpreadsheetDocument doc)
    {
        doc.getFormats().add(MoxelFactory.eINSTANCE.createFormat());
        return doc.getFormats().size() - 1;
    }

    private static Column columnOf(int formatIndex)
    {
        Column column = MoxelFactory.eINSTANCE.createColumn();
        column.setFormatIndex(formatIndex);
        return column;
    }

    private static PrintSettings pageSettings(SpreadsheetDocument doc)
    {
        PrintSettings settings = MoxelFactory.eINSTANCE.createPrintSettings();
        doc.setPrintSettings(settings);
        return settings;
    }

    private static String verdict(Map<String, Object> answer)
    {
        return (String)answer.get("verdict"); //$NON-NLS-1$
    }

    private static double millimetres(Map<String, Object> answer, String field)
    {
        return ((Number)answer.get(field)).doubleValue();
    }

    private static TemplatePrintWidth.CharMetrics narrow()
    {
        return new TemplatePrintWidth.CharMetrics(NARROW_CHAR_MM, "test"); //$NON-NLS-1$
    }

    @Test
    public void aTemplateWithRoomToSpareFitsTheSheet()
    {
        // A print form 805 units wide - 100.6 characters - on the 190 mm a A4 sheet has left
        // between two 10 mm margins.
        SpreadsheetDocument doc = emptyDocument();
        withColumnWidths(doc, 200, 200, 200, 150, 55);
        withRowOfCells(doc, 5);

        Map<String, Object> answer =
            TemplatePrintWidth.check(doc, TemplatePrintWidth.DEFAULT_SMALL_SCALE_PERCENT, narrow());

        assertEquals("fits", verdict(answer)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(805, answer.get("contentWidthCharUnits")); //$NON-NLS-1$
        assertEquals(190.0, millimetres(answer, "printableWidthMm"), 0.01); //$NON-NLS-1$
        assertEquals("3.6 mm of room, give or take a whole-pixel measurement", //$NON-NLS-1$
            3.6, millimetres(answer, "marginMm"), 1.0); //$NON-NLS-1$
    }

    @Test
    public void aColumnSetCountsItsDeclaredSizeNotItsLastCell()
    {
        // The receipt the corpus measured at 805: five columns holding 805 eighths between them,
        // the last cell in column 4 - and a sixth declared column, 13 wide, that no cell reaches.
        // The paginator measures a set to its declared size: 818, not 805.
        SpreadsheetDocument doc = emptyDocument();
        withColumnWidths(doc, 200, 200, 200, 150, 55);
        doc.getColumns().getColumns().put(Integer.valueOf(5), columnOf(formatOfWidth(doc, 13)));
        doc.getColumns().setSize(6);
        withRowOfCells(doc, 5);

        Map<String, Object> answer =
            TemplatePrintWidth.check(doc, TemplatePrintWidth.DEFAULT_SMALL_SCALE_PERCENT, narrow());

        assertEquals(818, answer.get("contentWidthCharUnits")); //$NON-NLS-1$
        assertEquals(818 / 8.0 * NARROW_CHAR_MM, millimetres(answer, "contentWidthMm"), 0.01); //$NON-NLS-1$
    }

    @Test
    public void aRowColumnSetWiderThanTheDocumentColumnsIsTheSetPrinted()
    {
        // A second set of columns, carried by a row: the paginator holds a contest between the
        // document's columns and every set a row brings, and the wider set is the one printed.
        // Both sets need their own id - the contest is held per id, and a real document gives
        // every set one.
        SpreadsheetDocument doc = emptyDocument();
        withColumnWidths(doc, 40, 40);
        doc.getColumns().setColumnsId(UUID.randomUUID());
        Row row = withRowOfCells(doc, 2);
        Columns own = MoxelFactory.eINSTANCE.createColumns();
        own.setColumnsId(UUID.randomUUID());
        own.setSize(2);
        own.setFormatIndex(formatOfWidth(doc, 100));
        row.setColumns(own);

        Map<String, Object> answer =
            TemplatePrintWidth.check(doc, TemplatePrintWidth.DEFAULT_SMALL_SCALE_PERCENT, narrow());

        assertEquals(200, answer.get("contentWidthCharUnits")); //$NON-NLS-1$
    }

    @Test
    public void aPrintAreaNamesTheColumnsTheSheetPrints()
    {
        // An area that names columns keeps the measurement to those columns: the widest set behind
        // it does not widen the answer.
        SpreadsheetDocument doc = emptyDocument();
        withColumnWidths(doc, 100, 100, 100);
        withRowOfCells(doc, 3);
        ColumnsArea area = MoxelFactory.eINSTANCE.createColumnsArea();
        area.setBegin(0);
        area.setEnd(1);
        area.setColumns(doc.getColumns());
        doc.setPrintArea(area);

        Map<String, Object> answer =
            TemplatePrintWidth.check(doc, TemplatePrintWidth.DEFAULT_SMALL_SCALE_PERCENT, narrow());

        assertEquals(200, answer.get("contentWidthCharUnits")); //$NON-NLS-1$
    }

    @Test
    public void landscapeComesFromTheModelAndTheSameWidthOverflowsInPortrait()
    {
        // 1239 units - 154.9 characters - and no margins declared, which is what the corpus
        // template on an A4 landscape sheet looks like.
        SpreadsheetDocument landscape = emptyDocument();
        withColumnWidths(landscape, 177, 177, 177, 177, 177, 177, 177);
        withRowOfCells(landscape, 7);
        PrintSettings settings = pageSettings(landscape);
        settings.setPageOrientation(PageOrientation.LANDSCAPE);
        settings.setLeftMargin(0);
        settings.setRightMargin(0);

        Map<String, Object> onLandscape =
            TemplatePrintWidth.check(landscape, TemplatePrintWidth.DEFAULT_SMALL_SCALE_PERCENT, narrow());
        assertEquals("landscape", onLandscape.get("orientation")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(297.0, millimetres(onLandscape, "printableWidthMm"), 0.01); //$NON-NLS-1$
        assertEquals("fits", verdict(onLandscape)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(10.2, millimetres(onLandscape, "marginMm"), 0.1); //$NON-NLS-1$

        SpreadsheetDocument portrait = emptyDocument();
        withColumnWidths(portrait, 177, 177, 177, 177, 177, 177, 177);
        withRowOfCells(portrait, 7);
        PrintSettings portraitPage = pageSettings(portrait);
        portraitPage.setLeftMargin(0);
        portraitPage.setRightMargin(0);

        Map<String, Object> onPortrait =
            TemplatePrintWidth.check(portrait, TemplatePrintWidth.DEFAULT_SMALL_SCALE_PERCENT, narrow());
        assertEquals("portrait", onPortrait.get("orientation")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(210.0, millimetres(onPortrait, "printableWidthMm"), 0.01); //$NON-NLS-1$
        assertEquals("overflows", verdict(onPortrait)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aTemplateWithinAPercentOfTheEdgeIsBorderlineAndNeverOverflows()
    {
        // 814 units on the same 190 mm strip: 1.6 mm to spare at one character width, 1.5 mm over
        // at the other. Which of the two a machine measures decides the verdict, and the point of
        // the borderline band is that neither of them is an overflow.
        SpreadsheetDocument doc = emptyDocument();
        withColumnWidths(doc, 177, 177, 177, 177, 106);
        withRowOfCells(doc, 5);

        Map<String, Object> roomToSpare = TemplatePrintWidth.check(doc,
            TemplatePrintWidth.DEFAULT_SMALL_SCALE_PERCENT, narrow());
        assertEquals("fits", verdict(roomToSpare)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(millimetres(roomToSpare, "marginMm") > 0); //$NON-NLS-1$

        Map<String, Object> overTheEdge = TemplatePrintWidth.check(doc,
            TemplatePrintWidth.DEFAULT_SMALL_SCALE_PERCENT, new TemplatePrintWidth.CharMetrics(WIDE_CHAR_MM, "test")); //$NON-NLS-1$
        assertEquals("borderline", verdict(overTheEdge)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("over by a millimetre and a half, not by a page", //$NON-NLS-1$
            millimetres(overTheEdge, "marginMm") > -2.0 //$NON-NLS-1$
                && millimetres(overTheEdge, "marginMm") < 0); //$NON-NLS-1$
    }

    @Test
    public void anUndeclaredPageIsAnsweredAsA4PortraitWithTenMillimeterMargins()
    {
        SpreadsheetDocument doc = emptyDocument();
        withColumnWidths(doc, 72, 72);
        withRowOfCells(doc, 2);

        // Whatever the model returns for print settings it never set - the platform fills them in
        // when it prints, and the answer has to fill in the same ones, scale included.
        List<String> assumed = assumedOf(TemplatePrintWidth.check(doc,
            TemplatePrintWidth.DEFAULT_SMALL_SCALE_PERCENT, narrow()));
        assertTrue(assumed.toString(), assumed.contains("paper=A4")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(assumed.toString(), assumed.contains("orientation=portrait")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(assumed.toString(), assumed.contains("leftMargin=10mm")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(assumed.toString(), assumed.contains("rightMargin=10mm")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(assumed.toString(), assumed.contains("scale=100%")); //$NON-NLS-1$ //$NON-NLS-2$

        PrintSettings settings = pageSettings(doc);
        List<String> onAnEmptyPage = assumedOf(TemplatePrintWidth.check(doc,
            TemplatePrintWidth.DEFAULT_SMALL_SCALE_PERCENT, narrow()));
        assertEquals("an untouched page takes exactly the same defaults", //$NON-NLS-1$
            assumed, onAnEmptyPage);
        assertFalse(settings.isSetPageOrientation());

        // A scale of 100 the model actually declared is not a default: it stays out of `assumed`,
        // so the two ways of printing at 100% stay tellable apart in the answer.
        settings.setScale(100);
        List<String> declared = assumedOf(TemplatePrintWidth.check(doc,
            TemplatePrintWidth.DEFAULT_SMALL_SCALE_PERCENT, narrow()));
        assertFalse(declared.toString(), declared.contains("scale=100%")); //$NON-NLS-1$
        assertEquals(100, printScaleOf(TemplatePrintWidth.check(doc,
            TemplatePrintWidth.DEFAULT_SMALL_SCALE_PERCENT, narrow())));
    }

    @Test
    public void aPaperDeclaredByItsDimensionsIsMeasuredByThem()
    {
        // Code -1 names no paper: the sheet is as big as pageWidth and pageHeight say, in
        // millimetres, with the orientation picking the side. Nothing was assumed.
        SpreadsheetDocument doc = emptyDocument();
        withColumnWidths(doc, 72, 72);
        withRowOfCells(doc, 2);
        PrintSettings settings = pageSettings(doc);
        settings.setPaper(-1);
        settings.setPageWidth(148.0f);
        settings.setPageHeight(210.0f);

        Map<String, Object> portrait =
            TemplatePrintWidth.check(doc, TemplatePrintWidth.DEFAULT_SMALL_SCALE_PERCENT, narrow());
        assertEquals("custom 148x210mm", portrait.get("paper")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(128.0, millimetres(portrait, "printableWidthMm"), 0.01); //$NON-NLS-1$
        assertFalse(assumedOf(portrait).toString(), assumedOf(portrait).contains("paper=A4")); //$NON-NLS-1$

        settings.setPageOrientation(PageOrientation.LANDSCAPE);
        Map<String, Object> landscape =
            TemplatePrintWidth.check(doc, TemplatePrintWidth.DEFAULT_SMALL_SCALE_PERCENT, narrow());
        assertEquals("the landscape sheet is as wide as pageHeight said", 190.0, //$NON-NLS-1$
            millimetres(landscape, "printableWidthMm"), 0.01); //$NON-NLS-1$
    }

    @Test
    public void aPaperCodeOtherThanA4IsMeasuredAsA4AndSaysSo()
    {
        SpreadsheetDocument doc = emptyDocument();
        withColumnWidths(doc, 72, 72);
        withRowOfCells(doc, 2);
        PrintSettings settings = pageSettings(doc);
        settings.setPaper(8);

        Map<String, Object> answer =
            TemplatePrintWidth.check(doc, TemplatePrintWidth.DEFAULT_SMALL_SCALE_PERCENT, narrow());

        assertEquals("code 8", answer.get("paper")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(assumedOf(answer).toString(), //$NON-NLS-1$
            assumedOf(answer).contains("paper=8 measured as A4")); //$NON-NLS-1$
        assertEquals(190.0, millimetres(answer, "printableWidthMm"), 0.01); //$NON-NLS-1$
    }

    @Test
    public void fitToPageReportsTheScaleItWouldNeedAndWarnsWhenItIsTooSmall()
    {
        // 2392 units wide - 553.8 mm of content - on a 190 mm strip: the platform would print it at
        // 34%, which is 2.7 point type.
        SpreadsheetDocument doc = emptyDocument();
        withColumnWidths(doc, 299, 299, 299, 299, 299, 299, 299, 299);
        withRowOfCells(doc, 8);
        pageSettings(doc).setFitToPage(true);

        Map<String, Object> answer =
            TemplatePrintWidth.check(doc, TemplatePrintWidth.DEFAULT_SMALL_SCALE_PERCENT, narrow());

        assertEquals(553.8, millimetres(answer, "contentWidthMm"), 0.1); //$NON-NLS-1$
        assertEquals("smallPrint", verdict(answer)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(34, answer.get("requiredScalePercent")); //$NON-NLS-1$
        assertEquals(2.7, millimetres(answer, "fontSizeAfterScale"), 0.05); //$NON-NLS-1$

        // The same layout with the warning threshold moved below the scale it needs: it still
        // shrinks, and it is no longer called unreadable.
        Map<String, Object> lenient = TemplatePrintWidth.check(doc, 25, narrow());
        assertEquals("fits", verdict(lenient)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(34, lenient.get("requiredScalePercent")); //$NON-NLS-1$
    }

    @Test
    public void aTemplateWithNoCellsIsEmpty()
    {
        Map<String, Object> blank = TemplatePrintWidth.check(emptyDocument(),
            TemplatePrintWidth.DEFAULT_SMALL_SCALE_PERCENT, narrow());
        assertEquals("empty", verdict(blank)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, blank.get("contentWidthCharUnits")); //$NON-NLS-1$
        assertNull("no scale is owed on content there is none of", //$NON-NLS-1$
            blank.get("requiredScalePercent")); //$NON-NLS-1$

        // A print area naming columns does not make an empty template printable: the columns would
        // be counted at the platform's default width and answer 'fits' about nothing at all.
        SpreadsheetDocument withArea = emptyDocument();
        ColumnsArea area = MoxelFactory.eINSTANCE.createColumnsArea();
        area.setBegin(0);
        area.setEnd(2);
        withArea.setPrintArea(area);
        assertEquals("empty", verdict(TemplatePrintWidth.check(withArea, //$NON-NLS-1$ //$NON-NLS-2$
            TemplatePrintWidth.DEFAULT_SMALL_SCALE_PERCENT, narrow())));

        assertEquals("empty", verdict(TemplatePrintWidth.check(null, //$NON-NLS-1$ //$NON-NLS-2$
            TemplatePrintWidth.DEFAULT_SMALL_SCALE_PERCENT, narrow())));
    }

    @Test
    public void aColumnTakesItsWidthFromItsOwnFormatThenTheSetThenTheDocument()
    {
        SpreadsheetDocument doc = emptyDocument();
        Columns set = MoxelFactory.eINSTANCE.createColumns();
        doc.setColumns(set);
        int ownFormat = formatOfWidth(doc, 100);
        int defaultFormat = formatOfWidth(doc, 64);
        int plainFormat = formatWithoutWidth(doc);
        doc.setDefaultFormatIndex(defaultFormat);
        set.setFormatIndex(plainFormat);
        set.getColumns().put(Integer.valueOf(0), columnOf(ownFormat));
        set.getColumns().put(Integer.valueOf(1), columnOf(plainFormat));

        assertEquals("its own format wins", 100, //$NON-NLS-1$
            TemplatePrintWidth.columnWidthCharUnits(doc, set, 0));
        assertEquals("then the document's default format", 64, //$NON-NLS-1$
            TemplatePrintWidth.columnWidthCharUnits(doc, set, 1));
        assertEquals("and for a column the set does not mention at all", 64, //$NON-NLS-1$
            TemplatePrintWidth.columnWidthCharUnits(doc, set, 3));

        set.setFormatIndex(formatOfWidth(doc, 80));
        assertEquals("the set's own format outranks the document's", 80, //$NON-NLS-1$
            TemplatePrintWidth.columnWidthCharUnits(doc, set, 1));

        // A row or a print area brings its own set of columns, and that one is what its columns are
        // measured by - not the document's.
        Columns own = MoxelFactory.eINSTANCE.createColumns();
        own.setFormatIndex(formatOfWidth(doc, 40));
        assertEquals(40, TemplatePrintWidth.columnWidthCharUnits(doc, own, 1));

        SpreadsheetDocument bare = emptyDocument();
        assertEquals("and with nothing declared anywhere, the platform's default column", //$NON-NLS-1$
            TemplatePrintWidth.DEFAULT_COLUMN_WIDTH_CHAR_UNITS,
            TemplatePrintWidth.columnWidthCharUnits(bare, MoxelFactory.eINSTANCE.createColumns(), 3));
    }

    @Test
    public void aMeasurementThatAnswersIsTheProcessAnswerAndSaysWhereItCameFrom()
    {
        try
        {
            TemplatePrintWidth.measuredCharWidth = null;
            TemplatePrintWidth.charMeasurement = () -> new TemplatePrintWidth.CharMetrics(1.9, "jdk:probe 8"); //$NON-NLS-1$

            TemplatePrintWidth.CharMetrics metrics = TemplatePrintWidth.resolveCharMetrics();

            assertEquals(1.9, metrics.charWidthMm(), 0.0001);
            assertEquals("jdk:probe 8", metrics.source()); //$NON-NLS-1$

            // The measurement runs once per process: a later one that fails does not change the
            // answer a caller already has.
            TemplatePrintWidth.charMeasurement = () -> {
                throw new IllegalStateException("the second measurement must not run"); //$NON-NLS-1$
            };
            assertEquals("jdk:probe 8", TemplatePrintWidth.resolveCharMetrics().source()); //$NON-NLS-1$
        }
        finally
        {
            resetCharMetrics();
        }
    }

    @Test
    public void aMeasurementThatThrowsAnswersWithTheConstant()
    {
        try
        {
            TemplatePrintWidth.measuredCharWidth = null;
            TemplatePrintWidth.charMeasurement = () -> {
                throw new IllegalStateException("no font machinery here"); //$NON-NLS-1$
            };

            TemplatePrintWidth.CharMetrics metrics = TemplatePrintWidth.resolveCharMetrics();

            assertEquals(TemplatePrintWidth.FALLBACK_CHAR_WIDTH_MM, metrics.charWidthMm(), 0.0001);
            assertEquals("constant", metrics.source()); //$NON-NLS-1$
        }
        finally
        {
            resetCharMetrics();
        }
    }

    @Test
    public void aMeasurementThatNeverAnswersDoesNotHoldTheCallerPastItsDeadline()
    {
        try
        {
            TemplatePrintWidth.measuredCharWidth = null;
            TemplatePrintWidth.charMeasurementTimeoutMs = 150;
            TemplatePrintWidth.charMeasurement = () -> {
                try
                {
                    Thread.sleep(60_000);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
                return new TemplatePrintWidth.CharMetrics(1.9, "jdk:too late"); //$NON-NLS-1$
            };

            long startedAt = System.nanoTime();
            TemplatePrintWidth.CharMetrics metrics = TemplatePrintWidth.resolveCharMetrics();
            long waitedMs = (System.nanoTime() - startedAt) / 1_000_000;

            assertEquals("constant", metrics.source()); //$NON-NLS-1$
            assertEquals(TemplatePrintWidth.FALLBACK_CHAR_WIDTH_MM, metrics.charWidthMm(), 0.0001);
            assertTrue("held for " + waitedMs + " ms, past the deadline", //$NON-NLS-1$ //$NON-NLS-2$
                waitedMs < 2000);
        }
        finally
        {
            resetCharMetrics();
        }
    }

    /** Puts the measurement seam back the way production runs it. */
    private static void resetCharMetrics()
    {
        TemplatePrintWidth.measuredCharWidth = null;
        TemplatePrintWidth.charMeasurement = TemplatePrintWidth.MEASURE_THROUGH_THE_JDK;
        TemplatePrintWidth.charMeasurementTimeoutMs = 2000;
    }

    @SuppressWarnings("unchecked")
    private static List<String> assumedOf(Map<String, Object> answer)
    {
        return (List<String>)answer.get("assumed"); //$NON-NLS-1$
    }

    private static int printScaleOf(Map<String, Object> answer)
    {
        return ((Number)answer.get("printScalePercent")).intValue(); //$NON-NLS-1$
    }
}
