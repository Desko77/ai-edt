/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IThickClientLauncher;

import ru.aiedt.mcp.server.support.DumpInfoRebuilder.Outcome;
import ru.aiedt.mcp.server.support.DumpInfoRebuilder.RebuildIo;

/**
 * The dump-info rebuild runs its steps in an order, and every failure of that order leaves the
 * stored file and the infobase connection in the state the answer claims.
 *
 * <p>The two Designer runs are stand-ins that write a fresh dump-info file - or refuse to - and the
 * environment records every step it was asked for. What each test asserts is the SEQUENCE plus the
 * stored file's content: a rebuild that reports "untouched" over a replaced file, or a
 * "replaced" over a lost one, would be worse than a failure that says nothing. The quick run and
 * the full dump write files that differ in their record count, so which of the two produced the
 * stored file is readable from the file itself and not only from the answer's path.</p>
 */
public class TheRebuildRunsItsStepsInOrderTest
{
    private Path store;

    private Path tempRoot;

    @Before
    public void makeStore() throws IOException
    {
        tempRoot = Files.createTempDirectory("rebuild-test-root"); //$NON-NLS-1$
        store = Files.createDirectories(tempRoot.resolve("ss").resolve("uuid")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @After
    public void dropTemp() throws IOException
    {
        deleteTree(tempRoot);
    }

    private static void deleteTree(Path dir) throws IOException
    {
        if (dir == null || !Files.exists(dir))
        {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir))
        {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
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

    /** A stored file carrying format 2.20 - the file a rebuild is asked to move off. */
    private Path storedOld() throws IOException
    {
        Path stored = store.resolve(DumpInfoProbe.FILE_NAME);
        Files.write(stored, dumpInfo("2.20", "<Metadata name=\"Old\"/>").getBytes( //$NON-NLS-1$ //$NON-NLS-2$
            StandardCharsets.UTF_8));
        return stored;
    }

    /** A fresh dump-info the quick Designer run writes - format 2.7, two records. */
    private static String platformDump()
    {
        return dumpInfo("2.7", //$NON-NLS-1$
            "<Metadata name=\"Configuration\"/><Metadata name=\"Catalog.Banks\"/>"); //$NON-NLS-1$
    }

    /**
     * What the full hierarchical dump leaves behind in its directory - the same format and one
     * record more, so a test can tell the two runs' files apart.
     */
    private static String fullDump()
    {
        return dumpInfo("2.7", //$NON-NLS-1$
            "<Metadata name=\"Configuration\"/><Metadata name=\"Catalog.Banks\"/>" //$NON-NLS-1$
                + "<Metadata name=\"Document.Order\"/>"); //$NON-NLS-1$
    }

    private static String dumpInfo(String version, String records)
    {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
            + "<ConfigDumpInfo xmlns=\"...\" version=\"" + version + "\">" + records //$NON-NLS-1$ //$NON-NLS-2$
            + "</ConfigDumpInfo>"; //$NON-NLS-1$
    }

    /**
     * The environment, with both Designer runs succeeding unless the test hands in different ones.
     * Every step is recorded, in the order it was asked for.
     */
    private static final class StandIn implements RebuildIo
    {
        final List<String> asked = new ArrayList<>();

        boolean refuseIdentity;

        boolean refuseLock;

        /** Set once a claim is taken, until {@link #releaseLock()}. */
        boolean lockHeld;

        /**
         * The refusal {@link #takeLock()} returns when the base is already held or {@link #refuseLock}
         * is set. The rebuild's answer must carry this sentence, holder and all.
         */
        String lockRefusal = "Another AI-EDT instance is working on this infobase. held by pid 4242"; //$NON-NLS-1$

        boolean failRemember;

        /** The quick run, which writes the dump-info alone - the primary path. */
        DesignerRun quick = dir -> {
            Files.write(dir.resolve(DumpInfoProbe.FILE_NAME), platformDump().getBytes(
                StandardCharsets.UTF_8));
            return null;
        };

        /** The full hierarchical dump - the fallback, and the one asked for when the quick left none. */
        DesignerRun full = dir -> {
            Files.write(dir.resolve(DumpInfoProbe.FILE_NAME), fullDump().getBytes(
                StandardCharsets.UTF_8));
            return null;
        };

        Exception reconnectFailure;

        /** Whether the infobase was connected when the rebuild released it; a release that reports
         * false owes no reconnection. */
        boolean connectedAtStart = true;

        String rememberedPair;

        String droppedHolder;

        @Override
        public String infobaseIdentity()
        {
            asked.add("identity"); //$NON-NLS-1$
            return refuseIdentity ? null : "file:///infobase"; //$NON-NLS-1$
        }

        @Override
        public String takeLock()
        {
            asked.add("takeLock"); //$NON-NLS-1$
            if (lockHeld)
            {
                return "This AI-EDT instance is holding the infobase itself, rebuild_dump_info, " //$NON-NLS-1$
                    + "since the Designer run that is still going"; //$NON-NLS-1$
            }
            if (refuseLock)
            {
                return lockRefusal;
            }
            lockHeld = true;
            return null;
        }

        @Override
        public void releaseLock()
        {
            asked.add("releaseLock"); //$NON-NLS-1$
            lockHeld = false;
        }

        @Override
        public Path storeDirectory() throws IOException
        {
            return theStore;
        }

        Path theStore;

        final List<Path> tempDirs = new ArrayList<>();

        @Override
        public boolean releaseInfobase()
        {
            asked.add("release"); //$NON-NLS-1$
            return connectedAtStart;
        }

        @Override
        public Path runDumpInfoOnly(Path tempDir) throws Exception
        {
            asked.add("dumpInfoOnly"); //$NON-NLS-1$
            tempDirs.add(tempDir);
            return quick.dumpInto(tempDir);
        }

        @Override
        public Path runFullDump(Path tempDir) throws Exception
        {
            asked.add("dumpFull"); //$NON-NLS-1$
            tempDirs.add(tempDir);
            return full.dumpInto(tempDir);
        }

        @Override
        public void reconnectInfobase()
        {
            asked.add("reconnect"); //$NON-NLS-1$
            if (reconnectFailure != null)
            {
                throw new RuntimeException(reconnectFailure.getMessage(), reconnectFailure);
            }
        }

        @Override
        public String dropCachedHolder()
        {
            asked.add("dropCachedHolder"); //$NON-NLS-1$
            droppedHolder = "ok (dropped 1 cached holder(s), reloads from disk)"; //$NON-NLS-1$
            return droppedHolder;
        }

        @Override
        public void rememberPair(String infobaseIdentity, String format, String platformVersion)
            throws IOException
        {
            asked.add("rememberPair"); //$NON-NLS-1$
            if (failRemember)
            {
                throw new IOException("disk full"); //$NON-NLS-1$
            }
            rememberedPair = infobaseIdentity + " -> " + format //$NON-NLS-1$
                + (platformVersion == null ? "" : " on " + platformVersion); //$NON-NLS-1$ //$NON-NLS-2$
        }

        @Override
        public void deleteTempDir(Path dir)
        {
            asked.add("deleteTempDir"); //$NON-NLS-1$
            try
            {
                deleteTree(dir);
            }
            catch (IOException ignored)
            {
                // best effort, as the contract says
            }
        }
    }

    /** The Designer run as the stand-in performs it. */
    interface DesignerRun
    {
        /**
         * @param dir the directory to dump into
         * @return the fresh dump-info file, or {@code null} for the conventional name
         * @throws Exception when the run fails
         */
        Path dumpInto(Path dir) throws Exception;
    }

    private StandIn standIn()
    {
        StandIn io = new StandIn();
        io.theStore = store;
        return io;
    }

    private Outcome run(StandIn io)
    {
        return DumpInfoRebuilder.performRebuild(io, "stamp", "8.3.27.2214"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- success ---------------------------------------------------------------------------

    /**
     * The happy path: identify, lock, temp directory, release, dump, verify, backup, swap, drop the
     * cached holder, record the pair, reconnect, clean up, unlock. The stored file ends up carrying
     * the platform's dump, the previous one lies beside it, the pair is remembered.
     */
    @Test
    public void theRebuildReplacesTheFileAndCleansUpAfterItself() throws IOException
    {
        Path stored = storedOld();
        StandIn io = standIn();

        Outcome outcome = run(io);

        assertTrue(outcome.ok);
        assertEquals("replaced", outcome.fileState); //$NON-NLS-1$
        assertEquals("2.20", outcome.oldFormat); //$NON-NLS-1$
        assertEquals("2.7", outcome.newFormat); //$NON-NLS-1$
        assertEquals("records of the fresh dump are counted", 2, outcome.records); //$NON-NLS-1$
        assertEquals(platformDump(), new String(Files.readAllBytes(stored), StandardCharsets.UTF_8));
        Path backup = store.resolve(DumpInfoProbe.FILE_NAME + ".before-rebuild-stamp"); //$NON-NLS-1$
        assertTrue("the previous file is kept beside the store", Files.isRegularFile(backup)); //$NON-NLS-1$
        assertEquals(outcome.backupPath, backup.toString());
        assertEquals("the quick run answered, so the full dump is never asked", //$NON-NLS-1$
            "configDumpInfoOnly", outcome.rebuildPath); //$NON-NLS-1$
        assertFalse(io.asked.contains("dumpFull")); //$NON-NLS-1$
        assertEquals("file:///infobase -> 2.7 on 8.3.27.2214", io.rememberedPair); //$NON-NLS-1$
        assertEquals("file:///infobase -> 2.7 on 8.3.27.2214", outcome.pairRemembered); //$NON-NLS-1$
        assertTrue(DumpInfoRebuilder.successMessage(outcome).contains("compares against it")); //$NON-NLS-1$
        assertNotNull(outcome.holderRefresh);
        assertNull(outcome.reconnectError);
        // Steps are written as they run: release, then the dump, then reconnect, then the
        // temporary directory is deleted and the lock released.
        assertEquals(Arrays.asList("identity", "lock", "readOld", "tempDir", "release", "work", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "dumpInfoOnly", "verify", "backup", "swap", "dropHolder", "rememberPair", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "reconnect", "cleanup", "unlock"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            outcome.sequence);
        assertFalse("the temporary dump directory is deleted", Files.exists(io.tempDirs.get(0))); //$NON-NLS-1$
        assertFalse("the lock is released", io.lockHeld); //$NON-NLS-1$
    }

    /**
     * The fallback: a quick run that leaves no file sends the rebuild through the full hierarchical
     * dump, and the stored file is that dump's - the answer names the path taken and why.
     */
    @Test
    public void aQuickRunThatLeavesNoFileFallsBackToTheFullDump() throws IOException
    {
        Path stored = storedOld();
        StandIn io = standIn();
        io.quick = dir -> null;

        Outcome outcome = run(io);

        assertTrue(outcome.ok);
        assertEquals("replaced", outcome.fileState); //$NON-NLS-1$
        assertEquals("records of the full dump are counted", 3, outcome.records); //$NON-NLS-1$
        assertEquals(fullDump(), new String(Files.readAllBytes(stored), StandardCharsets.UTF_8));
        assertTrue(io.asked.contains("dumpFull")); //$NON-NLS-1$
        assertTrue("the answer names the full dump and the reason for it", //$NON-NLS-1$
            outcome.rebuildPath.startsWith("fullHierarchical")); //$NON-NLS-1$
        assertTrue("the reason names what the quick run left", //$NON-NLS-1$
            outcome.rebuildPath.contains("left no " + DumpInfoProbe.FILE_NAME)); //$NON-NLS-1$
        assertEquals("file:///infobase -> 2.7 on 8.3.27.2214", outcome.pairRemembered); //$NON-NLS-1$
        assertOrder(outcome.sequence, "release", "dumpInfoOnly", "dumpFull", "reconnect", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "cleanup", "unlock"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A quick run that FAILS is refused with that error. The full dump is not started: a Configurator
     * failure would otherwise keep the infobase released and walk the whole configuration.
     */
    @Test
    public void aFailedQuickRunIsRefusedAndDoesNotStartTheFullDump() throws IOException
    {
        Path stored = storedOld();
        StandIn io = standIn();
        io.quick = dir -> {
            throw new IllegalStateException("the Designer exited with code 1"); //$NON-NLS-1$
        };

        Outcome outcome = run(io);

        assertFalse(outcome.ok);
        assertEquals("untouched", outcome.fileState); //$NON-NLS-1$
        assertFalse("a failed quick run is not followed by the full dump", //$NON-NLS-1$
            io.asked.contains("dumpFull")); //$NON-NLS-1$
        assertTrue(outcome.error.contains("exited with code 1")); //$NON-NLS-1$
        assertTrue(new String(Files.readAllBytes(stored), StandardCharsets.UTF_8)
            .contains("\"2.20\"")); //$NON-NLS-1$
        assertOrder(outcome.sequence, "release", "dumpInfoOnly", "reconnect", "cleanup", "unlock"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertFalse(outcome.sequence.contains("dumpFull")); //$NON-NLS-1$
    }

    /**
     * A quick run that throws after leaving a readable file uses that file. The exception is not a
     * reason to start the full dump, and the file is not ignored.
     */
    @Test
    public void aQuickRunThatThrowsAfterLeavingAFileUsesThatFile() throws IOException
    {
        Path stored = storedOld();
        StandIn io = standIn();
        io.quick = dir -> {
            Files.write(dir.resolve(DumpInfoProbe.FILE_NAME), platformDump().getBytes(
                StandardCharsets.UTF_8));
            throw new IllegalStateException("the log could not be read"); //$NON-NLS-1$
        };

        Outcome outcome = run(io);

        assertTrue(outcome.ok);
        assertEquals(platformDump(), new String(Files.readAllBytes(stored), StandardCharsets.UTF_8));
        assertFalse(io.asked.contains("dumpFull")); //$NON-NLS-1$
        assertTrue(outcome.rebuildPath.contains("configDumpInfoOnly")); //$NON-NLS-1$
        assertTrue(outcome.rebuildPath.contains("the log could not be read")); //$NON-NLS-1$
    }

    /**
     * An abandoned quick run is NOT followed by the full dump: the platform process is still
     * running and holding the base, so the rebuild reports what it knows rather than starting a
     * second run.
     */
    @Test
    public void anAbandonedQuickRunDoesNotFallBackToTheFullDump() throws IOException
    {
        Path stored = storedOld();
        StandIn io = standIn();
        io.quick = dir -> {
            throw new DumpInfoRebuilder.Abandoned("the Designer dump did not finish within 600s"); //$NON-NLS-1$
        };

        Outcome outcome = run(io);

        assertFalse(outcome.ok);
        assertEquals("untouched", outcome.fileState); //$NON-NLS-1$
        assertFalse("no second platform run is started after one was abandoned", //$NON-NLS-1$
            io.asked.contains("dumpFull")); //$NON-NLS-1$
        assertFalse("the infobase stays disconnected while the Designer is still running", //$NON-NLS-1$
            io.asked.contains("reconnect")); //$NON-NLS-1$
        assertFalse("the lock stays held", io.asked.contains("releaseLock")); //$NON-NLS-1$
        assertFalse("the temporary directory is not deleted under the writer", //$NON-NLS-1$
            io.asked.contains("deleteTempDir")); //$NON-NLS-1$
        assertTrue("the temporary directory is still there", Files.exists(io.tempDirs.get(0))); //$NON-NLS-1$
        assertTrue(outcome.error.contains("abandoned")); //$NON-NLS-1$
        assertTrue(outcome.error.contains("finishes on its own")); //$NON-NLS-1$
        assertTrue(outcome.error.contains("lock stays held")); //$NON-NLS-1$
        assertTrue(outcome.designerStillRunning);
        assertTrue(outcome.lockHeldForProcess);
        assertNotNull(outcome.tempDirLeft);
        assertTrue(outcome.reconnectError.contains(outcome.tempDirLeft));
        assertTrue(new String(Files.readAllBytes(stored), StandardCharsets.UTF_8)
            .contains("\"2.20\"")); //$NON-NLS-1$
        assertFalse(outcome.sequence.contains("cleanup")); //$NON-NLS-1$
        assertFalse(outcome.sequence.contains("unlock")); //$NON-NLS-1$
        assertOrder(outcome.sequence, "release", "dumpInfoOnly"); //$NON-NLS-1$ //$NON-NLS-2$

        Outcome second = run(io);
        assertFalse("a second rebuild is refused while the first Designer is still running", //$NON-NLS-1$
            second.ok);
        assertTrue(second.error.contains("still going")); //$NON-NLS-1$
    }

    // ---- fail-closed on an unidentified base ------------------------------------------------

    /**
     * A base this cannot identify is refused BEFORE anything is claimed or released: proceeding
     * unclaimed would race a neighbouring EDT, and releasing first would leave EDT disconnected
     * for a run that never happens.
     */
    @Test
    public void anUnidentifiedInfobaseIsRefusedBeforeAnythingIsTouched() throws IOException
    {
        Path stored = storedOld();
        StandIn io = standIn();
        io.refuseIdentity = true;

        Outcome outcome = run(io);

        assertFalse(outcome.ok);
        assertEquals(Arrays.asList("identity"), io.asked); //$NON-NLS-1$
        assertTrue("the stored file is untouched", //$NON-NLS-1$
            new String(Files.readAllBytes(stored), StandardCharsets.UTF_8).contains("\"2.20\"")); //$NON-NLS-1$
        assertTrue(outcome.error.contains("refused before")); //$NON-NLS-1$
    }

    // ---- the lock is refused ----------------------------------------------------------------

    /**
     * A base another instance holds is refused the same way: nothing released, nothing dumped.
     */
    @Test
    public void aHeldInfobaseIsRefusedWithoutReleasingAnything() throws IOException
    {
        storedOld();
        StandIn io = standIn();
        io.refuseLock = true;

        Outcome outcome = run(io);

        assertFalse(outcome.ok);
        assertEquals(Arrays.asList("identity", "takeLock"), io.asked); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(outcome.error.contains("pid 4242")); //$NON-NLS-1$
        assertEquals(io.lockRefusal, outcome.error);
    }

    // ---- the Designer run fails ---------------------------------------------------------------

    /**
     * A quick run that fails leaves the stored file untouched and the infobase reconnected. The full
     * dump is not asked: the quick run's own error is the refusal.
     */
    @Test
    public void aFailedDesignerRunLeavesTheFileUntouchedAndReconnects() throws IOException
    {
        Path stored = storedOld();
        StandIn io = standIn();
        io.quick = dir -> {
            throw new IllegalStateException("the quick Designer run exited with code 1"); //$NON-NLS-1$
        };
        io.full = dir -> {
            throw new IllegalStateException("the Designer exited with code 1"); //$NON-NLS-1$
        };

        Outcome outcome = run(io);

        assertFalse(outcome.ok);
        assertEquals("untouched", outcome.fileState); //$NON-NLS-1$
        assertFalse("the full dump is not the answer to a failed quick run", //$NON-NLS-1$
            io.asked.contains("dumpFull")); //$NON-NLS-1$
        assertTrue(new String(Files.readAllBytes(stored), StandardCharsets.UTF_8)
            .contains("\"2.20\"")); //$NON-NLS-1$
        assertTrue("the infobase is taken back whatever the run did", //$NON-NLS-1$
            io.asked.contains("reconnect")); //$NON-NLS-1$
        assertTrue(outcome.error.contains("exited with code 1")); //$NON-NLS-1$
        assertTrue(outcome.error.contains("not touched")); //$NON-NLS-1$
        assertNull(outcome.reconnectError);
        assertOrder(outcome.sequence, "release", "dumpInfoOnly", "reconnect", "cleanup", "unlock"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    /**
     * A dump without a readable ConfigDumpInfo - no file, or one without the version attribute -
     * is the platform's answer, not a reason to swap: the stored file stays as it was. A file that
     * is there but does not read is refused rather than followed by the full dump, because the
     * question the fallback answers is whether the quick run left a file at all.
     */
    @Test
    public void aDumpWithoutAReadableDumpInfoIsRefused() throws IOException
    {
        Path stored = storedOld();
        StandIn io = standIn();
        io.quick = dir -> {
            Files.write(dir.resolve(DumpInfoProbe.FILE_NAME),
                "<Configuration>not a dump info</Configuration>".getBytes( //$NON-NLS-1$
                    StandardCharsets.UTF_8));
            return null;
        };
        io.full = io.quick;

        Outcome outcome = run(io);

        assertFalse(outcome.ok);
        assertEquals("untouched", outcome.fileState); //$NON-NLS-1$
        assertTrue(new String(Files.readAllBytes(stored), StandardCharsets.UTF_8)
            .contains("\"2.20\"")); //$NON-NLS-1$
        assertTrue(outcome.error.contains("no readable")); //$NON-NLS-1$
        assertFalse("the pair is not recorded for a dump that did not verify", //$NON-NLS-1$
            io.asked.contains("rememberPair")); //$NON-NLS-1$
        assertFalse("a file that is there is not followed by the full dump", //$NON-NLS-1$
            io.asked.contains("dumpFull")); //$NON-NLS-1$
        assertOrder(outcome.sequence, "release", "dumpInfoOnly", "verify", "reconnect", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "cleanup", "unlock"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- a reconnection that fails is named beside the swap ----------------------------------

    /**
     * A reconnection failure is its own fact: the file WAS replaced and the answer says both, so a
     * caller repairing the connection does not redo a rebuild that already landed.
     */
    @Test
    public void aReconnectionFailureIsNamedOnItsOwnBesideTheReplacedFile() throws IOException
    {
        Path stored = storedOld();
        StandIn io = standIn();
        io.reconnectFailure = new IllegalStateException("connectInfobase: infobase is locked"); //$NON-NLS-1$

        Outcome outcome = run(io);

        assertTrue("the swap landed", outcome.ok); //$NON-NLS-1$
        assertEquals("replaced", outcome.fileState); //$NON-NLS-1$
        assertEquals(platformDump(), new String(Files.readAllBytes(stored), StandardCharsets.UTF_8));
        assertNotNull(outcome.reconnectError);
        assertTrue(outcome.reconnectError.contains("could not take the infobase back")); //$NON-NLS-1$
        assertTrue(outcome.reconnectError.contains("reconnect it by hand")); //$NON-NLS-1$
        assertOrder(outcome.sequence, "release", "dumpInfoOnly", "reconnect", "cleanup", "unlock"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    // ---- the swap itself ---------------------------------------------------------------------

    /**
     * The swap stages the fresh file beside the target and moves it over; the backup lies beside
     * the store; nothing temporary is left in the directory.
     */
    @Test
    public void theSwapStagesBesideTheTargetAndMoves() throws IOException
    {
        Path stored = storedOld();
        Path fresh = tempRoot.resolve("fresh.xml"); //$NON-NLS-1$
        Files.write(fresh, platformDump().getBytes(StandardCharsets.UTF_8));

        String backup = DumpInfoRebuilder.swapStored(stored, fresh, "stamp"); //$NON-NLS-1$

        assertNotNull(backup);
        assertEquals(platformDump(), new String(Files.readAllBytes(stored), StandardCharsets.UTF_8));
        assertEquals("only the stored file and its backup remain in the store", 2, //$NON-NLS-1$
            Files.list(store).count());
    }

    /**
     * A swap whose move fails is rolled back from the backup: the store holds the previous file,
     * and the failure carried up is the rolled-back kind, not a bare IOException the caller would
     * answer as "untouched".
     */
    @Test
    public void aSwapThatFailsIsRolledBackFromTheBackup() throws IOException
    {
        Path stored = storedOld();
        Path fresh = tempRoot.resolve("fresh.xml"); //$NON-NLS-1$
        Files.write(fresh, platformDump().getBytes(StandardCharsets.UTF_8));

        try
        {
            DumpInfoRebuilder.swapStored(stored, fresh, "stamp", (from, to) -> { //$NON-NLS-1$
                throw new IOException("the file is held open by another process"); //$NON-NLS-1$
            });
            throw new AssertionError("the failing move must not be reported as success"); //$NON-NLS-1$
        }
        catch (DumpInfoRebuilder.SwapRolledBack rolled)
        {
            assertEquals("the store holds the previous file again", //$NON-NLS-1$
                dumpInfo("2.20", "<Metadata name=\"Old\"/>"), //$NON-NLS-1$ //$NON-NLS-2$
                new String(Files.readAllBytes(stored), StandardCharsets.UTF_8));
            assertTrue(Files.isRegularFile(java.nio.file.Paths.get(rolled.backupPath)));
        }
    }

    /**
     * A swap with no previous file needs no rollback: the failure is the bare move failure, and
     * nothing of the store's is left behind.
     */
    @Test
    public void aSwapWithoutAPreviousFileFailsBare() throws IOException
    {
        Path stored = store.resolve(DumpInfoProbe.FILE_NAME);
        Path fresh = tempRoot.resolve("fresh.xml"); //$NON-NLS-1$
        Files.write(fresh, platformDump().getBytes(StandardCharsets.UTF_8));

        try
        {
            DumpInfoRebuilder.swapStored(stored, fresh, "stamp", (from, to) -> { //$NON-NLS-1$
                throw new IOException("the store is read-only"); //$NON-NLS-1$
            });
            throw new AssertionError("the failing move must not be reported as success"); //$NON-NLS-1$
        }
        catch (DumpInfoRebuilder.SwapRolledBack mistakenRollback)
        {
            throw new AssertionError("there is no backup, so there is nothing to roll back to", //$NON-NLS-1$
                mistakenRollback);
        }
        catch (IOException expected)
        {
            assertFalse("nothing was written into the store", Files.exists(stored)); //$NON-NLS-1$
        }
    }

    /**
     * A record that cannot be written does not turn the rebuild into a failure of the swap, and the
     * success sentence does not claim the next update will compare.
     */
    @Test
    public void aRecordThatCannotBeWrittenDoesNotClaimTheNextUpdateWillCompare() throws IOException
    {
        storedOld();
        StandIn io = standIn();
        io.failRemember = true;

        Outcome outcome = run(io);

        assertTrue(outcome.ok);
        assertEquals("replaced", outcome.fileState); //$NON-NLS-1$
        assertTrue(outcome.pairRemembered.startsWith("NOT recorded")); //$NON-NLS-1$
        assertTrue(outcome.pairRemembered.contains("will not compare")); //$NON-NLS-1$
        assertTrue(outcome.pairRemembered.contains("disk full")); //$NON-NLS-1$
        assertFalse(DumpInfoRebuilder.successMessage(outcome).contains("compares against")); //$NON-NLS-1$
    }

    /**
     * A swap whose move fails and whose rollback fails too is not "untouched": the stored file is
     * in an unknown state, and the answer names the backup.
     */
    @Test
    public void aSwapWhoseMoveAndRollbackBothFailNamesAnUnknownState() throws IOException
    {
        storedOld();
        StandIn io = standIn();
        DumpInfoRebuilder.FileMover previous = DumpInfoRebuilder.swapMover;
        try
        {
            DumpInfoRebuilder.swapMover = (from, to) -> {
                Files.deleteIfExists(to.resolveSibling(
                    DumpInfoProbe.FILE_NAME + ".before-rebuild-stamp")); //$NON-NLS-1$
                throw new IOException("the file is held open by another process"); //$NON-NLS-1$
            };

            Outcome outcome = run(io);

            assertFalse(outcome.ok);
            assertEquals("unknown", outcome.fileState); //$NON-NLS-1$
            assertTrue(outcome.error.contains("unknown state")); //$NON-NLS-1$
            assertTrue(outcome.error.contains("before-rebuild-stamp")); //$NON-NLS-1$
            assertFalse(outcome.error.contains("not touched")); //$NON-NLS-1$
        }
        finally
        {
            DumpInfoRebuilder.swapMover = previous;
        }
    }

    /**
     * A Designer call that is still running when the wait gives up keeps the cleanup for after the
     * call returns. The cleanup does not run while the call is in progress.
     */
    @Test
    public void anAbandonedRunCleansUpOnlyAfterTheCallReturns() throws Exception
    {
        CountDownLatch hold = new CountDownLatch(1);
        AtomicBoolean cleaned = new AtomicBoolean(false);
        CountDownLatch cleanedAt = new CountDownLatch(1);
        try
        {
            DumpInfoRebuilder.underTimeout("the dump-info-only Designer run", 200L, () -> { //$NON-NLS-1$
                while (hold.getCount() > 0)
                {
                    try
                    {
                        hold.await();
                    }
                    catch (InterruptedException ignored)
                    {
                        Thread.interrupted();
                    }
                }
                return null;
            });
            fail("a run that outlasts its budget is abandoned"); //$NON-NLS-1$
        }
        catch (DumpInfoRebuilder.Abandoned abandoned)
        {
            try
            {
                assertTrue(abandoned.processStillRunning());
                abandoned.whenFinished(() -> {
                    cleaned.set(true);
                    cleanedAt.countDown();
                });
                assertFalse("cleanup waits until the call returns", cleaned.get()); //$NON-NLS-1$
                hold.countDown();
                assertTrue("cleanup runs once the call has returned", //$NON-NLS-1$
                    cleanedAt.await(5, TimeUnit.SECONDS));
                assertTrue(cleaned.get());
            }
            finally
            {
                hold.countDown();
            }
        }
    }

    /**
     * A wait cut short from OUTSIDE - the waiting thread is interrupted while the Designer call is
     * still running - is the timeout's own situation: the call cannot be reached to be stopped, so
     * the run is answered as abandoned, the cleanup waits for the call's own return, and the
     * thread's interrupt flag is put back for its caller's own interruption policy.
     */
    @Test
    public void anInterruptedWaitIsAnAbandonedRunAndRestoresTheFlag() throws Exception
    {
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch callStarted = new CountDownLatch(1);
        AtomicBoolean cleaned = new AtomicBoolean(false);
        CountDownLatch cleanedAt = new CountDownLatch(1);
        AtomicReference<DumpInfoRebuilder.Abandoned> caught = new AtomicReference<>();
        AtomicBoolean flagRestored = new AtomicBoolean(false);
        AtomicReference<Exception> unexpected = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try
            {
                DumpInfoRebuilder.underTimeout("the dump-info-only Designer run", 60000L, () -> { //$NON-NLS-1$
                    callStarted.countDown();
                    while (hold.getCount() > 0)
                    {
                        try
                        {
                            hold.await();
                        }
                        catch (InterruptedException ignored)
                        {
                            Thread.interrupted();
                        }
                    }
                    return null;
                });
            }
            catch (DumpInfoRebuilder.Abandoned abandoned)
            {
                flagRestored.set(Thread.currentThread().isInterrupted());
                caught.set(abandoned);
                abandoned.whenFinished(() -> {
                    cleaned.set(true);
                    cleanedAt.countDown();
                });
            }
            catch (Exception other)
            {
                unexpected.set(other);
            }
        });
        waiter.start();
        try
        {
            assertTrue("the Designer call is running", callStarted.await(5, TimeUnit.SECONDS)); //$NON-NLS-1$
            waiter.interrupt();
            waiter.join(10000);
            assertFalse("the waiter is done", waiter.isAlive()); //$NON-NLS-1$
            assertNull("an interrupt is not an ordinary failure: " + unexpected.get(), //$NON-NLS-1$
                unexpected.get());
            DumpInfoRebuilder.Abandoned abandoned = caught.get();
            assertNotNull("the interrupt is answered as an abandoned run", abandoned); //$NON-NLS-1$
            assertTrue("the call was still running", abandoned.processStillRunning()); //$NON-NLS-1$
            assertTrue("the interrupt flag is restored for the caller's own policy", //$NON-NLS-1$
                flagRestored.get());
            assertFalse("the cleanup waits for the call's return", cleaned.get()); //$NON-NLS-1$
            hold.countDown();
            assertTrue("the cleanup runs once the call has returned", //$NON-NLS-1$
                cleanedAt.await(5, TimeUnit.SECONDS));
            assertTrue(cleaned.get());
        }
        finally
        {
            hold.countDown();
        }
    }

    /**
     * An abandonment declared before the worker crossed the launch boundary starts no Designer
     * and defers no cleanup: the boundary is claimed by the abandonment while the worker still
     * waits for the per-infobase lock, so the run is answered as not running - there is nothing
     * to wait for - and the launcher is never called.
     */
    @Test
    public void anAbandonmentBeforeTheBoundaryStartsNoDesignerAndDefersNoCleanup() throws Exception
    {
        List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
        ReentrantLock lock = new ReentrantLock();
        BmInfobaseExtensionHelper.LauncherContext ctx =
            new BmInfobaseExtensionHelper.LauncherContext();
        ctx.lock = lock;
        CountDownLatch gate = new CountDownLatch(1);
        ctx.launcher = blockingFullDumpLauncher(order, gate);

        lock.lock();
        try
        {
            try
            {
                DumpInfoRebuilder.underTimeout("the Designer dump", 200L, //$NON-NLS-1$
                    () -> BmInfobaseExtensionHelper.runFullDumpUnderInfobaseLock(ctx,
                        java.nio.file.Paths.get("dump")), //$NON-NLS-1$
                    ctx.launchClaim);
                fail("a run that outlasts its budget is abandoned"); //$NON-NLS-1$
            }
            catch (DumpInfoRebuilder.Abandoned abandoned)
            {
                assertFalse("a launch prevented at the boundary is waited for by nothing", //$NON-NLS-1$
                    abandoned.processStillRunning());
            }
        }
        finally
        {
            lock.unlock();
            gate.countDown();
        }
        assertFalse("the launcher was never called", order.contains("held")); //$NON-NLS-1$
    }

    /**
     * The other order of the same crossing: the worker claims the boundary first - the launcher
     * call is running - and the abandonment waits it out, running the cleanup only after that
     * call returns on its own.
     */
    @Test
    public void aBoundaryTheWorkerClaimedFirstIsWaitedOut() throws Exception
    {
        List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
        ReentrantLock lock = new ReentrantLock();
        BmInfobaseExtensionHelper.LauncherContext ctx =
            new BmInfobaseExtensionHelper.LauncherContext();
        ctx.lock = lock;
        CountDownLatch gate = new CountDownLatch(1);
        ctx.launcher = blockingFullDumpLauncher(order, gate);
        AtomicBoolean cleaned = new AtomicBoolean(false);
        CountDownLatch cleanedAt = new CountDownLatch(1);

        try
        {
            DumpInfoRebuilder.underTimeout("the Designer dump", 200L, //$NON-NLS-1$
                () -> BmInfobaseExtensionHelper.runFullDumpUnderInfobaseLock(ctx,
                    java.nio.file.Paths.get("dump")), //$NON-NLS-1$
                ctx.launchClaim);
            fail("a Designer that outlasts its budget is abandoned"); //$NON-NLS-1$
        }
        catch (DumpInfoRebuilder.Abandoned abandoned)
        {
            assertTrue("the launch the worker crossed into is running", //$NON-NLS-1$
                abandoned.processStillRunning());
            assertTrue("the launcher was called", order.contains("held")); //$NON-NLS-1$
            abandoned.whenFinished(() -> {
                cleaned.set(true);
                cleanedAt.countDown();
            });
            assertFalse("the cleanup waits for the running call", cleaned.get()); //$NON-NLS-1$
        }
        finally
        {
            gate.countDown();
        }
        assertTrue("the cleanup runs once the call has returned", //$NON-NLS-1$
            cleanedAt.await(5, TimeUnit.SECONDS));
        assertTrue(cleaned.get());
    }

    /**
     * The cleanup side of a prevented launch: an abandonment whose run started no Designer is
     * settled in the rebuild's own steps - reconnect, delete, release - with nothing deferred.
     */
    @Test
    public void anAbandonedRunThatStartedNoDesignerCleansUpAtOnce() throws IOException
    {
        storedOld();
        StandIn io = standIn();
        io.quick = dir -> {
            throw new DumpInfoRebuilder.Abandoned("the Designer dump did not finish within 600s", //$NON-NLS-1$
                false, null);
        };

        Outcome outcome = run(io);

        assertFalse(outcome.designerStillRunning);
        assertFalse(outcome.lockHeldForProcess);
        assertNull(outcome.tempDirLeft);
        assertOrder(outcome.sequence, "release", "work", "reconnect", "cleanup", "unlock"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertTrue("the temporary directory is deleted at once", io.asked.contains("deleteTempDir")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the claim is released at once", io.asked.contains("releaseLock")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(io.lockHeld);
    }

    /**
     * A launcher whose {@code exportFullXmlFromInfobase} records that it runs and then waits for
     * the gate, ignoring interrupts the way a spawned Designer process ignores the worker's own
     * interrupt.
     */
    private static IThickClientLauncher blockingFullDumpLauncher(List<String> order,
        CountDownLatch gate)
    {
        return (IThickClientLauncher)Proxy.newProxyInstance(
            IThickClientLauncher.class.getClassLoader(),
            new Class<?>[] { IThickClientLauncher.class }, (proxy, method, args) -> {
                if (!method.getName().equals("exportFullXmlFromInfobase")) //$NON-NLS-1$
                {
                    return null;
                }
                order.add("held"); //$NON-NLS-1$
                while (gate.getCount() > 0)
                {
                    try
                    {
                        gate.await();
                    }
                    catch (InterruptedException ignored)
                    {
                        Thread.interrupted();
                    }
                }
                return null;
            });
    }

    /**
     * An abandoned run whose base was ALREADY disconnected leaves it that way: the deferred
     * cleanup reconnects only a base the release disconnected. Reconnecting a base the rebuild
     * never disconnected would change the connection state the user had left.
     */
    @Test
    public void anAbandonedRunDoesNotReconnectABaseItNeverDisconnected() throws IOException
    {
        storedOld();
        StandIn io = standIn();
        io.connectedAtStart = false;
        AtomicReference<Runnable> deferredCleanup = new AtomicReference<>();
        io.quick = dir -> {
            throw new DumpInfoRebuilder.Abandoned("the Designer dump did not finish within 600s", //$NON-NLS-1$
                true, deferredCleanup::set);
        };

        Outcome outcome = run(io);

        assertTrue(outcome.designerStillRunning);
        assertFalse(io.asked.contains("reconnect")); //$NON-NLS-1$
        Runnable cleanup = deferredCleanup.get();
        assertNotNull("the cleanup is deferred until the call returns", cleanup); //$NON-NLS-1$
        cleanup.run();
        assertTrue("the temporary directory is deleted once the call has returned", //$NON-NLS-1$
            io.asked.contains("deleteTempDir")); //$NON-NLS-1$
        assertTrue("the claim is released once the call has returned", //$NON-NLS-1$
            io.asked.contains("releaseLock")); //$NON-NLS-1$
        assertFalse("a base the rebuild never disconnected is not reconnected", //$NON-NLS-1$
            io.asked.contains("reconnect")); //$NON-NLS-1$
    }

    /**
     * The other half of the same rule: when the release DID disconnect the base, the deferred
     * cleanup takes it back once the Designer call has returned.
     */
    @Test
    public void anAbandonedRunReconnectsAfterwardsTheBaseItDisconnected() throws IOException
    {
        storedOld();
        StandIn io = standIn();
        AtomicReference<Runnable> deferredCleanup = new AtomicReference<>();
        io.quick = dir -> {
            throw new DumpInfoRebuilder.Abandoned("the Designer dump did not finish within 600s", //$NON-NLS-1$
                true, deferredCleanup::set);
        };

        Outcome outcome = run(io);

        assertTrue(outcome.designerStillRunning);
        assertFalse(io.asked.contains("reconnect")); //$NON-NLS-1$
        deferredCleanup.get().run();
        assertTrue("the base the rebuild disconnected is taken back once the call has returned", //$NON-NLS-1$
            io.asked.contains("reconnect")); //$NON-NLS-1$
        assertTrue(io.asked.contains("releaseLock")); //$NON-NLS-1$
    }

    /** Steps occur in this order; steps between them are allowed. */
    private static void assertOrder(List<String> sequence, String... steps)
    {
        int at = -1;
        for (String step : steps)
        {
            int found = sequence.indexOf(step);
            assertTrue(step + " is missing from " + sequence, found >= 0); //$NON-NLS-1$
            assertTrue(step + " is out of order in " + sequence, found > at); //$NON-NLS-1$
            at = found;
        }
    }
}
