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
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;

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
 * Only an empty selection or the project node is undone: a cluster node or a collection chosen
 * within the window is the user's choice and stays.
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
        if (!isSlip(first))
        {
            // A choice the user made ends the window: what the tree selects after it is not
            // undone back to the object chosen before.
            lastSelectedObject = null;
            return;
        }
        if (lastSelectedObject != null
            && System.currentTimeMillis() - lastSelectionTime <= RESTORE_WINDOW_MILLIS)
        {
            restoreSelection(lastSelectedObject);
        }
    }

    /**
     * Whether a selection that is not a metadata object is the slip a rebuilding tree makes.
     * <p>
     * As a search clears, the rebuilt tree selects nothing or falls back to the project node.
     * Any other element is one the user picked.
     * </p>
     *
     * @param first the first element of the new selection, or {@code null} when nothing is selected
     * @return whether the selection is to be undone
     */
    static boolean isSlip(Object first)
    {
        return first == null || first instanceof IProject;
    }

    /**
     * Re-selects an object through the cluster that holds it.
     * <p>
     * The viewer is handed the object itself, with revealing on: it walks the parent chain the
     * content provider answers - for a clustered object the cluster node that holds it, and from
     * there up to the root - and selects the item that chain names. A hand-built two-segment path
     * named no item at all, because its first segment was not a root element, and the selection
     * came back empty.
     * </p>
     *
     * @param eObject the object to re-select
     */
    void restoreSelection(EObject eObject)
    {
        restoring = true;
        try
        {
            viewer.setSelection(new StructuredSelection(eObject), true);
        }
        finally
        {
            restoring = false;
        }
    }
}
