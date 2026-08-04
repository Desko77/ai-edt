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

import java.util.HashMap;

import java.util.HashSet;

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

 * The cache map is guarded by a read/write lock. The lock protects the cache slots, not the storage

 * objects inside them; mutations run on the shared storage after fetching it, which is safe as long as

 * they stay on one thread, as they do in practice.

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

     * Returns a project's marker storage, loading it from disk on the first request and caching it.

     *

     * @param project the project

     * @return the storage; never <code>null</code>, empty when the project has no marker file yet

     */

    public MarkerStore getMarkerStorage(IProject project)

    {

        lock.readLock().lock();

        try

        {

            MarkerStore cached = cache.get(project);

            if (cached != null)

            {

                return cached;

            }

        }

        finally

        {

            lock.readLock().unlock();

        }



        lock.writeLock().lock();

        try

        {

            MarkerStore cached = cache.get(project);

            if (cached != null)

            {

                return cached;

            }

            MarkerStore loaded = loadMarkerStorage(project);

            cache.put(project, loaded);

            return loaded;

        }

        finally

        {

            lock.writeLock().unlock();

        }

    }



    /**

     * Returns the live list of defined markers for a project.

     *

     * @param project the project

     * @return the backing marker list, in user order

     */

    public List<Marker> getMarkers(IProject project)

    {

        return getMarkerStorage(project).getTags();

    }



    /**

     * Defines a new marker.

     *

     * @param project the project

     * @param name the marker name

     * @param color the marker color, or <code>null</code> for the default

     * @param description the marker description, or <code>null</code> for none

     * @return the created marker, or <code>null</code> when a marker with that name already existed

     */

    public Marker createMarker(IProject project, String name, String color, String description)

    {

        MarkerStore storage = getMarkerStorage(project);

        Marker marker = new Marker(name, color, description);

        if (!storage.addMarker(marker))

        {

            return null;

        }

        saveMarkerStorage(project, storage);

        fireMarkersChanged(project);

        return marker;

    }



    /**

     * Updates a marker in place. A <code>null</code> argument leaves that field unchanged.

     * <p>

     * When the name changes, every assignment of the old name is moved to the new one.

     * </p>

     *

     * @param project the project

     * @param oldName the current name of the marker to update

     * @param newName the new name, or <code>null</code> to keep it

     * @param color the new color, or <code>null</code> to keep it

     * @param description the new description, or <code>null</code> to keep it

     * @return <code>true</code> on success; <code>false</code> when the marker is not found or the new

     *         name is already taken

     */

    public boolean updateMarker(IProject project, String oldName, String newName, String color,

        String description)

    {

        MarkerStore storage = getMarkerStorage(project);

        Marker marker = storage.getMarkerByName(oldName);

        if (marker == null)

        {

            return false;

        }



        boolean renaming = newName != null && !newName.equals(oldName);

        if (renaming)

        {

            if (storage.getMarkerByName(newName) != null)

            {

                return false;

            }

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

        saveMarkerStorage(project, storage);

        fireMarkersChanged(project);

        return true;

    }



    /**

     * Deletes a marker and removes it from every object it was on.

     *

     * @param project the project

     * @param markerName the name of the marker to delete

     * @return <code>true</code> when the marker existed

     */

    public boolean deleteMarker(IProject project, String markerName)

    {

        MarkerStore storage = getMarkerStorage(project);

        if (!storage.removeMarker(markerName))

        {

            return false;

        }

        saveMarkerStorage(project, storage);

        fireMarkersChanged(project);

        return true;

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

        return getMarkerStorage(project).getObjectMarkers(objectFqn);

    }



    /**

     * Assigns a marker to an object.

     *

     * @param project the project

     * @param objectFqn the object FQN

     * @param markerName the marker name; must already be defined

     * @return <code>true</code> when the assignment was newly added

     */

    public boolean assignMarker(IProject project, String objectFqn, String markerName)

    {

        MarkerStore storage = getMarkerStorage(project);

        if (!storage.assignMarker(objectFqn, markerName))

        {

            return false;

        }

        saveMarkerStorage(project, storage);

        fireAssignmentsChanged(project, objectFqn);

        return true;

    }



    /**

     * Removes a marker from an object.

     *

     * @param project the project

     * @param objectFqn the object FQN

     * @param markerName the marker name

     * @return <code>true</code> when the marker was assigned and has been removed

     */

    public boolean unassignMarker(IProject project, String objectFqn, String markerName)

    {

        MarkerStore storage = getMarkerStorage(project);

        if (!storage.unassignMarker(objectFqn, markerName))

        {

            return false;

        }

        saveMarkerStorage(project, storage);

        fireAssignmentsChanged(project, objectFqn);

        return true;

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

     * Moves an object's assignments to a new FQN, as when it is renamed.

     *

     * @param project the project

     * @param oldFqn the current FQN

     * @param newFqn the new FQN

     * @return <code>true</code> when the object had assignments to move

     */

    public boolean renameObject(IProject project, String oldFqn, String newFqn)

    {

        MarkerStore storage = getMarkerStorage(project);

        if (!storage.renameObject(oldFqn, newFqn))

        {

            return false;

        }

        saveMarkerStorage(project, storage);

        fireAssignmentsChanged(project, newFqn);

        return true;

    }



    /**

     * Drops an object's assignments, as when it is deleted.

     *

     * @param project the project

     * @param objectFqn the object FQN

     * @return <code>true</code> when the object had assignments

     */

    public boolean removeObject(IProject project, String objectFqn)

    {

        MarkerStore storage = getMarkerStorage(project);

        if (!storage.removeObject(objectFqn))

        {

            return false;

        }

        saveMarkerStorage(project, storage);

        fireAssignmentsChanged(project, objectFqn);

        return true;

    }



    /**

     * Moves a marker one place earlier in the order, which also shifts the keyboard shortcuts.

     *

     * @param project the project

     * @param markerName the marker to move

     * @return <code>true</code> when it moved

     */

    public boolean moveMarkerUp(IProject project, String markerName)

    {

        MarkerStore storage = getMarkerStorage(project);

        if (!storage.moveMarkerUp(markerName))

        {

            return false;

        }

        saveMarkerStorage(project, storage);

        fireMarkersChanged(project);

        return true;

    }



    /**

     * Moves a marker one place later in the order, which also shifts the keyboard shortcuts.

     *

     * @param project the project

     * @param markerName the marker to move

     * @return <code>true</code> when it moved

     */

    public boolean moveMarkerDown(IProject project, String markerName)

    {

        MarkerStore storage = getMarkerStorage(project);

        if (!storage.moveMarkerDown(markerName))

        {

            return false;

        }

        saveMarkerStorage(project, storage);

        fireMarkersChanged(project);

        return true;

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

     *

     * @param project the project to evict

     */

    private void evict(IProject project)

    {

        lock.writeLock().lock();

        try

        {

            cache.remove(project);

        }

        finally

        {

            lock.writeLock().unlock();

        }

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

     * Reads a project's marker file into a storage.

     * <p>

     * A missing or empty file, an I/O failure, or malformed YAML all resolve to an empty storage

     * rather than an error, so the decorator and filters always have something to work with. The load

     * also ignores properties it does not know, so a file written by a newer version still reads.

     * </p>

     *

     * @param project the project

     * @return the loaded storage, or an empty one

     */

    private MarkerStore loadMarkerStorage(IProject project)

    {

        if (project == null || !project.isAccessible())

        {

            return new MarkerStore();

        }

        IFile file = getMarkersFile(project);

        if (file == null || !file.exists())

        {

            return new MarkerStore();

        }

        try (InputStream input = file.getContents();

            Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8))

        {

            MarkerStore storage = createLoadYaml().load(reader);

            return storage != null ? storage : new MarkerStore();

        }

        catch (CoreException | IOException e)

        {

            Activator.logError("Could not read the marker file for project " + project.getName(), e); //$NON-NLS-1$

            return new MarkerStore();

        }

        catch (YAMLException e)

        {

            // Corrupt YAML, a git merge-conflict marker, or an unreadable property. Degrade to empty

            // rather than let the exception escape and leave the decorator permanently broken.

            Activator.logError("Could not parse the marker file for project " + project.getName(), e); //$NON-NLS-1$

            return new MarkerStore();

        }

    }



    /**

     * Writes a storage back to a project's marker file, creating the settings folder if needed.

     *

     * @param project the project

     * @param storage the storage to write

     */

    private void saveMarkerStorage(IProject project, MarkerStore storage)

    {

        if (project == null)

        {

            return;

        }

        try

        {

            IFolder settingsFolder = project.getFolder(MarkerKeys.SETTINGS_FOLDER);

            if (!settingsFolder.exists())

            {

                settingsFolder.create(true, true, null);

            }

            IFile file = settingsFolder.getFile(MarkerKeys.MARKERS_FILE);

            byte[] bytes = dumpToString(storage).getBytes(StandardCharsets.UTF_8);

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

        }

        catch (CoreException | IOException e)

        {

            Activator.logError("Could not save the marker file for project " + project.getName(), e); //$NON-NLS-1$

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

