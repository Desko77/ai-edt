/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;

/**
 * The whole marker file for one project: the ordered list of defined markers and the map of which markers are
 * assigned to which objects.
 * <p>
 * The marker list carries the order the user arranged, which is also the order the keyboard shortcuts
 * follow. Assignments map an object's fully qualified name to the names of the markers on it. The names,
 * not the {@link Marker} objects, are stored, and they are stored in a {@link List} rather than a
 * {@link Set} on purpose: a set would make SnakeYAML write {@code !!set} markers into the file, so the
 * list is kept unique by hand instead.
 * </p>
 * <p>
 * This is a SnakeYAML bean. Its two properties serialize alphabetically, so {@code assignments}
 * always precedes {@code markers} in the file.
 * </p>
 */
public class MarkerStore
{
    private List<Marker> markers;

    private Map<String, List<String>> assignments;

    /**
     * Creates an empty storage with no markers and no assignments.
     */
    public MarkerStore()
    {
        this.markers = new ArrayList<>();
        this.assignments = new HashMap<>();
    }

    /**
     * Returns the live list of defined markers, in user order.
     *
     * @return the markers, never <code>null</code>
     */
    public List<Marker> getTags()
    {
        return markers;
    }

    /**
     * Replaces the marker list, treating <code>null</code> as an empty list.
     *
     * @param markers the new list, or <code>null</code>
     */
    public void setTags(List<Marker> markers)
    {
        this.markers = markers != null ? markers : new ArrayList<>();
    }

    /**
     * Returns the live assignment map from object FQN to the list of marker names on that object.
     *
     * @return the assignments, never <code>null</code>
     */
    public Map<String, List<String>> getAssignments()
    {
        return assignments;
    }

    /**
     * Replaces the assignment map, treating <code>null</code> as an empty map.
     *
     * @param assignments the new map, or <code>null</code>
     */
    public void setAssignments(Map<String, List<String>> assignments)
    {
        this.assignments = assignments != null ? assignments : new HashMap<>();
    }

    /**
     * Finds a defined marker by name.
     *
     * @param name the name to look for
     * @return the first marker with that name, or <code>null</code> when there is none
     */
    public Marker getMarkerByName(String name)
    {
        for (Marker marker : markers)
        {
            if (Objects.equals(marker.getName(), name))
            {
                return marker;
            }
        }
        return null;
    }

    /**
     * Adds a marker unless one with the same name is already defined.
     *
     * @param marker the marker to add
     * @return <code>true</code> when it was added, <code>false</code> when the name was taken
     */
    public boolean addMarker(Marker marker)
    {
        if (getMarkerByName(marker.getName()) != null)
        {
            return false;
        }
        markers.add(marker);
        return true;
    }

    /**
     * Removes a marker and strips its name from every object it was assigned to. Emptied assignment
     * entries are left in place; nothing here prunes them.
     *
     * @param markerName the name of the marker to remove
     * @return <code>true</code> when the marker existed
     */
    public boolean removeMarker(String markerName)
    {
        Marker marker = getMarkerByName(markerName);
        if (marker == null)
        {
            return false;
        }
        markers.remove(marker);
        for (List<String> names : assignments.values())
        {
            names.remove(markerName);
        }
        return true;
    }

    /**
     * Assigns a defined marker to an object.
     *
     * @param objectFqn the fully qualified name of the object
     * @param markerName the name of the marker; must already be defined
     * @return <code>true</code> when the assignment was newly added; <code>false</code> when the marker
     *         is not defined or was already assigned to this object
     */
    public boolean assignMarker(String objectFqn, String markerName)
    {
        if (getMarkerByName(markerName) == null)
        {
            return false;
        }
        List<String> names = assignments.computeIfAbsent(objectFqn, key -> new ArrayList<>());
        if (names.contains(markerName))
        {
            return false;
        }
        names.add(markerName);
        return true;
    }

