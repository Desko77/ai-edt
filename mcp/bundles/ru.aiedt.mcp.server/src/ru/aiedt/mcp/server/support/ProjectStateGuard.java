/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;

import com._1c.g5.v8.derived.DerivedDataStatus;
import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.derived.IDerivedDataStatusListener;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;

import ru.aiedt.mcp.server.Activator;

/**
 * Answers the question most tools ask before they do anything else: is this project open, is it EDT's,
 * and has EDT finished thinking about it?
 * <p>
 * The alternative to asking is reading a model that is still being built, which does not fail - it
 * answers, with results that are wrong in ways nobody can see. So a tool asks first, and on anything
 * short of ready it tells the agent to come back rather than reporting half a truth.
 * </p>
 *
 * <pre>
 * String error = ProjectStateGuard.checkReadyOrError(projectName);
 * if (error != null)
 * {
 *     return ToolResult.error(error).toJson();
 * }
 * </pre>
 *
 * <p>
 * "Building" is usually a matter of seconds, and a tool that has just written to a project will
 * routinely find it so. {@link #checkReadyOrWait(String, long)} exists for that case: it waits the
 * build out once instead of handing the agent a retry it would only make anyway.
 * </p>
 */
public final class ProjectStateGuard
{
    /**
     * Appended to every message a tool shows. It reads oddly on a state that will never resolve on its
     * own - a closed project is not going to open itself - but the shape of the message is what agents
     * key on, and the prefix already says what is wrong.
     */
    private static final String RETRY_SUFFIX = ". Wait a moment and try again."; //$NON-NLS-1$

    /** What state a project is in, as far as a tool needs to care. */
    public enum ProjectState
    {
        /** Open, EDT's, and everything EDT derives from it has been computed. */
        READY("ready"), //$NON-NLS-1$

        /** EDT is still computing. This one passes on its own. */
        BUILDING("building"), //$NON-NLS-1$

        /** No such project, or it is closed, or EDT does not manage it. This one does not. */
        NOT_AVAILABLE("not_available"), //$NON-NLS-1$

        /** The state could not be determined, because EDT did not publish the service that knows. */
        UNKNOWN("unknown"); //$NON-NLS-1$

        private final String value;

        ProjectState(String value)
        {
            this.value = value;
        }

        /**
         * Returns the name this state goes by on the wire. Tools report it to agents, so it is part of
         * the tool output and not free to change.
         *
         * @return the lowercase name, never <code>null</code>
         */
        public String getValue()
        {
            return value;
        }
    }

    /** A state and the sentence that explains it. */
    public static class ProjectStateResult
    {
        private final ProjectState state;

        private final String message;

        private final boolean ready;

        /**
         * @param state the state
         * @param message the explanation, in the words a tool will show the agent
         */
        public ProjectStateResult(ProjectState state, String message)
        {
            this.state = state;
            this.message = message;
            this.ready = state == ProjectState.READY;
        }

        /**
         * @return the state
         */
        public ProjectState getState()
        {
            return state;
        }

        /**
         * @return the explanation - what is wrong, or that nothing is
         */
        public String getMessage()
        {
            return message;
        }

        /**
         * @return <code>true</code> when the project can be read
         */
        public boolean isReady()
        {
            return ready;
        }

        /**
         * @return the state's wire name
         */
        public String getStateValue()
        {
            return state.getValue();
        }
    }

    private ProjectStateGuard()
    {
        // utility
    }

    /**
     * Determines the state of a project.
     *
     * @param project the project; may be <code>null</code>
     * @return the state and an explanation, never <code>null</code>
     */
    public static ProjectStateResult checkProjectState(IProject project)
    {
        if (project == null)
        {
            return new ProjectStateResult(ProjectState.NOT_AVAILABLE, "Project handle is null"); //$NON-NLS-1$
        }
        if (!project.exists())
        {
            return new ProjectStateResult(ProjectState.NOT_AVAILABLE, "No such project in the workspace"); //$NON-NLS-1$
        }
        if (!project.isOpen())
        {
            return new ProjectStateResult(ProjectState.NOT_AVAILABLE, "The project is closed"); //$NON-NLS-1$
        }

        IDtProjectManager projectManager = dtProjectManager();
        if (projectManager == null)
        {
            // EDT itself is not answering. That is not the project's fault, and saying "not available"
            // would name the wrong culprit.
            return new ProjectStateResult(ProjectState.UNKNOWN, "DtProjectManager cannot be reached"); //$NON-NLS-1$
        }
        IDtProject dtProject = projectManager.getDtProject(project);
        if (dtProject == null)
        {
            return new ProjectStateResult(ProjectState.NOT_AVAILABLE, "Not an EDT project (the EDT nature is missing)"); //$NON-NLS-1$
        }
        return checkDtProjectState(dtProject);
    }

