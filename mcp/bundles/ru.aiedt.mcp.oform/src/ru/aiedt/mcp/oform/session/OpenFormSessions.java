/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IPath;

/**
 * The open ordinary forms, one editing session per container: several editors of one
 * {@code Form.oform} share the session and its lock, the way text editors share one buffer, and
 * the session ends with the last editor that closes.
 * <p>
 * The registry keys a session by the file's location, hands out the file's lock every state
 * change of a session - an edit, a save, a reconciliation - goes under, and answers whether a
 * file's entries are held changed by a live session, which the write paths of the agent ask
 * before they replace a container. The lock comes with its registry entry pinned from the
 * lookup to the give-back, so a release that ends the last editor in that window cannot drop
 * the entry and leave a reopened session with a different lock of the same file.
 * </p>
 * <p>
 * A move re-registers that same session under the new path while both registry locks are held,
 * in lexicographic order of the full workspace paths, and before the {@link Notice} runs. A
 * removal that is not a move leaves the session registered and marked removed until its editors
 * release it. Code that holds a registry lock does not take a workspace scheduling rule and does
 * not wait for the UI thread.
 * </p>
 */
public final class OpenFormSessions
{
    /**
     * What stage 7's editor receives after a live session's container has been re-registered or
     * marked removed. The methods run after the registry locks are released. They must return
     * without waiting for the UI thread; the editor schedules its own redraw.
     */
    public interface Notice
    {
        /**
         * The session is now bound to {@code to}. {@code from} is the file it was bound to.
         *
         * @param session the same session object
         * @param from the previous file
         * @param to the file the session is bound to now
         */
        void moved(FormEditingSession session, IFile from, IFile to);

        /**
         * The container was removed and not moved. The session stays registered until its
         * editors release it, and later operations refuse.
         *
         * @param session the session
         * @param file the file that was removed
         */
        void removed(FormEditingSession session, IFile file);

        /** Content may have changed; receivers reconcile on their UI thread. */
        default void changed(FormEditingSession session, IFile file) { }

    }

    /**
     * Observes the order in which a move takes the two registry locks. Called while the first
     * lock is held and before the second is taken. The hook must not take a workspace rule and
     * must not lock the second file.
     */
    interface LockStep
    {
        /**
         * @param first the file whose lock is already held
         * @param second the file whose lock is about to be taken
         */
        void between(IFile first, IFile second);
    }

    /** How far {@link #rebind} got. */
    enum Move
    {
        /** This call retargeted the session. */
        MOVED,

        /** The session was already bound to the destination. */
        ALREADY,

        /** The session was not moved. */
        REFUSED
    }

    /** One open session and the file it was bound to when the snapshot was taken. */
    static final class Binding
    {
        private final FormEditingSession session;

        private final IFile file;

        /**
         * @param session the open session
         * @param file the file it was bound to
         */
        Binding(FormEditingSession session, IFile file)
        {
            this.session = session;
            this.file = file;
        }

        /** @return the session */
        FormEditingSession session()
        {
            return session;
        }

        /** @return the file at snapshot time */
        IFile file()
        {
            return file;
        }
    }

    /** A notice waiting until the caller asks for it, or ready to run now. */
    static final class PendingNotice
    {
        private final boolean moved;

        private final FormEditingSession session;

        private final IFile from;

        private final IFile to;

        /**
         * @param moved whether this is a move; a removal carries {@code false}
         * @param session the session
         * @param from the previous file
         * @param to the new file, or {@code null} for a removal
         */
        private PendingNotice(boolean moved, FormEditingSession session, IFile from, IFile to)
        {
            this.moved = moved;
            this.session = session;
            this.from = from;
            this.to = to;
        }

        /** @return a move notice */
        static PendingNotice moved(FormEditingSession session, IFile from, IFile to)
        {
            return new PendingNotice(true, session, from, to);
        }

        /** @return a removal notice */
        static PendingNotice removed(FormEditingSession session, IFile file)
        {
            return new PendingNotice(false, session, file, null);
        }

        /** @return a content-change notice */
        static PendingNotice changed(FormEditingSession session, IFile file)
        {
            return new PendingNotice(false, session, file, file);
        }

        /** Runs the notice. A null receiver does nothing. */
        void deliver(Notice notice)
        {
            if (notice == null)
            {
                return;
            }
            if (!moved && to != null)
            {
                notice.changed(session, from);
            }
            else if (moved)
            {
                notice.moved(session, from, to);
            }
            else
            {
                notice.removed(session, from);
            }
        }
    }

