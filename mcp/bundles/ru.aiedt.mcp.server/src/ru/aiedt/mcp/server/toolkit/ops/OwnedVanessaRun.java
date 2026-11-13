package ru.aiedt.mcp.server.toolkit.ops;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** One owned client's exit decision; observation never stops a process. */
final class OwnedVanessaRun
{
    private final BooleanSupplier alive;
    private final BooleanSupplier stop;
    private final Supplier<String> finish;
    private final Runnable release;
    private final AtomicBoolean observing = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile String result;
    volatile long completedAt;
    volatile ru.aiedt.mcp.server.support.ClientDialogReader.Outcome windows =
        ru.aiedt.mcp.server.support.ClientDialogReader.Outcome.none();
    final long startedAt = System.currentTimeMillis();
    volatile String projectName;
    volatile BooleanSupplier ownershipHeld = () -> false;
    boolean cleanupRequired()
    {
        try { return ownershipHeld.getAsBoolean(); }
        catch (RuntimeException unavailable) { return true; }
    }
    volatile long phaseObservedAt = startedAt;
    private volatile String phase = "processStarted";

    OwnedVanessaRun(BooleanSupplier alive, BooleanSupplier stop, Supplier<String> finish,
        Runnable release)
    {
        this.alive = alive;
        this.stop = stop;
        this.finish = finish;
        this.release = release;
    }

    void observe()
    {
        if (finished.get() || !observing.compareAndSet(false, true)) return;
        try
        {
            boolean running;
            try { running = alive.getAsBoolean(); }
            catch (RuntimeException unavailable) { setPhase("unknown"); return; }
            if (running || !finished.compareAndSet(false, true)) return;
            setPhase("finalizing");
            String completed;
            try { completed = finish.get(); }
            catch (RuntimeException failure)
            { completed = "{\"success\":false,\"error\":\"Vanessa result finalization failed.\"}"; }
            try { release.run(); }
            catch (RuntimeException failure) {
                completed = "{\"success\":false,\"cleanupWarning\":\"The client exited, but resource cleanup was not confirmed.\"}";
            }
            setPhase("completed");
            completedAt = System.currentTimeMillis();
            result = completed;
        }
        finally { observing.set(false); }
    }

    boolean cancel()
    {
        if (result != null) return true;
        if (finished.get()) return false;
        setPhase("stopping");
        boolean stopped;
        try { stopped = stop.getAsBoolean(); }
        catch (RuntimeException unavailable) { stopped = false; }
        observe();
        return stopped;
    }

    void setPhase(String next)
    {
        phase = next;
        phaseObservedAt = System.currentTimeMillis();
    }

    String await(long milliseconds)
    {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0, milliseconds));
        do {
            observe();
            if (result != null || System.nanoTime() >= deadline) return result;
            try { Thread.sleep(Math.min(50, Math.max(1, milliseconds))); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return null; }
        } while (true);
    }

    String result() { return result; }
    String phase() { return phase; }
}
