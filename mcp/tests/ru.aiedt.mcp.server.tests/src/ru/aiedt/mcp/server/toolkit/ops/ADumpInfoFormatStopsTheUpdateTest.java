/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.junit.Assume;
import org.junit.Test;

import com.e1c.g5.dt.applications.ApplicationCheckUnknownStateTreatment;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.ApplicationUpdateType;
import com.e1c.g5.dt.applications.ExecutionContext;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationArtifact;
import com.e1c.g5.dt.applications.IApplicationListener;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.IApplicationType;
import com.e1c.g5.dt.applications.IUrlAccess;
import com.e1c.g5.dt.applications.LifecycleState;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.ApplicationUpdater;
import ru.aiedt.mcp.server.support.DumpInfoProbe;

/**
 * A stored ConfigDumpInfo.xml whose format is not the one recorded for that infobase stops an update
 * before anything is asked of the infobase: the platform answers {@code FullDump} to a format it does
 * not understand, so such an update silently becomes a full load.
 *
 * <p>Which format an infobase understands is a property of the base rather than of the platform
 * version - measured 23.09 on platform 8.3.27.2214, a base loaded from a {@code .cf} answered
 * {@code 2.20} while another base on the same platform answered {@code 2.7}. So the expectation
 * compared here is what a rebuild recorded for THIS base, and a base with no record is not compared
 * at all.</p>
 *
 * <p>The application manager below records every call it receives. The gate being tested decides
 * BEFORE the manager's first call, so a foreign format answers with a refusal and an empty record,
 * and the override ({@code ignoreDumpInfoFormat=true}) passes and the manager is asked - which is
 * the whole difference between "stopped" and "went ahead knowingly".</p>
 */
