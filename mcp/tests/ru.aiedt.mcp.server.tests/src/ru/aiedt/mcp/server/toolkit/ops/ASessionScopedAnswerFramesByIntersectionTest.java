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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.support.DiagnosticsBaselineStore;
import ru.aiedt.mcp.server.support.DiagnosticsBaselineStore.Diagnostic;

/**
 * A session-scoped comparison frames the baseline by the intersection of its object set and its
 * session set, not by their union.
 * <p>
 * The marker filter on the reader's side lets a marker through only when it matches both sets.
 * Framing the baseline with the union instead held records that fit just one of the two, and
 * every such record came back resolved - a narrowing the caller asked for read as findings the
 * caller had cured.
 * </p>
 */
public class ASessionScopedAnswerFramesByIntersectionTest
{
    private Path root;

    @Before
    public void makeRoot() throws IOException
    {
        root = Files.createTempDirectory("baseline-intersection-test"); //$NON-NLS-1$
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
     * Baseline records that fit only one of the two sets are not resolved: the intersection
     * frame drops them before the subtraction, so an empty current side over an empty frame
     * answers zero resolved.
     */
    @Test
    public void recordsFittingOnlyOneSetAreNotResolved() throws IOException
    {
        Diagnostic objectOnly = new Diagnostic("Catalog.Products", "bsl-comment-length", //$NON-NLS-1$ //$NON-NLS-2$
            "Comment is too long", "ERRORS"); //$NON-NLS-1$ //$NON-NLS-2$
        Diagnostic sessionOnly = new Diagnostic("Document.Sales", "bsl-comment-length", //$NON-NLS-1$ //$NON-NLS-2$
            "Comment is too long", "ERRORS"); //$NON-NLS-1$ //$NON-NLS-2$
        DiagnosticsBaselineStore.save(root, "Project", Collections.emptyList(), //$NON-NLS-1$
            Arrays.asList(objectOnly, sessionOnly));

        Set<String> objects = new HashSet<>();
        objects.add("catalog.products"); //$NON-NLS-1$
        Set<String> session = new HashSet<>();
        session.add("document.sales"); //$NON-NLS-1$
        // The marker filter intersects the two sets, and the intersection here is empty - so the
        // reader's side of this comparison collected nothing.
        String answer = ProjectProblemsReader.renderSinceBaseline(root, "Project", //$NON-NLS-1$ //$NON-NLS-2$
            Collections.emptyList(), 0, "", null, null, null, objects, true, session, 100, 5000); //$NON-NLS-1$

        assertTrue(answer, answer.contains("# Nothing New Since Baseline")); //$NON-NLS-1$
        assertFalse(answer, answer.contains("resolvedSinceBaseline:** 2")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("**resolvedSinceBaseline:** 0")); //$NON-NLS-1$
    }
}
