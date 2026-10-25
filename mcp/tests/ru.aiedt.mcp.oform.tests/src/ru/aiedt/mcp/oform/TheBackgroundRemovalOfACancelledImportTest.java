/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * The background removal of a cancelled import's parent directory: a directory that comes back
 * once - the export's place step can put it back right after the Designer exits - is removed
 * again, and the loop stops only after the directory has stayed gone for the quiet interval.
 * The ceiling, the quiet interval, the pause and the pass action are the seam of the loop; the
 * production caller passes its own constants and a pass action that does nothing.
 */
public class TheBackgroundRemovalOfACancelledImportTest
{
    /**
     * A directory that is put back once, right after the first removal, is removed again and the
     * loop ends with it gone: the pass action recreates the directory after the first pass only,
     * so a loop that stopped at the first disappearance would leave it behind.
     */
    @Test
    public void aDirectoryThatComesBackOnceIsRemovedAgain() throws IOException
    {
        Path root = Files.createTempDirectory("aiedt-oform-removal"); //$NON-NLS-1$
        try
        {
            Path directory = Files.createDirectories(root.resolve("oform-import-test")); //$NON-NLS-1$
            Files.write(directory.resolve("begun.bin"), new byte[]{1}); //$NON-NLS-1$
            AtomicBoolean putBack = new AtomicBoolean();
            AtomicInteger passes = new AtomicInteger();
            AtomicBoolean goneAgainWhenTheActionRan = new AtomicBoolean();

            OrdinaryFormImport.removeUntilItStaysGone(directory, 10_000L, 200L, 20L, () -> {
                passes.incrementAndGet();
                if (putBack.compareAndSet(false, true))
                {
                    try
                    {
                        Files.createDirectories(directory);
                        Files.write(directory.resolve("working.bin"), new byte[]{1}); //$NON-NLS-1$
                    }
                    catch (IOException e)
                    {
                        throw new IllegalStateException(e);
                    }
                }
                else if (!Files.exists(directory))
                {
                    goneAgainWhenTheActionRan.set(true);
                }
            });

            assertFalse("the loop ended with the directory still there: " + directory, Files.exists(directory)); //$NON-NLS-1$
            assertTrue("the pass after the recreation did not remove the directory again", //$NON-NLS-1$
                goneAgainWhenTheActionRan.get());
            assertTrue("the loop went on for " + passes.get() + " passes", passes.get() >= 3); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            try (var walk = Files.walk(root))
            {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }
}
