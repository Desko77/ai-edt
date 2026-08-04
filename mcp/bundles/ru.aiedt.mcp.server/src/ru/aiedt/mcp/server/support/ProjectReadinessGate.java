/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com._1c.g5.v8.dt.core.lifecycle.ProjectContext;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.lifecycle.ILifecycleContext;
import com._1c.g5.v8.dt.lifecycle.IServiceContextLifecycleListener;
import com._1c.g5.v8.dt.lifecycle.IServicesOrchestrator;
import com._1c.g5.v8.dt.lifecycle.ServiceState;

import ru.aiedt.mcp.server.Activator;

/**
 * Waits on the EDT project context - the services EDT starts and stops around a project.
 * <p>
 * It exists for one race. A clean build does not just rebuild a project: it stops the project's
 * context and starts a new one. Register a listener after asking for the build and the stop may
 * already have happened - so the wait either hangs forever, or, worse, returns at once because it saw
 * a start that belonged to the old context. The listener has to be live <em>before</em> the build is
 * triggered, which is why registering and waiting are two separate calls here:
 * </p>
 *
 * <pre>
 * ProjectRestartWaiter waiter = ProjectReadinessGate.prepareForRestart(dtProject);
 * if (waiter != null)
 * {
 *     project.build(IncrementalProjectBuilder.CLEAN_BUILD, monitor);
 *     waiter.await(timeoutMs);
 * }
 * </pre>
 *
 * <p>
 * A <code>null</code> waiter means the wait cannot be made - EDT is not publishing the orchestrator -
 * and the caller carries on without it. That is a degraded path, not a failure.
 * </p>
 */
public final class ProjectReadinessGate
{
    private ProjectReadinessGate()
    {
        // utility
    }

    /**
     * Starts listening for a project's context to stop and start again.
     * <p>
     * The listener is live when this returns. Trigger the restart <em>after</em> calling this, then
     * await the waiter.
     * </p>
     *
     * @param dtProject the project whose context is about to be restarted; may be <code>null</code>
     * @return a waiter to await once the restart has been triggered, or <code>null</code> when the wait
     *         cannot be made at all - there is no project, or EDT is not publishing the orchestrator.
     *         Callers must handle <code>null</code> by going on without the wait
     */
    public static ProjectRestartWaiter prepareForRestart(IDtProject dtProject)
    {
        if (dtProject == null)
        {
            Activator.logInfo("No DtProject was supplied for the lifecycle wait"); //$NON-NLS-1$
            return null;
        }
        IServicesOrchestrator orchestrator = servicesOrchestrator();
        if (orchestrator == null)
        {
            Activator.logInfo("No IServicesOrchestrator is published, skipping the lifecycle wait"); //$NON-NLS-1$
            return null;
        }
        Activator.logInfo("Arming the lifecycle listener ahead of a project restart: " + dtProject.getName()); //$NON-NLS-1$
        return new ProjectRestartWaiter(orchestrator, dtProject.getName());
    }

