/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;

import ru.aiedt.mcp.server.Activator;

/**
 * Remembers which 1C debug session is suspended, and gives its threads and frames names an agent can
 * use twice.
 * <p>
 * The debug tools talk over HTTP: one call stops at a breakpoint, a later call asks what a variable
 * held. Nothing survives between them by itself - Eclipse's threads and stack frames are objects, not
 * ids, and an agent cannot hold an object. So this listens for suspend events, keeps the suspended
 * thread of each application, and hands out numbers that stand in for it.
 * </p>
 * <p>
 * Those numbers are deliberately short lived. When execution resumes, every thread id and frame
 * reference issued for that application is dropped, because the frames they named no longer exist -
 * the debugger has moved on. A later lookup finds nothing and the tool tells the agent to wait for a
 * break again, which is the truth. Ids are never reused, so a stale one can never quietly resolve to
 * somebody else's frame.
 * </p>
 * <p>
 * Everything is keyed by application id, which {@link LaunchConfigAccess#getApplicationIdFor(ILaunch)}
 * defines. A launch it does not recognise answers <code>null</code>, and a <code>null</code> is
 * ignored at every turn - that is how the Java and Ant launches in the same workspace stay out of this.
 * </p>
 */
public final class DebugSessionBook
{
    private static final DebugSessionBook INSTANCE = new DebugSessionBook();

    private final Map<String, SuspendSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<Long, IThread> threadsById = new ConcurrentHashMap<>();
    private final Map<Long, IStackFrame> framesById = new ConcurrentHashMap<>();

    /** Frame reference to the address it names, which is what survives a re-suspend. */
    private final Map<Long, FramePlace> framePlaces = new ConcurrentHashMap<>();
    private final Map<Long, String> threadOwners = new ConcurrentHashMap<>();
    private final Map<Long, String> frameOwners = new ConcurrentHashMap<>();

    /**
     * One counter for both threads and frames, so a thread id can never be mistaken for a frame
     * reference or the other way round.
     */
    private final AtomicLong ids = new AtomicLong();

    private final AtomicBoolean listening = new AtomicBoolean();
    private final IDebugEventSetListener listener = this::handleDebugEvents;

    private DebugSessionBook()
    {
        // singleton
    }

    /**
     * @return the registry; there is one per plugin instance
     */
    public static DebugSessionBook get()
    {
        return INSTANCE;
    }

    /**
     * Starts listening for debug events, if not already.
     * <p>
     * Safe to call from anywhere, as often as you like: the first call registers, the rest do nothing.
     * The debug tools call it before they do anything else, because a suspend that arrives before the
     * listener is installed is a suspend nobody hears.
     * </p>
     */
    public void ensureListenerRegistered()
    {
        if (!listening.compareAndSet(false, true))
        {
            return;
        }

        DebugPlugin plugin = DebugPlugin.getDefault();
        if (plugin == null)
        {
            // The platform is not up yet. Let the next caller try again.
            listening.set(false);
            return;
        }

        plugin.addDebugEventListener(listener);
        Activator.logInfo("DebugSessionBook: now listening for debug events"); //$NON-NLS-1$
    }

    /**
     * Stops listening and forgets everything.
     * <p>
     * Call this when the plugin stops. Eclipse's debug plugin outlives ours, and it holds every
     * listener handed to it by strong reference: a listener we never take back keeps this class, its
     * classloader and the whole old bundle alive after the bundle has stopped. This plugin updates
     * itself in place, so without this the listeners accumulate one per update, each one still
     * handling events for a plugin that is no longer there.
     * </p>
     * <p>
     * Idempotent, and it does not burn the bridge: a later
     * {@link #ensureListenerRegistered()} will register again.
     * </p>
     */
    public void shutdown()
    {
        if (listening.compareAndSet(true, false))
        {
            DebugPlugin plugin = DebugPlugin.getDefault();
            if (plugin != null)
            {
                plugin.removeDebugEventListener(listener);
            }
            Activator.logInfo("DebugSessionBook: stopped listening for debug events"); //$NON-NLS-1$
        }

        synchronized (this)
        {
            snapshots.clear();
            threadsById.clear();
            framesById.clear();
            framePlaces.clear();
            threadOwners.clear();
            frameOwners.clear();
            notifyAll();
        }
    }

    /**
     * Waits for an application to suspend.
     * <p>
     * Returns at once if it is suspended already. Otherwise blocks the calling thread - an MCP worker,
     * and there are only four of them, so the timeout a caller chooses is a claim on the pool.
     * </p>
     *
     * @param applicationId the application to wait for
     * @param timeoutMs how long to wait, in milliseconds
     * @return the suspended thread, or <code>null</code> if the wait ran out - which callers report as
     *         a timeout, not as an error
     * @throws InterruptedException if the wait is interrupted
     */
    public synchronized SuspendSnapshot waitForSuspend(String applicationId, long timeoutMs)
        throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + timeoutMs;

