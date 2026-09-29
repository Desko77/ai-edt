/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.Test;

/**
 * A project named for its tasks is not answered with an empty list when it is closed.
 * <p>
 * A closed project passes the existence check that tells a typo from a project, and its markers are
 * read from the project description - which is open only when the project is. Skipping it silently
 * made "nothing found" out of "not looked at", and a caller reading that has no reason to ask again.
 * </p>
 */
public class AClosedProjectSaysItsTasksWereNotReadTest
{
    private static final String PROJECT_NAME = "aiedt-closed-project-tasks-test"; //$NON-NLS-1$

    /**
     * Creates the scratch project closed, with a task marker written while it was open.
     *
     * @return the closed project
     * @throws Exception when the project cannot be created
     */
    private static IProject aClosedProjectWithATask() throws Exception
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT_NAME);
        if (project.exists())
        {
            project.delete(true, true, new NullProgressMonitor());
        }
        project.create(new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        IMarker marker = project.createMarker("org.eclipse.core.resources.taskmarker"); //$NON-NLS-1$
        marker.setAttribute(IMarker.MESSAGE, "TODO: written while the project was open"); //$NON-NLS-1$
        marker.setAttribute(IMarker.PRIORITY, IMarker.PRIORITY_NORMAL);
        marker.setAttribute(IMarker.LINE_NUMBER, 1);
        project.close(new NullProgressMonitor());
        return project;
    }

    /** A named project that exists but is closed is named as not read, not left out. */
    @Test
    public void aNamedClosedProjectSaysItWasNotRead() throws Exception
    {
        IProject project = aClosedProjectWithATask();
        try
        {
            String answer = TasksReader.getTasks(PROJECT_NAME, null, null, 100);

            assertTrue("the answer names the project that was not read: " + answer, //$NON-NLS-1$
                answer.contains(PROJECT_NAME));
            assertTrue("and says why: " + answer, answer.contains("is closed")); //$NON-NLS-1$
            assertFalse("an unread project must not be answered as an empty one: " + answer, //$NON-NLS-1$
                answer.contains("*Nothing found.*")); //$NON-NLS-1$
        }
        finally
        {
            project.delete(true, true, new NullProgressMonitor());
        }
    }

    /** The whole-workspace read names the projects it did not read, next to what it did read. */
    @Test
    public void theWorkspaceReadNamesWhatItDidNotRead() throws Exception
    {
        IProject project = aClosedProjectWithATask();
        try
        {
            String answer = TasksReader.getTasks(null, null, null, 100);

            assertTrue("the closed project is named among those not read: " + answer, //$NON-NLS-1$
                answer.contains(PROJECT_NAME));
            assertTrue("the answer says the project was closed: " + answer, //$NON-NLS-1$
                answer.contains("closed")); //$NON-NLS-1$
            assertTrue("and marks it as not read: " + answer, answer.contains("Not read")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            project.delete(true, true, new NullProgressMonitor());
        }
    }

    /** A task of an open project is still listed, and no note is added for it. */
    @Test
    public void anOpenProjectIsReadAsBefore() throws Exception
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT_NAME + "-open"); //$NON-NLS-1$
        if (project.exists())
        {
            project.delete(true, true, new NullProgressMonitor());
        }
        project.create(new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        try
        {
            IMarker marker = project.createMarker("org.eclipse.core.resources.taskmarker"); //$NON-NLS-1$
            marker.setAttribute(IMarker.MESSAGE, "FIXME: read while open"); //$NON-NLS-1$
            marker.setAttribute(IMarker.PRIORITY, IMarker.PRIORITY_HIGH);
            marker.setAttribute(IMarker.LINE_NUMBER, 2);

            Map<String, String> params = new HashMap<>();
            params.put("projectName", project.getName()); //$NON-NLS-1$
            String answer = new TasksReader().execute(params);

            assertTrue("the task of an open project is found: " + answer, //$NON-NLS-1$
                answer.contains("FIXME: read while open")); //$NON-NLS-1$
            assertFalse("and no project is reported unread: " + answer, //$NON-NLS-1$
                answer.contains("Not read")); //$NON-NLS-1$
        }
        finally
        {
            project.delete(true, true, new NullProgressMonitor());
        }
    }

}
