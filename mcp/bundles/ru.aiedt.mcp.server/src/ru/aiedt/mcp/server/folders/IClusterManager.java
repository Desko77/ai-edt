/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders;

import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;

import ru.aiedt.mcp.server.folders.model.Cluster;
import ru.aiedt.mcp.server.folders.model.ClusterStore;

/**
 * The one seam through which everything - the Navigator content, the filter, the handlers and the
 * refactoring contributors - reads and edits a project's clusters.
 * <p>
 * The service keeps a per-project cache of the clusters, backed by the file on disk, and tells its
 * listeners whenever a project's clusters change. Every method takes the project first. No method
 * throws a checked exception: a failure to read or write the file is logged and turned into an empty
 * result, because the callers run on the display thread and inside refactoring and have nowhere
 * useful to put an exception.
 * </p>
 * <p>
 * Callers are written to tolerate there being no service at all - see
 * {@link ru.aiedt.mcp.server.Activator#getClusterServiceStatic()}, which answers <code>null</code>
 * before the plugin starts, after it stops, and in a headless workbench.
 * </p>
 */
public interface IClusterManager
{
    /**
     * Returns the whole set of clusters for a project.
     *
     * @param project the project
     * @return the storage, never <code>null</code>; empty when the project has no clusters
     */
    ClusterStore getClusterStorage(IProject project);

    /**
     * Lists the clusters sitting directly at a collection path, ordered for display.
     *
     * @param project the project
     * @param path the collection path; <code>null</code> or empty matches root clusters
     * @return a fresh, sorted list, never <code>null</code>
     */
    List<Cluster> getClustersAtPath(IProject project, String path);

    /**
     * Returns all of a project's clusters.
     *
     * @param project the project
     * @return a fresh list of the clusters, never <code>null</code>
     */
    List<Cluster> getAllClusters(IProject project);

    /**
     * Creates a cluster at a path.
     * <p>
     * The new cluster is placed after its siblings: its order is one past the highest order among the
     * clusters already at that path, or zero when it is the first.
     * </p>
     *
     * @param project the project
     * @param name the cluster name
     * @param path the collection path; may be <code>null</code> for a root cluster
     * @param description the description; may be <code>null</code>
     * @return the created cluster, or <code>null</code> if the path is occupied or the file could not
     *         be loaded or saved
     */
    Cluster createCluster(IProject project, String name, String path, String description);

    /**
     * Renames a cluster, changing only its name.
     *
     * @param project the project
     * @param oldFullPath the full path of the cluster to rename
     * @param newName the new name
     * @return <code>true</code> if the cluster was found, renamed and saved; <code>false</code> when
     *         it was absent or the file could not be loaded or saved
     */
    boolean renameCluster(IProject project, String oldFullPath, String newName);

    /**
     * Renames a cluster and sets its description. This is what the Rename dialog performs.
     *
     * @param project the project
     * @param oldFullPath the full path of the cluster to update
     * @param newName the new name
     * @param description the new description; may be <code>null</code>
     * @return <code>true</code> if the cluster was found, updated and saved; <code>false</code> when
     *         it was absent or the file could not be loaded or saved
     */
    boolean updateCluster(IProject project, String oldFullPath, String newName, String description);

    /**
     * Deletes a cluster and any clusters nested under it. Objects those clusters held return to
     * their normal place in the collection.
     *
     * @param project the project
     * @param fullPath the full path of the cluster to delete
     * @return <code>true</code> if a cluster was removed and the result was saved; <code>false</code>
     *         when it was absent or the file could not be loaded or saved
     */
    boolean deleteCluster(IProject project, String fullPath);

    /**
     * Moves an object into a cluster, taking it out of any cluster it is in now.
     *
     * @param project the project
     * @param objectFqn the fully qualified name of the object
     * @param clusterFullPath the full path of the target cluster
     * @return <code>true</code> only if the target exists, the object was not already one of its
     *         children, and the result was saved; <code>false</code> also reports a load or save failure
     */
    boolean addObjectToCluster(IProject project, String objectFqn, String clusterFullPath);

    /**
     * Removes an object from every cluster holding it, returning it to its normal place.
     *
     * @param project the project
     * @param objectFqn the fully qualified name of the object
     * @return <code>true</code> if it was in at least one cluster and the result was saved;
     *         <code>false</code> also reports a load or save failure
     */
    boolean removeObjectFromCluster(IProject project, String objectFqn);

    /**
     * Finds the first cluster holding an object.
     *
     * @param project the project
     * @param objectFqn the fully qualified name of the object
     * @return the cluster, or <code>null</code> if the object is not clustered
     */
    Cluster findClusterForObject(IProject project, String objectFqn);

    /**
     * Collects the objects held by the clusters at a collection path or nested below it.
     *
     * @param project the project
     * @param path the collection path
     * @return the set of child FQNs, never <code>null</code>
     */
    Set<String> getClusteredObjectsAtPath(IProject project, String path);

    /**
     * Tells whether any cluster sits directly at a collection path.
     *
     * @param project the project
     * @param path the collection path
     * @return <code>true</code> if there is at least one
     */
    boolean hasClustersAtPath(IProject project, String path);

    /**
     * Tells whether any cluster holds an object name or a name nested under it.
     *
     * @param project the project
     * @param objectFqn the fully qualified object name
     * @return <code>true</code> when the object or a descendant is held
     */
    boolean holdsObjectOrDescendant(IProject project, String objectFqn);

    /**
     * Drops a project's cache entry and tells the listeners, without touching the file.
     * <p>
     * Used when the clusters may have changed on disk and the in-memory copy is to be reloaded on next
     * use.
     * </p>
     *
     * @param project the project
     */
    void refresh(IProject project);

    /**
     * Renames an object's fully qualified name in every cluster that named it, and rewrites names
     * nested under it the same way. Refactoring support.
     *
     * @param project the project
     * @param oldFqn the current fully qualified name
     * @param newFqn the fully qualified name to give it
     * @return <code>true</code> if at least one matching name was changed and saved;
     *         <code>false</code> also reports a load or save failure
     */
    boolean renameObject(IProject project, String oldFqn, String newFqn);

    /**
     * Removes an object from every cluster naming it. Refactoring support for a deleted object.
     *
     * @param project the project
     * @param objectFqn the fully qualified name of the object
     * @return <code>true</code> if it was in at least one cluster and the result was saved;
     *         <code>false</code> also reports a load or save failure
     */
    boolean removeObject(IProject project, String objectFqn);

    /**
     * Registers a listener to be told when any project's clusters change.
     *
     * @param listener the listener; a listener already registered is not added again
     */
    void addClusterChangeListener(IClusterChangeObserver listener);

    /**
     * Removes a previously registered listener.
     *
     * @param listener the listener
     */
    void removeClusterChangeListener(IClusterChangeObserver listener);
}
