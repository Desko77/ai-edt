/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.navigation;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.navigator.CommonViewer;

import ru.aiedt.mcp.server.labels.MarkerKeys;

/**
 * The Expand Below button on the EDT Navigator toolbar: opens everything under whatever is selected.
 * <p>
 * Unlike its two siblings this one acts on a selection, so it is only enabled when there is one (the
 * <code>enabledWhen</code> in <code>plugin.xml</code>), and it walks each selected element open to
 * the bottom. An empty or non-structured selection is nothing to do, not an error.
 * </p>
 */
public class ExpandSubtreeCommand
    extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window == null)
        {
            return null;
        }
        IWorkbenchPage page = window.getActivePage();
        if (page == null)
        {
            return null;
        }
        IViewPart view = page.findView(MarkerKeys.NAVIGATOR_VIEW_ID);
        if (!(view instanceof CommonNavigator))
        {
            return null;
        }
        CommonViewer viewer = ((CommonNavigator)view).getCommonViewer();
        if (viewer == null || viewer.getControl() == null || viewer.getControl().isDisposed())
        {
            return null;
        }

        ISelection selection = viewer.getSelection();
        if (!(selection instanceof IStructuredSelection))
        {
            return null;
        }
        IStructuredSelection structured = (IStructuredSelection)selection;
        if (structured.isEmpty())
        {
            return null;
        }
        for (Object element : structured.toList())
        {
            viewer.expandToLevel(element, TreeViewer.ALL_LEVELS);
        }
        return null;
    }
}
