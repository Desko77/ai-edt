/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.repository;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.folders.ClusterWorkspaceProbe;
import ru.aiedt.mcp.server.folders.model.Cluster;
import ru.aiedt.mcp.server.folders.model.ClusterStore;

/**
 * The store tells its own bytes on the disk from somebody else's.
 * <p>
 * A change notification the service receives about a file it just wrote is its own write coming
 * back as an event. Recognizing the bytes is what keeps that notification from being handled as
 * an external change.
 * </p>
 */
public class AStoreRecognizesItsOwnBytesTest
{
    private static final String OTHER_CONTENT = "groups:\n" //$NON-NLS-1$
        + "- children:\n" //$NON-NLS-1$
        + "  - Document.Sales\n" //$NON-NLS-1$
        + "  name: Sales\n" //$NON-NLS-1$
        + "  order: 0\n" //$NON-NLS-1$
        + "  path: Documents\n"; //$NON-NLS-1$

    private ClusterWorkspaceProbe probe;

    private YamlClusterStore store;

    /**
     * Opens a project and a store.
     *
     * @throws Exception when the project cannot be created
     */
    @Before
    public void aProjectAndAStore() throws Exception
    {
        probe = ClusterWorkspaceProbe.open("AiEdtClusterOwnBytes"); //$NON-NLS-1$
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
     * What the store wrote is its own; what somebody else wrote is not; what it then read is its
     * own again.
     *
     * @throws Exception when the file cannot be written
     */
    @Test
    public void writtenThenForeignThenReadAgain() throws Exception
    {
        ClusterStore storage = new ClusterStore();
        Cluster shelf = new Cluster("Shelf", "Catalogs"); //$NON-NLS-1$ //$NON-NLS-2$
        shelf.addChild("Catalog.Products"); //$NON-NLS-1$
        storage.addCluster(shelf);
        assertTrue(store.save(probe.project, storage).isOk());

        assertTrue("the bytes this store wrote are its own", //$NON-NLS-1$
            store.holdsWhatWasLastReadOrWritten(probe.project));

        probe.writeClusters(OTHER_CONTENT.getBytes(StandardCharsets.UTF_8));
        assertFalse("bytes somebody else wrote are not its own", //$NON-NLS-1$
            store.holdsWhatWasLastReadOrWritten(probe.project));

        assertTrue(store.load(probe.project) != null);
        assertTrue("having read them, they are its own", //$NON-NLS-1$
            store.holdsWhatWasLastReadOrWritten(probe.project));
    }

    /**
     * A store that never read the project counts no file as its own untouched state, and any file
     * that is there as foreign.
     *
     * @throws Exception when the file cannot be written
     */
    @Test
    public void aStoreThatNeverReadCountsAnyFileAsForeign() throws Exception
    {
        assertTrue("no file at all is the untouched state of a store that never read", //$NON-NLS-1$
            store.holdsWhatWasLastReadOrWritten(probe.project));

        probe.writeClusters(OTHER_CONTENT.getBytes(StandardCharsets.UTF_8));
        assertFalse("a file nobody read is a foreign one", //$NON-NLS-1$
            store.holdsWhatWasLastReadOrWritten(probe.project));
    }
}
