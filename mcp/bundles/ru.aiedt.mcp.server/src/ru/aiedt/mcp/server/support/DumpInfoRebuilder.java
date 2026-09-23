/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import ru.aiedt.mcp.server.support.BmInfobaseExtensionHelper.HandshakeOutcome;

/**
 * Rebuilds the stored {@code ConfigDumpInfo.xml} with the platform's own Designer dump, so the file
 * carries a format and record versions the infobase's platform understands.
 *
 * <p>Bytecode-verified against EDT 2026.2: the platform reads the stored file from disk on every
 * {@code config dump-files} (EDT passes the path, never the content), and EDT only ever overwrites
 * the file by copying what the platform itself wrote. What EDT holds in memory is a parsed snapshot
 * loaded from disk once per holder, so a rebuild that replaces the file and drops the cached holder
 * is a rebuild the running EDT actually sees.</p>
 *
 * <p>The whole environment is handed in through {@link RebuildIo}, so the step order and every
 * failure of it are testable against a stand-in that records what ran.</p>
 */
public final class DumpInfoRebuilder
{
    /** What the infobase was left connected as, separately from what the file swap did. */
    public static final String RECONNECT_FAILED_HINT =
        "The infobase is left DISCONNECTED in EDT - reconnect it by hand (Infobases view) before " //$NON-NLS-1$
            + "the next update."; //$NON-NLS-1$

    /**
     * A Designer run abandoned by its caller's patience rather than finished by the platform - the
     * timeout the operation hands the run, which is a different sentence from a platform failure.
     */
    public static final class Abandoned extends Exception
    {
        private static final long serialVersionUID = 1L;

        /**
         * @param message what was abandoned and after how long
         */
        public Abandoned(String message)
        {
            super(message);
        }
    }

    /** What one rebuild attempt ended with, every part named on its own. */
    public static final class Outcome
    {
        /** Whether the stored file was replaced with the platform's own dump. */
        public boolean ok;

        /** The refusal or failure sentence, or {@code null} on success. */
        public String error;

        /** The failure kind for an answer's {@code failureKind}, or {@code null}. */
        public String failureKind;

        /**
         * What happened to the stored file: {@code replaced}, {@code untouched}, or
         * {@code restoredFromBackup}.
         */
        public String fileState = "untouched"; //$NON-NLS-1$

        /** The stored file's format before the rebuild, or {@code null} when there was no file. */
        public String oldFormat;

        /** The format the platform wrote, or {@code null} when it never got that far. */
        public String newFormat;

        /** How many object records the new file carries, or {@code -1} when unknown. */
        public int records = -1;

        /** The copy of the previous file, or {@code null} when there was nothing to copy. */
        public String backupPath;

        /** How long the whole attempt took. */
        public long durationMs;

        /** The platform version the dump ran with, or {@code null}. */
        public String platformVersion;

        /** The format pair this rebuild recorded, or the failure to record it. */
        public String pairRemembered;

        /** What dropping EDT's cached holder answered, or {@code null} when it never ran. */
        public String holderRefresh;

        /**
         * The reconnection failure, named separately from the swap outcome - a replaced file and a
         * disconnected infobase are two facts an answer must not merge into one verdict.
         */
        public String reconnectError;

        /** The steps that ran, in order - the record a test reads the order from. */
        public final List<String> sequence = new ArrayList<>();

        /** The infobase's name, when the environment resolved one to name. */
        public String infobaseName;
    }

    /**
     * Every step of the rebuild that reaches outside this class. The production set is assembled by
     * the operation from EDT services; a test hands in a stand-in that records what ran.
     */
    public interface RebuildIo
    {
        /**
         * @return the infobase identity {@link MonopolyLock} claims on, or {@code null} when this
         *         base cannot be identified - which refuses the whole rebuild before anything is
         *         released
         */
        String infobaseIdentity();

        /**
         * @return whether the cross-process claim was taken
         */
        boolean takeLock();

        /** Lets the claim go; runs at every outcome. */
        void releaseLock();

        /**
         * @return the store directory ({@code ib-sync/ss/<uuid>}), created when absent - the
         *         temporary dump directory is made beside it, on the same volume
         * @throws IOException when it cannot be created
         */
        Path storeDirectory() throws IOException;

