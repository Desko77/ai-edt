/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Covers reading a BSL method's parameters, from the signature and from the doc comment above it.
 * <p>
 * The signature reader has to survive what real code looks like: a declaration wrapped over several
 * lines, {@code Знач} in front of a name, and default values that contain their own brackets and
 * commas - each of which breaks a naive split. The doc reader exists because EDT's own parser
 * collapses a space-aligned {@code Параметры:} block into its first entry, losing every parameter
 * after it; anchoring on the names taken from the signature is what keeps them.
 * </p>
 */
public class BslCommentParseHelperTest
{
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parametersOf(Map<String, Object> doc)
    {
        return (List<Map<String, Object>>)doc.get("parameters"); //$NON-NLS-1$
    }

    // -- signature --

    @Test
    public void parameterNamesComeOffTheDeclaration()
    {
        List<String> module = Arrays.asList(
            "Процедура Записать(Ссылка, Отказ)"); //$NON-NLS-1$

        assertEquals(Arrays.asList("Ссылка", "Отказ"), //$NON-NLS-1$ //$NON-NLS-2$
            BslCommentParseHelper.extractParamNames(module, 1));
    }

    @Test
    public void theByValueKeywordIsNotPartOfTheName()
    {
        List<String> module = Arrays.asList(
            "Функция Посчитать(Знач Количество, Val Price) Экспорт"); //$NON-NLS-1$

        assertEquals(Arrays.asList("Количество", "Price"), //$NON-NLS-1$ //$NON-NLS-2$
            BslCommentParseHelper.extractParamNames(module, 1));
    }

    @Test
    public void aDefaultValueIsNotPartOfTheName()
    {
        List<String> module = Arrays.asList(
            "Процедура Обработать(Режим = \"Быстрый\", Отказ = Ложь)"); //$NON-NLS-1$

        assertEquals(Arrays.asList("Режим", "Отказ"), //$NON-NLS-1$ //$NON-NLS-2$
            BslCommentParseHelper.extractParamNames(module, 1));
    }

