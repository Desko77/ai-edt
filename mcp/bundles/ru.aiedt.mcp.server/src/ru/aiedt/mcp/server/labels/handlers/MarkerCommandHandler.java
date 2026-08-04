/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.bm.core.IBmObject;

import ru.aiedt.mcp.server.labels.MarkerHelpers;

/**
 * Shared plumbing for the marker command handlers: reading the selected metadata object, its project and
 * its FQN out of the current selection.
 */
public abstract class MarkerCommandHandler
    extends AbstractHandler
{
    /**
     * Returns the metadata object behind the first selected element.
     *
     * @param event the command event
     * @return the object, or <code>null</code> when nothing suitable is selected
     */
    protected EObject getSelectedMdObject(ExecutionEvent event)
    {
        Object element = getFirstElement(event);
        return element != null ? MarkerHelpers.extractMdObject(element) : null;
    }

    /**
     * Returns the project of the first selected element.
     *
     * @param event the command event
     * @return the project, or <code>null</code> when it cannot be resolved
     */
    protected IProject getSelectedProject(ExecutionEvent event)
    {
        Object element = getFirstElement(event);
        return element != null ? MarkerHelpers.extractProjectFromElement(element) : null;
    }

    /**
     * Returns the FQN of a metadata object, preferring the BM engine's own answer.
     *
     * @param eObject the object
     * @return the FQN, or <code>null</code> when one cannot be built
     */
    protected String extractFqn(EObject eObject)
    {
        if (eObject instanceof IBmObject)
        {
            return MarkerHelpers.extractFqn((IBmObject)eObject);
        }
        return MarkerHelpers.extractFqn(eObject);
    }

    /**
     * Returns the first element of the current structured selection.
     *
     * @param event the command event
     * @return the first selected element, or <code>null</code>
     */
    private static Object getFirstElement(ExecutionEvent event)
    {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        if (selection instanceof IStructuredSelection)
        {
            return ((IStructuredSelection)selection).getFirstElement();
        }
        return null;
    }
}
