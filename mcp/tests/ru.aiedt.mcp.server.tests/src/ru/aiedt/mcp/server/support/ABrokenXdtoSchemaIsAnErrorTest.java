/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A schema file that is present but does not parse is a load error. Absent and empty stay the other
 * answer, so a missing file is not reported as a broken one.
 */
public class ABrokenXdtoSchemaIsAnErrorTest
{
    private Path root;

    /** A throwaway directory for schema files. */
    @Before
    public void aRoot() throws Exception
    {
        root = Files.createTempDirectory("xdto-schema-"); //$NON-NLS-1$
    }

    /** Removes the throwaway directory. */
    @After
    public void theRootGoes() throws Exception
    {
        if (root == null)
        {
            return;
        }
        try (var walk = Files.walk(root))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try
                {
                    Files.deleteIfExists(path);
                }
                catch (java.io.IOException ioe)
                {
                    throw new IllegalStateException(ioe);
                }
            });
        }
    }

    /** Bytes that are not a package come back with the load failure, not as an absent file. */
    @Test
    public void aFileThatDoesNotParseNamesTheLoadFailure() throws Exception
    {
        Path file = root.resolve("Package.xdto"); //$NON-NLS-1$
        Files.writeString(file, "this is not a package"); //$NON-NLS-1$

        BmXdtoHelper.SchemaRead read = BmXdtoHelper.readSchemaFile(file);

        assertFalse(read.absentOrEmpty());
        assertNull(read.model());
        assertNotNull(read.loadError());
        assertTrue(read.loadError(), read.loadError().contains("Failed to load") //$NON-NLS-1$
            || read.loadError().contains("not an XDTO Package")); //$NON-NLS-1$
    }

    /** An empty file is absent, not a load failure. */
    @Test
    public void anEmptyFileIsAbsent() throws Exception
    {
        Path file = root.resolve("Package.xdto"); //$NON-NLS-1$
        Files.write(file, new byte[0]);

        BmXdtoHelper.SchemaRead read = BmXdtoHelper.readSchemaFile(file);

        assertTrue(read.absentOrEmpty());
        assertNull(read.loadError());
        assertNull(read.model());
    }

    /** A path that names nothing is absent. */
    @Test
    public void aMissingFileIsAbsent()
    {
        BmXdtoHelper.SchemaRead read = BmXdtoHelper.readSchemaFile(root.resolve("Package.xdto")); //$NON-NLS-1$

        assertTrue(read.absentOrEmpty());
        assertNull(read.loadError());
        assertNull(read.model());
    }
}
