/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.handlers;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.window.Window;

import ru.aiedt.mcp.server.folders.IClusterManager;
import ru.aiedt.mcp.server.folders.model.Cluster;
import ru.aiedt.mcp.server.folders.model.ClusterStore;
import ru.aiedt.mcp.server.folders.ui.ClusterEditDialog;

/**
 * Renames a cluster and lets its description be edited at the same time, through the Edit Cluster dialog.
 */
public class RenameClusterCommand
    extends ClusterCommandHandler
{
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        ClusterSelection selection = resolveClusterSelection(event);
        if (!selection.isValid())
        {
            return null;
        }
        IClusterManager service = getClusterService();
        if (service == null)
        {
            return null;
        }

        Cluster cluster = selection.cluster;
        IProject project = selection.project;
        ClusterEditDialog dialog = new ClusterEditDialog(selection.shell, cluster,
            name -> validateName(service, project, cluster, name));
        if (dialog.open() == Window.OK)
        {
            service.updateCluster(project, cluster.getFullPath(), dialog.getClusterName(),
                dialog.getClusterDescription());
        }
        return null;
    }

    /**
     * Rejects an empty name, a name with a slash, and a name that would collide with another cluster.
     * The current name is always accepted, so a description-only edit is allowed.
     *
     * @param service the cluster service
     * @param project the project
     * @param cluster the cluster being renamed
     * @param name the proposed name
     * @return an error message, or <code>null</code> when the name is acceptable
     */
    private static String validateName(IClusterManager service, IProject project, Cluster cluster, String name)
    {
        if (name == null || name.isEmpty())
        {
            return "The name must not be empty."; //$NON-NLS-1$
        }
        if (name.contains("/")) //$NON-NLS-1$
        {
            return "The name must not contain a slash."; //$NON-NLS-1$
        }
        if (name.equals(cluster.getName()))
        {
            return null;
        }
        ClusterStore storage = service.getClusterStorage(project);
        if (storage.getClusterByFullPath(buildFullPath(cluster.getPath(), name)) != null)
        {
            return "A cluster with this name already exists here."; //$NON-NLS-1$
        }
        return null;
    }
}
