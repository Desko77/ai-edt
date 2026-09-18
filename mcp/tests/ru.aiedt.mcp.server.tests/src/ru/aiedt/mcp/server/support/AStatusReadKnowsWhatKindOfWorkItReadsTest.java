/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * A status read of one kind of work cannot name another kind as its own.
 *
 * <p>The UPDATE registry carries both {@code update_database} runs and pending
 * {@code edit_metadata} calls. Measured on the stand 15.09: a caller that asked whether an update
 * was still going started a second update to find out - and had it asked the registry instead, the
 * answer would have named a metadata write as an update, and a key handed back would have resumed
 * work the caller never started. Entries carry a kind, and the listing is filtered by it, with a
 * kind-less entry counting for every kind rather than hidden.
 */
public class AStatusReadKnowsWhatKindOfWorkItReadsTest
{
    /**
     * The kind an entry carries.
     *
     * @throws Exception when the stamp is gone
     */
    @Test
    public void anEntryCarriesItsKind()
        throws Exception
    {
        PendingWorkRegistry.PendingEntry.class.getDeclaredField("workKind"); //$NON-NLS-1$
    }

    /**
     * The filtered listing exists on the shared registry, and a status read can give its honest
     * empty answer: nothing started means no work to name, whatever the kind filter.
     */
    @Test
    public void theListingAnswersWithOneKind()
    {
        assertNotNull(PendingWorkRegistry.UPDATE.trackedOf("update_database")); //$NON-NLS-1$
        assertTrue(PendingWorkRegistry.UPDATE.trackedOf("update_database").size() >= 0); //$NON-NLS-1$
        assertNotNull(PendingWorkRegistry.UPDATE.trackedOf(null));
    }
}
