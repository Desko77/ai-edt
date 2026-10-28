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
 * before they replace a container.
 * </p>
 */
public final class OpenFormSessions
{
    /** One file's place in the registry: its session, its editor count and its lock. */
    private static final class Held
    {
        final ReentrantLock lock = new ReentrantLock();

        FormEditingSession session;

        int editors;
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
     * Closes one editor of a session. The session ends when the last editor closes; the lock
     * stays while a thread holds or waits for it, so a state change in flight finishes on it.
     *
     * @param session the session one editor of which closes
     */
    public static synchronized void release(FormEditingSession session)
    {
        if (session == null)
        {
            return;
        }
        Held held = BY_FILE.get(keyOf(session.file()));
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
        if (held.lock.tryLock())
        {
            try
            {
                if (held.lock.getQueueLength() == 0)
                {
                    BY_FILE.remove(keyOf(session.file()));
                }
            }
            finally
            {
                held.lock.unlock();
            }
        }
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

    /** The lock of a file's session; every state change of the session goes under it. */
    static synchronized ReentrantLock lockFor(IFile file)
    {
        return held(file).lock;
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
