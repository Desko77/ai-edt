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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.support.DumpInfoRebuilder.Abandoned;
import ru.aiedt.mcp.server.support.InfobaseObjectsExporter.ExportIo;
import ru.aiedt.mcp.server.support.InfobaseObjectsExporter.Outcome;

/**
 * The infobase-objects export runs its steps in an order, and every failure of that order leaves
 * the destination, the service directory and the infobase connection in the state the answer
 * claims.
 *
 * <p>The Designer run is a stand-in that writes files into the service directory - or refuses to -
 * and the environment records every step it was asked for. What each test asserts is the SEQUENCE
 * plus what the destination holds afterwards: an export that reports success over an empty
 * destination, or leaves a service directory behind while claiming it cleaned up, would be worse
 * than a failure that says nothing.</p>
 */
public class TheExportRunsItsStepsInOrderTest
{
    private Path root;

    private Path outputPath;

    @Before
    public void makeRoot() throws IOException
    {
        root = Files.createTempDirectory("export-objects-test");
        outputPath = root.resolve("result");
    }

    @After
    public void dropTemp() throws IOException
    {
        deleteTree(root);
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

    /** The address list a call sends: one catalog, one form, one common form. */
    private static List<String> addresses()
    {
        return Arrays.asList("Catalog.Банки", "Catalog.Банки.Form.ФормаЭлемента", //$NON-NLS-1$ //$NON-NLS-2$
            "CommonForm.ОбщаяФорма"); //$NON-NLS-1$
    }

    /** The Designer run as this stand-in performs it; may throw, abandonment included. */
    @FunctionalInterface
    interface DesignerStep
    {
        /**
         * @param dir the service directory
         * @param listFile the list file
         * @throws Exception when the run fails or is abandoned
         */
        void run(Path dir, Path listFile) throws Exception;
    }

    /**
     * The environment, with the Designer run succeeding unless the test hands in a different one.
     * Every step is recorded, in the order it was asked for.
     */
    private class StandIn implements ExportIo
    {
        final List<String> asked = new ArrayList<>();

        boolean refuseIdentity;

        boolean refuseLock;

        String lockRefusal = "Another AI-EDT instance is working on this infobase. held by pid 777"; //$NON-NLS-1$

        boolean lockHeld;

        /** The destination is vacant until this many checks have happened; then it is full. */
        int vacantForFirstChecks = Integer.MAX_VALUE;

        int vacantChecks;

        boolean failMove;

        Exception reconnectFailure;

        boolean connectedAtStart = true;

        /** What the Designer run leaves in the service directory; may be empty for a failure. */
        List<String> writtenFiles = Arrays.asList("Catalogs/Банки/Ext/ObjectModule.bsl", //$NON-NLS-1$
            "Catalogs/Банки/Forms/ФормаЭлемента/Ext/Form.bin"); //$NON-NLS-1$

        /** Whether the Designer run succeeds; false leaves whatever it wrote and throws. */
        boolean designerOk = true;

        /** The Designer run as this stand-in performs it; overrides everything above. */
        DesignerStep designer = (serviceDir, listFile) -> {
            writeFiles(serviceDir, writtenFiles);
        };

        Path serviceDir;

        Path listFileRead;

        /** The list file's bytes, read while the Designer stand-in runs (it is deleted after). */
        byte[] listFileBytes;

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
            if (refuseLock || lockHeld)
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
        public Path serviceDirectory() throws IOException
        {
            asked.add("serviceDir"); //$NON-NLS-1$
            serviceDir = root.resolve(".aiedt-export-test"); //$NON-NLS-1$
            Files.createDirectories(serviceDir);
            return serviceDir;
        }

        @Override
        public boolean releaseInfobase()
        {
            asked.add("release"); //$NON-NLS-1$
            return connectedAtStart;
        }

        @Override
        public String runDesigner(Path dir, Path listFile, BooleanSupplier cancelled)
            throws Exception
        {
            asked.add("designer"); //$NON-NLS-1$
            listFileRead = listFile;
            try
            {
                listFileBytes = Files.readAllBytes(listFile);
            }
            catch (IOException unreadable)
            {
                listFileBytes = new byte[0];
            }
            designer.run(dir, listFile);
            if (!designerOk)
            {
                throw new IllegalStateException("the Designer exited with code 1: partial dump"); //$NON-NLS-1$
            }
            return "Designer log line"; //$NON-NLS-1$
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
        public boolean destinationVacant(Path destination)
        {
            asked.add("destinationVacant"); //$NON-NLS-1$
            vacantChecks++;
            return vacantChecks <= vacantForFirstChecks;
        }

        @Override
        public void removeVacantDestination(Path destination) throws IOException
        {
            asked.add("removeVacantDestination"); //$NON-NLS-1$
            Files.deleteIfExists(destination);
        }

        @Override
        public void moveIntoPlace(Path dir, Path destination) throws IOException
        {
            asked.add("moveIntoPlace"); //$NON-NLS-1$
            if (failMove)
            {
                throw new IOException("the directory is held open by another process"); //$NON-NLS-1$
            }
            Files.move(dir, destination);
        }

        @Override
        public void deleteDirectory(Path dir)
        {
            asked.add("deleteDirectory"); //$NON-NLS-1$
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

    private static void writeFiles(Path dir, List<String> relatives)
    {
        for (String relative : relatives)
        {
            try
            {
                Path file = dir.resolve(relative);
                Files.createDirectories(file.getParent());
                Files.write(file, "content".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
            }
            catch (IOException unreadable)
            {
                throw new IllegalStateException(unreadable);
            }
        }
    }

    private Outcome run(StandIn io)
    {
        return InfobaseObjectsExporter.performExport(io, addresses(), outputPath, () -> false);
    }

    // ---- success ---------------------------------------------------------------------------

    /**
     * The happy path: identify, lock, check the destination, service directory, release, run the
     * Designer, reconnect, verify, place, clean up, unlock. The result lands at the destination,
     * the list file carries the addresses with a BOM and is deleted, nothing of the service
     * directory remains.
     */
    @Test
    public void theExportPlacesTheResultAndCleansUpAfterItself() throws IOException
    {
        StandIn io = new StandIn();

        Outcome outcome = run(io);

        assertTrue(outcome.error, outcome.ok);
        assertEquals(outputPath.toString(), outcome.outputPath);
        assertEquals("infobase", outcome.source); //$NON-NLS-1$
        assertEquals(Arrays.asList("Catalogs/Банки/Ext/ObjectModule.bsl", //$NON-NLS-1$
            "Catalogs/Банки/Forms/ФормаЭлемента/Ext/Form.bin"), outcome.files); //$NON-NLS-1$
        assertTrue("the answer carries the designer log", //$NON-NLS-1$
            outcome.designerLog.contains("Designer log line")); //$NON-NLS-1$
        assertTrue(Files.isRegularFile(outputPath.resolve("Catalogs/Банки/Ext/ObjectModule.bsl"))); //$NON-NLS-1$
        assertEquals(Arrays.asList("identity", "lock", "destination", "serviceDir", "release", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "work", "designer", "reconnect", "verifyOutput", "place", "deleteListFile", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "cleanup", "unlock"), //$NON-NLS-1$ //$NON-NLS-2$
            outcome.sequence);
        assertNull(outcome.leftBehind);
        assertFalse(outcome.lockHeldForProcess);
        assertFalse(io.lockHeld);
        assertFalse("the service directory is gone", Files.exists(io.serviceDir)); //$NON-NLS-1$
        assertEquals("the list file is deleted", 0, //$NON-NLS-1$
            Files.list(root).filter(p -> p.getFileName().toString().endsWith(".txt")).count()); //$NON-NLS-1$
    }

    /**
     * The list file the Designer was pointed at: UTF-8 with a BOM, one Russian full name per
     * line, read by the stand-in while the run held it.
     */
    @Test
    public void theListFileCarriesRussianNamesWithABomOnePerLine()
    {
        StandIn io = new StandIn();

        Outcome outcome = run(io);

        assertTrue(outcome.ok);
        assertNotNull(io.listFileRead);
        assertTrue("the list file was handed to the Designer", //$NON-NLS-1$
            io.asked.contains("designer")); //$NON-NLS-1$
        assertEquals("the list file lies beside the service directory, not inside it", //$NON-NLS-1$
            io.serviceDir.resolveSibling(io.serviceDir.getFileName() + ".objects.txt"), //$NON-NLS-1$
            io.listFileRead);
        byte[] bytes = io.listFileBytes;
        assertNotNull(bytes);
        assertEquals("UTF-8 BOM", 0xEF, bytes[0] & 0xFF); //$NON-NLS-1$
        assertEquals("UTF-8 BOM", 0xBB, bytes[1] & 0xFF); //$NON-NLS-1$
        assertEquals("UTF-8 BOM", 0xBF, bytes[2] & 0xFF); //$NON-NLS-1$
        String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertEquals("one line per address, each newline-terminated", //$NON-NLS-1$
            String.join("\n", addresses()) + "\n", text); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- fail-closed on an unidentified base ------------------------------------------------

    /**
     * A base this cannot identify is refused BEFORE anything is claimed or released.
     */
    @Test
    public void anUnidentifiedInfobaseIsRefusedBeforeAnythingIsTouched()
    {
        StandIn io = new StandIn();
        io.refuseIdentity = true;

        Outcome outcome = run(io);

        assertFalse(outcome.ok);
        assertEquals(Arrays.asList("identity"), io.asked); //$NON-NLS-1$
        assertTrue(outcome.error.contains("refused before")); //$NON-NLS-1$
    }

    /**
     * A base another instance holds is refused the same way: nothing released, no Designer.
     */
    @Test
    public void aHeldInfobaseIsRefusedWithTheHolderNamed()
    {
        StandIn io = new StandIn();
        io.refuseLock = true;

        Outcome outcome = run(io);

        assertFalse(outcome.ok);
        assertEquals(Arrays.asList("identity", "takeLock"), io.asked); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(io.lockRefusal, outcome.error);
        assertEquals("busy", outcome.failureKind); //$NON-NLS-1$
        assertFalse("the claim was never taken, so nothing is held after the refusal", io.lockHeld); //$NON-NLS-1$
    }

    // ---- the destination --------------------------------------------------------------------

    /**
     * A destination that is not empty on the way in is refused before anything is released: the
     * Designer is not started.
     */
    @Test
    public void aNonEmptyDestinationIsRefusedBeforeTheDesignerRuns()
    {
        StandIn io = new StandIn();
        io.vacantForFirstChecks = 0;

        Outcome outcome = run(io);

        assertFalse(outcome.ok);
        assertFalse("the Designer is not started", io.asked.contains("designer")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the infobase is not released", io.asked.contains("release")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("outputDirectoryError", outcome.failureKind); //$NON-NLS-1$
        assertFalse(io.lockHeld);
        assertTrue("the lock is released after the refusal", io.asked.contains("releaseLock")); //$NON-NLS-1$
    }

    /**
     * A destination that was filled between the entry check and the placement is refused: the
     * service directory is discarded, the destination is left untouched.
     */
    @Test
    public void aDestinationFilledInBetweenIsRefusedAndLeftUntouched() throws IOException
    {
        StandIn io = new StandIn();
        io.vacantForFirstChecks = 1;
        Files.createDirectories(outputPath);
        Files.write(outputPath.resolve("someone.txt"), "x".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$

        Outcome outcome = run(io);

        assertFalse(outcome.ok);
        assertEquals("outputDirectoryError", outcome.failureKind); //$NON-NLS-1$
        assertEquals("the destination still holds what filled it", "someone.txt", //$NON-NLS-1$ //$NON-NLS-2$
            Files.list(outputPath).findFirst().get().getFileName().toString());
        assertFalse("the service directory is deleted", Files.exists(io.serviceDir)); //$NON-NLS-1$
        assertFalse(io.lockHeld);
    }

    /**
     * A placement that fails discards the service directory and leaves nothing of the export at
     * the destination.
     */
    @Test
    public void aFailingPlacementDiscardsTheServiceDirectory()
    {
        StandIn io = new StandIn();
        io.failMove = true;

        Outcome outcome = run(io);

        assertFalse(outcome.ok);
        assertEquals("outputDirectoryError", outcome.failureKind); //$NON-NLS-1$
        assertTrue(outcome.error.contains("held open")); //$NON-NLS-1$
        assertFalse("the service directory is deleted", Files.exists(io.serviceDir)); //$NON-NLS-1$
        assertFalse("nothing of the export landed at the destination", //$NON-NLS-1$
            Files.exists(outputPath));
        assertTrue("the lock is released", io.asked.contains("releaseLock")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A Designer run that answers without writing anything is a failure the platform did not
     * announce: the destination stays empty and the service directory is deleted.
     */
    @Test
    public void aDesignerRunThatWritesNothingIsRefused()
    {
        StandIn io = new StandIn();
        io.writtenFiles = new ArrayList<>();

        Outcome outcome = run(io);

        assertFalse(outcome.ok);
        assertEquals("outputMissing", outcome.failureKind); //$NON-NLS-1$
        assertFalse("nothing landed at the destination", Files.exists(outputPath)); //$NON-NLS-1$
        assertFalse(Files.exists(io.serviceDir));
    }

    // ---- the Designer run fails -------------------------------------------------------------

    /**
     * A Designer run that fails with files half-written (a partial export) deletes the service
     * directory, leaves the destination untouched, and its failure carries the run's own words.
     */
    @Test
    public void aPartialExportIsDiscardedAndTheFailureCarriesTheLog()
    {
        StandIn io = new StandIn();
        io.designerOk = false;

        Outcome outcome = run(io);

        assertFalse(outcome.ok);
        assertEquals("thickClientFailed", outcome.failureKind); //$NON-NLS-1$
        assertTrue("the failure carries the run's own words", //$NON-NLS-1$
            outcome.error.contains("exited with code 1")); //$NON-NLS-1$
        assertFalse("the destination is not touched", Files.exists(outputPath)); //$NON-NLS-1$
        assertFalse("the partial files are deleted", Files.exists(io.serviceDir)); //$NON-NLS-1$
        assertTrue("the infobase is taken back whatever the run did", //$NON-NLS-1$
            io.asked.contains("reconnect")); //$NON-NLS-1$
        assertFalse(io.lockHeld);
    }

    // ---- cancellation -----------------------------------------------------------------------

    /**
     * A cancellation raised before the Designer starts launches nothing: the answer says
     * cancelled, the destination is untouched, the claim is released.
     */
    @Test
    public void aCancellationBeforeTheLaunchStartsNothing()
    {
        StandIn io = new StandIn();

        Outcome outcome = InfobaseObjectsExporter.performExport(io, addresses(), outputPath,
            () -> true);

        assertFalse(outcome.ok);
        assertTrue(outcome.cancelledBeforeLaunch);
        assertEquals("cancelled", outcome.failureKind); //$NON-NLS-1$
        assertFalse("no Designer was started", io.asked.contains("designer")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(Files.exists(outputPath));
        assertTrue("the claim is released", io.asked.contains("releaseLock")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(io.lockHeld);
    }

    /**
     * An abandoned run whose process had already left (or never crossed the boundary) cleans up at
     * once: the service directory is deleted, the claim released, the destination untouched.
     */
    @Test
    public void anAbandonedRunThatLeftNoWriterCleansUpAtOnce()
    {
        StandIn io = new StandIn();
        io.designer = (dir, listFile) -> {
            writeFiles(dir, io.writtenFiles);
            throw new Abandoned("the Designer export did not finish within 600s", false, null); //$NON-NLS-1$
        };

        Outcome outcome = run(io);

        assertFalse(outcome.ok);
        assertNull(outcome.leftBehind);
        assertFalse(outcome.lockHeldForProcess);
        assertTrue("the temporary directory is deleted at once", io.asked.contains("deleteDirectory")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(io.lockHeld);
        assertFalse(Files.exists(outputPath));
    }

    /**
     * An abandoned run whose process is still writing keeps the service directory and the claim,
     * names the directory as left behind, and defers the cleanup until the call returns.
     */
    @Test
    public void anAbandonedRunStillWritingNamesTheDirectoryAndDefersCleanup() throws Exception
    {
        StandIn io = new StandIn();
        AtomicReference<Runnable> deferred = new AtomicReference<>();
        io.designer = (dir, listFile) -> {
            writeFiles(dir, io.writtenFiles);
            throw new Abandoned("the Designer export did not finish within 600s", true, //$NON-NLS-1$
                deferred::set);
        };

        Outcome outcome = run(io);

        assertFalse(outcome.ok);
        assertNotNull(outcome.leftBehind);
        assertEquals(io.serviceDir.toString(), outcome.leftBehind);
        assertTrue(outcome.lockHeldForProcess);
        assertTrue("the service directory is still there", Files.exists(io.serviceDir)); //$NON-NLS-1$
        assertTrue(io.lockHeld);
        assertFalse("the infobase stays disconnected while the process writes", //$NON-NLS-1$
            io.asked.contains("reconnect")); //$NON-NLS-1$

        Runnable cleanup = deferred.get();
        assertNotNull("the cleanup is deferred until the call returns", cleanup); //$NON-NLS-1$
        // Run the LAST registered cleanup - the full one; earlier registrations only settle flags.
        cleanup.run();
        assertTrue("the service directory is deleted once the call returns", //$NON-NLS-1$
            io.asked.contains("deleteDirectory")); //$NON-NLS-1$
        assertTrue("the base the export disconnected is taken back", io.asked.contains("reconnect")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(io.lockHeld);
    }

    /**
     * An abandoned run whose process returns within the grace is cleaned up in the answer itself:
     * no directory is named as left behind, and the infobase is taken back.
     */
    @Test
    public void anAbandonedRunThatReturnsWithinTheGraceIsCleanedUpInline() throws Exception
    {
        StandIn io = new StandIn();
        AtomicReference<Runnable> deferred = new AtomicReference<>();
        io.designer = (dir, listFile) -> {
            writeFiles(dir, io.writtenFiles);
            throw new Abandoned("the Designer export did not finish within 600s", true, //$NON-NLS-1$
                deferred::set);
        };
        CountDownLatch exportReturned = new CountDownLatch(1);
        AtomicReference<Outcome> outcomeRef = new AtomicReference<>();
        ExecutorService runner = Executors.newSingleThreadExecutor();
        try
        {
            runner.submit(() -> {
                outcomeRef.set(run(io));
                exportReturned.countDown();
            });
            // The grace registered its flag-settling cleanup with the Abandoned's deferred
            // consumer; running that cleanup stands for the process returning. Wait for the
            // FIRST registration - the flag-settler the grace polls for.
            long deadline = System.currentTimeMillis() + 10_000L;
            while (deferred.get() == null && System.currentTimeMillis() < deadline)
            {
                Thread.sleep(20L);
            }
            assertNotNull("the grace registered its settling cleanup", deferred.get()); //$NON-NLS-1$
            deferred.get().run();
            assertTrue("the export answered once the process returned", //$NON-NLS-1$
                exportReturned.await(40, TimeUnit.SECONDS));
            Outcome outcome = outcomeRef.get();
            assertFalse(outcome.ok);
            assertNull("nothing is named left behind once the process returned in time", //$NON-NLS-1$
                outcome.leftBehind);
            assertFalse(outcome.lockHeldForProcess);
            assertTrue("the infobase is taken back in the answer itself", //$NON-NLS-1$
                io.asked.contains("reconnect")); //$NON-NLS-1$
            assertFalse(io.lockHeld);
            assertFalse("the service directory is deleted", Files.exists(io.serviceDir)); //$NON-NLS-1$
        }
        finally
        {
            runner.shutdownNow();
        }
    }

    /**
     * A process that returns in the window between the grace's last check and the cleanup's hold
     * decision is still taken back: the deferral is registered and runs the reconnection, rather
     * than the hold unmaking itself, the cleanup running inline and the reconnection being owed
     * to nobody.
     */
    @Test
    public void aProcessReturningAtTheGraceBoundaryIsStillTakenBackByTheDeferral()
    {
        StandIn io = new StandIn();
        List<Runnable> registered = new ArrayList<>();
        io.designer = (dir, listFile) -> {
            writeFiles(dir, io.writtenFiles);
            throw new Abandoned("the Designer export did not finish within 600s", true, //$NON-NLS-1$
                registered::add);
        };
        // The grace is given nothing, and the process "returns" exactly at the boundary the
        // hold is resolved at - after the grace's last check, before the deferral decision.
        InfobaseObjectsExporter.beforeHoldResolution = () -> registered.get(0).run();
        try
        {
            Outcome outcome = InfobaseObjectsExporter.performExport(io, addresses(), outputPath,
                () -> false, 0L);

            assertFalse(outcome.ok);
            assertNotNull("the grace answered left-behind", outcome.leftBehind); //$NON-NLS-1$
            assertTrue(outcome.lockHeldForProcess);
            assertEquals("the settle and the deferral both registered", 2, registered.size()); //$NON-NLS-1$
            assertFalse("the boundary did not clean up inline", io.asked.contains("unlock")); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse("the reconnection is owed to the deferral, not to the answer", //$NON-NLS-1$
                io.asked.contains("reconnect")); //$NON-NLS-1$
            assertTrue("the claim stays with the run", io.lockHeld); //$NON-NLS-1$

            registered.get(1).run();
            assertTrue("the returned process runs the deferral, which takes the base back", //$NON-NLS-1$
                io.asked.contains("reconnect")); //$NON-NLS-1$
            assertFalse(io.lockHeld);
            assertFalse("the service directory is deleted by the deferral", //$NON-NLS-1$
                Files.exists(io.serviceDir));
        }
        finally
        {
            InfobaseObjectsExporter.beforeHoldResolution = null;
        }
    }

    // ---- the release and the reconnection ---------------------------------------------------

    /**
     * A release that fails means the export never started: the destination is untouched and the
     * failure names the release.
     */
    @Test
    public void aFailingReleaseRefusesTheExport()
    {
        StandIn failing = new StandIn()
        {
            @Override
            public boolean releaseInfobase()
            {
                super.releaseInfobase();
                throw new RuntimeException("disconnectInfobase: the base is locked"); //$NON-NLS-1$
            }
        };

        Outcome outcome = run(failing);

        assertFalse(outcome.ok);
        assertEquals("infobaseNotReleased", outcome.failureKind); //$NON-NLS-1$
        assertFalse("the Designer is not started", failing.asked.contains("designer")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(Files.exists(outputPath));
        assertFalse(failing.lockHeld);
    }
}