    /** One file's place in the registry: its session, its editor count, its lock and the pins that hold it. */
    private static final class Held
    {
        final ReentrantLock lock = new ReentrantLock();

        FormEditingSession session;

        int editors;

        int pins;
    }

    /**
     * The lock of one file's registry entry, with the entry pinned for its holder. The pin goes
     * on when the lock is looked up and comes off when the lock is given back, so the entry
     * outlives a release of the last editor that runs inside that window, and a session opened
     * beside it takes the same lock - one lock per file, whatever closes and reopens around it.
     */
    public static final class SessionLock
    {
        private final Held held;

        private final String key;

        /**
         * @param held the registry entry
         * @param key the key {@code held} is stored under
         */
        SessionLock(Held held, String key)
        {
            this.held = held;
            this.key = key;
        }

        /** Takes the lock, waiting for whoever holds it. */
        public void lock()
        {
            held.lock.lock();
        }

        /** Gives the lock back and takes the pin off; an entry nothing holds is dropped. */
        public void unlock()
        {
            held.lock.unlock();
            unpin(key, held);
        }

        /**
         * @param thread a thread
         * @return whether the thread waits for this lock
         */
        public boolean hasQueuedThread(Thread thread)
        {
            return held.lock.hasQueuedThread(thread);
        }

        /** @return whether any thread waits for this lock */
        public boolean hasQueuedThreads()
        {
            return held.lock.hasQueuedThreads();
        }

        /** @return whether the current thread already holds this file's lock */
        boolean heldByCurrentThread()
        {
            return held.lock.isHeldByCurrentThread();
        }
    }

    private static final Map<String, Held> BY_FILE = new LinkedHashMap<>();

    private static final List<PendingNotice> PENDING = new ArrayList<>();

    private static volatile Notice notice;

    private static final java.util.concurrent.CopyOnWriteArrayList<Notice> RECEIVERS =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Adds an independent editor receiver without replacing other editors. */
    public static void addNotice(Notice receiver) { RECEIVERS.addIfAbsent(receiver); }

    /** Removes a disposed editor receiver. */
    public static void removeNotice(Notice receiver) { RECEIVERS.remove(receiver); }

    private static volatile LockStep lockStep;

    private static volatile boolean deferNotices;

    /** Resource callbacks in progress. The listener stays installed while this is above zero. */
    private static int dispatchDepth;

    /** Bumped under the class lock whenever the listener's desired state may have changed. */
    private static int listenEpoch;

    /** The registry is static. */
    private OpenFormSessions()
    {
        // no instances
    }

    /**
     * Receives move and removal notices. {@code null} records nothing.
     *
     * @param notice the receiver, or {@code null}
     */
    public static void setNotice(Notice notice)
    {
        OpenFormSessions.notice = notice;
    }

    /**
     * Holds notices until {@link #deliverDeferredNotices()} instead of running them at the end
     * of the resource callback. Re-registration still happens before the notice is queued.
     * Production leaves this off.
     *
     * @param defer whether to queue notices
     */
    static void setDeferNotices(boolean defer)
    {
        deferNotices = defer;
    }

    /** Drops notices that were queued and not delivered. */
    static void discardDeferredNotices()
    {
        synchronized (OpenFormSessions.class)
        {
            PENDING.clear();
        }
    }

    /** Runs the notices queued while delivery was deferred, on this thread. */
    static void deliverDeferredNotices()
    {
        List<PendingNotice> batch;
        Notice current;
        synchronized (OpenFormSessions.class)
        {
            batch = new ArrayList<>(PENDING);
            PENDING.clear();
            current = notice;
        }
        deliver(current, batch);
    }

    /**
     * @param step the lock-order hook, or {@code null}
     */
    static void setLockStep(LockStep step)
    {
        lockStep = step;
    }

    /** @return whether the workspace listener is installed */
    static boolean listenerInstalled()
    {
        return SessionResourceListener.isInstalled();
    }

    /**
     * Opens the session of a container, the live one when another editor holds it.
     *
     * @param file the {@code Form.oform}
     * @return the session, with this editor counted in
     */
    public static FormEditingSession open(IFile file)
    {
        FormEditingSession session;
        synchronized (OpenFormSessions.class)
        {
            session = openLocked(file);
            listenEpoch++;
        }
        settleListener();
        return session;
    }

