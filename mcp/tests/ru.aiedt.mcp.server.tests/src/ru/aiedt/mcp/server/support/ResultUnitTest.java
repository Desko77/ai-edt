/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Holds the unit of the pageable sequence: its kind, its serialized form, and the detachment from
 * the caller's mutable content.
 */
public class ResultUnitTest
{
    @Test
    public void everyKindSurvivesConstruction()
    {
        assertEquals(ResultUnit.Kind.RESULT, ResultUnit.of("one").kind()); //$NON-NLS-1$
        assertEquals(ResultUnit.Kind.COVERAGE, ResultUnit.ofCoverage("two").kind()); //$NON-NLS-1$
        assertEquals(ResultUnit.Kind.BLOCK, ResultUnit.ofBlock("three").kind()); //$NON-NLS-1$
    }

    @Test
    public void textIsRecognizedAndCountedInCharacters()
    {
        ResultUnit unit = ResultUnit.of("Привет"); //$NON-NLS-1$
        assertTrue(unit.isText());
        assertEquals("Привет", unit.text()); //$NON-NLS-1$
        assertEquals(6, unit.textLength());
    }

    @Test
    public void aStructuredValueIsNotText()
    {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", "x"); //$NON-NLS-1$ //$NON-NLS-2$
        ResultUnit unit = ResultUnit.of(value);
        assertFalse(unit.isText());
        assertEquals(null, unit.text());
        assertEquals(0, unit.textLength());
    }

    @Test
    public void theSerializedFormIsWhatTheClientReceives()
    {
        ResultUnit unit = ResultUnit.of("a\"b\\c"); //$NON-NLS-1$
        assertEquals("\"a\\\"b\\\\c\"", unit.json()); //$NON-NLS-1$
    }

    @Test
    public void theValueIsDetachedFromTheCallersCollection()
    {
        List<String> source = new ArrayList<>();
        source.add("kept"); //$NON-NLS-1$
        ResultUnit unit = ResultUnit.of(source);
        source.add("added later"); //$NON-NLS-1$
        assertEquals("[\"kept\"]", unit.json()); //$NON-NLS-1$
    }

    @Test
    public void changingTheSourceMapDoesNotChangeTheUnit()
    {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("a", 1); //$NON-NLS-1$
        ResultUnit first = ResultUnit.of(source);
        source.put("b", 2); //$NON-NLS-1$
        ResultUnit second = ResultUnit.of(source);
        assertNotEquals(first.json(), second.json());
        assertEquals("{\"a\":1}", first.json()); //$NON-NLS-1$
    }

    @Test
    public void aShortenedCopyKeepsTheKind()
    {
        ResultUnit shortened = ResultUnit.ofBlock("0123456789").withText("012"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(ResultUnit.Kind.BLOCK, shortened.kind());
        assertEquals("012", shortened.text()); //$NON-NLS-1$
    }
}