        /**
         * @return whether the infobase was connected before the release, so the reconnection knows
         *         whether it is owed
         * @throws Exception when the release failed - nothing runs after it
         */
        boolean releaseInfobase() throws Exception;

        /**
         * The Designer run: dumps the infobase configuration into the directory given and answers
         * the fresh dump-info file it produced (or {@code null} to mean the conventional
         * {@code ConfigDumpInfo.xml} inside that directory). Throws {@link Abandoned} for a run
         * given up on by timeout.
         *
         * @param tempDir the directory to dump into, beside the store
         * @return the fresh dump-info file, or {@code null} for the conventional name
         * @throws Exception when the platform run failed
         */
        Path runDesignerDump(Path tempDir) throws Exception;

        /**
         * Takes the infobase back; owed exactly when the release said the infobase was connected.
         *
         * @throws Exception when the reconnection failed
         */
        void reconnectInfobase() throws Exception;

        /**
         * Drops EDT's cached in-memory holder of this infobase's sync state, so the next equality
         * check and update reload the baseline - and the new dump-info - from disk.
         *
         * @return what the drop answered
         */
        String dropCachedHolder();

        /**
         * Records the format this platform's Designer wrote, so later checks expect it.
         *
         * @param platformVersion the platform version, with or without build
         * @param format the {@code version} attribute the Designer wrote
         * @throws IOException when the pair cannot be written
         */
        void rememberPair(String platformVersion, String format) throws IOException;

        /**
         * Removes the temporary dump directory; runs at every outcome past its creation.
         *
         * @param dir the directory the dump went into
         */
        void deleteTempDir(Path dir);
    }

    private DumpInfoRebuilder()
    {
        // static orchestrator
    }

    /**
     * The production rebuild: resolves the thick-client environment the way every other Designer
     * call of this server does, then runs {@link #performRebuild} against it. The Designer dumps the
     * whole configuration in the hierarchical format into a temporary directory beside the store -
     * the dump that carries the platform's own {@code ConfigDumpInfo.xml}.
     *
     * @param projectName the project whose infobase the file belongs to
     * @param applicationId the application naming the infobase; required when the project has
     *            several, resolved otherwise
     * @param timeoutMs how long the Designer run is waited for before it is abandoned
     * @return the outcome; a resolution failure lands in {@link Outcome#error} with its kind
     */
    public static Outcome rebuildViaEdt(String projectName, String applicationId, long timeoutMs)
    {
        BmInfobaseExtensionHelper.LauncherContext ctx =
            BmInfobaseExtensionHelper.resolveLauncher(projectName, applicationId);
        if (ctx.error != null)
        {
            Outcome refused = new Outcome();
            refused.error = ctx.error;
            refused.failureKind = ctx.failureKind;
            refused.infobaseName = ctx.infobaseName;
            return refused;
        }
        java.util.UUID infobaseUuid = ctx.infobase.getUuid();
        String platformVersion = ctx.component.getInstallation().getVersionWithBuild();

        RebuildIo io = new RebuildIo()
        {
            @Override
            public String infobaseIdentity()
            {
                return InfobaseIdentity.of(ctx.infobase);
            }

            @Override
            public boolean takeLock()
            {
                MonopolyLock.Claim attempt =
                    MonopolyLock.claim(infobaseIdentity(), "rebuild_dump_info"); //$NON-NLS-1$
                if (attempt.granted())
                {
                    claim.set(attempt);
                    return true;
                }
                // A refused claim is closed here and now: the orchestrator does not call back for
                // it, and an unclosed one would sit in the thread-local for the thread's life.
                attempt.close();
                return false;
            }

            @Override
            public void releaseLock()
            {
                MonopolyLock.Claim held = claim.get();
                if (held != null)
                {
                    held.close();
                    claim.remove();
                }
            }

            @Override
            public Path storeDirectory() throws IOException
            {
                Path directory = SyncBaseline.indexOf(ctx.project, infobaseUuid.toString())
                    .getParent();
                Files.createDirectories(directory);
                return directory;
            }

            @Override
            public boolean releaseInfobase() throws Exception
            {
                return BmInfobaseExtensionHelper.releaseForThickClient(ctx);
            }

            @Override
            public Path runDesignerDump(Path tempDir) throws Exception
            {
                return runDesignerDumpUnderTimeout(ctx, tempDir, timeoutMs);
            }

            @Override
            public void reconnectInfobase() throws Exception
            {
                BmInfobaseExtensionHelper.takeInfobaseBack(ctx);
            }

            @Override
            public String dropCachedHolder()
            {
                return SyncBaseline.dropCachedHolder(infobaseUuid);
            }

            @Override
            public void rememberPair(String platform, String format) throws IOException
            {
                DumpInfoProbe.rememberPair(platform, format, DumpInfoProbe.stateFile());
            }

            @Override
            public void deleteTempDir(Path dir)
            {
                deleteTree(dir);
            }
        };
        Outcome outcome = performRebuild(io, STAMP_FORMAT.format(java.time.LocalDateTime.now()),
            platformVersion);
        outcome.infobaseName = ctx.infobaseName;
        return outcome;
    }

