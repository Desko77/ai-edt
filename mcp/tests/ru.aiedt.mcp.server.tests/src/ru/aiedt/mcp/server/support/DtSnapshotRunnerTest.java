/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.DtSnapshotRunner.IoFactory;
import ru.aiedt.mcp.server.support.DtSnapshotRunner.IoResolution;
import ru.aiedt.mcp.server.support.DtSnapshotRunner.SnapshotIo;

/**
 * The two infobase snapshot operations: the arguments they refuse on, the backup a load takes
 * before it touches the infobase, the Pending envelope a run outliving its soft wait answers with,
 * and the cancel a runKey accepts.
 * <p>
 * Everything here runs against a stand-in environment, so no infobase, no launcher and no EDT are
 * involved: what is under test is the runner's own behaviour - what it refuses before the work
 * starts, in which order it writes and loads, and what it answers.
 * </p>
 */
public class DtSnapshotRunnerTest
{
    /** The tool name a run started from a test is recorded under. */
    private static final String STARTER = "infobase_admin"; //$NON-NLS-1$

    private Path work;

    @Before
    public void aDirectoryOfItsOwn() throws IOException
    {
        work = Files.createTempDirectory("aiedt-dt-snapshot-test"); //$NON-NLS-1$
    }

    @After
    public void theDirectoryGoes()
    {
        try
        {
            deleteTree(work);
        }
        catch (IOException ignored)
        {
            // best effort
        }
    }

