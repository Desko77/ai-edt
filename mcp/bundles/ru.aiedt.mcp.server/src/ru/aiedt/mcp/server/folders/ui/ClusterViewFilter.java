/**

 * AI-EDT - 1C AI tools for EDT

 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)

 * Licensed under AGPL-3.0-or-later

 */



package ru.aiedt.mcp.server.folders.ui;



import org.eclipse.core.resources.IProject;

import org.eclipse.emf.ecore.EObject;

import org.eclipse.jface.viewers.StructuredViewer;

import org.eclipse.jface.viewers.TreePath;

import org.eclipse.jface.viewers.Viewer;

import org.eclipse.jface.viewers.ViewerFilter;



import ru.aiedt.mcp.server.Activator;

import ru.aiedt.mcp.server.folders.IClusterManager;

import ru.aiedt.mcp.server.labels.MarkerHelpers;



/**

 * Hides a metadata object from its normal place in the tree once it has been put in a cluster, so it is

 * not shown twice.

 * <p>

 * The hiding steps aside for a few cases. While the user is running a text search over the tree,

 * nothing is hidden, so a clustered object can still be found. Objects shown as the children of a cluster

 * node are kept. And when there is no service - the plugin is stopping or headless - nothing is

 * hidden, so the tree simply falls back to EDT's own view.

 * </p>

 */

public class ClusterViewFilter

    extends ViewerFilter

{

    private static final String MARKER_SEARCH_FILTER_CLASS = "ru.aiedt.mcp.server.labels.ui.MarkerQueryFilter"; //$NON-NLS-1$



    private static final String[] SEARCH_FILTER_MARKERS =

        {"Pattern", "Search", "Quick", "Text"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$



    @Override

    public boolean select(Viewer viewer, Object parentElement, Object element)

    {

        if (isSearchFilterActive(viewer))

        {

            return true;

        }

        if (parentElement instanceof ClusterNavigatorBridge)

        {

            return true;

        }

        if (parentElement instanceof TreePath && hasClusterSegment((TreePath)parentElement))

        {

            return true;

        }

        if (!(element instanceof EObject))

        {

            return true;

        }

        EObject eObject = (EObject)element;

        IProject project = MarkerHelpers.extractProject(eObject);

        String fqn = MarkerHelpers.extractFqn(eObject);

        if (project == null || fqn == null)

        {

            return true;

        }

        IClusterManager service = Activator.getClusterServiceStatic();

        if (service == null)

        {

            return true;

        }

        return service.findClusterForObject(project, fqn) == null;

    }



    /**

     * Tells whether a text-search filter is running on the viewer.

     * <p>

     * This filter and the marker search filter are skipped; any other filter whose class name reads like a

     * search or pattern filter counts.

     * </p>

     *

     * @param viewer the viewer

     * @return <code>true</code> if a search filter is active

     */

    private boolean isSearchFilterActive(Viewer viewer)

    {

        if (!(viewer instanceof StructuredViewer))

        {

            return false;

        }

        for (ViewerFilter filter : ((StructuredViewer)viewer).getFilters())

        {

            if (filter == this)

            {

                continue;

            }

            String className = filter.getClass().getName();

            if (MARKER_SEARCH_FILTER_CLASS.equals(className))

            {

                continue;

            }

            for (String marker : SEARCH_FILTER_MARKERS)

            {

                if (className.contains(marker))

                {

                    return true;

                }

            }

        }

        return false;

    }



    /**

     * Tells whether any segment of a tree path is a cluster node.

     *

     * @param path the tree path

     * @return <code>true</code> if a cluster node is on the path

     */

    private static boolean hasClusterSegment(TreePath path)

    {

        for (int i = 0; i < path.getSegmentCount(); i++)

        {

            if (path.getSegment(i) instanceof ClusterNavigatorBridge)

            {

                return true;

            }

        }

        return false;

    }

}

