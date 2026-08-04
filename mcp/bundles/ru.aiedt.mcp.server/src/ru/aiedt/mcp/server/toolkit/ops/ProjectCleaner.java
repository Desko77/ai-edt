/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BuildTaskHelper;
import ru.aiedt.mcp.server.support.ProjectReadinessGate;
import ru.aiedt.mcp.server.support.ProjectReadinessGate.ProjectRestartWaiter;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.ProjectStateGuard;
import ru.aiedt.mcp.server.support.TextSuggest;

/**
 * Cleans an EDT project and triggers a full revalidation. Equivalent to the Eclipse
 * {@code Project -> Clean} action: refreshes files from disk, clears all validation markers, and
 * waits for EDT to stop and restart the project context and recompute its derived data.
 * <p>
 * Registering the lifecycle listeners <em>before</em> triggering the clean build is what lets this
 * tool see the STOPPED-then-STARTED restart the clean causes - register after, and the stop can
 * already have happened.
 * </p>
 */
public class ProjectCleaner
    implements IMcpTool
{
    /** The name the agent calls this tool by. */
    public static final String NAME = "clean_project"; //$NON-NLS-1$

    /** How long to wait, per project, for the context to stop and start again. */
    private static final long DEFAULT_LIFECYCLE_TIMEOUT_MS = 3 * 60 * 1000;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `diagnostics` `operation=clean_project`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Cleans an EDT project and kicks off a full revalidation. Refreshes files from disk, clears " //$NON-NLS-1$
            + "every validation marker, and waits until EDT finishes revalidating."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "The project to clean (optional; when omitted, every EDT project is cleaned)") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        // The readiness gate is for the single-project path only. Cleaning every project does not
        // pin a readiness check on any one of them.
        if (projectName != null && !projectName.isEmpty())
        {
            String notReady = ProjectStateGuard.checkReadyOrError(projectName);
            if (notReady != null)
            {
                return ToolResult.error(notReady).toJson();
            }
        }
        return cleanProject(projectName);
    }

    /**
     * Cleans one project (when {@code projectName} names it) or every open EDT project (when it is
     * null or empty), and waits for EDT to settle after.
     * <p>
     * The pipeline is five phases, in order: resolve the targets, arm the lifecycle listeners,
     * trigger the clean builds, await the restarts, wait for the derived data. The listener phase
     * precedes the build phase so the stop the clean causes is never missed.
     * </p>
     *
     * @param projectName the project to clean, or null/empty for every open EDT project
     * @return the result document, never {@code null}
     */
    public static String cleanProject(String projectName)
    {
        try
        {
            IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
            List<String> projectNamesList = new ArrayList<>();
            List<ProjectCleanInfo> projectsToClean = new ArrayList<>();

            // Phase A - resolve the projects to clean.
            if (projectName != null && !projectName.isEmpty())
            {
                IProject project = ProjectResolver.resolve(projectName);
                if (project == null)
                {
                    return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
                }
                IDtProject dtProject = dtProjectManager != null ? dtProjectManager.getDtProject(project) : null;
                projectsToClean.add(new ProjectCleanInfo(project, dtProject));
                projectNamesList.add(projectName);
            }
            else if (dtProjectManager != null)
            {
                for (IDtProject dtProject : dtProjectManager.getDtProjects())
                {
                    IProject project = dtProject.getWorkspaceProject();
                    if (project != null && project.isOpen())
                    {
                        projectsToClean.add(new ProjectCleanInfo(project, dtProject));
                        projectNamesList.add(project.getName());
                    }
                }
            }

            // Phase B - arm the lifecycle listeners BEFORE the builds, so the STOPPED event the
            // clean causes is caught rather than raced past.
            List<ProjectRestartWaiter> waiters = new ArrayList<>();
            for (ProjectCleanInfo info : projectsToClean)
            {
                if (info.dtProject != null)
                {
                    ProjectRestartWaiter waiter = ProjectReadinessGate.prepareForRestart(info.dtProject);
                    if (waiter != null)
                    {
                        waiters.add(waiter);
                    }
                }
            }

            // Phase C - trigger the clean builds.
            IProgressMonitor monitor = new NullProgressMonitor();
            try
            {
                for (ProjectCleanInfo info : projectsToClean)
                {
                    cleanSingleProject(info.project, monitor);
                }

                // Phase D - wait for each context to stop and start again.
                for (ProjectRestartWaiter waiter : waiters)
                {
                    waiter.await(DEFAULT_LIFECYCLE_TIMEOUT_MS);
                }
            }
            finally
            {
                // If a build threw, some armed waiters were never awaited; detach them so their
                // lifecycle listeners do not leak for the life of the workbench. await() cleans up
                // after itself, and cleanup() is idempotent, so this is safe on the success path too.
                for (ProjectRestartWaiter waiter : waiters)
                {
                    try
                    {
                        waiter.cleanup();
                    }
                    catch (Exception ignore)
                    {
                        // best-effort detach
                    }
                }
            }

            // Phase E - wait for the derived data to be recomputed.
            for (ProjectCleanInfo info : projectsToClean)
            {
                BuildTaskHelper.waitForDerivedData(info.project);
            }

            return ToolResult.success()
                .put("projectsCleaned", projectNamesList.size()) //$NON-NLS-1$
                .put("projects", projectNamesList) //$NON-NLS-1$
                .put("message", "Clean and revalidation finished.") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Project clean raised an exception", e); //$NON-NLS-1$
            return ToolResult.error(TextSuggest.safeMessage(e)).toJson();
        }
    }

    /**
     * Refreshes a project from disk and then runs its clean build, which is what makes EDT stop the
     * project context, throw away its derived data, and start over.
     *
     * @param project the project to clean
     * @param monitor the monitor to report progress to and to watch for cancellation
     * @throws CoreException if the refresh or the build fails
     */
    private static void cleanSingleProject(IProject project, IProgressMonitor monitor) throws CoreException
    {
        Activator.logInfo("Cleaning project via CLEAN_BUILD: " + project.getName()); //$NON-NLS-1$
        // Pick up anything that changed on disk outside EDT before clearing the markers.
        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
        // EDT's clean handler stops the project context, clears its data and reimports - which is
        // what triggers the STOPPED then STARTED lifecycle the waiter is listening for.
        project.build(IncrementalProjectBuilder.CLEAN_BUILD, monitor);
        Activator.logInfo("Clean build queued for: " + project.getName()); //$NON-NLS-1$
    }

    /**
     * A project to clean, paired with its EDT handle (which may be null when EDT does not manage it).
     */
    private static class ProjectCleanInfo
    {
        /** The workspace project. */
        final IProject project;

        /** The EDT project, or {@code null} when EDT does not manage {@link #project}. */
        final IDtProject dtProject;

        ProjectCleanInfo(IProject project, IDtProject dtProject)
        {
            this.project = project;
            this.dtProject = dtProject;
        }
    }
}
