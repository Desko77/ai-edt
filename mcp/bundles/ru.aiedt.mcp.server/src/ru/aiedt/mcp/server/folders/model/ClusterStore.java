/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The whole set of clusters for one project, with the queries and edits the service works through.
 * <p>
 * SnakeYAML reads and writes this as a JavaBean with a single {@code clusters} property. Everything
 * else here is behaviour layered over that list: looking a cluster up by its full path, listing the
 * clusters that sit directly under a collection, moving an object from one cluster to another, and
 * carrying a rename or a delete of a metadata object through into every cluster that named it.
 * </p>
 * <p>
 * Path matching is by whole segments, not by string prefix. A query for {@code Catalog} answers with
 * clusters at {@code Catalog} and clusters nested below it ({@code Catalog/Sub}), but never with an
 * unrelated cluster whose name merely begins the same way ({@code CatalogXYZ}).
 * </p>
 */
public class ClusterStore
{
    private List<Cluster> clusters;

    /**
     * Creates empty storage.
     */
    public ClusterStore()
    {
        this.clusters = new ArrayList<>();
    }

    /**
     * Returns the clusters.
     * <p>
     * The returned list is a copy: adding to it or removing from it does not change the storage, so a
     * caller cannot corrupt the backing list, and iterating it cannot collide with a concurrent edit.
     * The {@link Cluster} objects inside it are the real ones, though - {@link #getClusterByFullPath(String)}
     * returns the same instances.
     * </p>
     *
     * @return a fresh list holding the real cluster instances, never <code>null</code>
     */
    public List<Cluster> getGroups()
    {
        return new ArrayList<>(clusters);
    }

    /**
     * Replaces the clusters.
     *
     * @param clusters the new clusters; <code>null</code> is taken to mean none. A non-null list is kept as
     *            given, which is what lets SnakeYAML hand its own mutable list straight in on load
     */
    public void setGroups(List<Cluster> clusters)
    {
        this.clusters = clusters == null ? new ArrayList<>() : clusters;
    }

    /**
     * Finds a cluster by its full path.
     *
     * @param fullPath the full path to match
     * @return the cluster, the real instance held by this storage, or <code>null</code> if none matches
     */
    public Cluster getClusterByFullPath(String fullPath)
    {
        for (Cluster cluster : clusters)
        {
            if (Objects.equals(cluster.getFullPath(), fullPath))
            {
                return cluster;
            }
        }
        return null;
    }

