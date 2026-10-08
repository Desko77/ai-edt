/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.ui;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers, per project, which object names the marker filter last decided to show.
 * <p>
 * Safe for the UI thread and the change-notification threads at once: reads and writes arrive
 * from either side, so the map underneath is concurrent and neither side sees a half-written
 * entry.
 * </p>
 * <p>
 * The filter recomputes that set only when the cache has no entry. Applying the filter again clears
 * it. A change to the markers themselves has to clear it too, or the Navigator keeps the previous
 * answer until the user opens the filter dialog once more.
 * </p>
 *
 * @param <K> the project key
 */
public final class MarkerMatchCache<K>
{
    // Concurrent on purpose: the Navigator reads it on the UI thread while marker changes
    // invalidate it from the thread the change arrived on - an HTTP call, a resource event, a
    // refactoring - and a plain HashMap between those two corrupts or loses entries.
    private final Map<K, Set<String>> matches = new ConcurrentHashMap<>();

    /**
     * Returns the cached object names for a project.
     *
     * @param key the project
     * @return the cached names, or <code>null</code> when this project has not been computed
     */
    public Set<String> get(K key)
    {
        return matches.get(key);
    }

    /**
     * Stores the object names computed for a project.
     *
     * @param key the project
     * @param value the matching object names
     */
    public void put(K key, Set<String> value)
    {
        matches.put(key, value);
    }

    /**
     * Forgets one project, so the next question computes its matches again.
     *
     * @param key the project whose markers or assignments changed
     */
    public void invalidate(K key)
    {
        matches.remove(key);
    }

    /**
     * Forgets every project. Used when the filter is applied again or switched off.
     */
    public void invalidateAll()
    {
        matches.clear();
    }

    /**
     * Tells whether a project still has a cached answer.
     *
     * @param key the project
     * @return <code>true</code> when {@link #get} would not return <code>null</code>
     */
    public boolean contains(K key)
    {
        return matches.containsKey(key);
    }
}
