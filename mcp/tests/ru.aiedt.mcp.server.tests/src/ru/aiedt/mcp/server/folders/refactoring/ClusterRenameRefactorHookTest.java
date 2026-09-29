/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.refactoring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.ltk.core.refactoring.Change;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.folders.ClusterWorkspaceProbe;
import ru.aiedt.mcp.server.folders.internal.ClusterManagerImpl;
import ru.aiedt.mcp.server.folders.model.Cluster;
import ru.aiedt.mcp.server.folders.model.ClusterStore;
import ru.aiedt.mcp.server.folders.refactoring.ClusterRenameRefactorHook.ClusterFqnRenameChange;

/**
 * The rename contributor follows a cluster that holds the renamed object or a child of it, and the
 * change it contributes undoes itself.
 */
public class ClusterRenameRefactorHookTest
{
    private ClusterWorkspaceProbe probe;

    /**
     * Opens a project for the undo that goes through the service.
     *
     * @throws Exception when the project cannot be created
     */
    @Before
    public void aProject() throws Exception
    {
        probe = ClusterWorkspaceProbe.open("AiEdtClusterRename"); //$NON-NLS-1$
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
     * A cluster that holds only a child of the renamed object is still membership the hook must carry.
     * A name that merely shares a prefix is not.
     */
    @Test
    public void aDescendantAloneIsEnoughToFollowTheRename()
    {
        ClusterStore storage = new ClusterStore();
        Cluster cluster = new Cluster("Shelf", "Catalogs"); //$NON-NLS-1$ //$NON-NLS-2$
        cluster.addChild("Catalog.Products.CatalogAttribute.X"); //$NON-NLS-1$
        storage.addCluster(cluster);

        assertTrue(ClusterRenameRefactorHook.holdsObjectOrDescendant(storage, "Catalog.Products")); //$NON-NLS-1$
        assertFalse(ClusterRenameRefactorHook.holdsObjectOrDescendant(storage, "Catalog.ProductsExtra")); //$NON-NLS-1$
        assertFalse(ClusterRenameRefactorHook.holdsObjectOrDescendant(storage, "Catalog.Other")); //$NON-NLS-1$
        assertFalse(ClusterRenameRefactorHook.holdsObjectOrDescendant(null, "Catalog.Products")); //$NON-NLS-1$
    }

    /**
     * Performing the change returns the change that names the opposite direction, and performing that
     * one returns the original direction.
     */
    @Test
    public void performReturnsTheChangeThatRenamesBack() throws Exception
    {
        ClusterFqnRenameChange change = new ClusterFqnRenameChange(null, "Catalog.Products", "Catalog.Goods"); //$NON-NLS-1$ //$NON-NLS-2$

        Change undo = change.perform(new NullProgressMonitor());

        assertNotNull(undo);
        assertEquals("Update cluster membership: Catalog.Goods -> Catalog.Products", undo.getName()); //$NON-NLS-1$
        Change redone = undo.perform(new NullProgressMonitor());
        assertEquals("Update cluster membership: Catalog.Products -> Catalog.Goods", redone.getName()); //$NON-NLS-1$
    }

    /**
     * The step the change runs rewrites the object and a child held beside it, and running it the
     * other way puts both names back.
     *
     * @throws Exception when the clusters file cannot be written
     */
    @Test
    public void undoingTheRenameRestoresTheObjectAndItsDescendant() throws Exception
    {
        ClusterManagerImpl manager = new ClusterManagerImpl();
        assertNotNull(manager.createCluster(probe.project, "Shelf", "Catalogs", null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(manager.addObjectToCluster(probe.project, "Catalog.Products", "Catalogs/Shelf")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(manager.addObjectToCluster(probe.project,
            "Catalog.Products.CatalogAttribute.X", "Catalogs/Shelf")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(ClusterFqnRenameChange.apply(manager, probe.project, "Catalog.Products", "Catalog.Goods")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(manager.findClusterForObject(probe.project, "Catalog.Goods")); //$NON-NLS-1$
        assertNotNull(manager.findClusterForObject(probe.project, "Catalog.Goods.CatalogAttribute.X")); //$NON-NLS-1$
        assertNull(manager.findClusterForObject(probe.project, "Catalog.Products")); //$NON-NLS-1$
        assertNull(manager.findClusterForObject(probe.project, "Catalog.Products.CatalogAttribute.X")); //$NON-NLS-1$

        assertTrue(ClusterFqnRenameChange.apply(manager, probe.project, "Catalog.Goods", "Catalog.Products")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(manager.findClusterForObject(probe.project, "Catalog.Products")); //$NON-NLS-1$
        assertNotNull(manager.findClusterForObject(probe.project, "Catalog.Products.CatalogAttribute.X")); //$NON-NLS-1$
        assertNull(manager.findClusterForObject(probe.project, "Catalog.Goods")); //$NON-NLS-1$
        assertNull(manager.findClusterForObject(probe.project, "Catalog.Goods.CatalogAttribute.X")); //$NON-NLS-1$
    }
}
