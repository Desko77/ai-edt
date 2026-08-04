/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.ISources;
import org.eclipse.ui.handlers.HandlerUtil;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.folders.IClusterManager;
import ru.aiedt.mcp.server.folders.model.Cluster;
import ru.aiedt.mcp.server.folders.ui.ClusterNavigatorBridge;

/**
 * Shared plumbing for the handlers that act on a cluster node: getting to the service, reading the
 * selected cluster node out of an event or an evaluation context, and bundling up the pieces a command
 * needs.
 */
public abstract class ClusterCommandHandler
    extends AbstractHandler
{
    /**
     * The cluster node a command is acting on, together with the shell to raise dialogs against.
     */
    protected static class ClusterSelection
    {
        final ClusterNavigatorBridge adapter;

        final Cluster cluster;

        final IProject project;

        final Shell shell;

        ClusterSelection(ClusterNavigatorBridge adapter, Cluster cluster, IProject project, Shell shell)
        {
            this.adapter = adapter;
            this.cluster = cluster;
            this.project = project;
            this.shell = shell;
        }

        /**
         * Tells whether there is a cluster to act on.
         *
         * @return <code>true</code> when the adapter, cluster and project are all present
         */
        boolean isValid()
        {
            return adapter != null && cluster != null && project != null;
        }
    }

    /**
     * Returns the cluster service.
     *
     * @return the service, or <code>null</code> when it is not available
     */
    protected IClusterManager getClusterService()
    {
        return Activator.getClusterServiceStatic();
    }

    /**
     * Reads the cluster node out of an evaluation context or a selection.
     *
     * @param context an {@link IEvaluationContext} or an {@link IStructuredSelection}
     * @return the first selected cluster node, or <code>null</code>
     */
    protected ClusterNavigatorBridge getClusterAdapterFromContext(Object context)
    {
        IStructuredSelection selection = selectionFromContext(context);
        if (selection == null || selection.isEmpty())
        {
            return null;
        }
        return extractClusterAdapter(selection.getFirstElement());
    }

    /**
     * Returns an element as a cluster node, if it is one.
     *
     * @param element the element
     * @return the cluster node, or <code>null</code>
     */
    protected ClusterNavigatorBridge extractClusterAdapter(Object element)
    {
        return element instanceof ClusterNavigatorBridge ? (ClusterNavigatorBridge)element : null;
    }

    /**
     * Returns the cluster of the first cluster node in a selection.
     *
     * @param selection the selection
     * @return the cluster, or <code>null</code>
     */
    protected Cluster extractCluster(ISelection selection)
    {
        ClusterNavigatorBridge adapter = adapterFromSelection(selection);
        return adapter == null ? null : adapter.getCluster();
    }

    /**
     * Returns the project of the first cluster node in a selection.
     *
     * @param selection the selection
     * @return the project, or <code>null</code>
     */
    protected IProject extractProject(ISelection selection)
    {
        ClusterNavigatorBridge adapter = adapterFromSelection(selection);
        return adapter == null ? null : adapter.getProject();
    }

    /**
     * Returns the selection an event acts on, preferring the menu selection.
     *
     * @param event the command event
     * @return the selection, or <code>null</code>
     */
    protected ISelection extractSelection(ExecutionEvent event)
    {
        ISelection selection = HandlerUtil.getActiveMenuSelection(event);
        if (selection == null)
        {
            selection = HandlerUtil.getCurrentSelection(event);
        }
        return selection;
    }

    /**
     * Bundles the selected cluster node with the shell for a command's use.
     *
     * @param event the command event
     * @return the bundle, whose {@link ClusterSelection#isValid()} says whether there is a cluster to act on
     */
    protected ClusterSelection resolveClusterSelection(ExecutionEvent event)
    {
        ClusterNavigatorBridge adapter = adapterFromSelection(extractSelection(event));
        Shell shell = HandlerUtil.getActiveShell(event);
        if (adapter == null)
        {
            return new ClusterSelection(null, null, null, shell);
        }
        return new ClusterSelection(adapter, adapter.getCluster(), adapter.getProject(), shell);
    }

    /**
     * Extracts a structured selection from an evaluation context or a selection.
     *
     * @param context an {@link IEvaluationContext} or an {@link IStructuredSelection}
     * @return the structured selection, or <code>null</code>
     */
    protected IStructuredSelection selectionFromContext(Object context)
    {
        if (context instanceof IStructuredSelection)
        {
            return (IStructuredSelection)context;
        }
        if (context instanceof IEvaluationContext)
        {
            IEvaluationContext evaluationContext = (IEvaluationContext)context;
            Object value = evaluationContext.getVariable(ISources.ACTIVE_MENU_SELECTION_NAME);
            if (!(value instanceof IStructuredSelection))
            {
                value = evaluationContext.getVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME);
            }
            if (value instanceof IStructuredSelection)
            {
                return (IStructuredSelection)value;
            }
        }
        return null;
    }

    /**
     * Builds a full path from a path and a name, the way {@link Cluster#getFullPath()} does.
     *
     * @param path the path; may be <code>null</code> or empty
     * @param name the name
     * @return the full path
     */
    protected static String buildFullPath(String path, String name)
    {
        if (path == null || path.isEmpty())
        {
            return name;
        }
        return path + "/" + name; //$NON-NLS-1$
    }

    /**
     * Returns the first cluster node in a selection.
     *
     * @param selection the selection
     * @return the cluster node, or <code>null</code>
     */
    private ClusterNavigatorBridge adapterFromSelection(ISelection selection)
    {
        if (selection instanceof IStructuredSelection)
        {
            return extractClusterAdapter(((IStructuredSelection)selection).getFirstElement());
        }
        return null;
    }
}
