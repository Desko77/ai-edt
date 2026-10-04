/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IThickClientLauncher;
import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.ModelFactory;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.DtSnapshotRunner.IoFactory;
import ru.aiedt.mcp.server.support.DtSnapshotRunner.IoResolution;

/**
 * A snapshot's launch boundary is claimed by the worker that crosses it, under the per-infobase
 * lock and immediately before the launcher call - not by the abandonment that gives the run up.
 * <p>
 * The two answers that depend on it are opposites, and both are wrong if the worker never claims:
 * an abandonment that arrives before the worker reaches the boundary has to leave the launcher
 * uncalled, and one that arrives after it has to be reported as a platform call still running -
 * which is also what keeps the infobase claim held and the file being written where it is. Every
 * run here goes through {@code DtSnapshotRunner.EdtIo}, the environment the operations really
 * use, with a launcher the test holds by hand; no infobase, no platform and no EDT are involved.
 * </p>
 */
public class TheSnapshotBoundaryIsClaimedBeforeTheLauncherTest
{
    /** Where {@link MonopolyLock} keeps claims; pointed at a directory of the test's own. */
    private static final String LOCK_DIR_PROPERTY = "aiedt.locks.dir"; //$NON-NLS-1$

    /** The tool name a run started from a test is recorded under. */
    private static final String STARTER = "infobase_admin"; //$NON-NLS-1$

    /** How long a test waits for the worker or for a claim to come back. */
    private static final long PATIENCE_MS = 30_000L;

    /**
     * The sentence the answer carries when the platform process is still writing - the one that has
     * to be there for a call that crossed the boundary and absent for a call that never started.
     * The abandonment's own message differs as well: {@link #NOTHING_LAUNCHED} for a call that never
     * started, "while it was still running" for a cancel of one that crossed the boundary, and "did
     * not finish within" for one whose budget ran out.
     */
    private static final String PROCESS_STILL_RUNNING =
        "the platform process is still running"; //$NON-NLS-1$

    /** The words of an abandonment that kept the Designer run from starting. */
    private static final String NOTHING_LAUNCHED = "that run was not launched"; //$NON-NLS-1$

    private Path work;

    private Path locks;

    private String previousLocksDirectory;

    @Before
    public void aDirectoryOfItsOwn() throws IOException
    {
        work = Files.createTempDirectory("aiedt-dt-boundary-test"); //$NON-NLS-1$
        locks = Files.createTempDirectory("aiedt-dt-boundary-locks"); //$NON-NLS-1$
        previousLocksDirectory = System.getProperty(LOCK_DIR_PROPERTY);
        System.setProperty(LOCK_DIR_PROPERTY, locks.toString());
    }

    @After
    public void theDirectoriesGo()
    {
        if (previousLocksDirectory == null)
        {
            System.clearProperty(LOCK_DIR_PROPERTY);
        }
        else
        {
            System.setProperty(LOCK_DIR_PROPERTY, previousLocksDirectory);
        }
        deleteTree(work);
        deleteTree(locks);
    }