    /**
     * A dump with no project named and a dump with no path are both refused, and neither reaches
     * the environment: nothing is resolved, nothing is claimed, nothing is written.
     */
    @Test
    public void aDumpWithoutAProjectOrAPathIsRefusedBeforeAnythingRuns()
    {
        ProbeIo io = new ProbeIo();
        Path file = work.resolve("dump.dt"); //$NON-NLS-1$

        JsonObject noProject =
            answer(DtSnapshotRunner.dispatchExport(call(), null, null, file.toString(), null, false,
                factory(io), STARTER));

        assertFalse(noProject.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("export_database_snapshot", noProject.get("operation").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(noProject.get("error").getAsString(), //$NON-NLS-1$
            noProject.get("error").getAsString().contains("projectName is required")); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject noPath =
            answer(DtSnapshotRunner.dispatchExport(call(), "Проект", null, null, null, false, //$NON-NLS-1$
                factory(io), STARTER));

        assertFalse(noPath.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(noPath.get("error").getAsString(), //$NON-NLS-1$
            noPath.get("error").getAsString().contains("path is required for export_database_snapshot")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("nothing reached the environment", io.ran.isEmpty()); //$NON-NLS-1$
        assertEquals("the infobase was never claimed", 0, io.locks); //$NON-NLS-1$
    }

    /**
     * A destination that already holds a file is refused, and the file standing there is left
     * untouched: the dump never runs, so a file this run did not write cannot be read as its
     * result.
     */
    @Test
    public void anOccupiedDestinationIsRefusedAndTheFileThereIsLeftAlone() throws IOException
    {
        ProbeIo io = new ProbeIo();
        Path file = work.resolve("dump.dt"); //$NON-NLS-1$
        Files.write(file, "someone else's dump".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        JsonObject answer = answer(DtSnapshotRunner.dispatchExport(call(), "Проект", null, //$NON-NLS-1$
            file.toString(), null, false, factory(io), STARTER));

        assertFalse(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer.get("error").getAsString(), //$NON-NLS-1$
            answer.get("error").getAsString().contains("A file already exists at")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer.get("alreadyExists").getAsBoolean()); //$NON-NLS-1$
        assertTrue("the dump never ran", io.ran.isEmpty()); //$NON-NLS-1$
        assertEquals("someone else's dump", //$NON-NLS-1$
            new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    /**
     * A dump that runs answers with the operation, the placed path, its size and the infobase it
     * came from, and the file is really there afterwards.
     */
    @Test
    public void aDumpPlacesTheFileAndAnswersWithItsSize() throws IOException
    {
        ProbeIo io = new ProbeIo();
        io.dumpBytes = 48L;
        Path file = work.resolve("dump.dt"); //$NON-NLS-1$

        JsonObject answer = answer(DtSnapshotRunner.dispatchExport(call(), "Проект", null, //$NON-NLS-1$
            file.toString(), null, false, factory(io), STARTER));

        assertTrue(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("Exported", answer.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("export_database_snapshot", answer.get("operation").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(file.toString(), answer.get("path").getAsString()); //$NON-NLS-1$
        assertEquals(48L, answer.get("sizeBytes").getAsLong()); //$NON-NLS-1$
        assertEquals("probe-infobase", answer.get("infobase").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("dump"), io.ran); //$NON-NLS-1$
        assertTrue(Files.isRegularFile(file));
        assertEquals("the infobase was claimed once and released", 1, io.locks); //$NON-NLS-1$
        assertEquals(1, io.releases);
    }

    /**
     * A load pointed at a file that is not there is refused before the infobase is claimed: a load
     * replaces what the base holds, so it is refused rather than started against nothing.
     */
    @Test
    public void aLoadOfAFileThatIsNotThereIsRefusedBeforeTheInfobaseIsClaimed()
    {
        ProbeIo io = new ProbeIo();
        Path missing = work.resolve("absent.dt"); //$NON-NLS-1$

        JsonObject answer = answer(DtSnapshotRunner.dispatchRestore(call(), "Проект", null, //$NON-NLS-1$
            missing.toString(), null, null, false, factory(io), STARTER));

        assertFalse(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer.get("error").getAsString(), //$NON-NLS-1$
            answer.get("error").getAsString().startsWith("The restore was not started: no file was found at")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer.get("inputMissing").getAsBoolean()); //$NON-NLS-1$
        assertEquals("the infobase was never claimed", 0, io.locks); //$NON-NLS-1$
        assertTrue(io.ran.isEmpty());
    }

    /**
     * A load takes the backup first: the dump that produces it finishes, the backup is on disk and
     * non-empty before the load begins, and the answer names it with its size.
     */
    @Test
    public void theBackupIsOnDiskBeforeTheLoadTouchesTheInfobase() throws IOException
    {
        ProbeIo io = new ProbeIo();
        io.dumpBytes = 64L;
        Path source = work.resolve("full.dt"); //$NON-NLS-1$
        Files.write(source, "a whole infobase".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        Path backup = work.resolve("before-the-load.dt"); //$NON-NLS-1$

        JsonObject answer = answer(DtSnapshotRunner.dispatchRestore(call(), "Проект", null, //$NON-NLS-1$
            source.toString(), backup.toString(), null, false, factory(io), STARTER));

        assertTrue(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("Loaded", answer.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("dump", "load"), io.ran); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("the load ran", io.filesWhenLoaded); //$NON-NLS-1$
        assertTrue("the backup was on disk before the load began", //$NON-NLS-1$
            io.filesWhenLoaded.contains(backup.getFileName().toString()));
        assertEquals("the load read the file the caller named", source, io.loadedFrom); //$NON-NLS-1$
        assertEquals(backup.toString(), answer.get("backup").getAsString()); //$NON-NLS-1$
        assertEquals(64L, answer.get("backupSizeBytes").getAsLong()); //$NON-NLS-1$
        assertEquals(Files.size(source), answer.get("sizeBytes").getAsLong()); //$NON-NLS-1$
        assertEquals(64L, Files.size(backup));
    }

    /**
     * A backup that does not materialize ends the run: the load never starts, so an infobase whose
     * previous contents exist nowhere is not produced.
     */
    @Test
    public void aLoadIsNotStartedWhenTheBackupDoesNotMaterialize() throws IOException
    {
        ProbeIo io = new ProbeIo();
        io.failTheDump = true;
        Path source = work.resolve("full.dt"); //$NON-NLS-1$
        Files.write(source, "a whole infobase".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        Path backup = work.resolve("before-the-load.dt"); //$NON-NLS-1$

        JsonObject answer = answer(DtSnapshotRunner.dispatchRestore(call(), "Проект", null, //$NON-NLS-1$
            source.toString(), backup.toString(), null, false, factory(io), STARTER));

        assertFalse(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer.get("error").getAsString(), //$NON-NLS-1$
            answer.get("error").getAsString().startsWith("The restore was not started: the backup of the infobase's")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer.get("error").getAsString(), //$NON-NLS-1$
            answer.get("error").getAsString().contains("probe dump failure")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer.get("thickClientFailed").getAsBoolean()); //$NON-NLS-1$
        assertEquals("the load never ran", List.of("dump"), io.ran); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("no backup stands at the path", Files.exists(backup)); //$NON-NLS-1$
        assertEquals(1, io.releases);
    }

    /**
     * A run that outlives its soft wait answers with the Pending envelope, and the runKey it carries
     * resumes to the result that the run came to in the meantime.
     */
    @Test
    public void aRunOutlivingTheSoftWaitAnswersPendingAndTheRunKeyResumes() throws IOException
    {
        ProbeIo io = new ProbeIo();
        io.dumpBytes = 32L;
        io.holdMillis = 6_500L;
        Path file = work.resolve("slow.dt"); //$NON-NLS-1$

        JsonObject pending = answer(DtSnapshotRunner.dispatchExport(call(), "Проект", null, //$NON-NLS-1$
            file.toString(), null, false, factory(io), STARTER));

        assertTrue(pending.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("Pending", pending.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the envelope is marked for the router", //$NON-NLS-1$
            pending.get(PendingEnvelope.MARK).getAsBoolean());
        String runKey = pending.get("runKey").getAsString(); //$NON-NLS-1$
        assertTrue(runKey, runKey.startsWith("dt-")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the dump had not finished when the envelope was answered", Files.exists(file)); //$NON-NLS-1$

        JsonObject done = answer(DtSnapshotRunner.dispatchExport(call(), "Проект", null, //$NON-NLS-1$
            file.toString(), runKey, false, factory(io), STARTER));

        assertTrue(done.toString(), done.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("Exported", done.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(32L, done.get("sizeBytes").getAsLong()); //$NON-NLS-1$
        assertEquals(file.toString(), done.get("path").getAsString()); //$NON-NLS-1$
        assertTrue("the file the resumed call reports is there", Files.isRegularFile(file)); //$NON-NLS-1$
        assertTrue("a retrieved run is no longer tracked", //$NON-NLS-1$
            PendingWorkRegistry.SNAPSHOT.get(runKey) == null);
    }

    /**
     * A call carrying a runKey and cancel=true stops tracking the run and says so, the runKey is not
     * found afterwards, and the domain declares the stopper a cancel reaches.
     */
    @Test
    public void aCancelThroughTheRunKeyStopsTrackingTheRun() throws IOException
    {
        ProbeIo io = new ProbeIo();
        io.holdMillis = 6_500L;
        Path file = work.resolve("cancel-me.dt"); //$NON-NLS-1$

        JsonObject pending = answer(DtSnapshotRunner.dispatchExport(call(), "Проект", null, //$NON-NLS-1$
            file.toString(), null, false, factory(io), STARTER));
        String runKey = pending.get("runKey").getAsString(); //$NON-NLS-1$

        JsonObject stopped = answer(DtSnapshotRunner.dispatchExport(call(), "Проект", null, //$NON-NLS-1$
            file.toString(), runKey, true, factory(io), STARTER));

        assertTrue(stopped.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(stopped.get("cancelled").getAsBoolean()); //$NON-NLS-1$
        assertTrue(stopped.get("note").getAsString(), //$NON-NLS-1$
            stopped.get("note").getAsString().startsWith("Stopped tracking this snapshot.")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("the snapshot domain has a stopper a cancel reaches", //$NON-NLS-1$
            PendingWorkRegistry.SNAPSHOT.stopper());

        JsonObject after = answer(DtSnapshotRunner.dispatchExport(call(), "Проект", null, //$NON-NLS-1$
            file.toString(), runKey, false, factory(io), STARTER));

        assertFalse(after.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(after.get("error").getAsString(), //$NON-NLS-1$
            after.get("error").getAsString().contains("runKey not found")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A cancel for a key no run holds changes nothing and says the key was not found, and the
     * stopper itself answers NOTHING_TO_STOP when there is no live run under the key.
     */
    @Test
    public void aCancelForAKeyNobodyHoldsAnswersWithTheKeyNotFound()
    {
        ProbeIo io = new ProbeIo();
        String runKey = "dt-" + System.nanoTime(); //$NON-NLS-1$

        JsonObject stopped = answer(DtSnapshotRunner.dispatchExport(call(), "Проект", null, //$NON-NLS-1$
            work.resolve("nothing.dt").toString(), runKey, true, factory(io), STARTER)); //$NON-NLS-1$

        assertTrue(stopped.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(stopped.get("cancelled").getAsBoolean()); //$NON-NLS-1$
        assertTrue(stopped.get("note").getAsString(), //$NON-NLS-1$
            stopped.get("note").getAsString().contains("runKey was not found")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(PendingWorkRegistry.StopOutcome.NOTHING_TO_STOP,
            DtSnapshotRunner.stopTheRun(runKey));
        assertTrue(io.ran.isEmpty());
    }

    /**
     * An environment that could not be resolved is answered with its own text and its own kind
     * rather than run against something else.
     */
    @Test
    public void anUnresolvedEnvironmentIsAnsweredWithItsOwnRefusal()
    {
        Path file = work.resolve("dump.dt"); //$NON-NLS-1$
        IoFactory refused = (projectName, applicationId, operation, runKey, live, cancelled) ->
            IoResolution.refused("No project named 'x' is open in this workspace.", //$NON-NLS-1$
                ErrorTags.PROJECT_NOT_FOUND.wire());

        JsonObject answer = answer(DtSnapshotRunner.dispatchExport(call(), "x", null, //$NON-NLS-1$
            file.toString(), null, false, refused, STARTER));

        assertFalse(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer.get("error").getAsString(), //$NON-NLS-1$
            answer.get("error").getAsString().contains("No project named")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer.get("projectNotFound").getAsBoolean()); //$NON-NLS-1$
        assertEquals("export_database_snapshot", answer.get("operation").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(Files.exists(file));
    }

    /**
     * An infobase somebody else holds is answered as busy, naming who holds it, and nothing is
     * dumped: the claim is refused, so the release that belongs to a granted claim is not called
     * either.
     */
    @Test
    public void anInfobaseHeldBySomebodyElseIsAnsweredBusyAndNothingRuns()
    {
        ProbeIo io = new ProbeIo();
        io.lockRefusal = "The infobase is held by another operation: update_database."; //$NON-NLS-1$
        Path file = work.resolve("dump.dt"); //$NON-NLS-1$

        JsonObject answer = answer(DtSnapshotRunner.dispatchExport(call(), "Проект", null, //$NON-NLS-1$
            file.toString(), null, false, factory(io), STARTER));

        assertFalse(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer.get("error").getAsString(), //$NON-NLS-1$
            answer.get("error").getAsString().contains("update_database")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer.get("busy").getAsBoolean()); //$NON-NLS-1$
        assertEquals("the claim was asked once", 1, io.locks); //$NON-NLS-1$
        assertEquals("a refused claim is not released", 0, io.releases); //$NON-NLS-1$
        assertTrue(io.ran.isEmpty());
        assertFalse(Files.exists(file));
    }

    /** The call arguments every test sends: the shortest soft wait the operations accept. */
    private static Map<String, String> call()
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("timeoutSeconds", "5"); //$NON-NLS-1$ //$NON-NLS-2$
        return params;
    }

    /** The factory that hands the runner the stand-in environment a test built. */
    private static IoFactory factory(SnapshotIo io)
    {
        return (projectName, applicationId, operation, runKey, live, cancelled) ->
            IoResolution.of(io, "probe-infobase"); //$NON-NLS-1$
    }

    private static JsonObject answer(String json)
    {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static void deleteTree(Path dir) throws IOException
    {
        if (dir == null || !Files.exists(dir))
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try
                {
                    Files.deleteIfExists(p);
                }
                catch (IOException ignored)
                {
                    // best effort cleanup
                }
            });
        }
    }

    /**
     * A load that finishes marks the store's dump-info record with the file and the time, and the
     * answer names that mark and the step that rewrites the copy. A load that fails marks nothing.
     */
    @Test
    public void aFinishedLoadMarksTheStoredCopyAndAFailedOneDoesNot() throws IOException
    {
        ProbeIo io = new ProbeIo();
        io.dumpBytes = 32L;
        Path source = work.resolve("replaced.dt"); //$NON-NLS-1$
        Files.write(source, "a whole infobase".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        Path backup = work.resolve("before.dt"); //$NON-NLS-1$
        io.recordFile = work.resolve(InfobaseOutsideChange.FILE_NAME);
        InfobaseOutsideChange.of("file:e:/bases/demo", "content-then", 4).writeTo(io.recordFile); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject answer = answer(DtSnapshotRunner.dispatchRestore(call(), "Project", null, //$NON-NLS-1$
            source.toString(), backup.toString(), null, false, factory(io), STARTER));

        assertTrue(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("Loaded", answer.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the load asked for the copy to be marked", io.marked); //$NON-NLS-1$
        assertTrue(answer.get("copyMarked").getAsBoolean()); //$NON-NLS-1$
        assertEquals(DtSnapshotRunner.REBUILD_COPY_STEP, answer.get("nextStep").getAsString()); //$NON-NLS-1$
        assertTrue(answer.get("infobaseChangeCheck").getAsString().contains(source.toString())); //$NON-NLS-1$
        InfobaseOutsideChange recorded = InfobaseOutsideChange.read(io.recordFile);
        assertTrue(recorded.replacedByLoad());
        assertEquals(source.toString(), recorded.replacedBy);
        assertNotNull(recorded.replacedAt);
        assertEquals("the fingerprint of the copy is kept", "content-then", recorded.fingerprint); //$NON-NLS-1$ //$NON-NLS-2$

        ProbeIo failed = new ProbeIo();
        failed.dumpBytes = 32L;
        failed.failTheLoad = true;
        failed.recordFile = work.resolve("failed-record.properties"); //$NON-NLS-1$
        InfobaseOutsideChange.of("file:e:/bases/demo", "content-then", 4).writeTo(failed.recordFile); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject refused = answer(DtSnapshotRunner.dispatchRestore(call(), "Project", null, //$NON-NLS-1$
            source.toString(), work.resolve("before-failed.dt").toString(), null, false, //$NON-NLS-1$
            factory(failed), STARTER));
        assertFalse(refused.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse("a load that did not finish does not mark the copy", failed.marked); //$NON-NLS-1$
        assertFalse(InfobaseOutsideChange.read(failed.recordFile).replacedByLoad());
    }

    /**
     * A load whose store record could not be written still reports the load, and says the copy was
     * not marked.
     */
    @Test
    public void aLoadWhoseRecordCannotBeWrittenSaysSo() throws IOException
    {
        ProbeIo io = new ProbeIo();
        io.dumpBytes = 16L;
        io.markAnswer = "disk full"; //$NON-NLS-1$
        Path source = work.resolve("replaced.dt"); //$NON-NLS-1$
        Files.write(source, "a whole infobase".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        JsonObject answer = answer(DtSnapshotRunner.dispatchRestore(call(), "Project", null, //$NON-NLS-1$
            source.toString(), work.resolve("before.dt").toString(), null, false, factory(io), //$NON-NLS-1$
            STARTER));

        assertTrue(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(answer.get("copyMarked").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer.get("infobaseChangeCheck").getAsString().contains("disk full")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(answer.has("nextStep")); //$NON-NLS-1$
    }

    /**
     * The environment the runner is exercised against: a dump that writes bytes and a load that
     * records what it read, both recorded in the order they ran. No infobase is touched.
     */
    private static final class ProbeIo implements SnapshotIo
    {
        /** What ran, in order: {@code dump} and {@code load}. */
        final List<String> ran = new ArrayList<>();

        /** How many times the claim was asked for. */
        int locks;

        /** How many times the claim was released. */
        int releases;

        /** The refusal the claim answers with, or {@code null} for a granted claim. */
        String lockRefusal;

        /** How many bytes a dump writes. */
        long dumpBytes = 16L;

        /** How long the dump takes before it writes; {@code 0} writes at once. */
        long holdMillis;

        /** When set, the next dump throws instead of writing - for a load, that is the backup. */
        boolean failTheDump;

        /** The file the load read. */
        Path loadedFrom;

        /** The names in the source's directory at the moment the load began. */
        List<String> filesWhenLoaded;

        /** When set, the load throws instead of recording what it read. */
        boolean failTheLoad;

        /** The store record a successful load marks, or {@code null} when this probe keeps none. */
        Path recordFile;

        /** What {@link #markLoaded} answers, when set, instead of writing {@link #recordFile}. */
        String markAnswer;

        /** Whether a successful load asked for the store record to be marked. */
        boolean marked;

        @Override
        public String infobaseIdentity()
        {
            return "file:///probe-infobase"; //$NON-NLS-1$
        }

        @Override
        public String takeLock()
        {
            locks++;
            return lockRefusal;
        }

        @Override
        public void releaseLock()
        {
            releases++;
        }

        @Override
        public void exportTo(Path target, BooleanSupplier cancelled) throws Exception
        {
            ran.add("dump"); //$NON-NLS-1$
            if (failTheDump)
            {
                failTheDump = false;
                throw new IOException("probe dump failure"); //$NON-NLS-1$
            }
            if (holdMillis > 0L)
            {
                Thread.sleep(holdMillis);
            }
            Files.createDirectories(target.getParent());
            Files.write(target, new byte[(int)dumpBytes]);
        }

        @Override
        public void importFrom(Path source, BooleanSupplier cancelled) throws IOException
        {
            ran.add("load"); //$NON-NLS-1$
            if (failTheLoad)
            {
                throw new IOException("probe load failure"); //$NON-NLS-1$
            }
            loadedFrom = source;
            filesWhenLoaded = namesIn(source.getParent());
        }

        @Override
        public String markLoaded(Path source, String when)
        {
            marked = true;
            if (markAnswer != null)
            {
                return markAnswer;
            }
            if (recordFile == null)
            {
                return DtSnapshotRunner.NO_STORE_RECORD;
            }
            try
            {
                InfobaseOutsideChange.markLoaded(recordFile, source.toString(), when);
                return null;
            }
            catch (IOException failed)
            {
                return failed.toString();
            }
        }

        private static List<String> namesIn(Path dir)
        {
            List<String> names = new ArrayList<>();
            if (dir == null || !Files.isDirectory(dir))
            {
                return names;
            }
            try (Stream<Path> entries = Files.list(dir))
            {
                entries.forEach(p -> names.add(p.getFileName().toString()));
            }
            catch (IOException unreadable)
            {
                // an empty listing is a fair answer for a directory that could not be read
            }
            return names;
        }
    }
}
