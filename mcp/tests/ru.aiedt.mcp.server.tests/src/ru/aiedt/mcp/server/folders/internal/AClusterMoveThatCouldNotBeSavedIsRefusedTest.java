/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.internal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.folders.ClusterWorkspaceProbe;
import ru.aiedt.mcp.server.folders.IClusterChangeObserver;
import ru.aiedt.mcp.server.folders.model.Cluster;

/**
 * A cluster edit whose file cannot be written must answer failure and must not announce a change.
 * <p>
 * The service is the real one, backed by {@link ru.aiedt.mcp.server.folders.repository.YamlClusterStore},
 * over a temporary project. The first edit creates the file. The file is then marked read-only, and
 * the next edit has nowhere to go.
 * </p>
 */
public class AClusterMoveThatCouldNotBeSavedIsRefusedTest
{
    private ClusterWorkspaceProbe probe;

    private ClusterManagerImpl manager;

    private int notices;

    /**
     * Opens a project and a service that has not been activated: file edits do not need the watcher.
     *
     * @throws Exception when the project cannot be created
     */
    @Before
    public void aProjectAndAService() throws Exception
    {
        probe = ClusterWorkspaceProbe.open("AiEdtClusterMove"); //$NON-NLS-1$
        manager = new ClusterManagerImpl();
        notices = 0;
    }

    /**
     * Closes the project, clearing a read-only bit first so the delete can remove the file.
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
     * Moving an object into a cluster whose file cannot be written answers {@code false}, leaves the
     * bytes that were saved, does not tell listeners, and does not keep the unsaved member.
     *
     * @throws Exception when the file cannot be read or marked read-only
     */
    @Test
    public void aMoveThatCannotBeSavedIsRefusedAndNotAnnounced() throws Exception
    {
        assertNotNull(manager.createCluster(probe.project, "Shelf", "Catalogs", null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(manager.addObjectToCluster(probe.project, "Catalog.A", "Catalogs/Shelf")); //$NON-NLS-1$ //$NON-NLS-2$
        byte[] saved = Files.readAllBytes(probe.clustersFile());
        assertTrue(saved.length > 0);

        manager.addClusterChangeListener(noticed());
        probe.setReadOnly(true);
        try
        {
            assertFalse(manager.addObjectToCluster(probe.project, "Catalog.B", "Catalogs/Shelf")); //$NON-NLS-1$ //$NON-NLS-2$
            assertArrayEquals(saved, Files.readAllBytes(probe.clustersFile()));
            assertTrue(manager.findClusterForObject(probe.project, "Catalog.A") != null); //$NON-NLS-1$
            assertNull(manager.findClusterForObject(probe.project, "Catalog.B")); //$NON-NLS-1$
            assertTrue(notices == 0);
        }
        finally
        {
            probe.setReadOnly(false);
        }
    }

    /**
     * Creating a cluster whose file cannot be written answers {@code null} and does not tell listeners.
     *
     * @throws Exception when the file cannot be marked read-only
     */
    @Test
    public void aClusterThatCannotBeSavedIsRefusedAndNotAnnounced() throws Exception
    {
        Cluster first = manager.createCluster(probe.project, "Shelf", "Catalogs", null); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(first);
        byte[] saved = Files.readAllBytes(probe.clustersFile());

        manager.addClusterChangeListener(noticed());
        probe.setReadOnly(true);
        try
        {
            assertNull(manager.createCluster(probe.project, "Other", "Catalogs", null)); //$NON-NLS-1$ //$NON-NLS-2$
            assertArrayEquals(saved, Files.readAllBytes(probe.clustersFile()));
            assertNull(manager.getClusterStorage(probe.project).getClusterByFullPath("Catalogs/Other")); //$NON-NLS-1$
            assertTrue(notices == 0);
        }
        finally
        {
            probe.setReadOnly(false);
        }
    }

    /**
     * @return a listener that counts announcements
     */
    private IClusterChangeObserver noticed()
    {
        return project -> notices++;
    }
}
