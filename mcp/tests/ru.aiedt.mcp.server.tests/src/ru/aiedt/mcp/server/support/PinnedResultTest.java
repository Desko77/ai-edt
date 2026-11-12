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

import com.google.gson.JsonObject;

import ru.aiedt.mcp.server.wire.GsonHolder;

/**
 * Holds the pinned selection: the snapshot covers the whole sequence and its context, and the
 * selection does not leak the caller's mutable arguments or units.
 */
public class PinnedResultTest
{
    @Test
    public void theSnapshotCoversEveryUnitNotOnlyTheFirst()
    {
        PinnedResult one = selection(List.of(ResultUnit.of("one"))); //$NON-NLS-1$
        PinnedResult two = selection(List.of(ResultUnit.of("one"), ResultUnit.of("two"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotEquals(one.snapshot(), two.snapshot());
    }

    @Test
    public void theSnapshotCoversProjectArgumentsAndSourceFingerprint()
    {
        PinnedResult base = PinnedResult.builder("read") //$NON-NLS-1$
            .projects(List.of("P")) //$NON-NLS-1$
            .arguments(Map.of("offset", 10)) //$NON-NLS-1$
            .units(List.of(ResultUnit.of("x"))) //$NON-NLS-1$ //$NON-NLS-2$
            .build();
        PinnedResult otherProject = PinnedResult.builder("read") //$NON-NLS-1$
            .projects(List.of("Q")) //$NON-NLS-1$
            .arguments(Map.of("offset", 10)) //$NON-NLS-1$
            .units(List.of(ResultUnit.of("x"))) //$NON-NLS-1$ //$NON-NLS-2$
            .build();
        PinnedResult otherArguments = PinnedResult.builder("read") //$NON-NLS-1$
            .projects(List.of("P")) //$NON-NLS-1$
            .arguments(Map.of("offset", 11)) //$NON-NLS-1$
            .units(List.of(ResultUnit.of("x"))) //$NON-NLS-1$ //$NON-NLS-2$
            .build();
        PinnedResult fingerprinted = PinnedResult.builder("read") //$NON-NLS-1$
            .projects(List.of("P")) //$NON-NLS-1$
            .arguments(Map.of("offset", 10)) //$NON-NLS-1$
            .units(List.of(ResultUnit.of("x"))) //$NON-NLS-1$ //$NON-NLS-2$
            .sourceFingerprint("abc") //$NON-NLS-1$
            .build();
        assertNotEquals(base.snapshot(), otherProject.snapshot());
        assertNotEquals(base.snapshot(), otherArguments.snapshot());
        assertNotEquals(base.snapshot(), fingerprinted.snapshot());
    }

    @Test
    public void theSnapshotDoesNotDependOnTheOrderKeysArrivedIn()
    {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("a", 1); //$NON-NLS-1$
        first.put("b", 2); //$NON-NLS-1$
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("b", 2); //$NON-NLS-1$
        second.put("a", 1); //$NON-NLS-1$
        assertEquals(selectionWithArguments(first).snapshot(), selectionWithArguments(second).snapshot());
    }

    @Test
    public void argumentsAreDetachedWhenStoredAndWhenRead()
    {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("offset", 10); //$NON-NLS-1$
        PinnedResult result = selectionWithArguments(source);
        source.put("offset", 99); //$NON-NLS-1$
        assertEquals(10, ((Number)result.arguments().get("offset")).intValue()); //$NON-NLS-1$
        result.arguments().put("offset", 42); //$NON-NLS-1$
        assertEquals(10, ((Number)result.arguments().get("offset")).intValue()); //$NON-NLS-1$
    }

    @Test
    public void unitsAreCopiedAtBuildTime()
    {
        List<ResultUnit> source = new ArrayList<>();
        source.add(ResultUnit.of("one")); //$NON-NLS-1$
        PinnedResult result = selection(source);
        source.add(ResultUnit.of("two")); //$NON-NLS-1$
        assertEquals(1, result.unitCount());
        assertEquals("one", result.units().get(0).text()); //$NON-NLS-1$
    }

    @Test
    public void theMemoryEstimateCountsTheWholeSequence()
    {
        PinnedResult small = selection(List.of(ResultUnit.of("12345"))); //$NON-NLS-1$
        PinnedResult large = selection(List.of(ResultUnit.of("12345"), ResultUnit.of("67890"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(large.estimatedBytes() > small.estimatedBytes());
    }

    @Test
    public void theMemoryEstimateCountsTheRetainedTreeNotOnlySerializedText()
    {
        // A wide JSON tree retains one node and one string per member; its serialized text packs
        // the same members far tighter. A cap that counted only the serialized copy would let a
        // wide selection pin several times its budget while looking small.
        JsonObject tree = new JsonObject();
        for (int i = 0; i < 200; i++)
        {
            tree.addProperty("key" + i, "v" + i); //$NON-NLS-1$ //$NON-NLS-2$
        }
        ResultUnit structured = ResultUnit.of(tree);
        ResultUnit flat = ResultUnit.of(structured.json());
        PinnedResult withTree = selection(List.of(structured));
        PinnedResult withFlat = selection(List.of(flat));
        assertTrue("the retained tree must count beyond its serialized copy", //$NON-NLS-1$
            withTree.estimatedBytes() > 2 * withFlat.estimatedBytes());

        Map<String, Object> nested = new LinkedHashMap<>();
        List<Object> names = new ArrayList<>();
        for (int i = 0; i < 100; i++)
        {
            names.add("n" + i); //$NON-NLS-1$
        }
        nested.put("objectFqns", names); //$NON-NLS-1$
        PinnedResult withNested = selectionWithArguments(nested);
        PinnedResult withOneFlatString = selectionWithArguments(
            Map.of("objectFqns", GsonHolder.toJson(nested))); //$NON-NLS-1$
        assertTrue("a hundred short values cost more than one flat string of their size", //$NON-NLS-1$
            withNested.estimatedBytes() > 2 * withOneFlatString.estimatedBytes());
    }

    @Test
    public void anIncompleteCollectionMustNameItsCoverage()
    {
        PinnedResult incomplete = PinnedResult.builder("read") //$NON-NLS-1$
            .units(List.of(ResultUnit.of("x"))) //$NON-NLS-1$ //$NON-NLS-2$
            .complete(false)
            .coverage("the search was cut short") //$NON-NLS-1$
            .build();
        assertFalse(incomplete.complete());
        assertEquals("the search was cut short", incomplete.coverage()); //$NON-NLS-1$
    }

    @Test(expected = IllegalArgumentException.class)
    public void anIncompleteCollectionWithoutCoverageIsRefused()
    {
        PinnedResult.builder("read").complete(false).build(); //$NON-NLS-1$
    }

    @Test(expected = IllegalArgumentException.class)
    public void aSelectionWithoutAnOperationIsRefused()
    {
        PinnedResult.builder(" "); //$NON-NLS-1$
    }

    private static PinnedResult selection(List<ResultUnit> units)
    {
        return PinnedResult.builder("read").units(units).build(); //$NON-NLS-1$
    }

    private static PinnedResult selectionWithArguments(Map<String, Object> arguments)
    {
        return PinnedResult.builder("read").arguments(arguments).units(List.of()) //$NON-NLS-1$
            .build();
    }
}
