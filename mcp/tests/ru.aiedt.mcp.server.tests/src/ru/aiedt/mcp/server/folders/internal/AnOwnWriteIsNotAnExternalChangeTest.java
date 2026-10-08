/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.folders.ClusterWorkspaceProbe;
import ru.aiedt.mcp.server.folders.IClusterChangeObserver;
import ru.aiedt.mcp.server.folders.model.ClusterStore;

/**
 * A write of the service's own does not arrive as an external change.
 * <p>
 * Every save rewrites the file, and the workspace answers with a change event. Handled as
 * foreign, that event dropped the cache the write had just produced - the next read reloaded the
 * file - and told the listeners a second time about a change they had already been told about.
 * The event about a file carrying the bytes this service last wrote is now recognized and left
 * alone; a foreign rewrite still reloads and still tells.
 * </p>
 */
public class AnOwnWriteIsNotAnExternalChangeTest
{
    private static final String FOREIGN_CONTENT = "groups:\n" //$NON-NLS-1$
        + "- children:\n" //$NON-NLS-1$
        + "  - Document.Sales\n" //$NON-NLS-1$
        + "  name: Sales\n" //$NON-NLS-1$
        + "  order: 0\n" //$NON-NLS-1$
        + "  path: Documents\n"; //$NON-NLS-1$

    private ClusterWorkspaceProbe probe;

    private ClusterManagerImpl manager;

    private int told;

    /**
     * Opens a project and a manager that counts what its listeners are told.
     *
     * @throws Exception when the project cannot be created
     */
    @Before
    public void aProjectAndAListeningManager() throws Exception
    {
        probe = ClusterWorkspaceProbe.open("AiEdtClusterOwnWrite"); //$NON-NLS-1$
        manager = new ClusterManagerImpl();
        manager.addClusterChangeListener(new IClusterChangeObserver()
        {
            @Override
            public void onClustersChanged(org.eclipse.core.resources.IProject project)
            {
                told++;
            }
        });
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
     * The change event a save causes is skipped: no second telling, and the cache keeps serving
     * what the write produced instead of reloading the file.
     *
     * @throws Exception when the clusters file cannot be written
     */
    @Test
    public void theEventAnOwnWriteCausesIsSkipped() throws Exception
    {
        assertTrue(manager.createCluster(probe.project, "Shelf", "Catalogs", null).succeeded()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, told);

        manager.clustersFileTouched(probe.project);

        assertEquals("the own write was already told by the save itself", 1, told); //$NON-NLS-1$
        ClusterStore served = manager.getClusterStorage(probe.project);
        assertNotNull("the cache keeps what the write produced", //$NON-NLS-1$
            served.getClusterByFullPath("Catalogs/Shelf")); //$NON-NLS-1$
    }

    /**
     * A rewrite from outside still reloads and still tells.
     *
     * @throws Exception when the clusters file cannot be written
     */
    @Test
    public void aForeignRewriteStillReloadsAndTells() throws Exception
    {
        assertTrue(manager.createCluster(probe.project, "Shelf", "Catalogs", null).succeeded()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, told);

        probe.writeClusters(FOREIGN_CONTENT.getBytes(StandardCharsets.UTF_8));
        manager.clustersFileTouched(probe.project);

        assertEquals(2, told);
        ClusterStore served = manager.getClusterStorage(probe.project);
        assertNotNull("the reload reads the foreign bytes", //$NON-NLS-1$
            served.getClusterByFullPath("Documents/Sales")); //$NON-NLS-1$
    }
}
