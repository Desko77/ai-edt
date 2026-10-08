/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.eclipse.core.resources.IProject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.folders.ClusterWorkspaceProbe;
import ru.aiedt.mcp.server.folders.internal.ClusterManagerImpl;
import ru.aiedt.mcp.server.folders.model.Cluster;

/**
 * An object is hidden from its normal place only while a drawn cluster node shows it.
 * <p>
 * A cluster whose path names no collection of the tree - a hand-typed path, a collection this
 * configuration does not carry - is never drawn anywhere. The filter hid its members all the
 * same, and the objects were then invisible in the whole tree. Hiding now asks whether the
 * holding cluster is drawn.
 * </p>
 */
public class AnObjectInAnUndrawnClusterStaysVisibleTest
{
    private ClusterWorkspaceProbe probe;

    private ClusterManagerImpl manager;

    /**
     * Opens a project with one cluster at a real collection and one at a path no collection has.
     *
     * @throws Exception when the project cannot be created
     */
    @Before
    public void clustersAtADrawnAndAnUndrawnPath() throws Exception
    {
        RenderedClusterPaths.clear();
        probe = ClusterWorkspaceProbe.open("AiEdtClusterFilter"); //$NON-NLS-1$
        manager = new ClusterManagerImpl();
        assertTrue(manager.createCluster(probe.project, "Shelf", "Catalogs", null).succeeded()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(manager.addObjectToCluster(probe.project, "Catalog.Products", "Catalogs/Shelf") //$NON-NLS-1$ //$NON-NLS-2$
            .succeeded());
        assertTrue(manager.createCluster(probe.project, "Nowhere", "NoCollectionHasThisPath", null) //$NON-NLS-1$ //$NON-NLS-2$
            .succeeded());
        assertTrue(manager.addObjectToCluster(probe.project, "Document.Sales", //$NON-NLS-1$
            "NoCollectionHasThisPath/Nowhere").succeeded());
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
        RenderedClusterPaths.clear();
    }

    /**
     * A member of an undrawn cluster stays visible in its normal place; drawing the collection
     * its sibling cluster sits at hides that cluster's member and only that one's.
     */
    @Test
    public void hidingWaitsForADrawnCluster()
    {
        assertFalse("nothing drawn yet: both objects stay in their normal places", //$NON-NLS-1$
            ClusterViewFilter.shownUnderADrawnCluster(manager.getAllClusters(probe.project),
                probe.project, "Catalog.Products") //$NON-NLS-1$
                || ClusterViewFilter.shownUnderADrawnCluster(manager.getAllClusters(probe.project),
                    probe.project, "Document.Sales")); //$NON-NLS-1$

        RenderedClusterPaths.noteCollectionDrawn(probe.project, "Catalogs"); //$NON-NLS-1$

        assertTrue("the drawn cluster's member is shown under it", //$NON-NLS-1$
            ClusterViewFilter.shownUnderADrawnCluster(manager.getAllClusters(probe.project),
                probe.project, "Catalog.Products")); //$NON-NLS-1$
        assertFalse("the undrawn cluster's member stays visible where it is", //$NON-NLS-1$
            ClusterViewFilter.shownUnderADrawnCluster(manager.getAllClusters(probe.project),
                probe.project, "Document.Sales")); //$NON-NLS-1$
    }

    /**
     * A cluster nested under a drawn cluster node is drawn with it.
     */
    @Test
    public void aNestedClusterIsDrawnWithItsNode()
    {
        assertTrue(manager.createCluster(probe.project, "Inner", "Catalogs/Shelf", null).succeeded()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(manager.addObjectToCluster(probe.project, "Catalog.Inner", "Catalogs/Shelf/Inner") //$NON-NLS-1$ //$NON-NLS-2$
            .succeeded());

        RenderedClusterPaths.noteClusterNodeDrawn(probe.project, "Catalogs/Shelf"); //$NON-NLS-1$

        assertTrue(ClusterViewFilter.shownUnderADrawnCluster(manager.getAllClusters(probe.project),
            probe.project, "Catalog.Inner")); //$NON-NLS-1$
    }

    /**
     * The registry answers per project: another project's drawn collection draws nothing here.
     */
    @Test
    public void drawingIsPerProject()
    {
        IProject other = probe.project.getWorkspace().getRoot().getProject("AiEdtOtherProject"); //$NON-NLS-1$
        RenderedClusterPaths.noteCollectionDrawn(other, "Catalogs"); //$NON-NLS-1$

        assertFalse(ClusterViewFilter.shownUnderADrawnCluster(manager.getAllClusters(probe.project),
            probe.project, "Catalog.Products")); //$NON-NLS-1$
        assertFalse("a cluster of another project is never drawn here", //$NON-NLS-1$
            RenderedClusterPaths.isDrawn(probe.project, new Cluster("Shelf", "Other"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A deleted parent cluster leaves no drawn path behind: a cluster later placed at the path
     * the deletion vacated hides nothing, because no node will ever show it.
     * <p>
     * The drawn path stayed in the registry after the delete, and a cluster taking the vacated
     * path was answered as drawn - its members were hidden from their normal place while no node
     * showed them anywhere. The change notification now forgets what the tree drew, and the
     * redraw notes again only what it draws then.
     * </p>
     */
    @Test
    public void aDeletedParentsPathStopsHiding()
    {
        manager.addClusterChangeListener(new ClusterTreeContent());
        RenderedClusterPaths.noteCollectionDrawn(probe.project, "Catalogs"); //$NON-NLS-1$
        RenderedClusterPaths.noteClusterNodeDrawn(probe.project, "Catalogs/Shelf"); //$NON-NLS-1$
        assertTrue("while the node is drawn, its member hides from the normal place", //$NON-NLS-1$
            ClusterViewFilter.shownUnderADrawnCluster(manager.getAllClusters(probe.project),
                probe.project, "Catalog.Products")); //$NON-NLS-1$

        assertTrue(manager.deleteCluster(probe.project, "Catalogs/Shelf").succeeded()); //$NON-NLS-1$
        assertTrue(manager.createCluster(probe.project, "Later", "Catalogs/Shelf", null).succeeded()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(manager.addObjectToCluster(probe.project, "Catalog.Later", "Catalogs/Shelf/Later") //$NON-NLS-1$ //$NON-NLS-2$
            .succeeded());
        RenderedClusterPaths.noteCollectionDrawn(probe.project, "Catalogs"); //$NON-NLS-1$

        assertFalse("the vacated path no longer hides: no node shows that cluster", //$NON-NLS-1$
            ClusterViewFilter.shownUnderADrawnCluster(manager.getAllClusters(probe.project),
                probe.project, "Catalog.Later")); //$NON-NLS-1$
    }

    /**
     * A renamed cluster leaves no drawn path behind either: a cluster later created under the
     * name the rename vacated hides nothing until the tree draws it.
     */
    @Test
    public void aRenamedParentsPathStopsHiding()
    {
        manager.addClusterChangeListener(new ClusterTreeContent());
        RenderedClusterPaths.noteCollectionDrawn(probe.project, "Catalogs"); //$NON-NLS-1$
        RenderedClusterPaths.noteClusterNodeDrawn(probe.project, "Catalogs/Shelf"); //$NON-NLS-1$

        assertTrue(manager.renameCluster(probe.project, "Catalogs/Shelf", "Case").succeeded()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(manager.createCluster(probe.project, "Again", "Catalogs/Shelf", null).succeeded()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(manager.addObjectToCluster(probe.project, "Catalog.Again", "Catalogs/Shelf/Again") //$NON-NLS-1$ //$NON-NLS-2$
            .succeeded());
        RenderedClusterPaths.noteCollectionDrawn(probe.project, "Catalogs"); //$NON-NLS-1$

        assertFalse("the renamed-away path no longer hides: no node shows that cluster", //$NON-NLS-1$
            ClusterViewFilter.shownUnderADrawnCluster(manager.getAllClusters(probe.project),
                probe.project, "Catalog.Again")); //$NON-NLS-1$
    }
}