    /**
     * Determines the state of a project EDT already resolved.
     * <p>
     * Two questions, in this order. Is the derived-data pipeline running right now - if so, say what it
     * is doing, because "building" with no detail tells an agent nothing about how long to wait. And if
     * it is idle, has it actually finished - an idle pipeline with work outstanding means a computation
     * was abandoned, which reads the same to a tool: not yet.
     * </p>
     *
     * @param dtProject the EDT project; may be <code>null</code>
     * @return the state and an explanation, never <code>null</code>
     */
    public static ProjectStateResult checkDtProjectState(IDtProject dtProject)
    {
        if (dtProject == null)
        {
            return new ProjectStateResult(ProjectState.NOT_AVAILABLE, "DtProject handle is null"); //$NON-NLS-1$
        }

        IDerivedDataManagerProvider provider = derivedDataManagerProvider();
        if (provider == null)
        {
            Activator.logInfo("DerivedDataManagerProvider cannot be reached for " + dtProject.getName()); //$NON-NLS-1$
            return new ProjectStateResult(ProjectState.UNKNOWN, "Build state cannot be determined"); //$NON-NLS-1$
        }
        IDerivedDataManager ddManager = provider.get(dtProject);
        if (ddManager == null)
        {
            Activator.logInfo("DerivedDataManager cannot be reached for " + dtProject.getName()); //$NON-NLS-1$
            return new ProjectStateResult(ProjectState.UNKNOWN, "Build state cannot be determined"); //$NON-NLS-1$
        }

        if (!ddManager.isIdle())
        {
            return new ProjectStateResult(ProjectState.BUILDING,
                "Project is still building: " + describe(ddManager.getDerivedDataStatus())); //$NON-NLS-1$
        }
        if (!ddManager.isAllComputed())
        {
            return new ProjectStateResult(ProjectState.BUILDING,
                "Project build is still running (derived data not complete)"); //$NON-NLS-1$
        }
        return new ProjectStateResult(ProjectState.READY, "Project is ready for calls"); //$NON-NLS-1$
    }

    /**
     * Checks a project and turns anything short of ready into a message for the agent.
     *
     * @param project the project; may be <code>null</code>
     * @return <code>null</code> when the project can be read - the "no error" answer callers test for -
     *         or the reason it cannot, worded for the agent
     */
    public static String checkReadyOrError(IProject project)
    {
        ProjectStateResult result = checkProjectState(project);
        if (result.isReady())
        {
            return null;
        }
        return result.getMessage() + RETRY_SUFFIX;
    }

    /**
     * Checks a project by name.
     *
     * @param projectName the project name; <code>null</code> or empty means the call has no project to
     *            check, which is not an error - a tool that works across the workspace has nothing to
     *            gate on and passes straight through
     * @return <code>null</code> when there is nothing to report, or the reason the project cannot be
     *         read. A name that matches no project reports that the project does not exist
     */
    public static String checkReadyOrError(String projectName)
    {
        if (projectName == null || projectName.isEmpty())
        {
            return null;
        }
        return checkReadyOrError(findProject(projectName));
    }

    /**
     * Checks a project, waiting out a build in progress before giving up on it.
     * <p>
     * Only a build is waited for, and only once. A closed project or a missing service will not be
     * fixed by waiting, so those are reported straight away. After the wait the project is checked
     * once more - once, not in a loop: if it is still not ready, the honest answer is to say so and let
     * the agent decide, rather than to hold its call open indefinitely.
     * </p>
     *
     * @param project the project; may be <code>null</code>
     * @param timeoutMs how long to wait for a build, in milliseconds; zero or less does not wait
     * @return <code>null</code> when the project can be read, or the reason it cannot
     */
    public static String checkReadyOrWait(IProject project, long timeoutMs)
    {
        ProjectStateResult result = checkProjectState(project);
        if (result.isReady())
        {
            return null;
        }
        if (result.getState() != ProjectState.BUILDING || timeoutMs <= 0)
        {
            return result.getMessage() + RETRY_SUFFIX;
        }

        BuildTaskHelper.waitForBuildAndDerivedData(project, timeoutMs, new NullProgressMonitor());

        ProjectStateResult recheck = checkProjectState(project);
        if (recheck.isReady())
        {
            return null;
        }
        return recheck.getMessage() + RETRY_SUFFIX;
    }

