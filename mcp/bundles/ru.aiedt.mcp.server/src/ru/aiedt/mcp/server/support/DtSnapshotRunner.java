/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Whole-infobase {@code .dt} snapshots: dumping an infobase into one file, and loading one back.
 * <p>
 * One launcher call each, unlike the object export - {@code exportDtFromInfobase} and
 * {@code importDtToInfobase} on the thick client - so the sequence is short and the guards around it
 * carry the weight. A dump is written beside its destination and moved over it only after it proves
 * to be this run's product, so a file an earlier run left there cannot be reported as this one's. A
 * load writes a backup of what the infobase holds now first, and refuses to touch the infobase when
 * that backup did not materialize: a load replaces everything it holds, and the backup is the only
 * way back.
 * </p>
 * <p>
 * Both operations take the infobase monopoly for their duration. Among the things that means: while
 * a load runs no other AI-EDT process may claim the same infobase, and a caller that arrives during
 * it is told who holds it rather than left waiting on a platform error.
 * </p>
 * <p>
 * The run goes through {@link PendingWorkRegistry#SNAPSHOT}, so a snapshot that outlasts the soft
 * timeout answers {@code Pending} with a runKey instead of blocking the call. The launcher call
 * itself runs on a worker thread under a budget: the caller's cancellation and the budget's end both
 * keep a launch that has not crossed its boundary from starting, and report a launch that has as
 * still running rather than pretending it stopped.
 * </p>
 */
public final class DtSnapshotRunner
{
    /** The operation that dumps an infobase into a {@code .dt}. */
    public static final String EXPORT_OPERATION = "export_database_snapshot"; //$NON-NLS-1$

    /** The operation that loads a {@code .dt} back into its infobase. */
    public static final String RESTORE_OPERATION = "restore_database_snapshot"; //$NON-NLS-1$

    /**
     * What {@link SnapshotIo#markLoaded} answers when the environment keeps no store record. The
     * load still stands; the answer does not claim the copy was marked.
     */
    static final String NO_STORE_RECORD = "no store record"; //$NON-NLS-1$

    /** The step a successful load names, the same one an incremental update is sent to. */
    static final String REBUILD_COPY_STEP =
        "sync_control syncOperation=rebuild_dump_info confirm=true"; //$NON-NLS-1$

    /** The soft wait's clamp range, the same one update_database and export_object use. */
    private static final int MIN_TIMEOUT_SECONDS = 5;

    private static final int MAX_TIMEOUT_SECONDS = 120;

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /**
     * How long one launcher call is given before it is abandoned.
     * <p>
     * A whole-infobase dump or load of a production base runs in minutes, and the platform call
     * cannot be interrupted once the process is up. Ten minutes is long enough that a slow but
     * working run finishes, and bounded so a wedged one returns the caller an answer naming what is
     * still running instead of holding it for the session.
     * </p>
     */
    static final long SNAPSHOT_BUDGET_MS = 600_000L;

    /** The suffix of the temporary file a dump is written to before it proves itself. */
    private static final String PART_SUFFIX = ".part"; //$NON-NLS-1$

    /** How a derived backup file is stamped, so two restores of one file do not collide. */
    private static final DateTimeFormatter BACKUP_STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"); //$NON-NLS-1$

    private DtSnapshotRunner()
    {
        // static utility
    }

    /**
     * What one snapshot run works through.
     * <p>
     * The seam exists for the same reason the object export has one: the arguments, the guards, the
     * Pending answer and the cancellation are what this class is responsible for, and none of them
     * needs an infobase to be exercised. The production implementation is {@link EdtIo}; a test
     * supplies a stand-in that writes and reads files.
     * </p>
     */
    public interface SnapshotIo
    {
        /**
         * @return what identifies the infobase to the monopoly claim; never empty
         */
        String infobaseIdentity();

        /**
         * Claims the infobase for this run's duration.
         *
         * @return the refusal naming who holds it, or {@code null} when the claim was granted
         */
        String takeLock();

        /** Releases the claim taken by {@link #takeLock()}; safe when nothing was taken. */
        void releaseLock();

        /**
         * Runs the dump.
         *
         * @param target the file to write
         * @param cancelled the caller's cancellation flag
         * @throws Exception when the dump failed
         */
        void exportTo(Path target, BooleanSupplier cancelled) throws Exception;

        /**
         * Runs the load.
         *
         * @param source the {@code .dt} to load
         * @param cancelled the caller's cancellation flag
         * @throws Exception when the load failed
         */
        void importFrom(Path source, BooleanSupplier cancelled) throws Exception;

        /**
         * Records that a finished load replaced the infobase, on the store's dump-info record. A
         * dump does not call this. The default keeps no store: a stand-in that does not override
         * it leaves the copy unmarked, and the answer does not claim otherwise.
         *
         * @param source the {@code .dt} that was loaded
         * @param when the moment the load finished, as text
         * @return {@code null} when the record was written, {@link DtSnapshotRunner#NO_STORE_RECORD}
         *         when this environment keeps no store, or why the record could not be written
         */
        default String markLoaded(Path source, String when)
        {
            return NO_STORE_RECORD;
        }
    }

    /**
     * Builds the environment one snapshot runs against. Split from the work so a test can hand in
     * its own stand-in.
     */
    public interface IoFactory
    {
        /**
         * @param projectName the project that owns the infobase
         * @param applicationId the application naming the infobase; may be {@code null}
         * @param operation which of the two operations is being run, for the claim's own record
         * @param runKey this run's runKey
         * @param live the live-run handle the stopper reaches
         * @param cancelled the caller's cancellation flag
         * @return the environment, or a resolution failure answered instead of it
         */
        IoResolution ioFor(String projectName, String applicationId, String operation, String runKey,
            LiveRun live, BooleanSupplier cancelled);
    }

    /** Either an environment to run against, or the refusal to answer with. */
    public static final class IoResolution
    {
        /** The environment, when resolution succeeded. */
        public final SnapshotIo io;

        /** The infobase's name, when one was resolved far enough to name it. */
        public final String infobaseName;

        /** The resolution failure, or {@code null}. */
        public final String error;

        /** The failure kind of {@link #error}, or {@code null}. */
        public final String failureKind;

        private IoResolution(SnapshotIo io, String infobaseName, String error, String failureKind)
        {
            this.io = io;
            this.infobaseName = infobaseName;
            this.error = error;
            this.failureKind = failureKind;
        }

        /**
         * @param io the environment
         * @param infobaseName the infobase's name
         * @return a successful resolution
         */
        public static IoResolution of(SnapshotIo io, String infobaseName)
        {
            return new IoResolution(io, infobaseName, null, null);
        }

        /**
         * @param error the refusal text
         * @param failureKind its {@link ErrorTags} kind
         * @return a failed resolution
         */
        public static IoResolution refused(String error, String failureKind)
        {
            return new IoResolution(null, null, error, failureKind);
        }
    }

    /**
     * The production environment factory: resolves the thick-client launcher the way every other
     * launcher call of this server does.
     */
    public static final IoFactory EDT_IO =
        (projectName, applicationId, operation, runKey, live, cancelled) -> {
            ThickClientLaunch.LauncherContext ctx =
                ThickClientLaunch.resolveLauncher(projectName, applicationId);
            if (ctx.error != null)
            {
                return IoResolution.refused(ctx.error, ctx.failureKind);
            }
            return IoResolution.of(new EdtIo(ctx, operation, runKey, live, cancelled),
                ctx.infobaseName);
        };

    /** The runs of this domain that are live right now, keyed by runKey. */
    private static final Map<String, LiveRun> LIVE = new ConcurrentHashMap<>();

    static
    {
        // tasks/cancel reaches this domain only through the stopper. Without it the registry drops
        // the entry while the platform call carries on writing the file or replacing the infobase.
        PendingWorkRegistry.SNAPSHOT.stopsWith(DtSnapshotRunner::stopTheRun);
    }

    /** What a live run exposes to its own stopper. */
    static final class LiveRun
    {
        /**
         * The launch boundary of the current launcher call, or {@code null} before it starts.
         * <p>
         * Claimed by whoever crosses it first: the worker under the per-infobase lock, right before
         * it calls the launcher, or this run's abandonment, which claims it before it declares
         * itself. Nobody else claims it - a stopper that took it would make the abandonment read a
         * boundary it did not take as a call already committed, and report a platform process for a
         * worker that never reached the launcher. A launcher call that claimed it is committed and
         * reported as still running when the wait gives up; one that did not never reaches the
         * platform.
         * </p>
         */
        volatile AtomicBoolean launchClaim;

        /** Counted down by the stopper; the run's wait polls it. */
        final CountDownLatch stopped = new CountDownLatch(1);
    }

    /**
     * Stops the run a registry cancel names: wakes the wait so the abandonment is answered now
     * rather than at the budget's end.
     * <p>
     * The launch boundary itself is left to the two sides that can decide it - the worker, under the
     * per-infobase lock right before the launcher, and the abandonment, before it declares itself.
     * This stopper must not claim it as a third party: the abandonment reads a boundary it did not
     * take as a launcher call that is already committed, so a claim made here over a worker that is
     * still waiting for the lock would be reported as a platform process running when none was ever
     * started. Waking the wait is enough - the abandonment is the side that claims, and a worker
     * that has not reached the boundary by then finds it taken and starts nothing.
     * </p>
     *
     * @param runKey the run's key
     * @return {@link PendingWorkRegistry.StopOutcome#NOTHING_TO_STOP} when no run is live, otherwise
     *         {@link PendingWorkRegistry.StopOutcome#STILL_RUNNING}, whether or not the Designer run
     *         has started - this side only wakes the wait, and the run's own answer under its runKey
     *         says whether a Designer run was launched
     */
    static PendingWorkRegistry.StopOutcome stopTheRun(String runKey)
    {
        LiveRun live = LIVE.get(runKey);
        if (live == null)
        {
            return PendingWorkRegistry.StopOutcome.NOTHING_TO_STOP;
        }
        live.stopped.countDown();
        return PendingWorkRegistry.StopOutcome.STILL_RUNNING;
    }

    /**
     * The whole dispatch of {@code export_database_snapshot}: the argument guards, then the work
     * through {@link PendingWorkRegistry#SNAPSHOT}, answering within the soft timeout or with a
     * {@code Pending} envelope carrying the runKey.
     *
     * @param params the call arguments, for the soft timeout a poll waits with
     * @param projectName the project a call names
     * @param applicationId the application naming the infobase; may be {@code null}
     * @param pathRaw the {@code .dt} file to write
     * @param runKeyParam a previously-issued runKey to poll, or {@code null}
     * @param cancel whether the call asks to stop the run {@code runKeyParam} names
     * @param io the environment factory
     * @param starter the tool name a poll of this run arrives under
     * @return the answer, or the {@code Pending} envelope
     */
    public static String dispatchExport(Map<String, String> params, String projectName,
        String applicationId, String pathRaw, String runKeyParam, boolean cancel, IoFactory io,
        String starter)
    {
        return dispatch(false, params, projectName, applicationId, pathRaw, null, runKeyParam, cancel,
            io, starter);
    }

    /**
     * The whole dispatch of {@code restore_database_snapshot}: as the export, with the backup
     * resolved before anything runs, so the answer - the Pending envelope included - names the file
     * the infobase's current contents are saved to.
     *
     * @param params the call arguments, for the soft timeout a poll waits with
     * @param projectName the project a call names
     * @param applicationId the application naming the infobase; may be {@code null}
     * @param pathRaw the {@code .dt} to load
     * @param backupToRaw where the backup goes, or {@code null} to derive one beside the source
     * @param runKeyParam a previously-issued runKey to poll, or {@code null}
     * @param cancel whether the call asks to stop the run {@code runKeyParam} names
     * @param io the environment factory
     * @param starter the tool name a poll of this run arrives under
     * @return the answer, or the {@code Pending} envelope
     */
    public static String dispatchRestore(Map<String, String> params, String projectName,
        String applicationId, String pathRaw, String backupToRaw, String runKeyParam, boolean cancel,
        IoFactory io, String starter)
    {
        return dispatch(true, params, projectName, applicationId, pathRaw, backupToRaw, runKeyParam,
            cancel, io, starter);
    }

    /**
     * The dispatch both operations share: the argument guards, the runKey, and the soft wait.
     *
     * @param restore {@code true} for a load, {@code false} for a dump
     * @param params the call arguments
     * @param projectName the project a call names
     * @param applicationId the application naming the infobase; may be {@code null}
     * @param pathRaw the file to write or to load
     * @param backupToRaw where a load's backup goes; ignored for a dump
     * @param runKeyParam a previously-issued runKey to poll, or {@code null}
     * @param cancel whether the call asks to stop the run {@code runKeyParam} names
     * @param io the environment factory
     * @param starter the tool name a poll of this run arrives under
     * @return the answer, or the {@code Pending} envelope
     */
    private static String dispatch(boolean restore, Map<String, String> params, String projectName,
        String applicationId, String pathRaw, String backupToRaw, String runKeyParam, boolean cancel,
        IoFactory io, String starter)
    {
        String operation = restore ? RESTORE_OPERATION : EXPORT_OPERATION;
        // A cancel outranks a poll: the same {runKey, cancel} pair means the same thing here as it
        // does for update_database, and a caller that asked to stop the run must not be answered
        // with the run still going.
        if (runKeyParam != null && !runKeyParam.isEmpty() && cancel)
        {
            return cancelTheRun(runKeyParam, operation);
        }
        if (runKeyParam != null && !runKeyParam.isEmpty())
        {
            return collect(runKeyParam, params, operation);
        }
        long timeoutMs = TimeoutArgs.readSeconds(params, DEFAULT_TIMEOUT_SECONDS,
            MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS) * 1000L;

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required for " + operation + ".") //$NON-NLS-1$ //$NON-NLS-2$
                .put("operation", operation).toJson(); //$NON-NLS-1$
        }
        if (pathRaw == null || pathRaw.isEmpty())
        {
            return ToolResult.error("path is required for " + operation //$NON-NLS-1$
                + (restore ? " (the .dt file to load)." : " (the .dt file to write).")) //$NON-NLS-1$ //$NON-NLS-2$
                .put("operation", operation).toJson(); //$NON-NLS-1$
        }
        Path path;
        try
        {
            path = Paths.get(pathRaw).toAbsolutePath();
        }
        catch (InvalidPathException bad)
        {
            return ToolResult.error("path is not a valid file path: " //$NON-NLS-1$
                + ThickClientLaunch.oneLine(ThickClientLaunch.causeChainText(bad)))
                .put("operation", operation) //$NON-NLS-1$
                .put(ErrorTags.INVALID_OUTPUT_PATH.wire(), Boolean.TRUE).toJson();
        }

        Path backup = null;
        if (restore)
        {
            String missing = sourceProblem(path);
            if (missing != null)
            {
                return ToolResult.error("The restore was not started: " + missing) //$NON-NLS-1$
                    .put("operation", operation) //$NON-NLS-1$
                    .put("path", path.toString()) //$NON-NLS-1$
                    .put(ErrorTags.INPUT_MISSING.wire(), Boolean.TRUE).toJson();
            }
            Path wanted;
            try
            {
                wanted = backupToRaw == null || backupToRaw.isEmpty() ? derivedBackupPath(path)
                    : Paths.get(backupToRaw).toAbsolutePath();
            }
            catch (InvalidPathException bad)
            {
                return ToolResult.error("backupTo is not a valid file path: " //$NON-NLS-1$
                    + ThickClientLaunch.oneLine(ThickClientLaunch.causeChainText(bad)))
                    .put("operation", operation) //$NON-NLS-1$
                    .put(ErrorTags.INVALID_OUTPUT_PATH.wire(), Boolean.TRUE).toJson();
            }
            String occupied = backupProblem(wanted);
            if (occupied != null)
            {
                return ToolResult.error(occupied).put("operation", operation) //$NON-NLS-1$
                    .put("backupTo", wanted.toString()) //$NON-NLS-1$
                    .put(ErrorTags.ALREADY_EXISTS.wire(), Boolean.TRUE).toJson();
            }
            backup = wanted;
        }
        else
        {
            String occupied = destinationProblem(path);
            if (occupied != null)
            {
                return ToolResult.error(occupied).put("operation", operation) //$NON-NLS-1$
                    .put("path", path.toString()) //$NON-NLS-1$
                    .put(ErrorTags.ALREADY_EXISTS.wire(), Boolean.TRUE).toJson();
            }
        }

        String runKey = "dt-" + UUID.randomUUID(); //$NON-NLS-1$
        PendingWorkRegistry registry = PendingWorkRegistry.SNAPSHOT;
        registry.pruneExpired();
        LiveRun live = new LiveRun();
        Path backupPath = backup;
        PendingWorkRegistry.PendingEntry entry = registry.getOrStart(runKey,
            pending -> runTheSnapshot(pending, io, restore, projectName, applicationId, path,
                backupPath, runKey, live, operation));
        entry.startedBy = starter;
        entry.subject = operation;
        entry.workKind = operation;

        String done = entry.await(timeoutMs);
        if (done != null)
        {
            registry.remove(runKey, entry);
            return done;
        }
        return PendingEnvelope.mark(ToolResult.success()
            .put("operation", operation) //$NON-NLS-1$
            .put("status", "Pending") //$NON-NLS-1$ //$NON-NLS-2$
            .put("runKey", runKey) //$NON-NLS-1$
            .put("path", path.toString()) //$NON-NLS-1$
            .put("backupTo", backupPath == null ? null : backupPath.toString()) //$NON-NLS-1$
            .put("elapsedMs", entry.elapsedMs()) //$NON-NLS-1$
            .put("waitedMs", timeoutMs) //$NON-NLS-1$
            .put("hint", "The snapshot is still running. Call again with runKey=\"" + runKey //$NON-NLS-1$
                + "\" to resume waiting, or stop it with tasks/cancel and that runKey.")).toJson(); //$NON-NLS-1$
    }

    /**
     * The work body: resolves the environment, runs the snapshot, and renders the answer.
     *
     * @param entry this run's registry entry, for its cancellation flag
     * @param io the environment factory
     * @param restore {@code true} for a load
     * @param projectName the project a call names
     * @param applicationId the application naming the infobase; may be {@code null}
     * @param path the file to write or to load
     * @param backup where a load's backup goes; {@code null} for a dump
     * @param runKey this run's runKey
     * @param live the live-run handle the stopper reaches
     * @param operation which operation is running
     * @return the answer
     */
    private static String runTheSnapshot(PendingWorkRegistry.PendingEntry entry, IoFactory io,
        boolean restore, String projectName, String applicationId, Path path, Path backup,
        String runKey, LiveRun live, String operation)
    {
        BooleanSupplier cancelled =
            () -> entry.cancellation != null && entry.cancellation.isCancelled();
        IoResolution resolution =
            io.ioFor(projectName, applicationId, operation, runKey, live, cancelled);
        if (resolution.error != null)
        {
            return ToolResult.error(resolution.error)
                .put("operation", operation) //$NON-NLS-1$
                .put("projectName", projectName) //$NON-NLS-1$
                .put(resolution.failureKind != null ? resolution.failureKind
                    : ErrorTags.RESOLVE_FAILED.wire(), Boolean.TRUE).toJson();
        }
        SnapshotOutcome outcome = perform(resolution.io, restore, path, backup, cancelled);
        outcome.infobaseName = resolution.infobaseName;
        if (outcome.infobaseBusy)
        {
            ToolResult busy = ToolResult.error(outcome.error)
                .put("operation", operation) //$NON-NLS-1$
                .put("projectName", projectName) //$NON-NLS-1$
                .put(ErrorTags.BUSY.wire(), Boolean.TRUE);
            putHolders(busy, applicationId, projectName);
            return busy.toJson();
        }
        return render(outcome, operation);
    }

    /**
     * Runs one snapshot against an already-resolved environment: claim the infobase, write a load's
     * backup, run the launcher call, release - unless the launcher call is still running, in which
     * case the claim stays with it.
     *
     * @param io the environment
     * @param restore {@code true} for a load
     * @param path the file to write or to load
     * @param backup where a load's backup goes; {@code null} for a dump
     * @param cancelled the caller's cancellation flag
     * @return what the run came to
     */
    private static SnapshotOutcome perform(SnapshotIo io, boolean restore, Path path, Path backup,
        BooleanSupplier cancelled)
    {
        SnapshotOutcome out = new SnapshotOutcome();
        out.path = path;
        out.backupPath = backup;
        long startedAt = System.currentTimeMillis();

        String identity = io.infobaseIdentity();
        if (identity == null || identity.isEmpty())
        {
            out.error = "This infobase cannot be identified, so nothing vouches for it being free. " //$NON-NLS-1$
                + "Reconnect it in EDT and retry."; //$NON-NLS-1$
            out.failureKind = ErrorTags.RESOLVE_FAILED.wire();
            return out;
        }

        String lockRefusal = io.takeLock();
        if (lockRefusal != null)
        {
            out.error = lockRefusal;
            out.failureKind = ErrorTags.BUSY.wire();
            out.infobaseBusy = true;
            return out;
        }
        boolean claimHandedOver = false;
        try
        {
            if (restore)
            {
                // Before the infobase is touched. A load replaces everything it holds, so a backup
                // that did not materialize ends the run here - the alternative is an infobase whose
                // previous contents exist nowhere.
                try
                {
                    WriteFailure backupFailure =
                        writeFile(out, io, backup, Instant.now(), cancelled, true);
                    if (backupFailure != null)
                    {
                        out.error = "The restore was not started: " + backupFailure.text; //$NON-NLS-1$
                        out.failureKind = backupFailure.failureKind;
                        return out;
                    }
                }
                catch (Throwable backupFailed)
                {
                    recordFailure(out, backupFailed);
                    claimHandedOver = handTheClaimToTheRunningCall(backupFailed, io);
                    out.error = "The restore was not started: the backup of the infobase's " //$NON-NLS-1$
                        + "current contents did not complete. " + out.error; //$NON-NLS-1$
                    return out;
                }
                out.backupSizeBytes = sizeOf(backup);
                out.backupWritten = true;
            }

            if (restore)
            {
                io.importFrom(path, cancelled);
            }
            else
            {
                WriteFailure exportFailure =
                    writeFile(out, io, path, Instant.now(), cancelled, false);
                if (exportFailure != null)
                {
                    out.error = exportFailure.text;
                    out.failureKind = exportFailure.failureKind;
                    return out;
                }
            }
            out.sizeBytes = sizeOf(path);
            out.done = true;
            if (restore)
            {
                noteTheLoad(out, io, path);
            }
        }
        catch (Throwable failed)
        {
            recordFailure(out, failed);
            claimHandedOver = handTheClaimToTheRunningCall(failed, io);
        }
        finally
        {
            if (!claimHandedOver)
            {
                io.releaseLock();
            }
            out.lockHeldForProcess = claimHandedOver;
            out.durationMs = System.currentTimeMillis() - startedAt;
        }
        return out;
    }

    /**
     * Writes a dump into a temporary sibling of {@code dest} and moves it over the destination only
     * after it proves to be this run's non-empty product.
     *
     * @param out the outcome being filled; the temporary file is named there when a launcher call
     *            that is still running keeps it
     * @param io the environment
     * @param dest the file the caller asked for
     * @param notBefore the moment the run started
     * @param cancelled the caller's cancellation flag
     * @param backup whether this write is a load's backup, for the refusals' wording
     * @return the failure, or {@code null} when the file is in place
     * @throws Throwable whatever the launcher threw; the caller classifies it
     */
    private static WriteFailure writeFile(SnapshotOutcome out, SnapshotIo io, Path dest,
        Instant notBefore, BooleanSupplier cancelled, boolean backup) throws Throwable
    {
        Path parent = dest.toAbsolutePath().getParent();
        if (parent != null)
        {
            try
            {
                Files.createDirectories(parent);
            }
            catch (IOException | RuntimeException e)
            {
                return new WriteFailure("the directory " + parent + " could not be created: " //$NON-NLS-1$ //$NON-NLS-2$
                    + ThickClientLaunch.oneLine(ThickClientLaunch.causeChainText(e)),
                    ErrorTags.OUTPUT_DIRECTORY_ERROR.wire());
            }
        }
        Path temp;
        try
        {
            temp = Files.createTempFile(parent, ".aiedt-dt-", PART_SUFFIX); //$NON-NLS-1$
        }
        catch (IOException | RuntimeException e)
        {
            return new WriteFailure(
                "a temporary file could not be created beside " + dest + ": " //$NON-NLS-1$ //$NON-NLS-2$
                    + ThickClientLaunch.oneLine(ThickClientLaunch.causeChainText(e)),
                ErrorTags.OUTPUT_DIRECTORY_ERROR.wire());
        }
        try
        {
            io.exportTo(temp, cancelled);
        }
        catch (Throwable failed)
        {
            if (theCallIsStillRunning(failed))
            {
                // The platform process is still writing into it. Deleting a file under a live writer
                // loses whatever it has not written yet and says nothing; the answer names it
                // instead, and the file stays on disk after the process returns.
                out.leftBehind = temp.toString();
            }
            else
            {
                BmInfobaseExtensionHelper.deleteQuietly(temp);
            }
            throw failed;
        }
        String problem = BmInfobaseExtensionHelper.freshExportProblem(temp, notBefore);
        if (problem != null)
        {
            BmInfobaseExtensionHelper.deleteQuietly(temp);
            return new WriteFailure((backup
                ? "the backup the export reported did not materialize at " //$NON-NLS-1$
                : "the export reported success but its output did not materialize at ") + dest //$NON-NLS-1$
                + ": " + problem, ErrorTags.OUTPUT_MISSING.wire()); //$NON-NLS-1$
        }
        try
        {
            // Moved by hand rather than through the shared move helper: that one tells the caller to
            // pass overwrite=true, and this operation declares no such argument.
            Files.move(temp, dest);
        }
        catch (FileAlreadyExistsException race)
        {
            BmInfobaseExtensionHelper.deleteQuietly(temp);
            return new WriteFailure(
                "A file appeared at " + dest + " while the dump ran - it was left untouched, and " //$NON-NLS-1$ //$NON-NLS-2$
                    + "the dump was not placed. Choose another path, or remove it first.", //$NON-NLS-1$
                ErrorTags.ALREADY_EXISTS.wire());
        }
        catch (IOException | RuntimeException e)
        {
            BmInfobaseExtensionHelper.deleteQuietly(temp);
            return new WriteFailure("The dump succeeded but its file could not be placed at " //$NON-NLS-1$
                + dest + ": " + ThickClientLaunch.oneLine(ThickClientLaunch.causeChainText(e)), //$NON-NLS-1$
                ErrorTags.OUTPUT_DIRECTORY_ERROR.wire());
        }
        return null;
    }

    /**
     * Records what a run failed with: the abandoned run's own report together with what it left
     * running, or the classified text for a launcher failure.
     * <p>
     * A launcher call that claimed the launch boundary is reported as still running. The answer then
     * says so and names the file the process is writing, because the caller has to learn that this
     * infobase is not free - an answer reading as an ordinary cancellation would have it start
     * another dump against a process that is still working.
     * </p>
     *
     * @param out the outcome being filled
     * @param failed what the run threw
     */
    private static void recordFailure(SnapshotOutcome out, Throwable failed)
    {
        if (failed instanceof DumpInfoRebuilder.Abandoned)
        {
            DumpInfoRebuilder.Abandoned abandoned = (DumpInfoRebuilder.Abandoned)failed;
            out.error = ThickClientLaunch.oneLine(failed.getMessage());
            out.failureKind = ErrorTags.CANCELLED.wire();
            if (abandoned.processStillRunning())
            {
                out.error = out.error + " The call was under way in the launcher and the platform " //$NON-NLS-1$
                    + "process is still running: this infobase is not free, and another dump or " //$NON-NLS-1$
                    + "restore must not be started against it until that process has finished. The " //$NON-NLS-1$
                    + "infobase claim stays held while it is alive."; //$NON-NLS-1$
            }
            return;
        }
        ThickClientLaunch.classifyFailure(failed,
            s -> { out.error = s.error; out.failureKind = s.failureKind; });
    }

    /**
     * The refusal a dump gives for an occupied destination.
     * <p>
     * Deliberately not {@link BmInfobaseExtensionHelper#occupiedOutputRefusal}: that one tells the
     * caller to pass {@code overwrite=true}, and this operation declares no such argument - an
     * instruction that cannot be followed is worse than no instruction. The destination is refused
     * because that check is what keeps a previous run's dump from reading as this run's result.
     * </p>
     *
     * @param dest the file the caller asked to write
     * @return the refusal, or {@code null} when nothing is there
     */
    static String destinationProblem(Path dest)
    {
        if (dest == null || !Files.exists(dest))
        {
            return null;
        }
        return "A file already exists at " + dest + " and it was left untouched, so nothing was " //$NON-NLS-1$ //$NON-NLS-2$
            + "dumped - a file this run did not write would otherwise read as its result. Choose " //$NON-NLS-1$
            + "another path, or remove it first."; //$NON-NLS-1$
    }

    /**
     * The refusal a load gives for a backup path that is already taken.
     *
     * @param backup the file the backup would go to
     * @return the refusal, or {@code null} when nothing is there
     */
    static String backupProblem(Path backup)
    {
        if (backup == null || !Files.exists(backup))
        {
            return null;
        }
        return "The restore was not started: a file already exists at " + backup //$NON-NLS-1$
            + " and the load would replace the infobase's current contents with that file left " //$NON-NLS-1$
            + "holding nothing of theirs. Pass backupTo=<another path>, or remove it first."; //$NON-NLS-1$
    }

    /**
     * What is wrong with the file a load was pointed at.
     *
     * @param source the {@code .dt} to load
     * @return the problem, or {@code null} when the file is there and not empty
     */
    static String sourceProblem(Path source)
    {
        if (source == null || !Files.isRegularFile(source))
        {
            return "no file was found at " + source //$NON-NLS-1$
                + ". Loading replaces what the infobase holds, so it is refused rather than " //$NON-NLS-1$
                + "started against nothing."; //$NON-NLS-1$
        }
        try
        {
            if (Files.size(source) == 0L)
            {
                return "the file at " + source + " is empty, so there is nothing to load."; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        catch (IOException | RuntimeException e)
        {
            return "the file at " + source + " could not be read: " //$NON-NLS-1$ //$NON-NLS-2$
                + ThickClientLaunch.oneLine(ThickClientLaunch.causeChainText(e));
        }
        return null;
    }

    /**
     * Where a load's backup goes when the caller named no path: beside the file being loaded, named
     * after it and stamped, so two restores of one file do not overwrite each other's backup.
     *
     * @param source the {@code .dt} being loaded
     * @return the backup path
     */
    static Path derivedBackupPath(Path source)
    {
        Path absolute = source.toAbsolutePath();
        String name = absolute.getFileName() == null ? "infobase.dt" //$NON-NLS-1$
            : absolute.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        Path parent = absolute.getParent();
        Path beside = parent != null ? parent : absolute;
        return beside.resolve(stem + "-backup-" + LocalDateTime.now().format(BACKUP_STAMP) + ".dt"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Names who is holding the infobase, when anyone visible is.
     *
     * @param result the answer being built
     * @param applicationId the application whose infobase is concerned; may be {@code null}
     * @param projectName the project that owns it; may be {@code null}
     */
    private static void putHolders(ToolResult result, String applicationId, String projectName)
    {
        Set<String> owners = projectName == null ? Collections.emptySet()
            : new HashSet<>(Collections.singletonList(projectName));
        Map<String, Object> holders = InfobaseHolders.describe(applicationId, owners);
        if (holders != null)
        {
            result.put("infobaseHolders", holders); //$NON-NLS-1$
        }
    }

    /**
     * Renders what a run came to as the operation's JSON answer.
     *
     * @param out what the run came to
     * @param operation which operation ran
     * @return the answer
     */
    static String render(SnapshotOutcome out, String operation)
    {
        if (out.error != null)
        {
            ToolResult failed = ToolResult.error(out.error)
                .put("operation", operation) //$NON-NLS-1$
                .put("path", out.path == null ? null : out.path.toString()) //$NON-NLS-1$
                .put("durationMs", out.durationMs); //$NON-NLS-1$
            if (out.failureKind != null)
            {
                failed.put(out.failureKind, Boolean.TRUE);
            }
            if (out.leftBehind != null)
            {
                failed.put("leftBehind", out.leftBehind); //$NON-NLS-1$
            }
            if (out.lockHeldForProcess)
            {
                failed.put("lockHeldForProcess", Boolean.TRUE); //$NON-NLS-1$
            }
            if (out.backupWritten && out.backupPath != null)
            {
                failed.put("backup", out.backupPath.toString()) //$NON-NLS-1$
                    .put("backupSizeBytes", out.backupSizeBytes); //$NON-NLS-1$
            }
            return failed.toJson();
        }
        ToolResult ok = ToolResult.success()
            .put("operation", operation) //$NON-NLS-1$
            .put("status", RESTORE_OPERATION.equals(operation) ? "Loaded" : "Exported") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .put("path", out.path == null ? null : out.path.toString()) //$NON-NLS-1$
            .put("sizeBytes", out.sizeBytes) //$NON-NLS-1$
            .put("durationMs", out.durationMs) //$NON-NLS-1$
            .put("infobase", out.infobaseName); //$NON-NLS-1$
        if (out.backupPath != null)
        {
            ok.put("backup", out.backupPath.toString()) //$NON-NLS-1$
                .put("backupSizeBytes", out.backupSizeBytes); //$NON-NLS-1$
        }
        if (RESTORE_OPERATION.equals(operation) && out.copyMarked)
        {
            ok.put("copyMarked", Boolean.TRUE) //$NON-NLS-1$
                .put("infobaseChangeCheck", "the stored copy is marked: this load replaced the " //$NON-NLS-1$ //$NON-NLS-2$
                    + "infobase from " + (out.path == null ? "the file" : out.path.toString()) //$NON-NLS-1$ //$NON-NLS-2$
                    + " at " + out.loadedAt + ", so an incremental update is refused until the " //$NON-NLS-1$ //$NON-NLS-2$
                    + "copy is rebuilt from the base") //$NON-NLS-1$
                .put("nextStep", REBUILD_COPY_STEP); //$NON-NLS-1$
        }
        else if (RESTORE_OPERATION.equals(operation) && out.markFailure != null)
        {
            ok.put("copyMarked", Boolean.FALSE) //$NON-NLS-1$
                .put("infobaseChangeCheck", "the load replaced the infobase but the stored copy " //$NON-NLS-1$ //$NON-NLS-2$
                    + "could not be marked: " + out.markFailure); //$NON-NLS-1$
        }
        return ok.toJson();
    }

    /**
     * Records a finished load on the store's dump-info record, so the next incremental update can
     * see that the infobase no longer holds what the copy describes.
     *
     * @param out the outcome being filled
     * @param io the environment
     * @param source the {@code .dt} that was loaded
     */
    private static void noteTheLoad(SnapshotOutcome out, SnapshotIo io, Path source)
    {
        String when = java.time.Instant.now().toString();
        out.loadedAt = when;
        try
        {
            String marked = io.markLoaded(source, when);
            if (marked == null)
            {
                out.copyMarked = true;
            }
            else if (!NO_STORE_RECORD.equals(marked))
            {
                out.markFailure = marked;
            }
        }
        catch (RuntimeException failed)
        {
            out.markFailure = failed.toString();
        }
    }

    /**
     * Polls a previously-issued runKey: the cached result, or a fresh {@code Pending} envelope.
     *
     * @param runKey the key a call is polling
     * @param params the call arguments, for the soft timeout
     * @param operation which operation the poll arrives under
     * @return the answer, or the {@code Pending} envelope
     */
    private static String collect(String runKey, Map<String, String> params, String operation)
    {
        PendingWorkRegistry registry = PendingWorkRegistry.SNAPSHOT;
        registry.pruneExpired();
        PendingWorkRegistry.PendingEntry entry = registry.get(runKey);
        if (entry == null)
        {
            return ToolResult.error("runKey not found - the snapshot either completed and was " //$NON-NLS-1$
                + "already retrieved, or was cancelled or evicted. Issue a new request without " //$NON-NLS-1$
                + "runKey to start over.").put("operation", operation) //$NON-NLS-1$
                .put("runKey", runKey).toJson(); //$NON-NLS-1$
        }
        long timeoutMs = TimeoutArgs.readSeconds(params, DEFAULT_TIMEOUT_SECONDS,
            MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS) * 1000L;
        String done = entry.await(timeoutMs);
        if (done != null)
        {
            registry.remove(runKey, entry);
            return done;
        }
        return PendingEnvelope.mark(ToolResult.success()
            .put("operation", operation) //$NON-NLS-1$
            .put("status", "Pending") //$NON-NLS-1$ //$NON-NLS-2$
            .put("runKey", runKey) //$NON-NLS-1$
            .put("elapsedMs", entry.elapsedMs()) //$NON-NLS-1$
            .put("hint", "The snapshot is still running. Call again with runKey=\"" + runKey //$NON-NLS-1$
                + "\" to resume waiting, or stop it with tasks/cancel and that runKey.")).toJson(); //$NON-NLS-1$
    }

    /**
     * Stops tracking a snapshot a call started earlier, and asks this domain's stopper to stop the
     * work it owns.
     * <p>
     * Best-effort by nature, and the note says so: the stopper holds a run that is about to cross
     * the launcher boundary, so a dump or load already inside the platform launcher is not pulled
     * out of it. A load that has begun is not undone - the backup taken before it stands, and the
     * infobase is left as the load left it.
     * </p>
     *
     * @param runKey the run a call asked to stop
     * @param operation which operation the cancel arrives under
     * @return the answer, naming whether the key was still tracked
     */
    private static String cancelTheRun(String runKey, String operation)
    {
        boolean removed = PendingWorkRegistry.SNAPSHOT.cancel(runKey);
        return ToolResult.success()
            .put("operation", operation) //$NON-NLS-1$
            .put("runKey", runKey) //$NON-NLS-1$
            .put("cancelled", removed) //$NON-NLS-1$
            .put("note", removed //$NON-NLS-1$
                ? "Stopped tracking this snapshot. Best-effort: a dump or load already inside the " //$NON-NLS-1$
                    + "platform launcher may still finish, and a load that has begun is not undone." //$NON-NLS-1$
                : "runKey was not found (the snapshot already finished and was already retrieved, " //$NON-NLS-1$
                    + "or it was evicted by TTL).") //$NON-NLS-1$
            .toJson();
    }

    /**
     * @param file a file that may not exist
     * @return its size in bytes, or {@code 0} when it cannot be read
     */
    private static long sizeOf(Path file)
    {
        try
        {
            return file == null ? 0L : Files.size(file);
        }
        catch (IOException | RuntimeException unreadable)
        {
            return 0L;
        }
    }

    /** Why writing one file failed, with the tag that names it. */
    static final class WriteFailure
    {
        /** The failure text a caller reads. */
        final String text;

        /** The {@link ErrorTags} kind of {@link #text}. */
        final String failureKind;

        /**
         * @param text the failure text
         * @param failureKind its {@link ErrorTags} kind
         */
        WriteFailure(String text, String failureKind)
        {
            this.text = text;
            this.failureKind = failureKind;
        }
    }

    /** What one snapshot run came to. */
    static final class SnapshotOutcome
    {
        /** The file written or loaded. */
        Path path;

        /** Where a load's backup went, or {@code null} for a dump. */
        Path backupPath;

        /** The backup's size in bytes. */
        long backupSizeBytes;

        /** Whether a load's backup was written completely. */
        boolean backupWritten;

        /** The product's size in bytes. */
        long sizeBytes;

        /** How long the run took, in milliseconds. */
        long durationMs;

        /** The infobase's name, for the answer. */
        String infobaseName;

        /** Whether the run finished its work. */
        boolean done;

        /** Whether a finished load marked the store's dump-info record. */
        boolean copyMarked;

        /** When that load finished, as text, or {@code null} when it was not marked. */
        String loadedAt;

        /** Why the store record could not be marked, or {@code null}. */
        String markFailure;

        /** Whether the infobase was held by somebody else. */
        boolean infobaseBusy;

        /** The failure text, or {@code null} on success. */
        String error;

        /** The {@link ErrorTags} kind of {@link #error}, or {@code null}. */
        String failureKind;

        /**
         * The file a launcher call that is still running is writing, left in place, or
         * {@code null}. A file held by a live process is not deleted: whatever it has not written
         * yet would be lost, so the answer names it instead.
         */
        String leftBehind;

        /**
         * Whether the infobase claim is held for a launcher call that is still running. Rendered in
         * the answer, as {@code InfobaseObjectsExporter} renders its own: a caller reads the base is
         * not free without matching the text of the refusal.
         */
        boolean lockHeldForProcess;
    }

    /**
     * The production environment for one snapshot, over the launcher the thick-client calls of this
     * server resolve.
     */
    static final class EdtIo implements SnapshotIo
    {
        private final ThickClientLaunch.LauncherContext ctx;

        private final String operation;

        private final String runKey;

        private final LiveRun live;

        private final BooleanSupplier callerCancelled;

        private MonopolyLock.Claim claim;

        /**
         * @param ctx the resolved launcher context
         * @param operation the operation, for the claim's own record
         * @param runKey this run's runKey
         * @param live the live-run handle the stopper reaches
         * @param callerCancelled the caller's cancellation flag
         */
        EdtIo(ThickClientLaunch.LauncherContext ctx, String operation, String runKey, LiveRun live,
            BooleanSupplier callerCancelled)
        {
            this.ctx = ctx;
            this.operation = operation;
            this.runKey = runKey;
            this.live = live;
            this.callerCancelled = callerCancelled;
        }

        @Override
        public String infobaseIdentity()
        {
            return InfobaseIdentity.of(ctx.infobase);
        }

        @Override
        public String takeLock()
        {
            MonopolyLock.Claim attempt = MonopolyLock.claim(infobaseIdentity(), operation);
            if (attempt.granted())
            {
                claim = attempt;
                return null;
            }
            String refusal = attempt.refusal();
            attempt.close();
            return refusal != null ? refusal
                : "The snapshot was refused because the infobase lock was not granted."; //$NON-NLS-1$
        }

        @Override
        public void releaseLock()
        {
            MonopolyLock.Claim held = claim;
            claim = null;
            if (held != null)
            {
                held.close();
            }
        }

        @Override
        public void exportTo(Path target, BooleanSupplier cancelled) throws Exception
        {
            runTheLauncherCall(() -> ctx.launcher.exportDtFromInfobase(ctx.component, ctx.infobase,
                ctx.args, target));
        }

        @Override
        public void importFrom(Path source, BooleanSupplier cancelled) throws Exception
        {
            runTheLauncherCall(() -> ctx.launcher.importDtToInfobase(ctx.component, ctx.infobase,
                ctx.args, source));
        }

        @Override
        public String markLoaded(Path source, String when)
        {
            if (ctx.project == null || ctx.infobase == null || ctx.infobase.getUuid() == null)
            {
                return "the infobase has no store record to mark"; //$NON-NLS-1$
            }
            if (source == null)
            {
                return "the load named no file"; //$NON-NLS-1$
            }
            Path copy = SyncBaseline.dumpInfoFile(ctx.project, ctx.infobase.getUuid().toString());
            try
            {
                InfobaseOutsideChange.markLoaded(InfobaseOutsideChange.recordFileOf(copy),
                    source.toAbsolutePath().toString(), when);
                return null;
            }
            catch (java.io.IOException failed)
            {
                return failed.toString();
            }
        }

        /**
         * Runs one launcher call the way every other one of this server does: the infobase is
         * released, the call runs under the per-infobase lock and nothing else, and the infobase is
         * taken back - all of it bounded, and abandoned rather than waited out when the budget ends
         * or the caller cancels.
         * <p>
         * The launch boundary is claimed by this worker under that lock and before the launcher -
         * the same claim {@code InfobaseObjectsExporter} and the dump-info rebuild make on their
         * own runs. That is what makes the abandonment's answer true: a cancellation or a budget
         * that claimed the boundary first leaves the worker's claim refused, so the launcher is not
         * called at all, while a worker that claimed it first is a platform call the wait has to
         * report as still running.
         * </p>
         *
         * @param call the launcher call
         * @throws Exception whatever the call threw, or the abandonment
         */
        private void runTheLauncherCall(LauncherCall call) throws Exception
        {
            LIVE.put(runKey, live);
            try
            {
                AtomicBoolean launchClaim = new AtomicBoolean();
                ctx.launchClaim = launchClaim;
                live.launchClaim = launchClaim;
                BooleanSupplier watch = () -> live.stopped.getCount() == 0
                    || (callerCancelled != null && callerCancelled.getAsBoolean());
                InfobaseObjectsExporter.runUnderBudget("the " + operation, SNAPSHOT_BUDGET_MS, //$NON-NLS-1$
                    () -> {
                        BmInfobaseExtensionHelper.underThickClientHandshakeWithLaunchClaim(ctx,
                            call::run);
                        return "ok"; //$NON-NLS-1$
                    }, launchClaim, watch);
            }
            finally
            {
                LIVE.remove(runKey);
            }
        }
    }

    /**
     * @param failed what a launcher call threw
     * @return whether the platform process that threw it is still running, and so still writing
     *         whatever file it was given; {@code false} for every other failure
     */
    private static boolean theCallIsStillRunning(Throwable failed)
    {
        return failed instanceof DumpInfoRebuilder.Abandoned
            && ((DumpInfoRebuilder.Abandoned)failed).processStillRunning();
    }

    /**
     * Hands the infobase claim over to a launcher call that is still running, instead of giving it
     * back while the platform is still working on the base.
     * <p>
     * The claim is what keeps a second run off an infobase this one is still working on. Giving it
     * back at the answer, while the platform process carries on writing, would leave the base
     * looking free to the next caller - the same reason {@code InfobaseObjectsExporter} holds it
     * for the process it abandoned.
     * </p>
     *
     * @param failed what the run threw
     * @param io the environment whose claim is at stake
     * @return whether the claim was handed over; when it was not - the call is not running, or
     *         there is nothing to wait for - the caller releases it as usual
     */
    private static boolean handTheClaimToTheRunningCall(Throwable failed, SnapshotIo io)
    {
        return theCallIsStillRunning(failed)
            && ((DumpInfoRebuilder.Abandoned)failed).whenFinished(io::releaseLock);
    }

    /** One launcher call, as a value the bounded run can call. */
    @FunctionalInterface
    interface LauncherCall
    {
        /**
         * @throws Exception when the call failed
         */
        void run() throws Exception;
    }
}
