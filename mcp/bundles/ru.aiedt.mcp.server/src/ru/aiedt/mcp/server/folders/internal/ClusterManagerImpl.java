/**

 * AI-EDT - 1C AI tools for EDT

 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)

 * Licensed under AGPL-3.0-or-later

 */



package ru.aiedt.mcp.server.folders.internal;



import java.io.IOException;

import java.nio.file.ClosedWatchServiceException;

import java.nio.file.FileSystems;

import java.nio.file.Files;

import java.nio.file.Path;

import java.nio.file.StandardWatchEventKinds;

import java.nio.file.WatchEvent;

import java.nio.file.WatchKey;

import java.nio.file.WatchService;

import java.util.HashMap;

import java.util.HashSet;

import java.util.List;

import java.util.Map;

import java.util.Set;

import java.util.concurrent.CopyOnWriteArrayList;

import java.util.concurrent.atomic.AtomicBoolean;

import java.util.concurrent.locks.ReadWriteLock;

import java.util.concurrent.locks.ReentrantReadWriteLock;

import java.util.function.Predicate;



import org.eclipse.core.resources.IContainer;

import org.eclipse.core.resources.IFile;

import org.eclipse.core.resources.IProject;

import org.eclipse.core.resources.IResource;

import org.eclipse.core.resources.IResourceChangeEvent;

import org.eclipse.core.resources.IResourceChangeListener;

import org.eclipse.core.resources.IResourceDelta;

import org.eclipse.core.resources.IWorkspaceRoot;

import org.eclipse.core.resources.ResourcesPlugin;

import org.eclipse.core.runtime.CoreException;

import org.eclipse.core.runtime.IPath;



import ru.aiedt.mcp.server.Activator;

import ru.aiedt.mcp.server.folders.ClusterKeys;

import ru.aiedt.mcp.server.folders.IClusterChangeObserver;

import ru.aiedt.mcp.server.folders.IClusterManager;

import ru.aiedt.mcp.server.folders.model.Cluster;

import ru.aiedt.mcp.server.folders.model.ClusterStore;

import ru.aiedt.mcp.server.folders.repository.IClusterStore;

import ru.aiedt.mcp.server.folders.repository.YamlClusterStore;



/**

 * The working cluster service.

 * <p>

 * It keeps one {@link ClusterStore} per project in a cache backed by the file on disk, and reads and

 * writes that file through the repository. It is wired imperatively from the plugin activator rather

 * than published as a declarative service, because it needs the plugin and the plugin needs it.

 * </p>

 * <p>

 * Locking. A read-write lock guards both the cache and, by extension, the contents of the cached

 * storage: every read of a storage's contents is taken under the read lock, and every edit-and-save

 * under the write lock, so a background refactoring or resource-change thread cannot see a

 * half-applied edit. The cache is loaded lazily, on first touch of a project.

 * </p>

 * <p>

 * Two background paths feed the cache without holding it stale. A daemon watcher notices the file

 * being rewritten from outside and asks the workspace to catch up, which turns into a resource-change

 * event; the resource listener drops the affected project's cache entry so the next read reloads.

 * </p>

 */

public class ClusterManagerImpl

    implements IClusterManager, IResourceChangeListener

