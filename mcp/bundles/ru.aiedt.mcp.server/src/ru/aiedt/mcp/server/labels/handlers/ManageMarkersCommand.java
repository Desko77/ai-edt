/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.handlers;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import ru.aiedt.mcp.server.labels.ui.MarkerManagerDialog;

/**
 * Opens the "Manage Markers" dialog for the selected metadata object.
 */
public class ManageMarkersCommand
    extends MarkerCommandHandler
{
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        EObject mdObject = getSelectedMdObject(event);
        IProject project = getSelectedProject(event);
        if (project == null || mdObject == null)
        {
            return null;
        }
        String fqn = extractFqn(mdObject);
        if (fqn == null)
        {
            return null;
        }
        Shell shell = HandlerUtil.getActiveShell(event);
        new MarkerManagerDialog(shell, project, fqn).open();
        return null;
    }
}
