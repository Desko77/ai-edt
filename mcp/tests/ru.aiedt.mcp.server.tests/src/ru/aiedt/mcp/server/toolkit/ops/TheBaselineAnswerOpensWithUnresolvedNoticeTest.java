/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.support.DiagnosticsBaselineStore;
import ru.aiedt.mcp.server.support.DiagnosticsBaselineStore.Diagnostic;

/**
 * A since-baseline answer opens with the notice about requested addresses the model does not
 * hold, exactly as the plain error report does.
 * <p>
 * Without the notice, a mistyped FQN narrows the comparison to nothing, an empty diff reads as
 * "nothing new since the baseline", and the caller never learns the address matched no object -
 * the same fault the plain report cured by naming the addresses it could not resolve.
 * </p>
 */
public class TheBaselineAnswerOpensWithUnresolvedNoticeTest
{
    private Path root;

    @Before
    public void makeRoot() throws IOException
    {
        root = Files.createTempDirectory("baseline-unresolved-test"); //$NON-NLS-1$
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
     * The notice stands in front of the answer, whatever the comparison found: both an empty
     * diff and a listing open with it, because both are read as a claim about the named objects.
     */
    @Test
    public void theNoticeStandsInFrontOfAnEmptyDiff() throws IOException
    {
        Diagnostic standing = new Diagnostic("Catalog.Products", "bsl-comment-length", //$NON-NLS-1$ //$NON-NLS-2$
            "Comment is too long", "ERRORS"); //$NON-NLS-1$ //$NON-NLS-2$
        DiagnosticsBaselineStore.save(root, "Project", Collections.emptyList(), //$NON-NLS-1$
            Collections.singletonList(standing));
        String unresolved = ProjectProblemsReader
            .describeMissing(List.of("Catalog.Ghost")); //$NON-NLS-1$

        String answer = ProjectProblemsReader.renderSinceBaseline(root, "Project", //$NON-NLS-1$ //$NON-NLS-2$
            Collections.singletonList(standing), 1, unresolved, null, null, null,
            new HashSet<>(), false, new HashSet<>(), 100, 5000);

        assertTrue(answer, answer.startsWith("# Addresses Not In The Model")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("Catalog.Ghost")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("# Nothing New Since Baseline")); //$NON-NLS-1$
    }

    /**
     * A diff with findings opens with the notice too - the headings of the plain report and of
     * the comparison sit in the same order.
     */
    @Test
    public void theNoticeStandsInFrontOfAListing() throws IOException
    {
        DiagnosticsBaselineStore.save(root, "Project", Collections.emptyList(), //$NON-NLS-1$
            Collections.emptyList());
        Diagnostic added = new Diagnostic("Catalog.Products", "bsl-empty-block", "Empty block", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "ERRORS"); //$NON-NLS-1$
        String unresolved = ProjectProblemsReader
            .describeMissing(List.of("Catalog.Ghost")); //$NON-NLS-1$

        String answer = ProjectProblemsReader.renderSinceBaseline(root, "Project", //$NON-NLS-1$ //$NON-NLS-2$
            Collections.singletonList(added), 1, unresolved, null, null, null, new HashSet<>(),
            false, new HashSet<>(), 100, 5000);

        assertTrue(answer, answer.startsWith("# Addresses Not In The Model")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("# Configuration Issues (since baseline)")); //$NON-NLS-1$
    }
}
