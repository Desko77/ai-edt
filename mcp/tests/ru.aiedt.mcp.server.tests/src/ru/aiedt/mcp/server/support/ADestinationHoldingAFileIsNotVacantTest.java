/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A regular file sitting at an export's destination is not a vacant destination.
 * <p>
 * Vacancy used to be read as "the path holds no directory entries", which a regular file
 * satisfies: the check let the export through, the infobase was released and reconnected
 * around the Designer run, and only the final move bounced off the file - the most expensive
 * possible moment to find out. A file there is a refusal now, before anything is released.
 * </p>
 */
public class ADestinationHoldingAFileIsNotVacantTest
{
    private Path dir;

    /**
     * Lays out the directory the probes sit in.
     *
     * @throws IOException when it cannot be created
     */
    @Before
    public void aScratchDirectory() throws IOException
    {
        dir = Files.createTempDirectory("aiedt-destination-vacant"); //$NON-NLS-1$
    }

    /**
     * Clears the directory.
     *
     * @throws IOException when it cannot be cleared
     */
    @After
    public void theScratchDirectoryGoes() throws IOException
    {
        try (java.util.stream.Stream<Path> entries = Files.list(dir))
        {
            for (Path entry : entries.toArray(Path[]::new))
            {
                Files.deleteIfExists(entry);
            }
        }
        Files.deleteIfExists(dir);
    }

    /** A regular file at the destination is not vacant. */
    @Test
    public void aFileAtTheDestinationIsNotVacant() throws IOException
    {
        Path file = dir.resolve("an-artifact"); //$NON-NLS-1$
        Files.write(file, new byte[] { 1 });

        assertFalse(theEnvironment().destinationVacant(file));
    }

    /** A path that is not there at all is vacant. */
    @Test
    public void anAbsentDestinationIsVacant()
    {
        assertTrue(theEnvironment().destinationVacant(dir.resolve("not-there"))); //$NON-NLS-1$
    }

    /** An empty directory is vacant, and a non-empty one is not. */
    @Test
    public void anEmptyDirectoryIsVacantAndAFullOneIsNot() throws IOException
    {
        assertTrue(theEnvironment().destinationVacant(dir));

        Path full = dir.resolve("full"); //$NON-NLS-1$
        Files.createDirectories(full);
        Files.write(full.resolve("object.xml"), new byte[] { 1 }); //$NON-NLS-1$
        assertFalse(theEnvironment().destinationVacant(full));
    }

    /**
     * The production environment, on a context that touches no platform service for these
     * checks.
     *
     * @return the environment the export reads vacancy through
     */
    private static InfobaseObjectsExporter.EdtIo theEnvironment()
    {
        return new InfobaseObjectsExporter.EdtIo(new ThickClientLaunch.LauncherContext(), null,
            "aiedt-destination-vacant-probe", new InfobaseObjectsExporter.LiveRun(), null); //$NON-NLS-1$
    }
}
