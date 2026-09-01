/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The converted object's root XML is found in the output, never derived from the binary's name.
 * <p>
 * Measured on a stand: a file named one thing converted to an XML named after the OBJECT it
 * contained. Computing the name from the binary refused a conversion that had succeeded, and the
 * whole import looked broken while the conversion had done its work.
 * </p>
 */
public class TheRootXmlIsFoundNotComputedTest
{
    private Path dir;

    @Before
    public void makeAnOutputDirectory() throws Exception
    {
        dir = Files.createTempDirectory("root-xml-test"); //$NON-NLS-1$
    }

    @After
    public void removeIt() throws Exception
    {
        try (Stream<Path> entries = Files.walk(dir))
        {
            entries.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private Path write(String name) throws Exception
    {
        Path file = dir.resolve(name);
        Files.write(file, "<x/>".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        return file;
    }

    @Test
    public void theSingleXmlIsTakenWhateverItIsCalled() throws Exception
    {
        // The name deliberately has nothing to do with any binary: that is the point.
        Path written = write("TestExtDP_MCP.xml"); //$NON-NLS-1$
        Files.createDirectory(dir.resolve("TestExtDP_MCP")); //$NON-NLS-1$

        BmExternalObjectProjectHelper.RootXml root =
            BmExternalObjectProjectHelper.findRootXml(dir);

        assertNull("a single XML is not a problem", root.problem); //$NON-NLS-1$
        assertEquals(written, root.file);
    }

    @Test
    public void aDirectoryBesideItIsNotMistakenForTheRoot() throws Exception
    {
        write("Объект.xml"); //$NON-NLS-1$
        Files.createDirectory(dir.resolve("Ext.xml")); //$NON-NLS-1$

        BmExternalObjectProjectHelper.RootXml root =
            BmExternalObjectProjectHelper.findRootXml(dir);

        assertNull(root.problem);
        assertEquals("Объект.xml", root.file.getFileName().toString()); //$NON-NLS-1$
    }

    @Test
    public void anEmptyOutputSaysThereIsNothingToImport() throws Exception
    {
        BmExternalObjectProjectHelper.RootXml root =
            BmExternalObjectProjectHelper.findRootXml(dir);

        assertNull(root.file);
        assertNotNull(root.problem);
        assertTrue("the caller has to be told where the conversion traces itself", //$NON-NLS-1$
            root.problem.contains(".metadata/.log")); //$NON-NLS-1$
    }

    @Test
    public void twoCandidatesAreRefusedRatherThanGuessed() throws Exception
    {
        write("Первый.xml"); //$NON-NLS-1$
        write("Второй.xml"); //$NON-NLS-1$

        BmExternalObjectProjectHelper.RootXml root =
            BmExternalObjectProjectHelper.findRootXml(dir);

        assertNull("picking one of two would be a guess", root.file); //$NON-NLS-1$
        assertNotNull(root.problem);
        assertTrue(root.problem.contains("2")); //$NON-NLS-1$
    }

    @Test
    public void anUnreadableOutputIsReportedAndNotTakenForEmpty() throws Exception
    {
        BmExternalObjectProjectHelper.RootXml root =
            BmExternalObjectProjectHelper.findRootXml(dir.resolve("no-such-dir")); //$NON-NLS-1$

        assertNull(root.file);
        assertNotNull("a directory that cannot be read is not an empty one", root.problem); //$NON-NLS-1$
    }
}
