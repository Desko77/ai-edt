/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.EMap;

import com._1c.g5.v8.dt.moxel.Cell;
import com._1c.g5.v8.dt.moxel.Column;
import com._1c.g5.v8.dt.moxel.Columns;
import com._1c.g5.v8.dt.moxel.ColumnsArea;
import com._1c.g5.v8.dt.moxel.Format;
import com._1c.g5.v8.dt.moxel.PageOrientation;
import com._1c.g5.v8.dt.moxel.PrintSettings;
import com._1c.g5.v8.dt.moxel.Rect;
import com._1c.g5.v8.dt.moxel.RectArea;
import com._1c.g5.v8.dt.moxel.Row;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.moxel.content.Area;

import ru.aiedt.mcp.server.Activator;

/**
 * Whether a template's print area fits the sheet by width, worked out from the moxel model alone.
 * <p>
 * A template that is a few millimetres too wide is not a template that fails: it prints as an extra
 * column page, or is shrunk to a scale nobody can read, and the platform says nothing about either.
 * The
 * model carries the widths, the print settings and the print scale, so the answer is arithmetic -
 * no layout engine and no running infobase are needed.
 * </p>
 * <p>
 * Widths are held in {@code Format.width}, in eighths of a character (the unit
 * {@code UnitsConverter.UNIT_PER_CHAR} names, and the one behind the platform's own default column
 * of 72). Millimetres come from one character's advance, which EDT measures with SWT: a template
 * opened in the editor gets the width of the font it finds, so the same document is slightly wider
 * or narrower depending on the machine. {@link #resolveCharMetrics()} measures the same character
 * through the JDK instead, and answers with the source it used, because a millimetre-sized
 * difference is a verdict that can flip.
 * </p>
 */
public final class TemplatePrintWidth
{
    /** Eighths of a character per character, the unit of {@code Format.width}. */
    private static final int CHAR_UNITS_PER_CHARACTER = 8;

    /** The column width the platform uses when neither the column nor a format sets one. */
    public static final int DEFAULT_COLUMN_WIDTH_CHAR_UNITS = 72;

    /** The character whose advance is taken as the width of one character. */
    private static final String MEASURED_CHARACTER = "X"; //$NON-NLS-1$

    /** The font size EDT measures templates with. */
    private static final int FONT_SIZE_PT = 8;

    /** The font family EDT measures templates with. */
    private static final String FONT_FAMILY = "Arial"; //$NON-NLS-1$

    /**
     * Taken when no font metric can be obtained at all. Within 1.6% of what the JDK reports for
     * Arial 8 wherever that font exists, so the verdict is the same either way but for a template
     * closer to the edge than the corpus has.
     */
    public static final double FALLBACK_CHAR_WIDTH_MM = 1.867;

    /** A4, portrait. The paper the platform falls back to when the model names none. */
    private static final int A4_SHORT_SIDE_MM = 210;

    private static final int A4_LONG_SIDE_MM = 297;

    /** The printer paper code of A4. */
    private static final int A4_PAPER_CODE = 9;

    /** Ten millimetres, in the hundredths of a millimetre the margins are held in. */
    private static final int DEFAULT_MARGIN_HUNDREDTHS_MM = 1000;

    private static final int DEFAULT_SCALE_PERCENT = 100;

    /** Above the printable width by this much, and the sheet is close enough to read as a warning. */
    private static final double BORDERLINE_RATIO = 1.05;

    /** Below this scale the print is too small to read unless the caller says otherwise. */
    public static final int DEFAULT_SMALL_SCALE_PERCENT = 75;

    private static final double MILLIMETER_PER_INCH = 25.4;

    private static final double POINTS_PER_INCH = 72.0;

    private TemplatePrintWidth() {}

    /**
     * The width of one character, and where that number came from.
     *
     * @param charWidthMm the advance width of one character, in millimetres.
     * @param source a short token naming the source, so an answer carries its own provenance.
     */
    public record CharMetrics(double charWidthMm, String source)
    {
    }

