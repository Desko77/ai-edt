/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.support.DiagnosticsBaselineStore.Baseline;
import ru.aiedt.mcp.server.support.DiagnosticsBaselineStore.Diagnostic;
import ru.aiedt.mcp.server.support.DiagnosticsBaselineStore.UnreadableBaselineException;

/**
 * What the baseline file itself promises: written whole or not at all, one project never reading
 * another's record, a file that cannot be understood refusing rather than reading as an empty
 * baseline, and concurrent writers leaving a file that is either readable or gone.
 */
public class TheBaselineFileSurvivesItsWritersTest
{
    private Path root;

    @Before
    public void makeRoot() throws IOException
    {
        root = Files.createTempDirectory("diagnostics-baseline-test"); //$NON-NLS-1$
    }

    @After
    public void dropRoot() throws IOException
    {
        deleteTree(root);
    }

    private static void deleteTree(Path dir) throws IOException
    {
        if (!Files.exists(dir))
        {
            return;
        }
        try (var stream = Files.walk(dir))
        {
            var paths = stream.sorted(java.util.Comparator.reverseOrder());
            for (Path path : paths.toArray(Path[]::new))
            {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Diagnostic finding(String objectRef, String message)
    {
        return new Diagnostic(objectRef, "bsl-comment-length", message, "ERRORS"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A saved baseline reads back with its moment, its scope and its records.
     */
    @Test
    public void aSavedBaselineReadsBack() throws IOException
    {
        DiagnosticsBaselineStore.save(root, "Project", Collections.emptyList(), //$NON-NLS-1$
            Arrays.asList(finding("Catalog.Products", "Comment is too long"), //$NON-NLS-1$ //$NON-NLS-2$
                finding("Catalog.Products", "Comment is too long"))); //$NON-NLS-1$ //$NON-NLS-2$

        Baseline read = DiagnosticsBaselineStore.load(root, "Project"); //$NON-NLS-1$

        assertNotNull(read);
        assertNotNull(read.savedAt);
        assertFalse(read.isScoped());
        assertEquals(1, read.diagnostics.size());
        assertEquals(2, read.diagnostics.get(0).count);
    }

    /**
     * A scope of named objects is part of what reads back.
     */
    @Test
    public void theScopeReadsBack() throws IOException
    {
        DiagnosticsBaselineStore.save(root, "Project", Arrays.asList("Catalog.Products"), //$NON-NLS-1$ //$NON-NLS-2$
            Collections.emptyList());

        Baseline read = DiagnosticsBaselineStore.load(root, "Project"); //$NON-NLS-1$

        assertTrue(read.isScoped());
        assertEquals(Arrays.asList("Catalog.Products"), read.objects); //$NON-NLS-1$
    }

    /**
     * No file answers null, which a caller tells from an empty baseline by not mistaking one for
     * the other.
     */
    @Test
    public void noFileAnswersNull() throws IOException
    {
        assertNull(DiagnosticsBaselineStore.load(root, "Project")); //$NON-NLS-1$
    }

    /**
     * A file of another format version refuses instead of reading as a baseline this build never
     * wrote.
     */
    @Test
    public void anotherFormatVersionRefuses() throws IOException
    {
        Files.writeString(DiagnosticsBaselineStore.fileFor(root, "Project"), //$NON-NLS-1$
            "{\"formatVersion\":99,\"savedAt\":\"2026-01-01T00:00:00Z\",\"objects\":[]," //$NON-NLS-1$
                + "\"diagnostics\":[]}",
            StandardCharsets.UTF_8);

        try
        {
            DiagnosticsBaselineStore.load(root, "Project"); //$NON-NLS-1$
            fail("a file of another format version must refuse"); //$NON-NLS-1$
        }
        catch (UnreadableBaselineException refused)
        {
            assertTrue(refused.getMessage().contains("99")); //$NON-NLS-1$
        }
    }

    /**
     * A damaged file refuses rather than reading as an empty baseline - empty says the project was
     * clean, and a file that cannot be parsed says nothing of the sort.
     */
    @Test
    public void aDamagedFileRefusesRatherThanReadingEmpty() throws IOException
    {
        Files.writeString(DiagnosticsBaselineStore.fileFor(root, "Project"), //$NON-NLS-1$
            "not json at all", StandardCharsets.UTF_8); //$NON-NLS-1$

        try
        {
            DiagnosticsBaselineStore.load(root, "Project"); //$NON-NLS-1$
            fail("a damaged file must refuse"); //$NON-NLS-1$
        }
        catch (UnreadableBaselineException refused)
        {
            assertNotNull(refused.getMessage());
        }
    }

    /**
     * A file with no objects array refuses rather than reading as a whole-project baseline: a
     * missing scope is damage, and reading it as "everything" would compare findings the record
     * never meant to hold.
     */
    @Test
    public void aFileWithNoObjectsArrayRefuses() throws IOException
    {
        Files.writeString(DiagnosticsBaselineStore.fileFor(root, "Project"), //$NON-NLS-1$
            "{\"formatVersion\":" + DiagnosticsBaselineStore.FORMAT_VERSION //$NON-NLS-1$
                + ",\"savedAt\":\"2026-01-01T00:00:00Z\",\"diagnostics\":[]}", //$NON-NLS-1$
            StandardCharsets.UTF_8);

        try
        {
            DiagnosticsBaselineStore.load(root, "Project"); //$NON-NLS-1$
            fail("a file with no objects array must refuse"); //$NON-NLS-1$
        }
        catch (UnreadableBaselineException refused)
        {
            assertTrue(refused.getMessage(), refused.getMessage().contains("objects")); //$NON-NLS-1$
        }
    }

    /**
     * An objects field that is not an array refuses too, for the same reason: the scope decides
     * what a comparison may subtract from, and a scope nobody can read is a file nobody can use.
     */
    @Test
    public void anObjectsFieldThatIsNotAnArrayRefuses() throws IOException
    {
        Files.writeString(DiagnosticsBaselineStore.fileFor(root, "Project"), //$NON-NLS-1$
            "{\"formatVersion\":" + DiagnosticsBaselineStore.FORMAT_VERSION //$NON-NLS-1$
                + ",\"savedAt\":\"2026-01-01T00:00:00Z\",\"objects\":\"all\",\"diagnostics\":[]}", //$NON-NLS-1$
            StandardCharsets.UTF_8);

        try
        {
            DiagnosticsBaselineStore.load(root, "Project"); //$NON-NLS-1$
            fail("an objects field that is not an array must refuse"); //$NON-NLS-1$
        }
        catch (UnreadableBaselineException refused)
        {
            assertTrue(refused.getMessage(), refused.getMessage().contains("objects")); //$NON-NLS-1$
        }
    }

    /**
     * An object entry that is not a string refuses: half a scope is a scope that answers
     * questions its own file cannot have meant.
     */
    @Test
    public void anObjectEntryThatIsNotAStringRefuses() throws IOException
    {
        Files.writeString(DiagnosticsBaselineStore.fileFor(root, "Project"), //$NON-NLS-1$
            "{\"formatVersion\":" + DiagnosticsBaselineStore.FORMAT_VERSION //$NON-NLS-1$
                + ",\"savedAt\":\"2026-01-01T00:00:00Z\",\"objects\":[7],\"diagnostics\":[]}", //$NON-NLS-1$
            StandardCharsets.UTF_8);

        try
        {
            DiagnosticsBaselineStore.load(root, "Project"); //$NON-NLS-1$
            fail("an object entry that is not a string must refuse"); //$NON-NLS-1$
        }
        catch (UnreadableBaselineException refused)
        {
            assertNotNull(refused.getMessage());
        }
    }

    /**
     * Two projects whose names differ only in characters a file name cannot hold keep two files.
     */
    @Test
    public void namesDifferingOnlyInForbiddenCharactersDoNotShareAFile() throws IOException
    {
        String left = "Конфигурация:Основная"; //$NON-NLS-1$
        String right = "Конфигурация;Основная"; //$NON-NLS-1$

        assertNotEquals(DiagnosticsBaselineStore.fileNameFor(left),
            DiagnosticsBaselineStore.fileNameFor(right));

        DiagnosticsBaselineStore.save(root, left, Collections.emptyList(),
            Collections.singletonList(finding("Catalog.Left", "left"))); //$NON-NLS-1$ //$NON-NLS-2$
        DiagnosticsBaselineStore.save(root, right, Collections.emptyList(),
            Collections.singletonList(finding("Catalog.Right", "right"))); //$NON-NLS-1$ //$NON-NLS-2$

        try (var files = Files.list(root))
        {
            assertEquals(2, files.count());
        }
        assertEquals("left", DiagnosticsBaselineStore.load(root, left).diagnostics.get(0).message); //$NON-NLS-1$
        assertEquals("right", DiagnosticsBaselineStore.load(root, right).diagnostics.get(0).message); //$NON-NLS-1$
    }

    /**
     * Clear answers whether a baseline was there, and after it there is none.
     */
    @Test
    public void clearAnswersWhetherThereWasOne() throws IOException
    {
        DiagnosticsBaselineStore.save(root, "Project", Collections.emptyList(), //$NON-NLS-1$
            Collections.singletonList(finding("Catalog.Products", "Comment is too long"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(DiagnosticsBaselineStore.clear(root, "Project")); //$NON-NLS-1$
        assertNull(DiagnosticsBaselineStore.load(root, "Project")); //$NON-NLS-1$
        assertFalse(DiagnosticsBaselineStore.clear(root, "Project")); //$NON-NLS-1$
    }

    /**
     * Parallel saves, clears and reads of one project leave a file that is either readable or
     * absent, and nobody sees an exception.
     * <p>
     * The readers may meet any state the writers leave - a baseline, no baseline - but never a
     * half-written file: the write goes to a temporary file and moves into place, and the
     * per-project lock keeps the read-modify-decide sequences from interleaving.
     * </p>
     */
    @Test
    public void parallelWritersLeaveAReadableFileOrNone() throws Exception
    {
        int writers = 4;
        int rounds = 25;
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < writers; t++)
        {
            final int seed = t;
            threads.add(new Thread(() -> {
                try
                {
                    start.await();
                    for (int i = 0; i < rounds; i++)
                    {
                        int choice = (seed + i) % 3;
                        if (choice == 0)
                        {
                            DiagnosticsBaselineStore.save(root, "Project", Collections.emptyList(), //$NON-NLS-1$
                                Collections.singletonList(finding("Catalog.Products", "round " + i))); //$NON-NLS-1$ //$NON-NLS-2$
                        }
                        else if (choice == 1)
                        {
                            DiagnosticsBaselineStore.clear(root, "Project"); //$NON-NLS-1$
                        }
                        else
                        {
                            DiagnosticsBaselineStore.load(root, "Project"); //$NON-NLS-1$
                        }
                    }
                }
                catch (Exception unexpected)
                {
                    failures.incrementAndGet();
                }
            }, "baseline-writer-" + t)); //$NON-NLS-1$
        }
        for (Thread thread : threads)
        {
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads)
        {
            thread.join(60000);
            assertFalse("a writer did not finish", thread.isAlive()); //$NON-NLS-1$
        }
        assertEquals(0, failures.get());

        Path file = DiagnosticsBaselineStore.fileFor(root, "Project"); //$NON-NLS-1$
        if (Files.isRegularFile(file))
        {
            assertNotNull(DiagnosticsBaselineStore.load(root, "Project")); //$NON-NLS-1$
        }
        else
        {
            assertNull(DiagnosticsBaselineStore.load(root, "Project")); //$NON-NLS-1$
        }
    }
}
