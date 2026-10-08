/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.folders.ClusterWorkspaceProbe;
import ru.aiedt.mcp.server.folders.model.Cluster;
import ru.aiedt.mcp.server.folders.model.ClusterStore;

/**
 * What the read seam serves shares nothing with the cached storage.
 * <p>
 * The Navigator and the tools read what they were served outside the service's cache lock, while a
 * writer may edit the cached clusters under the write lock. Serving the live instances made those
 * reads race the writer; serving detached copies makes the two sides independent, which is what
 * these tests hold to.
 * </p>
 */
public class AServedClusterSharesNothingWithTheCacheTest
{
    private ClusterWorkspaceProbe probe;

    private ClusterManagerImpl manager;

    /**
     * Opens a project with one cluster holding one object.
     *
     * @throws Exception when the project cannot be created
     */
    @Before
    public void aClusteredProject() throws Exception
    {
        probe = ClusterWorkspaceProbe.open("AiEdtClusterServed"); //$NON-NLS-1$
        manager = new ClusterManagerImpl();
        assertTrue(manager.createCluster(probe.project, "Shelf", "Catalogs", null).succeeded()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(manager.addObjectToCluster(probe.project, "Catalog.Products", "Catalogs/Shelf") //$NON-NLS-1$ //$NON-NLS-2$
            .succeeded());
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
     * Editing a served storage does not reach the service: the next read serves what the file
     * holds, not what the caller did to its copy.
     */
    @Test
    public void editingAServedStorageReachesNothing()
    {
        ClusterStore served = manager.getClusterStorage(probe.project);
        served.getClusterByFullPath("Catalogs/Shelf").addChild("Catalog.FromTheCopy"); //$NON-NLS-1$ //$NON-NLS-2$

        List<String> children = manager.getClusterStorage(probe.project)
            .getClusterByFullPath("Catalogs/Shelf").getChildren(); //$NON-NLS-1$
        assertEquals("the cached storage must not see the copy's edit", 1, children.size()); //$NON-NLS-1$
        assertEquals("Catalog.Products", children.get(0)); //$NON-NLS-1$
    }

    /**
     * The same independence for every list the seam serves, and for the cluster a create answers
     * with: mutating any of them leaves the next read untouched.
     */
    @Test
    public void editingAServedClusterReachesNothing()
    {
        Cluster atPath = manager.getClustersAtPath(probe.project, "Catalogs").get(0); //$NON-NLS-1$
        atPath.addChild("Catalog.FromTheCopy"); //$NON-NLS-1$
        Cluster everywhere = manager.getAllClusters(probe.project).get(0);
        everywhere.addChild("Document.FromTheCopy"); //$NON-NLS-1$
        Cluster holder = manager.findClusterForObject(probe.project, "Catalog.Products"); //$NON-NLS-1$
        holder.addChild("Catalog.FromTheHolder"); //$NON-NLS-1$

        List<String> children = manager.getClusterStorage(probe.project)
            .getClusterByFullPath("Catalogs/Shelf").getChildren(); //$NON-NLS-1$
        assertEquals(1, children.size());
        assertEquals("Catalog.Products", children.get(0)); //$NON-NLS-1$
    }

    /**
     * The cluster a create answers with carries the created values and is not the cached instance.
     */
    @Test
    public void aCreateAnswersWithTheValuesNotTheInstance()
    {
        Cluster created = manager.createCluster(probe.project, "Second", "Catalogs", null) //$NON-NLS-1$ //$NON-NLS-2$
            .getCluster();
        assertNotNull(created);
        assertEquals("Catalogs/Second", created.getFullPath()); //$NON-NLS-1$
        created.addChild("Catalog.FromTheAnswer"); //$NON-NLS-1$

        assertFalse(manager.getClusterStorage(probe.project) //$NON-NLS-1$
            .getClusterByFullPath("Catalogs/Second").getChildren().contains("Catalog.FromTheAnswer")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
