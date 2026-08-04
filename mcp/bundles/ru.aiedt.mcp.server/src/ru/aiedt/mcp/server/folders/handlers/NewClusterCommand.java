/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.ISources;
import org.eclipse.ui.handlers.HandlerUtil;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.folders.IClusterManager;
import ru.aiedt.mcp.server.folders.model.ClusterStore;
import ru.aiedt.mcp.server.folders.ui.CollectionAdapters;
import ru.aiedt.mcp.server.folders.ui.ClusterEditDialog;
import ru.aiedt.mcp.server.folders.ui.ClusterNavigatorBridge;

/**
 * Creates a cluster under a top-level collection folder.
 * <p>
 * Enabled only on a top-level collection folder, and never on a cluster node - so the command never
 * builds a cluster inside another cluster, even though the model would allow it.
 * </p>
 */
public class NewClusterCommand
    extends AbstractHandler
{
    @Override
    public void setEnabled(Object evaluationContext)
    {
        setBaseEnabled(canCreate(firstSelectedElement(evaluationContext)));
    }

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        Object element = firstElement(activeSelection(event));
        if (!canCreate(element))
        {
            return null;
        }
        String path = CollectionAdapters.getCollectionPath(element);
        IProject project = CollectionAdapters.getProjectFromAdapter(element);
        if (path == null || project == null)
        {
            return null;
        }
        IClusterManager service = Activator.getClusterServiceStatic();
        if (service == null)
        {
            return null;
        }

        Shell shell = HandlerUtil.getActiveShell(event);
        ClusterEditDialog dialog = new ClusterEditDialog(shell, name -> validateName(service, project, path, name));
        if (dialog.open() == Window.OK)
        {
            service.createCluster(project, dialog.getClusterName(), path, dialog.getClusterDescription());
        }
        return null;
    }

    /**
     * Tells whether a new cluster can be created on an element: a top-level collection folder, not a
     * cluster node.
     *
     * @param element the selected element
     * @return <code>true</code> when a cluster can be created here
     */
    private static boolean canCreate(Object element)
    {
        if (element == null || element instanceof ClusterNavigatorBridge)
        {
            return false;
        }
        return CollectionAdapters.isCollectionAdapter(element)
            && CollectionAdapters.getCollectionPath(element) != null;
    }

    /**
     * Rejects an empty name, a name with a slash, and a name already taken at the collection path.
     *
     * @param service the cluster service
     * @param project the project
     * @param path the collection path
     * @param name the proposed name
     * @return an error message, or <code>null</code> when the name is acceptable
     */
    private static String validateName(IClusterManager service, IProject project, String path, String name)
    {
        if (name == null || name.isEmpty())
        {
            return "The name must not be empty."; //$NON-NLS-1$
        }
        if (name.contains("/")) //$NON-NLS-1$
        {
            return "The name must not contain a slash."; //$NON-NLS-1$
        }
        String fullPath = path == null || path.isEmpty() ? name : path + "/" + name; //$NON-NLS-1$
        ClusterStore storage = service.getClusterStorage(project);
        if (storage.getClusterByFullPath(fullPath) != null)
        {
            return "A cluster with this name already exists here."; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Returns the selection an event acts on, preferring the menu selection.
     *
     * @param event the command event
     * @return the selection, possibly <code>null</code>
     */
    private static ISelection activeSelection(ExecutionEvent event)
    {
        ISelection selection = HandlerUtil.getActiveMenuSelection(event);
        return selection != null ? selection : HandlerUtil.getCurrentSelection(event);
    }

    /**
     * Returns the first element of a selection.
     *
     * @param selection the selection
     * @return the first element, or <code>null</code>
     */
    private static Object firstElement(ISelection selection)
    {
        if (selection instanceof IStructuredSelection)
        {
            return ((IStructuredSelection)selection).getFirstElement();
        }
        return null;
    }

    /**
     * Returns the first element of the selection carried by an evaluation context.
     *
     * @param evaluationContext the evaluation context
     * @return the first element, or <code>null</code>
     */
    private static Object firstSelectedElement(Object evaluationContext)
    {
        if (evaluationContext instanceof IEvaluationContext)
        {
            IEvaluationContext context = (IEvaluationContext)evaluationContext;
            Object value = context.getVariable(ISources.ACTIVE_MENU_SELECTION_NAME);
            if (!(value instanceof IStructuredSelection))
            {
                value = context.getVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME);
            }
            if (value instanceof IStructuredSelection)
            {
                return ((IStructuredSelection)value).getFirstElement();
            }
        }
        return null;
    }
}
