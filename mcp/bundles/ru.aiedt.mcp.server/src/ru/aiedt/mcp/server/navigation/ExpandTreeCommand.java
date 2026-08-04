/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.navigation;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.navigator.CommonViewer;

import ru.aiedt.mcp.server.labels.MarkerKeys;

/**
 * The Expand All button on the EDT Navigator toolbar: opens every node in the tree.
 * <p>
 * It reaches the Navigator through the workbench rather than being handed it, and gives up quietly at
 * every step where the view might not be there - no window, no page, the view not open, or its tree
 * already gone. Command handlers run on the UI thread, so the tree can be touched directly.
 * </p>
 */
public class ExpandTreeCommand
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
        viewer.expandAll();
        return null;
    }
}
