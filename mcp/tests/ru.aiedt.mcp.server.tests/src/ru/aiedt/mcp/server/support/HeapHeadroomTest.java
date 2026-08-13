/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Verifies the heap guard: the share is computed and clamped as expected, a refusal needs both the
 * post-collection and the live figure, a reading the JVM cannot vouch for never refuses anything, the
 * threshold can be switched off, and a live reading of the JVM running these tests makes sense.
 * <p>
 * The guard exists because a long series of expensive calls once walked EDT's heap to its ceiling and
 * the OutOfMemoryError took the workbench and the MCP server with it, so every later call was lost too.
 * These tests hold the two properties that keep such a guard useful rather than annoying: it refuses
 * when the heap really is full, and it stays out of the way whenever it cannot prove that.
 * </p>
 */
public class HeapHeadroomTest
{
    private static final long MB = 1024L * 1024L;

    @Test
    public void shareOfTheCeilingIsComputed()
    {
        assertEquals(50, HeapHeadroom.percentOf(2048L * MB, 4096L * MB));
        assertEquals("a part share rounds down, so the guard never fires early", //$NON-NLS-1$
            91, HeapHeadroom.percentOf(3768L * MB, 4096L * MB));
        assertEquals(0, HeapHeadroom.percentOf(1L, 4096L * MB));
    }

    @Test
    public void shareIsClampedAndSurvivesAnUnknownCeiling()
    {
        assertEquals("holding more than the ceiling is still full, not more than full", //$NON-NLS-1$
            100, HeapHeadroom.percentOf(8192L * MB, 4096L * MB));
        assertEquals("no ceiling means no proportion to report", 0, HeapHeadroom.percentOf(100L, 0L)); //$NON-NLS-1$
        assertEquals(0, HeapHeadroom.percentOf(100L, -1L));
        assertEquals(0, HeapHeadroom.percentOf(0L, 4096L * MB));
    }

    @Test
    public void aFullHeapRefusesWork()
    {
        HeapHeadroom.Reading full = new HeapHeadroom.Reading(3800L * MB, 3900L * MB, 4096L * MB, true);
        assertTrue(full.isTrustworthy());
        assertEquals(92, full.getPercentUsed());
        assertEquals(95, full.getLivePercent());
        assertTrue(HeapHeadroom.refusesWork(full, 92));
    }

    @Test
    public void aHeapWithRoomDoesNotRefuseWork()
    {
        HeapHeadroom.Reading roomy = new HeapHeadroom.Reading(1024L * MB, 1200L * MB, 4096L * MB, true);
        assertEquals(25, roomy.getPercentUsed());
        assertFalse(HeapHeadroom.refusesWork(roomy, 92));
    }

    @Test
    public void theThresholdIsTheBoundary()
    {
        HeapHeadroom.Reading exactly = new HeapHeadroom.Reading(3686L * MB, 3686L * MB, 4096L * MB, true);
        assertEquals(89, exactly.getPercentUsed());
        assertTrue("at the threshold is already too full", HeapHeadroom.refusesWork(exactly, 89)); //$NON-NLS-1$
        assertFalse("one percent below it is not", HeapHeadroom.refusesWork(exactly, 90)); //$NON-NLS-1$
    }

    @Test
    public void garbageAboutToBeSweptDoesNotRefuseWork()
    {
        // A tool that allocates hard keeps live occupancy near the ceiling while the heap is perfectly
        // healthy. Refusing on that alone would turn away work there was room for.
        HeapHeadroom.Reading allocating = new HeapHeadroom.Reading(900L * MB, 4000L * MB, 4096L * MB, true);
        assertEquals(97, allocating.getLivePercent());
        assertFalse(HeapHeadroom.refusesWork(allocating, 92));
    }

