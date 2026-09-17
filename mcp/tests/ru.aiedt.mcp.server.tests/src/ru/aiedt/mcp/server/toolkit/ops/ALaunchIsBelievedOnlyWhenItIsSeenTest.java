/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.ops.DebugSessionStarter.LaunchOutcome;

/**
 * A debug launch is a success only when it is seen to be alive.
 *
 * <p>Measured on the stand 16.09: the debug server could not take its port, the environment said so
 * in a dialog of its own, the launch call returned without throwing, and the tool answered
 * <code>success:true</code> with "Debug session is now running" while the launch list was empty a
 * second later. The judgement is what was wrong, so the judgement is what these cases put to the
 * test.
 */
public class ALaunchIsBelievedOnlyWhenItIsSeenTest
{
    @Test
    public void aRuntimeClientWithALiveTargetIsRunning()
    {
        LaunchOutcome outcome = DebugSessionStarter.decide(false, true, false, false, true);

        assertNotNull("a live target answers at once, without waiting for the deadline", outcome); //$NON-NLS-1$
        assertTrue(outcome.started);
    }

    @Test
    public void aLaunchThatTerminatedStraightAwayIsRefused()
    {
        LaunchOutcome outcome = DebugSessionStarter.decide(true, false, false, false, false);

        assertNotNull(outcome);
        assertFalse(outcome.started);
        assertEquals("terminated", outcome.observed); //$NON-NLS-1$
    }

    @Test
    public void aRuntimeClientWithoutATargetIsRefusedOnceTheWindowIsOver()
    {
        LaunchOutcome outcome = DebugSessionStarter.decide(false, false, false, true, false);

        assertNotNull(outcome);
        assertFalse(outcome.started);
        assertEquals("noTargets", outcome.observed); //$NON-NLS-1$
        assertTrue("the refusal says how long it waited", //$NON-NLS-1$
            outcome.refusal.contains(String.valueOf(DebugSessionStarter.TARGET_WAIT_MS / 1000)));
    }

    @Test
    public void targetsThatAllTerminatedAreRefusedByTheirOwnName()
    {
        LaunchOutcome outcome = DebugSessionStarter.decide(false, false, false, true, true);

        assertNotNull(outcome);
        assertFalse(outcome.started);
        assertEquals("allTargetsTerminated", outcome.observed); //$NON-NLS-1$
    }

    @Test
    public void beforeTheDeadlineARuntimeClientIsNeitherAcceptedNorRefused()
    {
        assertNull("a target registers during the start, so the wait has to be allowed to run", //$NON-NLS-1$
            DebugSessionStarter.decide(false, false, false, false, false));
    }

    @Test
    public void anAttachWithoutATargetIsStillARunningLaunch()
    {
        // An attach registers its target when the debugger connects, which can be later than any
        // window this call holds. Demanding a target here would refuse a healthy launch.
        LaunchOutcome outcome = DebugSessionStarter.decide(false, false, true, false, false);

        assertNotNull(outcome);
        assertTrue(outcome.started);
    }

    @Test
    public void aDialogThatWasAlreadyUpIsNotReported()
    {
        List<Map<String, Object>> before = Collections.singletonList(dialog("Secure storage", "Enter")); //$NON-NLS-1$ //$NON-NLS-2$
        List<Map<String, Object>> now = Collections.singletonList(dialog("Secure storage", "Enter")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("a dialog the person was already answering is not the launch's doing", //$NON-NLS-1$
            DebugSessionStarter.newDialogs(before, now).isEmpty());
    }

    @Test
    public void everyDialogThatAppearedIsReported()
    {
        List<Map<String, Object>> before = Collections.singletonList(dialog("Secure storage", "Enter")); //$NON-NLS-1$ //$NON-NLS-2$
        List<Map<String, Object>> now = Arrays.asList(dialog("Secure storage", "Enter"), //$NON-NLS-1$ //$NON-NLS-2$
            dialog("Update", "Update the infobase?"), dialog("Error", "Debug port 1550 is taken")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        List<Map<String, Object>> appeared = DebugSessionStarter.newDialogs(before, now);

        assertEquals(2, appeared.size());
        assertEquals("Update", appeared.get(0).get("title")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Error", appeared.get(1).get("title")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Map<String, Object> dialog(String title, String message)
    {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("title", title); //$NON-NLS-1$
        entry.put("message", message); //$NON-NLS-1$
        return entry;
    }
}
