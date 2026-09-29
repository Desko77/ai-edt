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
        public void importFrom(Path source, BooleanSupplier cancelled)
        {
            ran.add("load"); //$NON-NLS-1$
            loadedFrom = source;
            filesWhenLoaded = namesIn(source.getParent());
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