    /**
     * A cancel that claimed the boundary while the worker was still waiting for the infobase lock
     * starts no launcher call at all: the answer carries no file left behind, the temporary file
     * the write had already created is gone, and the infobase claim is free again.
     */
    @Test
    public void aCancelThatClaimedTheBoundaryFirstStartsNoLauncherCall() throws Exception
    {
        LauncherFixture fixture = new LauncherFixture(work);
        Path file = work.resolve("dump.dt"); //$NON-NLS-1$
        AtomicReference<String> runKey = new AtomicReference<>();
        AtomicReference<String> answer = new AtomicReference<>();

        // Held by the test, so the worker cannot reach the boundary until this is let go.
        fixture.lock.lock();
        Thread run = startTheRun(fixture, runKey, answer, file);
        assertTrue("the worker waits for the infobase lock", aWorkerIsWaitingFor(fixture.lock)); //$NON-NLS-1$
        assertEquals("a cancel whose abandonment took the boundary is answered as prevented", //$NON-NLS-1$
            PendingWorkRegistry.StopOutcome.PREVENTED, DtSnapshotRunner.stopTheRun(runKey.get()));
        // The lock is let go only once the answer is out: held while the run decides, the worker
        // cannot reach the boundary and the abandonment is the side that claims it.
        run.join(PATIENCE_MS);
        assertFalse("the run answered", run.isAlive()); //$NON-NLS-1$
        fixture.lock.unlock();

        JsonObject failed = parsed(answer.get());
        assertFalse(failed.toString(), failed.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(failed.toString(), failed.get("cancelled").getAsBoolean()); //$NON-NLS-1$
        assertFalse("a call that never started is not reported as running: " + failed, //$NON-NLS-1$
            failed.get("error").getAsString().contains(PROCESS_STILL_RUNNING)); //$NON-NLS-1$
        assertTrue("the answer says the Designer run was not launched: " + failed, //$NON-NLS-1$
            failed.get("error").getAsString().contains(NOTHING_LAUNCHED)); //$NON-NLS-1$
        assertFalse("the answer does not call a run that never started still running: " + failed, //$NON-NLS-1$
            failed.get("error").getAsString().contains("while it was still running")); //$NON-NLS-1$
        assertFalse("no file is left for a process that does not exist: " + failed, //$NON-NLS-1$
            failed.has("leftBehind")); //$NON-NLS-1$
        assertFalse("a call that never started holds no claim: " + failed, //$NON-NLS-1$
            failed.has("lockHeldForProcess")); //$NON-NLS-1$
        assertTrue("the launcher was never called", fixture.calls.isEmpty()); //$NON-NLS-1$
        assertFalse("the destination was not written", Files.exists(file)); //$NON-NLS-1$
        assertEquals("the temporary file was cleaned up", List.of(), partFilesIn(work)); //$NON-NLS-1$
        assertFalse("the lock is not left held", fixture.lock.isLocked()); //$NON-NLS-1$
        assertTrue("the infobase claim came back", canClaim(fixture.identity)); //$NON-NLS-1$
    }

    /**
     * A cancel that arrives after the worker claimed the boundary and entered the launcher call is
     * answered as a platform call still running - the answer says so and names the file the process
     * is writing, that file is not deleted, the destination is not placed, and the infobase claim
     * does not count as free until the call returns.
     */
    @Test
    public void aCancelThatArrivesAfterTheBoundaryIsAnsweredAsStillRunning() throws Exception
    {
        LauncherFixture fixture = new LauncherFixture(work);
        Path file = work.resolve("dump.dt"); //$NON-NLS-1$
        AtomicReference<String> runKey = new AtomicReference<>();
        AtomicReference<String> answer = new AtomicReference<>();

        Thread run = startTheRun(fixture, runKey, answer, file);
        if (!fixture.entered.await(PATIENCE_MS, TimeUnit.MILLISECONDS))
        {
            run.join(5_000L);
            throw new AssertionError("the launcher call did not start; the run answered: " //$NON-NLS-1$
                + answer.get() + ", calls " + fixture.calls + ", run alive " + run.isAlive()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        assertEquals("the dump writes into a temporary file of its own", 1, partFilesIn(work).size()); //$NON-NLS-1$

        assertEquals(PendingWorkRegistry.StopOutcome.STILL_RUNNING,
            DtSnapshotRunner.stopTheRun(runKey.get()));
        run.join(PATIENCE_MS);
        assertFalse("the run answered while the platform call was held", run.isAlive()); //$NON-NLS-1$

        JsonObject failed = parsed(answer.get());
        assertTrue(failed.toString(), failed.get("cancelled").getAsBoolean()); //$NON-NLS-1$
        assertTrue("the answer says the platform is still working: " + failed, //$NON-NLS-1$
            failed.get("error").getAsString().contains(PROCESS_STILL_RUNNING)); //$NON-NLS-1$
        assertTrue("the answer names the file the running process is writing: " + failed, //$NON-NLS-1$
            failed.has("leftBehind")); //$NON-NLS-1$
        assertTrue("the answer says the claim is held for that process: " + failed, //$NON-NLS-1$
            failed.get("lockHeldForProcess").getAsBoolean()); //$NON-NLS-1$
        Path left = Path.of(failed.get("leftBehind").getAsString()); //$NON-NLS-1$
        assertTrue("the file a live process is writing is not deleted", Files.exists(left)); //$NON-NLS-1$
        assertFalse("the destination is not placed by a dump that never returned", Files.exists(file)); //$NON-NLS-1$
        assertEquals("the launcher was called once", 1, fixture.calls.size()); //$NON-NLS-1$
        assertFalse("the infobase is not free while the answer says a process is still running", //$NON-NLS-1$
            canClaim(fixture.identity));

        fixture.release.set(true); // the platform call returns
        assertTrue("the claim comes back when that process returns", //$NON-NLS-1$
            awaitClaimable(fixture.identity));
    }

    /**
     * The load's own launcher call is the backup of what the infobase holds now, so it crosses the
     * same boundary: a cancel that arrives while the backup runs is answered as a platform process
     * still running, keeps the infobase claim, and names the backup's temporary file. The restore
     * itself is not started, and the file to load is left as it was.
     */
    @Test
    public void aCancelDuringALoadsBackupHoldsTheClaimAndNamesThatFile() throws Exception
    {
        LauncherFixture fixture = new LauncherFixture(work);
        Path source = work.resolve("source.dt"); //$NON-NLS-1$
        Files.write(source, new byte[] { 1, 2, 3 });
        Path backup = work.resolve("backup.dt"); //$NON-NLS-1$
        AtomicReference<String> runKey = new AtomicReference<>();
        AtomicReference<String> answer = new AtomicReference<>();

        Thread run = startTheLoad(fixture, runKey, answer, source, backup);
        if (!fixture.entered.await(PATIENCE_MS, TimeUnit.MILLISECONDS))
        {
            run.join(5_000L);
            throw new AssertionError("the backup call did not start; the run answered: " //$NON-NLS-1$
                + answer.get() + ", calls " + fixture.calls + ", run alive " + run.isAlive()); //$NON-NLS-1$ //$NON-NLS-2$
        }

        assertEquals(PendingWorkRegistry.StopOutcome.STILL_RUNNING,
            DtSnapshotRunner.stopTheRun(runKey.get()));
        run.join(PATIENCE_MS);
        assertFalse("the run answered while the platform call was held", run.isAlive()); //$NON-NLS-1$

        JsonObject failed = parsed(answer.get());
        assertTrue(failed.toString(), failed.get("cancelled").getAsBoolean()); //$NON-NLS-1$
        assertTrue("the restore was not started: " + failed, //$NON-NLS-1$
            failed.get("error").getAsString().contains("The restore was not started")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the answer says the platform is still working: " + failed, //$NON-NLS-1$
            failed.get("error").getAsString().contains(PROCESS_STILL_RUNNING)); //$NON-NLS-1$
        Path left = Path.of(failed.get("leftBehind").getAsString()); //$NON-NLS-1$
        assertEquals("the answer names the backup's own temporary file: " + failed, //$NON-NLS-1$
            partFilesIn(work), List.of(left.getFileName().toString()));
        assertTrue("the file a live process is writing is not deleted", Files.exists(left)); //$NON-NLS-1$
        assertTrue("the answer says the claim is held for that process: " + failed, //$NON-NLS-1$
            failed.get("lockHeldForProcess").getAsBoolean()); //$NON-NLS-1$
        assertFalse("the infobase is not free while the answer says a process is still running", //$NON-NLS-1$
            canClaim(fixture.identity));
        assertEquals("the file the load was pointed at is left as it was", //$NON-NLS-1$
            List.of(1, 2, 3), readAll(source));
        assertFalse("the backup is not placed by a run that never returned", //$NON-NLS-1$
            Files.exists(backup));

        fixture.release.set(true); // the platform call returns
        assertTrue("the claim comes back when that process returns", //$NON-NLS-1$
            awaitClaimable(fixture.identity));
    }

    /**
     * A stop signaled while the worker waits for the per-infobase lock holds the boundary even
     * when the lock is freed in the same moment - inside the wait's poll gap: the worker that
     * then acquires the lock finds the boundary taken, the launcher is never called, and the
     * stopper answers PREVENTED.
     */
    @Test
    public void aStopAtTheSignalHoldsTheBoundaryWhenTheLockIsFreedAtOnce() throws Exception
    {
        LauncherFixture fixture = new LauncherFixture(work);
        Path file = work.resolve("dump.dt"); //$NON-NLS-1$
        AtomicReference<String> runKey = new AtomicReference<>();
        AtomicReference<String> answer = new AtomicReference<>();
        AtomicReference<PendingWorkRegistry.StopOutcome> outcome = new AtomicReference<>();

        fixture.lock.lock();
        Thread run = startTheRun(fixture, runKey, answer, file);
        try
        {
            assertTrue("the worker waits for the infobase lock", aWorkerIsWaitingFor(fixture.lock)); //$NON-NLS-1$

            Thread stopper =
                new Thread(() -> outcome.set(DtSnapshotRunner.stopTheRun(runKey.get())));
            stopper.setDaemon(true);
            stopper.start();
            // The lock is let go the moment the stop is signaled, so a boundary the stopper did
            // not claim itself is the worker's to take before the wait's next poll.
            long deadline = System.currentTimeMillis() + PATIENCE_MS;
            while (fixture.live.stopped.getCount() > 0 && System.currentTimeMillis() < deadline)
            {
                Thread.sleep(2L);
            }
            fixture.lock.unlock();
            stopper.join(PATIENCE_MS);
            fixture.release.set(true); // lets a launcher call that slipped through return

            assertEquals("a stop at the signal is answered as prevented", //$NON-NLS-1$
                PendingWorkRegistry.StopOutcome.PREVENTED, outcome.get());
        }
        finally
        {
            fixture.release.set(true);
            if (fixture.lock.isHeldByCurrentThread())
            {
                fixture.lock.unlock();
            }
        }
        run.join(PATIENCE_MS);
        assertFalse("the run answered", run.isAlive()); //$NON-NLS-1$

        assertTrue("the launcher was never called", fixture.calls.isEmpty()); //$NON-NLS-1$
        JsonObject failed = parsed(answer.get());
        assertTrue(failed.toString(), failed.get("cancelled").getAsBoolean()); //$NON-NLS-1$
        assertTrue("the answer says the Designer run was not launched: " + failed, //$NON-NLS-1$
            failed.get("error").getAsString().contains(NOTHING_LAUNCHED)); //$NON-NLS-1$
        assertFalse("the destination was not written", Files.exists(file)); //$NON-NLS-1$
    }

    /**
     * A cancel that prevents a load's restore call after its backup call already ran is not
     * answered as prevented: the backup's platform process started and left its file, so the
     * stopper reports the run as still running rather than as a run that never launched.
     */
    @Test
    public void aCancelAfterTheBackupRanIsNotAnsweredAsPrevented() throws Exception
    {
        LauncherFixture fixture = new LauncherFixture(work);
        Path source = work.resolve("source.dt"); //$NON-NLS-1$
        Files.write(source, new byte[] { 1, 2, 3 });
        Path backup = work.resolve("backup.dt"); //$NON-NLS-1$
        AtomicReference<String> runKey = new AtomicReference<>();
        AtomicReference<String> answer = new AtomicReference<>();

        Thread run = startTheLoad(fixture, runKey, answer, source, backup);
        if (!fixture.entered.await(PATIENCE_MS, TimeUnit.MILLISECONDS))
        {
            run.join(5_000L);
            throw new AssertionError("the backup call did not start; the run answered: " //$NON-NLS-1$
                + answer.get() + ", calls " + fixture.calls + ", run alive " + run.isAlive()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        fixture.release.set(true); // the backup call returns
        fixture.lock.lock(); // taken back once the backup's handshake gives it up
        try
        {
            if (!aWorkerIsWaitingFor(fixture.lock))
            {
                run.join(5_000L);
                throw new AssertionError("the restore's worker did not reach the infobase lock; " //$NON-NLS-1$
                    + "the run answered: " + answer.get() + ", calls " + fixture.calls //$NON-NLS-1$
                    + ", run alive " + run.isAlive()); //$NON-NLS-1$
            }

            assertEquals("a run whose backup already launched is not answered as prevented", //$NON-NLS-1$
                PendingWorkRegistry.StopOutcome.STILL_RUNNING,
                DtSnapshotRunner.stopTheRun(runKey.get()));
        }
        finally
        {
            fixture.lock.unlock();
        }
        run.join(PATIENCE_MS);
        assertFalse("the run answered", run.isAlive()); //$NON-NLS-1$

        assertEquals("the restore's launcher call never happened", 1, fixture.calls.size()); //$NON-NLS-1$
        JsonObject failed = parsed(answer.get());
        assertTrue(failed.toString(), failed.get("cancelled").getAsBoolean()); //$NON-NLS-1$
        assertTrue("the answer still names the backup the run wrote: " + failed, //$NON-NLS-1$
            failed.has("backup")); //$NON-NLS-1$
        assertTrue("the backup file the first call left is still there", Files.exists(backup)); //$NON-NLS-1$
    }

    /**
     * Starts one dump on a thread of its own, so the test can act while the run is inside it.
     *
     * @param fixture the launcher the run goes through
     * @param runKey filled with the run's key
     * @param answer filled with the answer the run came to
     * @param file the destination the dump was pointed at
     * @return the running thread
     */
    private static Thread startTheRun(LauncherFixture fixture, AtomicReference<String> runKey,
        AtomicReference<String> answer, Path file)
    {
        Thread run = new Thread(() -> answer.set(DtSnapshotRunner.dispatchExport(call(),
            "Project", null, file.toString(), null, false, factory(fixture, runKey), STARTER)), //$NON-NLS-1$
            "snapshot-boundary-test"); //$NON-NLS-1$
        run.setDaemon(true);
        run.start();
        return run;
    }

    /**
     * Starts one load on a thread of its own, so the test can act while its backup is inside the
     * launcher.
     *
     * @param fixture the launcher the run goes through
     * @param runKey filled with the run's key
     * @param answer filled with the answer the run came to
     * @param source the {@code .dt} the load was pointed at
     * @param backup where the backup of the infobase's current contents goes
     * @return the running thread
     */
    private static Thread startTheLoad(LauncherFixture fixture, AtomicReference<String> runKey,
        AtomicReference<String> answer, Path source, Path backup)
    {
        Thread run = new Thread(() -> answer.set(DtSnapshotRunner.dispatchRestore(call(),
            "Project", null, source.toString(), backup.toString(), null, false, //$NON-NLS-1$
            factory(fixture, runKey), STARTER)), "snapshot-boundary-test"); //$NON-NLS-1$
        run.setDaemon(true);
        run.start();
        return run;
    }

    /**
     * @param file the file to read
     * @return its bytes as a list, so an assertion can name what it expected
     */
    private static List<Integer> readAll(Path file) throws IOException
    {
        List<Integer> bytes = new ArrayList<>();
        for (byte b : Files.readAllBytes(file))
        {
            bytes.add((int)b);
        }
        return bytes;
    }

    /**
     * @param fixture the launcher the run goes through
     * @param runKey filled with the run's key
     * @return the factory that hands the runner an environment over that launcher
     */
    private static IoFactory factory(LauncherFixture fixture, AtomicReference<String> runKey)
    {
        return (projectName, applicationId, operation, key, live, cancelled) -> {
            runKey.set(key);
            fixture.live = live;
            return IoResolution.of(
                new DtSnapshotRunner.EdtIo(fixture.ctx, operation, key, live, cancelled),
                fixture.ctx.infobaseName);
        };
    }

    /** The call arguments both tests send: a window long enough that the run is never answered Pending. */
    private static Map<String, String> call()
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("timeoutSeconds", "30"); //$NON-NLS-1$ //$NON-NLS-2$
        return params;
    }

    /**
     * @param json the answer a run rendered
     * @return it as an object
     */
    private static JsonObject parsed(String json)
    {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /**
     * @param lock the per-infobase lock of the run
     * @return whether a thread is queued on it, waited for
     */
    private static boolean aWorkerIsWaitingFor(ReentrantLock lock) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + PATIENCE_MS;
        while (System.currentTimeMillis() < deadline)
        {
            if (lock.hasQueuedThreads())
            {
                return true;
            }
            Thread.sleep(5L);
        }
        return false;
    }

    /**
     * @param identity the infobase's identity
     * @return whether a claim on it would be granted right now; the claim is given back at once
     */
    private static boolean canClaim(String identity)
    {
        MonopolyLock.Claim attempt = MonopolyLock.claim(identity, "export_database_snapshot"); //$NON-NLS-1$
        boolean granted = attempt.granted();
        attempt.close();
        return granted;
    }

    /**
     * @param identity the infobase's identity
     * @return whether a claim on it was granted within the wait
     */
    private static boolean awaitClaimable(String identity) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + PATIENCE_MS;
        while (System.currentTimeMillis() < deadline)
        {
            if (canClaim(identity))
            {
                return true;
            }
            Thread.sleep(20L);
        }
        return false;
    }

    /**
     * @param dir the destination's directory
     * @return the names of the temporary files a snapshot writes into it
     */
    private static List<String> partFilesIn(Path dir) throws IOException
    {
        List<String> names = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir))
        {
            entries.forEach(p -> {
                String name = p.getFileName().toString();
                if (name.startsWith(".aiedt-dt-") && name.endsWith(".part")) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    names.add(name);
                }
            });
        }
        Collections.sort(names);
        return names;
    }

