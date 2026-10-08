/**

 * AI-EDT - 1C AI tools for EDT

 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)

 * Licensed under AGPL-3.0-or-later

 */



package ru.aiedt.mcp.server.labels;



import java.io.ByteArrayInputStream;

import java.io.IOException;

import java.io.InputStream;

import java.io.InputStreamReader;

import java.io.Reader;

import java.io.StringWriter;

import java.nio.charset.StandardCharsets;

import java.nio.file.Files;

import java.nio.file.Path;

import java.util.ArrayList;

import java.util.HashMap;

import java.util.HashSet;

import java.util.LinkedHashMap;

import java.util.List;

import java.util.Map;

import java.util.Set;

import java.util.concurrent.CopyOnWriteArrayList;

import java.util.concurrent.locks.ReentrantReadWriteLock;



import org.eclipse.core.resources.IContainer;

import org.eclipse.core.resources.IFile;

import org.eclipse.core.resources.IFolder;

import org.eclipse.core.resources.IProject;

import org.eclipse.core.resources.IResource;

import org.eclipse.core.resources.IResourceChangeEvent;

import org.eclipse.core.resources.IResourceChangeListener;

import org.eclipse.core.resources.IResourceDelta;

import org.eclipse.core.resources.ResourcesPlugin;

import org.eclipse.core.runtime.CoreException;

import org.yaml.snakeyaml.DumperOptions;

import org.yaml.snakeyaml.LoaderOptions;

import org.yaml.snakeyaml.Yaml;

import org.yaml.snakeyaml.constructor.Constructor;

import org.yaml.snakeyaml.error.YAMLException;

import org.yaml.snakeyaml.introspector.PropertyUtils;

import org.yaml.snakeyaml.representer.Representer;



import org.eclipse.core.runtime.IPath;

import ru.aiedt.mcp.server.Activator;

import ru.aiedt.mcp.server.support.AtomicFileReplace;


import ru.aiedt.mcp.server.support.LegacyStorageMigration;

import ru.aiedt.mcp.server.labels.model.Marker;

import ru.aiedt.mcp.server.labels.model.MarkerStore;



/**

 * The one place markers are read and written.

 * <p>

 * Every project's markers live in a {@link MarkerStore} cached here and backed by the project's

 * {@code .settings/aiedt-markers.yaml} file. Reads are served from the cache; each mutation changes

 * the cached storage, writes the whole file back, and tells the registered listeners so the Navigator

 * decoration and the filter views can catch up. A workspace listener watches the file so that an edit

 * made outside the plugin - a git checkout, a hand edit - drops the stale cache entry too.

 * </p>

 * <p>

 * Readers take a snapshot of the collections under the read lock, so an HTTP call iterating

 * markers cannot race a UI thread that is mutating them. A mutation changes the live storage

 * and publishes it only after the file is stored; a failed write puts the previous contents back

 * and tells the caller. A marker file that does not parse is not cached and is not overwritten,

 * and neither is one that could not be read. The cache is served only while the file still

 * holds the bytes it was read from, and a save replaces the file under its lock only when

 * it still holds them.

 * </p>

 */

public class MarkerManager

    implements IResourceChangeListener

