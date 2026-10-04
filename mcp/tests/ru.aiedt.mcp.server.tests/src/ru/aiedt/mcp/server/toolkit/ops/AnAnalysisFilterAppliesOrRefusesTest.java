/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.Test;

import ru.aiedt.mcp.server.support.QueryAntiPatternRules;

/**
 * An analysis filter the caller names is either applied or refused.
 * <p>
 * A rule list in another case enabled no rule and a metadata family outside the list restricted
 * nothing, and both answered as though the filter had been applied. The nesting rule counted every
 * subquery of the text and reported the count as a depth.
 * </p>
 */
public class AnAnalysisFilterAppliesOrRefusesTest
{
    @Test
    public void aRuleNameInAnyCaseEnablesTheRule()
    {
        Set<String> rules = DetectQueryAntiPatternsTool.parseRules(" nested_query_depth , Select_Star "); //$NON-NLS-1$

        assertEquals(Set.of("NESTED_QUERY_DEPTH", "SELECT_STAR"), rules); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(DetectQueryAntiPatternsTool.unknownRule(rules));
    }

    @Test
    public void anUnknownRuleNameIsNamedForTheRefusal()
    {
        assertEquals("NOPE", //$NON-NLS-1$
            DetectQueryAntiPatternsTool.unknownRule(DetectQueryAntiPatternsTool.parseRules("select_star,nope"))); //$NON-NLS-1$
    }

    @Test
    public void aMetadataFamilyIsReadInItsSeveralSpellings()
    {
        assertEquals("Catalogs", FindDeadCodeTool.familyOf("Catalog")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Catalogs", FindDeadCodeTool.familyOf("catalogs")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Catalogs", FindDeadCodeTool.familyOf("Справочники")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("CommonModules", FindDeadCodeTool.familyOf("commonModule")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(FindDeadCodeTool.ALL_FAMILIES, FindDeadCodeTool.familyOf(null));
        assertEquals(FindDeadCodeTool.ALL_FAMILIES, FindDeadCodeTool.familyOf("ALL")); //$NON-NLS-1$
    }

    @Test
    public void aMetadataFamilyOutsideTheListIsNotReadAsAll()
    {
        assertNull("an unknown family restricts nothing and must be refused", //$NON-NLS-1$
            FindDeadCodeTool.familyOf("abc")); //$NON-NLS-1$
    }

    @Test
    public void subqueriesSideBySideAreOneLevelDeep()
    {
        String query = "ВЫБРАТЬ * ИЗ Т ГДЕ А В (ВЫБРАТЬ А ИЗ Т1) И Б В (ВЫБРАТЬ Б ИЗ Т2) " //$NON-NLS-1$
            + "И В В (ВЫБРАТЬ В ИЗ Т3)"; //$NON-NLS-1$

        assertEquals(1, QueryAntiPatternRules.deepestSubqueryNesting(query));
        assertFalse(names(QueryAntiPatternRules.analyze(query, null)).contains("NESTED_QUERY_DEPTH")); //$NON-NLS-1$
    }

    @Test
    public void subqueriesInsideEachOtherAreCountedByDepth()
    {
        String query = "ВЫБРАТЬ * ИЗ Т ГДЕ А В (ВЫБРАТЬ А ИЗ Т1 ГДЕ Б В (ВЫБРАТЬ Б ИЗ Т2 " //$NON-NLS-1$
            + "ГДЕ В В (ВЫБРАТЬ В ИЗ Т3)))"; //$NON-NLS-1$

        assertEquals(3, QueryAntiPatternRules.deepestSubqueryNesting(query));
        assertTrue(names(QueryAntiPatternRules.analyze(query, null)).contains("NESTED_QUERY_DEPTH")); //$NON-NLS-1$
    }

    @Test
    public void theQueriesOfABatchAreMeasuredApart()
    {
        String one = "ВЫБРАТЬ * ИЗ Т ГДЕ А В (ВЫБРАТЬ А ИЗ Т1 ГДЕ Б В (ВЫБРАТЬ Б ИЗ Т2))"; //$NON-NLS-1$
        String batch = one + ";\n" + one + " // ; not a separator\n"; //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(2, QueryAntiPatternRules.splitBatch(batch).size());
        assertEquals(2, QueryAntiPatternRules.deepestSubqueryNesting(batch));
    }

    private static List<String> names(List<QueryAntiPatternRules.Issue> issues)
    {
        return issues.stream().map(i -> i.rule).toList();
    }
}
