/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.handlers;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import ru.aiedt.mcp.server.folders.ui.ClusterNavigatorBridge;

/**
 * Copies the selected clusters' names to the clipboard.
 * <p>
 * Bound to the platform copy command, but active only when every selected element is a cluster node;
 * on anything else EDT's own copy runs instead.
 * </p>
 */
public class CopyClusterCommand
    extends ClusterCommandHandler
{
    @Override
    public void setEnabled(Object evaluationContext)
    {
        setBaseEnabled(allClusterNodes(evaluationContext));
    }

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        ISelection selection = extractSelection(event);
        if (!(selection instanceof IStructuredSelection))
        {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (Object element : ((IStructuredSelection)selection).toList())
        {
            ClusterNavigatorBridge adapter = extractClusterAdapter(element);
            if (adapter == null)
            {
                return null;
            }
            if (text.length() > 0)
            {
                text.append(System.lineSeparator());
            }
            text.append(adapter.getCluster().getName());
        }
        if (text.length() > 0)
        {
            copyToClipboard(HandlerUtil.getActiveShell(event), text.toString());
        }
        return null;
    }

    /**
     * Tells whether the selection is non-empty and every element is a cluster node.
     *
     * @param evaluationContext the evaluation context
     * @return <code>true</code> when the copy should be handled here
     */
    private boolean allClusterNodes(Object evaluationContext)
    {
        IStructuredSelection selection = selectionFromContext(evaluationContext);
        if (selection == null || selection.isEmpty())
        {
            return false;
        }
        for (Object element : selection.toList())
        {
            if (extractClusterAdapter(element) == null)
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Puts text on the system clipboard.
     *
     * @param shell the shell whose display to use; may be <code>null</code>
     * @param text the text to copy
     */
    private static void copyToClipboard(Shell shell, String text)
    {
        Display display = shell != null ? shell.getDisplay() : Display.getCurrent();
        if (display == null)
        {
            return;
        }
        Clipboard clipboard = new Clipboard(display);
        try
        {
            clipboard.setContents(new Object[] {text}, new Transfer[] {TextTransfer.getInstance()});
        }
        finally
        {
            clipboard.dispose();
        }
    }
}
