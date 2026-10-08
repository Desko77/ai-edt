/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * A caller is told about a replacement or a removal once the target holds what was asked for and
 * before the workspace is told about it, and is told nothing about a refusal.
 * <p>
 * The moment matters to a caller that keeps its own name for the file's bytes: told any earlier it
 * would publish bytes the file does not hold yet, and the refresh that follows would arrive as a
 * change it cannot recognize as its own. A refused write leaves the target as it was, so a caller
 * told about it would publish a state the file never reached.
 * </p>
 */
public class ACommitIsReportedOnceTheBytesAreThereTest
{
    private static final String ORIGINAL = "groups:\n- name: Kept\n"; //$NON-NLS-1$

    private static final String REPLACEMENT = "groups:\n- name: Shelf\n  path: Catalogs\n"; //$NON-NLS-1$

    private static final String FOREIGN = "groups:\n- name: Written elsewhere\n"; //$NON-NLS-1$

    private static Path locks;

    private Path directory;

    /**
     * Points the lock directory at a temporary one, so the tests write nothing into the user's.
     *
     * @throws IOException when the directory cannot be created
     */
    @BeforeClass
    public static void aSeparateLockDirectory() throws IOException
    {
        locks = Files.createTempDirectory("aiedt-commit-locks-"); //$NON-NLS-1$
        System.setProperty("aiedt.store.locks.dir", locks.toString()); //$NON-NLS-1$
    }

    /**
     * Restores the lock directory setting.
     */
    @AfterClass
    public static void theLockDirectoryGoes()
    {
        System.clearProperty("aiedt.store.locks.dir"); //$NON-NLS-1$
    }

    /**
     * Creates a temporary directory for the file under test.
     *
     * @throws IOException when the directory cannot be created
     */
    @Before
    public void aDirectory() throws IOException
    {
        directory = Files.createTempDirectory("aiedt-commit-"); //$NON-NLS-1$
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
                        // The lock file of an unfinished test would keep one directory.
                    }
                });
            }
        }
    }

    /**
     * What the caller is handed at the moment of the report is the file with its new bytes in it,
     * not the file as it was before the write.
     *
     * @throws Exception when the file cannot be written
     */
    @Test
    public void aReplacedFileIsReportedWithItsBytesInPlace() throws Exception
    {
        Path file = aFileWith(ORIGINAL);
        List<String> reported = new ArrayList<>();

        AtomicFileReplace.Outcome outcome = AtomicFileReplace.replace(file, fingerprint(ORIGINAL),
            bytes(REPLACEMENT), null, () -> reported.add(read(file)));

        assertEquals(AtomicFileReplace.OK, outcome.getCode());
        assertEquals("the report is made once", List.of(REPLACEMENT), reported); //$NON-NLS-1$
    }

    /**
     * A removal is reported once the file is gone.
     *
     * @throws Exception when the file cannot be written
     */
    @Test
    public void aRemovedFileIsReportedAfterItIsGone() throws Exception
    {
        Path file = aFileWith(ORIGINAL);
        List<Boolean> stillThere = new ArrayList<>();

        AtomicFileReplace.Outcome outcome = AtomicFileReplace.remove(file, fingerprint(ORIGINAL), null,
            () -> stillThere.add(Files.exists(file)));

        assertTrue(String.valueOf(outcome), outcome.isOk());
        assertEquals("the report is made once, with the file gone", List.of(Boolean.FALSE), stillThere); //$NON-NLS-1$
    }

    /**
     * A removal of a file that is already gone asks for an absence the disk has, so it is reported
     * like any other committed removal: the caller's state then names that absence, and the refresh
     * that follows has to arrive as this removal's own.
     *
     * @throws Exception when the file cannot be removed
     */
    @Test
    public void aTargetAlreadyGoneIsReportedAsTheRequestedState() throws Exception
    {
        Path file = directory.resolve("aiedt-clusters.yaml"); //$NON-NLS-1$
        AtomicInteger reported = new AtomicInteger();

        AtomicFileReplace.Outcome outcome = AtomicFileReplace.remove(file,
            AtomicFileReplace.NO_FILE_FINGERPRINT, null, reported::incrementAndGet);

        assertTrue(String.valueOf(outcome), outcome.isOk());
        assertEquals(1, reported.get());
    }

    /**
     * A replacement refused because the file no longer holds what was read reports nothing: the
     * target keeps its bytes, so there is no new state for the caller to take.
     *
     * @throws Exception when the file cannot be written
     */
    @Test
    public void aRefusedReplacementIsNotReported() throws Exception
    {
        Path file = aFileWith(ORIGINAL);
        AtomicInteger reported = new AtomicInteger();

        AtomicFileReplace.Outcome refused = AtomicFileReplace.replace(file, fingerprint(FOREIGN),
            bytes(REPLACEMENT), null, reported::incrementAndGet);

        assertEquals(AtomicFileReplace.CHANGED_ON_DISK, refused.getCode());
        assertEquals("a refused replacement reports nothing", 0, reported.get()); //$NON-NLS-1$
        assertEquals(ORIGINAL, read(file));
    }

    /**
     * A removal refused for the same reason reports nothing, and the file keeps its bytes.
     *
     * @throws Exception when the file cannot be written
     */
    @Test
    public void aRefusedRemovalIsNotReported() throws Exception
    {
        Path file = aFileWith(ORIGINAL);
        AtomicInteger reported = new AtomicInteger();

        AtomicFileReplace.Outcome refused = AtomicFileReplace.remove(file, fingerprint(FOREIGN), null,
            reported::incrementAndGet);

        assertEquals(AtomicFileReplace.CHANGED_ON_DISK, refused.getCode());
        assertEquals("a refused removal reports nothing", 0, reported.get()); //$NON-NLS-1$
        assertTrue(Files.exists(file));
        assertEquals(ORIGINAL, read(file));
    }

    /**
     * A replacement with no report asked for goes through, so a caller that has nothing to publish
     * keeps the behaviour it had.
     *
     * @throws Exception when the file cannot be written
     */
    @Test
    public void aReplacementWithoutAReportGoesThrough() throws Exception
    {
        Path file = aFileWith(ORIGINAL);

        assertTrue(AtomicFileReplace.replace(file, fingerprint(ORIGINAL), bytes(REPLACEMENT),
            null).isOk());
        assertEquals(REPLACEMENT, read(file));
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
     * The text of a file, for a report made from inside a write.
     *
     * @param file the file
     * @return its contents
     * @throws java.io.UncheckedIOException when the file cannot be read
     */
    private static String read(Path file)
    {
        try
        {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * The fingerprint of a text.
     *
     * @param content the text
     * @return its fingerprint
     */
    private static String fingerprint(String content)
    {
        return AtomicFileReplace.fingerprint(bytes(content));
    }

    /**
     * The bytes of a text in the encoding the files under test use.
     *
     * @param content the text
     * @return its bytes
     */
    private static byte[] bytes(String content)
    {
        return content.getBytes(StandardCharsets.UTF_8);
    }
}
