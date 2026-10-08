/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.folders.ClusterWorkspaceProbe;
import ru.aiedt.mcp.server.folders.model.ClusterStore;

/**
 * A clusters file with a blank list entry loads what it holds, and an entry of the wrong kind
 * refuses the load rather than throwing on every read.
 * <p>
 * A dash with nothing after it - what a hand edit or a merge leaves behind - reads as a null
 * cluster entry. Null reached as a cluster is a NullPointerException out of the load, on every
 * read of that project's clusters, with no unreadable-file refusal anywhere. The load now drops
 * such entries; an entry that parses into something that is not a cluster at all refuses the load.
 * </p>
 */
public class ABlankClusterEntryDoesNotBreakTheLoadTest
{
    private static final String BLANK_ENTRY_BESIDE_A_CLUSTER = "groups:\n" //$NON-NLS-1$
        + "-\n" //$NON-NLS-1$
        + "- children:\n" //$NON-NLS-1$
        + "  - Catalog.Products\n" //$NON-NLS-1$
        + "  name: Shelf\n" //$NON-NLS-1$
        + "  order: 0\n" //$NON-NLS-1$
        + "  path: Catalogs\n"; //$NON-NLS-1$

    private static final String ENTRY_OF_THE_WRONG_KIND = "groups:\n" //$NON-NLS-1$
        + "- Shelf\n"; //$NON-NLS-1$

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
        probe = ClusterWorkspaceProbe.open("AiEdtClusterBlank"); //$NON-NLS-1$
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
     * A null list entry is dropped; the cluster beside it loads with everything it held.
     *
     * @throws Exception when the file cannot be written
     */
    @Test
    public void aBlankEntryIsDroppedAndTheClusterBesideItLoads() throws Exception
    {
        probe.writeClusters(BLANK_ENTRY_BESIDE_A_CLUSTER.getBytes(StandardCharsets.UTF_8));

        ClusterStore loaded = store.load(probe.project);

        assertNotNull(loaded);
        assertEquals(1, loaded.getGroups().size());
        assertEquals("Shelf", loaded.getGroups().get(0).getName()); //$NON-NLS-1$
        assertTrue(loaded.findClusterForObject("Catalog.Products") != null); //$NON-NLS-1$
    }

    /**
     * An entry that is not a cluster at all refuses the load; the caller gets no clusters rather
     * than an exception, which is the refusal every other unreadable file already produces.
     *
     * @throws Exception when the file cannot be written
     */
    @Test
    public void anEntryOfTheWrongKindRefusesTheLoad() throws Exception
    {
        probe.writeClusters(ENTRY_OF_THE_WRONG_KIND.getBytes(StandardCharsets.UTF_8));

        assertNull(store.load(probe.project));
    }
}
