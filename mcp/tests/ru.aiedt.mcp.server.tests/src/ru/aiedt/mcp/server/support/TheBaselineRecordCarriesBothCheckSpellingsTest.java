/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.support.DiagnosticsBaselineStore.Baseline;
import ru.aiedt.mcp.server.support.DiagnosticsBaselineStore.Diagnostic;
import ru.aiedt.mcp.server.support.DiagnosticsBaselineStore.UnreadableBaselineException;

/**
 * A baseline record carries the check under both of its spellings - the symbolic id and the
 * marker's short code.
 * <p>
 * The reader's own check filter matches a row by either spelling, so a query that names the
 * short code a report once printed keeps the current marker and, before this, lost the baseline
 * record of the same finding: the frame knew one spelling only and the finding came back new.
 * </p>
 */
public class TheBaselineRecordCarriesBothCheckSpellingsTest
{
    private Path root;

    @Before
    public void makeRoot() throws IOException
    {
        root = Files.createTempDirectory("baseline-check-code-test"); //$NON-NLS-1$
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

    /**
     * The short code reads back beside the symbolic id it was saved with.
     */
    @Test
    public void theShortCodeReadsBack() throws IOException
    {
        Diagnostic recorded = new Diagnostic("Catalog.Products", "common-module-type", "a1b2c3", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Comment is too long", "ERRORS"); //$NON-NLS-1$ //$NON-NLS-2$
        DiagnosticsBaselineStore.save(root, "Project", Collections.emptyList(), //$NON-NLS-1$
            Collections.singletonList(recorded));

        Baseline read = DiagnosticsBaselineStore.load(root, "Project"); //$NON-NLS-1$

        assertEquals(1, read.diagnostics.size());
        assertEquals("common-module-type", read.diagnostics.get(0).checkId); //$NON-NLS-1$
        assertEquals("a1b2c3", read.diagnostics.get(0).checkCode); //$NON-NLS-1$
    }

    /**
     * A query naming the short code meets the record: the frame matches either spelling, the way
     * the reader's own check filter matches a row.
     */
    @Test
    public void theFrameMatchesTheShortCode()
    {
        Diagnostic recorded = new Diagnostic("Catalog.Products", "common-module-type", "a1b2c3", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Comment is too long", "ERRORS"); //$NON-NLS-1$ //$NON-NLS-2$

        List<Diagnostic> byShort = DiagnosticsBaselineStore.frame(
            Collections.singletonList(recorded), null, "a1b2c3", null, null); //$NON-NLS-1$
        List<Diagnostic> bySymbolic = DiagnosticsBaselineStore.frame(
            Collections.singletonList(recorded), null, "common-module-type", null, null); //$NON-NLS-1$

        assertEquals(1, byShort.size());
        assertEquals(1, bySymbolic.size());
    }

    /**
     * A file of the previous format version refuses instead of reading as a baseline whose
     * records carry no short code.
     */
    @Test
    public void aFileOfThePreviousFormatRefuses() throws IOException
    {
        Files.writeString(DiagnosticsBaselineStore.fileFor(root, "Project"), //$NON-NLS-1$
            "{\"formatVersion\":1,\"savedAt\":\"2026-01-01T00:00:00Z\",\"objects\":[]," //$NON-NLS-1$
                + "\"diagnostics\":[{\"object\":\"Catalog.Products\",\"check\":\"bsl-comment-length\"," //$NON-NLS-1$
                + "\"message\":\"Comment is too long\",\"severity\":\"ERRORS\",\"count\":1}]}", //$NON-NLS-1$
            StandardCharsets.UTF_8);

        try
        {
            DiagnosticsBaselineStore.load(root, "Project"); //$NON-NLS-1$
            fail("a file of the previous format version must refuse"); //$NON-NLS-1$
        }
        catch (UnreadableBaselineException refused)
        {
            assertTrue(refused.getMessage(), refused.getMessage().contains("format version 1")); //$NON-NLS-1$
        }
    }
}
