/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.handlers;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;

import ru.aiedt.mcp.server.folders.IClusterManager;
import ru.aiedt.mcp.server.folders.model.Cluster;

/**
 * Deletes a cluster after confirmation. Its objects return to their normal place in the collection.
 */
public class DeleteClusterCommand
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
        boolean confirmed = MessageDialog.openConfirm(selection.shell, "Delete Cluster", //$NON-NLS-1$
            "Delete the cluster '" + cluster.getName() //$NON-NLS-1$
                + "'? Its objects will return to their normal location."); //$NON-NLS-1$
        if (confirmed)
        {
            service.deleteCluster(selection.project, cluster.getFullPath());
        }
        return null;
    }
}
