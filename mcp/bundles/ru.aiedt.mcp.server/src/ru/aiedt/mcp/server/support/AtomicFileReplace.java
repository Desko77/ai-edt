/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;

import ru.aiedt.mcp.server.Activator;

/**
 * Replaces or removes a file atomically, but only while it still holds the content the caller read.
 * <p>
 * The marker file and the clusters file are written by more than one party: threads of this
 * instance, other EDT instances on the same machine, and programs outside the plugin altogether -
 * git checking a branch out, an editor saving. Writing over content nobody read is how a valid
 * file loses what it carried, so every write is refused unless the file still holds the bytes the
 * writer's state was built from.
 * </p>
 * <p>
 * Writers are serialized by a lock file outside any project, under
 * {@code <user home>/.aiedt/locks/<SHA-256 of the target's canonical path>.lock}. The lock is one
 * per physical file for every workspace and every EDT instance, so nothing is written into the
 * project or its {@code .gitignore}. It is held from the first content check to the end of the
 * replacement: under it the file's fingerprint is compared with the one the caller read, the new
 * content is staged in a sibling temporary file, the fingerprint is compared a second time
 * immediately before the atomic move, and a mismatch at either check refuses the write and leaves
 * the file untouched. A lock held longer than the wait limit refuses the write with that reason
 * instead of waiting forever. The workspace is refreshed after the lock is released, and a refresh
 * the workspace refuses is a warning on a completed write rather than a failure of it.
 * </p>
 */
public final class AtomicFileReplace
{
    /** Where the lock files live, unless the tests point somewhere else. */
    private static final String LOCK_DIR_PROPERTY = "aiedt.store.locks.dir"; //$NON-NLS-1$

    /**
     * How long one write waits for the lock file. Another writer of these small files finishes in
     * milliseconds; the limit exists so a writer that died holding the lock, or one that is wedged,
     * refuses the write instead of blocking its thread - which on the marker path is the UI thread.
     */
    private static final long LOCK_WAIT_MILLIS = 5000L;

    private static final long LOCK_RETRY_PAUSE_MILLIS = 25L;

    private static final int ATOMIC_MOVE_ATTEMPTS = 4;

    private static final long ATOMIC_MOVE_RETRY_MILLIS = 75L;

    private static final long STALE_TEMPORARY_MILLIS = 24L * 60L * 60L * 1000L;