    @Test
    public void aDeclarationWrappedOverSeveralLinesIsReadWhole()
    {
        List<String> module = Arrays.asList(
            "Процедура Длинная(", //$NON-NLS-1$
            "        Первый,", //$NON-NLS-1$
            "        Второй,", //$NON-NLS-1$
            "        Третий)"); //$NON-NLS-1$

        assertEquals(Arrays.asList("Первый", "Второй", "Третий"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            BslCommentParseHelper.extractParamNames(module, 1));
    }

    @Test
    public void bracketsInsideADefaultDoNotEndTheSignature()
    {
        // Новый Массив() closes a paren in the middle of the list; a reader that stops at the first
        // ')' loses every parameter after it.
        List<String> module = Arrays.asList(
            "Процедура Сложная(Список = Новый Массив(), Флаг = Истина)"); //$NON-NLS-1$

        assertEquals(Arrays.asList("Список", "Флаг"), //$NON-NLS-1$ //$NON-NLS-2$
            BslCommentParseHelper.extractParamNames(module, 1));
    }

    @Test
    public void aProcedureWithoutParametersHasNone()
    {
        assertTrue(BslCommentParseHelper
            .extractParamNames(Collections.singletonList("Процедура Пустая()"), 1).isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void anImpossibleLineNumberYieldsNoNames()
    {
        List<String> module = Collections.singletonList("Процедура Записать(Ссылка)"); //$NON-NLS-1$

        assertTrue(BslCommentParseHelper.extractParamNames(module, 0).isEmpty());
        assertTrue(BslCommentParseHelper.extractParamNames(module, 99).isEmpty());
        assertTrue(BslCommentParseHelper.extractParamNames(null, 1).isEmpty());
    }

    // -- doc comment --

    @Test
    public void everyDocumentedParameterSurvivesTheSpaceAlignedBlock()
    {
        // The exact shape EDT's own parser collapses: three parameters, each introduced by its name
        // and indented under "Параметры:".
        List<String> comment = Arrays.asList(
            "// Записывает документ.", //$NON-NLS-1$
            "//", //$NON-NLS-1$
            "// Параметры:", //$NON-NLS-1$
            "//  Ссылка - ДокументСсылка - что записывать", //$NON-NLS-1$
            "//  Отказ  - Булево         - признак отказа", //$NON-NLS-1$
            "//  Режим  - Строка         - режим записи"); //$NON-NLS-1$

        Map<String, Object> doc = BslCommentParseHelper.parseDocAnchored(comment,
            Arrays.asList("Ссылка", "Отказ", "Режим")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertNotNull(doc);
        List<Map<String, Object>> parameters = parametersOf(doc);
        assertEquals("all three parameters have to survive", 3, parameters.size()); //$NON-NLS-1$
        assertEquals("Ссылка", parameters.get(0).get("name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Отказ", parameters.get(1).get("name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Режим", parameters.get(2).get("name")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theDescriptionIsWhatStandsBeforeTheParameterBlock()
    {
        List<String> comment = Arrays.asList(
            "// Записывает документ.", //$NON-NLS-1$
            "// Параметры:", //$NON-NLS-1$
            "//  Ссылка - ДокументСсылка - что записывать"); //$NON-NLS-1$

        Map<String, Object> doc = BslCommentParseHelper.parseDocAnchored(comment,
            Collections.singletonList("Ссылка")); //$NON-NLS-1$

        assertTrue(String.valueOf(doc.get("description")), //$NON-NLS-1$
            String.valueOf(doc.get("description")).contains("Записывает документ")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aDeprecationNoticeIsCarriedThrough()
    {
        List<String> comment = Arrays.asList(
            "// Устарела. Используйте ЗаписатьНовый.", //$NON-NLS-1$
            "// Параметры:", //$NON-NLS-1$
            "//  Ссылка - ДокументСсылка - что записывать"); //$NON-NLS-1$

        Map<String, Object> doc = BslCommentParseHelper.parseDocAnchored(comment,
            Collections.singletonList("Ссылка")); //$NON-NLS-1$

        assertEquals("a caller has to be told the method is on its way out", //$NON-NLS-1$
            Boolean.TRUE, doc.get("deprecated")); //$NON-NLS-1$
    }

    @Test
    public void anEnglishParameterBlockIsRecognizedToo()
    {
        List<String> comment = Arrays.asList(
            "// Writes the document.", //$NON-NLS-1$
            "// Parameters:", //$NON-NLS-1$
            "//  Ref - DocumentRef - what to write"); //$NON-NLS-1$

        assertNotNull(BslCommentParseHelper.parseDocAnchored(comment,
            Collections.singletonList("Ref"))); //$NON-NLS-1$
    }

    @Test
    public void withoutAParameterBlockTheAnchoredParserDeclines()
    {
        // Declining is deliberate: the caller then falls back to the EDT parser, so returning an
        // empty result here would drop documentation that the other parser can read.
        List<String> comment = Arrays.asList(
            "// Записывает документ.", //$NON-NLS-1$
            "// Ничего больше не сказано."); //$NON-NLS-1$

        assertNull(BslCommentParseHelper.parseDocAnchored(comment,
            Collections.singletonList("Ссылка"))); //$NON-NLS-1$
    }

    @Test
    public void withoutAnchorsTheAnchoredParserDeclines()
    {
        List<String> comment = Arrays.asList("// Параметры:", "//  Ссылка - ДокументСсылка - что"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(BslCommentParseHelper.parseDocAnchored(comment, Collections.emptyList()));
        assertNull(BslCommentParseHelper.parseDocAnchored(comment, null));
        assertNull(BslCommentParseHelper.parseDocAnchored(null, Collections.singletonList("Ссылка"))); //$NON-NLS-1$
    }
}