        SuspendSnapshot snapshot = getSnapshot(applicationId);
        if (snapshot != null)
        {
            return snapshot;
        }

        while (true)
        {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0)
            {
                return null;
            }

            // Releases the monitor, so the event thread can record a suspend and wake us.
            wait(remaining);

            snapshot = getSnapshot(applicationId);
            if (snapshot != null)
            {
                return snapshot;
            }
        }
    }

    /**
     * Names a stack frame so an agent can refer to it in a later call.
     * <p>
     * The frame's owning application is resolved as well, when it can be, because that is what lets the
     * reference be dropped when execution resumes.
     * </p>
     *
     * @param frame the frame to name; must not be <code>null</code>
     * @return the reference to give the client
     */
    public synchronized long registerFrame(IStackFrame frame)
    {
        long frameRef = ids.incrementAndGet();
        framesById.put(Long.valueOf(frameRef), frame);

        try
        {
            IThread owner = frame.getThread();
            framePlaces.put(Long.valueOf(frameRef), new FramePlace(owner, positionOf(owner, frame)));
            String applicationId = findApplicationIdFor(owner);
            if (applicationId != null)
            {
                frameOwners.put(Long.valueOf(frameRef), applicationId);
            }
        }
        catch (Exception e)
        {
            // Best effort. A frame whose application cannot be named is still usable while it lives.
        }
        return frameRef;
    }

    /**
     * Where a frame sits in its own thread's stack.
     *
     * @param thread the thread the frame belongs to.
     * @param frame the frame.
     * @return the index, or -1 when the stack does not carry it
     */
    private static int positionOf(IThread thread, IStackFrame frame)
    {
        try
        {
            IStackFrame[] stack = thread.getStackFrames();
            for (int i = 0; i < stack.length; i++)
            {
                if (stack[i] == frame)
                {
                    return i;
                }
            }
        }
        catch (Exception e)
        {
            // The stack is only readable while the thread is suspended; -1 says so.
        }
        return -1;
    }

    /**
     * @param appId an application id; may be <code>null</code>
     * @return whether that application is suspended right now
     */
    public boolean hasSnapshot(String appId)
    {
        return appId != null && snapshots.containsKey(appId);
    }

    /**
     * Records a suspend that happened without us hearing about it.
     * <p>
     * A thread can already be stopped before the listener goes in - a breakpoint hit by hand in EDT, or
     * a suspend between launching and asking. The wait tool looks for such threads and reports them
     * here, so that a wait can succeed on a break that has already happened rather than hanging for one
     * that will never come again.
     * </p>
     * <p>
     * Does nothing when the application is already known to be suspended: a real event has more to say
     * than a guess.
     * </p>
     *
     * @param appId the application; ignored when <code>null</code>
     * @param thread the suspended thread; ignored when <code>null</code>
     */
    public synchronized void injectSuspend(String appId, IThread thread)
    {
        if (appId == null || thread == null || snapshots.containsKey(appId))
        {
            return;
        }
        recordSuspend(appId, thread);
    }

    /**
     * Forgets that an application is suspended, without touching the thread and frame it issued.
     * <p>
     * What a stepping tool does before it steps: the old snapshot describes where the debugger
     * <em>was</em>, and a wait that returned it would report the step as finished before it started.
     * </p>
     *
     * @param appId the application; ignored when <code>null</code>
     */
    public synchronized void clearSnapshot(String appId)
    {
        if (appId == null)
        {
            return;
        }
        snapshots.remove(appId);
    }

    /**
     * @param threadId a thread id handed out earlier
     * @return the thread, or <code>null</code> when the id is stale - the session resumed and this
     *         thread's state is gone
     */
    public IThread getThread(long threadId)
    {
        return threadsById.get(Long.valueOf(threadId));
    }

    /**
     * @param frameRef a frame reference handed out earlier
     * @return the frame, or <code>null</code> when the reference is stale
     */
    public IStackFrame getFrame(long frameRef)
    {
        FramePlace place = framePlaces.get(Long.valueOf(frameRef));
        if (place != null && place.index >= 0)
        {
            try
            {
                IStackFrame[] stack = place.thread.getStackFrames();
                int live = pickIndex(stack.length, place.index);
                if (live >= 0)
                {
                    // The object the model holds NOW. The one captured at suspend time is a shell
                    // once the session has moved on, and reading its variables throws.
                    return stack[live];
                }
            }
            catch (Exception e)
            {
                // Thread gone or running: the reference is stale, and the caller is told so.
            }
            return null;
        }
        return framesById.get(Long.valueOf(frameRef));
    }

    /**
     * Whether a remembered position still exists in a stack of this size.
     *
     * @param liveCount how many frames the thread has now.
     * @param index the remembered position.
     * @return the index to read, or -1 when the stack no longer reaches it
     */
    static int pickIndex(int liveCount, int index)
    {
        return index >= 0 && index < liveCount ? index : -1;
    }

    /** A frame's address: the thread it belongs to and where it sits in that thread's stack. */
    private static final class FramePlace
    {
        private final IThread thread;
        private final int index;

        FramePlace(IThread thread, int index)
        {
            this.thread = thread;
            this.index = index;
        }
    }

    /**
     * @param applicationId the application; may be <code>null</code>
     * @return where it is suspended, or <code>null</code> when it is running
     */
    public SuspendSnapshot getSnapshot(String applicationId)
    {
        return applicationId == null ? null : snapshots.get(applicationId);
    }

    /**
     * What the registry is holding, for the status tool.
     *
     * @return the number of suspended applications, live threads and live frames
     */
    public Map<String, Object> snapshotInfo()
    {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("activeApplications", Integer.valueOf(snapshots.size())); //$NON-NLS-1$
        info.put("liveThreads", Integer.valueOf(threadsById.size())); //$NON-NLS-1$
        info.put("liveFrames", Integer.valueOf(framesById.size())); //$NON-NLS-1$
        return info;
    }

    /**
     * @param thread a suspended thread; may be <code>null</code>
     * @return the application it belongs to, or <code>null</code> when it is not an EDT debug session
     */
    public static String findApplicationIdFor(IThread thread)
    {
        if (thread == null)
        {
            return null;
        }

        try
        {
            return findApplicationIdFor(thread.getDebugTarget());
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * @param target a debug target; may be <code>null</code>
     * @return the application it belongs to, or <code>null</code>
     */
    public static String findApplicationIdFor(IDebugTarget target)
    {
        return target == null ? null : findApplicationIdFor(target.getLaunch());
    }

    /**
     * @param launch a launch; may be <code>null</code>
     * @return the application it belongs to, or <code>null</code>
     */
    public static String findApplicationIdFor(ILaunch launch)
    {
        return launch == null ? null : LaunchConfigAccess.getApplicationIdFor(launch);
    }

    /**
     * Finds the live debug target of an application.
     *
     * @param applicationId the application; <code>null</code> yields <code>null</code>
     * @return its first debug target that is still running, or <code>null</code>
     */
    public static IDebugTarget findActiveTarget(String applicationId)
    {
        if (applicationId == null)
        {
            return null;
        }

        ILaunchManager launchManager = LaunchConfigAccess.getLaunchManager();
        if (launchManager == null)
        {
            return null;
        }

        for (ILaunch launch : launchManager.getLaunches())
        {
            if (launch.isTerminated() || !applicationId.equals(LaunchConfigAccess.getApplicationIdFor(launch)))
            {
                continue;
            }

            for (IDebugTarget target : launch.getDebugTargets())
            {
                if (target != null && !target.isTerminated())
                {
                    return target;
                }
            }
        }
        return null;
    }

    /**
     * The application to use when the caller named none.
     * <p>
     * Only answers when the choice is unambiguous. With two sessions running, guessing which one an
     * agent meant would be worse than making it say.
     * </p>
     *
     * @return the one running application, or <code>null</code> when there are none or several
     */
    public static String findLoneActiveApplicationId()
    {
        return loneOf(activeApplicationIds());
    }

    /**
     * The one id, when there is exactly one.
     *
     * @param applicationIds the ids seen, already free of repeats.
     * @return the single id, or <code>null</code> when there is none or more than one
     */
    static String loneOf(List<String> applicationIds)
    {
        return applicationIds != null && applicationIds.size() == 1 ? applicationIds.get(0) : null;
    }

    /**
     * Every launch the auto-resolve looked at, as the refusal should describe them.
     * <p>
     * Without this a caller is told that the application could not be resolved and nothing else -
     * not how many launches there are, not which ids they carry, not whether two entries of one
     * launch are being counted as two applications. That is the material the next reading of this
     * defect needs.
     * </p>
     *
     * @return one entry per launch that has not terminated; empty, never <code>null</code>
     */
    public static List<Map<String, Object>> describeActiveLaunches()
    {
        List<Map<String, Object>> seen = new ArrayList<>();
        ILaunchManager launchManager = LaunchConfigAccess.getLaunchManager();
        if (launchManager == null)
        {
            return seen;
        }
        for (ILaunch launch : launchManager.getLaunches())
        {
            if (launch.isTerminated())
            {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            ILaunchConfiguration config = launch.getLaunchConfiguration();
            entry.put("launchConfiguration", config == null ? null : config.getName()); //$NON-NLS-1$
            entry.put("configurationType", config == null ? null //$NON-NLS-1$
                : LaunchConfigAccess.getConfigTypeId(config));
            entry.put("applicationId", LaunchConfigAccess.getApplicationIdFor(launch)); //$NON-NLS-1$
            entry.put("debugTargets", Integer.valueOf(launch.getDebugTargets().length)); //$NON-NLS-1$
            seen.add(entry);
        }
        return seen;
    }

    /**
     * @return the applications of every launch that is still running, without duplicates, in the order
     *         the launch manager reports them; empty, never <code>null</code>
     */
    private static List<String> activeApplicationIds()
    {
        List<String> applicationIds = new ArrayList<>();

        ILaunchManager launchManager = LaunchConfigAccess.getLaunchManager();
        if (launchManager == null)
        {
            return applicationIds;
        }

        for (ILaunch launch : launchManager.getLaunches())
        {
            if (launch.isTerminated())
            {
                continue;
            }

            String applicationId = LaunchConfigAccess.getApplicationIdFor(launch);
            if (applicationId != null && !applicationIds.contains(applicationId))
            {
                applicationIds.add(applicationId);
            }
        }
        return applicationIds;
    }

    /**
     * Eclipse's debug events, filtered down to the three that matter: a thread stopped, a thread went
     * on, or the session ended.
     * <p>
     * Runs on the debug event dispatch thread, not the UI thread.
     * </p>
     *
     * @param events the events in this set
     */
    private void handleDebugEvents(DebugEvent[] events)
    {
        if (events == null)
        {
            return;
        }

        for (DebugEvent event : events)
        {
            Object source = event.getSource();

            switch (event.getKind())
            {
            case DebugEvent.SUSPEND:
                if (source instanceof IThread)
                {
                    onSuspend((IThread)source);
                }
                break;

            case DebugEvent.RESUME:
                if (source instanceof IThread)
                {
                    forget(findApplicationIdFor((IThread)source));
                }
                break;

            case DebugEvent.TERMINATE:
                if (source instanceof IDebugTarget)
                {
                    forget(findApplicationIdFor((IDebugTarget)source));
                }
                else if (source instanceof IThread)
                {
                    forget(findApplicationIdFor((IThread)source));
                }
                else if (source instanceof ILaunch)
                {
                    forget(findApplicationIdFor((ILaunch)source));
                }
                break;

            default:
                break;
            }
        }
    }

    /**
     * @param thread the thread that just stopped
     */
    private synchronized void onSuspend(IThread thread)
    {
        String applicationId = findApplicationIdFor(thread);
        if (applicationId == null)
        {
            // Not an EDT debug session. Not ours.
            return;
        }
        recordSuspend(applicationId, thread);
    }

    /**
     * Notes a suspended thread and wakes whoever is waiting for it.
     *
     * @param applicationId the application, known to be non-<code>null</code>
     * @param thread the suspended thread
     */
    private synchronized void recordSuspend(String applicationId, IThread thread)
    {
        long threadId = ids.incrementAndGet();
        threadsById.put(Long.valueOf(threadId), thread);
        threadOwners.put(Long.valueOf(threadId), applicationId);
        snapshots.put(applicationId, new SuspendSnapshot(threadId, thread));

        notifyAll();
    }

    /**
     * Drops everything issued for an application, because the debugger has moved and none of it
     * describes anything any more.
     *
     * @param applicationId the application; ignored when <code>null</code>
     */
    private synchronized void forget(String applicationId)
    {
        if (applicationId == null)
        {
            return;
        }

        snapshots.remove(applicationId);
        evictOwnedBy(applicationId, threadOwners, threadsById);
        evictOwnedBy(applicationId, frameOwners, framesById);

        notifyAll();
    }

    /**
     * @param applicationId the application whose ids are to go
     * @param owners id to application
     * @param objects id to the object it named
     */
    private static void evictOwnedBy(String applicationId, Map<Long, String> owners, Map<Long, ?> objects)
    {
        for (Iterator<Map.Entry<Long, String>> it = owners.entrySet().iterator(); it.hasNext();)
        {
            Map.Entry<Long, String> owned = it.next();
            if (applicationId.equals(owned.getValue()))
            {
                objects.remove(owned.getKey());
                it.remove();
            }
        }
    }

    /**
     * Where an application stopped: the thread that suspended, and the id an agent can name it by.
     */
    public static final class SuspendSnapshot
    {
        /** The id handed out for {@link #thread}. */
        public final long threadId;

        /** The suspended thread itself, valid until the session resumes. */
        public final IThread thread;

        /**
         * @param threadId the id issued for the thread
         * @param thread the suspended thread
         */
        SuspendSnapshot(long threadId, IThread thread)
        {
            this.threadId = threadId;
            this.thread = thread;
        }
    }
}
