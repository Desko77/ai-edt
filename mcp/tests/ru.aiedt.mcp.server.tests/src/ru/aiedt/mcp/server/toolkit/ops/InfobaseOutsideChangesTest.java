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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.DumpInfoProbe;
import ru.aiedt.mcp.server.support.InfobaseOutsideChange;

/**
 * An infobase changes without EDT, and the store's ConfigDumpInfo.xml is what an incremental update
 * is decided against - so which base that file belongs to, and what it held when this server left it
 * there, decide whether the update is a decision about the database the call names.
 *
 * <p>The copy and the record of it are read apart from EDT here: the gate compares two readings, and
 * a reading can be handed to it without a workspace, an application manager or an infobase standing
 * behind it. What the gate refuses (another base) is tested with the JSON it answers; what it only
 * reports (a copy that is not the recorded one) is tested through the sentence the answer carries,
 * because that sentence is the difference between an update that was decided about the wrong file
 * silently and one that says so.</p>
 */
public class InfobaseOutsideChangesTest
{
    /** The base a store's copy was written for, as an identity of a file infobase. */
    private static final String RECORDED_BASE = "file:e:/bases/demo"; //$NON-NLS-1$

    /** The base the same application points at after it was repointed. */
    private static final String CURRENT_BASE = "file:e:/bases/other"; //$NON-NLS-1$

    /** A reading of a stored copy, with the record of it this store holds. */
    private static DumpInfoProbe.Reading reading(String copyBase, String copyContent, int copyRecords,
        String recordedBase, String recordedContent, int recordedRecords)
    {
        return DumpInfoProbe.reading("E:/ws/.metadata/ib-sync/ss/<uuid>/ConfigDumpInfo.xml", //$NON-NLS-1$
            "2.7", "2.7", "8.3.27.2214", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            InfobaseOutsideChange.of(copyBase, copyContent, copyRecords),
            InfobaseOutsideChange.of(recordedBase, recordedContent, recordedRecords));
    }

