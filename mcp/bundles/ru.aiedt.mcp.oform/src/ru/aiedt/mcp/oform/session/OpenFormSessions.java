/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.session;

import java.util.LinkedHashMap;
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
 */
public final class OpenFormSessions
{
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
    }

    private static final Map<String, Held> BY_FILE = new LinkedHashMap<>();

    private OpenFormSessions()
    {
        // static registry
    }

    /**
     * Opens the session of a container, the live one when another editor holds it.
     *
     * @param file the {@code Form.oform}
     * @return the session, with this editor counted in
     */
    public static synchronized FormEditingSession open(IFile file)
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
    public static synchronized void release(FormEditingSession session)
    {
        if (session == null)
        {
            return;
        }
        String key = keyOf(session.file());
        Held held = BY_FILE.get(key);
        if (held == null || held.session != session)
        {
            return;
        }
        held.editors--;
        if (held.editors > 0)
        {
            return;
        }
        held.session = null;
        dropIfIdle(key, held);
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

    private static Held held(IFile file)
    {
        return BY_FILE.computeIfAbsent(keyOf(file), key -> new Held());
    }

    private static String keyOf(IFile file)
    {
        IPath location = file.getLocation();
        return (location == null ? file.getFullPath() : location).toPortableString();
    }
}
