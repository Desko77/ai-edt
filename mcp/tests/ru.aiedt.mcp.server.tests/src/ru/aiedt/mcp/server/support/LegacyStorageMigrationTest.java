/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers the states a settings folder can be in when a project moves onto the current file names:
 * only the old file, only the new one, both, neither, and the case that made the fallback approach
 * unsafe - a set emptied to the point where the current file is deleted.
 */
public class LegacyStorageMigrationTest
{
    private static final String LEGACY = "old-name.yaml"; //$NON-NLS-1$
    private static final String CURRENT = "new-name.yaml"; //$NON-NLS-1$

    private Path workDir;

    @Before
    public void createWorkDir() throws IOException
    {
        workDir = Files.createTempDirectory("aiedt-migration-test"); //$NON-NLS-1$
    }

    @After
    public void removeWorkDir() throws IOException
    {
        if (workDir == null || !Files.exists(workDir))
        {
            return;
        }
        try (java.util.stream.Stream<Path> entries = Files.list(workDir))
        {
            for (Path entry : entries.toArray(Path[]::new))
            {
                Files.deleteIfExists(entry);
            }
        }
        Files.deleteIfExists(workDir);
    }

    private Path settings()
    {
        return workDir;
    }

    private void write(String name, String body) throws IOException
    {
        Files.write(settings().resolve(name), body.getBytes(StandardCharsets.UTF_8));
    }

    private String read(String name) throws IOException
    {
        return new String(Files.readAllBytes(settings().resolve(name)), StandardCharsets.UTF_8);
    }

    @Test
    public void legacyOnlyIsCarriedOverAndRenamedAside() throws IOException
    {
        write(LEGACY, "clusters: [one]"); //$NON-NLS-1$

        assertTrue(LegacyStorageMigration.carryOver(settings(), LEGACY, CURRENT));

        assertEquals("clusters: [one]", read(CURRENT)); //$NON-NLS-1$
        assertFalse("the old file must not stay live", //$NON-NLS-1$
            Files.exists(settings().resolve(LEGACY)));
        assertTrue(Files.exists(settings().resolve(LEGACY + LegacyStorageMigration.MIGRATED_SUFFIX)));
    }

    @Test
    public void currentFileWinsAndLegacyIsLeftAlone() throws IOException
    {
        write(CURRENT, "clusters: [current]"); //$NON-NLS-1$
        write(LEGACY, "clusters: [stale]"); //$NON-NLS-1$

        assertFalse(LegacyStorageMigration.carryOver(settings(), LEGACY, CURRENT));

        assertEquals("clusters: [current]", read(CURRENT)); //$NON-NLS-1$
        assertEquals("clusters: [stale]", read(LEGACY)); //$NON-NLS-1$
    }

    @Test
    public void nothingToCarryIsNotAnError() throws IOException
    {
        assertFalse(LegacyStorageMigration.carryOver(settings(), LEGACY, CURRENT));
        assertFalse(Files.exists(settings().resolve(CURRENT)));
    }

    @Test
    public void missingFolderIsIgnored() throws IOException
    {
        assertFalse(LegacyStorageMigration.carryOver(
            workDir.resolve("no-such-folder"), LEGACY, CURRENT)); //$NON-NLS-1$
    }

    @Test
    public void repeatedCallsAreIdempotent() throws IOException
    {
        write(LEGACY, "clusters: [one]"); //$NON-NLS-1$

        assertTrue(LegacyStorageMigration.carryOver(settings(), LEGACY, CURRENT));
        assertFalse("a second pass has nothing left to move", //$NON-NLS-1$
            LegacyStorageMigration.carryOver(settings(), LEGACY, CURRENT));
        assertEquals("clusters: [one]", read(CURRENT)); //$NON-NLS-1$
    }

    @Test
    public void emptiedStoreDoesNotComeBackFromTheOldFile() throws IOException
    {
        write(LEGACY, "clusters: [one]"); //$NON-NLS-1$
        LegacyStorageMigration.carryOver(settings(), LEGACY, CURRENT);

        // The cluster store deletes its file once the last entry is gone. With the old file still
        // live, the next start would read the deleted entries straight back in.
        Files.delete(settings().resolve(CURRENT));

        assertFalse(LegacyStorageMigration.carryOver(settings(), LEGACY, CURRENT));
        assertFalse("deleted entries must stay deleted", //$NON-NLS-1$
            Files.exists(settings().resolve(CURRENT)));
    }
}
