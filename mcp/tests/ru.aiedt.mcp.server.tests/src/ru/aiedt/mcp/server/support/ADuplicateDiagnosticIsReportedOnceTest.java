/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * One diagnostic per place, and counts taken after that settling.
 * <p>
 * The resource's error list and the semantic validator both report a syntax problem, so a query
 * with four broken places and three bad fields answered with eleven errors - every syntax place
 * counted twice. A caller reading the count reads it as the number of things to fix.
 * </p>
 */
public class ADuplicateDiagnosticIsReportedOnceTest
{
    @Test
    public void anErrorArrivingTwiceIsCountedOnce()
    {
        List<QlValidator.QlIssue> twice = new ArrayList<>();
        twice.add(new QlValidator.QlIssue("ERROR", "mismatched input 'В'", 2, 7)); //$NON-NLS-1$ //$NON-NLS-2$
        twice.add(new QlValidator.QlIssue("ERROR", "mismatched input 'В'", 2, 7)); //$NON-NLS-1$ //$NON-NLS-2$

        QlValidator.ValidationResult settled = QlValidator.ValidationResult.of(twice);

        assertEquals("one place is one error, not two", 1, settled.errorCount); //$NON-NLS-1$
        assertEquals("the answer carries the place once", 1, settled.issues.size()); //$NON-NLS-1$
    }

    @Test
    public void aWarningArrivingTwiceIsCountedOnce()
    {
        List<QlValidator.QlIssue> twice = new ArrayList<>();
        twice.add(new QlValidator.QlIssue("WARNING", "the field is not indexed", 5, 20)); //$NON-NLS-1$ //$NON-NLS-2$
        twice.add(new QlValidator.QlIssue("WARNING", "the field is not indexed", 5, 20)); //$NON-NLS-1$ //$NON-NLS-2$

        QlValidator.ValidationResult settled = QlValidator.ValidationResult.of(twice);

        assertEquals(1, settled.warningCount);
        assertEquals(0, settled.errorCount);
    }

    @Test
    public void differentTextAtOnePlaceIsTwoFindings()
    {
        List<QlValidator.QlIssue> two = new ArrayList<>();
        two.add(new QlValidator.QlIssue("ERROR", "Поле 'А' не найдено", 3, 12)); //$NON-NLS-1$ //$NON-NLS-2$
        two.add(new QlValidator.QlIssue("ERROR", "Поле 'Б' не найдено", 3, 12)); //$NON-NLS-1$ //$NON-NLS-2$

        QlValidator.ValidationResult settled = QlValidator.ValidationResult.of(two);

        assertEquals("two different findings at one place are both kept", 2, settled.errorCount); //$NON-NLS-1$
    }

    @Test
    public void aWarningAndAnErrorAtOnePlaceAreBothKept()
    {
        List<QlValidator.QlIssue> both = new ArrayList<>();
        both.add(new QlValidator.QlIssue("ERROR", "Поле 'А' не найдено", 3, 12)); //$NON-NLS-1$ //$NON-NLS-2$
        both.add(new QlValidator.QlIssue("WARNING", "Поле 'А' не найдено", 3, 12)); //$NON-NLS-1$ //$NON-NLS-2$

        QlValidator.ValidationResult settled = QlValidator.ValidationResult.of(both);

        assertEquals(1, settled.errorCount);
        assertEquals(1, settled.warningCount);
    }

    @Test
    public void theCopyCarryingAnOffsetWinsThePlace()
    {
        // The resource's copy of a finding names no offset; the checker's copy of the same finding
        // does. The place is reported once, by the copy a caller can turn back into a position in
        // the text - which is also the copy the merge of two answers settles by.
        List<QlValidator.QlIssue> twice = new ArrayList<>();
        twice.add(new QlValidator.QlIssue("ERROR", "Поле 'А' не найдено", 3, 12)); //$NON-NLS-1$ //$NON-NLS-2$
        twice.add(new QlValidator.QlIssue("ERROR", "Поле 'А' не найдено", 3, 12, 44, null)); //$NON-NLS-1$ //$NON-NLS-2$

        QlValidator.ValidationResult settled = QlValidator.ValidationResult.of(twice);

        assertEquals("one place is one issue", 1, settled.issues.size()); //$NON-NLS-1$
        assertEquals("the record with the offset is the one kept", 44, //$NON-NLS-1$
            settled.issues.get(0).offset);
    }
}
