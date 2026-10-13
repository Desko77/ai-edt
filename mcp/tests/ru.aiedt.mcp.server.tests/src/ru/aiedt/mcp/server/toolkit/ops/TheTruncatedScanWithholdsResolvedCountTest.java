/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.support.DiagnosticsBaselineStore;
import ru.aiedt.mcp.server.support.DiagnosticsBaselineStore.Diagnostic;

/**
 * What a since-baseline answer says when the scan behind it reached its collection cap.
 * <p>
 * A truncated read saw only part of what stands, and a resolved count over it would name
 * resolved the findings the scan never saw. The answer withholds the count and says why, rather
 * than presenting a number that only looks like one; a complete read answers with the number.
 * A save whose collection reached the cap refuses instead of writing a partial baseline.
 * </p>
 */
public class TheTruncatedScanWithholdsResolvedCountTest
{
    private Path root;

    @Before
    public void makeRoot() throws IOException
    {
        root = Files.createTempDirectory("baseline-truncated-test"); //$NON-NLS-1$
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
     * A read that reached its cap carries no resolved count, and says why it cannot have one.
     */
    @Test
    public void aTruncatedScanCarriesNoResolvedCount() throws IOException
    {
        Diagnostic standing = finding("Catalog.Products", "Comment is too long"); //$NON-NLS-1$ //$NON-NLS-2$
        DiagnosticsBaselineStore.save(root, "Project", Collections.emptyList(), //$NON-NLS-1$
            Collections.singletonList(standing));

        String answer = ProjectProblemsReader.renderSinceBaseline(root, "Project", //$NON-NLS-1$ //$NON-NLS-2$
            Collections.singletonList(standing), 5000, null, null, null, new HashSet<>(),
            false, new HashSet<>(), 100, 5000);

        assertFalse(answer, answer.contains("resolvedSinceBaseline")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("cap of 5000")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("not determined")); //$NON-NLS-1$
    }

    /**
     * A read that stopped under its cap answers with the resolved count as a number.
     */
    @Test
    public void aCompleteReadAnswersWithTheResolvedCount() throws IOException
    {
        Diagnostic fixed = finding("Catalog.Products", "Comment is too long"); //$NON-NLS-1$ //$NON-NLS-2$
        DiagnosticsBaselineStore.save(root, "Project", Collections.emptyList(), //$NON-NLS-1$
            Collections.singletonList(fixed));

        String answer = ProjectProblemsReader.renderSinceBaseline(root, "Project", //$NON-NLS-1$ //$NON-NLS-2$
            Collections.emptyList(), 0, null, null, null, new HashSet<>(), false,
            new HashSet<>(), 100, 5000);

        assertTrue(answer, answer.contains("**resolvedSinceBaseline:** 1")); //$NON-NLS-1$
    }

    /**
     * A capped save refuses rather than writing a partial baseline, and names the narrowing that
     * fits under the cap.
     */
    @Test
    public void aCappedSaveRefusesInsteadOfWriting()
    {
        String refusal = ProjectProblemsReader.cappedBaselineRefusal(5000);

        assertTrue(refusal, refusal.contains("5000")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("was not written")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("objects=")); //$NON-NLS-1$
    }
}
