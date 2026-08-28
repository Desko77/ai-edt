/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import ru.aiedt.mcp.server.support.PendingWorkRegistry;

/**
 * What a run that outlives the caller's patience is able to say about itself.
 * <p>
 * A batch commits each operation on its own, so a run still going has already changed the project.
 * Its Pending answer carried the elapsed milliseconds and nothing else, and a caller whose batch
 * outlived the timeout three times running could not tell that four of its six operations were on
 * disk. Reported from a configuration of some 13 thousand objects, where every batch outlives the
 * timeout.
 * </p>
 */
public class APendingRunSaysHowFarItGotTest
{
    private static Map<String, String> params(String... keyValues)
    {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2)
        {
            m.put(keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    /**
     * A settled entry to report into. Its constructor is not visible from here, and widening it
     * for a test would be the wrong trade.
     *
     * @return an entry whose work has finished, never {@code null}
     */
    private static PendingWorkRegistry.PendingEntry anEntry()
    {
        PendingWorkRegistry reg = PendingWorkRegistry.GENERIC;
        String runKey = "note-" + System.nanoTime(); //$NON-NLS-1$
        PendingWorkRegistry.PendingEntry e = reg.getOrStart(runKey, () -> "{}"); //$NON-NLS-1$
        e.await(5000);
        reg.remove(runKey);
        return e;
    }

    @Test
    public void theWorkCanPublishIntoTheEntryItRunsUnder() throws Exception
    {
        PendingWorkRegistry reg = PendingWorkRegistry.GENERIC;
        String runKey = "publishes-" + System.nanoTime(); //$NON-NLS-1$
        CountDownLatch published = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try
        {
            PendingWorkRegistry.PendingEntry entry = reg.getOrStart(runKey, job -> {
                job.progressNote = "2 of 6 done"; //$NON-NLS-1$
                published.countDown();
                try
                {
                    release.await(10, TimeUnit.SECONDS);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
                return "{}"; //$NON-NLS-1$
            });
            assertTrue("the work should have started", //$NON-NLS-1$
                published.await(10, TimeUnit.SECONDS));
            assertNull("still running, so no result yet", entry.await(50)); //$NON-NLS-1$
            assertEquals("2 of 6 done", entry.progressNote); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
            reg.remove(runKey);
        }
    }

    @Test
    public void workWithNothingToReportLeavesTheNoteEmpty()
    {
        PendingWorkRegistry reg = PendingWorkRegistry.GENERIC;
        String runKey = "silent-" + System.nanoTime(); //$NON-NLS-1$
        try
        {
            PendingWorkRegistry.PendingEntry entry =
                reg.getOrStart(runKey, () -> "{}"); //$NON-NLS-1$
            entry.await(5000);
            assertNull("a run without steps has nothing to say", entry.progressNote); //$NON-NLS-1$
        }
        finally
        {
            reg.remove(runKey);
        }
    }

    @Test
    public void theNoteNamesTheCountsAndWhatIsRunningNow()
    {
        PendingWorkRegistry.PendingEntry job = anEntry();
        EditMetadataTool.noteProgress(job, 4, 6, "add_object_attribute", 3, 1); //$NON-NLS-1$
        String note = job.progressNote;
        assertTrue(note, note.contains("4 of 6")); //$NON-NLS-1$
        assertTrue(note, note.contains("3 applied")); //$NON-NLS-1$
        assertTrue(note, note.contains("1 failed")); //$NON-NLS-1$
        assertTrue(note, note.contains("add_object_attribute")); //$NON-NLS-1$
    }

    @Test
    public void anOperationWithNoNameIsStillDescribed()
    {
        PendingWorkRegistry.PendingEntry job = anEntry();
        EditMetadataTool.noteProgress(job, 0, 3, null, 0, 0);
        assertTrue(job.progressNote, job.progressNote.contains("unnamed")); //$NON-NLS-1$
    }

    @Test
    public void reportingWithNoJobIsNotAFailure()
    {
        // A batch can run outside the registry - it must not die for having nowhere to report.
        EditMetadataTool.noteProgress(null, 1, 2, "create_object", 1, 0); //$NON-NLS-1$
    }

    @Test
    public void twoCallsAskingTheSameThingShareAnIdentity()
    {
        String a = EditMetadataTool.callIdentity(
            params("projectName", "P", "name", "X", "timeoutSeconds", "25")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        String b = EditMetadataTool.callIdentity(
            params("name", "X", "projectName", "P", "timeoutSeconds", "90", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                "runKey", "abc")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("how long to wait and which run to poll are not part of the request", a, b); //$NON-NLS-1$
    }

    @Test
    public void twoCallsAskingForDifferentThingsDoNot()
    {
        String a = EditMetadataTool.callIdentity(params("name", "X")); //$NON-NLS-1$ //$NON-NLS-2$
        String b = EditMetadataTool.callIdentity(params("name", "Y")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotEquals(a, b);
    }
}