    /**
     * Counts one editor in and creates the session when the file has none.
     *
     * @param file the {@code Form.oform}
     * @return the session
     */
    private static FormEditingSession openLocked(IFile file)
    {
        Held held = held(file);
        if (held.session == null)
        {
            held.session = new FormEditingSession(file);
        }
        held.editors++;
        return held.session;
    }

    /**
     * Closes one editor of a session. The session ends when the last editor closes; the entry
     * stays while a thread holds or waits for its lock, so a state change in flight finishes on
     * it.
     *
     * @param session the session one editor of which closes
     */
    public static void release(FormEditingSession session)
    {
        FormEditingSession ending = endingOf(session);
        if (ending != null)
        {
            ending.disposeHistory();
        }
        settleListener();
    }

    /**
     * Counts one editor out. The session ends when the last editor closes; the history is
     * disposed by the caller after this returns, so the registry lock is not taken here and the
     * history is not disposed while this class lock is held.
     *
     * @param session the session one editor of which closes
     * @return the session when this was its last editor; {@code null} when it stays open
     */
    private static synchronized FormEditingSession endingOf(FormEditingSession session)
    {
        if (session == null)
        {
            return null;
        }
        listenEpoch++;
        String key = keyOf(session.file());
        Held held = BY_FILE.get(key);
        if (held == null || held.session != session)
        {
            key = null;
            held = null;
            for (Map.Entry<String, Held> entry : BY_FILE.entrySet())
            {
                if (entry.getValue().session == session)
                {
                    key = entry.getKey();
                    held = entry.getValue();
                    break;
                }
            }
            if (held == null)
            {
                return null;
            }
        }
        held.editors--;
        if (held.editors > 0)
        {
            return null;
        }
        FormEditingSession ending = held.session;
        held.session = null;
        dropIfIdle(key, held);
        return ending;
    }

    /** @param file the {@code Form.oform} @return whether a live session holds the file */
    public static synchronized boolean isHeld(IFile file)
    {
        Held held = BY_FILE.get(keyOf(file));
        return held != null && held.session != null;
    }

    /**
     * @param file the {@code Form.oform}
     * @return how many editors share the session of the file; {@code 0} when none is open
     */
    public static synchronized int editorsOf(IFile file)
    {
        Held held = BY_FILE.get(keyOf(file));
        return held == null || held.session == null ? 0 : held.editors;
    }

    /**
     * Whether a live session holds the file's entries changed - the entries a write path would
     * replace must not be replaced while a session holds them changed.
     *
     * @param file the {@code Form.oform}
     * @return whether the session of the file is dirty
     */
    public static synchronized boolean holdsChanges(IFile file)
    {
        Held held = BY_FILE.get(keyOf(file));
        return held != null && held.session != null && held.session.isDirty();
    }

    /**
     * Whether a live session other than the initiator holds one of the named entries changed.
     * The write paths ask this before they replace a container: what another session holds
     * changed must not be replaced by a write its editor has never seen, while the session's
     * own save - the initiator named - replaces its own edits.
     *
     * @param file the {@code Form.oform}
     * @param initiator the session id of the writer, or {@code null} when no session writes
     * @param form whether the write replaces the layout entry
     * @param module whether the write replaces the module entry
     * @return whether such a session holds one of those entries changed
     */
    public static synchronized boolean anotherHoldsChanged(IFile file, String initiator, boolean form,
        boolean module)
    {
        Held held = BY_FILE.get(keyOf(file));
        if (held == null || held.session == null)
        {
            return false;
        }
        if (initiator != null && initiator.equals(held.session.id()))
        {
            return false;
        }
        return (form && held.session.formChanged()) || (module && held.session.moduleChanged());
    }

    /**
     * The lock of a file's session with its registry entry pinned; every state change of the
     * session goes under it, and so does the write of a container through the write guard. The
     * entry stays in the registry until the lock's {@link SessionLock#unlock unlock}, so a
     * release that ends the last editor while the holder waits for the lock cannot drop the
     * entry and hand a different lock of the same file to a session opened beside it.
     *
     * @param file the {@code Form.oform}
     * @return the file's lock, pinned until it is unlocked
     */
    public static synchronized SessionLock lockOf(IFile file)
    {
        Held held = held(file);
        held.pins++;
        return new SessionLock(held, keyOf(file));
    }

    /**
     * Takes the pin off without giving the lock back. Used when a lookup only asked whether the
     * current thread already holds the lock.
     *
     * @param lock the lookup
     */
    static synchronized void releasePin(SessionLock lock)
    {
        unpin(lock.key, lock.held);
    }

