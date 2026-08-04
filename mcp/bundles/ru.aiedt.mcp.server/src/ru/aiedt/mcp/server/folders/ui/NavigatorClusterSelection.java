/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.ui;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.jface.viewers.TreeSelection;
import org.eclipse.jface.viewers.TreeViewer;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.folders.IClusterManager;
import ru.aiedt.mcp.server.folders.model.Cluster;
import ru.aiedt.mcp.server.labels.MarkerHelpers;

/**
 * Keeps a clustered object selected across a search being cleared.
 * <p>
 * When a search is on, the hiding filter steps aside and a clustered object can be picked. As the
 * search clears, the tree rebuilds and that object would lose its selection - or the tree would fall
 * back to selecting the project node. This helper remembers the last object actually chosen and, if
 * the selection then slips away within a short window, re-selects it along a path that runs through
 * its cluster node.
 * </p>
 * <p>
 * Only a selection whose first element is a metadata object is remembered. That guard is what keeps
 * the helper from latching onto the transient re-selection of the project node and fighting the user.
 * </p>
 */
public class NavigatorClusterSelection
    implements ISelectionChangedListener
{
    private static final long RESTORE_WINDOW_MILLIS = 2000L;

    private final TreeViewer viewer;

    private EObject lastSelectedObject;

    private long lastSelectionTime;

    private boolean restoring;

    /**
     * Creates a helper for a viewer.
     *
     * @param viewer the tree viewer to watch
     */
    public NavigatorClusterSelection(TreeViewer viewer)
    {
        this.viewer = viewer;
    }

    /**
     * Starts watching the viewer's selection.
     */
    public void attach()
    {
        viewer.addSelectionChangedListener(this);
    }

    /**
     * Stops watching the viewer's selection.
     */
    public void detach()
    {
        viewer.removeSelectionChangedListener(this);
    }

    @Override
    public void selectionChanged(SelectionChangedEvent event)
    {
        if (restoring)
        {
            return;
        }
        IStructuredSelection selection = event.getStructuredSelection();
        Object first = selection.getFirstElement();
        if (first instanceof EObject)
        {
            lastSelectedObject = (EObject)first;
            lastSelectionTime = System.currentTimeMillis();
            return;
        }
        if (lastSelectedObject != null
            && System.currentTimeMillis() - lastSelectionTime <= RESTORE_WINDOW_MILLIS)
        {
            restoreSelection(lastSelectedObject);
        }
    }

    /**
     * Re-selects an object along a path through the cluster that holds it.
     *
     * @param eObject the object to re-select
     */
    private void restoreSelection(EObject eObject)
    {
        IProject project = MarkerHelpers.extractProject(eObject);
        String fqn = MarkerHelpers.extractFqn(eObject);
        if (project == null || fqn == null)
        {
            return;
        }
        IClusterManager service = Activator.getClusterServiceStatic();
        if (service == null)
        {
            return;
        }
        Cluster cluster = service.findClusterForObject(project, fqn);
        if (cluster == null)
        {
            return;
        }
        ClusterNavigatorBridge clusterNode = new ClusterNavigatorBridge(cluster, project, null);
        TreePath path = new TreePath(new Object[] {clusterNode, eObject});
        restoring = true;
        try
        {
            viewer.setSelection(new TreeSelection(path), true);
        }
        finally
        {
            restoring = false;
        }
    }
}
