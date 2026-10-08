/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Holds a text search match that only exists across a line break to being a hit at all.
 * <p>
 * The whole-text pre-filter let such a file into the scan, and the per-line pass then found
 * nothing in any single line: the file that had promised a match answered with zero hits. The
 * literal two-token retry had the same hole - its flexible-whitespace pattern was made to cross
 * line breaks and then handed to the same per-line pass.
 * </p>
 */
public class ATextMatchAcrossALineBreakIsAHitTest
{
    private static final String PROJECT = "AiEdtTextAcrossLinesProbe"; //$NON-NLS-1$

    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    private static final String MODULE = "CommonModules/Probe/Module.bsl"; //$NON-NLS-1$

    private static final String TEXT = "Процедура Первый()" + CRLF //$NON-NLS-1$
        + "\tВозврат;" + CRLF //$NON-NLS-1$
        + "КонецПроцедуры" + CRLF //$NON-NLS-1$
        + "Функция Вторая() Экспорт" + CRLF //$NON-NLS-1$
        + "\tВозврат 2;" + CRLF //$NON-NLS-1$
        + "КонецФункции" + CRLF; //$NON-NLS-1$

    private static Path root;

    private static IProject project;

    /**
     * Opens a plain project holding one module whose lines are known by number.
     *
     * @throws Exception when the workspace or the files cannot be made
     */
    @BeforeClass
    public static void aProjectWithOneModule() throws Exception
    {
        root = Files.createTempDirectory("aiedt-text-across-lines"); //$NON-NLS-1$
        Path moduleDir = root.resolve(PROJECT).resolve("src/CommonModules/Probe"); //$NON-NLS-1$
        Files.createDirectories(moduleDir);
        Files.write(moduleDir.resolve("Module.bsl"), //$NON-NLS-1$
            ("﻿" + TEXT).getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(
            root.resolve(PROJECT).toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
    }

    /**
     * Removes the project and the directory under it.
     *
     * @throws Exception when the workspace cannot delete the project
     */
    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(false, true, new NullProgressMonitor());
        }
        if (root != null)
        {
            try (var walk = Files.walk(root))
            {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile)
                    .forEach(java.io.File::delete);
            }
            catch (IOException untouched)
            {
                // The temporary directory is the operating system's to clean.
            }
        }
    }

    /** A regex match that spans a line break is a hit on the line it starts on. */
    @Test
    public void aRegexMatchAcrossALineBreakIsAHitOnItsStartLine()
    {
        String found = new CodeTextSearcher().execute(args("projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$
            "query", "КонецПроцедуры\\s+Функция", "isRegex", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertTrue(found, found.contains("At line 3:")); //$NON-NLS-1$
        assertTrue(found, found.contains("4: Функция Вторая() Экспорт")); //$NON-NLS-1$
        assertTrue(found, found.contains("**Overall:** 1 hits across 1 modules")); //$NON-NLS-1$
    }

    /** A match across a line break counts in count mode, which has no lines to look at. */
    @Test
    public void aMatchAcrossALineBreakCounts()
    {
        String found = new CodeTextSearcher().execute(args("projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$
            "query", "КонецПроцедуры\\s+Функция", "isRegex", "true", "outputMode", "count")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

        assertTrue(found, found.contains("**Total hits:** 1 across **1** modules")); //$NON-NLS-1$
    }

    /** The flexible-whitespace retry of a literal query crosses line breaks and says so. */
    @Test
    public void theWhitespaceRetryOfALiteralQueryCrossesALineBreak()
    {
        String found = new CodeTextSearcher().execute(args("projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$
            "query", "КонецПроцедуры Функция")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(found, found.contains("_No exact match; retried with flexible whitespace")); //$NON-NLS-1$
        assertTrue(found, found.contains("At line 3:")); //$NON-NLS-1$
    }

    /** A match inside one line keeps the line and the window it always had. */
    @Test
    public void aSingleLineMatchKeepsItsLine()
    {
        String found = new CodeTextSearcher().execute(args("projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$
            "query", "Возврат 2;")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(found, found.contains("At line 5:")); //$NON-NLS-1$
        assertTrue(found, found.contains("4: Функция Вторая() Экспорт")); //$NON-NLS-1$
        assertTrue(found, found.contains("6: КонецФункции")); //$NON-NLS-1$
    }

    private static Map<String, String> args(String... pairs)
    {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
        {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
