/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.repository;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.folders.ClusterKeys;
import ru.aiedt.mcp.server.folders.ClusterWorkspaceProbe;
import ru.aiedt.mcp.server.folders.model.Cluster;
import ru.aiedt.mcp.server.folders.model.ClusterStore;

/**
 * A clusters file that another process wrote is the one that is read, and it is not replaced by a set
 * that was read without it.
 * <p>
 * The resource tree and the disk part company whenever something outside the workspace writes the
 * file - git checks a branch out, an editor saves it. The tree then still answers that there is no
 * such resource, and a store that believes the tree reads no clusters, hands the caller an empty set,
 * and writes that emptiness back over a valid file on the caller's first edit. Neither half of that
 * may happen: the bytes on disk are the project's clusters, and a set that was not read from them is
 * not written over them.
 * </p>
 */
public class AnExternalClustersFileIsReadAndNotReplacedTest
{
    private static final String TWO_CLUSTERS = "groups:\n" //$NON-NLS-1$
        + "- children:\n" //$NON-NLS-1$
        + "  - Catalog.Currencies\n" //$NON-NLS-1$
        + "  name: Currencies\n" //$NON-NLS-1$
        + "  order: 0\n" //$NON-NLS-1$
        + "  path: Catalogs\n" //$NON-NLS-1$
        + "- children:\n" //$NON-NLS-1$
        + "  - Document.Sales\n" //$NON-NLS-1$
        + "  name: Sales\n" //$NON-NLS-1$
        + "  order: 1\n" //$NON-NLS-1$
        + "  path: Documents\n"; //$NON-NLS-1$

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
        probe = ClusterWorkspaceProbe.open("AiEdtExternalFile"); //$NON-NLS-1$
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
     * Writes the clusters file straight to disk, leaving the resource tree unaware of it.
     *
     * @param yaml the file contents
     * @throws Exception when the file cannot be written
     */
    private void writeBehindTheTree(String yaml) throws Exception
    {
        Files.createDirectories(probe.clustersFile().getParent());
        Files.write(probe.clustersFile(), yaml.getBytes(StandardCharsets.UTF_8));
        assertFalse("the resource tree must not know the file for this test to mean anything", //$NON-NLS-1$
            probe.project.getFile(ClusterKeys.CLUSTERS_PATH).exists());
    }

    /**
     * The clusters a file on disk holds are read, even though the resource tree reports no file.
     *
     * @throws Exception when the file cannot be written
     */
    @Test
    public void aFileOnlyOnDiskIsRead() throws Exception
    {
        writeBehindTheTree(TWO_CLUSTERS);

        ClusterStore loaded = store.load(probe.project);

        assertEquals("the file holds two clusters and both have to arrive", 2, //$NON-NLS-1$
            loaded.getClusterCount());
        assertEquals("Currencies", loaded.getGroups().get(0).getName()); //$NON-NLS-1$
        assertEquals("Sales", loaded.getGroups().get(1).getName()); //$NON-NLS-1$
        assertTrue("a cluster's objects come with it", //$NON-NLS-1$
            loaded.findClusterForObject("Document.Sales") != null); //$NON-NLS-1$
    }

    /**
     * A file that appeared after a read that found nothing is not replaced by the next save.
     * <p>
     * This is the loss the file watcher would have covered if it had run first: the user's set was
     * built when the project had no clusters, and the file that arrived since holds clusters nobody
     * read.
     * </p>
     *
     * @throws Exception when the file cannot be written or read back
     */
    @Test
    public void aFileThatArrivedAfterAnEmptyReadIsNotReplaced() throws Exception
    {
        ClusterStore empty = store.load(probe.project);
        assertEquals(0, empty.getClusterCount());

        writeBehindTheTree(TWO_CLUSTERS);

        ClusterStore replacement = new ClusterStore();
        replacement.addCluster(new Cluster("Only", "Catalogs")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a file nobody read must not be written over", //$NON-NLS-1$
            store.save(probe.project, replacement));

        assertArrayEquals(TWO_CLUSTERS.getBytes(StandardCharsets.UTF_8),
            Files.readAllBytes(probe.clustersFile()));
    }

    /**
     * A file that changed after it was read is not replaced either, and a set read from a file that
     * did not change is still saved.
     *
     * @throws Exception when the file cannot be written or read back
     */
    @Test
    public void aFileThatChangedAfterTheReadIsNotReplaced() throws Exception
    {
        writeBehindTheTree(TWO_CLUSTERS);
        ClusterStore loaded = store.load(probe.project);
        assertEquals(2, loaded.getClusterCount());

        String rewritten = TWO_CLUSTERS + "- children: []\n  name: Added elsewhere\n  order: 2\n" //$NON-NLS-1$
            + "  path: Catalogs\n"; //$NON-NLS-1$
        probe.writeClusters(rewritten.getBytes(StandardCharsets.UTF_8));

        loaded.getGroups().get(0).addChild("Catalog.New"); //$NON-NLS-1$
        assertFalse("the file changed under the set that was read", //$NON-NLS-1$
            store.save(probe.project, loaded));
        assertArrayEquals(rewritten.getBytes(StandardCharsets.UTF_8),
            Files.readAllBytes(probe.clustersFile()));

        ClusterStore reread = store.load(probe.project);
        assertEquals("the file that is there reads as it stands", 3, reread.getClusterCount()); //$NON-NLS-1$
        assertTrue("and the set read from it is saved", store.save(probe.project, reread)); //$NON-NLS-1$
    }

    /**
     * The ordinary cycle - read, change, save, read - is unaffected.
     *
     * @throws Exception when a save or a read fails
     */
    @Test
    public void theOrdinaryCycleStillSaves() throws Exception
    {
        ClusterStore first = new ClusterStore();
        first.addCluster(new Cluster("Shelf", "Catalogs")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a set saved where no file was ever read creates one", //$NON-NLS-1$
            store.save(probe.project, first));

        ClusterStore read = store.load(probe.project);
        assertEquals(1, read.getClusterCount());
        read.getGroups().get(0).addChild("Catalog.A"); //$NON-NLS-1$
        assertTrue("a change to a set read from the file is written", //$NON-NLS-1$
            store.save(probe.project, read));

        ClusterStore again = store.load(probe.project);
        assertEquals(1, again.getClusterCount());
        assertTrue(again.getGroups().get(0).containsChild("Catalog.A")); //$NON-NLS-1$
    }

    /**
     * Removing the last cluster still deletes the file it was read from.
     *
     * @throws Exception when the file cannot be written or read back
     */
    @Test
    public void theLastClusterStillDeletesTheFileItWasReadFrom() throws Exception
    {
        probe.writeClusters(TWO_CLUSTERS.getBytes(StandardCharsets.UTF_8));
        ClusterStore loaded = store.load(probe.project);
        assertEquals(2, loaded.getClusterCount());

        loaded.setGroups(new ArrayList<>());
        assertTrue("an empty set removes a file that has not changed since the read", //$NON-NLS-1$
            store.save(probe.project, loaded));
        assertFalse(Files.exists(probe.clustersFile()));
    }
}