    /**
     * The claim one rebuild took, closed at every outcome. A thread-local because {@link RebuildIo}
     * is a hand-made closure over it: one rebuild per thread, the claim taken and released on the
     * same thread that runs the rebuild.
     */
    private static final ThreadLocal<MonopolyLock.Claim> claim = new ThreadLocal<>();

    /** The backup-name timestamp. */
    private static final java.time.format.DateTimeFormatter STAMP_FORMAT =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"); //$NON-NLS-1$

    /**
     * Runs the platform dump on a worker thread and abandons it when the budget runs out. The
     * platform process itself cannot be reached to be killed from here - it runs inside EDT's
     * designer session - so an abandoned run is handed back as {@link Abandoned} and the process is
     * left to finish on its own, which the answer says plainly.
     */
    private static Path runDesignerDumpUnderTimeout(
        BmInfobaseExtensionHelper.LauncherContext ctx, Path tempDir, long timeoutMs) throws Exception
    {
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors
            .newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "rebuild-dump-info-designer"); //$NON-NLS-1$
                thread.setDaemon(true);
                return thread;
            });
        java.util.concurrent.Future<Path> run = worker.submit(() -> ctx.launcher
            .exportFullXmlFromInfobase(ctx.component, ctx.infobase,
                com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ConfigurationFilesFormat.HIERARCHICAL,
                com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ConfigurationFilesKind.PLAIN_FILES,
                ctx.args, tempDir));
        worker.shutdown();
        try
        {
            return run.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        catch (java.util.concurrent.TimeoutException tooSlow)
        {
            run.cancel(true);
            throw new Abandoned("the Designer dump did not finish within " //$NON-NLS-1$
                + (timeoutMs / 1000) + "s"); //$NON-NLS-1$
        }
        catch (java.util.concurrent.ExecutionException failed)
        {
            Throwable cause = failed.getCause() != null ? failed.getCause() : failed;
            if (cause instanceof Exception)
            {
                throw (Exception)cause;
            }
            throw new IllegalStateException(cause);
        }
        finally
        {
            worker.shutdownNow();
        }
    }

    /**
     * Deletes a directory tree, best effort - the temporary dump directory is disposable by
     * contract, and a cleanup failure must not overwrite the rebuild's own outcome.
     */
    private static void deleteTree(Path dir)
    {
        if (dir == null || !Files.exists(dir))
        {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir))
        {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try
                {
                    Files.deleteIfExists(path);
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
     * Runs the rebuild: claim, release the infobase, dump with the platform's own Designer, verify
     * the file, back the old one up, replace it, make EDT re-read it, take the infobase back, clean
     * up. The stored file is replaced only after the platform's dump verified; at every other
     * outcome it is untouched, and a swap that fails halfway is rolled back from its backup.
     *
     * @param io the environment, step by step
     * @param stamp the timestamp suffix of the backup copy ({@code before-rebuild-<stamp>})
     * @param platformVersion the platform version for the answer and the recorded pair, or
     *            {@code null} when the caller could not name one (the pair is then not recorded)
     * @return the outcome, with the step sequence in it
     */
    public static Outcome performRebuild(RebuildIo io, String stamp, String platformVersion)
    {
        long startedAt = System.currentTimeMillis();
        Outcome out = new Outcome();
        out.platformVersion = platformVersion;
        boolean lockTaken = false;
        Path tempDir = null;

        // Fail-closed on a base this cannot name: an unidentified infobase would proceed without a
        // claim, and the operation that must not race a neighbour must refuse before releasing
        // anything of EDT's.
        out.sequence.add("identity"); //$NON-NLS-1$
        String identity = io.infobaseIdentity();
        if (identity == null || identity.isEmpty())
        {
            out.error = "The infobase cannot be identified, so the rebuild was refused before " //$NON-NLS-1$
                + "anything was released or claimed - an unidentified base cannot be locked " //$NON-NLS-1$
                + "against a neighbouring EDT."; //$NON-NLS-1$
            out.failureKind = ru.aiedt.mcp.server.support.ErrorTags.RESOLVE_FAILED.wire();
            out.durationMs = System.currentTimeMillis() - startedAt;
            return out;
        }

        out.sequence.add("lock"); //$NON-NLS-1$
        if (!io.takeLock())
        {
            out.error = "Another AI-EDT instance is working on this infobase, or this instance " //$NON-NLS-1$
                + "still holds it - the rebuild was refused."; //$NON-NLS-1$
            out.failureKind = ru.aiedt.mcp.server.support.ErrorTags.BUSY.wire();
            out.durationMs = System.currentTimeMillis() - startedAt;
            return out;
        }
        lockTaken = true;

        try
        {
            Path storeDirectory = io.storeDirectory();
            Path storedFile = storeDirectory.resolve(DumpInfoProbe.FILE_NAME);
            out.oldFormat = DumpInfoProbe.formatOf(storedFile);
            out.sequence.add("readOld"); //$NON-NLS-1$

            out.sequence.add("tempDir"); //$NON-NLS-1$
            final Path dumpDir = java.nio.file.Files.createTempDirectory(storeDirectory, "rebuild-dump-"); //$NON-NLS-1$
            tempDir = dumpDir;

            HandshakeOutcome handshake = BmInfobaseExtensionHelper.runUnderHandshake(
                io::releaseInfobase,
                () -> runTheDump(io, dumpDir, storedFile, out, stamp),
                io::reconnectInfobase);
            out.sequence.addAll(handshake.sequence);
            if (handshake.reconnectError != null)
            {
                // Named on its own and whatever else happened: a replaced file with a disconnected
                // infobase is two facts, and a caller repairing one must know the other stands.
                out.reconnectError = "EDT could not take the infobase back after the Designer " //$NON-NLS-1$
                    + "run: " + oneLine(handshake.reconnectError) + " " + RECONNECT_FAILED_HINT; //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (handshake.releaseError != null)
            {
                out.error = "EDT could not release the infobase for the Designer, so the rebuild " //$NON-NLS-1$
                    + "did not start and the stored file was not touched: " //$NON-NLS-1$
                    + oneLine(handshake.releaseError); //$NON-NLS-1$
                out.failureKind = ru.aiedt.mcp.server.support.ErrorTags.INFOBASE_NOT_RELEASED.wire();
                return out;
            }
            if (handshake.workError != null)
            {
                workFailure(handshake.workError, out);
                return out;
            }
            out.ok = true;
        }
        catch (IOException | RuntimeException failed)
        {
            // The paths this class itself touches: the store directory, the backup, the swap.
            out.error = "The rebuild failed: " + oneLine(failed); //$NON-NLS-1$
            out.failureKind = ru.aiedt.mcp.server.support.ErrorTags.OUTPUT_DIRECTORY_ERROR.wire();
            return out;
        }
        finally
        {
            if (tempDir != null)
            {
                out.sequence.add("cleanup"); //$NON-NLS-1$
                io.deleteTempDir(tempDir);
            }
            if (lockTaken)
            {
                out.sequence.add("unlock"); //$NON-NLS-1$
                io.releaseLock();
            }
            out.durationMs = System.currentTimeMillis() - startedAt;
        }
        return out;
    }

    /**
     * The Designer run and everything that must happen while the infobase is released: dump, verify
     * the file, back the old one up, replace it, make EDT re-read it, record the pair.
     */
    private static void runTheDump(RebuildIo io, Path tempDir, Path storedFile, Outcome out,
        String stamp) throws Exception
    {
        Path freshFile = io.runDesignerDump(tempDir);
        if (freshFile == null)
        {
            freshFile = tempDir.resolve(DumpInfoProbe.FILE_NAME);
        }
        out.sequence.add("dump"); //$NON-NLS-1$

        // Verify before anything of the stored file's is touched: a file that is not there, or not
        // a ConfigDumpInfo root with a version, is the platform's answer and not a reason to swap.
        out.newFormat = DumpInfoProbe.formatOf(freshFile);
        out.records = DumpInfoProbe.recordsIn(freshFile);
        out.sequence.add("verify"); //$NON-NLS-1$
        if (out.newFormat == null)
        {
            throw new IllegalStateException("the Designer run left no readable " //$NON-NLS-1$
                + DumpInfoProbe.FILE_NAME + " at " + freshFile //$NON-NLS-1$
                + (freshFile.toFile().isFile() ? " (not a ConfigDumpInfo root with a version)" //$NON-NLS-1$
                    : " (no such file)")); //$NON-NLS-1$
        }

        out.sequence.add("backup"); //$NON-NLS-1$
        out.backupPath = swapStored(storedFile, freshFile, stamp);
        out.sequence.add("swap"); //$NON-NLS-1$
        out.fileState = "replaced"; //$NON-NLS-1$

        out.sequence.add("dropHolder"); //$NON-NLS-1$
        out.holderRefresh = io.dropCachedHolder();

        if (out.platformVersion != null)
        {
            out.sequence.add("rememberPair"); //$NON-NLS-1$
            try
            {
                io.rememberPair(out.platformVersion, out.newFormat);
                out.pairRemembered = out.platformVersion + " -> " + out.newFormat; //$NON-NLS-1$
            }
            catch (IOException notWritten)
            {
                // The file is already replaced; an unrecorded pair costs the NEXT check its
                // expectation, not this rebuild its result.
                out.pairRemembered = "NOT recorded (" + oneLine(notWritten) //$NON-NLS-1$
                    + ") - the next format check has no expectation for " //$NON-NLS-1$
                    + out.platformVersion + " until a rebuild records one"; //$NON-NLS-1$
            }
        }
    }

    /**
     * Turns a Designer-run failure into the answer's words, naming what the stored file was left as:
     * untouched when the failure came before the swap, restored from its backup when the swap itself
     * failed and the rollback landed.
     */
    private static void workFailure(Throwable workError, Outcome out)
    {
        if (workError instanceof SwapRolledBack)
        {
            SwapRolledBack rolled = (SwapRolledBack)workError;
            out.fileState = "restoredFromBackup"; //$NON-NLS-1$
            out.error = "Replacing the stored file failed (" + oneLine(rolled.getCause()) //$NON-NLS-1$
                + "); the previous file was restored from the copy at " + rolled.backupPath //$NON-NLS-1$
                + ". Both attempts are named: the swap did not land, the rollback did."; //$NON-NLS-1$
            out.failureKind = ru.aiedt.mcp.server.support.ErrorTags.OUTPUT_DIRECTORY_ERROR.wire();
            return;
        }
        out.fileState = "untouched"; //$NON-NLS-1$
        if (workError instanceof Abandoned)
        {
            out.error = "The Designer run did not finish and was abandoned: " //$NON-NLS-1$
                + workError.getMessage() + ". The stored file was not touched; the platform " //$NON-NLS-1$
                + "process, if it is still running, finishes on its own - do not start another " //$NON-NLS-1$
                + "rebuild until it has."; //$NON-NLS-1$
        }
        else
        {
            out.error = "The Designer run failed, so the stored file was not touched: " //$NON-NLS-1$
                + oneLine(workError);
        }
        out.failureKind = ru.aiedt.mcp.server.support.ErrorTags.THICK_CLIENT_FAILED.wire();
    }

    /**
     * Replaces the stored file with the fresh one through a temporary file in the same directory:
     * the old file is copied beside itself first ({@code ConfigDumpInfo.xml.before-rebuild-<stamp>}),
     * the new one is staged beside the target and moved over it, and a move that fails is rolled
     * back from that copy so a half-finished swap cannot leave the store without a file.
     *
     * @param storedFile the store's {@code ConfigDumpInfo.xml}; need not exist yet
     * @param freshFile the platform's own dump-info file
     * @param stamp the backup name's timestamp suffix
     * @return the backup copy's path, or {@code null} when there was no previous file
     * @throws IOException when the swap failed without a backup to roll back to, or when the swap
     *             and the rollback BOTH failed - the message names both
     * @throws SwapRolledBack when the swap failed and the previous file was restored from the copy
     */
    public static String swapStored(Path storedFile, Path freshFile, String stamp) throws IOException
    {
        return swapStored(storedFile, freshFile, stamp,
            (from, to) -> Files.move(from, to, StandardCopyOption.REPLACE_EXISTING));
    }

    /**
     * The move of the staged file onto the stored one - the one step a test needs to fail on
     * purpose, because a real {@link Files#move} failing is a filesystem event a test cannot
     * stage portably.
     */
    interface FileMover
    {
        /**
         * Moves the staged file onto its target, replacing what is there.
         *
         * @param from the staged file
         * @param to the stored file
         * @throws IOException when the move fails
         */
        void move(Path from, Path to) throws IOException;
    }

    /**
     * As {@link #swapStored(Path, Path, String)}, with the final move handed in.
     *
     * @param storedFile the store's {@code ConfigDumpInfo.xml}; need not exist yet
     * @param freshFile the platform's own dump-info file
     * @param stamp the backup name's timestamp suffix
     * @param mover the move onto the stored file
     * @return the backup copy's path, or {@code null} when there was no previous file
     * @throws IOException when the swap failed without a backup to roll back to, or when the swap
     *             and the rollback BOTH failed - the message names both
     * @throws SwapRolledBack when the swap failed and the previous file was restored from the copy
     */
    static String swapStored(Path storedFile, Path freshFile, String stamp, FileMover mover)
        throws IOException
    {
        Path directory = storedFile.toAbsolutePath().getParent();
        String backupPath = null;
        if (Files.isRegularFile(storedFile))
        {
            Path backup = storedFile.resolveSibling(
                DumpInfoProbe.FILE_NAME + ".before-rebuild-" + stamp); //$NON-NLS-1$
            Files.copy(storedFile, backup, StandardCopyOption.REPLACE_EXISTING);
            backupPath = backup.toString();
        }

        Path staged = Files.createTempFile(directory, DumpInfoProbe.FILE_NAME, ".tmp"); //$NON-NLS-1$
        try
        {
            Files.copy(freshFile, staged, StandardCopyOption.REPLACE_EXISTING);
            try
            {
                mover.move(staged, storedFile);
            }
            catch (IOException moveFailed)
            {
                if (backupPath == null)
                {
                    throw moveFailed;
                }
                try
                {
                    Files.copy(java.nio.file.Paths.get(backupPath), storedFile,
                        StandardCopyOption.REPLACE_EXISTING);
                }
                catch (IOException rollbackFailed)
                {
                    throw new IOException("the swap failed (" + oneLine(moveFailed) //$NON-NLS-1$
                        + ") and restoring the previous file failed too (" //$NON-NLS-1$
                        + oneLine(rollbackFailed) + ") - the backup copy is at " + backupPath, //$NON-NLS-1$
                        rollbackFailed);
                }
                // Rolled back: the store holds the previous file again. Carried up as its own
                // kind, so the answer names both attempts rather than reporting one failure.
                throw new SwapRolledBack(moveFailed, backupPath);
            }
        }
        finally
        {
            Files.deleteIfExists(staged);
        }
        return backupPath;
    }

    /** A swap that failed and was rolled back from its backup - the store holds the old file. */
    static final class SwapRolledBack extends IOException
    {
        private static final long serialVersionUID = 1L;

        /** The copy the previous file was restored from. */
        final String backupPath;

        SwapRolledBack(IOException cause, String backupPath)
        {
            super(cause);
            this.backupPath = backupPath;
        }
    }

    /**
     * One line out of a failure, for an answer that names its facts without a stack dump.
     */
    private static String oneLine(Throwable failure)
    {
        String message = failure.getMessage();
        String named = message == null || message.isEmpty()
            ? failure.getClass().getSimpleName() : message;
        String line = named.replace('\n', ' ').replace('\r', ' ');
        return line.length() > 400 ? line.substring(0, 400) + "..." : line; //$NON-NLS-1$
    }
}
