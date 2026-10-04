/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.repository;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import ru.aiedt.mcp.server.support.UnwritableRecord;

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
        ClusterSaveOutcome refused = store.save(probe.project, replacement);
        assertEquals(ClusterSaveOutcome.UNREADABLE_FILE, refused.getCode());
        assertTrue(refused.isRefused());
        assertTrue(refused.getDetail().endsWith(ClusterKeys.CLUSTERS_FILE + ".bak")); //$NON-NLS-1$

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

        assertTrue(store.save(probe.project, storage).isOk());

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
        ClusterSaveOutcome refused = store.save(probe.project, replacement);
        assertEquals(ClusterSaveOutcome.UNREADABLE_FILE, refused.getCode());
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
        ClusterSaveOutcome refused = store.save(probe.project, replacement);
        assertEquals(ClusterSaveOutcome.UNREADABLE_FILE, refused.getCode());
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

        ClusterSaveOutcome firstRefusal = store.save(probe.project, replacement);
        assertEquals(ClusterSaveOutcome.UNREADABLE_FILE, firstRefusal.getCode());
        java.nio.file.Path backup = probe.clustersFile()
            .resolveSibling(ClusterKeys.CLUSTERS_FILE + ".bak"); //$NON-NLS-1$
        assertArrayEquals(CONFLICT, Files.readAllBytes(backup));

        byte[] laterConflict = "not: [valid".getBytes(StandardCharsets.UTF_8); //$NON-NLS-1$
        probe.writeClusters(laterConflict);
        ClusterSaveOutcome secondRefusal = store.save(probe.project, replacement);
        assertEquals(ClusterSaveOutcome.UNREADABLE_FILE, secondRefusal.getCode());

        assertArrayEquals(CONFLICT, Files.readAllBytes(backup));
        assertArrayEquals(laterConflict, Files.readAllBytes(probe.clustersFile()));
    }

    /**
     * A directory standing where the clusters file should be cannot be read, so the save names that.
     *
     * @throws Exception when the directory cannot be created
     */
    @Test
    public void aDirectoryInPlaceOfTheFileIsReadFailed() throws Exception
    {
        java.nio.file.Path file = probe.clustersFile();
        Files.createDirectories(file);
        ClusterStore replacement = new ClusterStore();
        replacement.addCluster(new Cluster("Only", "Catalogs")); //$NON-NLS-1$ //$NON-NLS-2$

        ClusterSaveOutcome outcome = store.save(probe.project, replacement);

        assertEquals(ClusterSaveOutcome.READ_FAILED, outcome.getCode());
        assertTrue(outcome.isRefused());
        assertNotNull(outcome.getDetail());
        assertFalse(outcome.getDetail().isEmpty());
        assertTrue(outcome.explanation().contains(outcome.getDetail()));
    }

    /**
     * A settings path that is a file, not a folder, refuses the save and keeps the exception text.
     * The code names the step that met the obstacle: WRITE_FAILED when no file was ever seen to
     * read, READ_FAILED when the path under a file answered as unreadable. Nothing is written
     * either way.
     *
     * @throws Exception when the stand-in file cannot be written
     */
    @Test
    public void aSettingsPathThatIsAFileIsWriteFailed() throws Exception
    {
        java.nio.file.Path settings = probe.project.getLocation().toFile().toPath()
            .resolve(ClusterKeys.SETTINGS_FOLDER);
        Files.writeString(settings, "not a folder"); //$NON-NLS-1$
        ClusterStore storage = new ClusterStore();
        storage.addCluster(new Cluster("Shelf", "Catalogs")); //$NON-NLS-1$ //$NON-NLS-2$

        ClusterSaveOutcome outcome = store.save(probe.project, storage);

        assertTrue(outcome.explanation(), outcome.isRefused());
        assertTrue(outcome.explanation(),
            ClusterSaveOutcome.WRITE_FAILED.equals(outcome.getCode())
                || ClusterSaveOutcome.READ_FAILED.equals(outcome.getCode()));
        assertNotNull(outcome.getDetail());
        assertFalse(outcome.getDetail().isEmpty());
        assertTrue(outcome.explanation().contains(outcome.getDetail()));
    }

    /**
     * A clusters file the file system will not let the store write is refused and left unchanged.
     * Windows answers ACCESS_DENIED from opening the read-only file; a POSIX system answers
     * WRITE_FAILED from the replacement the directory's permissions refuse.
     *
     * @throws Exception when the file cannot be written
     */
    @Test
    public void aClustersFileTheStoreCannotWriteIsRefused() throws Exception
    {
        String original = "groups:\n" //$NON-NLS-1$
            + "- children:\n" //$NON-NLS-1$
            + "  - Catalog.A\n" //$NON-NLS-1$
            + "  name: Shelf\n" //$NON-NLS-1$
            + "  order: 0\n" //$NON-NLS-1$
            + "  path: Catalogs\n"; //$NON-NLS-1$
        probe.writeClusters(original.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ClusterStore loaded = store.load(probe.project);
        loaded.getGroups().get(0).setName("Renamed"); //$NON-NLS-1$
        java.nio.file.Path file = probe.clustersFile();
        UnwritableRecord unwritable = UnwritableRecord.of(file);
        try
        {
            ClusterSaveOutcome outcome = store.save(probe.project, loaded);
            assertTrue(outcome.explanation(), outcome.isRefused());
            assertTrue(outcome.explanation(),
                ClusterSaveOutcome.ACCESS_DENIED.equals(outcome.getCode())
                    || ClusterSaveOutcome.WRITE_FAILED.equals(outcome.getCode()));
            assertTrue(Files.readString(file).contains("name: Shelf")); //$NON-NLS-1$
        }
        finally
        {
            unwritable.close();
        }
    }
}
