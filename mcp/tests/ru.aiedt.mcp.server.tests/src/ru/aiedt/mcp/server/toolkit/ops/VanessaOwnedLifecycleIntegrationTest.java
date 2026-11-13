package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import ru.aiedt.mcp.server.support.PendingWorkRegistry;

/** Real coordinator/registry scheduling with synthetic state only: no native client or infobase. */
public class VanessaOwnedLifecycleIntegrationTest
{
    @SuppressWarnings("unchecked") private static <T> T field(String name) throws Exception
    {
        Field f = VanessaTool.class.getDeclaredField(name); f.setAccessible(true); return (T) f.get(null);
    }
    private static Method method(String name, Class<?>... types) throws Exception
    {
        Method m = VanessaTool.class.getDeclaredMethod(name, types); m.setAccessible(true); return m;
    }

    @Test public void actualMonitorFinalizesWithoutPollingAndStopsWhenEmpty() throws Exception
    {
        Map<String, OwnedVanessaRun> owned = field("OWNED");
        assertTrue("this fixture must not touch another run", owned.isEmpty());
        ScheduledFuture<?> before = field("monitorTask");
        assertTrue(before == null || before.isDone());
        String key = "synthetic-monitor-" + UUID.randomUUID();
        CountDownLatch released = new CountDownLatch(1);
        AtomicInteger finishes = new AtomicInteger();
        OwnedVanessaRun run = new OwnedVanessaRun(() -> false, () -> false,
            () -> { finishes.incrementAndGet(); return "synthetic-final"; }, released::countDown);
        ScheduledThreadPoolExecutor scheduler = field("OWNED_MONITOR");
        try {
            owned.put(key, run);
            method("monitorOwnedRuns").invoke(null);
            assertTrue("the production monitor, not a poll, must release", released.await(4, TimeUnit.SECONDS));
            assertEquals(1, finishes.get());
            // A barrier on the same executor waits until publication after release.
            scheduler.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertEquals("synthetic-final", run.result());
            owned.remove(key, run);
            CountDownLatch nextTick = new CountDownLatch(1);
            scheduler.schedule(nextTick::countDown, 1500, TimeUnit.MILLISECONDS);
            assertTrue(nextTick.await(3, TimeUnit.SECONDS));
            assertNull("empty production monitor unregisters itself", field("monitorTask"));
        } finally { owned.remove(key, run); }
    }

    @Test public void preparationHandoffFollowsRealWorkExitEvenWhenFutureCanceled() throws Exception
    {
        exerciseHandoff(false);
        exerciseHandoff(true);
    }

    private void exerciseHandoff(boolean cancelFuture) throws Exception
    {
        Map<String, OwnedVanessaRun> owned = field("OWNED");
        Map<String, AtomicBoolean> exits = field("PREPARATION_EXIT");
        String key = "synthetic-preparation-" + UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(1), leave = new CountDownLatch(1), workLeft = new CountDownLatch(1);
        AtomicInteger releases = new AtomicInteger();
        OwnedVanessaRun run = new OwnedVanessaRun(() -> false, () -> false, () -> "{\"syntheticFinal\":true}", releases::incrementAndGet);
        PendingWorkRegistry registry = PendingWorkRegistry.VANESSA;
        PendingWorkRegistry.PendingEntry entry = registry.getOrStart(key, () -> {
            entered.countDown();
            try { if (!leave.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout"); }
            catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
            return "owned";
        });
        AtomicBoolean exited = new AtomicBoolean();
        exits.put(key, exited); entry.attachWorkExit(() -> { exited.set(true); workLeft.countDown(); });
        owned.put(key, run);
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            if (cancelFuture) assertTrue(entry.future.cancel(false));
            String pending = (String) method("collect", String.class, Map.class).invoke(new VanessaTool(), key,
                Map.of("runKey", key, "waitSeconds", "0"));
            assertTrue(pending, pending.contains("Pending"));
            assertSame(entry, registry.get(key));
            assertEquals(0, releases.get());
            assertFalse(exited.get());
            leave.countDown(); assertTrue(workLeft.await(2, TimeUnit.SECONDS));
            String done = (String) method("collect", String.class, Map.class).invoke(new VanessaTool(), key,
                Map.of("runKey", key, "waitSeconds", "0"));
            assertTrue(done, done.contains("syntheticFinal"));
            assertEquals(1, releases.get()); assertNull(registry.get(key)); assertFalse(owned.containsKey(key));
        } finally {
            leave.countDown(); workLeft.await(2, TimeUnit.SECONDS);
            owned.remove(key, run); exits.remove(key); registry.remove(key, entry);
        }
    }
}