    private static void deleteTree(Path dir)
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
                    // best effort
                }
            });
        }
        catch (IOException ignored)
        {
            // best effort
        }
    }

    /**
     * A launcher a test holds by hand: the per-infobase lock it can take to keep the worker away
     * from the boundary, a call that stays inside until it is let return - interrupting it does
     * not end it, the way a platform process is not pulled back - and the record of what was
     * called.
     */
    private static final class LauncherFixture
    {
        /** The per-infobase lock the run takes around its launcher call. */
        final ReentrantLock lock = new ReentrantLock();

        /** Counted down when the launcher call is entered. */
        final CountDownLatch entered = new CountDownLatch(1);

        /** Raised to let the launcher call return. */
        final AtomicBoolean release = new AtomicBoolean();

        /** The launcher calls that were made. */
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        /** The launcher context a snapshot runs over. */
        final ThickClientLaunch.LauncherContext ctx = new ThickClientLaunch.LauncherContext();

        /** What the run's infobase is claimed under. */
        final String identity;

        /** What the run exposes to its stopper, set by the factory when the run starts. */
        volatile DtSnapshotRunner.LiveRun live;

        /**
         * @param work the directory the infobase the run works on is named after
         */
        LauncherFixture(Path work)
        {
            ctx.infobase = fileInfobase(work.resolve("probe-infobase").toString(), "Probe base"); //$NON-NLS-1$
            ctx.infobaseName = ctx.infobase.getName();
            ctx.lock = lock;
            ctx.launcher = heldLauncher();
            identity = InfobaseIdentity.of(ctx.infobase);
        }

        /**
         * @return the launcher whose call records that it was called, waits to be let return -
         *         interrupting it does not end it, the way a platform process is not pulled back -
         *         and leaves a byte in the file it was pointed at, the way a platform process that
         *         finished leaves a written file
         */
        private IThickClientLauncher heldLauncher()
        {
            return (IThickClientLauncher)Proxy.newProxyInstance(
                IThickClientLauncher.class.getClassLoader(),
                new Class<?>[] { IThickClientLauncher.class }, (proxy, method, args) -> {
                    if (!method.getName().equals("exportDtFromInfobase") //$NON-NLS-1$
                        && !method.getName().equals("importDtToInfobase")) //$NON-NLS-1$
                    {
                        return null;
                    }
                    calls.add(method.getName());
                    entered.countDown();
                    while (!release.get())
                    {
                        try
                        {
                            Thread.sleep(20L);
                        }
                        catch (InterruptedException notStoppable)
                        {
                            // a platform process is not pulled back by an interrupt
                        }
                    }
                    if (args[args.length - 1] instanceof Path)
                    {
                        Files.write((Path)args[args.length - 1], new byte[] { 1 });
                    }
                    return null;
                });
        }

        /**
         * @param path where the infobase lives
         * @param name its name
         * @return a file infobase reference, the shape a resolved context carries
         */
        private static InfobaseReference fileInfobase(String path, String name)
        {
            InfobaseReference reference = ModelFactory.eINSTANCE.createInfobaseReference();
            FileConnectionString connection = ModelFactory.eINSTANCE.createFileConnectionString();
            connection.setFile(path);
            reference.setConnectionString(connection);
            reference.setUuid(UUID.randomUUID());
            reference.setName(name);
            return reference;
        }
    }

    /** A load refused after its backup was written names that backup and its size. */
    @Test
    public void aLoadStoppedAfterItsBackupNamesTheBackup()
    {
        DtSnapshotRunner.SnapshotOutcome out = new DtSnapshotRunner.SnapshotOutcome();
        out.path = work.resolve("in.dt"); //$NON-NLS-1$
        out.backupPath = work.resolve("in-backup.dt"); //$NON-NLS-1$
        out.backupSizeBytes = 42L;
        out.backupWritten = true;
        out.error = "the restore_database_snapshot was cancelled before the Designer run it was " //$NON-NLS-1$
            + "waiting for started; that run was not launched"; //$NON-NLS-1$

        JsonObject failed = parsed(DtSnapshotRunner.render(out, DtSnapshotRunner.RESTORE_OPERATION));

        assertFalse(failed.toString(), failed.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(failed.toString(), out.backupPath.toString(), failed.get("backup").getAsString()); //$NON-NLS-1$
        assertEquals(42L, failed.get("backupSizeBytes").getAsLong()); //$NON-NLS-1$
    }

    /** A load refused before its backup was complete names no backup. */
    @Test
    public void aLoadStoppedBeforeItsBackupNamesNone()
    {
        DtSnapshotRunner.SnapshotOutcome out = new DtSnapshotRunner.SnapshotOutcome();
        out.path = work.resolve("in.dt"); //$NON-NLS-1$
        out.backupPath = work.resolve("in-backup.dt"); //$NON-NLS-1$
        out.error = "The restore was not started: the backup of the infobase's current contents " //$NON-NLS-1$
            + "did not complete."; //$NON-NLS-1$

        JsonObject failed = parsed(DtSnapshotRunner.render(out, DtSnapshotRunner.RESTORE_OPERATION));

        assertFalse(failed.toString(), failed.has("backup")); //$NON-NLS-1$
    }
}
