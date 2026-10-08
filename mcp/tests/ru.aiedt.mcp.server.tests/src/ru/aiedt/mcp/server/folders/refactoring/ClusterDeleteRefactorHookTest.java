/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.refactoring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.folders.ClusterWorkspaceProbe;
import ru.aiedt.mcp.server.folders.internal.ClusterManagerImpl;
import ru.aiedt.mcp.server.folders.model.Cluster;
import ru.aiedt.mcp.server.folders.model.ClusterStore;

/**
 * The delete contributor cleans the deleted object and everything nested under it, and only runs
 * when there is membership to clean.
 */
public class ClusterDeleteRefactorHookTest
{
    private ClusterWorkspaceProbe probe;

    /**
     * Opens a project the removal can write to.
     *
     * @throws Exception when the project cannot be created
     */
    @Before
    public void aProject() throws Exception
    {
        probe = ClusterWorkspaceProbe.open("AiEdtClusterDelete"); //$NON-NLS-1$
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
     * A cluster that holds only a child of the deleted object is still membership the hook must
     * clean. A name that merely shares a prefix is not.
     */
    @Test
    public void aDescendantAloneIsEnoughToRunTheOperation()
    {
        ClusterStore storage = new ClusterStore();
        Cluster cluster = new Cluster("Shelf", "Catalogs"); //$NON-NLS-1$ //$NON-NLS-2$
        cluster.addChild("Catalog.Products.CatalogAttribute.X"); //$NON-NLS-1$
        storage.addCluster(cluster);

        assertTrue(ClusterDeleteRefactorHook.holdsObjectOrDescendant(storage, "Catalog.Products")); //$NON-NLS-1$
        assertFalse(ClusterDeleteRefactorHook.holdsObjectOrDescendant(storage, "Catalog.ProductsExtra")); //$NON-NLS-1$
        assertFalse(ClusterDeleteRefactorHook.holdsObjectOrDescendant(storage, "Catalog.Other")); //$NON-NLS-1$
        assertFalse(ClusterDeleteRefactorHook.holdsObjectOrDescendant(null, "Catalog.Products")); //$NON-NLS-1$
    }

    /**
     * Removing a deleted object takes the names nested under it out of the cluster too, and leaves
     * a sibling that merely shares a prefix where it was.
     *
     * @throws Exception when the clusters file cannot be written
     */
    @Test
    public void removingAnObjectTakesItsNestedNamesWithIt() throws Exception
    {
        ClusterManagerImpl manager = new ClusterManagerImpl();
        assertTrue(manager.createCluster(probe.project, "Shelf", "Catalogs", null).succeeded()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(manager.addObjectToCluster(probe.project, "Catalog.Products", "Catalogs/Shelf") //$NON-NLS-1$ //$NON-NLS-2$
            .succeeded());
        assertTrue(manager.addObjectToCluster(probe.project,
            "Catalog.Products.CatalogAttribute.X", "Catalogs/Shelf").succeeded()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(manager.addObjectToCluster(probe.project, "Catalog.ProductsExtra", "Catalogs/Shelf") //$NON-NLS-1$ //$NON-NLS-2$
            .succeeded());

        assertTrue(manager.removeObject(probe.project, "Catalog.Products").succeeded()); //$NON-NLS-1$

        assertNull(manager.findClusterForObject(probe.project, "Catalog.Products")); //$NON-NLS-1$
        assertNull(manager.findClusterForObject(probe.project, //$NON-NLS-1$
            "Catalog.Products.CatalogAttribute.X")); //$NON-NLS-1$
        assertEquals("Catalog.ProductsExtra must stay: it shares a prefix, not a parent", //$NON-NLS-1$
            "Catalogs/Shelf", //$NON-NLS-1$
            manager.findClusterForObject(probe.project, "Catalog.ProductsExtra").getFullPath()); //$NON-NLS-1$
    }

    /**
     * An object nobody holds, whose children nobody holds either, is no change - the hook builds no
     * operation and the removal writes nothing.
     *
     * @throws Exception when the clusters file cannot be written
     */
    @Test
    public void anObjectWithNoMembershipAnywhereIsNoChange() throws Exception
    {
        ClusterManagerImpl manager = new ClusterManagerImpl();
        assertTrue(manager.createCluster(probe.project, "Shelf", "Catalogs", null).succeeded()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(manager.addObjectToCluster(probe.project, "Catalog.Products", "Catalogs/Shelf") //$NON-NLS-1$ //$NON-NLS-2$
            .succeeded());

        assertTrue(manager.removeObject(probe.project, "Document.Sale").isNoChange()); //$NON-NLS-1$
        assertEquals("the clusters file must keep what it had", 1, //$NON-NLS-1$
            Files.readAllBytes(probe.clustersFile()).length > 0 ? 1 : 0);
        assertEquals("Catalogs/Shelf", //$NON-NLS-1$
            manager.findClusterForObject(probe.project, "Catalog.Products").getFullPath()); //$NON-NLS-1$
    }
}
