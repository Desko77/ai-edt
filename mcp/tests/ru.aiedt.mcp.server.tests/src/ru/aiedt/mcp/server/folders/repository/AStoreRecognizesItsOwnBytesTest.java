/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.repository;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.NullProgressMonitor;
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
     * A store that never read the project has no state of its own to find on the disk: neither a
     * file that is there nor the absence of one counts as its own.
     *
     * @throws Exception when the file cannot be written
     */
    @Test
    public void aStoreThatNeverReadCountsAnyFileAsForeign() throws Exception
    {
        assertFalse("a store that never read has no own state, not even the absent file", //$NON-NLS-1$
            store.holdsWhatWasLastReadOrWritten(probe.project));

        probe.writeClusters(OTHER_CONTENT.getBytes(StandardCharsets.UTF_8));
        assertFalse("a file nobody read is a foreign one", //$NON-NLS-1$
            store.holdsWhatWasLastReadOrWritten(probe.project));
    }

    /**
     * Not knowing what was last read and having read no file are different states: a read that
     * failed forgets the fingerprint, and the absence the store then finds on the disk is not the
     * absence it read. Reading the now-absent file puts the store back into its own empty state,
     * so a project whose unreadable file went away recovers instead of staying foreign forever.
     *
     * @throws Exception when the file cannot be written
     */
    @Test
    public void aForgottenFingerprintIsNotTheReadAbsenceOfTheFile() throws Exception
    {
        probe.writeClusters(new byte[] {(byte)0xC3, (byte)0x28});
        assertTrue("the unreadable file is refused", store.load(probe.project) == null); //$NON-NLS-1$
        assertFalse("the failed read left nothing the disk could hold as own", //$NON-NLS-1$
            store.holdsWhatWasLastReadOrWritten(probe.project));

        Files.deleteIfExists(probe.clustersFile());
        probe.project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        assertFalse("the deleted bad file is still not an own state - nothing was read", //$NON-NLS-1$
            store.holdsWhatWasLastReadOrWritten(probe.project));

        assertTrue(store.load(probe.project) != null);
        assertTrue("having read the absence, it is the store's own state", //$NON-NLS-1$
            store.holdsWhatWasLastReadOrWritten(probe.project));
    }
}
