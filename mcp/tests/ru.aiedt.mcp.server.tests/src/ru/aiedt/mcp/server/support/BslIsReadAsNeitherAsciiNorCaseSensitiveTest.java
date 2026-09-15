/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.ops.DetectQueryAntiPatternsTool;
import ru.aiedt.mcp.server.toolkit.ops.FindRlsViolationsTool;

/**
 * Holds every regular expression that reads 1C source to the two things that are true about 1C
 * source: it is written in Cyrillic, and it does not distinguish case.
 * <p>
 * Java's defaults say otherwise. {@code \w}, {@code \b} and their negations match ASCII only unless
 * {@code UNICODE_CHARACTER_CLASS} is set, and {@code CASE_INSENSITIVE} folds ASCII only unless
 * {@code UNICODE_CASE} joins it. A scan built on those defaults does not fail - it answers nothing
 * and calls it clean, which is the worst shape an answer can take.
 * </p>
 * <p>
 * The patterns are read by reflection on purpose. Asserting the flags would test a declaration;
 * these tests feed each pattern the input it exists to recognise, so removing a flag turns them red
 * for the reason a caller would notice.
 * </p>
 */
public class BslIsReadAsNeitherAsciiNorCaseSensitiveTest
{
    private static Pattern patternOf(Class<?> owner, String field) throws Exception
    {
        Field declared = owner.getDeclaredField(field);
        declared.setAccessible(true);
        return (Pattern)declared.get(null);
    }

    private static Set<String> rulesFor(String query)
    {
        return QueryAntiPatternRules.analyze(query, null).stream()
            .map(issue -> issue.rule)
            .collect(Collectors.toSet());
    }

