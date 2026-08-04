/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Test;

import ru.aiedt.mcp.server.support.QueryAntiPatternRules.Issue;

/**
 * Covers the query anti-pattern scan.
 * <p>
 * Both directions are load-bearing. A missed pattern is a slow query that ships; a false positive on
 * a query that is fine trains the reader to skip the warnings, which costs more than the check ever
 * saved. So each rule is exercised against text that trips it and against text that must not - and
 * the assertions read the rule ids, never the wording, because the wording is prose and prose gets
 * rewritten.
 * </p>
 */
public class QueryAntiPatternRulesTest
{
    private static Set<String> rulesFor(String query)
    {
        return QueryAntiPatternRules.analyze(query, null).stream()
            .map(issue -> issue.rule)
            .collect(Collectors.toSet());
    }

    @Test
    public void nothingToAnalyzeYieldsNoIssues()
    {
        assertTrue(QueryAntiPatternRules.analyze(null, null).isEmpty());
        assertTrue(QueryAntiPatternRules.analyze("", null).isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void selectStarIsReported()
    {
        assertTrue(rulesFor("ВЫБРАТЬ * ИЗ Справочник.Товары ГДЕ Код = &Код") //$NON-NLS-1$
            .contains("SELECT_STAR")); //$NON-NLS-1$
    }

    @Test
    public void selectStarIsReportedInEnglishToo()
    {
        // The query language accepts either vocabulary; a rule that only sees one is half a rule.
        assertTrue(rulesFor("SELECT * FROM Catalog.Products WHERE Code = &Code") //$NON-NLS-1$
            .contains("SELECT_STAR")); //$NON-NLS-1$
    }

    @Test
    public void namedFieldsAreNotSelectStar()
    {
        assertFalse(rulesFor("ВЫБРАТЬ Товары.Код, Товары.Наименование ИЗ Справочник.Товары " //$NON-NLS-1$
            + "КАК Товары ГДЕ Товары.Код = &Код").contains("SELECT_STAR")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aQueryWithNoWhereClauseIsReported()
    {
        assertTrue(rulesFor("ВЫБРАТЬ Товары.Код ИЗ Справочник.Товары КАК Товары") //$NON-NLS-1$
            .contains("NO_WHERE_ON_LARGE_TABLE")); //$NON-NLS-1$
    }

    @Test
    public void aWhereClauseClearsThatRule()
    {
        assertFalse(rulesFor("ВЫБРАТЬ Товары.Код ИЗ Справочник.Товары КАК Товары ГДЕ Товары.Код = &Код") //$NON-NLS-1$
            .contains("NO_WHERE_ON_LARGE_TABLE")); //$NON-NLS-1$
    }

    @Test
    public void aVirtualTableCalledWithoutParametersIsReported()
    {
        // The classic 1C performance trap: the platform materializes the whole register.
        assertTrue(rulesFor("ВЫБРАТЬ Ост.КоличествоОстаток ИЗ РегистрНакопления.Товары.Остатки() КАК Ост") //$NON-NLS-1$
            .contains("VIRTUAL_TABLE_PARAMS")); //$NON-NLS-1$
    }

    @Test
    public void aVirtualTableWithParametersIsLeftAlone()
    {
        assertFalse(rulesFor("ВЫБРАТЬ Ост.КоличествоОстаток ИЗ РегистрНакопления.Товары.Остатки(" //$NON-NLS-1$
            + "&Дата, Склад = &Склад) КАК Ост").contains("VIRTUAL_TABLE_PARAMS")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aCrossJoinIsReported()
    {
        assertTrue(rulesFor("ВЫБРАТЬ * ИЗ Справочник.А КРОСС СОЕДИНЕНИЕ Справочник.Б") //$NON-NLS-1$
            .contains("CROSS_JOIN_NO_CONDITION")); //$NON-NLS-1$
    }

    @Test
    public void aRussianIdentifierIsScannedTheSameAsALatinOne()
    {
        // Regression guard. Java's \w is ASCII-only unless a pattern asks otherwise, so two of these
        // rules used to match nothing at all once the table had a Russian name - which is nearly
        // every real query. Both spellings have to reach the same verdict.
        assertEquals(rulesFor("SELECT Goods.Code FROM Catalog.Goods"), //$NON-NLS-1$
            rulesFor("ВЫБРАТЬ Товары.Код ИЗ Справочник.Товары")); //$NON-NLS-1$

        assertEquals(rulesFor("SELECT B.QuantityBalance FROM AccumulationRegister.Goods.Balance()"), //$NON-NLS-1$
            rulesFor("ВЫБРАТЬ Ост.КоличествоОстаток ИЗ РегистрНакопления.Товары.Остатки()")); //$NON-NLS-1$
    }

    @Test
    public void anIssueCarriesItsLineSoTheReaderCanFindIt()
    {
        String query = "ВЫБРАТЬ\n" //$NON-NLS-1$
            + "    *\n" //$NON-NLS-1$
            + "ИЗ\n" //$NON-NLS-1$
            + "    Справочник.Товары"; //$NON-NLS-1$

        List<Issue> issues = QueryAntiPatternRules.analyze(query, null);

        Issue selectStar = issues.stream().filter(i -> "SELECT_STAR".equals(i.rule)) //$NON-NLS-1$
            .findFirst().orElseThrow();
        assertEquals("the pattern starts on the first line", 1, selectStar.lineInQuery); //$NON-NLS-1$
    }

    @Test
    public void anIssueSerializesEveryFieldTheToolReports()
    {
        Issue issue = QueryAntiPatternRules.analyze("ВЫБРАТЬ * ИЗ Справочник.Товары", null).get(0); //$NON-NLS-1$

        Map<String, Object> map = issue.toMap();

        assertEquals(issue.rule, map.get("rule")); //$NON-NLS-1$
        assertEquals(issue.severity.name(), map.get("severity")); //$NON-NLS-1$
        assertEquals(issue.message, map.get("message")); //$NON-NLS-1$
        assertEquals(issue.lineInQuery, map.get("lineInQuery")); //$NON-NLS-1$
    }

    @Test
    public void anExplicitRuleSetRunsOnlyThoseRules()
    {
        String query = "ВЫБРАТЬ * ИЗ Справочник.Товары"; // trips SELECT_STAR and NO_WHERE //$NON-NLS-1$

        Set<String> only = QueryAntiPatternRules
            .analyze(query, new HashSet<>(Collections.singletonList("SELECT_STAR"))).stream() //$NON-NLS-1$
            .map(issue -> issue.rule).collect(Collectors.toSet());

        assertEquals(Collections.singleton("SELECT_STAR"), only); //$NON-NLS-1$
    }

    @Test
    public void anEmptyRuleSetMeansEveryRule()
    {
        // Distinguishing "no filter" from "filter nothing in" would silently disable the scan for
        // any caller that passes an empty collection.
        String query = "ВЫБРАТЬ * ИЗ Справочник.Товары"; //$NON-NLS-1$

        assertEquals(QueryAntiPatternRules.analyze(query, null).size(),
            QueryAntiPatternRules.analyze(query, Collections.emptySet()).size());
    }
}
