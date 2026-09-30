/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * {@code dcs_search} reads each schema as a whole: a query spanning lines - a query text fragment
 * with its line break - is a hit on the line it starts on, while a query on one line is counted
 * per line as before. A schema that cannot be read is named in the answer rather than counted as
 * searched.
 */
public class ADcsSearchReadsTheWholeSchemaTest
{
    private static final String PROJECT = "AiEdtDcsSearchProbe"; //$NON-NLS-1$

    private static final String PATH = "Reports/Sales/Templates/Main/Template.dcs"; //$NON-NLS-1$

    private static final String BROKEN = "Reports/Broken/Templates/Main/Template.dcs"; //$NON-NLS-1$

    private static final String SCHEMA = "<dataSet>\r\n" //$NON-NLS-1$
        + "<query>ВЫБРАТЬ\r\n" //$NON-NLS-1$
        + "    Товары.Ссылка\r\n" //$NON-NLS-1$
        + "ИЗ Справочник.Товары КАК Товары</query>\r\n" //$NON-NLS-1$
        + "</dataSet>\r\n"; //$NON-NLS-1$

    private static IProject project;

    /**
     * A walk whose reads of one schema fail.
     */
    private static final class OneUnreadable extends DcsSearchTool.Collector
    {
        /**
         * @param query the plain query
         */
        OneUnreadable(String query)
        {
            super(DcsSearchTool.compile(query, false, false), null, 100);
        }

        @Override
        String readText(IFile file) throws Exception
        {
            if (file.getProjectRelativePath().toString().contains("/Broken/")) //$NON-NLS-1$
            {
                throw new java.io.IOException("locked by another process"); //$NON-NLS-1$
            }
            return super.readText(file);
        }
    }

    /**
     * Opens a project with two schemas under {@code src/}.
     *
     * @throws Exception when the workspace cannot create the project or its files
     */
    @BeforeClass
    public static void aProjectWithTwoSchemas() throws Exception
    {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT);
        if (project.exists())
        {
            project.delete(true, true, new NullProgressMonitor());
        }
        project.create(new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        write("src/" + PATH, SCHEMA); //$NON-NLS-1$
        write("src/" + BROKEN, SCHEMA); //$NON-NLS-1$
    }

    /**
     * Removes the project.
     *
     * @throws Exception when the workspace cannot delete the project
     */
    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(true, true, new NullProgressMonitor());
        }
    }

    /**
     * Writes a file, creating the folders above it.
     *
     * @param path the project-relative path
     * @param text the file text
     * @throws Exception when the workspace refuses a folder or the file
     */
    private static void write(String path, String text) throws Exception
    {
        String[] segments = path.split("/"); //$NON-NLS-1$
        IContainer parent = project;
        for (int i = 0; i < segments.length - 1; i++)
        {
            IFolder folder = parent.getFolder(new org.eclipse.core.runtime.Path(segments[i]));
            if (!folder.exists())
            {
                folder.create(true, true, new NullProgressMonitor());
            }
            parent = folder;
        }
        IFile file = parent.getFile(new org.eclipse.core.runtime.Path(segments[segments.length - 1]));
        file.create(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), true,
            new NullProgressMonitor());
    }

    /**
     * Searches the schema text for a query.
     *
     * @param query the query
     * @param isRegex whether it is a regular expression
     * @return the finished search
     */
    private static DcsSearchTool.Collector search(String query, boolean isRegex)
    {
        DcsSearchTool.Collector collector =
            new DcsSearchTool.Collector(DcsSearchTool.compile(query, isRegex, false), null, 100);
        collector.search(SCHEMA, PATH);
        return collector;
    }

    /** A query spanning a line break is a hit on the line it starts on. */
    @Test
    public void aQueryAcrossLinesIsAHitOnItsFirstLine()
    {
        DcsSearchTool.Collector c = search("ВЫБРАТЬ\n    Товары.Ссылка", false); //$NON-NLS-1$
        assertEquals(1, c.totalMatches);
        List<DcsSearchTool.Hit> hits = c.matchesByFile.get(PATH);
        assertEquals(2, hits.get(0).line);
        assertEquals(3, hits.get(0).endLine);
        assertTrue(hits.get(0).text, hits.get(0).text.contains("Товары.Ссылка")); //$NON-NLS-1$
    }

    /** A query on one line is counted once per line, whatever it matches on that line. */
    @Test
    public void aQueryOnOneLineIsCountedPerLine()
    {
        DcsSearchTool.Collector c = search("товары", false); //$NON-NLS-1$
        assertEquals(2, c.totalMatches);
        List<DcsSearchTool.Hit> hits = c.matchesByFile.get(PATH);
        assertEquals(3, hits.get(0).line);
        assertEquals(4, hits.get(1).line);
        assertEquals(4, hits.get(1).endLine);
    }

    /** Anchors still read lines. */
    @Test
    public void anchorsStillReadLines()
    {
        DcsSearchTool.Collector c = search("^</dataSet>$", true); //$NON-NLS-1$
        assertEquals(1, c.totalMatches);
        assertEquals(5, c.matchesByFile.get(PATH).get(0).line);
    }

    /** A schema that could not be read is named, and is not counted as searched. */
    @Test
    public void anUnreadableSchemaIsNamed() throws Exception
    {
        OneUnreadable walk = new OneUnreadable("Товары.Ссылка"); //$NON-NLS-1$
        project.getFolder("src").accept(walk); //$NON-NLS-1$
        assertEquals(1, walk.scannedFiles);
        assertEquals(1, walk.unreadableFiles);
        assertEquals(1, walk.unreadable.size());
        assertTrue(walk.unreadable.get(0), walk.unreadable.get(0).startsWith(BROKEN));
        assertTrue(walk.unreadable.get(0), walk.unreadable.get(0).contains("locked")); //$NON-NLS-1$
        String answer = new DcsSearchTool().format("Товары.Ссылка", walk); //$NON-NLS-1$
        assertTrue(answer, answer.contains("**Unreadable:** 1")); //$NON-NLS-1$
        assertTrue(answer, answer.contains(BROKEN));
    }
}
