/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.junit.Test;

import ru.aiedt.mcp.server.support.BmSupportRegistryHelper.ParentModes;

/**
 * The editability note fires on the registry's own record, not on the manager's aggregate.
 * <p>
 * In the vendor distribution file state the environment manager reduces every mode to
 * ChangesNotAllowed, and the note used to key on that aggregate - so exactly the case it exists to
 * explain (the records say ChangesAllowed, the environment edits nothing) came back with no note,
 * and a reader took the mode line over the canEdit line.
 * </p>
 */
public class TheEditabilityNoteSurvivesTheReducedModeTest
{
    /** The per-vendor record counts even when the manager has reduced its aggregate. */
    @Test
    public void theRegistryRecordCountsWhenTheManagerReducedTheMode()
    {
        ParentModes recorded = new ParentModes();
        recorded.userMode = "ChangesAllowed"; //$NON-NLS-1$

        assertTrue(BmSupportRegistryHelper.registryAllowsEdits("ChangesNotAllowed", //$NON-NLS-1$
            Collections.singletonList(recorded)));
    }

    /** The aggregated answer still counts on its own. */
    @Test
    public void theAggregatedAnswerStillCounts()
    {
        assertTrue(BmSupportRegistryHelper.registryAllowsEdits("ChangesAllowed", //$NON-NLS-1$
            Collections.<ParentModes>emptyList()));
    }

    /** Nothing recording ChangesAllowed is not the contradiction the note explains. */
    @Test
    public void nothingAllowedAnywhereIsNotAContradiction()
    {
        ParentModes recorded = new ParentModes();
        recorded.userMode = "ChangesNotAllowed"; //$NON-NLS-1$

        assertFalse(BmSupportRegistryHelper.registryAllowsEdits("ChangesNotAllowed", //$NON-NLS-1$
            Collections.singletonList(recorded)));
        assertFalse(BmSupportRegistryHelper.registryAllowsEdits(null,
            Collections.<ParentModes>emptyList()));
    }
}