public class ADumpInfoFormatStopsTheUpdateTest
{
    /** The reading the whole class is about: a 2.20 file where this base's own Designer wrote 2.7. */
    private static DumpInfoProbe.Reading foreignFile()
    {
        return DumpInfoProbe.reading("E:/ws/.metadata/ib-sync/ss/<uuid>/ConfigDumpInfo.xml", //$NON-NLS-1$
            "2.20", "2.7", "8.3.27.2214"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * A foreign format stops the update with both formats named, and NOTHING is asked of the
     * application manager on the way - not the update state, not the readiness check, not the
     * update itself.
     */
    @Test
    public void aForeignFormatStopsTheUpdateBeforeTheManagerIsAskedAnything()
    {
        RecordingApplications manager =
            new RecordingApplications(ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED);
        IApplication application = new StubApplication("app-1"); //$NON-NLS-1$

        String stop = DatabaseUpdater.passTheFormatGate(manager, application, foreignFile(), false,
            () -> {
                manager.check(application, null, null, null);
                manager.update(application, ApplicationUpdateType.INCREMENTAL, null, null);
                return "asked"; //$NON-NLS-1$
            });

        assertNotNull(stop);
        JsonObject refusal = JsonParser.parseString(stop).getAsJsonObject();
        assertEquals("2.20", refusal.get("dumpInfoFormat").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("2.7", refusal.get("expectedDumpInfoFormat").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("8.3.27.2214", refusal.get("platformVersion").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("dumpInfoFormat", refusal.get("tag").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("sync_control syncOperation=rebuild_dump_info confirm=true", //$NON-NLS-1$
            refusal.get("nextStep").getAsString()); //$NON-NLS-1$
        assertTrue(refusal.get("dumpInfoFile").getAsString().endsWith("ConfigDumpInfo.xml")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal says what the update would silently become", //$NON-NLS-1$
            refusal.get("error").getAsString().contains("FULL")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal names the way out of the check", //$NON-NLS-1$
            refusal.get("error").getAsString().contains("ignoreDumpInfoFormat")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("nothing was asked of the application manager while the format was foreign", //$NON-NLS-1$
            manager.calls.isEmpty());
    }

    /**
     * With the override the gate passes and the supplier runs: the manager is asked to check and to
     * update. The answer says the check was overridden.
     */
    @Test
    public void withTheOverrideTheGatePassesAndTheManagerIsAsked()
    {
        RecordingApplications manager =
            new RecordingApplications(ApplicationUpdateState.UPDATED);
        IApplication application = new StubApplication("app-1"); //$NON-NLS-1$

        String passed = DatabaseUpdater.passTheFormatGate(manager, application, foreignFile(), true,
            () -> {
                manager.check(application, null, null, null);
                manager.update(application, ApplicationUpdateType.INCREMENTAL, null, null);
                return "asked"; //$NON-NLS-1$
            });
        assertEquals("asked", passed); //$NON-NLS-1$
        assertEquals("past the gate, the manager is asked - the update goes ahead", //$NON-NLS-1$
            List.of("check", "update"), manager.calls); //$NON-NLS-1$ //$NON-NLS-2$

        String described = DatabaseUpdater.describeDumpInfoFormatCheck(foreignFile(), true);
        assertNotNull(described);
        assertTrue("the answer says the check was overridden, not that it passed", //$NON-NLS-1$
            described.contains("OVERRIDDEN")); //$NON-NLS-1$
        assertTrue(described.contains("2.20")); //$NON-NLS-1$
        assertTrue(described.contains("2.7")); //$NON-NLS-1$
    }

    /**
     * A probe REPORTS the mismatch and stops nothing: exactly one read of the environment, the
     * mismatch in the answer, no check, no update.
     */
    @Test
    public void aProbeReportsTheMismatchAndStopsNothing()
    {
        RecordingApplications manager =
            new RecordingApplications(ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED);
        IApplication application = new StubApplication("app-1"); //$NON-NLS-1$

        String answer = DatabaseUpdater.whatAnUpdateWouldFace(manager, application, null,
            "app-1", "proj", false, "proj", foreignFile(), false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("a probe reads exactly one thing off the environment", //$NON-NLS-1$
            List.of("getUpdateState"), manager.calls); //$NON-NLS-1$
        JsonObject json = JsonParser.parseString(answer).getAsJsonObject();
        assertTrue(json.get("dumpInfoFormatCheck").getAsString().contains("2.20")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.get("dumpInfoFormatCheck").getAsString().contains("2.7")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Nothing to compare means nothing to stop: no format recorded for this base, or no stored file
     * at all, passes the gate - and the answer says the check was not made rather than claiming it.
     * A base whose record is missing is not compared even where another base on the SAME platform
     * has one, which is the measured case: 8.3.27.2214 wrote 2.7 for one base and 2.20 for another.
     */
    @Test
    public void noRecordOrNoFileMeansNoGate()
    {
        assertNull("a base with no record is not compared even on the platform that wrote 2.7", //$NON-NLS-1$
            DatabaseUpdater.stopOnForeignDumpInfoFormat( //$NON-NLS-1$
                DumpInfoProbe.reading("file", "2.20", null, "8.3.27.2214"), false)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull("a base with no record and no platform either -> no gate", //$NON-NLS-1$
            DatabaseUpdater.stopOnForeignDumpInfoFormat( //$NON-NLS-1$
                DumpInfoProbe.reading("file", "2.20", null, "9.9.9.1"), false)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull("no file -> no gate", DatabaseUpdater.stopOnForeignDumpInfoFormat( //$NON-NLS-1$
            DumpInfoProbe.reading("file", null, "2.7", "8.3.27.1"), false)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull("matching formats -> no gate", DatabaseUpdater.stopOnForeignDumpInfoFormat( //$NON-NLS-1$
            DumpInfoProbe.reading("file", "2.7", "2.7", "8.3.27.1"), false)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull("no reading at all -> no gate", //$NON-NLS-1$
            DatabaseUpdater.stopOnForeignDumpInfoFormat(null, false)); //$NON-NLS-1$

        String notCompared = DatabaseUpdater.describeDumpInfoFormatCheck(
            DumpInfoProbe.reading("file", "2.20", null, "9.9.9.1"), false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue("the answer says the check was not made", notCompared.contains("not compared")); //$NON-NLS-1$ //$NON-NLS-2$
        String matched = DatabaseUpdater.describeDumpInfoFormatCheck(
            DumpInfoProbe.reading("file", "2.7", "2.7", "8.3.27.1"), false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(matched.contains("matched")); //$NON-NLS-1$
    }

    // ---- the format probe itself -------------------------------------------------------------

    /**
     * The version attribute comes off the file head, either side of the comparison: the EDT-written
     * 2.20 and the platform's 2.7 both read, and a file without the root attribute answers null
     * rather than a guess.
     */
    @Test
    public void theFormatComesOffTheFileHead()
        throws IOException
    {
        Path file = Files.createTempFile("dump-info", ".xml"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            Files.write(file, ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
                + "<ConfigDumpInfo version=\"2.20\"><Metadata name=\"A\"/></ConfigDumpInfo>") //$NON-NLS-1$
                .getBytes(StandardCharsets.UTF_8));
            assertEquals("2.20", DumpInfoProbe.formatOf(file)); //$NON-NLS-1$
            assertEquals(1, DumpInfoProbe.recordsIn(file));

            Files.write(file, "<ConfigDumpInfo version=\"2.7\"/>" //$NON-NLS-1$
                .getBytes(StandardCharsets.UTF_8));
            assertEquals("2.7", DumpInfoProbe.formatOf(file)); //$NON-NLS-1$
            assertEquals(0, DumpInfoProbe.recordsIn(file));

            Files.write(file, "<Configuration/>".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
            assertNull(DumpInfoProbe.formatOf(file));
        }
        finally
        {
            Files.deleteIfExists(file);
        }
        assertNull("a missing file is no format at all", DumpInfoProbe.formatOf( //$NON-NLS-1$
            Files.createTempFile("gone", "x").resolveSibling("not-there.xml"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * The expectation is what a rebuild recorded for THIS infobase, and nothing is assumed of a base
     * without a record: not the platform's reputation, and not a neighbouring base's format.
     */
    @Test
    public void theExpectationIsTheFormatRecordedForThisInfobase()
        throws IOException
    {
        Path pairs = Files.createTempFile("dump-info-formats", ".properties"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            assertNull("a base with no record has no expectation", //$NON-NLS-1$
                DumpInfoProbe.expectedFormat("file:e:/bases/one", pairs)); //$NON-NLS-1$
            DumpInfoProbe.rememberPair("file:e:/bases/one", "2.7", pairs); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("what this base's own Designer wrote is the expectation afterwards", //$NON-NLS-1$
                "2.7", DumpInfoProbe.expectedFormat("file:e:/bases/one", pairs)); //$NON-NLS-1$ //$NON-NLS-2$
            assertNull("another base on the same platform is given its own record, not this one", //$NON-NLS-1$
                DumpInfoProbe.expectedFormat("server:srv/Acc", pairs)); //$NON-NLS-1$
            DumpInfoProbe.rememberPair("file:e:/bases/one", "2.20", pairs); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("a later rebuild of the same base supersedes its earlier record", //$NON-NLS-1$
                "2.20", DumpInfoProbe.expectedFormat("file:e:/bases/one", pairs)); //$NON-NLS-1$ //$NON-NLS-2$
            assertNull("nowhere to record means nothing is expected", //$NON-NLS-1$
                DumpInfoProbe.expectedFormat("file:e:/bases/one", null)); //$NON-NLS-1$
            assertNull("no infobase identity is no expectation", //$NON-NLS-1$
                DumpInfoProbe.expectedFormat(null, pairs)); //$NON-NLS-1$
        }
        finally
        {
            Files.deleteIfExists(pairs);
        }
    }

    /**
     * A record applies only on the platform it was measured on. Another platform is not compared,
     * and the answer names both versions.
     */
    @Test
    public void aRecordFromAnotherPlatformIsNotCompared() throws IOException
    {
        Path pairs = Files.createTempFile("dump-info-formats", ".properties"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            DumpInfoProbe.rememberPair("file:e:/bases/one", "2.7", "8.3.27.2214", pairs); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertEquals("2.7", DumpInfoProbe.applicableFormat("file:e:/bases/one", pairs, //$NON-NLS-1$ //$NON-NLS-2$
                "8.3.27.2214")); //$NON-NLS-1$
            assertNull(DumpInfoProbe.applicableFormat("file:e:/bases/one", pairs, "8.3.24.1000")); //$NON-NLS-1$ //$NON-NLS-2$
            String reason = DumpInfoProbe.inapplicableReason("file:e:/bases/one", pairs, //$NON-NLS-1$
                "8.3.24.1000"); //$NON-NLS-1$
            assertTrue(reason.contains("not compared")); //$NON-NLS-1$
            assertTrue(reason.contains("8.3.27.2214")); //$NON-NLS-1$
            assertTrue(reason.contains("8.3.24.1000")); //$NON-NLS-1$

            DumpInfoProbe.rememberPair("file:e:/bases/legacy", "2.20", pairs); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("2.20", DumpInfoProbe.expectedFormat("file:e:/bases/legacy", pairs)); //$NON-NLS-1$ //$NON-NLS-2$
            assertNull("a record without a platform is not applied", //$NON-NLS-1$
                DumpInfoProbe.applicableFormat("file:e:/bases/legacy", pairs, "8.3.27.2214")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(DumpInfoProbe.inapplicableReason("file:e:/bases/legacy", pairs, //$NON-NLS-1$
                "8.3.27.2214").contains("without the platform")); //$NON-NLS-1$ //$NON-NLS-2$

            String described = DatabaseUpdater.describeDumpInfoFormatCheck(
                DumpInfoProbe.reading("file", "2.20", null, "8.3.27.2214", reason), false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(described.contains("not compared")); //$NON-NLS-1$
            assertTrue(described.contains("8.3.24.1000")); //$NON-NLS-1$
        }
        finally
        {
            Files.deleteIfExists(pairs);
        }
    }

    /**
     * A failed write leaves the previous records in place and no temporary file behind. Opening the
     * destination first would truncate it, and every base would lose its record. The replacing step
     * fails through {@link DumpInfoProbe#recordFileMove}, the same way on any file system.
     */
    @Test
    public void aFailedRecordWriteLeavesThePreviousRecords() throws IOException
    {
        Path pairs = Files.createTempFile("dump-info-formats", ".properties"); //$NON-NLS-1$ //$NON-NLS-2$
        DumpInfoProbe.RecordFileMove production = DumpInfoProbe.recordFileMove;
        try
        {
            DumpInfoProbe.rememberPair("file:e:/bases/one", "2.7", "8.3.27.2214", pairs); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            DumpInfoProbe.recordFileMove = (temporary, destination) -> {
                throw new IOException("the destination cannot be replaced"); //$NON-NLS-1$
            };
            try
            {
                DumpInfoProbe.rememberPair("file:e:/bases/two", "2.20", "8.3.27.2214", pairs); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                throw new AssertionError("a write that cannot replace the file must fail"); //$NON-NLS-1$
            }
            catch (IOException expected)
            {
                assertEquals("the destination cannot be replaced", expected.getMessage()); //$NON-NLS-1$
            }
            finally
            {
                DumpInfoProbe.recordFileMove = production;
            }
            assertEquals("2.7", DumpInfoProbe.expectedFormat("file:e:/bases/one", pairs)); //$NON-NLS-1$ //$NON-NLS-2$
            assertNull(DumpInfoProbe.expectedFormat("file:e:/bases/two", pairs)); //$NON-NLS-1$
            String prefix = pairs.getFileName().toString();
            try (Stream<Path> siblings = Files.list(pairs.getParent()))
            {
                assertEquals(List.of(), siblings.map(sibling -> sibling.getFileName().toString())
                    .filter(name -> name.startsWith(prefix) && name.endsWith(".tmp")) //$NON-NLS-1$
                    .collect(Collectors.toList()));
            }
        }
        finally
        {
            DumpInfoProbe.recordFileMove = production;
            Files.deleteIfExists(pairs);
            Files.deleteIfExists(pairs.resolveSibling(pairs.getFileName() + ".lock")); //$NON-NLS-1$
        }
    }

    /**
     * On Windows a record file that another handle holds locked cannot be replaced: the write fails
     * and the previous records stand. Elsewhere the lock is advisory and does not stop the move, so
     * the test runs on Windows only.
     */
    @Test
    public void aLockedRecordFileIsLeftAsItWasOnWindows() throws IOException
    {
        Assume.assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Path pairs = Files.createTempFile("dump-info-formats", ".properties"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            DumpInfoProbe.rememberPair("file:e:/bases/one", "2.7", "8.3.27.2214", pairs); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            // The destination is locked, so replacing it fails. Opening it for write would truncate
            // it first, and every base already in the file would lose its record.
            try (FileChannel channel = FileChannel.open(pairs, StandardOpenOption.READ,
                StandardOpenOption.WRITE))
            {
                FileLock lock = channel.lock();
                try
                {
                    DumpInfoProbe.rememberPair("file:e:/bases/two", "2.20", "8.3.27.2214", pairs); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    throw new AssertionError("a write that cannot replace the file must fail"); //$NON-NLS-1$
                }
                catch (IOException expected)
                {
                    // asserted after the lock is released: the locked file cannot be read either
                }
                finally
                {
                    lock.release();
                }
            }
            assertEquals("2.7", DumpInfoProbe.expectedFormat("file:e:/bases/one", pairs)); //$NON-NLS-1$ //$NON-NLS-2$
            assertNull(DumpInfoProbe.expectedFormat("file:e:/bases/two", pairs)); //$NON-NLS-1$
        }
        finally
        {
            Files.deleteIfExists(pairs);
            Files.deleteIfExists(pairs.resolveSibling(pairs.getFileName() + ".lock")); //$NON-NLS-1$
        }
    }

    /**
     * A launch-time update refuses a foreign format before the manager is asked, and asks the
     * manager once the formats match.
     */
    @Test
    public void aLaunchUpdateRefusesAForeignFormatBeforeTheManagerIsAsked()
    {
        RecordingApplications manager =
            new RecordingApplications(ApplicationUpdateState.UPDATED);
        IApplication application = new StubApplication("app-1"); //$NON-NLS-1$

        ApplicationUpdater.Result refused = ApplicationUpdater.updateIfNeeded(manager, application,
            foreignFile());
        assertEquals(ApplicationUpdater.Outcome.FAILED, refused.outcome);
        assertTrue(refused.errorMessage.contains("2.20")); //$NON-NLS-1$
        assertTrue(manager.calls.isEmpty());

        RecordingApplications matching =
            new RecordingApplications(ApplicationUpdateState.UPDATED);
        ApplicationUpdater.Result went = ApplicationUpdater.updateIfNeeded(matching, application,
            DumpInfoProbe.reading("file", "2.7", "2.7", "8.3.27.2214")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals(ApplicationUpdater.Outcome.ALREADY_UP_TO_DATE, went.outcome);
        assertEquals(List.of("getUpdateState"), matching.calls); //$NON-NLS-1$

        String debugRefusal = DebugSessionStarter.updateDatabase(manager, application, foreignFile());
        assertNotNull(debugRefusal);
        assertTrue(debugRefusal.contains("2.20")); //$NON-NLS-1$
        assertTrue("the debugger's update did not ask the manager", manager.calls.isEmpty()); //$NON-NLS-1$

        String debugWent = DebugSessionStarter.updateDatabase(matching, application,
            DumpInfoProbe.reading("file", "2.7", "2.7", "8.3.27.2214")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertNull(debugWent);
        assertEquals(List.of("getUpdateState", "getUpdateState"), matching.calls); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Two updates that differ only in the format override are two different intentions and never
     * share a run: the key they coalesce on carries the override, so a call that did not consent
     * to the override never joins a run that did, and the overriding call never inherits another
     * call's refusal.
     */
    @Test
    public void theFormatOverrideIsPartOfTheRunIdentity()
    {
        String plain = DatabaseUpdater.runKeyFor("proj", "app-1", false, true, false, false, false); //$NON-NLS-1$ //$NON-NLS-2$
        String overriding = DatabaseUpdater.runKeyFor("proj", "app-1", false, true, false, false, true); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotEquals("the override separates two otherwise identical runs", plain, overriding); //$NON-NLS-1$
        assertEquals("the same arguments still key the same run", plain, //$NON-NLS-1$
            DatabaseUpdater.runKeyFor("proj", "app-1", false, true, false, false, false)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Two rebuilds of DIFFERENT infobases write the one record file each under its own claim, so
     * the writes serialize: each record survives the other's write, and neither base silently
     * loses the format its next update is checked against.
     */
    @Test
    public void twoParallelRecordWritesBothSurvive() throws Exception
    {
        Path pairs = Files.createTempFile("dump-info-formats", ".properties"); //$NON-NLS-1$ //$NON-NLS-2$
        // Both writers pause between reading the current records and replacing the file - the
        // window in which an unsynchronized read-modify-write loses one of the two records.
        CountDownLatch bothInside = new CountDownLatch(2);
        DumpInfoProbe.betweenRecordReadAndWrite = () -> {
            bothInside.countDown();
            try
            {
                bothInside.await(2, TimeUnit.SECONDS);
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
            }
        };
        try
        {
            List<Throwable> failures = new ArrayList<>();
            Thread one = recordWriter(pairs, "file:e:/bases/one", "2.7", failures); //$NON-NLS-1$ //$NON-NLS-2$
            Thread two = recordWriter(pairs, "file:e:/bases/two", "2.20", failures); //$NON-NLS-1$ //$NON-NLS-2$
            one.start();
            two.start();
            one.join(30000);
            two.join(30000);
            assertFalse("the first writer is done", one.isAlive()); //$NON-NLS-1$
            assertFalse("the second writer is done", two.isAlive()); //$NON-NLS-1$
            assertTrue("no writer failed: " + failures, failures.isEmpty()); //$NON-NLS-1$
            assertEquals("2.7", DumpInfoProbe.expectedFormat("file:e:/bases/one", pairs)); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("2.20", DumpInfoProbe.expectedFormat("file:e:/bases/two", pairs)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            DumpInfoProbe.betweenRecordReadAndWrite = () -> {
                // nothing, as production keeps it
            };
            Files.deleteIfExists(pairs);
            Files.deleteIfExists(pairs.resolveSibling(pairs.getFileName() + ".lock")); //$NON-NLS-1$
        }
    }

    /** One parallel writer; its failure is collected for the assertion rather than lost. */
    private static Thread recordWriter(Path pairs, String identity, String format,
        List<Throwable> failures)
    {
        Thread thread = new Thread(() -> {
            try
            {
                DumpInfoProbe.rememberPair(identity, format, "8.3.27.2214", pairs); //$NON-NLS-1$
            }
            catch (Throwable failed)
            {
                synchronized (failures)
                {
                    failures.add(failed);
                }
            }
        });
        return thread;
    }

    /**
     * An application manager that records every call it receives. The recording is the point: the
     * assertions are over the whole list, so a gate that reaches for one more read of the
     * environment fails on the list rather than passing quietly.
     */
    private static final class RecordingApplications implements IApplicationManager
    {
        final List<String> calls = new ArrayList<>();

        private final ApplicationUpdateState state;

        RecordingApplications(ApplicationUpdateState state)
        {
            this.state = state;
        }

        @Override
        public ApplicationUpdateState getUpdateState(IApplication application)
        {
            calls.add("getUpdateState"); //$NON-NLS-1$
            return state;
        }

        @Override
        public IStatus check(IApplication application, ApplicationCheckUnknownStateTreatment treatment,
            ExecutionContext context, IProgressMonitor monitor)
        {
            calls.add("check"); //$NON-NLS-1$
            return Status.OK_STATUS;
        }

        @Override
        public ApplicationUpdateState update(IApplication application, ApplicationUpdateType type,
            ExecutionContext context, IProgressMonitor monitor)
        {
            calls.add("update"); //$NON-NLS-1$
            return state;
        }

        @Override
        public Optional<IApplication> getApplication(IProject project, String applicationId)
        {
            calls.add("getApplication"); //$NON-NLS-1$
            return Optional.empty();
        }

        @Override
        public List<IApplication> getApplications(IProject project)
        {
            calls.add("getApplications"); //$NON-NLS-1$
            return List.of();
        }

        @Override
        public Optional<IProject> getDefaultProject()
        {
            calls.add("getDefaultProject"); //$NON-NLS-1$
            return Optional.empty();
        }

        @Override
        public Optional<IApplication> getDefaultApplication(IProject project)
        {
            calls.add("getDefaultApplication"); //$NON-NLS-1$
            return Optional.empty();
        }

        @Override
        public Optional<IUrlAccess> getDefaultUrlAccess(IApplication application)
        {
            calls.add("getDefaultUrlAccess"); //$NON-NLS-1$
            return Optional.empty();
        }

        @Override
        public LifecycleState getLifecycleState(com.e1c.g5.dt.applications.ILifecycleAware lifecycleAware)
        {
            calls.add("getLifecycleState"); //$NON-NLS-1$
            return null;
        }

        @Override
        public List<IApplicationType> getApplicationTypes()
        {
            calls.add("getApplicationTypes"); //$NON-NLS-1$
            return List.of();
        }

        @Override
        public List<IApplicationArtifact> getApplicationArtifacts(Object owner)
        {
            calls.add("getApplicationArtifacts"); //$NON-NLS-1$
            return List.of();
        }

        @Override
        public List<IUrlAccess> getUrlAccesses(IApplication application)
        {
            calls.add("getUrlAccesses"); //$NON-NLS-1$
            return List.of();
        }

        @Override
        public void setDefaultApplication(IProject project, IApplication application)
        {
            calls.add("setDefaultApplication"); //$NON-NLS-1$
        }

        @Override
        public void setDefaultUrlAccess(IApplication application, IUrlAccess urlAccess)
        {
            calls.add("setDefaultUrlAccess"); //$NON-NLS-1$
        }

        @Override
        public void prepare(IApplication application, String mode, ExecutionContext context,
            IProgressMonitor monitor)
        {
            calls.add("prepare"); //$NON-NLS-1$
        }

        @Override
        public void cleanup(IApplication application, ExecutionContext context, IProgressMonitor monitor)
        {
            calls.add("cleanup"); //$NON-NLS-1$
        }

        @Override
        public Optional<Process> start(IApplication application, ExecutionContext context,
            IProgressMonitor monitor)
        {
            calls.add("start"); //$NON-NLS-1$
            return Optional.empty();
        }

        @Override
        public void addAppllicationListener(IApplicationListener listener)
        {
            calls.add("addAppllicationListener"); //$NON-NLS-1$
        }

        @Override
        public void removeAppllicationListener(IApplicationListener listener)
        {
            calls.add("removeAppllicationListener"); //$NON-NLS-1$
        }

        @Override
        public void delete(IApplication application, boolean deleteFiles)
        {
            calls.add("delete"); //$NON-NLS-1$
        }

        @Override
        public Optional<IApplication> findApplicationByInfobase(
            com._1c.g5.v8.dt.platform.services.model.InfobaseReference infobase)
        {
            calls.add("findApplicationByInfobase"); //$NON-NLS-1$
            return Optional.empty();
        }

        @Override
        public Optional<IApplication> findApplicationByInfobaseAndProject(
            com._1c.g5.v8.dt.platform.services.model.InfobaseReference infobase, IProject project)
        {
            calls.add("findApplicationByInfobaseAndProject"); //$NON-NLS-1$
            return Optional.empty();
        }

        @Override
        public String suggestNewApplicationName(String projectName, String applicationType)
        {
            calls.add("suggestNewApplicationName"); //$NON-NLS-1$
            return ""; //$NON-NLS-1$
        }
    }

    /**
     * An application with an id and nothing else, as in the probe tests.
     */
    private static final class StubApplication implements IApplication
    {
        private final String id;

        StubApplication(String id)
        {
            this.id = id;
        }

        @Override
        public String getId()
        {
            return id;
        }

        @Override
        public String getName()
        {
            return id;
        }

        @Override
        public IProject getProject()
        {
            return null;
        }

        @Override
        public IApplicationType getType()
        {
            return null;
        }

        @Override
        public List<IApplicationArtifact> getArtifacts()
        {
            return List.of();
        }

        @Override
        public Optional<String> getRequiredVersion()
        {
            return Optional.empty();
        }

        @Override
        public IApplication getApplication()
        {
            return this;
        }

        @Override
        public <T> T getAdapter(Class<T> adapter)
        {
            return null;
        }
    }
}
