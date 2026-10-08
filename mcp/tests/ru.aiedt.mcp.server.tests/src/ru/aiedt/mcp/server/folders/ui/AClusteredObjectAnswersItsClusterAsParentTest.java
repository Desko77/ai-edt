/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.folders.ClusterWorkspaceProbe;
import ru.aiedt.mcp.server.folders.internal.ClusterManagerImpl;
import ru.aiedt.mcp.server.folders.model.Cluster;

/**
 * The parent a clustered object answers is the cluster node that holds it.
 * <p>
 * A viewer asked to select or reveal an element walks the parent chain from the element up to a
 * root, so the chain has to be answered for the clustered object too - otherwise the selection
 * cannot name the item that sits under the cluster node, and comes back empty.
 * </p>
 */
public class AClusteredObjectAnswersItsClusterAsParentTest
{
    private ClusterWorkspaceProbe probe;

    private ClusterManagerImpl manager;

    /**
     * Opens a project whose one cluster holds one object.
     *
     * @throws Exception when the project cannot be created
     */
    @Before
    public void aClusteredProject() throws Exception
    {
        probe = ClusterWorkspaceProbe.open("AiEdtClusterParent"); //$NON-NLS-1$
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
     * A clustered object's parent is a cluster node for the cluster that holds it - equal, by the
     * bridge's own equality, to the rendered node for the same cluster.
     */
    @Test
    public void aClusteredObjectsParentIsItsClusterNode()
    {
        Object parent = ClusterTreeContent.parentOfClusteredObject(manager, probe.project,
            "Catalog.Products"); //$NON-NLS-1$

        assertTrue(parent instanceof ClusterNavigatorBridge);
        ClusterNavigatorBridge node = (ClusterNavigatorBridge)parent;
        assertEquals("Catalogs/Shelf", node.getCluster().getFullPath()); //$NON-NLS-1$
        assertEquals(probe.project, node.getProject());
        assertEquals(node, new ClusterNavigatorBridge( //$NON-NLS-1$
            new Cluster("Shelf", "Catalogs"), probe.project, null)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * An object in no cluster keeps the parent EDT's own providers name: the answer is null.
     */
    @Test
    public void anUnclusteredObjectHasNoClusterParent()
    {
        assertNull(ClusterTreeContent.parentOfClusteredObject(manager, probe.project, //$NON-NLS-1$
            "Document.Sales"));
    }

    /**
     * Without a service, a project or a name there is no cluster parent to answer.
     */
    @Test
    public void noServiceAnswersNoParent()
    {
        assertNull(ClusterTreeContent.parentOfClusteredObject(null, probe.project, "Catalog.Products")); //$NON-NLS-1$
        assertNull(ClusterTreeContent.parentOfClusteredObject(manager, null, "Catalog.Products")); //$NON-NLS-1$
        assertNull(ClusterTreeContent.parentOfClusteredObject(manager, probe.project, null));
    }

    /**
     * A cluster node's own parent stays the one the node was built with.
     */
    @Test
    public void aBridgeKeepsTheParentItWasGiven()
    {
        Object given = new Object();
        ClusterNavigatorBridge node = new ClusterNavigatorBridge(new Cluster("Shelf", "Catalogs"), //$NON-NLS-1$ //$NON-NLS-2$
            probe.project, given);

        assertSame(given, new ClusterTreeContent().getParent(node));
    }

    /**
     * The node answered for an object of a nested cluster carries the chain of ancestors above
     * it: a viewer walking the parent chain reaches the top cluster instead of stopping on a
     * node the tree shows under other nodes, and a collapsed nested cluster can be revealed.
     */
    @Test
    public void aNestedClustersNodeCarriesItsAncestors()
    {
        assertTrue(manager.createCluster(probe.project, "Rack", "Catalogs/Shelf", null).succeeded()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(manager.addObjectToCluster(probe.project, "Catalog.Products", "Catalogs/Shelf/Rack") //$NON-NLS-1$ //$NON-NLS-2$
            .succeeded());

        Object parent = ClusterTreeContent.parentOfClusteredObject(manager, probe.project,
            "Catalog.Products"); //$NON-NLS-1$

        assertTrue(parent instanceof ClusterNavigatorBridge);
        ClusterNavigatorBridge rack = (ClusterNavigatorBridge)parent;
        assertEquals("Catalogs/Shelf/Rack", rack.getCluster().getFullPath()); //$NON-NLS-1$

        Object above = rack.getParent(rack);
        assertTrue("the nested node's parent is the enclosing cluster's node", //$NON-NLS-1$
            above instanceof ClusterNavigatorBridge);
        assertEquals("Catalogs/Shelf", ((ClusterNavigatorBridge)above).getCluster().getFullPath()); //$NON-NLS-1$
        assertNull("the top cluster ends the chain", //$NON-NLS-1$
            ((ClusterNavigatorBridge)above).getParent(above));
    }
}