    /**
     * A query whose filter is written in lower-case Russian has a filter.
     * <p>
     * Measured: the upper-case ГДЕ matched even with ASCII folding, so the defect was narrower than
     * it first looked - only a filter written in lower case went unseen, and that query was then
     * reported as a query without a filter. The second assertion is the load-bearing one.
     * </p>
     */
    @Test
    public void aFilterWrittenInRussianIsSeenWhateverTheCase()
    {
        assertFalse("ГДЕ in upper case is a filter", //$NON-NLS-1$
            rulesFor("ВЫБРАТЬ * ИЗ Справочник.Товары ГДЕ Код > 5").contains("NO_WHERE_ON_LARGE_TABLE")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("где in lower case is the same filter", //$NON-NLS-1$
            rulesFor("выбрать * из Справочник.Товары где Код > 5").contains("NO_WHERE_ON_LARGE_TABLE")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A query that really has no filter is still reported. */
    @Test
    public void aQueryWithoutAFilterIsStillReported()
    {
        assertTrue("a select over a table with no filter is the finding this rule exists for", //$NON-NLS-1$
            rulesFor("ВЫБРАТЬ * ИЗ Справочник.Товары").contains("NO_WHERE_ON_LARGE_TABLE")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The keyword that starts a select is a keyword in either case. */
    @Test
    public void selectStarIsSeenInLowerCase()
    {
        assertTrue("выбрать * is the same asterisk as ВЫБРАТЬ *", //$NON-NLS-1$
            rulesFor("выбрать * из Справочник.Товары").contains("SELECT_STAR")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A log call is a log call however the caller capitalised it. */
    @Test
    public void theLogRecordCallIsSeenWhateverTheCase()
    {
        assertTrue("as written in the syntax reference", //$NON-NLS-1$
            SensitivePatternLibrary.LOG_RECORD
                .matcher("ЗаписьЖурналаРегистрации(\"Событие\", УровеньЖурналаРегистрации.Информация)") //$NON-NLS-1$
                .find());
        assertTrue("as written by a developer in a hurry", //$NON-NLS-1$
            SensitivePatternLibrary.LOG_RECORD
                .matcher("записьжурналарегистрации(\"Событие\")") //$NON-NLS-1$
                .find());
    }

    /**
     * A method named in Cyrillic is a method.
     * <p>
     * The keyword matched and the name did not, so a module written in Russian counted as zero
     * methods and every metric built on that count was a floor of zero.
     * </p>
     */
    @Test
    public void aMethodNamedInCyrillicIsCounted() throws Exception
    {
        Pattern methodStart = patternOf(ProjectMetricsCollector.class, "PROC_PATTERN"); //$NON-NLS-1$
        assertTrue("Процедура ПроверитьЗаполнение()", //$NON-NLS-1$
            methodStart.matcher("Процедура ПроверитьЗаполнение(Отказ)").find()); //$NON-NLS-1$
        assertTrue("процедура in lower case declares the same method", //$NON-NLS-1$
            methodStart.matcher("процедура ПроверитьЗаполнение(Отказ)").find()); //$NON-NLS-1$
        assertTrue("Функция ВернутьЗначение()", //$NON-NLS-1$
            methodStart.matcher("Функция ВернутьЗначение()").find()); //$NON-NLS-1$
    }

    /** Branch keywords decide the complexity count, and they too are case-blind. */
    @Test
    public void branchKeywordsAreSeenInLowerCase() throws Exception
    {
        Pattern branch = patternOf(ProjectMetricsCollector.class, "BRANCH_PATTERN"); //$NON-NLS-1$
        assertTrue("Если as written in most code", branch.matcher("Если Условие Тогда").find()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("если is the same branch", branch.matcher("если Условие тогда").find()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Цикл counts as well", branch.matcher("Для Каждого Строка Из Таблица Цикл").find()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The registration marker of a test suite is Cyrillic, and it is matched case-blind. */
    @Test
    public void theTestSuiteMarkerIsSeenInLowerCase() throws Exception
    {
        Pattern marker = patternOf(ProjectMetricsCollector.class, "YAXUNIT_PATTERN"); //$NON-NLS-1$
        assertTrue("РегистрацияТестов", marker.matcher("Процедура РегистрацияТестов(Набор)").find()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("регистрациятестов is the same marker", //$NON-NLS-1$
            marker.matcher("процедура регистрациятестов(Набор)").find()); //$NON-NLS-1$
    }

    /**
     * The variable a query text is assigned to is usually named in Cyrillic.
     * <p>
     * The name here deliberately does not contain the word {@code Запрос}: a name that does matches
     * the pattern's second alternative as a substring, and the test would pass with the word class
     * still ASCII - proving nothing. Caught by returning the defect.
     * </p>
     */
    @Test
    public void theQueryVariableMayBeNamedInCyrillic() throws Exception
    {
        Pattern assignment = patternOf(DetectQueryAntiPatternsTool.class, "QUERY_TEXT_ASSIGN"); //$NON-NLS-1$
        assertTrue("ВыборкаТоваров.Текст = \"...\"", //$NON-NLS-1$
            assignment.matcher("ВыборкаТоваров.Текст = \"ВЫБРАТЬ * ИЗ Справочник.Товары\"").find()); //$NON-NLS-1$
        assertTrue("an ASCII name still works", //$NON-NLS-1$
            assignment.matcher("Query.Text = \"SELECT * FROM Catalog.Goods\"").find()); //$NON-NLS-1$
    }

    /**
     * The boundaries of a method decide which findings belong to which method.
     * <p>
     * Without them the scan attributes a finding to whatever method it last saw, and with an
     * ASCII-only name class it saw none at all in Russian code.
     * </p>
     */
    @Test
    public void theMethodBoundariesAreSeenInRussianCode() throws Exception
    {
        Pattern start = patternOf(FindRlsViolationsTool.class, "PROC_BOUNDARY"); //$NON-NLS-1$
        Pattern end = patternOf(FindRlsViolationsTool.class, "PROC_END"); //$NON-NLS-1$
        assertTrue("Процедура ОбработатьДанные()", //$NON-NLS-1$
            start.matcher("Процедура ОбработатьДанные(Параметр)").find()); //$NON-NLS-1$
        assertTrue("процедура in lower case opens the same method", //$NON-NLS-1$
            start.matcher("процедура ОбработатьДанные(Параметр)").find()); //$NON-NLS-1$
        assertTrue("КонецПроцедуры", end.matcher("КонецПроцедуры").find()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("конецпроцедуры closes the same method", //$NON-NLS-1$
            end.matcher("конецпроцедуры").find()); //$NON-NLS-1$
    }

    /** Privileged mode is what this scan looks for, and it is written both ways in real code. */
    @Test
    public void privilegedModeIsSeenWhateverTheCase() throws Exception
    {
        Pattern set = patternOf(FindRlsViolationsTool.class, "PRIVILEGED_MODE_SET"); //$NON-NLS-1$
        Pattern reset = patternOf(FindRlsViolationsTool.class, "PRIVILEGED_MODE_RESET"); //$NON-NLS-1$
        assertTrue("as the syntax reference writes it", //$NON-NLS-1$
            set.matcher("УстановитьПривилегированныйРежим(Истина);").find()); //$NON-NLS-1$
        assertTrue("as a developer typed it", //$NON-NLS-1$
            set.matcher("установитьпривилегированныйрежим(истина);").find()); //$NON-NLS-1$
        assertTrue("and the same for the reset", //$NON-NLS-1$
            reset.matcher("установитьпривилегированныйрежим(ложь);").find()); //$NON-NLS-1$
    }
}
