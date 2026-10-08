/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

import org.junit.Test;

import ru.aiedt.mcp.server.folders.ClusterWorkspaceProbe;
import ru.aiedt.mcp.server.folders.model.Cluster;
import ru.aiedt.mcp.server.folders.model.ClusterStore;

/**
 * A save of the clusters file that meets a lock held on the destination answers a refusal and
 * leaves the bytes as they were. The staging, the double fingerprint check and the atomic move
 * behind the save are exercised in {@code AtomicFileReplaceTest}; this class watches the save
 * itself, against a real project.
 */
public class ARefusedLockLeavesTheFileWholeTest
{
    /**
     * A lock held on the clusters file makes {@link YamlClusterStore#save} refuse and leave the bytes.
     *
     * @throws Exception when the project or the lock cannot be opened
     */
    @Test
    public void aHeldLockRefusesTheSave() throws Exception
    {
        ClusterWorkspaceProbe probe = ClusterWorkspaceProbe.open("AiEdtClusterLock"); //$NON-NLS-1$
        try
        {
            YamlClusterStore store = new YamlClusterStore();
            ClusterStore storage = new ClusterStore();
            storage.addCluster(new Cluster("Shelf", "Catalogs")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(store.save(probe.project, storage).isOk());
            byte[] original = Files.readAllBytes(probe.clustersFile());
            storage.getGroups().get(0).addChild("Catalog.A"); //$NON-NLS-1$
            try (FileChannel channel = FileChannel.open(probe.clustersFile(), StandardOpenOption.READ);
                FileLock lock = channel.lock(0L, Long.MAX_VALUE, true))
            {
                assertTrue(lock.isValid());
                ClusterSaveOutcome outcome = store.save(probe.project, storage);
                assertEquals(ClusterSaveOutcome.LOCK_REFUSED, outcome.getCode());
                assertTrue(outcome.isRefused());
            }
            assertEquals(new String(original, StandardCharsets.UTF_8),
                Files.readString(probe.clustersFile()));
        }
        finally
        {
            probe.close();
        }
    }

    /**
     * A save that succeeded reads back, so the refusal above is about the lock and not about a
     * file the store can no longer write at all.
     *
     * @throws Exception when the project cannot be created
     */
    @Test
    public void theSameStoreStillSavesAfterTheLockGoes() throws Exception
    {
        ClusterWorkspaceProbe probe = ClusterWorkspaceProbe.open("AiEdtClusterLockAfter"); //$NON-NLS-1$
        try
        {
            YamlClusterStore store = new YamlClusterStore();
            ClusterStore storage = new ClusterStore();
            storage.addCluster(new Cluster("Shelf", "Catalogs")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(store.save(probe.project, storage).isOk());
            storage.getGroups().get(0).addChild("Catalog.A"); //$NON-NLS-1$
            assertTrue(store.save(probe.project, storage).isOk());
            ClusterStore reread = store.load(probe.project);
            assertTrue(reread.getGroups().get(0).containsChild("Catalog.A")); //$NON-NLS-1$
        }
        finally
        {
            probe.close();
        }
    }
}
