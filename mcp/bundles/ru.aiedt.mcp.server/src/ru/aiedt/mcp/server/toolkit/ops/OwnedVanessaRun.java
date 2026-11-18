package ru.aiedt.mcp.server.toolkit.ops;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** One owned client's exit decision; observation never stops a process. */
final class OwnedVanessaRun
{
    /** How often a cleanup that could not confirm its release is retried. */
    private static final long CLEANUP_RETRY_MS = 1000L;

    private static final String FINISH_FAILED =
        "{\"success\":false,\"error\":\"Vanessa result finalization failed.\"}"; //$NON-NLS-1$

    private static final String CLEANUP_WARNING =
        "{\"success\":false,\"cleanupWarning\":\"The client exited, but resource cleanup was not confirmed.\"}"; //$NON-NLS-1$

    private final BooleanSupplier alive;
    private final BooleanSupplier stop;
    private final Supplier<String> finish;
    private final BooleanSupplier cleanup;
    private final AtomicBoolean observing = new AtomicBoolean();
    private final AtomicBoolean finalizing = new AtomicBoolean();
    private volatile String completed;
    private volatile String result;
    private volatile boolean cleanupPending;
    private volatile long lastCleanupAttemptAt;
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

    /** Whether the containment release is still to be confirmed and may be retried. */
    boolean cleanupPending()
    {
        return cleanupPending;
    }

    volatile long phaseObservedAt = startedAt;
    private volatile String phase = "processStarted";

    /**
     * A run whose release is an action with no report. Kept for the existing callers; a release
     * that throws is treated as the old cleanup warning, not as a retryable containment.
     */
    OwnedVanessaRun(BooleanSupplier alive, BooleanSupplier stop, Supplier<String> finish,
        Runnable release)
    {
        this(alive, stop, finish, () -> {
            release.run();
            return true;
        });
    }

    /**
     * A run whose release reports whether it completed. {@code false} keeps the result unpublished
     * and the resources owned; the observation is retried later.
     */
    OwnedVanessaRun(BooleanSupplier alive, BooleanSupplier stop, Supplier<String> finish,
        BooleanSupplier cleanup)
    {
        this.alive = alive;
        this.stop = stop;
        this.finish = finish;
        this.cleanup = cleanup;
    }

    void observe()
    {
        if (result != null || !observing.compareAndSet(false, true)) return;
        try
        {
            if (!finalizing.get())
            {
                boolean running;
                try { running = alive.getAsBoolean(); }
                catch (RuntimeException unavailable) { setPhase("unknown"); return; }
                if (running) return;
                if (!finalizing.compareAndSet(false, true)) return;
                setPhase("finalizing");
                try { completed = finish.get(); }
                catch (RuntimeException failure) { completed = FINISH_FAILED; }
            }
            long now = System.currentTimeMillis();
            if (cleanupPending && now - lastCleanupAttemptAt < CLEANUP_RETRY_MS) return;
            lastCleanupAttemptAt = now;
            boolean done;
            try { done = cleanup.getAsBoolean(); }
            catch (RuntimeException failure)
            {
                // A global resource (infobase hold, claim) failed. That is the original
                // non-retry contract: publish the warning once, do not pretend a retry.
                completed = CLEANUP_WARNING;
                done = true;
            }
            if (!done)
            {
                cleanupPending = true;
                setPhase("cleanupRequired");
                return;
            }
            cleanupPending = false;
            setPhase("completed");
            completedAt = System.currentTimeMillis();
            result = completed;
        }
        finally { observing.set(false); }
    }

    boolean cancel()
    {
        if (result != null) return true;
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
