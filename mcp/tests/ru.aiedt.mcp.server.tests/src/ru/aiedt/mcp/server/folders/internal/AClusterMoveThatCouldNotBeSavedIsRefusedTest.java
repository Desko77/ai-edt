/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.folders.ClusterWorkspaceProbe;
import ru.aiedt.mcp.server.folders.ClusterWriteOutcome;
import ru.aiedt.mcp.server.folders.IClusterChangeObserver;
import ru.aiedt.mcp.server.folders.model.Cluster;
import ru.aiedt.mcp.server.folders.model.ClusterStore;
import ru.aiedt.mcp.server.folders.repository.ClusterSaveOutcome;
import ru.aiedt.mcp.server.folders.repository.IClusterStore;

/**
 * A cluster edit whose file cannot be written must answer failure and must not announce a change.
 * <p>
 * The service is backed by a deterministic store whose save operation can be refused. The tests do
 * not rely on filesystem permissions, which differ between operating systems and privileged users.
 * </p>
 */
public class AClusterMoveThatCouldNotBeSavedIsRefusedTest
{
    private ClusterWorkspaceProbe probe;

    private ClusterManagerImpl manager;

    private RefusingStore store;

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
        store = new RefusingStore();
        manager = new ClusterManagerImpl(store);
        notices = 0;
    }

    /**
     * Closes the project.
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
     * saved storage unchanged, does not tell listeners, and does not keep the unsaved member.
     *
     * @throws Exception when the project cannot be inspected
     */
    @Test
    public void aMoveThatCannotBeSavedIsRefusedAndNotAnnounced() throws Exception
    {
        assertNotNull(manager.createCluster(probe.project, "Shelf", "Catalogs", null).getCluster()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(manager.addObjectToCluster(probe.project, "Catalog.A", "Catalogs/Shelf").succeeded()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(manager.holdsObjectOrDescendant(probe.project, "Catalog")); //$NON-NLS-1$
        manager.addClusterChangeListener(noticed());
        store.refuseSaves = true;

        ClusterWriteOutcome refused = manager.addObjectToCluster(probe.project, "Catalog.B", "Catalogs/Shelf"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refused.isRefused());
        assertEquals(ClusterSaveOutcome.WRITE_FAILED, refused.getCode());
        assertTrue(manager.findClusterForObject(probe.project, "Catalog.A") != null); //$NON-NLS-1$
        assertNull(manager.findClusterForObject(probe.project, "Catalog.B")); //$NON-NLS-1$
        assertTrue(notices == 0);
    }

    /**
     * Creating a cluster whose file cannot be written answers {@code null} and does not tell listeners.
     *
     * @throws Exception when the project cannot be inspected
     */
    @Test
    public void aClusterThatCannotBeSavedIsRefusedAndNotAnnounced() throws Exception
    {
        Cluster first = manager.createCluster(probe.project, "Shelf", "Catalogs", null).getCluster(); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(first);
        manager.addClusterChangeListener(noticed());
        store.refuseSaves = true;

        ClusterWriteOutcome refused = manager.createCluster(probe.project, "Other", "Catalogs", null); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(refused.getCluster());
        assertTrue(refused.isRefused());
        assertEquals(ClusterSaveOutcome.WRITE_FAILED, refused.getCode());
        assertNull(manager.getClusterStorage(probe.project).getClusterByFullPath("Catalogs/Other")); //$NON-NLS-1$
        assertTrue(notices == 0);
    }

    /**
     * A failed load is not cached as an empty store, and no edit based on that failed load is saved.
     */
    @Test
    public void aFailedLoadIsNotCachedOrSaved()
    {
        store.saved.addCluster(new Cluster("Shelf", "Catalogs")); //$NON-NLS-1$ //$NON-NLS-2$
        store.refuseLoads = true;

        assertTrue(manager.getClusterStorage(probe.project).isEmpty());
        ClusterWriteOutcome refused = manager.addObjectToCluster(probe.project, "Catalog.A", "Catalogs/Shelf"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refused.isRefused());
        assertEquals(ClusterSaveOutcome.READ_FAILED, refused.getCode());
        assertTrue(store.saveCalls == 0);

        store.refuseLoads = false;
        manager.refresh(probe.project);
        assertNotNull(manager.getClusterStorage(probe.project).getClusterByFullPath("Catalogs/Shelf")); //$NON-NLS-1$
    }

    /**
     * A file that could not be loaded is read once until the cache is dropped, not once per call.
     */
    @Test
    public void aFailedLoadIsNotRepeatedOnEveryRead()
    {
        store.refuseLoads = true;

        manager.getClusterStorage(probe.project);
        manager.getClusterStorage(probe.project);
        manager.findClusterForObject(probe.project, "Catalog.A"); //$NON-NLS-1$
        assertEquals(1, store.loadCalls);

        manager.refresh(probe.project);
        manager.getClusterStorage(probe.project);
        assertEquals(2, store.loadCalls);
    }

    /**
     * @return a listener that counts announcements
     */
    private IClusterChangeObserver noticed()
    {
        return project -> notices++;
    }

    /** A controllable repository that persists copies only when a save is accepted. */
    private static final class RefusingStore
        implements IClusterStore
    {
        private ClusterStore saved = new ClusterStore();

        private boolean refuseSaves;

        private boolean refuseLoads;

        private int saveCalls;

        private int loadCalls;

        @Override
        public ClusterStore load(org.eclipse.core.resources.IProject project)
        {
            loadCalls++;
            return refuseLoads ? null : copy(saved);
        }

        @Override
        public ClusterSaveOutcome save(org.eclipse.core.resources.IProject project, ClusterStore storage)
        {
            saveCalls++;
            if (refuseSaves)
            {
                return ClusterSaveOutcome.refused(ClusterSaveOutcome.WRITE_FAILED, "refused"); //$NON-NLS-1$
            }
            saved = copy(storage);
            return ClusterSaveOutcome.ok();
        }

        @Override
        public boolean exists(org.eclipse.core.resources.IProject project)
        {
            return !saved.isEmpty();
        }

        @Override
        public boolean delete(org.eclipse.core.resources.IProject project)
        {
            saved = new ClusterStore();
            return true;
        }

        /**
         * Copies storage deeply enough that a rejected edit cannot alter persisted state.
         *
         * @param source the storage to copy
         * @return an independent copy
         */
        private static ClusterStore copy(ClusterStore source)
        {
            ClusterStore result = new ClusterStore();
            for (Cluster cluster : source.getGroups())
            {
                Cluster copied = new Cluster(cluster.getName(), cluster.getPath());
                copied.setDescription(cluster.getDescription());
                copied.setOrder(cluster.getOrder());
                copied.setChildren(cluster.getChildren());
                result.addCluster(copied);
            }
            return result;
        }
    }
}
