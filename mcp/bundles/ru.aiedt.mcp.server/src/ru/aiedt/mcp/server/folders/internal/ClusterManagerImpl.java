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

import java.util.ArrayList;
import java.util.HashMap;

import java.util.HashSet;

import java.util.List;

import java.util.Map;

import java.util.Set;

import java.util.concurrent.CopyOnWriteArrayList;

import java.util.concurrent.atomic.AtomicBoolean;

import java.util.concurrent.locks.ReadWriteLock;

import java.util.concurrent.locks.ReentrantReadWriteLock;

import java.util.Objects;
import java.util.function.Function;



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

import ru.aiedt.mcp.server.folders.ClusterWriteOutcome;
import ru.aiedt.mcp.server.folders.IClusterManager;

import ru.aiedt.mcp.server.folders.model.Cluster;

import ru.aiedt.mcp.server.folders.model.ClusterStore;

import ru.aiedt.mcp.server.folders.repository.ClusterSaveOutcome;
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

 * A change the service itself wrote is recognized by the bytes the file carries and skipped: the

 * cache the write produced is current, and the write path has already told the listeners.

 * </p>

 */

public class ClusterManagerImpl

    implements IClusterManager, IResourceChangeListener

{

    private static final String WATCHER_THREAD_NAME = "ClusterService-FileWatcher"; //$NON-NLS-1$



    private static final long WATCHER_JOIN_MILLIS = 2000L;



    private final IClusterStore repository;



    private final Map<String, ClusterStore> projectStorageCache = new HashMap<>();

    /**

     * Projects whose clusters file could not be loaded since the cache entry was last dropped. The

     * file is not read again until {@link #invalidateCache} or {@link #refresh} clears the mark, so

     * a tree that asks once per element does not read and log the same unreadable file each time.

     */

    private final java.util.Set<String> failedLoads = new java.util.HashSet<>();



    private final ReadWriteLock cacheLock = new ReentrantReadWriteLock();



    private final CopyOnWriteArrayList<IClusterChangeObserver> listeners = new CopyOnWriteArrayList<>();



    private final AtomicBoolean shutdown = new AtomicBoolean(false);



    private final Object watcherLock = new Object();



    private final Map<WatchKey, Path> watchKeyToPath = new HashMap<>();



    private final Set<String> watchedProjects = new HashSet<>();



    private volatile WatchService watchService;



    private volatile Thread watchThread;

    /**
     * Creates a manager backed by the YAML cluster repository.
     */
    public ClusterManagerImpl()
    {
        this(new YamlClusterStore());
    }

    /**
     * Creates a manager backed by the supplied repository.
     *
     * @param repository the repository used for every load and save
     */
    ClusterManagerImpl(IClusterStore repository)
    {
        if (repository == null)
        {
            throw new IllegalArgumentException("repository must not be null"); //$NON-NLS-1$
        }
        this.repository = repository;
    }



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
     * Answers a question from the project's store without copying the store.
     * <p>
     * The Navigator filter asks about every object it draws, on the UI thread. A detached copy of
     * the whole store per question would allocate the store once per object; the question is put
     * to the cached store under the lock instead, and detaches only what it returns.
     * </p>
     *
     * @param <T> what the question answers
     * @param project the project; <code>null</code> is answered from an empty store
     * @param question reads the store and returns a value that shares nothing with it
     * @return the answer
     */
    private <T> T serveFromStorage(IProject project, java.util.function.Function<ClusterStore, T> question)
    {
        if (project == null)
        {
            return question.apply(new ClusterStore());
        }
        cacheLock.readLock().lock();
        try
        {
            ClusterStore cached = projectStorageCache.get(project.getName());
            if (cached != null)
            {
                return question.apply(cached);
            }
        }
        finally
        {
            cacheLock.readLock().unlock();
        }
        cacheLock.writeLock().lock();
        try
        {
            ClusterStore loaded = loadStorageLocked(project);
            return question.apply(loaded == null ? new ClusterStore() : loaded);
        }
        finally
        {
            cacheLock.writeLock().unlock();
        }
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

            failedLoads.clear();

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

                return cached.detachedCopy();

            }

        }

        finally

        {

            cacheLock.readLock().unlock();

        }

        cacheLock.writeLock().lock();

        try

        {

            ClusterStore loaded = loadStorageLocked(project);
            return loaded == null ? new ClusterStore() : loaded.detachedCopy();

        }

        finally

        {

            cacheLock.writeLock().unlock();

        }

    }



    @Override

    public List<Cluster> getClustersAtPath(IProject project, String path)

    {
        return serveFromStorage(project, storage -> detachedCopies(storage.getClustersAtPath(path)));
    }



    @Override

    public List<Cluster> getAllClusters(IProject project)

    {
        return serveFromStorage(project, storage -> detachedCopies(storage.getGroups()));
    }



    /**
     * Creates a cluster at a path and saves it.
     * <p>
     * When the file refuses the write the cluster is not kept and listeners are not told it
     * appeared. A full path that is already taken is {@link ClusterWriteOutcome#CLUSTER_EXISTS}
     * and does not write.
     * </p>
     *
     * @param project the project
     * @param name the cluster name
     * @param path the collection path; may be {@code null} for a root cluster
     * @param description the description; may be {@code null}
     * @return the outcome, carrying the created cluster when the create was kept
     */
    @Override
    public ClusterWriteOutcome createCluster(IProject project, String name, String path, String description)
    {
        Cluster created = null;
        ClusterSaveOutcome saved = null;
        cacheLock.writeLock().lock();
        try
        {
            ClusterStore storage = loadStorageLocked(project);
            if (storage == null)
            {
                return unread();
            }
            if (storage.getClusterByFullPath(buildFullPath(path, name)) != null)
            {
                return ClusterWriteOutcome.domain(ClusterWriteOutcome.CLUSTER_EXISTS);
            }
            Cluster cluster = new Cluster(name, path);
            cluster.setDescription(description);
            cluster.setOrder(nextOrderAtPath(storage, path));
            storage.addCluster(cluster);
            saved = repository.save(project, storage);
            if (saved.isRefused())
            {
                projectStorageCache.remove(project.getName());
                return ClusterWriteOutcome.of(saved);
            }
            created = cluster.detachedCopy();
        }
        finally
        {
            cacheLock.writeLock().unlock();
        }
        fireClustersChanged(project);
        return ClusterWriteOutcome.of(saved, created);
    }



    /**
     * Renames a cluster, changing only its name.
     *
     * @param project the project
     * @param oldFullPath the full path of the cluster to rename
     * @param newName the new name
     * @return the outcome of the rename
     */
    @Override
    public ClusterWriteOutcome renameCluster(IProject project, String oldFullPath, String newName)
    {
        return mutate(project, storage -> {
            Cluster cluster = storage.getClusterByFullPath(oldFullPath);
            if (cluster == null)
            {
                return ClusterWriteOutcome.domain(ClusterWriteOutcome.CLUSTER_NOT_FOUND);
            }
            if (nameTaken(storage, cluster, newName))
            {
                return ClusterWriteOutcome.domain(ClusterWriteOutcome.NAME_TAKEN);
            }
            if (Objects.equals(cluster.getName(), newName))
            {
                return ClusterWriteOutcome.of(ClusterSaveOutcome.noChange());
            }
            storage.renameCluster(oldFullPath, newName);
            return null;
        });
    }



    /**
     * Renames a cluster and sets its description.
     *
     * @param project the project
     * @param oldFullPath the full path of the cluster to update
     * @param newName the new name
     * @param description the new description; may be {@code null}
     * @return the outcome of the update
     */
    @Override
    public ClusterWriteOutcome updateCluster(IProject project, String oldFullPath, String newName,
        String description)
    {
        return mutate(project, storage -> {
            Cluster cluster = storage.getClusterByFullPath(oldFullPath);
            if (cluster == null)
            {
                return ClusterWriteOutcome.domain(ClusterWriteOutcome.CLUSTER_NOT_FOUND);
            }
            if (nameTaken(storage, cluster, newName))
            {
                return ClusterWriteOutcome.domain(ClusterWriteOutcome.NAME_TAKEN);
            }
            boolean sameName = Objects.equals(cluster.getName(), newName);
            boolean sameDescription = Objects.equals(cluster.getDescription(), description);
            if (sameName && sameDescription)
            {
                return ClusterWriteOutcome.of(ClusterSaveOutcome.noChange());
            }
            storage.updateCluster(oldFullPath, newName, description);
            return null;
        });
    }



    /**
     * Deletes a cluster and any clusters nested under it.
     *
     * @param project the project
     * @param fullPath the full path of the cluster to delete
     * @return the outcome of the delete
     */
    @Override
    public ClusterWriteOutcome deleteCluster(IProject project, String fullPath)
    {
        return mutate(project, storage -> {
            if (storage.getClusterByFullPath(fullPath) == null)
            {
                return ClusterWriteOutcome.domain(ClusterWriteOutcome.CLUSTER_NOT_FOUND);
            }
            storage.removeCluster(fullPath);
            return null;
        });
    }



    /**
     * Moves an object into a cluster, taking it out of any cluster it is in now.
     *
     * @param project the project
     * @param objectFqn the fully qualified name of the object
     * @param clusterFullPath the full path of the target cluster
     * @return the outcome of the move
     */
    @Override
    public ClusterWriteOutcome addObjectToCluster(IProject project, String objectFqn, String clusterFullPath)
    {
        return mutate(project, storage -> {
            Cluster target = storage.getClusterByFullPath(clusterFullPath);
            if (target == null)
            {
                return ClusterWriteOutcome.domain(ClusterWriteOutcome.CLUSTER_NOT_FOUND);
            }
            Cluster current = storage.findClusterForObject(objectFqn);
            if (current == target)
            {
                return ClusterWriteOutcome.of(ClusterSaveOutcome.noChange());
            }
            storage.moveObjectToCluster(objectFqn, clusterFullPath);
            return null;
        });
    }



    /**
     * Removes an object from every cluster holding it.
     *
     * @param project the project
     * @param objectFqn the fully qualified name of the object
     * @return the outcome of the removal
     */
    @Override
    public ClusterWriteOutcome removeObjectFromCluster(IProject project, String objectFqn)
    {
        return mutate(project, storage -> {
            if (storage.findClusterForObject(objectFqn) == null)
            {
                return ClusterWriteOutcome.of(ClusterSaveOutcome.noChange());
            }
            storage.removeObjectFromAllClusters(objectFqn);
            return null;
        });
    }



    @Override

    public Cluster findClusterForObject(IProject project, String objectFqn)

    {
        return serveFromStorage(project, storage -> {
            Cluster found = storage.findClusterForObject(objectFqn);
            return found == null ? null : found.detachedCopy();
        });
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

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean holdsObjectOrDescendant(IProject project, String objectFqn)
    {
        ClusterStore storage = getClusterStorage(project);
        cacheLock.readLock().lock();
        try
        {
            return storage.holdsObjectOrDescendant(objectFqn);
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



    /**
     * Renames an object's fully qualified name in every cluster that named it.
     *
     * @param project the project
     * @param oldFqn the current fully qualified name
     * @param newFqn the fully qualified name to give it
     * @return the outcome of the rename
     */
    @Override
    public ClusterWriteOutcome renameObject(IProject project, String oldFqn, String newFqn)
    {
        return mutate(project, storage -> {
            if (!storage.renameObject(oldFqn, newFqn))
            {
                return ClusterWriteOutcome.of(ClusterSaveOutcome.noChange());
            }
            return null;
        });
    }



    /**
     * Removes an object, and every name nested under it, from every cluster naming either.
     *
     * @param project the project
     * @param objectFqn the fully qualified name of the object
     * @return the outcome of the removal
     */
    @Override
    public ClusterWriteOutcome removeObject(IProject project, String objectFqn)
    {
        return removeHeldObject(project, objectFqn);
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

            clustersFileTouched(project);

        }

    }



    /**

     * Reacts to a project's clusters file having changed on disk.

     * <p>

     * A change this service itself wrote does not count: the cache the write produced already

     * holds the file's clusters and the write path has told the listeners, so dropping the cache

     * would only force the next read to reload what it has, and the listeners would hear the

     * same change twice. The file carrying the bytes this store last saw is exactly that case.

     * </p>

     *

     * @param project the project whose clusters file changed

     */

    void clustersFileTouched(IProject project)

    {

        if (repository.holdsWhatWasLastReadOrWritten(project))

        {

            return;

        }

        invalidateCache(project);

        fireClustersChanged(project);

    }



    /**
     * Applies an edit to a project's storage and, when the edit changed anything and the file
     * accepted the write, notifies listeners.
     * <p>
     * The edit runs under the write lock. It returns an outcome to stop without saving, which it
     * must do only when it has not changed the storage. It returns {@code null} after changing the
     * storage, and this method then saves. A save that is refused drops the cached storage, so the
     * next read reloads the file that is actually on disk, and does not notify listeners.
     * </p>
     *
     * @param project the project
     * @param edit the edit; a {@code null} result means the storage changed and must be saved
     * @return the edit's outcome, or the save when the edit changed the storage
     */
    private ClusterWriteOutcome mutate(IProject project, Function<ClusterStore, ClusterWriteOutcome> edit)
    {
        ClusterWriteOutcome outcome = null;
        boolean changed = false;
        cacheLock.writeLock().lock();
        try
        {
            ClusterStore storage = loadStorageLocked(project);
            if (storage == null)
            {
                return unread();
            }
            ClusterWriteOutcome decided = edit.apply(storage);
            if (decided != null)
            {
                return decided;
            }
            changed = true;
            ClusterSaveOutcome saved = repository.save(project, storage);
            if (saved.isRefused())
            {
                projectStorageCache.remove(project.getName());
            }
            outcome = ClusterWriteOutcome.of(saved);
        }
        finally
        {
            cacheLock.writeLock().unlock();
        }
        if (changed && outcome != null && outcome.succeeded())
        {
            fireClustersChanged(project);
        }
        return outcome;
    }

    /**
     * The outcome of an edit whose clusters could not be loaded.
     *
     * @return a refused read
     */
    private static ClusterWriteOutcome unread()
    {
        return ClusterWriteOutcome.of(ClusterSaveOutcome.refused(ClusterSaveOutcome.READ_FAILED));
    }

    /**
     * Copies a list of clusters so the copies share nothing with the storage they came from.
     * <p>
     * The read seam serves these: a caller holding one reads outside the cache lock, where the
     * live instances could be edited under the write lock at the same time.
     * </p>
     *
     * @param clusters the clusters the storage holds
     * @return a fresh list of detached copies, never <code>null</code>
     */
    private static List<Cluster> detachedCopies(List<Cluster> clusters)
    {
        List<Cluster> copies = new ArrayList<>(clusters.size());
        for (Cluster cluster : clusters)
        {
            copies.add(cluster == null ? null : cluster.detachedCopy());
        }
        return copies;
    }

    /**
     * Tells whether renaming {@code cluster} to {@code newName} would collide with another cluster.
     *
     * @param storage the project's clusters
     * @param cluster the cluster being renamed
     * @param newName the proposed name
     * @return {@code true} when another cluster already holds the resulting full path
     */
    private static boolean nameTaken(ClusterStore storage, Cluster cluster, String newName)
    {
        String newFullPath = buildFullPath(cluster.getPath(), newName);
        if (Objects.equals(newFullPath, cluster.getFullPath()))
        {
            return false;
        }
        Cluster other = storage.getClusterByFullPath(newFullPath);
        return other != null && other != cluster;
    }

    /**
     * Removes an object, and every name nested under it, from every cluster that held either.
     *
     * @param project the project
     * @param objectFqn the fully qualified name of the object
     * @return {@link ClusterSaveOutcome#NO_CHANGE} when neither the object nor a nested name is
     *         held, otherwise the save
     */
    private ClusterWriteOutcome removeHeldObject(IProject project, String objectFqn)
    {
        return mutate(project, storage -> {
            if (!storage.holdsObjectOrDescendant(objectFqn))
            {
                return ClusterWriteOutcome.of(ClusterSaveOutcome.noChange());
            }
            storage.removeObjectTree(objectFqn);
            return null;
        });
    }



    /**

     * Returns a project's storage, loading it into the cache if it is not there yet.

     * <p>

     * The caller must hold the write lock.

     * </p>

     *

     * @param project the project

     * @return the cached storage, or <code>null</code> when the repository could not load it now

     *         or on an earlier call since the cache entry was last dropped

     */

    private ClusterStore loadStorageLocked(IProject project)

    {

        String key = project.getName();

        ClusterStore cached = projectStorageCache.get(key);

        if (cached != null)

        {

            return cached;

        }

        if (failedLoads.contains(key))

        {

            return null;

        }

        ensureProjectWatched(project);

        ClusterStore loaded = repository.load(project);

        if (loaded != null)

        {

            projectStorageCache.put(key, loaded);

        }

        else

        {

            failedLoads.add(key);

        }

        return loaded;

    }



    /**

     * Drops a project's cache entry.

     *

     * @param project the project

     */

    private void invalidateCache(IProject project)
    {
        // Called from the resource-change notification, which runs under the workspace lock. A
        // writer may hold the cache lock while the workspace refreshes the file it replaced, so
        // waiting here would be the other half of a deadlock. When the lock is busy the entry is
        // dropped from another thread, which holds no workspace lock.
        if (cacheLock.writeLock().tryLock())
        {
            try
            {
                dropCached(project);
            }
            finally
            {
                cacheLock.writeLock().unlock();
            }
            return;
        }
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            cacheLock.writeLock().lock();
            try
            {
                dropCached(project);
            }
            finally
            {
                cacheLock.writeLock().unlock();
            }
        });
    }

    /**
     * Forgets a project's cache entry and its failed load. The caller holds the cache write lock.
     *
     * @param project the project
     */
    private void dropCached(IProject project)
    {
        projectStorageCache.remove(project.getName());
        failedLoads.remove(project.getName());
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