    /**
     * @param file the file
     * @param thread a thread
     * @return whether the thread waits for that file's registry lock
     */
    static boolean waiterQueued(IFile file, Thread thread)
    {
        SessionLock lock = lockOf(file);
        try
        {
            return lock.hasQueuedThread(thread);
        }
        finally
        {
            releasePin(lock);
        }
    }

    /** @return the live sessions and the files they are bound to, in registry order */
    static synchronized List<Binding> liveBindings()
    {
        List<Binding> bindings = new ArrayList<>();
        for (Held held : BY_FILE.values())
        {
            if (held.session != null)
            {
                bindings.add(new Binding(held.session, held.session.file()));
            }
        }
        return bindings;
    }

    /**
     * @param session a session
     * @return whether the registry still maps the session's current file to that session
     */
    static synchronized boolean binds(FormEditingSession session)
    {
        if (session == null)
        {
            return false;
        }
        return boundTo(session, session.file());
    }

    /**
     * Moves one session from {@code from} to {@code to}. Both registry locks are taken in
     * lexicographic order of the full workspace paths. The same session object, its history and
     * its editor count stay; the file, the module, the FQN and the delivery owner are recomputed.
     * An empty registry entry created for the destination is removed. A destination that already
     * holds a different live session is left alone.
     *
     * @param session the session
     * @param from the file the caller believes the session is bound to
     * @param to the file at the new path
     * @return how far the move got
     */
    static Move rebind(FormEditingSession session, IFile from, IFile to)
    {
        if (session == null || from == null || to == null)
        {
            return Move.REFUSED;
        }
        String fromPath = from.getFullPath().toPortableString();
        String toPath = to.getFullPath().toPortableString();
        IFile first = fromPath.compareTo(toPath) <= 0 ? from : to;
        IFile second = first == from ? to : from;
        SessionLock lock1 = lockOf(first);
        SessionLock lock2 = lockOf(second);
        lock1.lock();
        try
        {
            LockStep step = lockStep;
            if (step != null)
            {
                step.between(first, second);
            }
            lock2.lock();
            try
            {
                synchronized (OpenFormSessions.class)
                {
                    return rebindLocked(session, from, to);
                }
            }
            finally
            {
                lock2.unlock();
            }
        }
        finally
        {
            lock1.unlock();
        }
    }

    /**
     * Map surgery for {@link #rebind}. Both file locks and the class lock are held.
     *
     * @param session the session
     * @param from the previous file
     * @param to the new file
     * @return how far the move got
     */
    private static Move rebindLocked(FormEditingSession session, IFile from, IFile to)
    {
        boolean atFrom = boundTo(session, from);
        boolean atTo = boundTo(session, to);
        if (!atFrom && atTo)
        {
            return Move.ALREADY;
        }
        if (!atFrom)
        {
            return Move.REFUSED;
        }
        String oldKey = keyOf(from);
        String newKey = keyOf(to);
        Held source = BY_FILE.get(oldKey);
        if (source == null || source.session != session)
        {
            return Move.REFUSED;
        }
        if (oldKey.equals(newKey))
        {
            session.retarget(to);
            return Move.MOVED;
        }
        Held dest = BY_FILE.get(newKey);
        if (!to.exists() || (dest != null && dest != source
            && (dest.session != null || dest.pins > 1)))
        {
            // Our lookup owns one destination pin. Every other pin must keep its exact Held;
            // replacing it would create two independent locks for the same destination.
            // Preserve both histories and texts, but make the unbound source refuse operations.
            session.markRemoved();
            return Move.REFUSED;
        }
        BY_FILE.remove(oldKey);
        if (dest != null && dest != source)
        {
            BY_FILE.remove(newKey);
        }
        BY_FILE.put(newKey, source);
        session.retarget(to);
        return Move.MOVED;
    }

    /**
     * Marks a session removed without taking it out of the registry. Editors still release it
     * by identity, and a later operation refuses instead of writing the missing path.
     *
     * @param session the session
     * @param file the file the caller believes it is bound to
     * @return whether this call marked it
     */
    static boolean detachRemoved(FormEditingSession session, IFile file)
    {
        SessionLock lock = lockOf(file);
        lock.lock();
        try
        {
            synchronized (OpenFormSessions.class)
            {
                if (!boundTo(session, file) || session.isRemoved())
                {
                    return false;
                }
                session.markRemoved();
                return true;
            }
        }
        finally
        {
            lock.unlock();
        }
    }

