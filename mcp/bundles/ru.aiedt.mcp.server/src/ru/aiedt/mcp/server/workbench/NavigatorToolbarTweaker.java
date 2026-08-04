/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.workbench;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IPageListener;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.CommonNavigator;

import ru.aiedt.mcp.server.labels.MarkerKeys;

/**
 * Takes the stock Eclipse "Collapse All" button off the EDT Navigator toolbar, so that this plugin's
 * own Expand All / Collapse All / Expand Below buttons are the only tree controls there.
 * <p>
 * The Navigator may already be open when the plugin starts, or open later, in this window or one that
 * has not been created yet - so this watches for all of those: it hides the button on every Navigator
 * showing now, and listens for parts, pages and windows to catch the rest. Hiding is idempotent and
 * re-runs on every activation, which costs nothing and covers the toolbar being rebuilt underneath.
 * </p>
 * <p>
 * Everything here runs on the UI thread - the {@link ru.aiedt.mcp.server.Activator} calls in through
 * an {@code asyncExec} and out through a {@code syncExec}.
 * </p>
 */
public final class NavigatorToolbarTweaker
{
    private static final String COLLAPSE_ALL_ID = "org.eclipse.ui.navigate.collapseAll"; //$NON-NLS-1$

    private static NavigatorToolbarTweaker instance;

    private boolean initialized;

    private IPartListener2 partListener;

    private IWindowListener windowListener;

    private final List<IWorkbenchPage> registeredPages = new ArrayList<>();

    private final Map<IWorkbenchWindow, IPageListener> pageListeners = new HashMap<>();

    private NavigatorToolbarTweaker()
    {
        // Singleton
    }

    /**
     * Returns the customizer, building it the first time it is asked for.
     *
     * @return the single instance
     */
    public static synchronized NavigatorToolbarTweaker getInstance()
    {
        if (instance == null)
        {
            instance = new NavigatorToolbarTweaker();
        }
        return instance;
    }

