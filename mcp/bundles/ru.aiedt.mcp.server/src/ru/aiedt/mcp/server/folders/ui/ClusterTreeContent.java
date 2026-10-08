/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.navigator.ICommonContentExtensionSite;
import org.eclipse.ui.navigator.ICommonContentProvider;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.folders.IClusterChangeObserver;
import ru.aiedt.mcp.server.folders.IClusterManager;
import ru.aiedt.mcp.server.folders.model.Cluster;
import ru.aiedt.mcp.server.labels.MarkerHelpers;

/**
 * Adds cluster folder nodes to the EDT Navigator tree.
 * <p>
 * It contributes nodes in two places. Under a top-level collection folder it returns the cluster nodes
 * for that collection, to be shown alongside EDT's own objects (the ones that are clustered being
 * hidden from there by the filter). Under a cluster node it returns that cluster's contents - nested
 * clusters and resolved objects. It contributes nothing anywhere else, leaving the rest of the tree to
 * EDT.
 * </p>
 * <p>
 * It listens to the cluster service and refreshes the viewer, on the display thread, whenever a
 * project's clusters change. The listener is registered once and taken back when the provider is
 * disposed.
 * </p>
 */
public class ClusterTreeContent
    implements ICommonContentProvider, IClusterChangeObserver
{
    private static final Object[] NO_ELEMENTS = new Object[0];

    private IClusterManager service;

    private StructuredViewer viewer;

    private NavigatorClusterSelection selectionHelper;

    private boolean listenerRegistered;

    @Override
    public void init(ICommonContentExtensionSite config)
    {
        service = Activator.getClusterServiceStatic();
        if (service != null && !listenerRegistered)
        {
            service.addClusterChangeListener(this);
            listenerRegistered = true;
        }
    }

    @Override
    public Object[] getElements(Object inputElement)
    {
        return getChildren(inputElement);
    }

    @Override
    public Object[] getChildren(Object parentElement)
    {
        if (CollectionAdapters.isCollectionAdapter(parentElement))
        {
            return collectionClusterNodes(parentElement);
        }
        if (parentElement instanceof ClusterNavigatorBridge)
        {
            return ((ClusterNavigatorBridge)parentElement).getChildren(parentElement);
        }
        Activator.logDebug("ClusterTreeContent: no cluster children for " + parentElement); //$NON-NLS-1$
        return NO_ELEMENTS;
    }

    @Override
    public Object getParent(Object element)
    {
        if (element instanceof ClusterNavigatorBridge)
        {
            return ((ClusterNavigatorBridge)element).getParent(element);
        }
        if (element instanceof EObject)
        {
            EObject eObject = (EObject)element;
            IClusterManager clusters = service != null ? service : Activator.getClusterServiceStatic();
            return parentOfClusteredObject(clusters, MarkerHelpers.extractProject(eObject),
                MarkerHelpers.extractFqn(eObject));
        }
        return null;
    }

    /**
     * The tree parent of a clustered object: the cluster node that holds it.
     * <p>
     * A viewer that is asked to select or reveal an element walks the parent chain this answers,
     * from the element up to a root, and only such a chain names an item in the tree. The cluster
     * node this builds equals the rendered one for the same cluster, so the chain the viewer walks
     * reaches the item that is on screen.
     * </p>
     *
     * @param service the cluster service; <code>null</code> answers no parent
     * @param project the object's project; <code>null</code> answers no parent
     * @param fqn the object's fully qualified name; <code>null</code> answers no parent
     * @return a cluster node for the cluster holding the object, or <code>null</code> when the
     *         object is in no cluster and its parent is EDT's own to name
     */
    static Object parentOfClusteredObject(IClusterManager service, IProject project, String fqn)
    {
        if (service == null || project == null || fqn == null)
        {
            return null;
        }
        Cluster cluster = service.findClusterForObject(project, fqn);
        return cluster == null ? null : new ClusterNavigatorBridge(cluster, project, null);
    }

    @Override
    public boolean hasChildren(Object element)
    {
        if (element instanceof ClusterNavigatorBridge)
        {
            ClusterNavigatorBridge node = (ClusterNavigatorBridge)element;
            Cluster cluster = node.getCluster();
            // Read the way the node's getChildren reads it, so a provider set up before the
            // cluster service started still offers the nested clusters it would show.
            IClusterManager clusters = service != null ? service : Activator.getClusterServiceStatic();
            if (clusters != null && clusters.hasClustersAtPath(node.getProject(), cluster.getFullPath()))
            {
                return true;
            }
            return node.holdsAResolvableObject();
        }
        if (CollectionAdapters.isCollectionAdapter(element))
        {
            if (service == null)
            {
                return false;
            }
            IProject project = CollectionAdapters.getProjectFromAdapter(element);
            String path = CollectionAdapters.getFullCollectionPath(element, MarkerHelpers::extractFqn);
            if (project == null || path == null)
            {
                return false;
            }
            // The expand affordance is asked about an item that exists, the same as its children.
            RenderedClusterPaths.noteCollectionDrawn(project, path);
            return service.hasClustersAtPath(project, path);
        }
        return false;
    }

    @Override
    public void inputChanged(Viewer newViewer, Object oldInput, Object newInput)
    {
        if (newViewer instanceof StructuredViewer)
        {
            this.viewer = (StructuredViewer)newViewer;
        }
        if (newViewer instanceof TreeViewer && selectionHelper == null)
        {
            selectionHelper = new NavigatorClusterSelection((TreeViewer)newViewer);
            selectionHelper.attach();
        }
    }

    @Override
    public void onClustersChanged(IProject project)
    {
        StructuredViewer current = viewer;
        if (current == null)
        {
            return;
        }
        Control control = current.getControl();
        if (control == null || control.isDisposed())
        {
            return;
        }
        Display display = control.getDisplay();
        if (display == null)
        {
            return;
        }
        display.asyncExec(() -> {
            if (!control.isDisposed())
            {
                current.refresh();
            }
        });
    }

    @Override
    public void restoreState(IMemento memento)
    {
        // No state to restore.
    }

    @Override
    public void saveState(IMemento memento)
    {
        // No state to save.
    }

    @Override
    public void dispose()
    {
        if (selectionHelper != null)
        {
            selectionHelper.detach();
            selectionHelper = null;
        }
        if (service != null && listenerRegistered)
        {
            service.removeClusterChangeListener(this);
            listenerRegistered = false;
        }
        viewer = null;
    }

    /**
     * Returns the cluster nodes to show under a top-level collection folder.
     *
     * @param adapter the collection adapter
     * @return the cluster nodes, or an empty array when there are none or the collection cannot be
     *         resolved
     */
    private Object[] collectionClusterNodes(Object adapter)
    {
        if (service == null)
        {
            return NO_ELEMENTS;
        }
        IProject project = CollectionAdapters.getProjectFromAdapter(adapter);
        String path = CollectionAdapters.getFullCollectionPath(adapter, MarkerHelpers::extractFqn);
        if (project == null || path == null)
        {
            Activator.logDebug("ClusterTreeContent: unresolved collection " + adapter); //$NON-NLS-1$
            return NO_ELEMENTS;
        }
        // Being asked about a collection is what a tree item for it existing means: the cluster
        // nodes returned here are drawn, and the hiding filter hides a member only under a drawn
        // cluster - see RenderedClusterPaths.
        RenderedClusterPaths.noteCollectionDrawn(project, path);
        if (!service.hasClustersAtPath(project, path))
        {
            return NO_ELEMENTS;
        }
        List<Cluster> clusters = service.getClustersAtPath(project, path);
        List<ClusterNavigatorBridge> nodes = new ArrayList<>(clusters.size());
        for (Cluster cluster : clusters)
        {
            nodes.add(new ClusterNavigatorBridge(cluster, project, adapter));
            RenderedClusterPaths.noteClusterNodeDrawn(project, cluster.getFullPath());
        }
        return nodes.toArray();
    }
}
