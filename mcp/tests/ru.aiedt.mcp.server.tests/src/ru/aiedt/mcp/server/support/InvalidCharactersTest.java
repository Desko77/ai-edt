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

import org.junit.Test;

/**
 * The characters that arrive inside written text and cannot stand in BSL source are replaced on the
 * way in, and the answer says how many and where.
 * <p>
 * Every character below is written as an escape rather than as itself, so that this file holds none
 * of them: the class under test exists because they are invisible in an editor and a file that
 * carries them while testing them would be the defect it describes.
 * </p>
 */
public class InvalidCharactersTest
{
    private static final char FIGURE_DASH = '\u2012';
    private static final char EN_DASH = '\u2013';
    private static final char EM_DASH = '\u2014';
    private static final char HORIZONTAL_BAR = '\u2015';
    private static final char MINUS_SIGN = '\u2212';
    private static final char NO_BREAK_SPACE = '\u00A0';
    private static final char SOFT_HYPHEN = '\u00AD';

    /** Text with none of these characters comes back as it was, and the report says so. */
    @Test
    public void textThatHoldsNoneOfThemIsUntouched()
    {
        String source = "Процедура Тест()\n\tСообщить(\"ОК\" +\n\t\t\"!\");\nКонецПроцедуры"; //$NON-NLS-1$
        InvalidCharacters.Report report = InvalidCharacters.normalize(source);

        assertEquals(source, report.text);
        assertFalse(report.changed());
        assertEquals(0, report.count);
        assertTrue(report.positions.isEmpty());
        assertEquals("", report.describe()); //$NON-NLS-1$
        assertEquals("", report.positionsAsText()); //$NON-NLS-1$
    }