    /**
     * Starts watching the workbench and hides the button wherever the Navigator is already open. A
     * second call does nothing.
     */
    public void initialize()
    {
        if (initialized)
        {
            return;
        }
        if (!PlatformUI.isWorkbenchRunning())
        {
            return;
        }
        IWorkbench workbench = PlatformUI.getWorkbench();

        partListener = new NavigatorPartListener();

        for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
        {
            registerWindow(window);
        }

        windowListener = new WorkbenchWindowListener();
        workbench.addWindowListener(windowListener);

        for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                hideOnPage(page);
            }
        }

        initialized = true;
    }

    /**
     * Stops watching, unwinding every listener it added. Each removal is guarded on its own, because
     * at shutdown the workbench may already have taken the widgets away. The singleton is cleared, so
     * the next {@link #getInstance()} builds a fresh one.
     */
    public void dispose()
    {
        if (PlatformUI.isWorkbenchRunning() && windowListener != null)
        {
            try
            {
                PlatformUI.getWorkbench().removeWindowListener(windowListener);
            }
            catch (RuntimeException e)
            {
                // The workbench is going.
            }
        }

        for (Map.Entry<IWorkbenchWindow, IPageListener> entry : pageListeners.entrySet())
        {
            try
            {
                entry.getKey().removePageListener(entry.getValue());
            }
            catch (RuntimeException e)
            {
                // The window may be gone.
            }
        }

        for (IWorkbenchPage page : registeredPages)
        {
            try
            {
                if (partListener != null)
                {
                    page.removePartListener(partListener);
                }
            }
            catch (RuntimeException e)
            {
                // The page may be gone.
            }
        }

        pageListeners.clear();
        registeredPages.clear();
        windowListener = null;
        partListener = null;
        initialized = false;
        instance = null;
    }

    /**
     * Attaches to a window: the part listener on every page it has now, and a page listener so pages
     * opened in it later are caught too.
     *
     * @param window the window to attach to
     */
    private void registerWindow(IWorkbenchWindow window)
    {
        for (IWorkbenchPage page : window.getPages())
        {
            addPartListener(page);
        }
        if (!pageListeners.containsKey(window))
        {
            IPageListener pageListener = new WorkbenchPageListener();
            window.addPageListener(pageListener);
            pageListeners.put(window, pageListener);
        }
    }

    /**
     * Detaches from a window that has closed.
     *
     * @param window the window to detach from
     */
    private void unregisterWindow(IWorkbenchWindow window)
    {
        IPageListener pageListener = pageListeners.remove(window);
        if (pageListener != null)
        {
            try
            {
                window.removePageListener(pageListener);
            }
            catch (RuntimeException e)
            {
                // The window may be gone.
            }
        }
        for (IWorkbenchPage page : window.getPages())
        {
            registeredPages.remove(page);
            try
            {
                if (partListener != null)
                {
                    page.removePartListener(partListener);
                }
            }
            catch (RuntimeException e)
            {
                // The page may be gone.
            }
        }
    }

    private void addPartListener(IWorkbenchPage page)
    {
        if (partListener != null && !registeredPages.contains(page))
        {
            page.addPartListener(partListener);
            registeredPages.add(page);
        }
    }

    private void hideOnPage(IWorkbenchPage page)
    {
        IViewPart view = page.findView(MarkerKeys.NAVIGATOR_VIEW_ID);
        if (view != null)
        {
            hideCollapseAll(view);
        }
    }

    private void hideForReference(IWorkbenchPartReference reference)
    {
        if (reference == null || !MarkerKeys.NAVIGATOR_VIEW_ID.equals(reference.getId()))
        {
            return;
        }
        IWorkbenchPart part = reference.getPart(false);
        if (part instanceof IViewPart)
        {
            hideCollapseAll((IViewPart)part);
        }
    }

    /**
     * Hides the stock Collapse All item on a Navigator's toolbar. Does nothing when the view is not a
     * {@link CommonNavigator} or the item is not there; safe to call again and again.
     *
     * @param view the view to hide it on
     */
    private void hideCollapseAll(IViewPart view)
    {
        if (!(view instanceof CommonNavigator))
        {
            return;
        }
        IViewSite site = view.getViewSite();
        if (site == null)
        {
            return;
        }
        IActionBars actionBars = site.getActionBars();
        if (actionBars == null)
        {
            return;
        }
        IToolBarManager toolBarManager = actionBars.getToolBarManager();
        if (toolBarManager == null)
        {
            return;
        }

        for (IContributionItem item : toolBarManager.getItems())
        {
            if (item instanceof ActionContributionItem)
            {
                IAction action = ((ActionContributionItem)item).getAction();
                if (action != null && COLLAPSE_ALL_ID.equals(action.getActionDefinitionId()))
                {
                    item.setVisible(false);
                    toolBarManager.update(true);
                    actionBars.updateActionBars();
                    return;
                }
            }
        }
    }

    /**
     * Hides the button when the Navigator is opened or brought to the front.
     */
    private final class NavigatorPartListener
        implements IPartListener2
    {
        @Override
        public void partOpened(IWorkbenchPartReference partRef)
        {
            hideForReference(partRef);
        }

        @Override
        public void partActivated(IWorkbenchPartReference partRef)
        {
            hideForReference(partRef);
        }
    }

    /**
     * Follows windows as they open and close.
     */
    private final class WorkbenchWindowListener
        implements IWindowListener
    {
        @Override
        public void windowOpened(IWorkbenchWindow window)
        {
            registerWindow(window);
        }

        @Override
        public void windowClosed(IWorkbenchWindow window)
        {
            unregisterWindow(window);
        }

        @Override
        public void windowActivated(IWorkbenchWindow window)
        {
            // Nothing.
        }

        @Override
        public void windowDeactivated(IWorkbenchWindow window)
        {
            // Nothing.
        }
    }

    /**
     * Adds the part listener to pages as they open in a window, and takes it off as they close.
     */
    private final class WorkbenchPageListener
        implements IPageListener
    {
        @Override
        public void pageOpened(IWorkbenchPage page)
        {
            addPartListener(page);
        }

        @Override
        public void pageClosed(IWorkbenchPage page)
        {
            registeredPages.remove(page);
            try
            {
                if (partListener != null)
                {
                    page.removePartListener(partListener);
                }
            }
            catch (RuntimeException e)
            {
                // The page may be gone.
            }
        }

        @Override
        public void pageActivated(IWorkbenchPage page)
        {
            // Nothing.
        }
    }
}
