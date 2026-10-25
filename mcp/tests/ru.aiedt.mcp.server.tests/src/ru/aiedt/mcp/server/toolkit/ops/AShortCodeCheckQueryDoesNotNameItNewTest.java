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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.support.DiagnosticsBaselineStore;
import ru.aiedt.mcp.server.support.DiagnosticsBaselineStore.Diagnostic;

/**
 * A since-baseline comparison whose check filter names the short code of a check does not name
 * that finding new.
 * <p>
 * The reader's own check filter matches a marker by either spelling, so the current side keeps
 * the finding. Before the record carried the short code beside the symbolic id, the frame knew
 * one spelling only, dropped the record, and the same finding came back both new and resolved.
 * </p>
 */
public class AShortCodeCheckQueryDoesNotNameItNewTest
{
    private Path root;

    @Before
    public void makeRoot() throws IOException
    {
        root = Files.createTempDirectory("baseline-short-code-test"); //$NON-NLS-1$
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
     * The same finding, asked for by its short code, is neither new nor resolved: the baseline
     * record meets the filter through the code it now carries.
     */
    @Test
    public void theSameFindingAskedByShortCodeIsNotNew() throws IOException
    {
        Diagnostic standing = new Diagnostic("Catalog.Products", "common-module-type", "a1b2c3", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Comment is too long", "ERRORS"); //$NON-NLS-1$ //$NON-NLS-2$
        DiagnosticsBaselineStore.save(root, "Project", Collections.emptyList(), //$NON-NLS-1$
            Collections.singletonList(standing));

        String answer = ProjectProblemsReader.renderSinceBaseline(root, "Project", //$NON-NLS-1$ //$NON-NLS-2$
            Collections.singletonList(standing), 1, "", null, "a1b2c3", null, new HashSet<>(), //$NON-NLS-1$ //$NON-NLS-2$
            false, new HashSet<>(), 100, 5000);

        assertTrue(answer, answer.contains("# Nothing New Since Baseline")); //$NON-NLS-1$
    }
}
