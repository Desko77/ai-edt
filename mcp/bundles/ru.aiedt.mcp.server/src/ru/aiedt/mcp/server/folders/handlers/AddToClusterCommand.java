/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.handlers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.ISources;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.handlers.HandlerUtil;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.folders.ClusterWriteOutcome;
import ru.aiedt.mcp.server.folders.IClusterManager;
import ru.aiedt.mcp.server.folders.model.Cluster;
import ru.aiedt.mcp.server.labels.MarkerHelpers;

/**
 * Adds the selected metadata objects to a cluster the user picks.
 * <p>
 * Each object is moved into the chosen cluster, out of any cluster it was already in. A selection
 * that spans projects is split: each project's objects are moved into a cluster of that project,
 * picked per project.
 * </p>
 */
public class AddToClusterCommand
    extends AbstractHandler
{
    @Override
    public void setEnabled(Object evaluationContext)
    {
        setBaseEnabled(hasMetadataObject(structuredSelection(evaluationContext)));
    }

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        IStructuredSelection selection = structuredSelection(activeSelection(event));
        List<EObject> objects = metadataObjects(selection);
        if (objects.isEmpty())
        {
            return null;
        }
        IClusterManager service = Activator.getClusterServiceStatic();
        if (service == null)
        {
            return null;
        }
        Shell shell = HandlerUtil.getActiveShell(event);
        // A selection can span projects - a configuration beside an extension. Each project's
        // objects go into that project's clusters: taking the project of the first selected
        // object wrote the second project's objects into the first project's clusters file.
        for (Map.Entry<IProject, List<EObject>> each : groupedBy(objects, MarkerHelpers::extractProject)
            .entrySet())
        {
            addToClusters(service, shell, each.getKey(), each.getValue());
        }
        return null;
    }

    /**
     * Moves one project's objects into a cluster of that project the user picks.
     *
     * @param service the cluster service
     * @param shell the parent shell for the picker
     * @param project the project the objects and the clusters belong to
     * @param objects the selected objects of that project, never empty
     */
    private static void addToClusters(IClusterManager service, Shell shell, IProject project,
        List<EObject> objects)
    {
        List<Cluster> clusters = service.getAllClusters(project);
        if (clusters.isEmpty())
        {
            MessageDialog.openInformation(shell, "Add to Cluster", //$NON-NLS-1$
                "There are no clusters yet. Create one from a collection folder first."); //$NON-NLS-1$
            return;
        }
        Cluster target = chooseCluster(shell, clusters);
        if (target == null)
        {
            return;
        }
        for (EObject object : objects)
        {
            String fqn = MarkerHelpers.extractFqn(object);
            if (fqn == null || isAlreadyIn(service.findClusterForObject(project, fqn), target))
            {
                continue;
            }
            ClusterWriteOutcome outcome = service.addObjectToCluster(project, fqn, target.getFullPath());
            if (outcome.isRefused())
            {
                ClusterCommandHandler.showSaveFailure(shell, project, outcome);
                break;
            }
        }
    }

    /**
     * Splits items by the key an extractor reads from each, keeping the order both of the groups
     * and of the items inside them. An item with no key is dropped: it belongs to no project the
     * command can write to.
     *
     * @param <K> the key type, the project in the command
     * @param <T> the item type, the metadata object in the command
     * @param items the items to split
     * @param keyOf reads the key of one item; {@code null} drops the item
     * @return the items grouped by key, in first-seen order
     */
    static <K, T> Map<K, List<T>> groupedBy(List<T> items, Function<T, K> keyOf)
    {
        Map<K, List<T>> grouped = new LinkedHashMap<>();
        for (T item : items)
        {
            K key = keyOf.apply(item);
            if (key == null)
            {
                continue;
            }
            grouped.computeIfAbsent(key, missing -> new ArrayList<>()).add(item);
        }
        return grouped;
    }

    /**
     * Whether an object already sits in the chosen cluster, so there is nothing to move and nothing
     * to save.
     *
     * @param current the cluster the object is in, or <code>null</code>
     * @param target the chosen cluster
     * @return <code>true</code> when both are the same cluster
     */
    static boolean isAlreadyIn(Cluster current, Cluster target)
    {
        return current != null && target != null && current.getFullPath().equals(target.getFullPath());
    }

    /**
     * Opens a picker over the project's clusters.
     *
     * @param shell the parent shell
     * @param clusters the clusters to choose from
     * @return the chosen cluster, or <code>null</code> if the picker was cancelled
     */
    private static Cluster chooseCluster(Shell shell, List<Cluster> clusters)
    {
        ILabelProvider labelProvider = new LabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return element instanceof Cluster ? ((Cluster)element).getFullPath() : super.getText(element);
            }
        };
        ElementListSelectionDialog dialog = new ElementListSelectionDialog(shell, labelProvider);
        dialog.setTitle("Add to Cluster"); //$NON-NLS-1$
        dialog.setMessage("Choose a target cluster:"); //$NON-NLS-1$
        dialog.setElements(clusters.toArray());
        if (dialog.open() == Window.OK)
        {
            Object result = dialog.getFirstResult();
            return result instanceof Cluster ? (Cluster)result : null;
        }
        return null;
    }

    /**
     * Collects the metadata objects out of a selection.
     *
     * @param selection the selection; may be <code>null</code>
     * @return the selected metadata objects, never <code>null</code>
     */
    private static List<EObject> metadataObjects(IStructuredSelection selection)
    {
        List<EObject> objects = new ArrayList<>();
        if (selection != null)
        {
            for (Object element : selection.toList())
            {
                if (element instanceof EObject)
                {
                    objects.add((EObject)element);
                }
            }
        }
        return objects;
    }

    /**
     * Tells whether a selection holds at least one metadata object.
     *
     * @param selection the selection; may be <code>null</code>
     * @return <code>true</code> if there is one
     */
    private static boolean hasMetadataObject(IStructuredSelection selection)
    {
        if (selection == null)
        {
            return false;
        }
        for (Object element : selection.toList())
        {
            if (element instanceof EObject)
            {
                return true;
            }
        }
        return false;
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