    /** Every dash a person or a model types in place of a hyphen becomes a hyphen. */
    @Test
    public void everyDashBecomesAHyphen()
    {
        String source = "a" + FIGURE_DASH + "b" + EN_DASH + "c" + EM_DASH + "d" + HORIZONTAL_BAR
            + "e" + MINUS_SIGN + "f"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$
        InvalidCharacters.Report report = InvalidCharacters.normalize(source);

        assertEquals("a-b-c-d-e-f", report.text); //$NON-NLS-1$
        assertEquals(5, report.count);
        assertEquals("every kind of the family is named, and each was seen once", 5, report.kinds.size()); //$NON-NLS-1$
        assertEquals(Integer.valueOf(1), report.kinds.get("figure dash (U+2012) -> '-'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Integer.valueOf(1), report.kinds.get("horizontal bar (U+2015) -> '-'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Integer.valueOf(1), report.kinds.get("em dash (U+2014) -> '-'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Integer.valueOf(1), report.kinds.get("minus sign (U+2212) -> '-'")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A non-breaking space becomes a plain space and a soft hyphen is dropped. */
    @Test
    public void theSpaceIsReplacedAndTheSoftHyphenIsDropped()
    {
        InvalidCharacters.Report space = InvalidCharacters.normalize("a" + NO_BREAK_SPACE + "b"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a b", space.text); //$NON-NLS-1$
        assertEquals(1, space.count);
        assertEquals("no-break space (U+00A0) -> ' '", space.kinds.keySet().iterator().next()); //$NON-NLS-1$

        InvalidCharacters.Report hyphen = InvalidCharacters.normalize("a" + SOFT_HYPHEN + "b"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the character takes no room, so it leaves none behind", "ab", hyphen.text); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, hyphen.count);
        assertEquals("soft hyphen (U+00AD) -> removed", hyphen.kinds.keySet().iterator().next()); //$NON-NLS-1$
    }

    /** A position is where the character stood, line and column counted from one. */
    @Test
    public void thePositionsAreLinesAndColumns()
    {
        String source = "первая\nвто" + EM_DASH + "рая\nтре" + EN_DASH + "тья"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        InvalidCharacters.Report report = InvalidCharacters.normalize(source);

        assertEquals(2, report.count);
        assertEquals("2:4", report.positions.get(0)); //$NON-NLS-1$
        assertEquals("3:4", report.positions.get(1)); //$NON-NLS-1$
        assertEquals("2:4, 3:4", report.positionsAsText()); //$NON-NLS-1$
    }

    /** A dropped character does not move the column of what follows it. */
    @Test
    public void aDroppedCharacterLeavesTheColumnWhereItWas()
    {
        String source = "a" + SOFT_HYPHEN + "b" + EM_DASH; //$NON-NLS-1$ //$NON-NLS-2$
        InvalidCharacters.Report report = InvalidCharacters.normalize(source);

        assertEquals("ab-", report.text); //$NON-NLS-1$
        assertEquals("1:2", report.positions.get(0)); //$NON-NLS-1$
        assertEquals("the hyphen stands where the b stood", "1:3", report.positions.get(1)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A long text reports its first fifty places and says that it stopped. */
    @Test
    public void thePositionListStopsAtFiftyAndSaysSo()
    {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 60; i++)
        {
            source.append(EM_DASH);
        }
        InvalidCharacters.Report report = InvalidCharacters.normalize(source.toString());

        assertEquals(60, report.count);
        assertEquals(50, report.positions.size());
        assertTrue(report.positionsTruncated);
        assertTrue("the list says it is a part of the whole", //$NON-NLS-1$
            report.positionsAsText().contains("(first 50)")); //$NON-NLS-1$
    }

    /** The one line a tool prints names the count and every kind that was found. */
    @Test
    public void theSummaryNamesTheCountAndTheKinds()
    {
        InvalidCharacters.Report report =
            InvalidCharacters.normalize("a" + EM_DASH + "b" + EM_DASH + "c" + NO_BREAK_SPACE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        String summary = report.describe();
        assertTrue(summary, summary.startsWith("3 characters were replaced")); //$NON-NLS-1$
        assertTrue(summary, summary.contains("em dash (U+2014) -> '-' x2")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(summary, summary.contains("no-break space (U+00A0) -> ' ' x1")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("1 character was", InvalidCharacters.normalize("a" + EM_DASH) //$NON-NLS-1$ //$NON-NLS-2$
            .describe().split(" replaced")[0]); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Nothing to normalize is not a failure. */
    @Test
    public void noTextIsNotAFailure()
    {
        assertNull(InvalidCharacters.normalize(null).text);
        assertEquals(0, InvalidCharacters.normalize(null).count);
        assertEquals("", InvalidCharacters.normalize("").text); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---------- string literals are data ----------

    /**
     * A character inside a string literal is data, and data is not rewritten.
     * <p>
     * A no-break space inside a format string is the group separator the code means; a dash inside a
     * message is the message's own punctuation. Replacing either changes what the code does while
     * the module still compiles.
     * </p>
     */
    @Test
    public void aCharacterInsideAStringLiteralStandsAsWritten()
    {
        String source = "Формат(Значение, \"ЧРГ='" + NO_BREAK_SPACE + "'; ЧДЦ=2\");"; //$NON-NLS-1$ //$NON-NLS-2$

        InvalidCharacters.Report report = InvalidCharacters.normalize(source);

        assertEquals("nothing outside the literal was touched", source, report.text); //$NON-NLS-1$
        assertFalse(report.changed());
    }

    /** A literal may run over lines that continue with {@code |}; its characters stand as written. */
    @Test
    public void aMultilineLiteralKeepsItsCharacters()
    {
        String source = "Текст = \"первая строка\n" //$NON-NLS-1$
            + "|вторая " + EM_DASH + " строка\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "|третья \" + Дефис;\nДефис = " + EN_DASH + ";"; //$NON-NLS-1$ //$NON-NLS-2$

        InvalidCharacters.Report report = InvalidCharacters.normalize(source);

        assertEquals("the literal keeps its dash, the code loses its own", //$NON-NLS-1$
            "Текст = \"первая строка\n" //$NON-NLS-1$
                + "|вторая " + EM_DASH + " строка\n" //$NON-NLS-1$ //$NON-NLS-2$
                + "|третья \" + Дефис;\nДефис = -;", //$NON-NLS-1$
            report.text);
        assertEquals(1, report.count);
        assertEquals("4:9", report.positions.get(0)); //$NON-NLS-1$
    }

    /** An embedded {@code ""} is an escaped quote: it does not close the literal. */
    @Test
    public void anEscapedQuoteDoesNotEndTheLiteral()
    {
        String source = "Сообщить(\"слово \"\"в кавычках\" + \" дальше " + EM_DASH + " конец\");"
            + " x = " + EN_DASH + ";"; //$NON-NLS-1$ //$NON-NLS-2$

        InvalidCharacters.Report report = InvalidCharacters.normalize(source);

        assertEquals("only the dash after the closing quote counts", 1, report.count); //$NON-NLS-1$
        assertTrue(report.text,
            report.text.contains("слово \"\"в кавычках\" + \" дальше " + EM_DASH + " конец\")")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(report.text, report.text.endsWith("x = -;")); //$NON-NLS-1$
    }

    /** A comment is written by a person and is rewritten like code. */
    @Test
    public void aCommentIsRewrittenLikeCode()
    {
        String source = "А = 1; // примечание " + EM_DASH + " продолжение"; //$NON-NLS-1$ //$NON-NLS-2$

        InvalidCharacters.Report report = InvalidCharacters.normalize(source);

        assertEquals("А = 1; // примечание - продолжение", report.text); //$NON-NLS-1$
        assertEquals(1, report.count);
    }

    /** A quote inside a comment opens no literal, and {@code //} inside a literal starts no comment. */
    @Test
    public void aQuoteInACommentAndSlashesInALiteralMeanNothing()
    {
        String source = "// кавычка \" тут" + EM_DASH + "\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "Стр = \"слэши // внутри" + NO_BREAK_SPACE + "литерала\";"; //$NON-NLS-1$ //$NON-NLS-2$

        InvalidCharacters.Report report = InvalidCharacters.normalize(source);

        assertTrue("the dash in the comment is replaced", report.text.contains("тут-")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the no-break space inside the literal stands", //$NON-NLS-1$
            report.text.contains("слэши // внутри" + NO_BREAK_SPACE + "литерала")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, report.count);
    }

    // ---------- a line is read by its own first character ----------

    /**
     * A line that begins with {@code |} is a continuation of the literal above it, and its
     * characters are data - even where the text handed to the pass begins on that line and holds no
     * opening quote at all.
     */
    @Test
    public void aContinuationLineIsDataEvenWhereNoQuoteOpensItInTheText()
    {
        String source = "|вторая " + EM_DASH + " строка"; //$NON-NLS-1$ //$NON-NLS-2$

        InvalidCharacters.Report report = InvalidCharacters.normalize(source);

        assertEquals(source, report.text);
        assertFalse(report.changed());
    }

    /** A literal does not cross a newline by itself: a line of code below it ends it there. */
    @Test
    public void aLineOfCodeEndsAnUnclosedLiteral()
    {
        String source = "Стр = \"первая\nвто" + EM_DASH + "рая"; //$NON-NLS-1$ //$NON-NLS-2$

        InvalidCharacters.Report report = InvalidCharacters.normalize(source);

        assertEquals("Стр = \"первая\nвто-рая", report.text); //$NON-NLS-1$
        assertEquals(1, report.count);
    }

    /**
     * A comment line between the lines of a literal is a comment: it is rewritten like code, its
     * quote opens nothing, and the literal goes on below it.
     */
    @Test
    public void aCommentLineBetweenTheLinesOfALiteralLeavesTheLiteralOpen()
    {
        String source = "Стр = \"первая\n" //$NON-NLS-1$
            + "//| запрос \" тут" + EM_DASH + "\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "|вторая" + NO_BREAK_SPACE + "строка\";"; //$NON-NLS-1$ //$NON-NLS-2$

        InvalidCharacters.Report report = InvalidCharacters.normalize(source);

        assertTrue("the dash of the comment is replaced", report.text.contains("тут-")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the literal below is untouched", //$NON-NLS-1$
            report.text.contains("|вторая" + NO_BREAK_SPACE + "строка\";")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, report.count);
    }

    // ---------- a fragment is read where it lands ----------

    /** The state of a module at the end of a piece of its text: in code, inside a literal or a comment. */
    @Test
    public void theStateSaysWhereAModuleStands()
    {
        InvalidCharacters.State inCode = InvalidCharacters.stateOf("А = 1;\n"); //$NON-NLS-1$
        assertFalse(inCode.literal);
        assertFalse(inCode.comment);
        assertTrue("a line ends, so the text stands at the start of one", inCode.lineStart); //$NON-NLS-1$

        InvalidCharacters.State inLiteral = InvalidCharacters.stateOf("Стр = \"первая "); //$NON-NLS-1$
        assertTrue("the quote opened a literal that nothing closed", inLiteral.literal); //$NON-NLS-1$
        assertFalse(inLiteral.lineStart);

        InvalidCharacters.State inComment = InvalidCharacters.stateOf("А = 1; // примечание"); //$NON-NLS-1$
        assertTrue(inComment.comment);
        assertFalse(inComment.literal);

        assertTrue("no text is the start of a text", InvalidCharacters.stateOf(null).lineStart); //$NON-NLS-1$
    }

    /**
     * A fragment that lands inside a literal keeps the characters of that literal, while the same
     * text read from the start of a text is code and loses them.
     */
    @Test
    public void aFragmentIsReadWhereItLands()
    {
        String fragment = "ЧРГ='" + NO_BREAK_SPACE + "'"; //$NON-NLS-1$ //$NON-NLS-2$

        InvalidCharacters.Report asCode = InvalidCharacters.normalize(fragment);
        assertEquals("read as a text of its own, the no-break space is code", 1, asCode.count); //$NON-NLS-1$
        assertEquals("ЧРГ=' '", asCode.text); //$NON-NLS-1$

        InvalidCharacters.State inLiteral = InvalidCharacters.stateOf("Стр = \"первая "); //$NON-NLS-1$
        InvalidCharacters.Report inTheModule = InvalidCharacters.normalize(fragment, inLiteral);
        assertEquals("read inside the literal it lands in, it is data", //$NON-NLS-1$
            fragment, inTheModule.text);
        assertFalse(inTheModule.changed());

        InvalidCharacters.Report closed = InvalidCharacters.normalize("\"; код дальше", inLiteral); //$NON-NLS-1$
        assertFalse("the fragment closed the literal its text landed in", closed.endState.literal); //$NON-NLS-1$
        InvalidCharacters.Report open = InvalidCharacters.normalize("новая строка", inLiteral); //$NON-NLS-1$
        assertTrue("a fragment without a quote leaves the literal as it was", open.endState.literal); //$NON-NLS-1$
    }

    /** Several pieces written in one call answer with one count. */
    @Test
    public void thePiecesOfOneWriteAreCountedTogether()
    {
        InvalidCharacters.Report whole =
            InvalidCharacters.normalize("a" + EM_DASH + "b"); //$NON-NLS-1$ //$NON-NLS-2$
        InvalidCharacters.Report total = new InvalidCharacters.Report();
        total.merge(null, whole);
        total.merge("Метод1", InvalidCharacters.normalize("c" + EN_DASH + "d" + NO_BREAK_SPACE)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        total.merge("Метод2", InvalidCharacters.normalize("clean")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(3, total.count);
        assertEquals("1:2", total.positions.get(0)); //$NON-NLS-1$
        assertEquals("a position is named by the piece it was measured in", //$NON-NLS-1$
            "Метод1 1:2", total.positions.get(1)); //$NON-NLS-1$
        assertEquals("a replaced dash takes one column, so the space behind it is the fourth", //$NON-NLS-1$
            "Метод1 1:4", total.positions.get(2)); //$NON-NLS-1$
        assertEquals(Integer.valueOf(1), total.kinds.get("en dash (U+2013) -> '-'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the piece without a change adds nothing", 3, total.positions.size()); //$NON-NLS-1$
    }

    /** Folding in a pass that changed nothing leaves the count where it was. */
    @Test
    public void foldingInNothingChangesNothing()
    {
        InvalidCharacters.Report total = new InvalidCharacters.Report();
        total.merge(null, null);
        total.merge(null, InvalidCharacters.normalize("clean")); //$NON-NLS-1$

        assertEquals(0, total.count);
        assertFalse(total.changed());
    }
}
