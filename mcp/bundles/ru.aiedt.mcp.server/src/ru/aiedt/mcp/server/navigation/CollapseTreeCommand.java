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
 * The Collapse All button on the EDT Navigator toolbar: closes every node back to the roots.
 * <p>
 * The same preamble as its two siblings, and the same policy of giving up quietly wherever the view
 * is not there to act on. It tests the tree rather than the control for disposal - the same widget by
 * a different accessor, an inconsistency kept on purpose so the three handlers stay line-for-line
 * comparable.
 * </p>
 */
public class CollapseTreeCommand
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
        if (viewer == null || viewer.getTree() == null || viewer.getTree().isDisposed())
        {
            return null;
        }
        viewer.collapseAll();
        return null;
    }
}
