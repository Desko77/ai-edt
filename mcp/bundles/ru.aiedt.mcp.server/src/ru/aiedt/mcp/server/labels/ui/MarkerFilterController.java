/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.ui;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.State;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.RegistryToggleState;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.navigator.CommonViewer;

import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.labels.MarkerKeys;
import ru.aiedt.mcp.server.labels.model.Marker;

/**
 * Drives the "Filter by Marker" feature: it opens the dialog, applies or clears a shared
 * {@link MarkerQueryFilter} on the EDT Navigator, and keeps the toolbar toggle in step.
 * <p>
 * The chosen markers and the unmarked-only flag are remembered between opens, but a fresh EDT session
 * always starts unfiltered - the constructor resets the toggle off, since a filter is transient
 * session state and should not carry over.
 * </p>
 */
public class MarkerFilterController
{
    /** The id of the toggle command whose checked state mirrors the filter. */
    private static final String FILTER_COMMAND_ID = "ru.aiedt.mcp.server.labels.filterByMarker"; //$NON-NLS-1$

    /** The tree depth the Navigator is expanded to after the filter is applied. */
    private static final int EXPAND_LEVEL = 3;

    private static MarkerFilterController instance;

    private final Map<IProject, Set<Marker>> selectedMarkers = new HashMap<>();

    private boolean showUnmarkedOnly;

    private boolean filterActive;

    private MarkerQueryFilter sharedFilter;

    /**
     * Builds the manager and schedules the toggle to start off.
     */
    private MarkerFilterController()
    {
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
        {
            display.asyncExec(() -> updateToggleState(false));
        }
    }

    /**
     * Returns the shared manager, creating it on first use.
     *
     * @return the manager
     */
    public static synchronized MarkerFilterController getInstance()
    {
        if (instance == null)
        {
            instance = new MarkerFilterController();
        }
        return instance;
    }

    /**
     * Opens the filter dialog on the UI thread and applies the result.
     */
    public void openFilterDialog()
    {
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
        {
            display.asyncExec(this::doOpenFilterDialog);
        }
    }

    /**
     * Returns a copy of the markers currently selected per project.
     *
     * @return the selected markers
     */
    public Map<IProject, Set<Marker>> getSelectedMarkers()
    {
        return new HashMap<>(selectedMarkers);
    }

    /**
     * Opens the dialog and reacts to how it was closed.
     */
    private void doOpenFilterDialog()
    {
        Shell shell = getActiveShell();
        if (shell == null)
        {
            return;
        }
        IV8ProjectManager projectManager = getProjectManager();
        if (projectManager == null)
        {
            return;
        }
        MarkerFilterDialog dialog = new MarkerFilterDialog(shell, projectManager);
        dialog.setInitialSelection(selectedMarkers);
        dialog.setInitialShowUnmarkedOnly(showUnmarkedOnly);

        int result = dialog.open();
        if (dialog.isTurnedOff())
        {
            deactivate();
            return;
        }
        if (result == Window.OK)
        {
            selectedMarkers.clear();
            selectedMarkers.putAll(dialog.getSelectedMarkers());
            showUnmarkedOnly = dialog.isShowUnmarkedOnly();
            if (dialog.isFilterEnabled())
            {
                activate();
            }
            else
            {
                deactivate();
            }
        }
        else
        {
            // Cancelled: leave the filter as it was but resync the toggle to reality.
            updateToggleState(filterActive);
        }
    }

    /**
     * Applies the filter to the Navigator and expands the tree to reveal the matches.
     */
    private void activate()
    {
        CommonViewer viewer = getNavigatorViewer();
        if (viewer == null)
        {
            return;
        }
        MarkerQueryFilter filter = getSharedFilter();
        if (showUnmarkedOnly)
        {
            filter.setShowUnmarkedOnly(true);
        }
        else
        {
            filter.setSelectedMarkersMode(selectedMarkers);
        }
        if (!hasFilter(viewer, filter))
        {
            viewer.addFilter(filter);
        }
        viewer.refresh();
        viewer.expandToLevel(EXPAND_LEVEL);
        filterActive = true;
        updateToggleState(true);
    }

    /**
     * Removes the filter from the Navigator and restores the full tree.
     */
    private void deactivate()
    {
        CommonViewer viewer = getNavigatorViewer();
        if (viewer != null && sharedFilter != null && hasFilter(viewer, sharedFilter))
        {
            viewer.removeFilter(sharedFilter);
            viewer.refresh();
        }
        if (sharedFilter != null)
        {
            sharedFilter.clearSelectedMarkersMode();
        }
        filterActive = false;
        updateToggleState(false);
    }

    /**
     * Returns the shared filter instance, creating it on first use.
     *
     * @return the shared filter
     */
    private MarkerQueryFilter getSharedFilter()
    {
        if (sharedFilter == null)
        {
            sharedFilter = new MarkerQueryFilter();
        }
        return sharedFilter;
    }

    /**
     * Tells whether a viewer already carries the given filter.
     *
     * @param viewer the viewer
     * @param filter the filter
     * @return <code>true</code> when it is already installed
     */
    private static boolean hasFilter(CommonViewer viewer, ViewerFilter filter)
    {
        for (ViewerFilter installed : viewer.getFilters())
        {
            if (installed == filter)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Sets the toolbar toggle command's checked state.
     *
     * @param on whether the filter is on
     */
    private void updateToggleState(boolean on)
    {
        if (!PlatformUI.isWorkbenchRunning())
        {
            return;
        }
        ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commandService == null)
        {
            return;
        }
        Command command = commandService.getCommand(FILTER_COMMAND_ID);
        State state = command.getState(RegistryToggleState.STATE_ID);
        if (state != null)
        {
            state.setValue(Boolean.valueOf(on));
            commandService.refreshElements(FILTER_COMMAND_ID, null);
        }
    }

    /**
     * Finds the EDT Navigator's viewer.
     *
     * @return the viewer, or <code>null</code> when the Navigator is not open
     */
    private CommonViewer getNavigatorViewer()
    {
        if (!PlatformUI.isWorkbenchRunning())
        {
            return null;
        }
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
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
        if (view instanceof CommonNavigator)
        {
            return ((CommonNavigator)view).getCommonViewer();
        }
        return null;
    }

    /**
     * Returns the active shell, or <code>null</code>.
     *
     * @return the active shell
     */
    private static Shell getActiveShell()
    {
        if (!PlatformUI.isWorkbenchRunning())
        {
            return null;
        }
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        return window != null ? window.getShell() : null;
    }

    /**
     * Returns the project manager, or <code>null</code> when the plugin is not running.
     *
     * @return the project manager
     */
    private static IV8ProjectManager getProjectManager()
    {
        Activator activator = Activator.getDefault();
        return activator != null ? activator.getV8ProjectManager() : null;
    }
}
