/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.Test;

/**
 * How a name the call gave is looked up in the report, and what comes back when it is not there.
 */
public class ANameIsLookedUpByTheWholeOfItOrItsTailTest
{
    /**
     * A name matches itself, matches the same name in another case, and matches a module-qualified
     * name that ends with it - in either direction.
     */
    @Test
    public void aNameMatchesTheWholeOfItOrItsTail()
    {
        assertTrue(RunNameReconciliation.sameName("Тест_Сумма", "Тест_Сумма")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(RunNameReconciliation.sameName("Тест_Сумма", "тест_Сумма")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(RunNameReconciliation.sameName("Тест_Сумма", "МодульТестов.Тест_Сумма")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(RunNameReconciliation.sameName("МодульТестов.Тест_Сумма", "Тест_Сумма")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(RunNameReconciliation.sameName("Модуль.Тесты", "ОбщиеТесты.Модуль.Тесты")); //$NON-NLS-1$ //$NON-NLS-2$

        // A part of a name is not a name: the tail has to start at a dot, so a module is not
        // found inside a longer module that merely ends with the same letters, and a test is not
        // found in a longer test name that carries it in the middle.
        assertFalse(RunNameReconciliation.sameName("Тесты", "ОбщиеТесты")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(RunNameReconciliation.sameName("Тест", "Тест_Сумма")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(RunNameReconciliation.sameName("Сумма", "Модуль.Тест_Сумма")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Every name given is looked up, the counts say how many there were and how many were found,
     * and only the ones that were not found are listed.
     */
    @Test
    public void theCountsAndTheMissingNamesComeBack()
    {
        List<String> named = Arrays.asList("МодульТестов", "МодульДругой", "МодульТестов.Тест_Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        RunNameReconciliation.Result result = RunNameReconciliation.reconcile(named,
            new LinkedHashSet<>(Arrays.asList("МодульТестов", "МодульТестов.Тест_Сумма"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(3, result.namedCount);
        assertEquals(2, result.foundCount);
        assertFalse(result.isComplete());
        assertEquals(Collections.singletonList("МодульДругой"), result.missing); //$NON-NLS-1$
        String described = result.describe();
        assertTrue(described, described.contains("МодульДругой")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(described, described.contains("1 of the 3 names")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(described, described.contains("not registered in the module")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(described, described.contains("not found")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A report that carries everything named, a call that named nothing, and a name given twice or
     * carried with no value are all answered without inventing a discrepancy.
     */
    @Test
    public void nothingNamedOrNothingMissingIsNoDiscrepancy()
    {
        RunNameReconciliation.Result allFound = RunNameReconciliation.reconcile(
            Arrays.asList("МодульТестов.Тест_Сумма"), Collections.singleton("МодульТестов.Тест_Сумма")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(allFound.isComplete());
        assertEquals(1, allFound.namedCount);
        assertEquals(1, allFound.foundCount);

        RunNameReconciliation.Result noneNamed =
            RunNameReconciliation.reconcile(Collections.emptyList(), Collections.singleton("A")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(noneNamed.isComplete());
        assertEquals(0, noneNamed.namedCount);
        assertEquals(0, noneNamed.foundCount);

        RunNameReconciliation.Result noReport = RunNameReconciliation.reconcile(
            Arrays.asList(" A ", "a", "  "), Collections.emptyList()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("a name given twice is one name, and a blank one is none", 1, noReport.namedCount); //$NON-NLS-1$
        assertEquals(1, noReport.missing.size());
        assertEquals("A", noReport.missing.get(0)); //$NON-NLS-1$

        assertTrue(RunNameReconciliation.reconcile(null, Collections.singleton("A")).isComplete()); //$NON-NLS-1$
    }
}
