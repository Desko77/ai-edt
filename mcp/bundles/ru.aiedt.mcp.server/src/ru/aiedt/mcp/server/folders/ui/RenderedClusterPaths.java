/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.ui;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IProject;

import ru.aiedt.mcp.server.folders.model.Cluster;

/**
 * Remembers which collections and cluster nodes the Navigator actually drew, per project.
 * <p>
 * A cluster node is drawn only under a collection the tree shows, so a cluster whose path names
 * no collection of the tree - a hand-typed path, a collection this configuration does not carry -
 * is never drawn anywhere. The hiding filter asks this before it hides an object from its normal
 * place: hiding a member of a cluster nothing will draw would leave the object invisible in the
 * whole tree. A collection counts as drawn once the content provider has been asked for its
 * children or its expand affordance, which is what a tree item being there means; a cluster node
 * counts as drawn when the provider builds it to return.
 * </p>
 * <p>
 * The state is per workbench, filled on the display thread and read there; the concurrent maps
 * keep it sound when a test or another thread touches it. A project's paths are forgotten when
 * its clusters change, and the redraw notes again everything the tree draws then: a path left
 * behind by a rename or a delete would keep hiding objects that no node shows anymore.
 * </p>
 */
public final class RenderedClusterPaths
{
    private static final Map<String, Set<String>> DRAWN_COLLECTIONS = new ConcurrentHashMap<>();

    private static final Map<String, Set<String>> DRAWN_CLUSTER_NODES = new ConcurrentHashMap<>();

    private RenderedClusterPaths()
    {
        // utility
    }

    /**
     * Records that the tree drew a collection, so clusters sitting directly at that path are
     * drawn with it.
     *
     * @param project the project the collection belongs to
     * @param path the collection path, as {@code CollectionAdapters.getFullCollectionPath} reads it
     */
    public static void noteCollectionDrawn(IProject project, String path)
    {
        if (project == null || path == null)
        {
            return;
        }
        drawnCollectionsOf(project).add(path);
    }

    /**
     * Records that the tree drew a cluster node, so clusters nested under that node are drawn
     * with it.
     *
     * @param project the project the cluster belongs to
     * @param fullPath the cluster's full path, the identity a nested cluster's path carries
     */
    public static void noteClusterNodeDrawn(IProject project, String fullPath)
    {
        if (project == null || fullPath == null)
        {
            return;
        }
        drawnNodesOf(project).add(fullPath);
    }

    /**
     * Tells whether the tree draws a cluster.
     * <p>
     * A cluster sitting directly at a collection path is drawn when that collection is; a cluster
     * nested under another cluster is drawn when a node for that parent cluster is, and a node's
     * identity is the parent cluster's full path, which is exactly the nested cluster's path. So
     * both cases read the cluster's own path against the two sets. When nothing of the project
     * has been drawn yet, nothing is - the safe direction is to keep an object visible in its
     * normal place, not to hide it from both.
     * </p>
     *
     * @param project the project the cluster belongs to
     * @param cluster the cluster
     * @return <code>true</code> when the tree draws the cluster's node
     */
    public static boolean isDrawn(IProject project, Cluster cluster)
    {
        if (project == null || cluster == null)
        {
            return false;
        }
        Set<String> collections = DRAWN_COLLECTIONS.get(project.getName());
        if (collections != null && collections.contains(cluster.getPath()))
        {
            return true;
        }
        Set<String> nodes = DRAWN_CLUSTER_NODES.get(project.getName());
        return nodes != null && nodes.contains(cluster.getPath());
    }

    /**
     * Forgets everything a project drew, so a path nothing draws anymore stops hiding objects.
     * <p>
     * Called when a project's clusters change, before the tree redraws: a collection or a parent
     * cluster that a rename or a delete took away leaves its path behind, and a path still
     * answered as drawn hid objects from their normal place that no node would ever show. The
     * redraw notes again everything the tree draws then.
     * </p>
     *
     * @param project the project whose drawn paths to forget; {@code null} forgets nothing
     */
    public static void forget(IProject project)
    {
        if (project == null)
        {
            return;
        }
        DRAWN_COLLECTIONS.remove(project.getName());
        DRAWN_CLUSTER_NODES.remove(project.getName());
    }

    /**
     * Forgets everything drawn. For tests.
     */
    public static void clear()
    {
        DRAWN_COLLECTIONS.clear();
        DRAWN_CLUSTER_NODES.clear();
    }

    /**
     * The drawn collections of a project, created on first use.
     *
     * @param project the project
     * @return the project's set of drawn collection paths
     */
    private static Set<String> drawnCollectionsOf(IProject project)
    {
        return DRAWN_COLLECTIONS.computeIfAbsent(project.getName(), missing -> ConcurrentHashMap.newKeySet());
    }

    /**
     * The drawn cluster nodes of a project, created on first use.
     *
     * @param project the project
     * @return the project's set of drawn cluster full paths
     */
    private static Set<String> drawnNodesOf(IProject project)
    {
        return DRAWN_CLUSTER_NODES.computeIfAbsent(project.getName(), missing -> ConcurrentHashMap.newKeySet());
    }
}
