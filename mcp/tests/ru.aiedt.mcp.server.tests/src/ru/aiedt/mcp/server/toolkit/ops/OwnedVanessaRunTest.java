package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class OwnedVanessaRunTest
{
    @Test public void liveClientKeepsResourcesUntilExitAndFinalizesOnce()
    {
        boolean[] alive = {true};
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger finishes = new AtomicInteger();
        OwnedVanessaRun run = new OwnedVanessaRun(() -> alive[0], () -> false,
            () -> { finishes.incrementAndGet(); return "done"; }, closes::incrementAndGet);
        run.observe();
        assertNull(run.result());
        assertEquals(0, closes.get());
        alive[0] = false;
        run.observe(); run.observe();
        assertEquals("done", run.result());
        assertEquals(1, finishes.get());
        assertEquals(1, closes.get());
    }
    @Test public void failedStopKeepsOwnershipAndDoesNotReleaseResources()
    {
        AtomicInteger closes = new AtomicInteger();
        OwnedVanessaRun run = new OwnedVanessaRun(() -> true, () -> false,
            () -> "done", closes::incrementAndGet);
        assertFalse(run.cancel());
        assertNull(run.result());
        assertEquals(0, closes.get());
    }
    @Test public void resultIsPublishedOnlyAfterRelease()
    {
        OwnedVanessaRun[] box = new OwnedVanessaRun[1];
        box[0] = new OwnedVanessaRun(() -> false, () -> true, () -> "done",
            () -> assertNull("collection must not outrun lease release", box[0].result()));
        box[0].observe();
        assertEquals("done", box[0].result());
    }
    @Test public void repeatedConcurrentObservationsFinalizeOnce() throws Exception
    {
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger finishes = new AtomicInteger();
        OwnedVanessaRun run = new OwnedVanessaRun(() -> false, () -> true,
            () -> { finishes.incrementAndGet(); return "done"; }, closes::incrementAndGet);
        Thread a = new Thread(run::observe); Thread b = new Thread(run::observe);
        a.start(); b.start(); a.join(1000); b.join(1000);
        assertEquals(1, finishes.get()); assertEquals(1, closes.get());
    }
    @Test public void resultFailureStillReleasesOnce()
    {
        AtomicInteger closes = new AtomicInteger();
        OwnedVanessaRun run = new OwnedVanessaRun(() -> false, () -> true,
            () -> { throw new IllegalStateException(); }, closes::incrementAndGet);
        run.observe(); run.observe();
        assertEquals(1, closes.get()); assertNotNull(run.result());
    }
    @Test public void ownedPollRejectsForeignScopeAndLaunchArguments() throws Exception
    {
        java.lang.reflect.Field f = VanessaTool.class.getDeclaredField("OWNED"); f.setAccessible(true);
        @SuppressWarnings("unchecked") java.util.Map<String, OwnedVanessaRun> map =
            (java.util.Map<String, OwnedVanessaRun>) f.get(null);
        OwnedVanessaRun run = new OwnedVanessaRun(() -> true, () -> false, () -> "done", () -> {});
        run.projectName = "SyntheticProject"; map.put("synthetic-owned", run);
        try {
            assertTrue(VanessaTool.isOwnedLivePoll("synthetic-owned", "vanessa", java.util.Map.of("runKey", "synthetic-owned")));
            assertFalse(VanessaTool.isOwnedLivePoll("synthetic-owned", "other", java.util.Map.of("runKey", "synthetic-owned")));
            assertFalse(VanessaTool.isOwnedLivePoll("synthetic-owned", "vanessa", java.util.Map.of("runKey", "synthetic-owned", "projectName", "Foreign")));
            assertFalse(VanessaTool.isOwnedLivePoll("synthetic-owned", "vanessa", java.util.Map.of("runKey", "synthetic-owned", "scenarioText", "new run")));
        } finally { map.remove("synthetic-owned"); }
    }
    @Test public void canceledPreparationDoesNotInvokeLaunchPort() throws Exception
    {
        AtomicInteger launches = new AtomicInteger();
        VanessaTool.stopTheClient("synthetic-prestart");
        try {
            assertNull(VanessaTool.launchOwned("synthetic-prestart", () -> { launches.incrementAndGet(); return null; }));
            assertEquals(0, launches.get());
        } finally { VanessaTool.refusedBeforeLaunch("synthetic-prestart"); }
    }
    @Test public void cleanupExceptionPublishesFailureInsteadOfEndlessPending()
    {
        OwnedVanessaRun run = new OwnedVanessaRun(() -> false, () -> true, () -> "done",
            () -> { throw new IllegalStateException(); });
        run.observe();
        assertNotNull(run.result()); assertTrue(run.result().contains("cleanupWarning"));
        assertTrue(run.completedAt > 0);
    }
    @Test public void waitObservesExitWithinBudget() throws Exception
    {
        java.util.concurrent.atomic.AtomicBoolean alive = new java.util.concurrent.atomic.AtomicBoolean(true);
        OwnedVanessaRun run = new OwnedVanessaRun(alive::get, () -> false, () -> "done", () -> {});
        Thread exit = new Thread(() -> { try { Thread.sleep(40); } catch (InterruptedException ignored) {} alive.set(false); });
        exit.start(); assertEquals("done", run.await(1000)); exit.join(1000);
        assertTrue(run.phaseObservedAt >= run.startedAt);
    }
    @Test public void cancelInsideLaunchStopsOnlyFreshFakeProcess() throws Exception
    {
        AtomicInteger stops = new AtomicInteger();
        Process fake = new Process() {
            public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
            public java.io.InputStream getInputStream() { return java.io.InputStream.nullInputStream(); }
            public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
            public int waitFor() { return 0; } public int exitValue() { return 0; }
            public void destroy() { stops.incrementAndGet(); }
            public Process destroyForcibly() { stops.incrementAndGet(); return this; }
            public java.util.stream.Stream<ProcessHandle> descendants() { return java.util.stream.Stream.empty(); }
        };
        try {
            assertSame(fake, VanessaTool.launchOwned("synthetic-midspawn", () -> {
                VanessaTool.stopTheClient("synthetic-midspawn"); return fake;
            }));
            assertEquals(1, stops.get());
        } finally { VanessaTool.refusedBeforeLaunch("synthetic-midspawn"); }
    }
    @Test public void cancelWaitsForAtomicPublicationAfterLaunchReturns() throws Exception
    {
        java.lang.reflect.Field lockField = VanessaTool.class.getDeclaredField("LAUNCHING");
        lockField.setAccessible(true); Object lock = lockField.get(null);
        java.util.concurrent.atomic.AtomicReference<Process> published = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Process> canceledTarget = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        Process fake = new Process() {
            public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
            public java.io.InputStream getInputStream() { return java.io.InputStream.nullInputStream(); }
            public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
            public int waitFor() { return 0; } public int exitValue() { return 0; }
            public void destroy() {}
        };
        Thread cancel = new Thread(() -> { entered.countDown(); canceledTarget.set(VanessaTool.cancellationTarget("synthetic-publication", published)); });
        synchronized (lock) {
            cancel.start(); assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));
            published.set(fake);
        }
        cancel.join(1000); assertSame(fake, canceledTarget.get());
        VanessaTool.refusedBeforeLaunch("synthetic-publication");
    }
    @Test public void failedObservationNeverMeansExit()
    {
        AtomicInteger closes = new AtomicInteger();
        OwnedVanessaRun run = new OwnedVanessaRun(() -> { throw new IllegalStateException(); },
            () -> false, () -> "done", closes::incrementAndGet);
        run.observe();
        assertNull(run.result());
        assertEquals(0, closes.get());
        assertEquals("unknown", run.phase());
    }
}
