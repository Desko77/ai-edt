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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

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

        String rememberedPair;

        String droppedHolder;

        @Override
        public String infobaseIdentity()
        {
            asked.add("identity"); //$NON-NLS-1$
            return refuseIdentity ? null : "file:///infobase"; //$NON-NLS-1$
        }

        @Override
        public boolean takeLock()
        {
            asked.add("takeLock"); //$NON-NLS-1$
            return !refuseLock;
        }

        @Override
        public void releaseLock()
        {
            asked.add("releaseLock"); //$NON-NLS-1$
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
            return true;
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
        public void rememberPair(String infobaseIdentity, String format)
        {
            asked.add("rememberPair"); //$NON-NLS-1$
            rememberedPair = infobaseIdentity + " -> " + format; //$NON-NLS-1$
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
        assertEquals("file:///infobase -> 2.7", io.rememberedPair); //$NON-NLS-1$
        assertEquals("file:///infobase -> 2.7", outcome.pairRemembered); //$NON-NLS-1$
        assertNotNull(outcome.holderRefresh);
        assertNull(outcome.reconnectError);
        // The work's own steps land in the record as the work runs; the handshake's three step
        // names are appended by the handshake when it returns. So the file's steps precede the
        // release/work/reconnect triple - the run order is release, work (which appends its
        // detail), reconnect, and that is what the record shows.
        assertEquals(Arrays.asList("identity", "lock", "readOld", "tempDir", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "dumpInfoOnly", "verify", "backup", "swap", "dropHolder", "rememberPair", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "release", "work", "reconnect", "cleanup", "unlock"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            outcome.sequence);
        assertFalse("the temporary dump directory is deleted", Files.exists(io.tempDirs.get(0))); //$NON-NLS-1$
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
        assertEquals("file:///infobase -> 2.7", outcome.pairRemembered); //$NON-NLS-1$
    }

    /**
     * A quick run that FAILS falls back the same way, and both facts survive: the failure of the
     * quick run in the path, the run that actually produced the file in the stored content.
     */
    @Test
    public void aFailedQuickRunFallsBackAndNamesBothRuns() throws IOException
    {
        Path stored = storedOld();
        StandIn io = standIn();
        io.quick = dir -> {
            throw new IllegalStateException("the Designer exited with code 1"); //$NON-NLS-1$
        };

        Outcome outcome = run(io);

        assertTrue(outcome.ok);
        assertEquals(fullDump(), new String(Files.readAllBytes(stored), StandardCharsets.UTF_8));
        assertTrue(io.asked.contains("dumpFull")); //$NON-NLS-1$
        assertTrue(outcome.rebuildPath.contains("fullHierarchical")); //$NON-NLS-1$
        assertTrue("the reason is the quick run's own failure", //$NON-NLS-1$
            outcome.rebuildPath.contains("the quick dump failed")); //$NON-NLS-1$
        assertTrue(outcome.rebuildPath.contains("exited with code 1")); //$NON-NLS-1$
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
        assertTrue(io.asked.contains("reconnect")); //$NON-NLS-1$
        assertTrue(outcome.error.contains("abandoned")); //$NON-NLS-1$
        assertTrue(outcome.error.contains("finishes on its own")); //$NON-NLS-1$
        assertTrue(new String(Files.readAllBytes(stored), StandardCharsets.UTF_8)
            .contains("\"2.20\"")); //$NON-NLS-1$
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
        assertTrue(outcome.error.contains("Another AI-EDT instance")); //$NON-NLS-1$
    }

    // ---- the Designer run fails ---------------------------------------------------------------

    /**
     * Both runs failing leaves the stored file untouched and the infobase reconnected - the swap
     * sits after the verification, so a run that did not produce a file cannot have replaced one.
     * The answer names the full dump's failure, and the path names the quick run's.
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
        assertTrue(new String(Files.readAllBytes(stored), StandardCharsets.UTF_8)
            .contains("\"2.20\"")); //$NON-NLS-1$
        assertTrue("the infobase is taken back whatever the run did", //$NON-NLS-1$
            io.asked.contains("reconnect")); //$NON-NLS-1$
        assertTrue(outcome.error.contains("exited with code 1")); //$NON-NLS-1$
        assertTrue("the path keeps the quick run's own failure", //$NON-NLS-1$
            outcome.rebuildPath.contains("the quick dump failed")); //$NON-NLS-1$
        assertNull(outcome.reconnectError);
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
}