    /**
     * A copy recorded for another base stops an incremental update, names both bases and the way
     * past it, and a full update is not gated at all - it loads the model into the base it was given
     * and does not read the copy.
     */
    @Test
    public void aCopyOfAnotherBaseStopsAnIncrementalUpdateAndNotAFullOne()
    {
        DumpInfoProbe.Reading dumpInfo = reading(CURRENT_BASE, "content-now", 1800, //$NON-NLS-1$
            RECORDED_BASE, "content-then", 17); //$NON-NLS-1$

        String stop = DatabaseUpdater.stopOnAnotherInfobase(dumpInfo, false);

        assertNotNull("an incremental update decided against another base's copy is stopped", stop); //$NON-NLS-1$
        JsonObject refusal = JsonParser.parseString(stop).getAsJsonObject();
        assertEquals("infobaseChanged", refusal.get("tag").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(RECORDED_BASE, refusal.get("recordedInfobase").getAsString()); //$NON-NLS-1$
        assertEquals(CURRENT_BASE, refusal.get("currentInfobase").getAsString()); //$NON-NLS-1$
        assertEquals("sync_control syncOperation=rebuild_dump_info confirm=true", //$NON-NLS-1$
            refusal.get("nextStep").getAsString()); //$NON-NLS-1$
        assertTrue("the refusal says which base the file was written for", //$NON-NLS-1$
            refusal.get("error").getAsString().contains(RECORDED_BASE)); //$NON-NLS-1$
        assertTrue("the refusal names the way past the check", //$NON-NLS-1$
            refusal.get("error").getAsString().contains("fullUpdate=true")); //$NON-NLS-1$

        assertNull("a full update does not read the copy, so it is not gated", //$NON-NLS-1$
            DatabaseUpdater.stopOnAnotherInfobase(dumpInfo, true));
    }

    /**
     * A copy that belongs to this base but holds other content than the record describes does not
     * stop the update - the file may have been written by an update before launch - and it is not
     * passed over either: the answer says the update is being decided against a file this server did
     * not leave.
     */
    @Test
    public void aCopyWhoseContentMovedIsNamedRatherThanPassedOver()
    {
        DumpInfoProbe.Reading dumpInfo = reading(CURRENT_BASE, "content-now", 1800, //$NON-NLS-1$
            CURRENT_BASE, "content-then", 1700); //$NON-NLS-1$

        assertNull("the same base's copy is not a reason to refuse", //$NON-NLS-1$
            DatabaseUpdater.stopOnAnotherInfobase(dumpInfo, false));

        String line = DatabaseUpdater.describeInfobaseChangeCheck(dumpInfo);
        assertNotNull(line);
        assertTrue("the answer names what the record held", line.contains("1700")); //$NON-NLS-1$
        assertTrue("the answer names what the copy holds now", line.contains("1800")); //$NON-NLS-1$
        assertTrue("the answer says the file is not the one this server left", //$NON-NLS-1$
            line.contains("not the one the last update here left")); //$NON-NLS-1$
        assertTrue("the answer does not claim the copy matched", !line.startsWith("matched")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A copy this server's own update left is reported as the one that was compared. */
    @Test
    public void aCopyOfThisBaseWithTheRecordedContentIsReportedAsMatched()
    {
        DumpInfoProbe.Reading dumpInfo = reading(CURRENT_BASE, "content-now", 1800, //$NON-NLS-1$
            CURRENT_BASE, "content-now", 1800); //$NON-NLS-1$

        assertNull(DatabaseUpdater.stopOnAnotherInfobase(dumpInfo, false));

        String line = DatabaseUpdater.describeInfobaseChangeCheck(dumpInfo);
        assertNotNull(line);
        assertTrue("the sentence says the two readings agree", line.startsWith("matched")); //$NON-NLS-1$
        assertTrue("and how many records that is", line.contains("1800 records")); //$NON-NLS-1$
    }

    /**
     * An application that does not say where its infobase is names no base, so nothing is compared
     * and nothing is refused - a check with no second reading is a fact the answer names.
     */
    @Test
    public void withoutABaseEitherSideNothingIsCompared()
    {
        DumpInfoProbe.Reading unnamed = reading(null, "content-now", 1800, //$NON-NLS-1$
            RECORDED_BASE, "content-then", 1700); //$NON-NLS-1$

        assertNull("no base on the copy means no comparison", //$NON-NLS-1$
            DatabaseUpdater.stopOnAnotherInfobase(unnamed, false));

        String line = DatabaseUpdater.describeInfobaseChangeCheck(unnamed);
        assertNotNull(line);
        assertTrue("the answer says why it could not compare", //$NON-NLS-1$
            line.startsWith("not compared")); //$NON-NLS-1$
        assertTrue("and what the copy it did read holds", line.contains("1800 records")); //$NON-NLS-1$

        DumpInfoProbe.Reading unrecorded = reading(CURRENT_BASE, "content-now", 1800, null, null, //$NON-NLS-1$
            InfobaseOutsideChange.UNKNOWN_RECORDS);
        assertNull(DatabaseUpdater.stopOnAnotherInfobase(unrecorded, false));
        assertNotNull(DatabaseUpdater.describeInfobaseChangeCheck(unrecorded));
    }

    /** A base whose copy has never been recorded is not compared, and the answer says so. */
    @Test
    public void aCopyWithNoRecordIsNotCompared()
    {
        InfobaseOutsideChange nothing = InfobaseOutsideChange.read(null);
        DumpInfoProbe.Reading dumpInfo = DumpInfoProbe.reading("copy.xml", "2.7", "2.7", "8.3.27", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            null, InfobaseOutsideChange.of(CURRENT_BASE, "content-now", 1800), nothing); //$NON-NLS-1$

        assertNull(DatabaseUpdater.stopOnAnotherInfobase(dumpInfo, false));

        String line = DatabaseUpdater.describeInfobaseChangeCheck(dumpInfo);
        assertNotNull(line);
        assertTrue("the answer says no record exists yet", //$NON-NLS-1$
            line.contains("no copy has been recorded")); //$NON-NLS-1$
    }

    /** A copy that is not there at all is not compared by content, and says nothing about it. */
    @Test
    public void anUnreadableCopySaysNothingAboutItsContent()
    {
        DumpInfoProbe.Reading missing = DumpInfoProbe.reading("copy.xml", "2.7", "2.7", "8.3.27", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            InfobaseOutsideChange.copyOf(java.nio.file.Paths.get("no/such/dir/ConfigDumpInfo.xml"), //$NON-NLS-1$
                CURRENT_BASE),
            InfobaseOutsideChange.of(CURRENT_BASE, "content-then", 17)); //$NON-NLS-1$

        assertNull(DatabaseUpdater.stopOnAnotherInfobase(missing, false));
        assertNull("a file that does not read is not reported as a comparison", //$NON-NLS-1$
            DatabaseUpdater.describeInfobaseChangeCheck(missing));
    }

    /**
     * A store whose record names another base is refused even when the copy file itself is gone:
     * the file is not what makes the store foreign - the baseline beside it is the other base's, and
     * an update decided on that baseline is decided about the wrong database.
     */
    @Test
    public void aStoreRecordedForAnotherBaseIsRefusedWithNoCopyOnDisk()
    {
        DumpInfoProbe.Reading gone = DumpInfoProbe.reading("copy.xml", "2.7", "2.7", "8.3.27", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            InfobaseOutsideChange.copyOf(java.nio.file.Paths.get("no/such/dir/ConfigDumpInfo.xml"), //$NON-NLS-1$
                CURRENT_BASE),
            InfobaseOutsideChange.of(RECORDED_BASE, "content-then", 17)); //$NON-NLS-1$

        assertNotNull("the store belongs to another base", //$NON-NLS-1$
            DatabaseUpdater.stopOnAnotherInfobase(gone, false));
        assertTrue("and the answer names that rather than the content", //$NON-NLS-1$
            DatabaseUpdater.describeInfobaseChangeCheck(gone).contains(RECORDED_BASE)); //$NON-NLS-1$
    }

    /**
     * The fingerprint follows the records a file carries and not the order they are written in, and
     * a version that moved changes it - which is what makes it a reading of the content rather than
     * of the bytes.
     */
    @Test
    public void theFingerprintFollowsTheContentRatherThanTheOrder() throws IOException
    {
        Path dir = Files.createTempDirectory("outside-change-"); //$NON-NLS-1$
        try
        {
            Path first = write(dir.resolve("first.xml"), record("Catalog.Goods", "aaaa"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                record("Document.Sale", "bbbb")); //$NON-NLS-1$ //$NON-NLS-2$
            Path reordered = write(dir.resolve("reordered.xml"), record("Document.Sale", "bbbb"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                record("Catalog.Goods", "aaaa")); //$NON-NLS-1$ //$NON-NLS-2$
            Path moved = write(dir.resolve("moved.xml"), record("Catalog.Goods", "aaaa"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                record("Document.Sale", "cccc")); //$NON-NLS-1$ //$NON-NLS-2$

            InfobaseOutsideChange asWritten = InfobaseOutsideChange.copyOf(first, CURRENT_BASE);
            InfobaseOutsideChange sameRecords =
                InfobaseOutsideChange.copyOf(reordered, CURRENT_BASE);
            InfobaseOutsideChange otherVersion = InfobaseOutsideChange.copyOf(moved, CURRENT_BASE);

            assertTrue(asWritten.known());
            assertEquals("two records in the file", 2, asWritten.records); //$NON-NLS-1$
            assertEquals("the same records in another order are the same content", //$NON-NLS-1$
                asWritten.fingerprint, sameRecords.fingerprint);
            assertNotEquals("a version that moved is not the same content", //$NON-NLS-1$
                asWritten.fingerprint, otherVersion.fingerprint);

            InfobaseOutsideChange recorded = InfobaseOutsideChange.of(CURRENT_BASE,
                asWritten.fingerprint, asWritten.records);
            assertTrue(recorded.contentDiffersFrom(otherVersion));
            assertTrue(!recorded.contentDiffersFrom(sameRecords));
        }
        finally
        {
            deleteTree(dir);
        }
    }

    /**
     * A record written and read back is the reading that was written, identity included: an
     * identity carries the path of the base, and a path outside one workspace's ASCII has to come
     * back as it went in - which is why the record is written as UTF-8.
     */
    @Test
    public void aRecordSurvivesARoundTripWithAPathOutsideAscii() throws IOException
    {
        Path dir = Files.createTempDirectory("record-roundtrip-"); //$NON-NLS-1$
        try
        {
            // The identity of a base on a path spelled in Cyrillic, as the identity of a Russian
            // installation carries it: written as escapes so this source stays ASCII.
            String identity = "file:e:/bases/\u0414\u0435\u043c\u043e"; //$NON-NLS-1$
            Path recordFile = dir.resolve(InfobaseOutsideChange.FILE_NAME);

            InfobaseOutsideChange.of(identity, "content-now", 1800).writeTo(recordFile); //$NON-NLS-1$
            InfobaseOutsideChange readBack = InfobaseOutsideChange.read(recordFile);

            assertTrue(readBack.known());
            assertEquals("the base comes back as it went in", identity, readBack.identity); //$NON-NLS-1$
            assertEquals("content-now", readBack.fingerprint); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(1800, readBack.records);
            assertTrue("a reading of the same base does not diverge from its own record", //$NON-NLS-1$
                !readBack.describesAnotherInfobaseThan(InfobaseOutsideChange.of(identity, "x", 1))); //$NON-NLS-1$
            assertTrue("a reading of another base does", //$NON-NLS-1$
                readBack.describesAnotherInfobaseThan(InfobaseOutsideChange.of(CURRENT_BASE, "x", 1))); //$NON-NLS-1$
        }
        finally
        {
            deleteTree(dir);
        }
    }

    /**
     * The record file is a sidecar of the copy it describes: named beside it, so a store directory
     * carries both or neither, and a copy with no path has no record either.
     */
    @Test
    public void theRecordIsASidecarOfTheCopy()
    {
        Path copy = java.nio.file.Paths.get("E:/ws/.metadata/ib-sync/ss/11111111/ConfigDumpInfo.xml"); //$NON-NLS-1$
        assertEquals(copy.resolveSibling(InfobaseOutsideChange.FILE_NAME),
            InfobaseOutsideChange.recordFileOf(copy));
        assertNull(InfobaseOutsideChange.recordFileOf(null));
    }

    /** One {@code Metadata} record of a dump-info file, shaped as the platform writes it. */
    private static String record(String name, String configVersion)
    {
        return "<Metadata name=\"" + name + "\" id=\"00000000-0000-0000-0000-000000000000\" " //$NON-NLS-1$ //$NON-NLS-2$
            + "configVersion=\"" + configVersion + "\"/>"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A dump-info file carrying the given records under a root the format check reads. */
    private static Path write(Path file, String... records) throws IOException
    {
        StringBuilder content = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"); //$NON-NLS-1$
        content.append("<ConfigDumpInfo version=\"2.7\">\n"); //$NON-NLS-1$
        for (String record : records)
        {
            content.append(record).append('\n');
        }
        content.append("</ConfigDumpInfo>\n"); //$NON-NLS-1$
        Files.write(file, content.toString().getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /** Removes a temporary directory with everything in it. */
    private static void deleteTree(Path dir) throws IOException
    {
        if (!Files.isDirectory(dir))
        {
            Files.deleteIfExists(dir);
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir))
        {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder())
                .collect(java.util.stream.Collectors.toList()))
            {
                Files.deleteIfExists(path);
            }
        }
    }
}