    /**
     * Waits for a project's context to report that it has started.
     * <p>
     * This one watches for a start that is already on its way; it does not care whether a stop came
     * first. It also only sees what happens from here on: a project whose context is already up and
     * quiet emits nothing, so the call waits out the whole timeout and answers <code>false</code>.
     * That is why it belongs immediately after an operation known to restart the context, and why
     * <code>false</code> means "stopped waiting", not "the project failed to start".
     * </p>
     *
     * @param dtProject the project; may be <code>null</code>
     * @param timeoutMs how long to wait, in milliseconds, counted from this call
     * @return <code>true</code> when the context reported a start; <code>false</code> when the wait ran
     *         out, was interrupted, or could not be made
     */
    public static boolean waitForProjectStarted(IDtProject dtProject, long timeoutMs)
    {
        if (dtProject == null)
        {
            Activator.logInfo("No DtProject was supplied for the lifecycle wait"); //$NON-NLS-1$
            return false;
        }
        IServicesOrchestrator orchestrator = servicesOrchestrator();
        if (orchestrator == null)
        {
            Activator.logInfo("No IServicesOrchestrator is published, skipping the lifecycle wait"); //$NON-NLS-1$
            return false;
        }

        String projectName = dtProject.getName();
        Activator.logInfo("Waiting for the project to reach STARTED: " + projectName); //$NON-NLS-1$

        CountDownLatch started = new CountDownLatch(1);
        IServiceContextLifecycleListener listener = (context, state) -> {
            if (!isContextOf(context, projectName))
            {
                return;
            }
            Activator.logInfo("Lifecycle callback for " + projectName + ": " + state); //$NON-NLS-1$ //$NON-NLS-2$
            if (state == ServiceState.STARTED)
            {
                started.countDown();
            }
        };
        orchestrator.addListener(listener);
        try
        {
            if (started.await(timeoutMs, TimeUnit.MILLISECONDS))
            {
                Activator.logInfo("Project reports STARTED: " + projectName); //$NON-NLS-1$
                return true;
            }
            Activator.logInfo("Gave up waiting for STARTED: " + projectName); //$NON-NLS-1$
            return false;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            Activator.logInfo("Lifecycle wait was interrupted for: " + projectName); //$NON-NLS-1$
            return false;
        }
        finally
        {
            removeListener(orchestrator, listener, projectName);
        }
    }

    /**
     * Tells whether a lifecycle callback is about the project we are waiting on.
     * <p>
     * Matching is by name, not by identity: a restart hands out a new {@link IDtProject} for the same
     * project, so the instance we started with is not the instance the start is reported for.
     * </p>
     *
     * @param context the context the callback carries
     * @param projectName the name of the project being waited on
     * @return <code>true</code> when the callback is about that project
     */
    private static boolean isContextOf(ILifecycleContext context, String projectName)
    {
        if (!(context instanceof ProjectContext))
        {
            return false;
        }
        IDtProject project = ((ProjectContext)context).getProject();
        return project != null && projectName.equals(project.getName());
    }

    /**
     * Deregisters a listener. A listener left behind is a leak that outlives the tool call that made
     * it, so failure to remove one is reported but never propagated - it is not the caller's problem to
     * solve.
     *
     * @param orchestrator the orchestrator the listener is registered with
     * @param listener the listener to remove
     * @param projectName the project the listener was watching, for the log
     */
    private static void removeListener(IServicesOrchestrator orchestrator, IServiceContextLifecycleListener listener,
        String projectName)
    {
        try
        {
            orchestrator.removeListener(listener);
            Activator.logInfo("Lifecycle listener deregistered for: " + projectName); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Failed to remove lifecycle listener", e); //$NON-NLS-1$
        }
    }

    /**
     * @return EDT's services orchestrator, or <code>null</code> when this EDT does not publish it or the
     *         plugin is not running
     */
    private static IServicesOrchestrator servicesOrchestrator()
    {
        Activator activator = Activator.getDefault();
        return activator == null ? null : activator.getServicesOrchestrator();
    }

    /**
     * A listener, already registered, waiting for one project's context to stop and then start again.
     * <p>
     * Obtainable only from {@link ProjectReadinessGate#prepareForRestart(IDtProject)}, because an instance
     * that has not registered its listener would be useless and one that registered it late would be
     * worse than useless. Single use: {@link #await(long)} deregisters the listener before it returns.
     * </p>
     */
    public static class ProjectRestartWaiter
    {
        private final IServicesOrchestrator orchestrator;

        private final String projectName;

        /**
         * When the listener went live. The timeout is measured from here, not from the call to
         * {@link #await(long)} - see there.
         */
        private final long registrationTime;

        private final CountDownLatch stoppedLatch = new CountDownLatch(1);

        private final CountDownLatch startedLatch = new CountDownLatch(1);

        private final IServiceContextLifecycleListener listener;

        /** Written by the EDT thread that reports the stop, read by the waiting thread. */
        private volatile boolean stoppedSeen;

