/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Replacing a clusters file goes through a temporary file, and a lock that cannot be taken leaves
 * the destination as it was. In one runtime an overlapping lock is reported by an exception rather
 * than by a null lock; both answers must refuse without truncating.
 */
public class ARefusedLockLeavesTheFileWholeTest
{
    private Path directory;

    /**
     * Creates a temporary directory for the file under test.
     *
     * @throws IOException when the directory cannot be created
     */
    @Before
    public void aDirectory() throws IOException
    {
        directory = Files.createTempDirectory("aiedt-cluster-lock-"); //$NON-NLS-1$
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
     * A lock held on the destination makes the write answer {@code false} and leaves the original
     * bytes, with no temporary file left behind.
     *
     * @throws Exception when the file or the lock cannot be opened
     */
    @Test
    public void aHeldLockLeavesTheOriginalBytes() throws Exception
    {
        Path file = directory.resolve("aiedt-clusters.yaml"); //$NON-NLS-1$
        byte[] original = "groups:\n- name: Kept\n".getBytes(StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.write(file, original);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE);
            FileLock lock = channel.lock())
        {
            assertTrue(lock.isValid());
            assertFalse(YamlClusterStore.writeChannelLocked(file, "groups: []\n")); //$NON-NLS-1$
            // A second open cannot read the file while this lock is held. The size on this channel
            // is what a truncate-before-lock would already have driven to zero.
            assertEquals(original.length, (int)channel.size());
        }
        assertEquals(new String(original, StandardCharsets.UTF_8), Files.readString(file));
        assertEquals(1, entries());
    }

    /**
     * A completed write replaces the whole destination: a shorter document does not leave the old
     * tail, and a longer one is stored in full. No temporary file remains.
     *
     * @throws Exception when the write fails
     */
    @Test
    public void aFinishedWriteReplacesTheWholeFile() throws Exception
    {
        Path file = directory.resolve("aiedt-clusters.yaml"); //$NON-NLS-1$
        Files.writeString(file, "old-and-longer-than-the-replacement"); //$NON-NLS-1$

        assertTrue(YamlClusterStore.writeChannelLocked(file, "short\n")); //$NON-NLS-1$
        assertEquals("short\n", Files.readString(file)); //$NON-NLS-1$

        String longer = "groups:\n- name: Shelf\n  path: Catalogs\n"; //$NON-NLS-1$
        assertTrue(YamlClusterStore.writeChannelLocked(file, longer));
        assertEquals(longer, Files.readString(file));
        assertEquals(1, entries());
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
     * A destination that does not exist yet is created with the full document, not an empty file.
     *
     * @throws Exception when the write fails
     */
    @Test
    public void aNewFileIsWrittenWhole() throws Exception
    {
        Path file = directory.resolve("aiedt-clusters.yaml"); //$NON-NLS-1$
        String content = "groups:\n- name: Shelf\n"; //$NON-NLS-1$
        assertFalse(Files.exists(file));

        assertTrue(YamlClusterStore.writeChannelLocked(file, content));

        assertEquals(content, Files.readString(file));
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

        YamlClusterStore.moveReplacing(temporary, target, (source, destination) -> {
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
            YamlClusterStore.moveReplacing(temporary, target, (source, destination) -> {
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

        assertTrue(YamlClusterStore.writeChannelLocked(file, "new")); //$NON-NLS-1$

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

        assertTrue(YamlClusterStore.writeChannelLocked(target, "content")); //$NON-NLS-1$

        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(fresh));
    }
}
