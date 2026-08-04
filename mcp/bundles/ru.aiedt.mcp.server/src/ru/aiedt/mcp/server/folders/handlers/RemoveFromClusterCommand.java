/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.handlers;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.ISources;
import org.eclipse.ui.handlers.HandlerUtil;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.folders.IClusterManager;
import ru.aiedt.mcp.server.labels.MarkerHelpers;

/**
 * Removes the selected objects from their clusters, returning each to its normal place in the tree.
 */
public class RemoveFromClusterCommand
    extends AbstractHandler
{
    /**
     * A clustered object: its project and its fully qualified name.
     */
    private record ObjectInCluster(IProject project, String fqn)
    {
    }

    @Override
    public void setEnabled(Object evaluationContext)
    {
        setBaseEnabled(!clusteredObjects(structuredSelection(evaluationContext)).isEmpty());
    }

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        List<ObjectInCluster> targets = clusteredObjects(structuredSelection(activeSelection(event)));
        if (targets.isEmpty())
        {
            return null;
        }
        IClusterManager service = Activator.getClusterServiceStatic();
        if (service == null)
        {
            return null;
        }
        for (ObjectInCluster target : targets)
        {
            service.removeObjectFromCluster(target.project(), target.fqn());
        }
        return null;
    }

    /**
     * Collects the selected objects that are currently in a cluster.
     *
     * @param selection the selection; may be <code>null</code>
     * @return the clustered objects, never <code>null</code>
     */
    private static List<ObjectInCluster> clusteredObjects(IStructuredSelection selection)
    {
        List<ObjectInCluster> result = new ArrayList<>();
        if (selection == null)
        {
            return result;
        }
        IClusterManager service = Activator.getClusterServiceStatic();
        if (service == null)
        {
            return result;
        }
        for (Object element : selection.toList())
        {
            if (!(element instanceof EObject))
            {
                continue;
            }
            EObject eObject = (EObject)element;
            IProject project = MarkerHelpers.extractProject(eObject);
            String fqn = MarkerHelpers.extractFqn(eObject);
            if (project != null && fqn != null && service.findClusterForObject(project, fqn) != null)
            {
                result.add(new ObjectInCluster(project, fqn));
            }
        }
        return result;
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
     * Extracts a structured selection from a selection or an evaluation context.
     *
     * @param source an {@link ISelection} or an {@link IEvaluationContext}
     * @return the structured selection, or <code>null</code>
     */
    private static IStructuredSelection structuredSelection(Object source)
    {
        if (source instanceof IStructuredSelection)
        {
            return (IStructuredSelection)source;
        }
        if (source instanceof IEvaluationContext)
        {
            IEvaluationContext context = (IEvaluationContext)source;
            Object value = context.getVariable(ISources.ACTIVE_MENU_SELECTION_NAME);
            if (!(value instanceof IStructuredSelection))
            {
                value = context.getVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME);
            }
            if (value instanceof IStructuredSelection)
            {
                return (IStructuredSelection)value;
            }
        }
        return null;
    }
}