{

    private static final String WATCHER_THREAD_NAME = "ClusterService-FileWatcher"; //$NON-NLS-1$



    private static final long WATCHER_JOIN_MILLIS = 2000L;



    private final IClusterStore repository = new YamlClusterStore();



    private final Map<String, ClusterStore> projectStorageCache = new HashMap<>();



    private final ReadWriteLock cacheLock = new ReentrantReadWriteLock();



    private final CopyOnWriteArrayList<IClusterChangeObserver> listeners = new CopyOnWriteArrayList<>();



    private final AtomicBoolean shutdown = new AtomicBoolean(false);



    private final Object watcherLock = new Object();



    private final Map<WatchKey, Path> watchKeyToPath = new HashMap<>();



    private final Set<String> watchedProjects = new HashSet<>();



    private volatile WatchService watchService;



    private volatile Thread watchThread;



    /**

     * Brings the service up: starts listening for resource changes and starts the file watcher.

     */

    public void activate()

    {

        ResourcesPlugin.getWorkspace().addResourceChangeListener(this, IResourceChangeEvent.POST_CHANGE);

        startWatcher();

        Activator.logInfo("ClusterService activated"); //$NON-NLS-1$

    }



    /**

     * Takes the service down: stops listening, stops the watcher, and clears the cache and listeners.

     */

    public void deactivate()

    {

        shutdown.set(true);

        try

        {

            ResourcesPlugin.getWorkspace().removeResourceChangeListener(this);

        }

        catch (RuntimeException e)

        {

            // The workspace may already be gone at shutdown; there is nothing to detach from.

        }

        stopWatcher();

        cacheLock.writeLock().lock();

        try

        {

            projectStorageCache.clear();

        }

        finally

        {

            cacheLock.writeLock().unlock();

        }

        listeners.clear();

        Activator.logInfo("ClusterService deactivated"); //$NON-NLS-1$

    }



    @Override

    public ClusterStore getClusterStorage(IProject project)

    {

        if (project == null)

        {

            return new ClusterStore();

        }

        cacheLock.readLock().lock();

        try

        {

            ClusterStore cached = projectStorageCache.get(project.getName());

            if (cached != null)

            {

                return cached;

            }

        }

        finally

        {

            cacheLock.readLock().unlock();

        }

        cacheLock.writeLock().lock();

        try

        {

            return loadStorageLocked(project);

        }

        finally

        {

            cacheLock.writeLock().unlock();

        }

    }



    @Override

    public List<Cluster> getClustersAtPath(IProject project, String path)

    {

        ClusterStore storage = getClusterStorage(project);

        cacheLock.readLock().lock();

        try

        {

            return storage.getClustersAtPath(path);

        }

        finally

        {

            cacheLock.readLock().unlock();

        }

    }



    @Override

    public List<Cluster> getAllClusters(IProject project)

    {

        ClusterStore storage = getClusterStorage(project);

        cacheLock.readLock().lock();

        try

        {

            return storage.getGroups();

        }

        finally

        {

            cacheLock.readLock().unlock();

        }

    }



    @Override

    public Cluster createCluster(IProject project, String name, String path, String description)

    {

        Cluster created;

        cacheLock.writeLock().lock();

        try

        {

            ClusterStore storage = loadStorageLocked(project);

            if (storage.getClusterByFullPath(buildFullPath(path, name)) != null)

            {

                return null;

            }

            Cluster cluster = new Cluster(name, path);

            cluster.setDescription(description);

            cluster.setOrder(nextOrderAtPath(storage, path));

            storage.addCluster(cluster);

            repository.save(project, storage);

            created = cluster;

        }

        finally

        {

            cacheLock.writeLock().unlock();

        }

        fireClustersChanged(project);

        return created;

    }



    @Override

    public boolean renameCluster(IProject project, String oldFullPath, String newName)

    {

        return mutate(project, storage -> storage.renameCluster(oldFullPath, newName));

    }



    @Override

    public boolean updateCluster(IProject project, String oldFullPath, String newName, String description)

    {

        return mutate(project, storage -> storage.updateCluster(oldFullPath, newName, description));

    }



    @Override

    public boolean deleteCluster(IProject project, String fullPath)

    {

        return mutate(project, storage -> storage.removeCluster(fullPath));

    }



    @Override

    public boolean addObjectToCluster(IProject project, String objectFqn, String clusterFullPath)

    {

        return mutate(project, storage -> storage.moveObjectToCluster(objectFqn, clusterFullPath));

    }



    @Override

    public boolean removeObjectFromCluster(IProject project, String objectFqn)

    {

        return mutate(project, storage -> storage.removeObjectFromAllClusters(objectFqn));

    }



    @Override

    public Cluster findClusterForObject(IProject project, String objectFqn)

    {

        ClusterStore storage = getClusterStorage(project);

        cacheLock.readLock().lock();

        try

        {

            return storage.findClusterForObject(objectFqn);

        }

        finally

        {

            cacheLock.readLock().unlock();

        }

    }



    @Override

    public Set<String> getClusteredObjectsAtPath(IProject project, String path)

    {

        ClusterStore storage = getClusterStorage(project);

        cacheLock.readLock().lock();

        try

        {

            return storage.getClusteredObjectsAtPath(path);

        }

        finally

        {

            cacheLock.readLock().unlock();

        }

    }



    @Override

    public boolean hasClustersAtPath(IProject project, String path)

    {

        ClusterStore storage = getClusterStorage(project);

        cacheLock.readLock().lock();

        try

        {

            return storage.hasClustersAtPath(path);

        }

        finally

        {

            cacheLock.readLock().unlock();

        }

    }



    @Override

    public void refresh(IProject project)

    {

        invalidateCache(project);

        fireClustersChanged(project);

    }



    @Override

    public boolean renameObject(IProject project, String oldFqn, String newFqn)

    {

        return mutate(project, storage -> storage.renameObject(oldFqn, newFqn));

    }



    @Override

    public boolean removeObject(IProject project, String objectFqn)

    {

        return mutate(project, storage -> storage.removeObjectFromAllClusters(objectFqn));

    }



    @Override

    public void addClusterChangeListener(IClusterChangeObserver listener)

    {

        if (listener != null && !listeners.contains(listener))

        {

            listeners.add(listener);

        }

    }



    @Override

    public void removeClusterChangeListener(IClusterChangeObserver listener)

    {

        listeners.remove(listener);

    }



    @Override

    public void resourceChanged(IResourceChangeEvent event)

    {

        if (shutdown.get())

        {

            return;

        }

        IResourceDelta delta = event.getDelta();

        if (delta == null)

        {

            return;

        }

        Set<IProject> affected = new HashSet<>();

        try

        {

            delta.accept(childDelta -> {

                IResource resource = childDelta.getResource();

                if (resource instanceof IFile && ClusterKeys.CLUSTERS_FILE.equals(resource.getName()))

                {

                    IContainer parent = resource.getParent();

                    if (parent != null && ClusterKeys.SETTINGS_FOLDER.equals(parent.getName()))

                    {

                        IProject project = resource.getProject();

                        if (project != null)

                        {

                            affected.add(project);

                        }

                    }

                    return false;

                }

                return true;

            });

        }

        catch (CoreException e)

        {

            Activator.logError("Failed to process a resource change for clusters", e); //$NON-NLS-1$

        }

        for (IProject project : affected)

        {

            invalidateCache(project);

        }

        for (IProject project : affected)

        {

            fireClustersChanged(project);

        }

    }



    /**

     * Applies an edit to a project's storage and, if it changed anything, saves and notifies.

     * <p>

     * The edit runs under the write lock, so the storage cannot be read half-changed elsewhere; the

     * notification is sent after the lock is dropped.

     * </p>

     *

     * @param project the project

     * @param edit the edit, returning whether it changed anything

     * @return whatever the edit returned

     */

    private boolean mutate(IProject project, Predicate<ClusterStore> edit)

    {

        boolean changed;

        cacheLock.writeLock().lock();

        try

        {

            ClusterStore storage = loadStorageLocked(project);

            changed = edit.test(storage);

            if (changed)

            {

                repository.save(project, storage);

            }

        }

        finally

        {

            cacheLock.writeLock().unlock();

        }

        if (changed)

        {

            fireClustersChanged(project);

        }

        return changed;

    }



    /**

     * Returns a project's storage, loading it into the cache if it is not there yet.

     * <p>

     * The caller must hold the write lock.

     * </p>

     *

     * @param project the project

     * @return the cached storage

     */

    private ClusterStore loadStorageLocked(IProject project)

    {

        String key = project.getName();

        ClusterStore cached = projectStorageCache.get(key);

        if (cached != null)

        {

            return cached;

        }

        ensureProjectWatched(project);

        ClusterStore loaded = repository.load(project);

        projectStorageCache.put(key, loaded);

        return loaded;

    }



    /**

     * Drops a project's cache entry.

     *

     * @param project the project

     */

    private void invalidateCache(IProject project)

    {

        cacheLock.writeLock().lock();

        try

        {

            projectStorageCache.remove(project.getName());

        }

        finally

        {

            cacheLock.writeLock().unlock();

        }

    }



    /**

     * Tells every listener that a project's clusters changed, guarding against a listener that throws.

     *

     * @param project the project

     */

    private void fireClustersChanged(IProject project)

    {

        for (IClusterChangeObserver listener : listeners)

        {

            try

            {

                listener.onClustersChanged(project);

            }

            catch (RuntimeException e)

            {

                Activator.logError("A cluster change listener failed", e); //$NON-NLS-1$

            }

        }

    }



    /**

     * Starts the daemon file watcher.

     */

    private void startWatcher()

    {

        try

        {

            watchService = FileSystems.getDefault().newWatchService();

        }

        catch (IOException e)

        {

            Activator.logError("Could not start the clusters file watcher", e); //$NON-NLS-1$

            return;

        }

        Thread thread = new Thread(this::runWatchLoop, WATCHER_THREAD_NAME);

        thread.setDaemon(true);

        watchThread = thread;

        thread.start();

    }



    /**

     * Stops the watcher: interrupts and joins the thread, cancels the keys, and closes the service.

     */

    private void stopWatcher()

    {

        Thread thread = watchThread;

        if (thread != null)

        {

            thread.interrupt();

            try

            {

                thread.join(WATCHER_JOIN_MILLIS);

            }

            catch (InterruptedException e)

            {

                Thread.currentThread().interrupt();

            }

        }

        watchThread = null;

        synchronized (watcherLock)

        {

            for (WatchKey key : watchKeyToPath.keySet())

            {

                key.cancel();

            }

            watchKeyToPath.clear();

            watchedProjects.clear();

        }

        WatchService service = watchService;

        if (service != null)

        {

            try

            {

                service.close();

            }

            catch (IOException e)

            {

                // Closing on the way down; nothing useful to do with the failure.

            }

        }

        watchService = null;

    }



    /**

     * The watcher loop: waits for a settings folder to change and, when it is the clusters file, asks the

     * workspace to refresh it.

     */

    private void runWatchLoop()

    {

        WatchService service = watchService;

        if (service == null)

        {

            return;

        }

        while (!shutdown.get())

        {

            WatchKey key;

            try

            {

                key = service.take();

            }

            catch (ClosedWatchServiceException e)

            {

                break;

            }

            catch (InterruptedException e)

            {

                Thread.currentThread().interrupt();

                break;

            }

            Path directory;

            synchronized (watcherLock)

            {

                directory = watchKeyToPath.get(key);

            }

            if (directory != null)

            {

                for (WatchEvent<?> event : key.pollEvents())

                {

                    handleWatchEvent(directory, event);

                }

            }

            if (!key.reset())

            {

                synchronized (watcherLock)

                {

                    watchKeyToPath.remove(key);

                }

            }

        }

    }



    /**

     * Reacts to a single filesystem event, refreshing the clusters file when that is what changed.

     *

     * @param directory the watched settings directory the event came from

     * @param event the event

     */

    private void handleWatchEvent(Path directory, WatchEvent<?> event)

    {

        Object context = event.context();

        if (!(context instanceof Path))

        {

            return;

        }

        Path changed = (Path)context;

        if (!ClusterKeys.CLUSTERS_FILE.equals(changed.getFileName().toString()))

        {

            return;

        }

        Path fullPath = directory.resolve(changed);

        try

        {

            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();

            IFile file = root.getFileForLocation(IPath.fromOSString(fullPath.toString()));

            if (file != null)

            {

                file.refreshLocal(IResource.DEPTH_ONE, null);

            }

        }

        catch (CoreException e)

        {

            if (!shutdown.get())

            {

                Activator.logError("Could not refresh aiedt-clusters.yaml after an external change", e); //$NON-NLS-1$

            }

        }

    }



    /**

     * Registers a project's settings folder with the watcher, once, creating the folder if missing.

     * <p>

     * The caller holds the write lock; this takes the watcher lock inside it. Nothing takes those two

     * locks in the other order, so the nesting is safe.

     * </p>

     *

     * @param project the project

     */

    private void ensureProjectWatched(IProject project)

    {

        WatchService service = watchService;

        if (service == null)

        {

            return;

        }

        String key = project.getName();

        synchronized (watcherLock)

        {

            if (watchedProjects.contains(key))

            {

                return;

            }

            IPath projectLocation = project.getLocation();

            if (projectLocation == null)

            {

                // A non-local project cannot be watched; mark it so we do not probe on every access.

                watchedProjects.add(key);

                return;

            }

            Path settingsDir = projectLocation.toFile().toPath().resolve(ClusterKeys.SETTINGS_FOLDER);

            try

            {

                Files.createDirectories(settingsDir);

                WatchKey watchKey = settingsDir.register(service, StandardWatchEventKinds.ENTRY_MODIFY,

                    StandardWatchEventKinds.ENTRY_CREATE);

                watchKeyToPath.put(watchKey, settingsDir);

                watchedProjects.add(key);

            }

            catch (IOException e)

            {

                Activator.logError("Could not watch the settings folder of " + key, e); //$NON-NLS-1$

            }

        }

    }



    /**

     * Returns one past the highest order among the clusters at a path, or zero when there are none.

     *

     * @param storage the storage

     * @param path the collection path

     * @return the order to give a new cluster at that path

     */

    private static int nextOrderAtPath(ClusterStore storage, String path)

    {

        int max = -1;

        for (Cluster cluster : storage.getClustersAtPath(path))

        {

            max = Math.max(max, cluster.getOrder());

        }

        return max + 1;

    }



    /**

     * Builds a full path from a path and a name, the way {@link Cluster#getFullPath()} does.

     *

     * @param path the path; may be <code>null</code> or empty

     * @param name the name

     * @return the full path

     */

    private static String buildFullPath(String path, String name)

    {

        if (path == null || path.isEmpty())

        {

            return name;

        }

        return path + "/" + name; //$NON-NLS-1$

    }

}