    /**
     * Lists the clusters sitting directly at a collection path, ordered for display.
     * <p>
     * Order first, then name without regard to case. A <code>null</code> or empty query matches clusters
     * with a <code>null</code> or empty path.
     * </p>
     *
     * @param path the collection path
     * @return a fresh, sorted list, never <code>null</code>
     */
    public List<Cluster> getClustersAtPath(String path)
    {
        List<Cluster> result = new ArrayList<>();
        for (Cluster cluster : clusters)
        {
            if (pathEquals(cluster.getPath(), path))
            {
                result.add(cluster);
            }
        }
        result.sort(Comparator.comparingInt(Cluster::getOrder).thenComparing(ClusterStore::nameOf,
            String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /**
     * Adds a cluster, unless one already occupies its full path.
     *
     * @param cluster the cluster to add
     * @return <code>true</code> if added, <code>false</code> if the full path was taken
     */
    public boolean addCluster(Cluster cluster)
    {
        if (getClusterByFullPath(cluster.getFullPath()) != null)
        {
            return false;
        }
        return clusters.add(cluster);
    }

    /**
     * Removes the cluster at a full path.
     *
     * @param fullPath the full path of the cluster to remove
     * @return <code>true</code> if a cluster was removed
     */
    public boolean removeCluster(String fullPath)
    {
        return clusters.removeIf(cluster -> Objects.equals(cluster.getFullPath(), fullPath));
    }

    /**
     * Renames a cluster, changing only its name, and carries the change through to any clusters nested
     * under it.
     * <p>
     * The new full path is built the same way {@link Cluster#getFullPath()} builds it, so a root cluster
     * with no path renames cleanly. The rename is refused if the resulting full path is already taken
     * by another cluster.
     * </p>
     *
     * @param oldFullPath the full path of the cluster to rename
     * @param newName the new name
     * @return <code>true</code> if the cluster was found and renamed
     */
    public boolean renameCluster(String oldFullPath, String newName)
    {
        Cluster cluster = getClusterByFullPath(oldFullPath);
        if (cluster == null)
        {
            return false;
        }
        String newFullPath = buildFullPath(cluster.getPath(), newName);
        if (!newFullPath.equals(oldFullPath) && isFullPathTaken(cluster, newFullPath))
        {
            return false;
        }
        cluster.setName(newName);
        cascadeNestedPaths(cluster, oldFullPath, newFullPath);
        return true;
    }

    /**
     * Renames a cluster and sets its description in one step.
     * <p>
     * When the name actually changes this behaves like {@link #renameCluster(String, String)} - conflict
     * check and nested-path cascade included. The description is always applied. This is the edit the
     * Rename dialog performs.
     * </p>
     *
     * @param oldFullPath the full path of the cluster to update
     * @param newName the new name
     * @param description the new description; may be <code>null</code>
     * @return <code>true</code> if the cluster was found and updated
     */
    public boolean updateCluster(String oldFullPath, String newName, String description)
    {
        Cluster cluster = getClusterByFullPath(oldFullPath);
        if (cluster == null)
        {
            return false;
        }
        if (!Objects.equals(cluster.getName(), newName))
        {
            String newFullPath = buildFullPath(cluster.getPath(), newName);
            if (isFullPathTaken(cluster, newFullPath))
            {
                return false;
            }
            cluster.setName(newName);
            cascadeNestedPaths(cluster, oldFullPath, newFullPath);
        }
        cluster.setDescription(description);
        return true;
    }

    /**
     * Finds the first cluster holding the given object.
     *
     * @param objectFqn the fully qualified name of the object
     * @return the cluster, or <code>null</code> if the object is not clustered
     */
    public Cluster findClusterForObject(String objectFqn)
    {
        for (Cluster cluster : clusters)
        {
            if (cluster.containsChild(objectFqn))
            {
                return cluster;
            }
        }
        return null;
    }

    /**
     * Moves an object into a target cluster, taking it out of the cluster it is in now.
     *
     * @param objectFqn the fully qualified name of the object
     * @param targetClusterFullPath the full path of the cluster to move it into
     * @return <code>true</code> only if the target exists and the object was not already one of its
     *         children; <code>false</code> leaves the object where it was
     */
    public boolean moveObjectToCluster(String objectFqn, String targetClusterFullPath)
    {
        Cluster target = getClusterByFullPath(targetClusterFullPath);
        if (target == null)
        {
            return false;
        }
        Cluster current = findClusterForObject(objectFqn);
        if (current != null && current != target)
        {
            current.removeChild(objectFqn);
        }
        return target.addChild(objectFqn);
    }

    /**
     * Removes an object from every cluster holding it.
     *
     * @param objectFqn the fully qualified name of the object
     * @return <code>true</code> if it was in at least one cluster
     */
    public boolean removeObjectFromAllClusters(String objectFqn)
    {
        boolean removed = false;
        for (Cluster cluster : clusters)
        {
            if (cluster.removeChild(objectFqn))
            {
                removed = true;
            }
        }
        return removed;
    }

    /**
     * Collects the objects held by every cluster at a collection path or nested below it.
     *
     * @param path the collection path
     * @return the set of child FQNs, never <code>null</code>
     */
    public Set<String> getClusteredObjectsAtPath(String path)
    {
        Set<String> result = new LinkedHashSet<>();
        for (Cluster cluster : clusters)
        {
            if (pathAtOrUnder(cluster.getPath(), path))
            {
                result.addAll(cluster.getChildren());
            }
        }
        return result;
    }

    /**
     * Renames an object across every cluster that holds it.
     *
     * @param oldFqn the current fully qualified name
     * @param newFqn the fully qualified name to give it
     * @return <code>true</code> if at least one cluster held the old name
     */
    public boolean renameObject(String oldFqn, String newFqn)
    {
        boolean renamed = false;
        for (Cluster cluster : clusters)
        {
            if (cluster.renameChild(oldFqn, newFqn))
            {
                renamed = true;
            }
        }
        return renamed;
    }

    /**
     * Tells whether any cluster sits directly at a collection path.
     *
     * @param path the collection path
     * @return <code>true</code> if there is at least one
     */
    public boolean hasClustersAtPath(String path)
    {
        for (Cluster cluster : clusters)
        {
            if (pathEquals(cluster.getPath(), path))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns how many clusters there are.
     *
     * @return the count
     */
    public int getClusterCount()
    {
        return clusters.size();
    }

    /**
     * Tells whether there are no clusters.
     *
     * @return <code>true</code> when empty
     */
    public boolean isEmpty()
    {
        return clusters.isEmpty();
    }

    /**
     * Rewrites the paths of the clusters nested under a renamed cluster.
     * <p>
     * A nested cluster's path either equals the old full path exactly (a direct child) or begins with
     * the old full path followed by a slash (a deeper descendant). Matching whole segments this way
     * keeps a sibling whose name merely starts the same ({@code Root/ParentX} against {@code Root/Parent})
     * from being caught up in the rename.
     * </p>
     *
     * @param renamed the cluster that was renamed, which is skipped
     * @param oldFullPath its old full path
     * @param newFullPath its new full path
     */
    private void cascadeNestedPaths(Cluster renamed, String oldFullPath, String newFullPath)
    {
        String descendantPrefix = oldFullPath + "/"; //$NON-NLS-1$
        for (Cluster cluster : clusters)
        {
            if (cluster == renamed)
            {
                continue;
            }
            String clusterPath = cluster.getPath();
            if (clusterPath == null)
            {
                continue;
            }
            if (clusterPath.equals(oldFullPath))
            {
                cluster.setPath(newFullPath);
            }
            else if (clusterPath.startsWith(descendantPrefix))
            {
                cluster.setPath(newFullPath + clusterPath.substring(oldFullPath.length()));
            }
        }
    }

    /**
     * Tells whether some cluster other than the one being renamed already occupies a full path.
     *
     * @param self the cluster being renamed, which does not count as a conflict with itself
     * @param fullPath the full path to test
     * @return <code>true</code> if another cluster holds that full path
     */
    private boolean isFullPathTaken(Cluster self, String fullPath)
    {
        for (Cluster cluster : clusters)
        {
            if (cluster != self && fullPath.equals(cluster.getFullPath()))
            {
                return true;
            }
        }
        return false;
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

    /**
     * Whole-segment equality of two collection paths, treating <code>null</code> and empty alike.
     *
     * @param clusterPath a cluster's path
     * @param queryPath the path being asked about
     * @return <code>true</code> when they name the same collection
     */
    private static boolean pathEquals(String clusterPath, String queryPath)
    {
        boolean clusterEmpty = clusterPath == null || clusterPath.isEmpty();
        boolean queryEmpty = queryPath == null || queryPath.isEmpty();
        if (clusterEmpty || queryEmpty)
        {
            return clusterEmpty && queryEmpty;
        }
        return clusterPath.equals(queryPath);
    }

    /**
     * Tells whether a cluster's path is at, or nested under, a query path - by whole segments.
     *
     * @param clusterPath a cluster's path
     * @param queryPath the collection path being asked about
     * @return <code>true</code> when the cluster is at or below the query path
     */
    private static boolean pathAtOrUnder(String clusterPath, String queryPath)
    {
        boolean queryEmpty = queryPath == null || queryPath.isEmpty();
        boolean clusterEmpty = clusterPath == null || clusterPath.isEmpty();
        if (queryEmpty)
        {
            return clusterEmpty;
        }
        if (clusterEmpty)
        {
            return false;
        }
        return clusterPath.equals(queryPath) || clusterPath.startsWith(queryPath + "/"); //$NON-NLS-1$
    }

    /**
     * A null-safe cluster name, for use as a sort key.
     *
     * @param cluster the cluster
     * @return the name, or the empty string when the name is <code>null</code>
     */
    private static String nameOf(Cluster cluster)
    {
        return cluster.getName() == null ? "" : cluster.getName(); //$NON-NLS-1$
    }
}
