/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.jobs.IJobManager;
import org.eclipse.core.runtime.jobs.Job;

import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;

import ru.aiedt.mcp.server.Activator;

/**
 * Waits for a project to stop moving.
 * <p>
 * A tool that reads a project right after something changed it can read a half-built model: Eclipse
 * still has builders queued, and EDT is still recomputing what it derives from the sources - the BSL
 * indexes, the validation markers, the form models. These are the waits that let a tool see the model
 * the user would see.
 * </p>
 * <p>
 * Everything here is best effort. A wait that could not be made, or that ran out of time, is logged
 * and returns: none of these methods reports a result, and none of them throws. A tool must not fail
 * merely because the answer to "is it done yet" was inconclusive - it goes on to read what is there.
 * </p>
 */
public final class BuildTaskHelper
{
    /**
     * How long to wait for derived data when the caller does not say. Long, because on a large
     * configuration a full recompute genuinely takes minutes, and returning early would defeat the
     * purpose of waiting at all.
     */
    private static final long DEFAULT_DERIVED_DATA_TIMEOUT_MS = 5L * 60 * 1000;

    private BuildTaskHelper()
    {
        // utility
    }

    /**
     * Waits for the Eclipse build jobs to finish - the automatic ones first, then the manual ones.
     * <p>
     * There is no timeout: a build takes as long as it takes, and the caller passes a monitor to cancel
     * it if it must. An interruption is logged and swallowed, which means the caller cannot tell a
     * completed build from an interrupted one and will go on as though the build had finished. That is
     * the intended trade here - see the class comment.
     * </p>
     *
     * @param monitor the monitor to report to and to watch for cancellation; may be <code>null</code>
     * @throws org.eclipse.core.runtime.OperationCanceledException if the monitor is cancelled while
     *             waiting - cancellation is the caller's own decision and is not swallowed
     */
    public static void waitForBuildJobs(IProgressMonitor monitor)
    {
        try
        {
            IJobManager jobManager = Job.getJobManager();
            jobManager.join(ResourcesPlugin.FAMILY_AUTO_BUILD, monitor);
            jobManager.join(ResourcesPlugin.FAMILY_MANUAL_BUILD, monitor);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            Activator.logError("Interrupted while waiting for build jobs", e); //$NON-NLS-1$
        }
    }

    /**
     * Waits for the build jobs, then for the project's derived data, with the default timeout.
     *
     * @param project the project; may be <code>null</code>, in which case only the build jobs are
     *            waited for
     * @param monitor the monitor; may be <code>null</code>
     */
    public static void waitForBuildAndDerivedData(IProject project, IProgressMonitor monitor)
    {
        waitForBuildAndDerivedData(project, DEFAULT_DERIVED_DATA_TIMEOUT_MS, monitor);
    }

    /**
     * Waits for the build jobs, then for the project's derived data.
     * <p>
     * The timeout bounds the second wait only; the build itself is unbounded. Note what this does
     * <em>not</em> cover: a clean build tears the EDT project context down and brings it back up, and
     * catching that needs a listener registered <em>before</em> the build is triggered. That is
     * {@link ProjectReadinessGate}'s job, and it has to be started earlier than this can be called.
     * </p>
     *
     * @param project the project; may be <code>null</code>, in which case only the build jobs are
     *            waited for
     * @param timeoutMs how long to wait for the derived data, in milliseconds
     * @param monitor the monitor; may be <code>null</code>
     */
    public static void waitForBuildAndDerivedData(IProject project, long timeoutMs, IProgressMonitor monitor)
    {
        waitForBuildJobs(monitor);
        if (project != null)
        {
            waitForDerivedData(project, timeoutMs);
        }
    }

    /**
     * Waits for a project's derived data to be fully computed, with the default timeout.
     *
     * @param project the project
     */
    public static void waitForDerivedData(IProject project)
    {
        waitForDerivedData(project, DEFAULT_DERIVED_DATA_TIMEOUT_MS);
    }

    /**
     * Waits for a project's derived data to be fully computed.
     * <p>
     * Every step is optional. EDT may not publish the services; the project may not be an EDT project
     * at all. Each of those ends the wait quietly, because there is nothing there to wait for. Running
     * out of time ends it quietly too: the caller gets no result either way and reads whatever the
     * model holds by then.
     * </p>
     *
     * @param project the project
     * @param timeoutMs how long to wait, in milliseconds
     */
    public static void waitForDerivedData(IProject project, long timeoutMs)
    {
        try
        {
            IDerivedDataManagerProvider provider = derivedDataManagerProvider();
            if (provider == null)
            {
                Activator.logInfo("No IDerivedDataManagerProvider is published, skipping the derived-data wait"); //$NON-NLS-1$
                return;
            }
            IDtProjectManager projectManager = dtProjectManager();
            if (projectManager == null)
            {
                Activator.logInfo("No IDtProjectManager is published, skipping the derived-data wait"); //$NON-NLS-1$
                return;
            }
            IDtProject dtProject = projectManager.getDtProject(project);
            if (dtProject == null)
            {
                Activator.logInfo("Not a DtProject, skipping the derived-data wait: " + project.getName()); //$NON-NLS-1$
                return;
            }
            IDerivedDataManager ddManager = provider.get(dtProject);
            if (ddManager == null)
            {
                Activator.logInfo("No IDerivedDataManager is available for project: " + project.getName()); //$NON-NLS-1$
                return;
            }

            Activator.logInfo("Waiting on derived-data computation for: " + project.getName()); //$NON-NLS-1$
            if (ddManager.waitAllComputations(timeoutMs))
            {
                Activator.logInfo("Derived-data computation finished for: " + project.getName()); //$NON-NLS-1$
            }
            else
            {
                Activator.logInfo("Timed out waiting on derived-data computation for: " + project.getName()); //$NON-NLS-1$
            }
        }
        catch (Exception e)
        {
            // Including the interruption: waiting is an optimization, and a tool that cannot wait still
            // has a job to do.
            Activator.logError("Derived-data wait failed", e); //$NON-NLS-1$
        }
    }

    /**
     * @return EDT's derived data manager provider, or <code>null</code> when this EDT does not publish
     *         it or the plugin is not running
     */
    private static IDerivedDataManagerProvider derivedDataManagerProvider()
    {
        Activator activator = Activator.getDefault();
        return activator == null ? null : activator.getDerivedDataManagerProvider();
    }

    /**
     * @return EDT's project manager, or <code>null</code> when this EDT does not publish it or the
     *         plugin is not running
     */
    private static IDtProjectManager dtProjectManager()
    {
        Activator activator = Activator.getDefault();
        return activator == null ? null : activator.getDtProjectManager();
    }
}
