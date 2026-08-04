/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.jobs.Job;

/**
 * Whether the workspace has settled, in the one sense the whole plugin agrees on.
 * <p>
 * Eclipse builders finishing is not enough on its own: EDT's derived-data pipeline - BSL indexes,
 * validation markers, the semantic model the tools read - runs on after them, and that is the
 * readiness a caller actually needs. Both halves are asked here so that "indexing" cannot come to
 * mean two different things in two places; it was the health endpoint's private notion until the
 * update watcher needed the same answer.
 * </p>
 */
public final class WorkspacePhase
{
    /** Builders are idle and no open project is still computing its derived data. */
    public static final String READY = "ready"; //$NON-NLS-1$

    /** A build is running, or a project is still computing its derived data. */
    public static final String INDEXING = "indexing"; //$NON-NLS-1$

    /** The state could not be read. Deliberately distinct from {@link #READY}. */
    public static final String UNKNOWN = "unknown"; //$NON-NLS-1$

    private WorkspacePhase()
    {
    }

    /**
     * Reads the current phase.
     * <p>
     * A failure answers {@link #UNKNOWN} rather than {@link #READY}: a caller that has just
     * relaunched EDT waits for a settled workspace before trusting the semantic model, and handing
     * it a false "ready" would send it racing the build it was trying to avoid.
     * </p>
     *
     * @return {@link #READY}, {@link #INDEXING} or {@link #UNKNOWN}
     */
    public static String current()
    {
        try
        {
            if (Job.getJobManager().find(ResourcesPlugin.FAMILY_AUTO_BUILD).length > 0
                || Job.getJobManager().find(ResourcesPlugin.FAMILY_MANUAL_BUILD).length > 0)
            {
                return INDEXING;
            }
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
            {
                if (project.isOpen() && ProjectStateGuard.checkProjectState(project)
                    .getState() == ProjectStateGuard.ProjectState.BUILDING)
                {
                    return INDEXING;
                }
            }
            return READY;
        }
        catch (RuntimeException e)
        {
            return UNKNOWN;
        }
    }

    /**
     * Whether the workspace is known to be busy, for callers that want to stay out of the way.
     * <p>
     * Only a positive answer counts as busy. {@link #UNKNOWN} means the state could not be read,
     * and treating that as busy would let one unreadable workspace postpone a background task for
     * ever - a worse outcome than a background task running alongside an index nobody could see.
     * </p>
     *
     * @return <code>true</code> only when the phase is {@link #INDEXING}
     */
    public static boolean busy()
    {
        return busy(current());
    }

    /**
     * The rule behind {@link #busy()}, kept apart from the reading of it so the decision can be
     * checked against every phase instead of against whatever the workspace happened to be doing.
     *
     * @param phase one of the three phase names
     * @return <code>true</code> only for {@link #INDEXING}
     */
    static boolean busy(String phase)
    {
        return INDEXING.equals(phase);
    }
}
