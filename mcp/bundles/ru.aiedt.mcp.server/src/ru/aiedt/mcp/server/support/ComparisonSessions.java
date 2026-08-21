/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ru.aiedt.mcp.server.Activator;

/**
 * Keeps comparisons open between calls, so asking a second question about an update does not mean
 * comparing the two configurations again.
 * <p>
 * <b>Why this is worth a registry.</b> A comparison of a real configuration takes minutes and
 * produces tens of thousands of changed objects. Walking that set a page at a time - which is the
 * only way to enumerate what to protect - would otherwise cost a full comparison per page.
 * </p>
 * <p>
 * <b>Only comparisons this server opened are ever reused.</b> The environment can list every
 * comparison in the workspace, including one a person opened in the EDT editor, and adopting one of
 * those would mean applying decisions to somebody's work or closing it under them. So nothing is
 * adopted: a session belongs to this registry only if this registry created it. That is a stricter
 * rule than matching sides, and it cannot be defeated by two comparisons happening to look alike.
 * </p>
 */
public final class ComparisonSessions
{
    /**
     * How long a session stays usable without being touched.
     * <p>
     * A comparison holds memory and a lock on the environment's comparison store, so one left
     * behind by a caller that walked away has to expire. Twenty minutes is long enough to page
     * through tens of thousands of objects and decide about them, and short enough that a forgotten
     * session is gone before the next working session starts.
     * </p>
     */
    static final long IDLE_LIMIT_MS = 20L * 60L * 1000L;

    /**
     * How many sessions may be open at once.
     * <p>
     * Each one holds a comparison of two whole configurations. Without a ceiling a caller looping
     * over projects would open one per project and exhaust the heap before anything refused.
     * </p>
     */
    static final int MAX_SESSIONS = 4;

    /** One comparison this server opened and may hand back. */
    public static final class Session
    {
        /** How a caller names this session. */
        public final String key;

        /** What was compared, so a caller can see the session is the one they meant. */
        public final String fingerprint;

        /** The environment's handle on the comparison. */
        public final Object handle;

        /** When it was last used, for expiry. */
        long touchedAt;

        /** How many objects the comparison found changed, so a page need not recount. */
        public int objectsChanged;

        Session(String key, String fingerprint, Object handle, long now)
        {
            this.key = key;
            this.fingerprint = fingerprint;
            this.handle = handle;
            this.touchedAt = now;
        }
    }

    /** Sessions by key, oldest first so expiry and eviction walk in order. */
    private static final Map<String, Session> OPEN = new LinkedHashMap<>();

    /** Counter behind the keys. A key says nothing about what it opens. */
    private static long counter;

    /**
     * Sessions dropped by expiry or eviction, waiting to be closed.
     * <p>
     * Dropping and closing are separate on purpose. Closing means calling the environment, and a
     * registry that did that while holding its own lock would block every other caller behind an
     * environment call. So expiry forgets, and whoever next talks to the environment drains this
     * and closes what was forgotten - otherwise a comparison outlives its session and keeps the
     * environment's comparison store busy, which is how an EDT ends up unable to close.
     * </p>
     */
    private static final List<Session> DROPPED = new ArrayList<>();

    private ComparisonSessions()
    {
        // Static registry.
    }

    /**
     * Describes what a comparison is of, for matching and for the answer.
     *
     * @param projectName our side.
     * @param otherPath the delivery compared against.
     * @param ancestorPath the delivery both came from; may be <code>null</code>.
     * @return a stable description of the three sides
     */
    public static String fingerprintOf(String projectName, String otherPath, String ancestorPath)
    {
        return projectName + " | " + otherPath + " | " //$NON-NLS-1$ //$NON-NLS-2$
            + (ancestorPath == null || ancestorPath.trim().isEmpty() ? "(two-sided)" //$NON-NLS-1$
                : ancestorPath);
    }

    /**
     * Finds an open session for the same three sides.
     *
     * @param fingerprint what the caller wants compared.
     * @param now the current time in milliseconds.
     * @return the session, or <code>null</code> when nothing open matches
     */
    public static synchronized Session findByFingerprint(String fingerprint, long now)
    {
        expire(now);
        for (Session session : OPEN.values())
        {
            if (session.fingerprint.equals(fingerprint))
            {
                session.touchedAt = now;
                return session;
            }
        }
        return null;
    }