    /** Marks the start of a resource callback. Does not install or remove the listener. */
    static void beginDispatch()
    {
        synchronized (OpenFormSessions.class)
        {
            dispatchDepth++;
            listenEpoch++;
        }
    }

    /** Marks the end of a resource callback. Does not install or remove the listener. */
    static void endDispatch()
    {
        synchronized (OpenFormSessions.class)
        {
            dispatchDepth--;
            listenEpoch++;
        }
        settleListener();
    }

    /**
     * Queues or runs notices. Called after the registry locks are released and after
     * {@link #endDispatch()}.
     *
     * @param notices the notices of one callback
     */
    static void acceptNotices(List<PendingNotice> notices)
    {
        if (notices.isEmpty())
        {
            return;
        }
        Notice current;
        boolean defer;
        synchronized (OpenFormSessions.class)
        {
            defer = deferNotices;
            current = notice;
            if (defer)
            {
                PENDING.addAll(notices);
                return;
            }
        }
        deliver(current, notices);
    }

    /** Runs notices without the class lock held. */
    private static void deliver(Notice current, List<PendingNotice> notices)
    {
        List<Notice> receivers = new ArrayList<>(RECEIVERS);
        if (current != null) { receivers.add(current); }
        for (PendingNotice pending : notices)
        {
            for (Notice receiver : receivers)
            {
                try { pending.deliver(receiver); }
                catch (RuntimeException e)
                {
                    ru.aiedt.mcp.server.Activator.logError("Ordinary form resource notice failed", e); //$NON-NLS-1$
                }
            }
        }
    }

    /**
     * Installs the listener while a session is open or a callback is running, and removes it
     * otherwise. The workspace call is outside the class lock. A generation counter repeats the
     * check when an open and a release pass each other.
     */
    private static void settleListener()
    {
        while (true)
        {
            boolean want;
            int epoch;
            synchronized (OpenFormSessions.class)
            {
                want = liveCount() > 0 || dispatchDepth > 0;
                epoch = listenEpoch;
            }
            if (want)
            {
                SessionResourceListener.install();
            }
            else
            {
                SessionResourceListener.uninstall();
            }
            synchronized (OpenFormSessions.class)
            {
                boolean wantNow = liveCount() > 0 || dispatchDepth > 0;
                if (want == wantNow && epoch == listenEpoch)
                {
                    return;
                }
            }
        }
    }

    /** @return how many registry entries hold a session; the class lock is held */
    private static int liveCount()
    {
        int count = 0;
        for (Held held : BY_FILE.values())
        {
            if (held.session != null)
            {
                count++;
            }
        }
        return count;
    }

    /** @return whether {@code file}'s registry entry is this session; the class lock is held */
    private static boolean boundTo(FormEditingSession session, IFile file)
    {
        if (session == null || file == null)
        {
            return false;
        }
        Held held = BY_FILE.get(keyOf(file));
        return held != null && held.session == session;
    }

    /** Takes one pin off an entry and drops the entry when nothing holds it. */
    private static synchronized void unpin(String key, Held held)
    {
        held.pins--;
        dropIfIdle(key, held);
    }

    /**
     * Drops an entry that has no session, no editor and no pinned taker of its lock, whose lock
     * nobody waits for. An entry whose lock is held or waited for stays, so a state change in
     * flight finishes on it.
     */
    private static void dropIfIdle(String key, Held held)
    {
        if (held.session != null || held.editors > 0 || held.pins > 0 || BY_FILE.get(key) != held)
        {
            return;
        }
        if (held.lock.tryLock())
        {
            try
            {
                if (held.lock.getQueueLength() == 0)
                {
                    BY_FILE.remove(key);
                }
            }
            finally
            {
                held.lock.unlock();
            }
        }
    }

    /**
     * The registry entry of {@code file}, creating an empty one when none is stored.
     * The caller pins it before using the lock.
     *
     * @param file the container
     * @return the entry
     */
    private static Held held(IFile file)
    {
        return BY_FILE.computeIfAbsent(keyOf(file), key -> new Held());
    }

    /**
     * The registry key: the file's physical location, or its workspace path when the location
     * is gone.
     *
     * @param file the container
     * @return the key
     */
    private static String keyOf(IFile file)
    {
        IPath location = file.getLocation();
        return (location == null ? file.getFullPath() : location).toPortableString();
    }
}
