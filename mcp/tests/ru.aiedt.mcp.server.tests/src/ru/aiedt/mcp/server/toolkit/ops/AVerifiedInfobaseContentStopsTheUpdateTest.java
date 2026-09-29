/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.DumpInfoProbe;
import ru.aiedt.mcp.server.support.DumpInfoRebuilder;
import ru.aiedt.mcp.server.support.InfobaseOutsideChange;

/**
 * An incremental update asked to read the infobase compares that dump with the stored copy before
 * anything is started. The comparison is the seam: a probe is handed in, the way a Designer run's
 * outcome is handed to the rebuild, so each answer is a fact about the two readings and not about
 * a live infobase.
 */
public class AVerifiedInfobaseContentStopsTheUpdateTest
{
    private static final String BASE = "file:e:/bases/demo"; //$NON-NLS-1$

    private static DumpInfoProbe.Reading stored(String fingerprint, int records)
    {
        return DumpInfoProbe.reading("copy.xml", "2.7", "2.7", "8.3.27", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            InfobaseOutsideChange.of(BASE, fingerprint, records),
            InfobaseOutsideChange.of(BASE, fingerprint, records));
    }

    /**
     * The same content is not a refusal, and the sentence the update carries names the comparison.
     */
    @Test
    public void aMatchingDumpDoesNotStopAndNamesTheComparison()
    {
        DumpInfoProbe.Reading copy = stored("same-content", 13390); //$NON-NLS-1$
        DumpInfoRebuilder.ContentProbe probe = DumpInfoRebuilder.ContentProbe.read(
            InfobaseOutsideChange.of(BASE, "same-content", 13390)); //$NON-NLS-1$

        assertNull(DatabaseUpdater.stopWhenTheInfobaseDiffers(copy, probe));

        String line = DatabaseUpdater.describeVerifiedMatch(probe);
        assertTrue(line.contains("matches")); //$NON-NLS-1$
        assertTrue(line.contains("13390")); //$NON-NLS-1$
    }

    /**
     * A dump that differs refuses before the update, with both record counts and the step that
     * rewrites the stored copy.
     */
    @Test
    public void aDifferentDumpStopsWithBothRecordCounts()
    {
        DumpInfoProbe.Reading copy = stored("stored-content", 13390); //$NON-NLS-1$
        DumpInfoRebuilder.ContentProbe probe = DumpInfoRebuilder.ContentProbe.read(
            InfobaseOutsideChange.of(BASE, "infobase-content", 13389)); //$NON-NLS-1$

        String stop = DatabaseUpdater.stopWhenTheInfobaseDiffers(copy, probe);

        assertNotNull(stop);
        JsonObject refusal = JsonParser.parseString(stop).getAsJsonObject();
        assertEquals("infobaseChanged", refusal.get("tag").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(13389, refusal.get("infobaseRecords").getAsInt()); //$NON-NLS-1$
        assertEquals(13390, refusal.get("copyRecords").getAsInt()); //$NON-NLS-1$
        assertEquals(DatabaseUpdater.REBUILD_COPY_STEP, refusal.get("nextStep").getAsString()); //$NON-NLS-1$
        assertTrue(refusal.get("error").getAsString().contains("13389")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refusal.get("error").getAsString().contains("13390")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refusal.get("error").getAsString().contains("fullUpdate=true")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A read that did not produce a dump refuses with that reason. The update is not started on
     * the assumption that the copy is still current.
     */
    @Test
    public void aReadThatFailedStopsAndNamesTheReason()
    {
        String stop = DatabaseUpdater.stopWhenTheInfobaseDiffers(stored("stored-content", 10), //$NON-NLS-1$
            DumpInfoRebuilder.ContentProbe.failed("designer refused")); //$NON-NLS-1$

        assertNotNull(stop);
        JsonObject refusal = JsonParser.parseString(stop).getAsJsonObject();
        assertEquals("thickClientFailed", refusal.get("tag").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refusal.get("error").getAsString().contains("designer refused")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refusal.get("error").getAsString().contains("not started")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A read that names why it failed keeps that tag. The infobase's content was not compared, so
     * the refusal is not a change of the infobase.
     */
    @Test
    public void aFailedReadKeepsItsOwnTag()
    {
        assertEquals("busy", tagOf(DumpInfoRebuilder.ContentProbe.failed("held by pid 7"), "busy")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("infobaseNotReleased", //$NON-NLS-1$
            tagOf(DumpInfoRebuilder.ContentProbe.failed("could not release"), "infobaseNotReleased")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("thickClientFailed", //$NON-NLS-1$
            tagOf(DumpInfoRebuilder.ContentProbe.failed("the Designer run timed out"), //$NON-NLS-1$
                "thickClientFailed")); //$NON-NLS-1$
        assertEquals("resolveFailed", //$NON-NLS-1$
            tagOf(DumpInfoRebuilder.ContentProbe.failed("cannot be identified"), "resolveFailed")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A full update was asked to read the infobase and does not: it loads the configuration, and
     * the sentence says the read was not made.
     */
    @Test
    public void aFullUpdateDoesNotReadTheInfobase()
    {
        String line = DatabaseUpdater.aFullUpdateDoesNotReadTheInfobase();
        assertTrue(line.contains("full update")); //$NON-NLS-1$
        assertTrue(line.contains("does not read")); //$NON-NLS-1$
    }

    /**
     * Asking for the comparison and not asking for it are two runs. A refusal from the read must
     * not be served as the answer of a call that did not ask for the read.
     */
    @Test
    public void verifyInfobaseContentIsPartOfTheRunIdentity()
    {
        String off = DatabaseUpdater.runKeyFor("proj", "app-1", false, true, false, false, false, //$NON-NLS-1$ //$NON-NLS-2$
            true, false);
        String on = DatabaseUpdater.runKeyFor("proj", "app-1", false, true, false, false, false, //$NON-NLS-1$ //$NON-NLS-2$
            true, false, true);
        assertNotEquals(off, on);
    }

    /** The tag {@link DatabaseUpdater#stopWhenTheInfobaseDiffers} puts on a failed read. */
    private static String tagOf(DumpInfoRebuilder.ContentProbe probe, String failureKind)
    {
        probe.failureKind = failureKind;
        String stop = DatabaseUpdater.stopWhenTheInfobaseDiffers(stored("stored-content", 10), probe); //$NON-NLS-1$
        return JsonParser.parseString(stop).getAsJsonObject().get("tag").getAsString(); //$NON-NLS-1$
    }
}