    @Test
    public void aStaleSnapshotDoesNotLatchTheGuardShut()
    {
        // The last collection ran while a large temporary was still held and nothing has collected
        // since. Acting on that history alone would refuse every call - and refusing them suppresses
        // the allocation that would trigger the next collection, so the guard would never reopen.
        HeapHeadroom.Reading stale = new HeapHeadroom.Reading(3900L * MB, 800L * MB, 4096L * MB, true);
        assertEquals(95, stale.getPercentUsed());
        assertEquals(19, stale.getLivePercent());
        assertFalse(HeapHeadroom.refusesWork(stale, 92));
    }

    @Test
    public void aReadingTheJvmCannotVouchForNeverRefuses()
    {
        HeapHeadroom.Reading unsupported = new HeapHeadroom.Reading(4000L * MB, 4000L * MB, 4096L * MB, false);
        assertFalse(unsupported.isTrustworthy());
        assertFalse(HeapHeadroom.refusesWork(unsupported, 92));
        assertEquals("it is still reported, just not acted on", 97, unsupported.getPercentUsed()); //$NON-NLS-1$
    }

    @Test
    public void anUnknownCeilingNeverRefuses()
    {
        HeapHeadroom.Reading noCeiling = new HeapHeadroom.Reading(4000L * MB, 4000L * MB, 0L, true);
        assertFalse(noCeiling.isTrustworthy());
        assertFalse(HeapHeadroom.refusesWork(noCeiling, 92));
    }

    @Test
    public void aThresholdOutOfRangeSwitchesTheGuardOff()
    {
        HeapHeadroom.Reading full = new HeapHeadroom.Reading(4090L * MB, 4090L * MB, 4096L * MB, true);
        assertFalse("zero is how the guard is turned off", HeapHeadroom.refusesWork(full, 0)); //$NON-NLS-1$
        assertFalse(HeapHeadroom.refusesWork(full, -1));
        assertFalse("100 would only fire on a heap already past saving", //$NON-NLS-1$
            HeapHeadroom.refusesWork(full, 100));
        assertFalse(HeapHeadroom.refusesWork(null, 92));
    }

    @Test
    public void theReadingDescribesBothFiguresInWords()
    {
        String held = new HeapHeadroom.Reading(3800L * MB, 3900L * MB, 4096L * MB, true).describe();
        assertTrue(held, held.contains("3800 MB of 4096 MB")); //$NON-NLS-1$
        assertTrue(held, held.contains("92%")); //$NON-NLS-1$
        assertTrue(held, held.contains("after a collection")); //$NON-NLS-1$
        assertTrue("the refusal has to say what is occupied now, not only what survived", //$NON-NLS-1$
            held.contains("3900 MB")); //$NON-NLS-1$

        String unsupported = new HeapHeadroom.Reading(3800L * MB, 3800L * MB, 4096L * MB, false).describe();
        assertTrue("a figure that may not be acted on says so", //$NON-NLS-1$
            unsupported.contains("no post-collection figure")); //$NON-NLS-1$
    }

    @Test
    public void theRunningJvmCanBeMeasured()
    {
        HeapHeadroom.Reading now = HeapHeadroom.current();
        assertTrue("this JVM runs with a heap ceiling", now.getCeilingBytes() > 0L); //$NON-NLS-1$
        assertTrue(now.getRetainedBytes() >= 0L);
        assertTrue("something is always occupied while this test runs", now.getLiveBytes() > 0L); //$NON-NLS-1$
        int percent = now.getPercentUsed();
        assertTrue("share out of range: " + percent, percent >= 0 && percent <= 100); //$NON-NLS-1$
        assertFalse(now.describe().isEmpty());
    }

    @Test
    public void aTestJvmIsNotItselfRefusedWork()
    {
        // A guard that fires on an idle JVM would refuse every heavy call on a healthy workbench too.
        assertFalse("the JVM running these tests is not out of heap", //$NON-NLS-1$
            HeapHeadroom.refusesWork(HeapHeadroom.current(), 99));
    }
}