{

    /**

     * Told when a project's markers or assignments change, however the change was made.

     */

    public interface IMarkerChangeListener

    {

        /**

         * Called when the set of defined markers, or their order, changed for a project.

         *

         * @param project the affected project

         */

        void onMarkersChanged(IProject project);



        /**

         * Called when the markers on one object changed.

         *

         * @param project the affected project

         * @param objectFqn the object whose assignments changed

         */

        void onAssignmentsChanged(IProject project, String objectFqn);

    }



    private static volatile MarkerManager instance;



    private final Map<IProject, MarkerStore> cache = new HashMap<>();

    /**
     * The digest of the bytes each project's storage was read from. A cache entry is served
     * only while the file still holds them.
     */
    private final Map<IProject, String> fingerprints = new HashMap<>();

    /**

     * Why a caller is told a mutation did not run: the marker file is on disk and does not parse.

     */

    public static final String UNREADABLE_MARKER_FILE =

        "the marker file is unreadable; resolve it first"; //$NON-NLS-1$



    /**

     * Projects whose marker file is present but does not parse. Those are not cached.

     */

    private final Set<IProject> unreadable = new HashSet<>();



    /**

     * Projects whose marker file could not be read at all on the last load attempt - a sharing

     * violation, a network drive that went away. Not a state, only the answer to the last

     * attempt: the next load reads the file again and a read that succeeds clears it.

     */

    private final Set<IProject> readFailed = new HashSet<>();



    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();



    private final CopyOnWriteArrayList<IMarkerChangeListener> listeners = new CopyOnWriteArrayList<>();



    /**

     * Builds the service and starts watching the workspace for outside edits of the marker files.

     */

    private MarkerManager()

    {

        try

        {

            ResourcesPlugin.getWorkspace().addResourceChangeListener(this, IResourceChangeEvent.POST_CHANGE);

        }

        catch (RuntimeException e)

        {

            Activator.logError("Could not register the marker file change listener", e); //$NON-NLS-1$

        }

    }



    /**

     * Returns the shared service, creating it on first use.

     *

     * @return the service

     */

    public static MarkerManager getInstance()

    {

        MarkerManager result = instance;

        if (result == null)

        {

            synchronized (MarkerManager.class)

            {

                result = instance;

                if (result == null)

                {

                    result = new MarkerManager();

                    instance = result;

                }

            }

        }

        return result;

    }



    /**

     * Shuts the service down: stops watching the workspace, empties the cache and listener list, and

     * forgets the singleton so a later {@link #getInstance()} starts fresh.

     * <p>

     * Call this from the plugin's stop hook. Without it the workspace listener stays attached across a

     * bundle stop or update, and each update leaks another one.

     * </p>

     */

    public static void dispose()

    {

        synchronized (MarkerManager.class)

        {

            MarkerManager current = instance;

            if (current == null)

            {

                return;

            }

            try

            {

                ResourcesPlugin.getWorkspace().removeResourceChangeListener(current);

            }

            catch (RuntimeException e)

            {

                // The workspace may already be gone at shutdown; nothing more to do.

            }

            current.lock.writeLock().lock();

            try

            {

                current.cache.clear();

                current.fingerprints.clear();

            }

            finally

            {

                current.lock.writeLock().unlock();

            }

            current.listeners.clear();

            instance = null;

        }

    }



    /**

     * Registers a change listener, unless it is <code>null</code> or already registered.

     *

     * @param listener the listener to add

     */

    public void addMarkerChangeListener(IMarkerChangeListener listener)

    {

        if (listener != null && !listeners.contains(listener))

        {

            listeners.add(listener);

        }

    }



    /**

     * Unregisters a change listener.

     *

     * @param listener the listener to remove

     */

    public void removeMarkerChangeListener(IMarkerChangeListener listener)

    {

        listeners.remove(listener);

    }



    /**

     * Returns a detached copy of a project's marker storage.

     * <p>

     * The copy is taken under the lock. An HTTP caller can iterate the lists after it returns

     * without racing a UI thread that mutates the live storage, and edits of the copy are not

     * written back.

     * </p>

     *

     * @param project the project

     * @return a snapshot; empty when the project has no readable marker file

     */

    public MarkerStore getMarkerStorage(IProject project)

    {

        lock.readLock().lock();

        try

        {

            MarkerStore cached = cache.get(project);

            if (cached != null && cacheIsCurrent(project))

            {

                return cached.copy();

            }

        }

        finally

        {

            lock.readLock().unlock();

        }

        lock.writeLock().lock();

        try

        {

            MarkerStore live = loadIntoCache(project);

            if (live == null)

            {

                return new MarkerStore();

            }

            return live.copy();

        }

        finally

        {

            lock.writeLock().unlock();

        }

    }




    /**

     * Returns the defined markers for a project, in user order.

     * <p>

     * The list is a copy. The {@link Marker} instances are the live ones, so a tree that found a

     * marker in one call still finds that same instance on the next call while the cache is unchanged.

     * </p>

     *

     * @param project the project

     * @return the markers; empty when the file is missing or unreadable

     */

    public List<Marker> getMarkers(IProject project)

    {

        lock.readLock().lock();

        try

        {

            MarkerStore cached = cache.get(project);

            if (cached != null && cacheIsCurrent(project))

            {

                return new ArrayList<>(cached.getTags());

            }

        }

        finally

        {

            lock.readLock().unlock();

        }

        lock.writeLock().lock();

        try

        {

            MarkerStore live = loadIntoCache(project);

            if (live == null)

            {

                return new ArrayList<>();

            }

            return new ArrayList<>(live.getTags());

        }

        finally

        {

            lock.writeLock().unlock();

        }

    }




    /**

     * Defines a new marker.

     * <p>

     * Nothing is published when a marker with that name already exists, when the marker file does

     * not parse, or when the file cannot be written. In those cases the file on disk is left as it was.

     * </p>

     *

     * @param project the project

     * @param name the marker name

     * @param color the marker color, or <code>null</code> for the default

     * @param description the marker description, or <code>null</code> for none

     * @return the created marker, or <code>null</code> when it was not stored

     */

    public Marker createMarker(IProject project, String name, String color, String description)

    {

        MarkerWriteOutcome outcome = createMarkerWithOutcome(project, name, color, description);

        return outcome.isRefused() ? null : outcome.getMarker();

    }



    /**
     * Defines a new marker and answers what became of the edit.
     *
     * @param project the project
     * @param name the marker name
     * @param color the marker color, or <code>null</code> for the default
     * @param description the marker description, or <code>null</code> for none
     * @return the stored edit with the created marker, or the refusal
     */
    public MarkerWriteOutcome createMarkerWithOutcome(IProject project, String name, String color,
        String description)
    {
        lock.writeLock().lock();
        MarkerWriteOutcome outcome;
        try
        {
            MarkerStore storage = loadIntoCache(project);
            if (storage == null)
            {
                outcome = MarkerWriteOutcome.refused(storageRefusalCode(project),
                    "the marker file of project " + safeName(project) //$NON-NLS-1$
                        + " could not be read, so no marker was created"); //$NON-NLS-1$
            }
            else if (storage.getMarkerByName(name) != null)
            {
                outcome = MarkerWriteOutcome.refused(MarkerWriteOutcome.TAG_EXISTS,
                    "a marker named \"" + name + "\" is already defined in this project"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
            {
                MarkerStore before = storage.copy();
                Marker marker = new Marker(name, color, description);
                if (!storage.addMarker(marker))
                {
                    outcome = MarkerWriteOutcome.refused(MarkerWriteOutcome.TAG_EXISTS,
                        "a marker named \"" + name + "\" is already defined in this project"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                else
                {
                    String refusal = storedRefusal(project, storage, before);
                    outcome = refusal != null
                        ? MarkerWriteOutcome.refused(refusal,
                            "the marker \"" + name + "\" was not created: " //$NON-NLS-1$ //$NON-NLS-2$
                                + refusalText(refusal))
                        : MarkerWriteOutcome.stored(marker, 0);
                }
            }
        }
        finally
        {
            lock.writeLock().unlock();
        }
        if (!outcome.isRefused())
        {
            fireMarkersChanged(project);
        }
        return outcome;
    }




    /**

     * Updates a marker in place. A <code>null</code> argument leaves that field unchanged.

     * <p>

     * When the name changes, every assignment of the old name is moved to the new one. A name that

     * is already taken changes nothing, including the color and description; the caller keeps those

     * by updating again with the original name. A file that cannot be written is restored to the

     * state it had before this call.

     * </p>

     *

     * @param project the project

     * @param oldName the current name of the marker to update

     * @param newName the new name, or <code>null</code> to keep it

     * @param color the new color, or <code>null</code> to keep it

     * @param description the new description, or <code>null</code> to keep it

     * @return <code>true</code> when the update was stored; <code>false</code> when the marker is not

     *         found, the new name is already taken, or the file could not be written

     */

    public boolean updateMarker(IProject project, String oldName, String newName, String color,

        String description)

    {

        return !updateMarkerWithOutcome(project, oldName, newName, color, description).isRefused();

    }



    /**
     * Updates a marker in place and answers what became of the edit, including how many objects a
     * rename moved to the new name.
     *
     * @param project the project
     * @param oldName the current name of the marker to update
     * @param newName the new name, or <code>null</code> to keep it
     * @param color the new color, or <code>null</code> to keep it
     * @param description the new description, or <code>null</code> to keep it
     * @return the stored edit, or the refusal
     */
    public MarkerWriteOutcome updateMarkerWithOutcome(IProject project, String oldName,
        String newName, String color, String description)
    {
        lock.writeLock().lock();
        MarkerWriteOutcome outcome;
        try
        {
            MarkerStore storage = loadIntoCache(project);
            if (storage == null)
            {
                outcome = MarkerWriteOutcome.refused(storageRefusalCode(project),
                    "the marker file of project " + safeName(project) //$NON-NLS-1$
                        + " could not be read, so the marker was not updated"); //$NON-NLS-1$
            }
            else
            {
                Marker marker = storage.getMarkerByName(oldName);
                if (marker == null)
                {
                    outcome = MarkerWriteOutcome.refused(MarkerWriteOutcome.TAG_NOT_FOUND,
                        "no marker named \"" + oldName + "\" is defined in this project"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                else if (newName != null && !newName.equals(oldName)
                    && storage.getMarkerByName(newName) != null)
                {
                    outcome = MarkerWriteOutcome.refused(MarkerWriteOutcome.NAME_TAKEN,
                        "another marker is already named \"" + newName //$NON-NLS-1$
                            + "\"; the color and description were left as they were"); //$NON-NLS-1$
                }
                else
                {
                    MarkerStore before = storage.copy();
                    boolean renaming = newName != null && !newName.equals(oldName);
                    int moved = renaming ? storage.getObjectsByMarker(oldName).size() : 0;
                    if (renaming)
                    {
                        for (List<String> names : storage.getAssignments().values())
                        {
                            for (int i = 0; i < names.size(); i++)
                            {
                                if (oldName.equals(names.get(i)))
                                {
                                    names.set(i, newName);
                                }
                            }
                        }
                        marker.setName(newName);
                    }
                    if (color != null)
                    {
                        marker.setColor(color);
                    }
                    if (description != null)
                    {
                        marker.setDescription(description);
                    }
                    String refusal = storedRefusal(project, storage, before);
                    outcome = refusal != null
                        ? MarkerWriteOutcome.refused(refusal,
                            "the marker \"" + oldName + "\" was not updated: " //$NON-NLS-1$ //$NON-NLS-2$
                                + refusalText(refusal))
                        : MarkerWriteOutcome.stored(marker, moved);
                }
            }
        }
        finally
        {
            lock.writeLock().unlock();
        }
        if (!outcome.isRefused())
        {
            fireMarkersChanged(project);
        }
        return outcome;
    }




    /**

     * Deletes a marker and removes it from every object it was on.

     *

     * @param project the project

     * @param markerName the name of the marker to delete

     * @return <code>true</code> when the marker existed and the file was written

     */

    public boolean deleteMarker(IProject project, String markerName)

    {

        return !deleteMarkerWithOutcome(project, markerName).isRefused();

    }



    /**
     * Deletes a marker and answers what became of the edit, including how many assignments it took
     * off the objects that carried the marker.
     *
     * @param project the project
     * @param markerName the name of the marker to delete
     * @return the stored edit, or the refusal
     */
    public MarkerWriteOutcome deleteMarkerWithOutcome(IProject project, String markerName)
    {
        lock.writeLock().lock();
        MarkerWriteOutcome outcome;
        try
        {
            MarkerStore storage = loadIntoCache(project);
            if (storage == null)
            {
                outcome = MarkerWriteOutcome.refused(storageRefusalCode(project),
                    "the marker file of project " + safeName(project) //$NON-NLS-1$
                        + " could not be read, so the marker was not deleted"); //$NON-NLS-1$
            }
            else if (storage.getMarkerByName(markerName) == null)
            {
                outcome = MarkerWriteOutcome.refused(MarkerWriteOutcome.TAG_NOT_FOUND,
                    "no marker named \"" + markerName + "\" is defined in this project"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
            {
                int removed = storage.getObjectsByMarker(markerName).size();
                MarkerStore before = storage.copy();
                storage.removeMarker(markerName);
                String refusal = storedRefusal(project, storage, before);
                outcome = refusal != null
                    ? MarkerWriteOutcome.refused(refusal,
                        "the marker \"" + markerName + "\" was not deleted: " //$NON-NLS-1$ //$NON-NLS-2$
                            + refusalText(refusal))
                    : MarkerWriteOutcome.stored(null, removed);
            }
        }
        finally
        {
            lock.writeLock().unlock();
        }
        if (!outcome.isRefused())
        {
            fireMarkersChanged(project);
        }
        return outcome;
    }




    /**

     * Returns the markers on an object.

     *

     * @param project the project

     * @param objectFqn the object FQN

     * @return the markers; an empty set when the object has none

     */

    public Set<Marker> getObjectMarkers(IProject project, String objectFqn)

    {

        // The decorator asks this once per Navigator node, so the answer is built from the live

        // storage under the lock rather than through a full detached copy of every marker and

        // assignment the project holds. The set is fresh and the Marker instances are the live

        // ones, the same exposure getMarkers already answers with.

        lock.readLock().lock();

        try

        {

            MarkerStore cached = cache.get(project);

            if (cached != null && cacheIsCurrent(project))

            {

                return cached.getObjectMarkers(objectFqn);

            }

        }

        finally

        {

            lock.readLock().unlock();

        }

        lock.writeLock().lock();

        try

        {

            MarkerStore live = loadIntoCache(project);

            return live == null ? Set.of() : live.getObjectMarkers(objectFqn);

        }

        finally

        {

            lock.writeLock().unlock();

        }

    }



    /**

     * Assigns a marker to an object.

     * <p>

     * Returns <code>false</code>, and leaves the storage as it was, when the marker is not defined,

     * when it was already assigned, when the marker file does not parse, or when the file cannot be

     * written.

     * </p>

     *

     * @param project the project

     * @param objectFqn the object FQN

     * @param markerName the marker name; must already be defined

     * @return <code>true</code> when the assignment was newly added and stored

     */

    public boolean assignMarker(IProject project, String objectFqn, String markerName)

    {

        MarkerWriteOutcome outcome = assignMarkersWithOutcome(project, objectFqn,
            List.of(markerName));

        return !outcome.isRefused() && outcome.getChangedAssignments() > 0;

    }



    /**
     * Assigns several markers to one object in a single write.
     * <p>
     * Every name must be defined before the first assignment is made: one unknown name refuses the
     * whole call and nothing is assigned. A name that is already on the object is skipped rather
     * than refused, and when every name is skipped the file is not written at all.
     * </p>
     *
     * @param project the project
     * @param objectFqn the object FQN
     * @param markerNames the marker names; must already be defined
     * @return the stored edit with the number of assignments it added, or the refusal
     */
    public MarkerWriteOutcome assignMarkersWithOutcome(IProject project, String objectFqn,
        List<String> markerNames)
    {
        lock.writeLock().lock();
        MarkerWriteOutcome outcome;
        try
        {
            MarkerStore storage = loadIntoCache(project);
            if (storage == null)
            {
                outcome = MarkerWriteOutcome.refused(storageRefusalCode(project),
                    "the marker file of project " + safeName(project) //$NON-NLS-1$
                        + " could not be read, so nothing was assigned"); //$NON-NLS-1$
            }
            else
            {
                String unknown = firstUndefined(storage, markerNames);
                if (unknown != null)
                {
                    outcome = MarkerWriteOutcome.refused(MarkerWriteOutcome.TAG_NOT_FOUND,
                        "no marker named \"" + unknown + "\" is defined in this project; " //$NON-NLS-1$ //$NON-NLS-2$
                            + "nothing was assigned"); //$NON-NLS-1$
                }
                else
                {
                    MarkerStore before = storage.copy();
                    List<String> applied = new ArrayList<>();
                    Map<String, String> skipped = new LinkedHashMap<>();
                    for (String markerName : markerNames)
                    {
                        if (storage.assignMarker(objectFqn, markerName))
                        {
                            applied.add(markerName);
                        }
                        else
                        {
                            // An undefined name was already refused above, so this names a marker
                            // the object carried when the edit itself looked.
                            skipped.put(markerName, "alreadyAssigned"); //$NON-NLS-1$
                        }
                    }
                    if (applied.isEmpty())
                    {
                        outcome = MarkerWriteOutcome.storedPerName(applied, skipped, 0);
                    }
                    else
                    {
                        String refusal = storedRefusal(project, storage, before);
                        outcome = refusal != null
                            ? MarkerWriteOutcome.refused(refusal,
                                "no marker was assigned to \"" + objectFqn + "\": " //$NON-NLS-1$ //$NON-NLS-2$
                                    + refusalText(refusal))
                            : MarkerWriteOutcome.storedPerName(applied, skipped, applied.size());
                    }
                }
            }
        }
        finally
        {
            lock.writeLock().unlock();
        }
        if (!outcome.isRefused() && outcome.getChangedAssignments() > 0)
        {
            fireAssignmentsChanged(project, objectFqn);
        }
        return outcome;
    }




    /**

     * Removes a marker from an object.

     *

     * @param project the project

     * @param objectFqn the object FQN

     * @param markerName the marker name

     * @return <code>true</code> when the marker was assigned and the removal was stored

     */

    public boolean unassignMarker(IProject project, String objectFqn, String markerName)

    {

        MarkerWriteOutcome outcome = unassignMarkersWithOutcome(project, objectFqn,
            List.of(markerName));

        return !outcome.isRefused() && outcome.getChangedAssignments() > 0;

    }



    /**
     * Removes several markers from one object in a single write.
     * <p>
     * Every name must be defined before the first removal is made: one unknown name refuses the
     * whole call and nothing is removed. A name the object does not carry is skipped rather than
     * refused, and when every name is skipped the file is not written at all.
     * </p>
     *
     * @param project the project
     * @param objectFqn the object FQN
     * @param markerNames the marker names to take off the object
     * @return the stored edit with the number of assignments it removed, or the refusal
     */
    public MarkerWriteOutcome unassignMarkersWithOutcome(IProject project, String objectFqn,
        List<String> markerNames)
    {
        lock.writeLock().lock();
        MarkerWriteOutcome outcome;
        try
        {
            MarkerStore storage = loadIntoCache(project);
            if (storage == null)
            {
                outcome = MarkerWriteOutcome.refused(storageRefusalCode(project),
                    "the marker file of project " + safeName(project) //$NON-NLS-1$
                        + " could not be read, so nothing was removed"); //$NON-NLS-1$
            }
            else
            {
                String unknown = firstUndefined(storage, markerNames);
                if (unknown != null)
                {
                    outcome = MarkerWriteOutcome.refused(MarkerWriteOutcome.TAG_NOT_FOUND,
                        "no marker named \"" + unknown + "\" is defined in this project; " //$NON-NLS-1$ //$NON-NLS-2$
                            + "nothing was removed"); //$NON-NLS-1$
                }
                else
                {
                    MarkerStore before = storage.copy();
                    List<String> applied = new ArrayList<>();
                    Map<String, String> skipped = new LinkedHashMap<>();
                    for (String markerName : markerNames)
                    {
                        if (storage.unassignMarker(objectFqn, markerName))
                        {
                            applied.add(markerName);
                        }
                        else
                        {
                            // An undefined name was already refused above, so this names a marker
                            // the object did not carry when the edit itself looked.
                            skipped.put(markerName, "notAssigned"); //$NON-NLS-1$
                        }
                    }
                    if (applied.isEmpty())
                    {
                        outcome = MarkerWriteOutcome.storedPerName(applied, skipped, 0);
                    }
                    else
                    {
                        String refusal = storedRefusal(project, storage, before);
                        outcome = refusal != null
                            ? MarkerWriteOutcome.refused(refusal,
                                "no marker was removed from \"" + objectFqn + "\": " //$NON-NLS-1$ //$NON-NLS-2$
                                    + refusalText(refusal))
                            : MarkerWriteOutcome.storedPerName(applied, skipped, applied.size());
                    }
                }
            }
        }
        finally
        {
            lock.writeLock().unlock();
        }
        if (!outcome.isRefused() && outcome.getChangedAssignments() > 0)
        {
            fireAssignmentsChanged(project, objectFqn);
        }
        return outcome;
    }




    /**

     * Returns every object a marker is assigned to.

     *

     * @param project the project

     * @param markerName the marker name

     * @return the object FQNs; an empty set when the marker is on nothing

     */

    public Set<String> findObjectsByMarker(IProject project, String markerName)

    {

        return getMarkerStorage(project).getObjectsByMarker(markerName);

    }



    /**

     * Returns the objects carrying any of the given markers, each mapped to the subset of those markers it

     * actually carries.

     *

     * @param project the project

     * @param markerNames the marker names to union over

     * @return a map from object FQN to the matching markers; empty when nothing matches

     */

    public Map<String, Set<Marker>> findObjectsByMarkers(IProject project, Set<String> markerNames)

    {

        Map<String, Set<Marker>> result = new HashMap<>();

        if (markerNames == null || markerNames.isEmpty())

        {

            return result;

        }

        MarkerStore storage = getMarkerStorage(project);

        for (String markerName : markerNames)

        {

            Marker marker = storage.getMarkerByName(markerName);

            if (marker == null)

            {

                continue;

            }

            for (String objectFqn : storage.getObjectsByMarker(markerName))

            {

                result.computeIfAbsent(objectFqn, key -> new HashSet<>()).add(marker);

            }

        }

        return result;

    }



    /**

     * Tells whether an object, or any object nested under it, carries markers.

     * <p>

     * A rename contributor calls this before it builds a change. Markers on the object itself and

     * markers on a child, whose name continues past {@code objectFqn} with a dot, both count. A name

     * that only shares a prefix does not.

     * </p>

     *

     * @param project the project

     * @param objectFqn the fully qualified name being renamed

     * @return <code>true</code> when there are assignments to carry

     */

    public boolean holdsObjectOrDescendant(IProject project, String objectFqn)

    {

        return getMarkerStorage(project).holdsObjectOrDescendant(objectFqn);

    }



    /**

     * Returns why a mutation of this project's markers was refused because the file does not parse.

     *

     * @param project the project

     * @return {@link #UNREADABLE_MARKER_FILE} when the file is present and does not parse, or

     *         <code>null</code> when it is readable or absent

     */

    public String markerFileRefusal(IProject project)

    {

        if (project == null)

        {

            return null;

        }

        getMarkerStorage(project);

        lock.readLock().lock();

        try

        {

            return unreadable.contains(project) ? UNREADABLE_MARKER_FILE : null;

        }

        finally

        {

            lock.readLock().unlock();

        }

    }

    /**
     * Tells whether the last load attempt of the project's marker file could not read its bytes at
     * all - as opposed to reading them and finding they do not parse, which
     * {@link #markerFileRefusal(IProject)} answers.
     * <p>
     * The answer describes the last attempt, so the question is meaningful after a call that
     * loads: {@code getMarkerStorage}, {@code markerFileRefusal}. A read that failed is not a
     * state - the next load reads the file again, and one that succeeds clears this.
     * </p>
     *
     * @param project the project
     * @return <code>true</code> when the last load attempt of its marker file failed to read
     */
    public boolean markerFileReadFailed(IProject project)
    {
        if (project == null)
        {
            return false;
        }
        lock.readLock().lock();
        try
        {
            return readFailed.contains(project);
        }
        finally
        {
            lock.readLock().unlock();
        }
    }

    /**

     * Moves an object's assignments, and the assignments of every object nested under it, to a new FQN.

     *

     * @param project the project

     * @param oldFqn the current FQN

     * @param newFqn the new FQN

     * @return <code>true</code> when something was moved and the file was written

     */

    public boolean renameObject(IProject project, String oldFqn, String newFqn)

    {

        lock.writeLock().lock();

        boolean changed = false;

        try

        {

            MarkerStore storage = loadIntoCache(project);

            if (storage == null)

            {

                return false;

            }

            MarkerStore before = storage.copy();

            if (!storage.renameObject(oldFqn, newFqn))

            {

                return false;

            }

            if (storedRefusal(project, storage, before) != null)

            {

                return false;

            }

            changed = true;

        }

        finally

        {

            lock.writeLock().unlock();

        }

        if (changed)

        {

            fireAssignmentsChanged(project, newFqn);

        }

        return changed;

    }




    /**

     * Drops an object's assignments, as when it is deleted.

     *

     * @param project the project

     * @param objectFqn the object FQN

     * @return <code>true</code> when the object had assignments and the file was written

     */

    public boolean removeObject(IProject project, String objectFqn)

    {

        lock.writeLock().lock();

        boolean changed = false;

        try

        {

            MarkerStore storage = loadIntoCache(project);

            if (storage == null)

            {

                return false;

            }

            MarkerStore before = storage.copy();

            if (!storage.removeObject(objectFqn))

            {

                return false;

            }

            if (storedRefusal(project, storage, before) != null)

            {

                return false;

            }

            changed = true;

        }

        finally

        {

            lock.writeLock().unlock();

        }

        if (changed)

        {

            fireAssignmentsChanged(project, objectFqn);

        }

        return changed;

    }




    /**

     * Moves a marker one place earlier in the order, which also shifts the keyboard shortcuts.

     *

     * @param project the project

     * @param markerName the marker to move

     * @return <code>true</code> when it moved and the file was written

     */

    public boolean moveMarkerUp(IProject project, String markerName)

    {

        lock.writeLock().lock();

        boolean changed = false;

        try

        {

            MarkerStore storage = loadIntoCache(project);

            if (storage == null)

            {

                return false;

            }

            MarkerStore before = storage.copy();

            if (!storage.moveMarkerUp(markerName))

            {

                return false;

            }

            if (storedRefusal(project, storage, before) != null)

            {

                return false;

            }

            changed = true;

        }

        finally

        {

            lock.writeLock().unlock();

        }

        if (changed)

        {

            fireMarkersChanged(project);

        }

        return changed;

    }




    /**

     * Moves a marker one place later in the order, which also shifts the keyboard shortcuts.

     *

     * @param project the project

     * @param markerName the marker to move

     * @return <code>true</code> when it moved and the file was written

     */

    public boolean moveMarkerDown(IProject project, String markerName)

    {

        lock.writeLock().lock();

        boolean changed = false;

        try

        {

            MarkerStore storage = loadIntoCache(project);

            if (storage == null)

            {

                return false;

            }

            MarkerStore before = storage.copy();

            if (!storage.moveMarkerDown(markerName))

            {

                return false;

            }

            if (storedRefusal(project, storage, before) != null)

            {

                return false;

            }

            changed = true;

        }

        finally

        {

            lock.writeLock().unlock();

        }

        if (changed)

        {

            fireMarkersChanged(project);

        }

        return changed;

    }




    /**

     * Returns the keyboard digit a marker is reachable by, mapping the tenth marker to {@code Ctrl+Alt+0}.

     *

     * @param project the project

     * @param markerName the marker name

     * @return 1..9 for the first nine markers, 0 for the tenth, or -1 when the marker is beyond the tenth or

     *         is not defined

     */

    public int getMarkerHotkeyIndex(IProject project, String markerName)

    {

        int index = getMarkerStorage(project).getMarkerIndex(markerName);

        if (index < 0 || index >= 10)

        {

            return -1;

        }

        if (index == 9)

        {

            return 0;

        }

        return index + 1;

    }



    @Override

    public void resourceChanged(IResourceChangeEvent event)

    {

        IResourceDelta delta = event.getDelta();

        if (delta == null)

        {

            return;

        }

        try

        {

            delta.accept(childDelta -> {

                IResource resource = childDelta.getResource();

                if (resource instanceof IFile && MarkerKeys.MARKERS_FILE.equals(resource.getName()))

                {

                    IContainer parent = resource.getParent();

                    if (parent != null && MarkerKeys.SETTINGS_FOLDER.equals(parent.getName()))

                    {

                        IProject project = resource.getProject();

                        if (project != null)

                        {

                            evict(project);

                            fireMarkersChanged(project);

                        }

                    }

                }

                return true;

            });

        }

        catch (CoreException e)

        {

            Activator.logError("Error while handling a marker file change", e); //$NON-NLS-1$

        }

    }



    /**

     * Drops a project from the cache so its storage is reloaded on next request.

     * <p>

     * An unreadable file is forgotten too, so a later edit of the file is parsed again instead of

     * being refused forever.

     * </p>

     *

     * @param project the project to evict

     */

    private void evict(IProject project)
    {
        // Called from the resource-change notification, which runs under the workspace lock. A
        // writer holds this manager's lock while it asks the workspace to refresh the file it has
        // just replaced, so waiting for the lock here would be the other half of a deadlock. When
        // the lock is busy the eviction runs on another thread, which holds no workspace lock.
        if (lock.writeLock().tryLock())
        {
            try
            {
                dropCached(project);
            }
            finally
            {
                lock.writeLock().unlock();
            }
            return;
        }
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            lock.writeLock().lock();
            try
            {
                dropCached(project);
            }
            finally
            {
                lock.writeLock().unlock();
            }
        });
    }

    /**
     * Forgets everything cached for a project. The caller holds the write lock.
     *
     * @param project the project to forget
     */
    private void dropCached(IProject project)
    {
        cache.remove(project);
        unreadable.remove(project);
        readFailed.remove(project);
        fingerprints.remove(project);
    }

    /**

     * Notifies listeners that a project's markers changed.

     *

     * @param project the affected project

     */

    private void fireMarkersChanged(IProject project)

    {

        for (IMarkerChangeListener listener : listeners)

        {

            try

            {

                listener.onMarkersChanged(project);

            }

            catch (RuntimeException e)

            {

                Activator.logError("A marker change listener failed", e); //$NON-NLS-1$

            }

        }

    }



    /**

     * Notifies listeners that one object's assignments changed.

     *

     * @param project the affected project

     * @param objectFqn the object whose assignments changed

     */

    private void fireAssignmentsChanged(IProject project, String objectFqn)

    {

        for (IMarkerChangeListener listener : listeners)

        {

            try

            {

                listener.onAssignmentsChanged(project, objectFqn);

            }

            catch (RuntimeException e)

            {

                Activator.logError("A marker change listener failed", e); //$NON-NLS-1$

            }

        }

    }



    /**
     * Returns the cached storage, loading it when absent. The caller holds the write lock.
     * <p>
     * An unreadable file is not cached, and neither is a read that failed: the caller must
     * not write an empty storage over either. A failed read is not remembered as a state
     * either: the next call reads the file again, so a transient failure disables the
     * markers only while it lasts. A cache entry is served only while the file still holds
     * the bytes it was read from; once the file changes, the entry is dropped and the
     * storage is reloaded from what the file holds now.
     * </p>
     *
     * @param project the project
     * @return the live storage, or <code>null</code> when the file does not parse or cannot be
     *         read
     */
    private MarkerStore loadIntoCache(IProject project)
    {
        if (project == null || unreadable.contains(project))
        {
            return null;
        }
        MarkerStore cached = cache.get(project);
        if (cached != null)
        {
            if (cacheIsCurrent(project))
            {
                return cached;
            }
            cache.remove(project);
            fingerprints.remove(project);
        }
        MarkerStore loaded = loadMarkerStorage(project);
        if (loaded == null)
        {
            return null;
        }
        cache.put(project, loaded);
        return loaded;
    }

    /**

     * Writes a storage that was already mutated, or puts the previous contents back when the write fails.

     * The caller holds the write lock.

     *

     * @param project the project

     * @param storage the live storage, already changed

     * @param before a snapshot taken before the change

     * @return the refusal code, or <code>null</code> when the file was written

     */

    private String storedRefusal(IProject project, MarkerStore storage, MarkerStore before)

    {

        String refusal = saveMarkerStorage(project, storage);

        if (refusal != null)

        {

            storage.restoreFrom(before);

            return refusal;

        }

        cache.put(project, storage);

        return null;

    }



    /**
     * The refusal code of a storage the manager could not load: the file does not parse, or it
     * could not be read at all. The caller holds the write lock, so the unreadable set is safe to
     * read here.
     *
     * @param project the project whose storage was asked for
     * @return {@link MarkerWriteOutcome#UNREADABLE_FILE} or {@link MarkerWriteOutcome#SAVE_FAILED}
     */
    private String storageRefusalCode(IProject project)
    {
        return unreadable.contains(project) ? MarkerWriteOutcome.UNREADABLE_FILE
            : MarkerWriteOutcome.SAVE_FAILED;
    }

    /**
     * The project's name for a refusal sentence, or a placeholder for the null no caller should
     * hand in but one still can.
     *
     * @param project the project
     * @return its name
     */
    private static String safeName(IProject project)
    {
        return project != null ? project.getName() : "(none)"; //$NON-NLS-1$
    }

    /**
     * A short human sentence for a file refusal code, to close the refusal text of an edit with.
     *
     * @param refusal the refusal code
     * @return the sentence
     */
    private static String refusalText(String refusal)
    {
        if (MarkerWriteOutcome.UNREADABLE_FILE.equals(refusal))
        {
            return "the marker file does not parse; resolve it first"; //$NON-NLS-1$
        }
        if (MarkerWriteOutcome.READ_ONLY_FILE.equals(refusal))
        {
            return "the marker file is read-only"; //$NON-NLS-1$
        }
        if (MarkerWriteOutcome.CHANGED_ON_DISK.equals(refusal))
        {
            return "the marker file changed after it was read and was left as the changing party wrote it"; //$NON-NLS-1$
        }
        return "the marker file could not be written"; //$NON-NLS-1$
    }

    /**
     * The first name of a list no defined marker answers to, if there is one.
     *
     * @param storage the project's markers
     * @param markerNames the names a call wants to work with
     * @return the unknown name, or <code>null</code> when every name is defined
     */
    private static String firstUndefined(MarkerStore storage, List<String> markerNames)
    {
        if (markerNames == null)
        {
            return null;
        }
        for (String markerName : markerNames)
        {
            if (storage.getMarkerByName(markerName) == null)
            {
                return markerName;
            }
        }
        return null;
    }



    /**

     * Tells whether an existing marker file may be replaced.

     * <p>

     * A forced Eclipse write clears the read-only attribute and then succeeds, so the caller would be

     * told the markers were saved. A file the operating system will not open for writing is refused

     * here, before that write.

     * </p>

     *

     * @param file the marker file, which exists

     * @return <code>false</code> when the file is read-only

     */

    private static boolean canOverwrite(IFile file)

    {

        if (file.isReadOnly())

        {

            return false;

        }

        IPath location = file.getLocation();

        if (location == null)

        {

            return true;

        }

        Path path = location.toFile().toPath();

        return !Files.exists(path) || Files.isWritable(path);

    }

    /**
     * Reads a project's marker file into a storage and remembers the bytes it was read from.
     * <p>
     * A missing file resolves to an empty storage. A file the workspace does not know about is
     * read from the disk it lies on: the bytes are the project's own whichever party put them
     * there. Malformed YAML is recorded as unreadable and answered with <code>null</code>, and a
     * read that failed is answered the same way and tried again on the next call: neither is
     * cached, so no mutation writes an empty storage over a file it never read. The load
     * ignores properties it does not know, so a file written by a newer version still reads.
     * </p>
     *
     * @param project the project
     * @return the loaded storage, an empty one when there is nothing to read, or
     *         <code>null</code> when the file does not parse or cannot be read
     */
    private MarkerStore loadMarkerStorage(IProject project)
    {
        if (project == null || !project.isAccessible())
        {
            return new MarkerStore();
        }
        IFile file = getMarkersFile(project);
        if (file == null)
        {
            return new MarkerStore();
        }
        byte[] bytes;
        try
        {
            bytes = readMarkersBytes(file);
        }
        catch (CoreException | IOException e)
        {
            fingerprints.remove(project);
            readFailed.add(project);
            Activator.logError("Could not read the marker file for project " + project.getName(), e); //$NON-NLS-1$
            return null;
        }
        unreadable.remove(project);
        readFailed.remove(project);
        if (bytes == null)
        {
            fingerprints.put(project, AtomicFileReplace.NO_FILE_FINGERPRINT);
            return new MarkerStore();
        }
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(bytes),
            StandardCharsets.UTF_8))
        {
            MarkerStore storage = createLoadYaml().load(reader);
            fingerprints.put(project, AtomicFileReplace.fingerprint(bytes));
            return storage != null ? storage : new MarkerStore();
        }
        catch (IOException e)
        {
            fingerprints.remove(project);
            readFailed.add(project);
            Activator.logError("Could not read the marker file for project " + project.getName(), e); //$NON-NLS-1$
            return null;
        }
        catch (YAMLException e)
        {
            // Corrupt YAML or a git merge-conflict marker. Remember it and refuse to cache an
            // empty storage: the next mutation would otherwise overwrite both sides of the
            // conflict.
            unreadable.add(project);
            fingerprints.remove(project);
            Activator.logError("Could not parse the marker file for project " + project.getName(), e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Reads the marker file's bytes, following the file onto the local disk when the resource
     * tree does not have it yet.
     * <p>
     * Another process writes the file - git checks a branch out, an editor outside the
     * workspace saves it - while the tree still answers that there is no such resource. The
     * bytes on disk are the ones the project has, so they are read directly when the tree has
     * no file. The disk decides the other way around too: a file the tree remembers that the
     * disk no longer has counts as no file, not as a read error.
     * </p>
     *
     * @param file the marker file handle
     * @return the bytes, or {@code null} when there is no file at all
     * @throws CoreException when the file cannot be read through the workspace
     * @throws IOException when the file cannot be read from disk
     */
    private static byte[] readMarkersBytes(IFile file) throws CoreException, IOException
    {
        IPath location = file.getLocation();
        Path onDisk = location == null ? null : location.toFile().toPath();
        if (onDisk != null && Files.notExists(onDisk))
        {
            // The tree may still remember a file the disk no longer has - a delete the
            // workspace was never told about. The disk decides whether there is a file.
            // notExists, not !exists: a path whose existence cannot be determined - a drive
            // that is away, a directory that may not be listed - is not a missing file, and
            // falls through to the read below, which fails with the reason.
            return null;
        }
        if (file.exists())
        {
            try (InputStream input = file.getContents())
            {
                return input.readAllBytes();
            }
        }
        return onDisk == null ? null : Files.readAllBytes(onDisk);
    }

    /**
     * Tells whether the cached storage still describes the marker file.
     * <p>
     * The cache is served only while the file holds the bytes it was read from. The comparison
     * reads the file the same way the load does, so an edit made outside the workspace is seen
     * even before the workspace has been told about it.
     * </p>
     * <p>
     * The caller may hold the read lock, so the file handle is built without the legacy
     * carry-over, which writes to disk and belongs under the write lock.
     * </p>
     *
     * @param project the project
     * @return {@code true} when the remembered fingerprint still matches the file
     */
    private boolean cacheIsCurrent(IProject project)
    {
        String remembered = fingerprints.get(project);
        if (remembered == null || !project.isAccessible())
        {
            return false;
        }
        IFile file = project.getFolder(MarkerKeys.SETTINGS_FOLDER)
            .getFile(MarkerKeys.MARKERS_FILE);
        return remembered.equals(diskFingerprint(project, file));
    }

    /**
     * The fingerprint of the marker file as it stands now.
     *
     * @param project the project, for the log line
     * @param file the marker file handle
     * @return the fingerprint, {@link AtomicFileReplace#NO_FILE_FINGERPRINT} when there is no
     *         file, or {@code null} when it cannot be read
     */
    private static String diskFingerprint(IProject project, IFile file)
    {
        try
        {
            byte[] bytes = readMarkersBytes(file);
            return bytes == null ? AtomicFileReplace.NO_FILE_FINGERPRINT
                : AtomicFileReplace.fingerprint(bytes);
        }
        catch (CoreException | IOException e)
        {
            Activator.logError("Could not read the marker file for project " + project.getName(), e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Writes a storage back to a project's marker file.
     * <p>
     * The write runs under the file's lock, against the fingerprint of the bytes the storage
     * was read from, and replaces the file atomically: a file that changed after it was read is
     * left as the changing party wrote it. The settings folder is created when missing, and the
     * workspace holds the written file as a resource without an outside refresh. A project whose
     * file has no location on disk, or whose file was never read, is written through the
     * workspace as before.
     * </p>
     *
     * @param project the project
     * @param storage the storage to write
     * @return <code>true</code> when the file was written; <code>false</code> when it was not
     */
    private String saveMarkerStorage(IProject project, MarkerStore storage)
    {
        if (project == null)
        {
            return MarkerWriteOutcome.SAVE_FAILED;
        }
        IFile file = getMarkersFile(project);
        byte[] bytes = dumpToString(storage).getBytes(StandardCharsets.UTF_8);
        if (file.exists() && !canOverwrite(file))
        {
            Activator.logError("Could not save the marker file for project " + project.getName() //$NON-NLS-1$
                + ": the file is read-only", null); //$NON-NLS-1$
            return MarkerWriteOutcome.READ_ONLY_FILE;
        }
        String expected = fingerprints.get(project);
        IPath location = file.getLocation();
        if (expected == null || location == null)
        {
            return saveThroughWorkspace(project, file, bytes) ? null
                : MarkerWriteOutcome.SAVE_FAILED;
        }
        AtomicFileReplace.Outcome written = AtomicFileReplace.replace(
            location.toFile().toPath(), expected, bytes, file);
        if (!written.isOk())
        {
            Activator.logWarning("Could not save the marker file for project " + project.getName() //$NON-NLS-1$
                + ": " + written); //$NON-NLS-1$
            return replaceRefusal(written);
        }
        fingerprints.put(project, AtomicFileReplace.fingerprint(bytes));
        return null;
    }

    /**
     * The marker-side refusal code of a refused atomic replacement: a file that changed after it
     * was read keeps its own code, a file the system will not open for writing reads as read-only,
     * and everything else is a write that did not happen.
     *
     * @param written the refused replacement
     * @return {@link MarkerWriteOutcome#CHANGED_ON_DISK}, {@link MarkerWriteOutcome#READ_ONLY_FILE}
     *         or {@link MarkerWriteOutcome#SAVE_FAILED}
     */
    private static String replaceRefusal(AtomicFileReplace.Outcome written)
    {
        if (AtomicFileReplace.CHANGED_ON_DISK.equals(written.getCode()))
        {
            return MarkerWriteOutcome.CHANGED_ON_DISK;
        }
        if (AtomicFileReplace.ACCESS_DENIED.equals(written.getCode()))
        {
            return MarkerWriteOutcome.READ_ONLY_FILE;
        }
        return MarkerWriteOutcome.SAVE_FAILED;
    }

    /**
     * Writes the bytes through the workspace, the route used when the file has no location on
     * disk or was never read by this manager. An existing read-only file is refused here as it
     * is on the locked route: a forced workspace write clears the read-only flag and overwrites
     * the file while telling the caller it was saved.
     *
     * @param project the project
     * @param file the marker file handle
     * @param bytes the bytes to write
     * @return <code>true</code> when the file was written
     */
    static boolean saveThroughWorkspace(IProject project, IFile file, byte[] bytes)
    {
        try
        {
            IFolder settingsFolder = project.getFolder(MarkerKeys.SETTINGS_FOLDER);
            if (!settingsFolder.exists())
            {
                settingsFolder.create(true, true, null);
            }
            if (file.exists() && !canOverwrite(file))
            {
                Activator.logError("Could not save the marker file for project " + project.getName() //$NON-NLS-1$
                    + ": the file is read-only", null); //$NON-NLS-1$
                return false;
            }
            try (InputStream input = new ByteArrayInputStream(bytes))
            {
                if (file.exists())
                {
                    file.setContents(input, true, true, null);
                }
                else
                {
                    file.create(input, true, null);
                }
            }
            return true;
        }
        catch (CoreException | IOException e)
        {
            Activator.logError("Could not save the marker file for project " + project.getName(), e); //$NON-NLS-1$
            return false;
        }
    }

    /**

     * Returns the marker file handle for a project.

     *

     * @param project the project

     * @return the file, which may not yet exist

     */

    private IFile getMarkersFile(IProject project)

    {

        IFolder settingsFolder = project.getFolder(MarkerKeys.SETTINGS_FOLDER);
        IPath settingsLocation = settingsFolder.getLocation();
        if (settingsLocation != null)
        {
            try
            {
                if (LegacyStorageMigration.carryOver(settingsLocation.toFile().toPath(),
                    MarkerKeys.LEGACY_MARKERS_FILE, MarkerKeys.MARKERS_FILE))
                {
                    // The carry-over writes straight to disk, which the workspace does not see.
                    // Without this refresh the IFile returned below reports itself absent, the
                    // caller reads an empty store, and the next save writes that emptiness over the
                    // markers just migrated - the upgrade would eat them.
                    settingsFolder.refreshLocal(IResource.DEPTH_ONE, null);
                }
            }
            catch (java.io.IOException | CoreException e)
            {
                Activator.logError("Could not carry " + MarkerKeys.LEGACY_MARKERS_FILE + " over to " //$NON-NLS-1$ //$NON-NLS-2$
                    + MarkerKeys.MARKERS_FILE + " for " + project.getName(), e); //$NON-NLS-1$
            }
        }
        return settingsFolder.getFile(MarkerKeys.MARKERS_FILE);

    }



    /**

     * Serializes a storage to the exact YAML text written to disk: block style, two-space indent, no

     * type markers.

     *

     * @param storage the storage

     * @return the YAML document

     */

    private String dumpToString(MarkerStore storage)

    {

        DumperOptions options = new DumperOptions();

        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);

        options.setPrettyFlow(true);

        options.setIndent(2);



        Representer representer = new Representer(options);

        PropertyUtils propertyUtils = new PropertyUtils();

        propertyUtils.setSkipMissingProperties(true);

        representer.setPropertyUtils(propertyUtils);

        representer.addClassTag(MarkerStore.class, org.yaml.snakeyaml.nodes.Tag.MAP);

        representer.addClassTag(Marker.class, org.yaml.snakeyaml.nodes.Tag.MAP);



        Yaml yaml = new Yaml(representer, options);

        StringWriter writer = new StringWriter();

        yaml.dump(storage, writer);

        return writer.toString();

    }



    /**

     * Builds the reader-side YAML with the marker file's root type fixed, unknown properties ignored, and

     * global YAML markers refused so a git-borne file cannot ask for an arbitrary type.

     *

     * @return the configured reader

     */

    private Yaml createLoadYaml()

    {

        LoaderOptions loaderOptions = new LoaderOptions();

        // Refuse global markers outright. Our files only carry the standard map/sequence/scalar markers,

        // which this predicate is never consulted for, so nothing legitimate breaks and the

        // arbitrary-type deserialization vector stays closed.

        loaderOptions.setTagInspector(marker -> false);



        Constructor constructor = new Constructor(MarkerStore.class, loaderOptions);

        PropertyUtils propertyUtils = new PropertyUtils();

        propertyUtils.setSkipMissingProperties(true);

        constructor.setPropertyUtils(propertyUtils);



        return new Yaml(constructor);

    }

}
