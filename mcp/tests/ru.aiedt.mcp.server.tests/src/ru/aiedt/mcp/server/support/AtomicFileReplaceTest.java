/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * A replacement that lost the content it was given refuses, one that still holds it goes through,
 * and the writers of one file - a thread of this instance, another process on the machine - wait
 * for each other rather than racing.
 */
public class AtomicFileReplaceTest
{
    private static final String ORIGINAL = "groups:\n- name: Kept\n"; //$NON-NLS-1$

    private static final String REPLACEMENT = "groups:\n- name: Shelf\n  path: Catalogs\n"; //$NON-NLS-1$

    private static final String FOREIGN = "groups:\n- name: Written elsewhere\n"; //$NON-NLS-1$

    private static Path locks;

    private Path directory;

    /**
     * Points the lock directory at a temporary one, so the tests write nothing into the user's.
     */
    @BeforeClass
    public static void aSeparateLockDirectory() throws IOException
    {
        locks = Files.createTempDirectory("aiedt-replace-locks-"); //$NON-NLS-1$
        System.setProperty("aiedt.store.locks.dir", locks.toString()); //$NON-NLS-1$
    }

    /**
     * Restores the lock directory setting.
     */
    @AfterClass
    public static void theLockDirectoryGoes() throws IOException
    {
        System.clearProperty("aiedt.store.locks.dir"); //$NON-NLS-1$
        if (locks != null && Files.exists(locks))
        {
            try (var walk = Files.walk(locks))
            {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try
                    {
                        Files.deleteIfExists(path);
                    }
                    catch (IOException ignored)
                    {
                        // A lock still held would keep one file; the tests release theirs first.
                    }
                });
            }
        }
    }

    /**
     * Creates a temporary directory for the file under test.
     *
     * @throws IOException when the directory cannot be created
     */
    @Before
    public void aDirectory() throws IOException
    {
        directory = Files.createTempDirectory("aiedt-atomic-replace-"); //$NON-NLS-1$
    }

    /**
     * Deletes the temporary directory.
     *
     * @throws IOException when a file cannot be deleted
     */
    @After
    public void theDirectoryGoes() throws IOException
    {
        if (directory != null && Files.exists(directory))
        {
            try (var walk = Files.walk(directory))
            {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try
                    {
                        Files.deleteIfExists(path);
                    }
                    catch (IOException ignored)
                    {
                        // A lock still held would keep one file; the tests release theirs first.
                    }
                });
            }
        }
    }

    /**
     * A file that no longer holds what was read is not replaced.
     *
     * @throws Exception when the file cannot be written
     */
    @Test
    public void aChangedFileIsNotReplaced() throws Exception
    {
        Path file = aFileWith(ORIGINAL);
        AtomicFileReplace.Outcome refused = AtomicFileReplace.replace(file,
            fingerprint(FOREIGN), bytes(REPLACEMENT), null);
        assertEquals(AtomicFileReplace.CHANGED_ON_DISK, refused.getCode());
        assertFalse(refused.isOk());
        assertEquals(ORIGINAL, Files.readString(file));
    }

    /**
     * A file that changes between the first check and the move is not replaced either: the second
     * check, immediately before the atomic move, catches what arrived in that window.
     *
     * @throws Exception when the file cannot be written
     */
    @Test
    public void aFileChangedBetweenTheCheckAndTheMoveIsNotReplaced() throws Exception
    {
        Path file = aFileWith(ORIGINAL);
        AtomicFileReplace.Outcome refused = AtomicFileReplace.replace(file,
            fingerprint(ORIGINAL), bytes(REPLACEMENT), null, 4000L, () -> {
                try
                {
                    Files.write(file, bytes(FOREIGN));
                }
                catch (IOException e)
                {
                    throw new UncheckedIOException(e);
                }
            });
        assertEquals(AtomicFileReplace.CHANGED_ON_DISK, refused.getCode());
        assertEquals("the external edit survives whole", FOREIGN, Files.readString(file)); //$NON-NLS-1$
        assertEquals("no staging file remains", 1, entries()); //$NON-NLS-1$
    }

    /**
     * A file that still holds what was read is replaced whole, whether it exists or is new.
     *
     * @throws Exception when the file cannot be written
     */
    @Test
    public void anUnchangedFileIsReplacedWholly() throws Exception
    {
        Path file = directory.resolve("aiedt-clusters.yaml"); //$NON-NLS-1$
        assertFalse(Files.exists(file));
        assertTrue(AtomicFileReplace.replace(file, AtomicFileReplace.NO_FILE_FINGERPRINT,
            bytes(REPLACEMENT), null).isOk());
        assertEquals(REPLACEMENT, Files.readString(file));

        assertTrue(AtomicFileReplace.replace(file, fingerprint(REPLACEMENT),
            bytes("short\n"), null).isOk()); //$NON-NLS-1$
        assertEquals("short\n", Files.readString(file)); //$NON-NLS-1$
        assertEquals(1, entries());
    }

    /**
     * A second writer of this instance waits for the first and is then refused by the fingerprint:
     * the file now holds what the first writer wrote, not what the second one read.
     *
     * @throws Exception when the writers cannot run
     */
    @Test
    public void aSecondWriterWaitsAndIsRefusedByTheFingerprint() throws Exception
    {
        Path file = aFileWith(ORIGINAL);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try
        {
            CountDownLatch staged = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<AtomicFileReplace.Outcome> first = pool.submit(() -> AtomicFileReplace.replace(
                file, fingerprint(ORIGINAL), bytes(REPLACEMENT), null, 8000L, () -> {
                    staged.countDown();
                    await(release);
                }));
            assertTrue("the first writer must reach the staging seam", staged.await(5, TimeUnit.SECONDS)); //$NON-NLS-1$

            Future<AtomicFileReplace.Outcome> second = pool.submit(() -> AtomicFileReplace.replace(
                file, fingerprint(ORIGINAL), bytes("groups: []\n"), null, 8000L, null)); //$NON-NLS-1$

            release.countDown();
            assertEquals(AtomicFileReplace.OK, first.get(10, TimeUnit.SECONDS).getCode());
            assertEquals(AtomicFileReplace.CHANGED_ON_DISK,
                second.get(10, TimeUnit.SECONDS).getCode());
            assertEquals(REPLACEMENT, Files.readString(file));
        }
        finally
        {
            pool.shutdownNow();
        }
    }

    /**
     * A lock file held by another process for longer than the wait limit refuses the write with
     * that reason, and the target is untouched.
     *
     * @throws Exception when the child process cannot run
     */
    @Test
    public void aLockHeldLongerThanTheWaitLimitRefusesTheWrite() throws Exception
    {
        Path file = aFileWith(ORIGINAL);
        Process child = holdTheLockFromAnotherProcess(file, 2000L);
        try
        {
            AtomicFileReplace.Outcome refused = AtomicFileReplace.replace(file,
                fingerprint(ORIGINAL), bytes(REPLACEMENT), null, 400L, null);
            assertEquals(AtomicFileReplace.LOCK_REFUSED, refused.getCode());
            assertNotNull(refused.getDetail());
            assertTrue("the refusal names the wait limit: " + refused.getDetail(), //$NON-NLS-1$
                refused.getDetail().contains("longer than")); //$NON-NLS-1$
            assertEquals(ORIGINAL, Files.readString(file));
        }
        finally
        {
            stop(child);
        }
    }

    /**
     * A lock file held by another process and released within the wait limit is waited out, and
     * the write then goes through.
     *
     * @throws Exception when the child process cannot run
     */
    @Test
    public void aLockHeldByAnotherProcessIsWaitedOut() throws Exception
    {
        Path file = aFileWith(ORIGINAL);
        Process child = holdTheLockFromAnotherProcess(file, 700L);
        try
        {
            AtomicFileReplace.Outcome waited = AtomicFileReplace.replace(file,
                fingerprint(ORIGINAL), bytes(REPLACEMENT), null, 5000L, null);
            assertEquals(AtomicFileReplace.OK, waited.getCode());
            assertEquals(REPLACEMENT, Files.readString(file));
        }
        finally
        {
            stop(child);
        }
    }

    /**
     * An operating-system lock held on the destination itself refuses the write and leaves the
     * original bytes.
     *
     * @throws Exception when the file or the lock cannot be opened
     */
    @Test
    public void aHeldOsLockOnTheDestinationRefuses() throws Exception
    {
        Path file = aFileWith(ORIGINAL);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE);
            FileLock lock = channel.lock())
        {
            assertTrue(lock.isValid());
            AtomicFileReplace.Outcome refused = AtomicFileReplace.replace(file,
                fingerprint(ORIGINAL), bytes(REPLACEMENT), null);
            assertEquals(AtomicFileReplace.LOCK_REFUSED, refused.getCode());
            assertEquals(ORIGINAL.length(), channel.size());
        }
        assertEquals(ORIGINAL, Files.readString(file));
        assertEquals(1, entries());
    }

    /**
     * A file that holds what was read is removed, one that changed is not, and a file that is not
     * there is nothing to remove.
     *
     * @throws Exception when the file cannot be written
     */
    @Test
    public void removalFollowsTheSameRules() throws Exception
    {
        Path file = aFileWith(ORIGINAL);
        AtomicFileReplace.Outcome refused = AtomicFileReplace.remove(file,
            fingerprint(FOREIGN), null);
        assertEquals(AtomicFileReplace.CHANGED_ON_DISK, refused.getCode());
        assertEquals(ORIGINAL, Files.readString(file));

        assertTrue(AtomicFileReplace.remove(file, fingerprint(ORIGINAL), null).isOk());
        assertFalse(Files.exists(file));

        assertTrue(AtomicFileReplace.remove(file, AtomicFileReplace.NO_FILE_FINGERPRINT, null).isOk());
        assertFalse(Files.exists(file));
    }

    /**
     * A transient refusal is retried with atomic replacement until it succeeds.
     *
     * @throws Exception when the test files cannot be written
     */
    @Test
    public void anAtomicReplaceIsRetried() throws Exception
    {
        Path target = directory.resolve("aiedt-clusters.yaml"); //$NON-NLS-1$
        Path temporary = directory.resolve("staged.tmp"); //$NON-NLS-1$
        Files.writeString(target, "old"); //$NON-NLS-1$
        Files.writeString(temporary, "new"); //$NON-NLS-1$
        AtomicInteger attempts = new AtomicInteger();

        AtomicFileReplace.moveReplacing(temporary, target, (source, destination) -> {
            if (attempts.incrementAndGet() < 4)
            {
                throw new AccessDeniedException(destination.toString());
            }
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        }, millis -> {
            // The retry policy is under test; sleeping would only slow it down.
        });

        assertEquals(4, attempts.get());
        assertEquals("new", Files.readString(target)); //$NON-NLS-1$
    }

    /**
     * Exhausting atomic replacement retries leaves the destination and staged file untouched.
     *
     * @throws Exception when the test files cannot be written
     */
    @Test
    public void aFinallyRefusedAtomicReplaceLeavesTheDestinationWhole() throws Exception
    {
        Path target = directory.resolve("aiedt-clusters.yaml"); //$NON-NLS-1$
        Path temporary = directory.resolve("staged.tmp"); //$NON-NLS-1$
        Files.writeString(target, "old"); //$NON-NLS-1$
        Files.writeString(temporary, "new"); //$NON-NLS-1$
        AtomicInteger attempts = new AtomicInteger();
        IOException failure = null;

        try
        {
            AtomicFileReplace.moveReplacing(temporary, target, (source, destination) -> {
                attempts.incrementAndGet();
                throw new AccessDeniedException(destination.toString());
            }, millis -> {
                // The retry policy is under test; sleeping would only slow it down.
            });
        }
        catch (IOException e)
        {
            failure = e;
        }

        assertNotNull(failure);
        assertEquals(4, attempts.get());
        assertEquals("old", Files.readString(target)); //$NON-NLS-1$
        assertEquals("new", Files.readString(temporary)); //$NON-NLS-1$
    }

    /**
     * Atomic replacement retains the permissions of an existing POSIX destination.
     *
     * @throws Exception when the test file cannot be written
     */
    @Test
    public void anAtomicReplaceRetainsExistingPosixPermissions() throws Exception
    {
        Path file = directory.resolve("aiedt-clusters.yaml"); //$NON-NLS-1$
        PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        org.junit.Assume.assumeNotNull(view);
        Set<PosixFilePermission> permissions = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ);
        Files.writeString(file, "old"); //$NON-NLS-1$
        Files.setPosixFilePermissions(file, permissions);

        assertTrue(AtomicFileReplace.replace(file, fingerprint("old"), bytes("new"), null).isOk()); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(permissions, Files.getPosixFilePermissions(file));
    }

    /**
     * A staging file left by an earlier process is removed after it becomes stale.
     *
     * @throws Exception when the test files cannot be written
     */
    @Test
    public void anOldStagingFileIsCleanedBeforeWriting() throws Exception
    {
        Path target = directory.resolve("aiedt-clusters.yaml"); //$NON-NLS-1$
        Path stale = directory.resolve("aiedt-clusters.yaml.crashed.tmp"); //$NON-NLS-1$
        Path fresh = directory.resolve("aiedt-clusters.yaml.active.tmp"); //$NON-NLS-1$
        Files.writeString(stale, "stale"); //$NON-NLS-1$
        Files.writeString(fresh, "fresh"); //$NON-NLS-1$
        Files.setLastModifiedTime(stale, java.nio.file.attribute.FileTime.fromMillis(
            System.currentTimeMillis() - 2L * 24L * 60L * 60L * 1000L));

        assertTrue(AtomicFileReplace.replace(target, AtomicFileReplace.NO_FILE_FINGERPRINT,
            bytes("content"), null).isOk()); //$NON-NLS-1$

        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(fresh));
    }

    /**
     * A workspace that refuses the refresh after the write is a warning, not a failure: the file
     * was written, and the outcome is still {@code ok}.
     *
     * @throws Exception when the file cannot be written
     */
    @Test
    public void aWorkspaceThatRefusesTheRefreshDoesNotFailTheWrite() throws Exception
    {
        Path file = aFileWith(ORIGINAL);
        IFile refusing = (IFile)java.lang.reflect.Proxy.newProxyInstance(
            IFile.class.getClassLoader(), new Class<?>[]{IFile.class},
            (proxy, method, arguments) -> {
                if ("refreshLocal".equals(method.getName())) //$NON-NLS-1$
                {
                    throw new CoreException(new org.eclipse.core.runtime.Status(
                        org.eclipse.core.runtime.IStatus.ERROR, "aiedt.test", //$NON-NLS-1$
                        "the workspace refused the refresh"));
                }
                return null;
            });

        AtomicFileReplace.Outcome outcome = AtomicFileReplace.replace(file,
            fingerprint(ORIGINAL), bytes(REPLACEMENT), refusing);

        assertTrue(String.valueOf(outcome), outcome.isOk());
        assertEquals(REPLACEMENT, Files.readString(file));
    }

    /**
     * Writes the file under test and returns it.
     *
     * @param content the file contents
     * @return the file
     * @throws IOException when the file cannot be written
     */
    private Path aFileWith(String content) throws IOException
    {
        Path file = directory.resolve("aiedt-clusters.yaml"); //$NON-NLS-1$
        Files.writeString(file, content);
        return file;
    }

    /**
     * @param text the text to encode
     * @return its UTF-8 bytes
     */
    private static byte[] bytes(String text)
    {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * @param text the content the caller read
     * @return the fingerprint of it
     */
    private static String fingerprint(String text)
    {
        return AtomicFileReplace.fingerprint(bytes(text));
    }

    /**
     * @return how many files the directory holds
     * @throws IOException when the directory cannot be listed
     */
    private long entries() throws IOException
    {
        try (var listed = Files.list(directory))
        {
            return listed.count();
        }
    }

    /**
     * Waits for a latch, re-interrupting on the way out.
     *
     * @param latch the latch to await
     */
    private static void await(CountDownLatch latch)
    {
        try
        {
            latch.await();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Runs a child Java process that holds the lock file of the target for a while.
     * <p>
     * The child is the test's own process: it is started here and stopped by {@link #stop}, and it
     * touches nothing but the lock file it is given.
     * </p>
     *
     * @param target the file whose lock is held
     * @param holdMillis how long to hold it after announcing it
     * @return the running child, which has announced that it holds the lock
     * @throws Exception when the child cannot be started or does not announce the lock
     */
    private static Process holdTheLockFromAnotherProcess(Path target, long holdMillis) throws Exception
    {
        Path lockFile = AtomicFileReplace.lockFileOf(target.toAbsolutePath().normalize());
        Files.createDirectories(lockFile.getParent());
        Path program = Files.createTempFile("aiedt-lock-holder-", ".java"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.write(program, ("""
            import java.nio.channels.FileChannel;
            import java.nio.channels.FileLock;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.nio.file.StandardOpenOption;

            public class LockHolder {
                public static void main(String[] arguments) throws Exception {
                    try (FileChannel channel = FileChannel.open(Path.of(arguments[0]),
                            StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
                            FileLock lock = channel.tryLock()) {
                        if (lock == null) {
                            System.exit(2);
                        }
                        System.out.println("held");
                        System.out.flush();
                        Thread.sleep(Long.parseLong(arguments[1]));
                    }
                }
            }
            """).getBytes(StandardCharsets.UTF_8));
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", //$NON-NLS-1$ //$NON-NLS-2$
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                ? "java.exe" : "java").toString(); //$NON-NLS-1$ //$NON-NLS-2$
        Process child = new ProcessBuilder(javaExecutable, program.toString(), lockFile.toString(),
            Long.toString(holdMillis)).start();
        try (BufferedReader output = new BufferedReader(
            new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8)))
        {
            String announced = output.readLine();
            if (!"held".equals(announced)) //$NON-NLS-1$
            {
                child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                throw new IllegalStateException("the child did not hold the lock: " + announced); //$NON-NLS-1$
            }
        }
        return child;
    }

    /**
     * Stops the child process of a test.
     *
     * @param child the child, which may already have exited
     * @throws InterruptedException when the wait is interrupted
     */
    private static void stop(Process child) throws InterruptedException
    {
        child.destroy();
        if (!child.waitFor(5, TimeUnit.SECONDS))
        {
            child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }
}