    /**
     * Finds a session by the key a caller was given.
     *
     * @param key the key.
     * @param now the current time in milliseconds.
     * @return the session, or <code>null</code> when it expired or never existed
     */
    public static synchronized Session findByKey(String key, long now)
    {
        expire(now);
        Session session = key == null ? null : OPEN.get(key);
        if (session != null)
        {
            session.touchedAt = now;
        }
        return session;
    }

    /**
     * Registers a comparison this server has just opened.
     *
     * @param fingerprint what it compares.
     * @param handle the environment's handle.
     * @param now the current time in milliseconds.
     * @return the session, which carries the key to hand back
     */
    public static synchronized Session open(String fingerprint, Object handle, long now)
    {
        expire(now);
        String key = "cmp-" + Long.toHexString(++counter) //$NON-NLS-1$
            + Long.toHexString(now & 0xffffffL);
        Session session = new Session(key, fingerprint, handle, now);
        OPEN.put(key, session);
        evictOldest();
        return session;
    }

    /**
     * Forgets a session, returning its handle so the caller can close it.
     *
     * @param key the key.
     * @return the session that was forgotten, or <code>null</code> when there was none
     */
    public static synchronized Session close(String key)
    {
        return key == null ? null : OPEN.remove(key);
    }

    /**
     * Forgets every session, returning their handles.
     * <p>
     * For plugin shutdown: a comparison left open outlives the server that opened it and keeps the
     * environment's comparison store busy, which is how an EDT ends up unable to close.
     * </p>
     *
     * @return the sessions that were open
     */
    public static synchronized List<Session> closeAll()
    {
        List<Session> were = new ArrayList<>(OPEN.values());
        OPEN.clear();
        return were;
    }

    /**
     * Lists what is open, for a caller that lost track.
     *
     * @param now the current time in milliseconds.
     * @return the open sessions, oldest first
     */
    public static synchronized List<Session> list(long now)
    {
        expire(now);
        return new ArrayList<>(OPEN.values());
    }

    /**
     * Drops sessions nobody has touched within the idle limit.
     * <p>
     * The handles are not closed here. Closing one means talking to the environment, and a
     * registry that did that while holding its own lock would block every other caller behind an
     * environment call. Forgotten sessions are dropped; the comparison behind them is left to the
     * environment, which discards a comparison with its session.
     * </p>
     *
     * @param now the current time in milliseconds.
     */
    private static void expire(long now)
    {
        Iterator<Session> sessions = OPEN.values().iterator();
        while (sessions.hasNext())
        {
            Session session = sessions.next();
            if (now - session.touchedAt > IDLE_LIMIT_MS)
            {
                Activator.logDebug("comparison session " + session.key //$NON-NLS-1$
                    + " expired after " + IDLE_LIMIT_MS / 60000 + " idle minutes"); //$NON-NLS-1$ //$NON-NLS-2$
                sessions.remove();
                DROPPED.add(session);
            }
        }
    }

    /**
     * Drops the oldest session when there are more than the ceiling allows.
     *
     * @return the session dropped, or <code>null</code> when none needed to be
     */
    private static Session evictOldest()
    {
        if (OPEN.size() <= MAX_SESSIONS)
        {
            return null;
        }
        Iterator<Session> sessions = OPEN.values().iterator();
        Session oldest = sessions.next();
        sessions.remove();
        DROPPED.add(oldest);
        Activator.logDebug("comparison session " + oldest.key //$NON-NLS-1$
            + " dropped: more than " + MAX_SESSIONS + " were open"); //$NON-NLS-1$ //$NON-NLS-2$
        return oldest;
    }

    /**
     * Takes the sessions dropped since the last call, for the caller to close.
     *
     * @return the dropped sessions, empty when none were
     */
    public static synchronized List<Session> drainDropped()
    {
        if (DROPPED.isEmpty())
        {
            return Collections.emptyList();
        }
        List<Session> taken = new ArrayList<>(DROPPED);
        DROPPED.clear();
        return taken;
    }

    /**
     * Empties the registry without touching the environment.
     * <p>
     * For tests, which have no environment to touch.
     * </p>
     */
    static synchronized void forgetEverything()
    {
        OPEN.clear();
        DROPPED.clear();
        counter = 0;
    }
}
