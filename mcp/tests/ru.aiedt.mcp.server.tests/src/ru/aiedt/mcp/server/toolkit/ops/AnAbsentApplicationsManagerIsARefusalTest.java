/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.Test;

import ru.aiedt.mcp.server.support.ApplicationUpdater;

/**
 * A call that reaches for the applications manager while nothing is there to answer is a named
 * refusal, in the pre-launch update and in the event-log read alike.
 * <p>
 * The manager is resolved through the running plugin's activator; this runtime offers no
 * applications service, so the resolution has nothing to hand back. What that has to produce is
 * a refusal the caller can read - not a NullPointerException. The activator itself being null,
 * the other half of the same guard, happens only while the bundle is stopping and is covered by
 * inspection: no test runtime can hold the bundle stopped and running at once.
 * </p>
 */
public class AnAbsentApplicationsManagerIsARefusalTest
{
    private static final String PROJECT = "aiedt-absent-manager-probe"; //$NON-NLS-1$

    /** The scratch project goes away again. */
    @After
    public void theProjectGoes()
    {
        try
        {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT);
            if (project.exists())
            {
                project.delete(true, true, new NullProgressMonitor());
            }
        }
        catch (Exception ignored)
        {
            // best-effort cleanup of a scratch project
        }
    }

    /** The pre-launch update answers SERVICE_UNAVAILABLE, or names the application it missed. */
    @Test
    public void thePreLaunchUpdateRefusesInsteadOfThrowing() throws Exception
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT);
        project.create(new NullProgressMonitor());
        project.open(new NullProgressMonitor());

        ApplicationUpdater.Result r = ApplicationUpdater.updateIfNeeded(PROJECT, "an-application"); //$NON-NLS-1$

        assertNotNull("an absent manager is an answer, not silence", r); //$NON-NLS-1$
        assertTrue("the refusal names one of the two ways it could not proceed: " + r.outcome, //$NON-NLS-1$
            r.outcome == ApplicationUpdater.Outcome.SERVICE_UNAVAILABLE
                || r.outcome == ApplicationUpdater.Outcome.APPLICATION_NOT_FOUND);
    }

    /** The event-log read answers with an error that says what is missing. */
    @Test
    public void theEventLogReadRefusesInsteadOfThrowing() throws Exception
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT);
        project.create(new NullProgressMonitor());
        project.open(new NullProgressMonitor());

        Map<String, String> params = new HashMap<>();
        params.put("projectName", PROJECT); //$NON-NLS-1$
        String answer = new EventLogTool().execute(params);

        assertNotNull(answer);
        assertTrue("the answer is an error about what could not be reached: " + answer, //$NON-NLS-1$
            answer.contains("unavailable") || answer.contains("No infobase found")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