    /**
     * Checks a project by name, waiting out a build in progress.
     *
     * @param projectName the project name; <code>null</code> or empty passes straight through, as in
     *            {@link #checkReadyOrError(String)}
     * @param timeoutMs how long to wait for a build, in milliseconds
     * @return <code>null</code> when the project can be read, or the reason it cannot
     */
    public static String checkReadyOrWait(String projectName, long timeoutMs)
    {
        if (projectName == null || projectName.isEmpty())
        {
            return null;
        }
        return checkReadyOrWait(findProject(projectName), timeoutMs);
    }

    /**
     * Watches one project's model for the length of an operation, and says afterwards whether it
     * moved.
     * <p>
     * <b>Checking readiness twice does not answer this.</b> A model can go ready, building, ready
     * again while an operation runs, and both checks would see ready - the operation still read a
     * model that was being rebuilt underneath it. What separates the two is not a second reading
     * but a record of every transition in between, which is what the derived-data manager
     * publishes to a listener.
     * </p>
     * <p>
     * A watch that could not be opened says so rather than saying nothing moved: not knowing and
     * knowing that nothing happened are different answers, and only one of them is safe to act on.
     * </p>
     */
    public static final class ModelWatch implements AutoCloseable
    {
        private final IDerivedDataManager manager;

        private final IDerivedDataStatusListener listener;

        private volatile boolean moved;

        private ModelWatch(IDerivedDataManager manager)
        {
            this.manager = manager;
            this.listener = status -> this.moved = true;
            manager.addStatusListener(this.listener);
        }

        /**
         * Whether the model published a status change since the watch opened.
         *
         * @return <code>true</code> if it moved
         */
        public boolean moved()
        {
            return this.moved;
        }

        /**
         * Forgets what was seen so far, so one watch can cover a second attempt.
         */
        public void reset()
        {
            this.moved = false;
        }

        @Override
        public void close()
        {
            try
            {
                this.manager.removeStatusListener(this.listener);
            }
            catch (RuntimeException e)
            {
                Activator.logError("Could not stop watching a project model", e); //$NON-NLS-1$
            }
        }
    }

    /**
     * Opens a watch on a project's model.
     *
     * @param project the project to watch; may be <code>null</code>
     * @return the watch, or <code>null</code> when the model cannot be watched - in which case a
     *         caller knows it cannot tell, rather than being told nothing moved
     */
    public static ModelWatch watchModel(IProject project)
    {
        try
        {
            IDtProjectManager projects = dtProjectManager();
            IDerivedDataManagerProvider provider = derivedDataManagerProvider();
            if (project == null || projects == null || provider == null)
            {
                return null;
            }
            IDtProject dtProject = projects.getDtProject(project);
            if (dtProject == null)
            {
                return null;
            }
            IDerivedDataManager manager = provider.get(dtProject);
            return manager != null ? new ModelWatch(manager) : null;
        }
        catch (RuntimeException e)
        {
            Activator.logError("Could not start watching a project model", e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Puts the build status into words, or says plainly that there are none to give.
     * <p>
     * The status class does not override toString, so asking it directly yields the default
     * identity form - a class name and a hash. That was reaching callers as the named reason a
     * refusal is supposed to carry, which is no reason at all, and reads like a leak of something
     * internal rather than an answer.
     * </p>
     *
     * @param status the build status; may be <code>null</code>
     * @return wording a caller can act on, never <code>null</code>
     */
    private static String describe(DerivedDataStatus status)
    {
        String said = status != null ? status.toString() : null;
        if (said == null || said.isEmpty() || said.equals(defaultToString(status)))
        {
            return "derived data is still being computed"; //$NON-NLS-1$
        }
        return said;
    }

    /**
     * Builds what toString would return had the class not overridden it.
     *
     * @param value the object to describe; never <code>null</code> when called
     * @return the default identity form
     */
    static String defaultToString(Object value)
    {
        return value.getClass().getName() + '@' + Integer.toHexString(value.hashCode());
    }

    /**
     * Returns the workspace project of that name. The workspace hands one back for any name at all, so
     * the result is a handle that may point at nothing - which the state check then reports as "does
     * not exist".
     *
     * @param projectName the project name
     * @return the project handle, never <code>null</code>
     */
    private static IProject findProject(String projectName)
    {
        return ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
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

    /**
     * @return EDT's derived data manager provider, or <code>null</code> when this EDT does not publish
     *         it or the plugin is not running
     */
    private static IDerivedDataManagerProvider derivedDataManagerProvider()
    {
        Activator activator = Activator.getDefault();
        return activator == null ? null : activator.getDerivedDataManagerProvider();
    }
}