    /**
     * Measures one character of the template font through the JDK.
     * <p>
     * The font is named but not available everywhere: a machine without Arial answers with whatever
     * stands in for it, which is what the editor would do as well. On a machine with no graphics
     * environment at all - which is how the test runtime and a headless CI runner start - the font
     * machinery can refuse to run, and that is not a reason to have no answer: the constant stands
     * in and says so.
     * </p>
     *
     * @return the measured width and its source, never null
     */
    public static CharMetrics resolveCharMetrics()
    {
        try
        {
            Font font = new Font(FONT_FAMILY, Font.PLAIN, FONT_SIZE_PT);
            FontRenderContext context = new FontRenderContext(null, true, true);
            GlyphVector glyphs = font.createGlyphVector(context, MEASURED_CHARACTER);
            double advancePt = glyphs.getGlyphMetrics(0).getAdvanceX();
            if (advancePt > 0)
            {
                double widthMm = advancePt * MILLIMETER_PER_INCH / POINTS_PER_INCH;
                return new CharMetrics(widthMm,
                    "jdk:" + font.getFontName() + " " + FONT_SIZE_PT); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        catch (Throwable e)
        {
            // Deliberately broad: a headless JVM answers with HeadlessException, a JVM that wanted a
            // display answers with InternalError, and a missing font manager answers with something
            // else again. All three mean the same thing here - measure with the constant instead.
            Activator.logDebug("print width: no font metrics available: " + e); //$NON-NLS-1$
        }
        return new CharMetrics(FALLBACK_CHAR_WIDTH_MM, "constant"); //$NON-NLS-1$
    }

    /**
     * The width of one column, following the inheritance the platform uses.
     * <p>
     * A column's own format first, then the format of the set of columns it belongs to, then the
     * document's default format, then the platform's default column. The set is consulted rather
     * than the document alone because a row or a print area may carry its own: a template that
     * formats its columns through the row they sit in would otherwise be measured with the
     * document's widths.
     * </p>
     *
     * @param doc the document holding the format table.
     * @param set the set of columns to consult first, or {@code null} for the document's own.
     * @param column the column, 0-based.
     * @return the width in eighths of a character, never negative
     */
    public static int columnWidthCharUnits(SpreadsheetDocument doc, Columns set, int column)
    {
        if (doc == null)
        {
            return DEFAULT_COLUMN_WIDTH_CHAR_UNITS;
        }
        if (set != null)
        {
            EMap<Integer, Column> byIndex = set.getColumns();
            Column own = byIndex == null ? null : byIndex.get(Integer.valueOf(column));
            if (own != null)
            {
                Integer width = widthOfFormat(doc, own.getFormatIndex());
                if (width != null)
                {
                    return width.intValue();
                }
            }
            Integer width = widthOfFormat(doc, set.getFormatIndex());
            if (width != null)
            {
                return width.intValue();
            }
        }
        Integer width = widthOfFormat(doc, doc.getDefaultFormatIndex());
        return width == null ? DEFAULT_COLUMN_WIDTH_CHAR_UNITS : width.intValue();
    }

    /**
     * Whether the print area fits the sheet, and what the answer rests on.
     * <p>
     * The verdict is a warning at any value, never a failure: a template that overflows is printed
     * by the platform, only differently from what its author expected.
     * </p>
     * <p>
     * The print scale the model may hold is reported but not applied. The platform's own fit
     * calculation compares the content against the printable width as it stands and applies the
     * scale later, at print time - so folding it in here would answer a different question than the
     * one the platform answers.
     * </p>
     *
     * @param doc the template's document, or {@code null} for an empty answer.
     * @param smallScalePercent below this required scale the answer warns of unreadable type; zero
     *            or less takes the default, and above 100 is capped at 100.
     * @param metrics the width of one character; {@code null} falls back to the constant.
     * @return the answer, in the order the fields are read.
     */
    public static Map<String, Object> check(SpreadsheetDocument doc, int smallScalePercent,
        CharMetrics metrics)
    {
        double charWidthMm = metrics == null ? FALLBACK_CHAR_WIDTH_MM : metrics.charWidthMm();
        String source = metrics == null ? "constant" : metrics.source(); //$NON-NLS-1$
        int threshold = smallScalePercent <= 0 ? DEFAULT_SMALL_SCALE_PERCENT
            : Math.min(smallScalePercent, 100);

        List<String> assumed = new ArrayList<>();
        PrintSettings settings = doc == null ? null : doc.getPrintSettings();

        boolean landscape = landscapeOf(settings, assumed);
        String paper = paperOf(settings, assumed);
        int sheetWidthMm = landscape ? A4_LONG_SIDE_MM : A4_SHORT_SIDE_MM;
        int leftMargin = marginOf(settings, true, assumed);
        int rightMargin = marginOf(settings, false, assumed);
        // A page cannot have a negative printable width; margins that wide are an overflow either
        // way, and zero is the floor a reader can act on.
        double printableWidthMm = Math.max(0.0,
            sheetWidthMm - (leftMargin + rightMargin) / 100.0);
        int printScalePercent =
            settings != null && settings.isSetScale() ? settings.getScale() : DEFAULT_SCALE_PERCENT;

        Columns set = null;
        int[] columns = printAreaColumns(doc);
        Row widest = widestRow(doc);
        if (widest == null)
        {
            // No cell anywhere: there is no content to measure, whatever the print area names.
            columns = null;
        }
        else if (columns != null)
        {
            set = printAreaColumnsSet(doc);
        }
        else
        {
            set = widest.getColumns();
            int last = lastColumn(widest);
            columns = last < 0 ? null : new int[] { 0, last };
        }
        int contentCharUnits = 0;
        if (columns != null)
        {
            Columns lookup = set != null ? set : (doc == null ? null : doc.getColumns());
            for (int column = columns[0]; column <= columns[1]; column++)
            {
                contentCharUnits += columnWidthCharUnits(doc, lookup, column);
            }
        }
        double contentWidthMm = contentCharUnits / (double)CHAR_UNITS_PER_CHARACTER * charWidthMm;

        boolean fitToPage = settings != null && settings.isFitToPage();
        String verdict;
        Double requiredScalePercent = null;
        if (widest == null)
        {
            verdict = "empty"; //$NON-NLS-1$
        }
        else if (fitToPage)
        {
            requiredScalePercent =
                Math.min(100.0, printableWidthMm / contentWidthMm * 100.0);
            verdict = requiredScalePercent < threshold ? "smallPrint" : "fits"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        else if (contentWidthMm <= printableWidthMm)
        {
            verdict = "fits"; //$NON-NLS-1$
        }
        else if (contentWidthMm <= printableWidthMm * BORDERLINE_RATIO)
        {
            verdict = "borderline"; //$NON-NLS-1$
        }
        else
        {
            verdict = "overflows"; //$NON-NLS-1$
        }

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("verdict", verdict); //$NON-NLS-1$
        answer.put("contentWidthMm", round(contentWidthMm, 2)); //$NON-NLS-1$
        answer.put("contentWidthCharUnits", contentCharUnits); //$NON-NLS-1$
        answer.put("printableWidthMm", round(printableWidthMm, 2)); //$NON-NLS-1$
        answer.put("marginMm", round(printableWidthMm - contentWidthMm, 2)); //$NON-NLS-1$
        answer.put("overflowMm", round(contentWidthMm - printableWidthMm, 2)); //$NON-NLS-1$
        answer.put("orientation", landscape ? "landscape" : "portrait"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        answer.put("paper", paper); //$NON-NLS-1$
        answer.put("printScalePercent", printScalePercent); //$NON-NLS-1$
        answer.put("charWidthMm", round(charWidthMm, 4)); //$NON-NLS-1$
        answer.put("charWidthSource", source); //$NON-NLS-1$
        answer.put("assumed", assumed); //$NON-NLS-1$
        if (requiredScalePercent != null)
        {
            answer.put("requiredScalePercent", (int)Math.round(requiredScalePercent)); //$NON-NLS-1$
            answer.put("fontSizeAfterScale", //$NON-NLS-1$
                round(requiredScalePercent / 100.0 * FONT_SIZE_PT, 1));
        }
        return answer;
    }

    /**
     * The column span of the print area, when it names one.
     *
     * @param doc the document.
     * @return the first and last column, 0-based inclusive, or {@code null} when the area names no
     *         columns - a rows-only area covers every column, and the caller falls back to the
     *         widest row for both.
     */
    private static int[] printAreaColumns(SpreadsheetDocument doc)
    {
        Area area = doc == null ? null : doc.getPrintArea();
        if (area instanceof ColumnsArea)
        {
            ColumnsArea columns = (ColumnsArea)area;
            // The normal accessors order the pair, so an area written right to left is read the same
            // way as one written left to right.
            int begin = columns.normalBegin();
            int end = columns.normalEnd();
            return begin >= 0 && end >= begin ? new int[] { begin, end } : null;
        }
        if (area instanceof RectArea)
        {
            Rect position = ((RectArea)area).getPosition();
            if (position != null)
            {
                int x = position.normalX();
                int width = position.normalWidth();
                if (x >= 0 && width > 0)
                {
                    return new int[] { x, x + width - 1 };
                }
            }
        }
        return null;
    }

    private static Columns printAreaColumnsSet(SpreadsheetDocument doc)
    {
        Area area = doc == null ? null : doc.getPrintArea();
        if (area instanceof ColumnsArea)
        {
            return ((ColumnsArea)area).getColumns();
        }
        if (area instanceof RectArea)
        {
            return ((RectArea)area).getColumns();
        }
        return null;
    }

    /**
     * The row covering the most columns, which is the span a template prints when no print area
     * says otherwise.
     *
     * @param doc the document.
     * @return the widest row, or {@code null} when the document has no cells at all.
     */
    private static Row widestRow(SpreadsheetDocument doc)
    {
        EMap<Integer, Row> rows = doc == null ? null : doc.getRows();
        if (rows == null)
        {
            return null;
        }
        Row widest = null;
        int widestLast = -1;
        for (Row row : rows.values())
        {
            int last = lastColumn(row);
            if (last > widestLast)
            {
                widestLast = last;
                widest = row;
            }
        }
        return widestLast < 0 ? null : widest;
    }

    /** @return the 0-based index of a row's rightmost cell, or -1 for a row with none. */
    private static int lastColumn(Row row)
    {
        EMap<Integer, Cell> cells = row == null ? null : row.getCells();
        if (cells == null)
        {
            return -1;
        }
        int last = -1;
        for (Integer column : cells.keySet())
        {
            if (column != null && column.intValue() > last)
            {
                last = column.intValue();
            }
        }
        return last;
    }

    /**
     * The width a format carries, or nothing when it carries none.
     *
     * @param doc the document holding the format table.
     * @param index the format index, which may point past the table - the model does not guarantee
     *            it stays in range when formats are dropped.
     * @return the width in eighths of a character, or {@code null} when the format is absent or
     *         names no usable width
     */
    private static Integer widthOfFormat(SpreadsheetDocument doc, int index)
    {
        if (doc == null || index < 0 || index >= doc.getFormats().size())
        {
            return null;
        }
        Format format = doc.getFormats().get(index);
        if (format == null || !format.isSetWidth() || format.getWidth() <= 0)
        {
            return null;
        }
        return Integer.valueOf(format.getWidth());
    }

    /**
     * Whether the sheet is landscape.
     *
     * @param settings the print settings, or {@code null}.
     * @param assumed collects the parameters taken by default.
     * @return true for landscape.
     */
    private static boolean landscapeOf(PrintSettings settings, List<String> assumed)
    {
        if (settings == null || !settings.isSetPageOrientation())
        {
            assumed.add("orientation=portrait"); //$NON-NLS-1$
            return false;
        }
        return settings.getPageOrientation() == PageOrientation.LANDSCAPE;
    }

    /**
     * The paper the sheet is, by name.
     *
     * @param settings the print settings, or {@code null}.
     * @param assumed collects the parameters taken by default.
     * @return the paper's name.
     */
    private static String paperOf(PrintSettings settings, List<String> assumed)
    {
        if (settings == null || !settings.isSetPaper())
        {
            assumed.add("paper=A4"); //$NON-NLS-1$
            return "A4"; //$NON-NLS-1$
        }
        int code = settings.getPaper();
        if (code == A4_PAPER_CODE)
        {
            return "A4"; //$NON-NLS-1$
        }
        // Only A4 has dimensions here. A template on another paper is measured as though it were on
        // A4 and told so, which is a warning a reader can act on - a guessed size would not be.
        assumed.add("paper=" + code + " measured as A4"); //$NON-NLS-1$ //$NON-NLS-2$
        return "code " + code; //$NON-NLS-1$
    }

    /**
     * One page margin.
     *
     * @param settings the print settings, or {@code null}.
     * @param left true for the left margin, false for the right.
     * @param assumed collects the parameters taken by default.
     * @return the margin in hundredths of a millimetre.
     */
    private static int marginOf(PrintSettings settings, boolean left, List<String> assumed)
    {
        boolean set = settings != null
            && (left ? settings.isSetLeftMargin() : settings.isSetRightMargin());
        if (!set)
        {
            assumed.add((left ? "leftMargin=" : "rightMargin=") //$NON-NLS-1$ //$NON-NLS-2$
                + DEFAULT_MARGIN_HUNDREDTHS_MM / 100 + "mm"); //$NON-NLS-1$
            return DEFAULT_MARGIN_HUNDREDTHS_MM;
        }
        return left ? settings.getLeftMargin() : settings.getRightMargin();
    }

    private static double round(double value, int digits)
    {
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }
}
