/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.After;
import org.junit.Test;

/**
 * Covers the check that decides whether unpacking a binary external object produced anything.
 * <p>
 * The conversion runs in a separate Designer process and reports back only by not throwing. That
 * is not evidence: it can decline the file and exit cleanly, and the caller then hands an empty
 * directory to the import and ends up hunting for an object that was never created. The check
 * below is what turns that into a refusal, so it has to treat every flavour of "nothing here" -
 * empty, absent, not a directory - the same way.
 * </p>
 */
public class ConversionOutputCheckTest
{
    private final List<Path> created = new ArrayList<>();

    @After
    public void removeWhatTheTestsCreated()
    throws IOException
    {
        for (Path root : created)
        {
            if (!Files.exists(root))
            {
                continue;
            }
            List<Path> entries = new ArrayList<>();
            try (java.util.stream.Stream<Path> walk = Files.walk(root))
            {
                walk.sorted(Comparator.reverseOrder()).forEach(entries::add);
            }
            for (Path p : entries)
            {
                Files.deleteIfExists(p);
            }
        }
    }

    private Path tempDir() throws IOException
    {
        Path dir = Files.createTempDirectory("aiedt-unpack-check"); //$NON-NLS-1$
        created.add(dir);
        return dir;
    }

    @Test
    public void aDirectoryWithFilesCountsAsOutput()
    throws IOException
    {
        Path dir = tempDir();
        Files.createFile(dir.resolve("ExternalDataProcessor.xml")); //$NON-NLS-1$

        assertFalse(BmInfobaseExtensionHelper.isEmptyDirectory(dir));
    }

    @Test
    public void aDirectoryHoldingOnlyAnotherDirectoryStillCountsAsOutput()
    throws IOException
    {
        // The Designer writes a tree, not a flat file list - a single subfolder is a real result.
        Path dir = tempDir();
        Files.createDirectory(dir.resolve("Ext")); //$NON-NLS-1$

        assertFalse(BmInfobaseExtensionHelper.isEmptyDirectory(dir));
    }

    @Test
    public void anEmptyDirectoryIsNoOutput()
    throws IOException
    {
        assertTrue(BmInfobaseExtensionHelper.isEmptyDirectory(tempDir()));
    }

    @Test
    public void aDirectoryThatWasNeverCreatedIsNoOutput()
    throws IOException
    {
        assertTrue(BmInfobaseExtensionHelper.isEmptyDirectory(
            tempDir().resolve("never-created"))); //$NON-NLS-1$
    }

    @Test
    public void aFileInPlaceOfTheDirectoryIsNoOutput()
    throws IOException
    {
        Path file = tempDir().resolve("not-a-directory"); //$NON-NLS-1$
        Files.createFile(file);

        assertTrue(BmInfobaseExtensionHelper.isEmptyDirectory(file));
    }

    @Test
    public void nothingAtAllIsNoOutput()
    {
        assertTrue(BmInfobaseExtensionHelper.isEmptyDirectory(null));
    }
}