        /**
         * Registers the listener. Package-private: instances come from
         * {@link ProjectReadinessGate#prepareForRestart(IDtProject)} and from nowhere else.
         *
         * @param orchestrator the orchestrator to listen to, never <code>null</code>
         * @param projectName the name of the project to watch, never <code>null</code>
         */
        ProjectRestartWaiter(IServicesOrchestrator orchestrator, String projectName)
        {
            this.orchestrator = orchestrator;
            this.projectName = projectName;
            this.registrationTime = System.currentTimeMillis();
            this.listener = this::onContextStateChanged;
            orchestrator.addListener(this.listener);
            Activator.logInfo("Lifecycle listener armed for: " + projectName); //$NON-NLS-1$
        }

        /**
         * Waits for the restart: first the context stopping, then a new one starting.
         * <p>
         * The timeout is the budget for the whole restart and it is counted from the moment the
         * listener was registered - not from this call. A caller that arms ten waiters and then triggers
         * ten clean builds has already spent some of the tenth one's budget by the time it gets there,
         * and that is the point: the budget bounds the wall clock the batch may take, not the time each
         * individual wait may sit idle.
         * </p>
         * <p>
         * The listener is deregistered before this returns, whatever the outcome, so a waiter serves
         * exactly one restart.
         * </p>
         *
         * @param timeoutMs the total budget, in milliseconds, from the moment the listener was
         *            registered
         * @return <code>true</code> when the context stopped and started again within the budget;
         *         <code>false</code> when it ran out or the wait was interrupted. A caller may ignore
         *         this: a wait that timed out has still waited, and whatever comes next will find the
         *         project in whatever state it reached
         */
        public boolean await(long timeoutMs)
        {
            try
            {
                if (!stoppedLatch.await(remainingBudget(timeoutMs), TimeUnit.MILLISECONDS))
                {
                    Activator.logInfo("Gave up waiting for STOPPED: " + projectName); //$NON-NLS-1$
                    return false;
                }
                if (!startedLatch.await(remainingBudget(timeoutMs), TimeUnit.MILLISECONDS))
                {
                    Activator.logInfo("Gave up waiting for the restart's STARTED: " + projectName); //$NON-NLS-1$
                    return false;
                }
                Activator.logInfo("Project lifecycle restart finished: " + projectName); //$NON-NLS-1$
                return true;
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                Activator.logInfo("Lifecycle wait was interrupted for: " + projectName); //$NON-NLS-1$
                return false;
            }
            finally
            {
                cleanup();
            }
        }

        /**
         * Deregisters the listener.
         * <p>
         * {@link #await(long)} always calls this, so there is normally nothing to do by hand. It is
         * public for the caller who arms a waiter and then decides not to await it - without this, that
         * listener would stay registered for the life of the workbench.
         * </p>
         */
        public void cleanup()
        {
            ProjectReadinessGate.removeListener(orchestrator, listener, projectName);
        }

        /**
         * Returns what is left of the budget.
         *
         * @param timeoutMs the total budget, from registration
         * @return the milliseconds left, never negative - a spent budget means "look, do not wait",
         *         which is what a zero timeout does to a latch
         */
        private long remainingBudget(long timeoutMs)
        {
            long elapsed = System.currentTimeMillis() - registrationTime;
            return Math.max(0L, timeoutMs - elapsed);
        }

        /**
         * Handles one lifecycle callback.
         * <p>
         * A start seen before any stop is ignored, and that guard is the reason this class exists: the
         * context announces starts for reasons of its own, and taking one of those for the restart we
         * asked for would let {@link #await(long)} return before the restart had even begun.
         * </p>
         *
         * @param context the context that changed state
         * @param state the state it changed to
         */
        private void onContextStateChanged(ILifecycleContext context, ServiceState state)
        {
            if (!isContextOf(context, projectName))
            {
                return;
            }
            Activator.logInfo("Lifecycle callback for " + projectName + ": " + state); //$NON-NLS-1$ //$NON-NLS-2$

            if (state == ServiceState.STOPPED)
            {
                stoppedSeen = true;
                stoppedLatch.countDown();
            }
            else if (state == ServiceState.STARTED && stoppedSeen)
            {
                startedLatch.countDown();
            }
        }
    }
}