    /**
     * Removes a marker from an object. When that was the object's last marker, its entry is dropped from the
     * map so the file keeps no empty entries.
     *
     * @param objectFqn the fully qualified name of the object
     * @param markerName the name of the marker to remove from it
     * @return <code>true</code> when the marker was assigned and has been removed
     */
    public boolean unassignMarker(String objectFqn, String markerName)
    {
        List<String> names = assignments.get(objectFqn);
        if (names == null)
        {
            return false;
        }
        boolean removed = names.remove(markerName);
        if (removed && names.isEmpty())
        {
            assignments.remove(objectFqn);
        }
        return removed;
    }

    /**
     * Returns the markers assigned to an object, resolved to {@link Marker} objects. Names that no longer
     * match a defined marker are skipped.
     *
     * @param objectFqn the fully qualified name of the object
     * @return the markers on the object; an empty set when it has none
     */
    public Set<Marker> getObjectMarkers(String objectFqn)
    {
        List<String> names = assignments.get(objectFqn);
        if (names == null || names.isEmpty())
        {
            return Set.of();
        }
        Set<Marker> result = new HashSet<>();
        for (String name : names)
        {
            Marker marker = getMarkerByName(name);
            if (marker != null)
            {
                result.add(marker);
            }
        }
        return result;
    }

    /**
     * Returns the names of the markers assigned to an object.
     *
     * @param objectFqn the fully qualified name of the object
     * @return a copy of the assigned names; an empty set when the object has none
     */
    public Set<String> getMarkerNames(String objectFqn)
    {
        List<String> names = assignments.get(objectFqn);
        if (names == null || names.isEmpty())
        {
            return Set.of();
        }
        return new HashSet<>(names);
    }

    /**
     * Moves an object's assignments to a new FQN, as when the object is renamed. Any markers already
     * recorded under the new FQN are overwritten.
     *
     * @param oldFqn the current FQN
     * @param newFqn the FQN to move the assignments to
     * @return <code>true</code> when the old FQN had assignments to move
     */
    public boolean renameObject(String oldFqn, String newFqn)
    {
        List<String> names = assignments.get(oldFqn);
        if (names == null || names.isEmpty())
        {
            return false;
        }
        assignments.remove(oldFqn);
        assignments.put(newFqn, names);
        return true;
    }

    /**
     * Drops all assignments for an object, as when the object is deleted.
     *
     * @param objectFqn the fully qualified name of the object
     * @return <code>true</code> when the object had assignments
     */
    public boolean removeObject(String objectFqn)
    {
        List<String> names = assignments.get(objectFqn);
        if (names == null || names.isEmpty())
        {
            return false;
        }
        assignments.remove(objectFqn);
        return true;
    }

    /**
     * Returns every object the given marker is assigned to.
     *
     * @param markerName the marker name
     * @return the object FQNs; an empty set when the marker is on nothing
     */
    public Set<String> getObjectsByMarker(String markerName)
    {
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, List<String>> entry : assignments.entrySet())
        {
            List<String> names = entry.getValue();
            if (names != null && names.contains(markerName))
            {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * Moves a marker one place earlier in the order.
     *
     * @param markerName the marker to move
     * @return <code>true</code> when it moved; <code>false</code> when it was already first or is not
     *         defined
     */
    public boolean moveMarkerUp(String markerName)
    {
        int index = getMarkerIndex(markerName);
        if (index <= 0)
        {
            return false;
        }
        Collections.swap(markers, index, index - 1);
        return true;
    }

    /**
     * Moves a marker one place later in the order.
     *
     * @param markerName the marker to move
     * @return <code>true</code> when it moved; <code>false</code> when it was already last or is not
     *         defined
     */
    public boolean moveMarkerDown(String markerName)
    {
        int index = getMarkerIndex(markerName);
        if (index < 0 || index >= markers.size() - 1)
        {
            return false;
        }
        Collections.swap(markers, index, index + 1);
        return true;
    }

    /**
     * Returns the position of a marker in the order.
     *
     * @param markerName the marker name
     * @return the 0-based index, or -1 when the marker is not defined
     */
    public int getMarkerIndex(String markerName)
    {
        for (int i = 0; i < markers.size(); i++)
        {
            if (Objects.equals(markers.get(i).getName(), markerName))
            {
                return i;
            }
        }
        return -1;
    }
}
