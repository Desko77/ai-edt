/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.repository;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.folders.ClusterKeys;
import ru.aiedt.mcp.server.folders.ClusterWorkspaceProbe;
import ru.aiedt.mcp.server.folders.model.Cluster;
import ru.aiedt.mcp.server.folders.model.ClusterStore;

/**
 * An unreadable clusters file is not replaced by the next save, and a key this build does not know
 * does not make the file read as empty.
 */
public class AnUnreadableClusterFileIsNotReplacedTest
{
    private static final byte[] CONFLICT = ("<<<<<<< HEAD\n" //$NON-NLS-1$
        + "groups: []\n" //$NON-NLS-1$
        + "=======\n" //$NON-NLS-1$
        + "groups: []\n" //$NON-NLS-1$
        + ">>>>>>> other\n").getBytes(StandardCharsets.UTF_8); //$NON-NLS-1$

    private ClusterWorkspaceProbe probe;

    private YamlClusterStore store;

    /**
     * Opens a project and a store.
     *
     * @throws Exception when the project cannot be created
     */
    @Before
    public void aProject() throws Exception
    {
        probe = ClusterWorkspaceProbe.open("AiEdtClusterFile"); //$NON-NLS-1$
        store = new YamlClusterStore();
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
     * Conflict markers read as no clusters, and saving a cluster afterwards leaves the file bytes
     * in place and keeps a copy beside them.
     *
     * @throws Exception when the file cannot be written or read back
     */
    @Test
    public void conflictMarkersAreNotReplaced() throws Exception
    {
        probe.writeClusters(CONFLICT);
        ClusterStore loaded = store.load(probe.project);
        assertNull(loaded);

        ClusterStore replacement = new ClusterStore();
        replacement.addCluster(new Cluster("Only", "Catalogs")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(store.save(probe.project, replacement));

        assertArrayEquals(CONFLICT, Files.readAllBytes(probe.clustersFile()));
        assertArrayEquals(CONFLICT, Files.readAllBytes(
            probe.clustersFile().resolveSibling(ClusterKeys.CLUSTERS_FILE + ".bak"))); //$NON-NLS-1$
    }

    /**
     * A key written by a newer build is skipped, and the clusters this build does know are kept.
     *
     * @throws Exception when the file cannot be written
     */
    @Test
    public void anUnknownKeyDoesNotDegradeToEmpty() throws Exception
    {
        String yaml = "groups:\n" //$NON-NLS-1$
            + "- children:\n" //$NON-NLS-1$
            + "  - Catalog.A\n" //$NON-NLS-1$
            + "  name: Kept\n" //$NON-NLS-1$
            + "  order: 0\n" //$NON-NLS-1$
            + "  path: Catalogs\n" //$NON-NLS-1$
            + "  addedByNewerBuild: true\n"; //$NON-NLS-1$
        probe.writeClusters(yaml.getBytes(StandardCharsets.UTF_8));

        ClusterStore loaded = store.load(probe.project);

        assertEquals(1, loaded.getClusterCount());
        assertEquals("Kept", loaded.getGroups().get(0).getName()); //$NON-NLS-1$
        assertTrue(loaded.getGroups().get(0).containsChild("Catalog.A")); //$NON-NLS-1$
    }

    /**
     * A file this build can read is still saved and read back.
     *
     * @throws Exception when the save fails for a reason other than the one under test
     */
    @Test
    public void aReadableFileIsStillSaved() throws Exception
    {
        Cluster cluster = new Cluster("Shelf", "Catalogs"); //$NON-NLS-1$ //$NON-NLS-2$
        cluster.addChild("Catalog.A"); //$NON-NLS-1$
        ClusterStore storage = new ClusterStore();
        storage.addCluster(cluster);

        assertTrue(store.save(probe.project, storage));

        ClusterStore loaded = store.load(probe.project);
        assertEquals(1, loaded.getClusterCount());
        assertEquals("Shelf", loaded.getGroups().get(0).getName()); //$NON-NLS-1$
        assertTrue(loaded.findClusterForObject("Catalog.A") != null); //$NON-NLS-1$
    }

    /**
     * A malformed UTF-8 sequence makes the file unreadable and prevents replacement.
     *
     * @throws Exception when the file cannot be written or read back
     */
    @Test
    public void malformedUtf8IsNotLoadedOrReplaced() throws Exception
    {
        byte[] malformed = new byte[] {'g', 'r', 'o', 'u', 'p', 's', ':', '\n', '-', ' ',
            'n', 'a', 'm', 'e', ':', ' ', (byte)0xc3, (byte)0x28, '\n'};
        probe.writeClusters(malformed);

        assertNull(store.load(probe.project));
        ClusterStore replacement = new ClusterStore();
        replacement.addCluster(new Cluster("Only", "Catalogs")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(store.save(probe.project, replacement));
        assertArrayEquals(malformed, Files.readAllBytes(probe.clustersFile()));
    }

    /**
     * A file created directly on disk is protected even before the workspace resource tree sees it.
     *
     * @throws Exception when the file cannot be written or read back
     */
    @Test
    public void anUnrefreshedDiskFileIsNotReplaced() throws Exception
    {
        Files.createDirectories(probe.clustersFile().getParent());
        Files.write(probe.clustersFile(), CONFLICT);
        assertFalse(probe.project.getFile(ClusterKeys.CLUSTERS_PATH).exists());

        ClusterStore replacement = new ClusterStore();
        replacement.addCluster(new Cluster("Only", "Catalogs")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(store.save(probe.project, replacement));
        assertArrayEquals(CONFLICT, Files.readAllBytes(probe.clustersFile()));
    }

    /**
     * Repeated refused saves preserve the first unreadable backup instead of overwriting it.
     *
     * @throws Exception when the file cannot be written or read back
     */
    @Test
    public void repeatedRefusedSavesPreserveTheFirstBackup() throws Exception
    {
        probe.writeClusters(CONFLICT);
        ClusterStore replacement = new ClusterStore();
        replacement.addCluster(new Cluster("Only", "Catalogs")); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(store.save(probe.project, replacement));
        java.nio.file.Path backup = probe.clustersFile()
            .resolveSibling(ClusterKeys.CLUSTERS_FILE + ".bak"); //$NON-NLS-1$
        assertArrayEquals(CONFLICT, Files.readAllBytes(backup));

        byte[] laterConflict = "not: [valid".getBytes(StandardCharsets.UTF_8); //$NON-NLS-1$
        probe.writeClusters(laterConflict);
        assertFalse(store.save(probe.project, replacement));

        assertArrayEquals(CONFLICT, Files.readAllBytes(backup));
        assertArrayEquals(laterConflict, Files.readAllBytes(probe.clustersFile()));
    }
}
