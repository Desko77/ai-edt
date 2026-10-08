/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * A clean whose restart wait ran out is not answered as finished.
 * <p>
 * The tool waits for every cleaned project's context to stop and start again, and a wait that ran
 * out used to be dropped on the floor: the answer said "Clean and revalidation finished" whether
 * or not the restarts it waited for had happened. A caller planning its next step on that answer -
 * read the markers, run the export - would act on a revalidation still in flight.
 * </p>
 */
public class ACleanThatMissedARestartSaysSoTest
{
    @Test
    public void aWaitThatRanOutIsNotCalledFinished()
    {
        String json = ProjectCleaner.cleanOutcome(List.of("Alpha", "Beta"), 1); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(json, json.contains("Clean and revalidation finished.")); //$NON-NLS-1$
        assertTrue(json, json.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(json, json.contains("restartsNotFinished")); //$NON-NLS-1$
        assertTrue(json, json.contains("did not finish restarting")); //$NON-NLS-1$
        // What WAS done is still said: the builds ran, and the projects are named.
        assertTrue(json, json.contains("Alpha")); //$NON-NLS-1$
        assertTrue(json, json.contains("Beta")); //$NON-NLS-1$
    }

    @Test
    public void aCleanWhoseWaitsAllFinishedIsFinished()
    {
        String json = ProjectCleaner.cleanOutcome(List.of("Alpha"), 0); //$NON-NLS-1$

        assertTrue(json, json.contains("Clean and revalidation finished.")); //$NON-NLS-1$
        assertFalse(json, json.contains("restartsNotFinished")); //$NON-NLS-1$
        assertTrue(json, json.contains("\"success\":true")); //$NON-NLS-1$
    }
}
