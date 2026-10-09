/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * A write that did not land leaves the store naming the bytes the file holds, so the change the
 * file then gets from outside is still recognized as somebody else's.
 * <p>
 * The store's name for the file is what decides whether a change event about that file is its own
 * write coming back or a change from outside. A write that was refused never put its bytes on the
 * disk, and a store left naming them would read the next outside change as its own - the service
 * would keep the cache it has and tell nobody.
 * </p>
 */
public class ARefusedWriteLeavesNoNewBytesBehindTest
{
    private static final String FOREIGN = "groups:\n" //$NON-NLS-1$
        + "- children:\n" //$NON-NLS-1$
        + "  - Document.Sales\n" //$NON-NLS-1$
        + "  name: Sales\n" //$NON-NLS-1$
        + "  order: 0\n" //$NON-NLS-1$
        + "  path: Documents\n"; //$NON-NLS-1$

    /**
     * A write refused by a lock held on the file leaves the store on the bytes the file holds.
     *
     * @throws Exception when the project or the lock cannot be opened
     */
    @Test
    public void aRefusedWriteKeepsTheBytesTheFileHolds() throws Exception
    {
        ClusterWorkspaceProbe probe = ClusterWorkspaceProbe.open("AiEdtClusterRefusedBytes"); //$NON-NLS-1$
        try
        {
            YamlClusterStore store = new YamlClusterStore();
            ClusterStore storage = new ClusterStore();
            storage.addCluster(new Cluster("Shelf", "Catalogs")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(store.save(probe.project, storage).isOk());
            storage.getGroups().get(0).addChild("Catalog.A"); //$NON-NLS-1$

            try (FileChannel channel = FileChannel.open(probe.clustersFile(), StandardOpenOption.READ);
                FileLock lock = channel.lock(0L, Long.MAX_VALUE, true))
            {
                assertTrue(lock.isValid());
                ClusterSaveOutcome refused = store.save(probe.project, storage);
                assertEquals(ClusterSaveOutcome.LOCK_REFUSED, refused.getCode());
            }

            assertTrue("the file still holds what the store wrote, and the store says so", //$NON-NLS-1$
                store.holdsWhatWasLastReadOrWritten(probe.project));
        }
        finally
        {
            probe.close();
        }
    }

    /**
     * A rewrite from outside that follows a refused write is not taken for the store's own: the
     * bytes the refused write would have written are not on the disk, so the store cannot name
     * them.
     *
     * @throws Exception when the project or the lock cannot be opened
     */
    @Test
    public void aForeignChangeAfterARefusedWriteIsNotTheStoresOwn() throws Exception
    {
        ClusterWorkspaceProbe probe = ClusterWorkspaceProbe.open("AiEdtClusterRefusedForeign"); //$NON-NLS-1$
        try
        {
            YamlClusterStore store = new YamlClusterStore();
            ClusterStore storage = new ClusterStore();
            storage.addCluster(new Cluster("Shelf", "Catalogs")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(store.save(probe.project, storage).isOk());
            storage.getGroups().get(0).addChild("Catalog.A"); //$NON-NLS-1$

            try (FileChannel channel = FileChannel.open(probe.clustersFile(), StandardOpenOption.READ);
                FileLock lock = channel.lock(0L, Long.MAX_VALUE, true))
            {
                assertTrue(lock.isValid());
                ClusterSaveOutcome refused = store.save(probe.project, storage);
                assertEquals(ClusterSaveOutcome.LOCK_REFUSED, refused.getCode());
            }

            probe.writeClusters(FOREIGN.getBytes(StandardCharsets.UTF_8));

            assertFalse("the change from outside is not the bytes the refused write tried to put there", //$NON-NLS-1$
                store.holdsWhatWasLastReadOrWritten(probe.project));
            assertEquals(FOREIGN, Files.readString(probe.clustersFile()));
        }
        finally
        {
            probe.close();
        }
    }
}