    private static final Set<PosixFilePermission> DEFAULT_POSIX_FILE_PERMISSIONS = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.OTHERS_READ);

    /** One waiting room per file for the threads of this instance, before the lock file is reached. */
    private static final ConcurrentHashMap<Path, ReentrantLock> WRITERS_HERE = new ConcurrentHashMap<>();

    /** What stands for a target that was not there when its content was read. */
    public static final String NO_FILE_FINGERPRINT = "-"; //$NON-NLS-1$

    /** The target was replaced, or removed when removal was asked for. */
    public static final String OK = "ok"; //$NON-NLS-1$

    /** The target's bytes are not the bytes the caller read. */
    public static final String CHANGED_ON_DISK = "changedOnDisk"; //$NON-NLS-1$

    /** The target could not be read before writing it. */
    public static final String READ_FAILED = "readFailed"; //$NON-NLS-1$

    /** The lock could not be taken within the wait limit, or the target is locked by someone else. */
    public static final String LOCK_REFUSED = "lockRefused"; //$NON-NLS-1$

    /** The target is read-only. */
    public static final String ACCESS_DENIED = "accessDenied"; //$NON-NLS-1$

    /** The bytes could not be written. */
    public static final String WRITE_FAILED = "writeFailed"; //$NON-NLS-1$

    private AtomicFileReplace()
    {
        // Static utility.
    }

    /**
     * What one replacement did.
     */
    public static final class Outcome
    {
        private final String code;

        private final String detail;

        private Outcome(String code, String detail)
        {
            this.code = code;
            this.detail = detail;
        }

        /**
         * An outcome that replaced or removed the target.
         *
         * @return the outcome
         */
        static Outcome ok()
        {
            return new Outcome(OK, null);
        }

        /**
         * A refusal that left the target as it was.
         *
         * @param code one of the refusal codes
         * @param detail extra text; may be {@code null}
         * @return the outcome
         */
        static Outcome refused(String code, String detail)
        {
            return new Outcome(code, detail);
        }

        /**
         * Whether the target was replaced or removed.
         *
         * @return {@code true} for {@link #OK}
         */
        public boolean isOk()
        {
            return OK.equals(code);
        }

        /**
         * The outcome code: {@link #OK}, or a refusal code.
         *
         * @return the code
         */
        public String getCode()
        {
            return code;
        }

        /**
         * Extra text carried with a refusal, or {@code null} when there is none.
         *
         * @return the exception text or the reason
         */
        public String getDetail()
        {
            return detail;
        }

        /**
         * @return the code, and the detail when there is one
         */
        @Override
        public String toString()
        {
            return detail == null ? code : code + "(" + detail + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** Performs one atomic replacement attempt. */
    @FunctionalInterface
    interface AtomicMover
    {
        /**
         * Moves the staged file over the destination atomically.
         *
         * @param source the staged file
         * @param target the destination
         * @throws IOException when the atomic replacement is refused
         */
        void move(Path source, Path target) throws IOException;
    }

    /** Waits between atomic replacement attempts. */
    @FunctionalInterface
    interface RetrySleeper
    {
        /**
         * Waits for the requested retry delay.
         *
         * @param milliseconds the delay in milliseconds
         * @throws InterruptedException when the waiting thread is interrupted
         */
        void sleep(long milliseconds) throws InterruptedException;
    }

    /**
     * Replaces the target's content, provided the target still holds what the caller read.
     *
     * @param target the file to replace
     * @param expectedFingerprint {@link #fingerprint(byte[])} of the bytes the caller's state was
     *            read from, or {@link #NO_FILE_FINGERPRINT} when it was read from no file
     * @param content the bytes to write
     * @param workspaceFile the workspace handle of the target, refreshed after the write; may be
     *            {@code null} when the file does not belong to a workspace
     * @return what the replacement did
     */
    public static Outcome replace(Path target, String expectedFingerprint, byte[] content, IFile workspaceFile)
    {
        return replace(target, expectedFingerprint, content, workspaceFile, LOCK_WAIT_MILLIS, null);
    }

    /**
     * Replaces the target's content with a wait limit and a seam of the caller's choosing.
     *
     * @param target the file to replace
     * @param expectedFingerprint the fingerprint of the bytes the caller read, or
     *            {@link #NO_FILE_FINGERPRINT}
     * @param content the bytes to write
     * @param workspaceFile the workspace handle to refresh; may be {@code null}
     * @param waitMillis how long to wait for the lock file
     * @param afterStaging run after the content is staged and before the second fingerprint check;
     *            may be {@code null}
     * @return what the replacement did
     */
    static Outcome replace(Path target, String expectedFingerprint, byte[] content, IFile workspaceFile,
        long waitMillis, Runnable afterStaging)
    {
        return runUnderLocks(target, expectedFingerprint, content, workspaceFile, waitMillis, afterStaging);
    }

    /**
     * Removes the target, provided it still holds what the caller read.
     *
     * @param target the file to remove
     * @param expectedFingerprint the fingerprint of the bytes the caller read, or
     *            {@link #NO_FILE_FINGERPRINT}; {@code null} removes without comparing content
     * @param workspaceFile the workspace handle of the target, refreshed after the removal; may be
     *            {@code null}
     * @return what the removal did
     */
    public static Outcome remove(Path target, String expectedFingerprint, IFile workspaceFile)
    {
        return runUnderLocks(target, expectedFingerprint, null, workspaceFile, LOCK_WAIT_MILLIS, null);
    }

    /**
     * The digest of a file's bytes, the identity of the content a state was read from.
     *
     * @param bytes the file contents
     * @return the hexadecimal SHA-256 of the bytes
     */
    public static String fingerprint(byte[] bytes)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes); //$NON-NLS-1$
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest)
            {
                hex.append(Character.forDigit((value >> 4) & 0xf, 16));
                hex.append(Character.forDigit(value & 0xf, 16));
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            // Every runtime that runs this bundle ships SHA-256. One that does not still has to tell
            // the file it read from another one, and a shortened digest would not, so the content
            // itself stands in for it.
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * Runs one replacement or removal under both locks and refreshes the workspace afterwards.
     *
     * @param target the file to write
     * @param expectedFingerprint the fingerprint the caller read, or {@code null} to skip comparing
     * @param content the bytes to write, or {@code null} to remove the file
     * @param workspaceFile the workspace handle to refresh; may be {@code null}
     * @param waitMillis how long to wait for the locks
     * @param afterStaging run between staging and the second fingerprint check; may be {@code null}
     * @return what the write did
     */
    private static Outcome runUnderLocks(Path target, String expectedFingerprint, byte[] content,
        IFile workspaceFile, long waitMillis, Runnable afterStaging)
    {
        Path canonical = target.toAbsolutePath().normalize();
        ReentrantLock writer = WRITERS_HERE.computeIfAbsent(canonical, key -> new ReentrantLock());
        boolean taken;
        try
        {
            taken = writer.tryLock(waitMillis, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return Outcome.refused(LOCK_REFUSED,
                "interrupted while waiting for another writer of this file in this instance"); //$NON-NLS-1$
        }
        if (!taken)
        {
            return Outcome.refused(LOCK_REFUSED, "another thread of this instance has been writing this file " //$NON-NLS-1$
                + "for longer than " + waitMillis + " ms"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        try
        {
            Outcome done = runUnderTheLockFile(canonical, expectedFingerprint, content, waitMillis, afterStaging);
            if (done.isOk())
            {
                refreshQuietly(workspaceFile, canonical);
            }
            return done;
        }
        finally
        {
            writer.unlock();
        }
    }

    /**
     * Runs one replacement or removal while holding the lock file of the target.
     *
     * @param canonical the normalized target
     * @param expectedFingerprint the fingerprint the caller read, or {@code null} to skip comparing
     * @param content the bytes to write, or {@code null} to remove the file
     * @param waitMillis how long to wait for the lock file
     * @param afterStaging run between staging and the second fingerprint check; may be {@code null}
     * @return what the write did
     */
    private static Outcome runUnderTheLockFile(Path canonical, String expectedFingerprint, byte[] content,
        long waitMillis, Runnable afterStaging)
    {
        Path lockFile;
        try
        {
            lockFile = lockFileOf(canonical);
            Files.createDirectories(lockFile.getParent());
        }
        catch (IOException e)
        {
            return Outcome.refused(WRITE_FAILED,
                "the lock directory could not be created: " + exceptionText(e)); //$NON-NLS-1$
        }
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
            StandardOpenOption.READ, StandardOpenOption.WRITE))
        {
            FileLock lock = awaitLockFile(channel, waitMillis);
            if (lock == null)
            {
                return Outcome.refused(LOCK_REFUSED, "the lock file has been held for longer than " //$NON-NLS-1$
                    + waitMillis + " ms"); //$NON-NLS-1$
            }
            try
            {
                return writeHoldingTheLockFile(canonical, expectedFingerprint, content, afterStaging);
            }
            finally
            {
                lock.release();
            }
        }
        catch (OverlappingFileLockException overlapping)
        {
            // File locks are per virtual machine, so this is another thread of this instance that
            // the in-process lock did not serialize - two spellings of one path.
            return Outcome.refused(LOCK_REFUSED, "the file is locked within this process"); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            return Outcome.refused(WRITE_FAILED, exceptionText(e));
        }
    }

    /**
     * Replaces or removes the target, both fingerprint checks and the staging under the lock file.
     *
     * @param canonical the normalized target
     * @param expectedFingerprint the fingerprint the caller read, or {@code null} to skip comparing
     * @param content the bytes to write, or {@code null} to remove the file
     * @param afterStaging run between staging and the second fingerprint check; may be {@code null}
     * @return what the write did
     * @throws IOException when the file cannot be read, staged or replaced
     */
    private static Outcome writeHoldingTheLockFile(Path canonical, String expectedFingerprint, byte[] content,
        Runnable afterStaging) throws IOException
    {
        if (Files.exists(canonical))
        {
            Outcome probe = probeWritableAndUnlocked(canonical);
            if (!probe.isOk())
            {
                return probe;
            }
        }
        Outcome unchanged = holdsWhatWasRead(canonical, expectedFingerprint);
        if (!unchanged.isOk())
        {
            return unchanged;
        }
        if (content == null)
        {
            if (Files.notExists(canonical))
            {
                return Outcome.ok();
            }
            Outcome stillUnchanged = holdsWhatWasRead(canonical, expectedFingerprint);
            if (!stillUnchanged.isOk())
            {
                return stillUnchanged;
            }
            try
            {
                Files.deleteIfExists(canonical);
            }
            catch (AccessDeniedException denied)
            {
                return Outcome.refused(ACCESS_DENIED, null);
            }
            return Outcome.ok();
        }
        Path directory = canonical.getParent();
        if (directory != null)
        {
            Files.createDirectories(directory);
        }
        Path stagingDirectory = directory == null ? Path.of(".") : directory; //$NON-NLS-1$
        cleanupStaleTemporaryFiles(canonical, stagingDirectory);
        Path temporary = createTemporaryFile(canonical, stagingDirectory);
        IOException failure = null;
        try
        {
            writeForced(temporary, content);
            if (afterStaging != null)
            {
                afterStaging.run();
            }
            Outcome stillUnchanged = holdsWhatWasRead(canonical, expectedFingerprint);
            if (!stillUnchanged.isOk())
            {
                return stillUnchanged;
            }
            moveReplacing(temporary, canonical);
            return Outcome.ok();
        }
        catch (IOException e)
        {
            failure = e;
            throw e;
        }
        finally
        {
            try
            {
                Files.deleteIfExists(temporary);
            }
            catch (IOException cleanupFailure)
            {
                if (failure == null)
                {
                    throw cleanupFailure;
                }
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    /**
     * Tells whether the target still holds the content the caller read.
     *
     * @param canonical the normalized target
     * @param expectedFingerprint the fingerprint the caller read, or {@code null} to skip comparing
     * @return {@link Outcome#ok()} when it does, otherwise the refusal
     */
    private static Outcome holdsWhatWasRead(Path canonical, String expectedFingerprint)
    {
        if (expectedFingerprint == null)
        {
            return Outcome.ok();
        }
        if (Files.notExists(canonical))
        {
            return NO_FILE_FINGERPRINT.equals(expectedFingerprint)
                ? Outcome.ok()
                : Outcome.refused(CHANGED_ON_DISK, null);
        }
        byte[] bytes;
        try
        {
            bytes = Files.readAllBytes(canonical);
        }
        catch (IOException e)
        {
            return Outcome.refused(READ_FAILED, exceptionText(e));
        }
        return expectedFingerprint.equals(fingerprint(bytes))
            ? Outcome.ok()
            : Outcome.refused(CHANGED_ON_DISK, null);
    }

    /**
     * Takes an exclusive lock on the target without truncating it, as an availability and
     * writability probe.
     * <p>
     * A read-only file refuses with {@link #ACCESS_DENIED}. A lock that is already held - by
     * another process, or by this one - refuses with {@link #LOCK_REFUSED}.
     * </p>
     *
     * @param canonical the normalized target, which exists
     * @return {@link Outcome#ok()} when the file is writable and unlocked, otherwise the refusal
     * @throws IOException when the file cannot be opened for a reason other than access
     */
    private static Outcome probeWritableAndUnlocked(Path canonical) throws IOException
    {
        try
        {
            try (FileChannel channel = FileChannel.open(canonical, StandardOpenOption.WRITE))
            {
                try
                {
                    FileLock lock = channel.tryLock();
                    if (lock == null)
                    {
                        return Outcome.refused(LOCK_REFUSED, null);
                    }
                    lock.release();
                    return Outcome.ok();
                }
                catch (OverlappingFileLockException overlapping)
                {
                    return Outcome.refused(LOCK_REFUSED, null);
                }
            }
        }
        catch (AccessDeniedException denied)
        {
            return Outcome.refused(ACCESS_DENIED, null);
        }
    }

    /**
     * Waits for the lock file up to the limit, in short attempts.
     *
     * @param channel the channel of the lock file
     * @param waitMillis how long to wait
     * @return the lock, or {@code null} when the limit ran out
     * @throws IOException when the lock cannot be taken for a reason other than contention
     */
    private static FileLock awaitLockFile(FileChannel channel, long waitMillis) throws IOException
    {
        long deadline = System.currentTimeMillis() + Math.max(0L, waitMillis);
        while (true)
        {
            FileLock lock = channel.tryLock();
            if (lock != null)
            {
                return lock;
            }
            if (System.currentTimeMillis() >= deadline)
            {
                return null;
            }
            try
            {
                Thread.sleep(LOCK_RETRY_PAUSE_MILLIS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /**
     * Asks the workspace to see the file as it now is, without undoing a completed write.
     * <p>
     * The refresh happens after the lock file is released: holding the lock across a workspace
     * call would serialize EDT instances on the workspace lock while they hold this one.
     * </p>
     *
     * @param workspaceFile the workspace handle of the target; may be {@code null}
     * @param canonical the normalized target, for the log line
     */
    private static void refreshQuietly(IFile workspaceFile, Path canonical)
    {
        if (workspaceFile == null)
        {
            return;
        }
        try
        {
            workspaceFile.refreshLocal(IResource.DEPTH_ZERO, null);
        }
        catch (CoreException e)
        {
            Activator.logWarning("The workspace could not refresh " + canonical.getFileName() //$NON-NLS-1$
                + " after it was written: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Creates a sibling staging file with the target's POSIX permissions when available.
     * <p>
     * A new target starts with ordinary 0644 permissions subject to the process umask. Other
     * file-system providers receive no unsupported attributes.
     * </p>
     *
     * @param target the destination whose permissions should be retained
     * @param directory the directory in which to create the staging file
     * @return the new staging file
     * @throws IOException when the file or its permissions cannot be created
     */
    private static Path createTemporaryFile(Path target, Path directory) throws IOException
    {
        Set<PosixFilePermission> permissions = DEFAULT_POSIX_FILE_PERMISSIONS;
        if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null)
        {
            if (Files.exists(target))
            {
                permissions = Files.getPosixFilePermissions(target);
            }
            FileAttribute<Set<PosixFilePermission>> attribute =
                PosixFilePermissions.asFileAttribute(permissions);
            return Files.createTempFile(directory, target.getFileName().toString() + ".", ".tmp", //$NON-NLS-1$ //$NON-NLS-2$
                attribute);
        }
        return Files.createTempFile(directory, target.getFileName().toString() + ".", ".tmp"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Removes staging files left by an earlier process once they are old enough not to be active.
     *
     * @param target the destination whose staging-file prefix identifies owned files
     * @param directory the directory containing the destination
     */
    private static void cleanupStaleTemporaryFiles(Path target, Path directory)
    {
        long cutoff = System.currentTimeMillis() - STALE_TEMPORARY_MILLIS;
        String pattern = target.getFileName().toString() + ".*.tmp"; //$NON-NLS-1$
        try (DirectoryStream<Path> stagedFiles = Files.newDirectoryStream(directory, pattern))
        {
            for (Path stagedFile : stagedFiles)
            {
                try
                {
                    if (Files.getLastModifiedTime(stagedFile).toMillis() < cutoff)
                    {
                        Files.deleteIfExists(stagedFile);
                    }
                }
                catch (IOException e)
                {
                    Activator.logError("Failed to remove a stale staging file " //$NON-NLS-1$
                        + stagedFile.getFileName(), e);
                }
            }
        }
        catch (IOException e)
        {
            Activator.logError("Failed to inspect staging files beside " + target.getFileName(), e); //$NON-NLS-1$
        }
    }

    /**
     * Writes every byte of {@code bytes} to {@code path} and forces them to disk.
     *
     * @param path the file to write; it is truncated because it is a temporary file, not the target
     * @param bytes the bytes to write
     * @throws IOException if the write stops short or the channel cannot be forced
     */
    private static void writeForced(Path path, byte[] bytes) throws IOException
    {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING))
        {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            int written = 0;
            while (buffer.hasRemaining())
            {
                int count = channel.write(buffer);
                if (count <= 0)
                {
                    throw new IOException("writing " + path.getFileName() + " made no progress"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                written += count;
            }
            if (written != bytes.length)
            {
                throw new IOException("short write to " + path.getFileName()); //$NON-NLS-1$
            }
            channel.force(true);
        }
    }

    /**
     * Replaces {@code target} with {@code temporary}, retrying a refused atomic move.
     * <p>
     * There is deliberately no non-atomic fallback: if every atomic attempt fails, the target
     * remains intact and the failure is returned to the caller.
     * </p>
     *
     * @param temporary the staged file
     * @param target the destination
     * @throws IOException if the move fails
     */
    static void moveReplacing(Path temporary, Path target) throws IOException
    {
        moveReplacing(temporary, target, (source, destination) -> Files.move(source, destination,
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING), Thread::sleep);
    }

    /**
     * Repeats an atomic replacement through injectable move and sleep operations.
     *
     * @param temporary the staged file
     * @param target the destination
     * @param mover the atomic move operation
     * @param sleeper the delay operation between attempts
     * @throws IOException when every attempt fails or the retry wait is interrupted
     */
    static void moveReplacing(Path temporary, Path target, AtomicMover mover, RetrySleeper sleeper)
        throws IOException
    {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= ATOMIC_MOVE_ATTEMPTS; attempt++)
        {
            try
            {
                mover.move(temporary, target);
                return;
            }
            catch (IOException e)
            {
                lastFailure = e;
            }
            if (attempt < ATOMIC_MOVE_ATTEMPTS)
            {
                try
                {
                    sleeper.sleep(ATOMIC_MOVE_RETRY_MILLIS);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    IOException interrupted = new IOException(
                        "Interrupted while retrying atomic replacement", e); //$NON-NLS-1$
                    interrupted.addSuppressed(lastFailure);
                    throw interrupted;
                }
            }
        }
        throw lastFailure;
    }

    /**
     * The lock file one target is written through.
     *
     * @param canonical the normalized target
     * @return the lock file's path
     */
    static Path lockFileOf(Path canonical)
    {
        return lockDirectory().resolve(digest(canonical) + ".lock"); //$NON-NLS-1$
    }

    /**
     * The stable name of one target inside the lock directory.
     * <p>
     * The full 64-character digest, where the monopoly locks use their first 32 characters, so the
     * two never name the same file.
     * </p>
     *
     * @param canonical the normalized target
     * @return the hexadecimal SHA-256 of the path's text
     */
    private static String digest(Path canonical)
    {
        try
        {
            byte[] hash = MessageDigest.getInstance("SHA-256") //$NON-NLS-1$
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash)
            {
                hex.append(Character.forDigit((value >> 4) & 0xf, 16));
                hex.append(Character.forDigit(value & 0xf, 16));
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException impossible)
        {
            return Integer.toHexString(canonical.hashCode());
        }
    }

    /**
     * @return where the lock files are kept
     */
    private static Path lockDirectory()
    {
        String override = System.getProperty(LOCK_DIR_PROPERTY);
        if (override != null && !override.isEmpty())
        {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".aiedt", "locks"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * The text of an exception to keep with a refused write.
     *
     * @param failure the exception
     * @return its message, or {@link Throwable#toString()} when it has none
     */
    private static String exceptionText(Throwable failure)
    {
        String message = failure.getMessage();
        if (message == null || message.isEmpty())
        {
            return failure.toString();
        }
        return message;
    }
}
