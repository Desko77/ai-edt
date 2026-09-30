/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.folders.ClusterWorkspaceProbe;
import ru.aiedt.mcp.server.folders.ClusterWriteOutcome;
import ru.aiedt.mcp.server.folders.repository.ClusterSaveOutcome;

/**
 * A cluster edit that does not reach the file names why, and a no-op is not a refusal.
 */
public class AClusterWriteNamesTheRefusalTest
{
    private ClusterWorkspaceProbe probe;

    private ClusterManagerImpl manager;

    /**
     * Opens a project and a service backed by the real clusters file.
     *
     * @throws Exception when the project cannot be created
     */
    @Before
    public void aProject() throws Exception
    {
        probe = ClusterWorkspaceProbe.open("AiEdtClusterRefusal"); //$NON-NLS-1$
        manager = new ClusterManagerImpl();
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
     * Creating a cluster at a path that is already taken names that, and does not add another.
     *
     * @throws Exception when the project cannot be inspected
     */
    @Test
    public void creatingAClusterThatAlreadyExistsIsRefused() throws Exception
    {
        assertTrue(manager.createCluster(probe.project, "Shelf", "Catalogs", null).succeeded()); //$NON-NLS-1$ //$NON-NLS-2$

        ClusterWriteOutcome again = manager.createCluster(probe.project, "Shelf", "Catalogs", null); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(ClusterWriteOutcome.CLUSTER_EXISTS, again.getCode());
        assertTrue(again.isRefused());
        assertNull(again.getCluster());
        assertEquals(1, manager.getAllClusters(probe.project).size());
    }

    /**
     * Renaming a cluster that is not there names that.
     */
    @Test
    public void renamingAMissingClusterIsClusterNotFound()
    {
        ClusterWriteOutcome outcome = manager.renameCluster(probe.project, "Catalogs/Missing", "Other"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(ClusterWriteOutcome.CLUSTER_NOT_FOUND, outcome.getCode());
        assertTrue(outcome.isRefused());
        assertEquals("The cluster was not found.", outcome.explanation()); //$NON-NLS-1$
    }

    /**
     * Renaming a cluster onto a name another cluster already has names that and leaves both.
     *
     * @throws Exception when the project cannot be inspected
     */
    @Test
    public void renamingOntoAnotherClusterIsNameTaken() throws Exception
    {
        assertTrue(manager.createCluster(probe.project, "Shelf", "Catalogs", null).succeeded()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(manager.createCluster(probe.project, "Box", "Catalogs", null).succeeded()); //$NON-NLS-1$ //$NON-NLS-2$

        ClusterWriteOutcome outcome = manager.renameCluster(probe.project, "Catalogs/Shelf", "Box"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(ClusterWriteOutcome.NAME_TAKEN, outcome.getCode());
        assertTrue(outcome.isRefused());
        assertNotNull(manager.getClusterStorage(probe.project).getClusterByFullPath("Catalogs/Shelf")); //$NON-NLS-1$
        assertNotNull(manager.getClusterStorage(probe.project).getClusterByFullPath("Catalogs/Box")); //$NON-NLS-1$
    }

    /**
     * Updating a cluster onto a name another cluster already has is the same refusal.
     *
     * @throws Exception when the project cannot be inspected
     */
    @Test
    public void updatingOntoAnotherClusterIsNameTaken() throws Exception
    {
        assertTrue(manager.createCluster(probe.project, "Shelf", "Catalogs", null).succeeded()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(manager.createCluster(probe.project, "Box", "Catalogs", null).succeeded()); //$NON-NLS-1$ //$NON-NLS-2$

        ClusterWriteOutcome outcome = manager.updateCluster(probe.project, "Catalogs/Shelf", "Box", "desc"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(ClusterWriteOutcome.NAME_TAKEN, outcome.getCode());
        assertTrue(outcome.isRefused());
        assertNotNull(manager.getClusterStorage(probe.project).getClusterByFullPath("Catalogs/Shelf")); //$NON-NLS-1$
    }

    /**
     * Removing an object that no cluster holds changes nothing and is not a refusal.
     *
     * @throws Exception when the project cannot be inspected
     */
    @Test
    public void removingAnObjectThatIsNotHeldIsNoChange() throws Exception
    {
        assertTrue(manager.createCluster(probe.project, "Shelf", "Catalogs", null).succeeded()); //$NON-NLS-1$ //$NON-NLS-2$

        ClusterWriteOutcome outcome = manager.removeObjectFromCluster(probe.project, "Catalog.Missing"); //$NON-NLS-1$

        assertEquals(ClusterSaveOutcome.NO_CHANGE, outcome.getCode());
        assertTrue(outcome.isNoChange());
        assertTrue(outcome.succeeded());
        assertFalse(outcome.isRefused());
    }
}
