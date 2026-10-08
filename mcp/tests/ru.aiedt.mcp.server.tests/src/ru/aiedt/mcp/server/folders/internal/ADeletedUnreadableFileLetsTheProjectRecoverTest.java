/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.internal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.folders.ClusterWorkspaceProbe;

/**
 * A project whose unreadable clusters file went away recovers to an empty storage.
 * <p>
 * The unreadable file made the load fail, and the failed load kept refusing every write until
 * the cache was dropped. The user deleting the bad file produced a change notification that
 * read as the project's own untouched state - nothing was read, and no file was there - so the
 * failed load was never dropped and every write stayed refused. Unknown bytes and a read
 * absence are different states now: the deletion reloads, and the project writes again.
 * </p>
 */
public class ADeletedUnreadableFileLetsTheProjectRecoverTest
{
    private ClusterWorkspaceProbe probe;

    private ClusterManagerImpl manager;

    /**
     * Opens a project whose clusters file no loader can read.
     *
     * @throws Exception when the project cannot be created
     */
    @Before
    public void aProjectWithAnUnreadableClustersFile() throws Exception
    {
        probe = ClusterWorkspaceProbe.open("AiEdtClusterDeletedBad"); //$NON-NLS-1$
        manager = new ClusterManagerImpl();
        probe.writeClusters(new byte[] {(byte)0xC3, (byte)0x28});
    }

    /**
     * Deletes the project.
     *
     * @throws Exception when the project cannot be deleted
     */
    @After
    public void theProjectGoes() throws Exception
    {
        if (probe != null)
        {
            probe.close();
        }
    }

    /**
     * After the bad file is deleted and the change arrives, a write works again instead of
     * refusing with the failed load.
     *
     * @throws Exception when the file cannot be deleted
     */
    @Test
    public void deletingTheBadFileEndsTheRefusals() throws Exception
    {
        assertFalse("the unreadable file refuses writes", //$NON-NLS-1$
            manager.createCluster(probe.project, "Shelf", "Catalogs", null).succeeded()); //$NON-NLS-1$ //$NON-NLS-2$

        Files.deleteIfExists(probe.clustersFile());
        probe.project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        manager.clustersFileTouched(probe.project);

        assertTrue("the project is back to an empty storage and writes again", //$NON-NLS-1$
            manager.createCluster(probe.project, "Shelf", "Catalogs", null).succeeded()); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
